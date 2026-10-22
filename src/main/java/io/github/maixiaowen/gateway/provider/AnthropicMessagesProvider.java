package io.github.maixiaowen.gateway.provider;

import io.github.maixiaowen.gateway.dto.ChatCompletionChunk;
import io.github.maixiaowen.gateway.dto.ChatCompletionRequest;
import io.github.maixiaowen.gateway.dto.ChatCompletionResponse;
import io.github.maixiaowen.gateway.dto.Tool;
import io.github.maixiaowen.gateway.dto.ToolCall;
import io.github.maixiaowen.gateway.exception.UpstreamIdleTimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p><b>★ G12：工具调用（Function Calling）的完整翻译 ★</b>
 * <p>两边对"工具"的表达方式完全不同：
 * <pre>
 *                    OpenAI                              Anthropic
 *   工具定义          {"type":"function",                 {"name":...,
 *                     "function":{name,description,       "description":...,
 *                                 parameters}}              "input_schema":...}
 *   模型发起调用      message.tool_calls[]                 content[] 里的 {"type":"tool_use",
 *                                                           "id","name","input":{...}}
 *   回填执行结果      role="tool" + tool_call_id            role="user" + {"type":"tool_result",
 *                                                           "tool_use_id","content"}
 *   结束原因          finish_reason="tool_calls"            stop_reason="tool_use"
 *   流式参数          tool_calls[].function.arguments       input_json_delta.partial_json
 *                     （字符串片段，按 index 拼）             （字符串片段，按 block index 拼）
 * </pre>
 * <b>五处差异，没有一处能靠 YAML 表达。</b>更关键的是第三行：
 * Anthropic 的 {@code messages} 里<b>没有 tool 角色</b>，工具结果必须包成
 * user 消息里的 {@code tool_result} 块 —— 直接透传会让第二轮请求 400。
 *
 * <p><b>★ 为什么"错误翻译"比"不支持"更危险 ★</b>
 * <p>不支持时，客户端至少能收到 400 或者拿到空 tool_calls，问题当场暴露；
 * 翻译错了（比如把 {@code arguments} 字符串原样塞进 {@code input}），
 * 模型会收到一个字符串而不是对象，然后<b>静默地调用一个参数全错的工作</b>。
 * 所以本类的每一处翻译都由 {@code AnthropicProviderTest} 里的契约断言锁住。
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
            // ★ 工具调用的流式状态必须"每次订阅一份" ★
            //   Anthropic 用 content block index 标识内容块，OpenAI 用 tool_calls index
            //   标识工具调用 —— 两者不是一回事（一次回复里可能先有 text 块、再有工具块）。
            //   这里维护 blockIndex → openAiIndex 的映射，并把工具序号从 0 单独数。
            //   放在 Flux.defer 里（而不是字段上），两个并发流才不会互相污染。
            Map<Integer, Integer> toolBlockIndex = new HashMap<>();
            AtomicInteger toolCallIndex = new AtomicInteger(0);

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
                    // concatMap 保序：SSE 事件的顺序就是语义，flatMap 会把字打乱。
                    // 也因为它是串行的，下面两个可变状态不需要额外加锁（每条流各自一份）。
                    .concatMap(event -> normalizeEventSafely(
                            event, model, promptTokens, toolBlockIndex, toolCallIndex));
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
                String role = message.role() == null ? "" : message.role();
                if ("system".equalsIgnoreCase(role)) {
                    if (!system.isEmpty()) {
                        system.append("\n\n");
                    }
                    system.append(message.content());
                    continue;
                }

                if ("tool".equalsIgnoreCase(role)) {
                    appendToolResult(messages, message);
                    continue;
                }

                if ("assistant".equalsIgnoreCase(role)
                        && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    messages.add(assistantWithToolUse(message));
                    continue;
                }

                messages.add(Map.of(
                        "role", role,
                        "content", message.content() == null ? "" : message.content()));
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
        applyTools(body, request);
        return body;
    }

    /**
     * <b>工具结果：OpenAI 的 {@code role=tool} → Anthropic 的 {@code tool_result} 内容块。</b>
     *
     * <p><b>★ 为什么要"合并连续的工具结果"★</b>
     * <p>模型一次并发发起两个工具调用时，客户端会连续回两条 {@code role=tool} 消息。
     * Anthropic 要求消息角色交替（user / assistant / user …），
     * <b>连续两条 user 消息会被拒绝</b>。正确形状是一条 user 消息里放两个
     * {@code tool_result} 块。所以这里如果上一条也是"纯工具结果"的 user 消息，
     * 就把块追加进去，而不是新开一条。
     */
    @SuppressWarnings("unchecked")
    private void appendToolResult(List<Map<String, Object>> messages,
                                  ChatCompletionRequest.Message message) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", message.toolCallId() == null ? "" : message.toolCallId());
        block.put("content", message.content() == null ? "" : message.content());

        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if ("user".equals(last.get("role"))
                    && last.get("content") instanceof List<?> blocks
                    && !blocks.isEmpty()
                    && blocks.stream().allMatch(item -> item instanceof Map<?, ?> map
                            && "tool_result".equals(map.get("type")))) {
                ((List<Object>) last.get("content")).add(block);
                return;
            }
        }

        List<Object> blocks = new ArrayList<>();
        blocks.add(block);
        Map<String, Object> messageNode = new LinkedHashMap<>();
        messageNode.put("role", "user");
        messageNode.put("content", blocks);
        messages.add(messageNode);
    }

    /**
     * <b>带工具调用的 assistant 消息。</b>
     *
     * <p>OpenAI 里它是 {@code content=null + tool_calls[]}；
     * Anthropic 里它是 {@code content:[{type:"text"}?, {type:"tool_use"}...]}。
     * <p><b>{@code arguments} 是字符串，{@code input} 必须是对象</b> ——
     * 这是整段翻译里最容易错的一步：把字符串直接放进去，Anthropic 会拒绝；
     * 或者更糟，某些兼容层会接受然后把参数当字符串传给工具。
     */
    private Map<String, Object> assistantWithToolUse(ChatCompletionRequest.Message message) {
        List<Object> blocks = new ArrayList<>();
        if (StringUtils.hasText(message.content())) {
            blocks.add(Map.of("type", "text", "text", message.content()));
        }
        for (ToolCall call : message.toolCalls()) {
            ToolCall.Function function = call.function();
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_use");
            block.put("id", call.id() == null ? "" : call.id());
            block.put("name", function == null || function.name() == null ? "" : function.name());
            block.put("input", parseArguments(function));
            blocks.add(block);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("role", "assistant");
        node.put("content", blocks);
        return node;
    }

    /**
     * {@code arguments} 字符串 → Anthropic 的 {@code input} 对象。
     *
     * <p><b>不合法时直接抛错，而不是"退化成空对象"</b>：空对象意味着
     * 模型会用一个没有参数的调用继续跑，<b>结果是业务错误而不是协议错误</b>，
     * 排查时根本看不出是网关把参数吃掉了。
     * <b>在边界处响亮地失败，比在下游静默地错要好。</b>
     */
    private JsonNode parseArguments(ToolCall.Function function) {
        if (function == null || !StringUtils.hasText(function.arguments())) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(function.arguments());
            if (!parsed.isObject()) {
                throw new IllegalArgumentException(
                        "tool_calls[].function.arguments 必须是 JSON 对象，实际是："
                                + parsed.getNodeType());
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "tool_calls[].function.arguments 不是合法 JSON：" + function.arguments(), e);
        }
    }

    /**
     * <b>工具定义 + tool_choice 的翻译。</b>
     *
     * <p>OpenAI 的 {@code {"type":"function","function":{...}}} 去掉一层壳，
     * 把 {@code parameters} 改名成 {@code input_schema}。
     *
     * <p><b>{@code tool_choice} 的四种取值</b>：
     * <pre>
     *   "auto"      → {"type":"auto"}         模型自己决定
     *   "required"  → {"type":"any"}          必须调一个
     *   "none"      → 不发送 tools             Anthropic 没有 none，只能整段省略
     *   {"type":"function","function":{"name":"x"}}
     *               → {"type":"tool","name":"x"}
     * </pre>
     * <p><b>"none" 只能靠"不发 tools"实现</b>：Anthropic 的 API 里没有
     * "给了工具但不许用"的语义。如果既发 tools 又把 choice 忽略掉，
     * 模型会照常调用工具 —— <b>客户端明确说"别调"，网关却让它调了，这是语义错误。</b>
     */
    private void applyTools(Map<String, Object> body, ChatCompletionRequest request) {
        if (request.tools() == null || request.tools().isEmpty()) {
            return;
        }

        JsonNode choice = request.toolChoice();
        boolean explicitlyDisabled = choice != null && choice.isTextual()
                && "none".equalsIgnoreCase(choice.asText());
        if (explicitlyDisabled) {
            return;
        }

        List<Map<String, Object>> tools = new ArrayList<>(request.tools().size());
        for (Tool tool : request.tools()) {
            Tool.Function function = tool.function();
            if (function == null || !StringUtils.hasText(function.name())) {
                // 没有名字的工具定义是无意义的，直接跳过会让模型少一个能力；
                // 报错又会让整个请求失败。这里选择"跳过 + WARN"，因为
                // 一个坏工具不应该拖垮同一批里其它可用工具。
                log.warn("[Anthropic 工具定义缺少 name，已跳过] channel={}", name);
                continue;
            }
            Map<String, Object> translated = new LinkedHashMap<>();
            translated.put("name", function.name());
            if (StringUtils.hasText(function.description())) {
                translated.put("description", function.description());
            }
            translated.put("input_schema", inputSchema(function.parameters()));
            tools.add(translated);
        }

        if (tools.isEmpty()) {
            return;
        }
        body.put("tools", tools);

        if (choice == null || choice.isNull()) {
            return;
        }
        if (choice.isTextual()) {
            switch (choice.asText().toLowerCase()) {
                case "auto" -> body.put("tool_choice", Map.of("type", "auto"));
                case "required" -> body.put("tool_choice", Map.of("type", "any"));
                default -> log.warn("[Anthropic tool_choice 无法识别，按 auto 处理] channel={} value={}",
                        name, choice.asText());
            }
            return;
        }
        if (choice.isObject()) {
            String toolName = choice.path("function").path("name").asText();
            if (StringUtils.hasText(toolName)) {
                body.put("tool_choice", Map.of("type", "tool", "name", toolName));
            } else {
                log.warn("[Anthropic tool_choice 对象缺少 function.name，已忽略] channel={}", name);
            }
        }
    }

    /** Anthropic 的 input_schema 必填。parameters 缺失时给一个合法的空对象 schema */
    private JsonNode inputSchema(JsonNode parameters) {
        if (parameters != null && !parameters.isNull()) {
            return parameters;
        }
        ObjectNode empty = objectMapper.createObjectNode();
        empty.put("type", "object");
        empty.set("properties", objectMapper.createObjectNode());
        return empty;
    }

    private ChatCompletionResponse normalize(JsonNode raw, String requestedModel) {
        JsonNode usage = raw.path("usage");
        int promptTokens = usage.path("input_tokens").asInt();
        int completionTokens = usage.path("output_tokens").asInt();

        String text = extractText(raw.path("content"));
        List<ToolCall> toolCalls = extractToolCalls(raw.path("content"));
        // OpenAI 的约定：只有工具调用、没有文字时，content 是 null 而不是空串。
        // 空串会被客户端当成"模型说了一句空话"，null 才表示"这一轮只调工具"。
        String content = (text.isEmpty() && toolCalls != null) ? null : text;

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
                        new ChatCompletionResponse.Message("assistant", content, toolCalls),
                        mapStopReason(raw.path("stop_reason").asText()))),
                new ChatCompletionResponse.Usage(
                        promptTokens, completionTokens, promptTokens + completionTokens));
    }

    /**
     * <b>Anthropic 的 {@code tool_use} 块 → OpenAI 的 {@code ToolCall}。</b>
     *
     * <p>关键差异：Anthropic 的 {@code input} 是<b>对象</b>；OpenAI 的
     * {@code arguments} 必须是<b>JSON 字符串</b>。这里要序列化回去，
     * 而且<b>不能重新排序或美化</b> —— 客户端可能对它做哈希/签名。
     * Jackson 的 {@code writeValueAsString} 对同一棵树是确定性的，
     * 但和上游原始字节不保证一致；这是协议转换本身的代价，不只是本实现的问题。
     *
     * <p>没有 tool_use 块时返回 {@code null} 而不是空列表：
     * {@code ChatCompletionResponse.Message} 用 NON_NULL，空列表会输出
     * {@code "tool_calls":[]}，严格客户端会认为"有工具调用字段但内容为空"。
     */
    private List<ToolCall> extractToolCalls(JsonNode content) {
        if (!content.isArray()) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : content) {
            if (!"tool_use".equals(block.path("type").asText())) {
                continue;
            }
            String arguments;
            try {
                arguments = objectMapper.writeValueAsString(
                        block.has("input") ? block.get("input") : objectMapper.createObjectNode());
            } catch (Exception e) {
                // 序列化自己的 JsonNode 失败说明树里有不可序列化的东西 —— 这是 bug，
                // 不能悄悄变成一个空参数调用。
                throw new IllegalStateException("无法序列化 Anthropic tool_use.input", e);
            }
            calls.add(new ToolCall(
                    block.path("id").asText(),
                    "function",
                    new ToolCall.Function(block.path("name").asText(), arguments)));
        }
        return calls.isEmpty() ? null : calls;
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
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    text.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    // 工具调用由 extractToolCalls 处理 —— 这里不是"忽略"，
                    // 是"另一个字段负责"。打成 WARN 会让每次正常工具调用都产生噪音。
                    continue;
                } else {
                    log.warn("[Anthropic 非文本内容块已忽略] channel={} type={}", name, type);
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
            // ★ tool_use → tool_calls ★
            //   OpenAI 客户端靠这个值判断"这一轮结束是为了让你执行工具"。
            //   映射丢了，Agent 会把"我要调工具"当成"我说完了"，直接结束循环。
            case "tool_use" -> "tool_calls";
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
    private Flux<ChatCompletionChunk> normalizeEventSafely(
            String data,
            String requestedModel,
            AtomicReference<Integer> promptTokens,
            Map<Integer, Integer> toolBlockIndex,
            AtomicInteger toolCallIndex) {
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
                case "content_block_start" -> {
                    // 文本块的 start 不产生数据（文字由后面的 delta 逐字给出）；
                    // 但工具块的 start 必须产出一帧，因为 OpenAI 协议要求
                    // 第一个工具块带上 id / name，后续块才只带 arguments 片段。
                    JsonNode block = event.path("content_block");
                    if (!"tool_use".equals(block.path("type").asText())) {
                        yield Flux.empty();
                    }
                    int blockIndex = event.path("index").asInt(0);
                    int openAiIndex = toolCallIndex.getAndIncrement();
                    toolBlockIndex.put(blockIndex, openAiIndex);

                    ObjectNode fragment = objectMapper.createObjectNode();
                    fragment.put("index", openAiIndex);
                    fragment.put("id", block.path("id").asText());
                    fragment.put("type", "function");
                    ObjectNode function = fragment.putObject("function");
                    function.put("name", block.path("name").asText());
                    // OpenAI 的第一帧 arguments 是空串，后续 input_json_delta 再逐段追加
                    function.put("arguments", "");

                    yield Flux.just(chunk("", requestedModel,
                            new ChatCompletionChunk.Delta(null, null, List.<JsonNode>of(fragment)),
                            null, null));
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.path("delta");
                    String deltaType = delta.path("type").asText();

                    if ("input_json_delta".equals(deltaType)) {
                        Integer openAiIndex = toolBlockIndex.get(event.path("index").asInt(0));
                        if (openAiIndex == null) {
                            log.warn("[Anthropic 工具参数片段找不到对应的 tool_use 块] channel={} index={}",
                                    name, event.path("index").asInt(0));
                            yield Flux.empty();
                        }
                        ObjectNode fragment = objectMapper.createObjectNode();
                        fragment.put("index", openAiIndex);
                        fragment.putObject("function")
                                .put("arguments", delta.path("partial_json").asText());

                        yield Flux.just(chunk("", requestedModel,
                                new ChatCompletionChunk.Delta(null, null, List.<JsonNode>of(fragment)),
                                null, null));
                    }

                    if (!"text_delta".equals(deltaType)) {
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
