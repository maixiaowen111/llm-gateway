package com.example.gateway.provider;

import com.example.gateway.dto.ChatCompletionChunk;
import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.dto.ChatCompletionResponse;
import com.example.gateway.exception.UpstreamIdleTimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>G3c 的验收物：一个协议真的不一样的渠道。</b>
 *
 * <p><b>它为什么必须存在 —— 先看 3b 留下的那个逻辑漏洞</b>：
 * 3b 证明了"同一套 OpenAI 兼容协议下，加厂商 = 改 4 行 YAML"。
 * 这个结论很漂亮，但它<b>只覆盖了一类厂商</b>。
 * 如果所有上游都是 OpenAI 兼容的，那 {@link Provider} 这个接口
 * <b>确实可能只是一个多余的层</b> —— 反正一份实现类就能吃掉全部渠道。
 *
 * <p>Anthropic Messages API 就是反例，而且差异是<b>结构性的、配置表达不了的</b>：
 * <pre>
 *                    OpenAI 兼容                  Anthropic Messages
 *   路径             POST /chat/completions        POST /v1/messages
 *   鉴权             Authorization: Bearer xxx     x-api-key: xxx
 *   版本头           无                            anthropic-version: 2023-06-01
 *   请求体           messages 里可含 system        system 是顶层字段，messages 只收 user/assistant
 *   最大输出         可选 max_tokens               <b>必填</b> max_tokens
 *   响应内容         choices[0].message.content    content: [{type:"text", text:"..."}]
 *   结束原因         finish_reason                  stop_reason（end_turn / max_tokens）
 *   流式协议         data: {...}                    event: 事件名 + data: {...}
 * </pre>
 * <b>八处差异里没有一处能靠 YAML 填出来。</b>这不是"配置项没设计好"，
 * 是两个协议真的不同 —— 协议转换是代码，不是配置。
 *
 * <p><b>★ 本类存在的意义，是证明"抽象不是多余的" ★</b>
 * <p>写法上它只实现 {@link Provider} 的两个方法，<b>对 {@code ChatController}、
 * {@code ProviderRegistry}、DTO 一行都不碰</b>：
 * 客户端依然发 OpenAI 格式、拿 OpenAI 格式；Anthropic 的
 * {@code content[]}、{@code stop_reason}、{@code x-api-key} 全部被关在这个类里。
 * <p>面试可讲的那句话是：
 * <blockquote><b>SPI 的意义不是"支持多家"，是"加第三家的时候不用改已有代码"。</b></blockquote>
 * 3b 让这句话少了半句（只证明了同协议）；本类把它补全。
 *
 * <p><b>⚠️ 一个刻意不做的取舍：工具调用（tools / function calling）没有映射</b>
 * <p>G1 的 DTO 只收了 text。把 tools 两边的结构也翻译过去不是做不到，
 * 而是<b>没有测试就无法声称它是对的</b>，而错误地把工具调用透传给模型
 * 比"直接不支持"危险得多（Agent 会静默地调错工具）。
 * <b>不做的时候要说"没做"，不能说"支持"。</b>
 */
public class AnthropicMessagesProvider implements Provider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessagesProvider.class);

    /** Anthropic 目前要求显式声明 API 版本；写成常量而不是配置项，因为它是协议的一部分 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String name;
    private final WebClient webClient;
    private final String apiKey;
    private final String defaultModel;
    private final int defaultMaxTokens;
    private final long idleTimeoutMs;
    private final ObjectMapper objectMapper;

    public AnthropicMessagesProvider(String name,
                                     String baseUrl,
                                     String apiKey,
                                     String defaultModel,
                                     int defaultMaxTokens,
                                     long idleTimeoutMs,
                                     WebClient.Builder builder,
                                     ObjectMapper objectMapper) {
        this.name = name;
        this.webClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.defaultMaxTokens = defaultMaxTokens;
        this.idleTimeoutMs = idleTimeoutMs;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        String model = resolveModel(request);

        return webClient.post()
                .uri("/v1/messages")
                // ★ 这两个头是 Anthropic 与 OpenAI 兼容协议最直观的差异。
                //   注意 x-api-key 不是 Authorization —— 用 Bearer 过去会得到 401，
                //   而且错误信息只说 "invalid x-api-key"，不会告诉你"你该用另一个头"。
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildUpstreamBody(request, model, false))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnNext(raw -> log.info("[上游原始响应] channel={} provider=anthropic {}", name, raw))
                .map(raw -> normalize(raw, model));
    }

    @Override
    public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
        String model = resolveModel(request);

        // ★ 每次订阅一份独立状态：promptTokens 在 message_start 里，
        //   而 output_tokens 要到 message_delta 才给。把两半拼成一个完整的
        //   Usage 需要跨事件记状态 —— 但绝不能记在字段里，否则两个并发流会互相污染。
        //   Flux.defer 让"这次订阅的状态"有明确的生命周期。
        return Flux.defer(() -> {
            AtomicReference<Integer> promptTokens = new AtomicReference<>(0);

            return webClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(buildUpstreamBody(request, model, true))
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL) {
                            log.info("[cancel] channel={} provider=anthropic 客户端断开，上游连接将被关闭", name);
                        } else {
                            log.info("[stream-end] channel={} provider=anthropic signal={}", name, signal);
                        }
                    })
                    // G2-2d 的铁律：逐块空闲超时，不是整条流的总时长
                    .timeout(Duration.ofMillis(idleTimeoutMs))
                    .onErrorMap(TimeoutException.class,
                            e -> new UpstreamIdleTimeoutException(idleTimeoutMs, e))
                    // concatMap 保序：SSE 事件的顺序就是语义，flatMap 会把字打乱
                    .concatMap(event -> normalizeEventSafely(event, model, promptTokens));
        });
    }

    /**
     * 把网关契约翻译成 Anthropic 的请求体。
     *
     * <p><b>三个必须做的转换</b>（少一个请求就会 400）：
     * <ol>
     *   <li><b>system 提到顶层</b>：Anthropic 的 messages 只接受 user / assistant，
     *       role=system 放进去会被拒；</li>
     *   <li><b>max_tokens 必填</b>：OpenAI 里它是可选的，Anthropic 里缺了直接 400；</li>
     *   <li><b>只发 content 文本</b>：OpenAI 的 content 可能是数组（多模态），
     *       这个 DTO 只收了 String —— 多模态本来就没支持，这里如实只发文本。</li>
     * </ol>
     *
     * <p><b>★ 为什么这个方法是"翻译"而不是"拼 JSON" ★</b>
     * <p>它的输入是<b>网关自己的契约</b>，输出是<b>Anthropic 的契约</b>。
     * 两年后要接第四家协议时，会再出现一个类似的方法 ——
     * 但 {@code ChatController} 永远不需要知道这些字段名。
     */
    private Map<String, Object> buildUpstreamBody(ChatCompletionRequest request,
                                                  String model,
                                                  boolean stream) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> messages = new ArrayList<>();

        if (request.messages() != null) {
            for (ChatCompletionRequest.Message message : request.messages()) {
                if ("system".equalsIgnoreCase(message.role())) {
                    if (!system.isEmpty()) {
                        system.append("\n\n");
                    }
                    system.append(message.content());
                } else {
                    messages.add(Map.of(
                            "role", message.role(),
                            "content", message.content() == null ? "" : message.content()));
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        body.put("messages", messages);
        // ★ 必填。客户端没给就用渠道默认值 —— 这是网关该做的兜底，
        //   而不是把一个 400 原样甩给不知道 Anthropic 规则的调用方。
        body.put("max_tokens", request.maxTokens() == null ? defaultMaxTokens : request.maxTokens());
        body.put("stream", stream);
        return body;
    }

    private ChatCompletionResponse normalize(JsonNode raw, String requestedModel) {
        JsonNode usage = raw.path("usage");
        int promptTokens = usage.path("input_tokens").asInt();
        int completionTokens = usage.path("output_tokens").asInt();

        return new ChatCompletionResponse(
                raw.path("id").asText(),
                "chat.completion",
                // Anthropic 的响应没有 created 字段。用当前时间补一个 ——
                // 客户端只拿它做排序/展示，不参与业务判断。
                Instant.now().getEpochSecond(),
                // 和 OpenAI 兼容实现同一条铁律：报客户端请求的模型，不报上游的
                requestedModel,
                List.of(new ChatCompletionResponse.Choice(
                        0,
                        new ChatCompletionResponse.Message("assistant", extractText(raw.path("content"))),
                        mapStopReason(raw.path("stop_reason").asText()))),
                new ChatCompletionResponse.Usage(
                        promptTokens, completionTokens, promptTokens + completionTokens));
    }

    /**
     * 把 Anthropic 的 {@code content: [{type:"text", text:"..."}]} 拍平成一段文本。
     *
     * <p><b>为什么要跳过非 text 块而不是直接拼 {@code asText()}</b>：
     * 以后上游可能返回 tool_use 块（结构完全不同）。直接 asText() 会得到空串，
     * 用户看到"模型什么也没说"却查不出原因；跳过并留下日志至少指得清方向。
     */
    private String extractText(JsonNode content) {
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                } else {
                    log.warn("[Anthropic 非文本内容块已忽略] channel={} type={}",
                            name, block.path("type").asText());
                }
            }
        }
        return text.toString();
    }

    /**
     * Anthropic 的 stop_reason → OpenAI 的 finish_reason。
     *
     * <p>不做映射的后果不是报错，是<b>客户端把"被长度截断"当成"正常说完了"</b> ——
     * 用户会看到一句说到一半的话，而这比抛异常难查得多。
     */
    private String mapStopReason(String anthropicReason) {
        return switch (anthropicReason) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            default -> anthropicReason;
        };
    }

    /**
     * 单个 SSE 事件 → 网关契约的块。空返回表示"这个事件不产生数据块"。
     *
     * <p><b>Anthropic 流式事件类型</b>（本方法只处理其中三种）：
     * <pre>
     *   message_start          消息开始，带 input_tokens 和角色
     *   content_block_start    内容块开始（本类只支持 text）
     *   content_block_delta    ★ 真正的增量文本
     *   message_delta          结束原因 + output_tokens
     *   message_stop           结束哨兵
     *   ping                   保活
     * </pre>
     * <b>不认识的事件必须忽略而不是抛异常</b>：Anthropic 会新增事件类型，
     * 网关因为"多了个 type"把整条流打断，是不可接受的脆弱。
     */
    private Flux<ChatCompletionChunk> normalizeEventSafely(String data,
                                                          String requestedModel,
                                                          AtomicReference<Integer> promptTokens) {
        if (!StringUtils.hasText(data) || ChatCompletionChunk.DONE_SENTINEL.equals(data)) {
            return Flux.empty();
        }

        try {
            JsonNode event = objectMapper.readTree(data);
            String type = event.path("type").asText();

            return switch (type) {
                case "message_start" -> {
                    JsonNode message = event.path("message");
                    promptTokens.set(message.path("usage").path("input_tokens").asInt());
                    yield Flux.just(chunk(message.path("id").asText(), requestedModel,
                            new ChatCompletionChunk.Delta("assistant", ""), null, null));
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.path("delta");
                    if (!"text_delta".equals(delta.path("type").asText())) {
                        yield Flux.empty();
                    }
                    yield Flux.just(chunk("", requestedModel,
                            new ChatCompletionChunk.Delta(null, delta.path("text").asText()),
                            null, null));
                }
                case "message_delta" -> {
                    JsonNode usage = event.path("usage");
                    int output = usage.path("output_tokens").asInt();
                    int input = promptTokens.get() == null ? 0 : promptTokens.get();
                    // Anthropic 的 input_tokens 在 message_start、output_tokens 在
                    // message_delta —— 两半要在最后拼成一个完整的 Usage 交给计费。
                    // 少了 input 那一半，预扣和结算就会对不上账。
                    yield Flux.just(chunk("", requestedModel,
                            new ChatCompletionChunk.Delta(null, null),
                            mapStopReason(event.path("delta").path("stop_reason").asText()),
                            new ChatCompletionResponse.Usage(input, output, input + output)));
                }
                default -> Flux.empty();
            };
        } catch (Exception e) {
            // 单块坏了不拖垮整条流 —— 和 OpenAI 兼容实现同一个取舍
            log.warn("[Anthropic 事件解析失败，已跳过] channel={} data={}", name, data, e);
            return Flux.empty();
        }
    }

    private ChatCompletionChunk chunk(String id,
                                      String requestedModel,
                                      ChatCompletionChunk.Delta delta,
                                      String finishReason,
                                      ChatCompletionResponse.Usage usage) {
        return new ChatCompletionChunk(
                id,
                ChatCompletionChunk.OBJECT,
                Instant.now().getEpochSecond(),
                requestedModel,
                List.of(new ChatCompletionChunk.Choice(0, delta, finishReason)),
                usage);
    }

    private String resolveModel(ChatCompletionRequest request) {
        return StringUtils.hasText(request.model()) ? request.model() : defaultModel;
    }
}
