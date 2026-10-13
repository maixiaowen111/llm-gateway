package com.example.gateway.provider;

import com.example.gateway.dto.ChatCompletionChunk;
import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.dto.ChatCompletionResponse;
import com.example.gateway.exception.UpstreamIdleTimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * DeepSeek 渠道。承载 G1 的 1b（直连上游）与 1c（协议归一）、G2 的流式与取消传播。
 *
 * <p><b>本类在 G3-3a 之前叫 {@code DeepSeekClient}</b>，住在 {@code client} 包。
 * 3a 只做了一件事：<b>让它实现 {@link Provider} 接口</b>，从"唯一的实现"
 * 变成"其中一个实现"。<b>业务逻辑一行没改</b> —— 这是重构该有的样子。
 *
 * <p><b>★ 这个类的存在方式，就是 SPI 的意义 ★</b>：
 * 所有跟 DeepSeek 有关的东西 —— base-url、密钥、默认模型、上游返回的
 * {@code deepseek-flash} 这种别名怪癖、协议字段长什么样 —— <b>全部关在这个文件里</b>。
 * 外面（Controller、注册表）一个字都不知道。
 * 想加通义千问？<b>新开一个文件，这个文件一个字不改。</b>
 *
 * <p><b>和 G1 时相比，本类多了两个"不属于契约"的方法</b>：
 * {@link #chat(String)} 和 {@link #chatCompletionStreamRaw(ChatCompletionRequest)}。
 * 它们在接口上看不到 —— 因为它们是<b>历史验收物</b>（1b 和 2a 的证据），
 * 不是网关对外的承诺。<b>"实现类可以有额外方法，接口只留共同承诺"</b>，
 * 这个区分很重要：把 raw 方法提到接口上，等于强迫每个厂商都暴露自己的原始协议。
 */
@Component
public class DeepSeekProvider implements Provider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProvider.class);

    /** ★ 渠道标识：配置里写的、日志里打的，就是这个字符串 */
    public static final String NAME = "deepseek";

    private final WebClient webClient;
    private final String apiKey;
    private final String defaultModel;
    /** 归一用：把上游的块读成 JsonNode，再把网关自己的契约写成 JSON 文本 */
    private final ObjectMapper objectMapper;
    /** G2-2d：两个数据块之间最长允许隔多久（毫秒） */
    private final long idleTimeoutMs;

    public DeepSeekProvider(WebClient.Builder builder,
                            ObjectMapper objectMapper,
                            @Value("${deepseek.base-url}") String baseUrl,
                            @Value("${deepseek.api-key}") String apiKey,
                            @Value("${deepseek.model}") String defaultModel,
                            @Value("${gateway.upstream.idle-timeout-ms}") long idleTimeoutMs) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 1b 的便捷入口：一句话进，一句话出。保留它是为了让 1b 的验收物还能复现。
     */
    public Mono<String> chat(String prompt) {
        return chatCompletion(new ChatCompletionRequest(
                null,
                List.of(new ChatCompletionRequest.Message("user", prompt)),
                false))
                .map(resp -> resp.choices().get(0).message().content());
    }

    /**
     * 1c 的主入口：吃网关自己的契约，吐网关自己的契约。
     *
     * @param request 客户端（项目一 Agent / OpenAI SDK）发来的请求
     */
    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        // 客户端没指定模型就用网关的默认值 —— 网关该有的兜底行为
        String model = StringUtils.hasText(request.model()) ? request.model() : defaultModel;

        // 把「我们的契约」翻译成「DeepSeek 的请求体」。
        // 现在是同一个结构所以看着像白写；等 G3 接一家协议不同的厂商，这一段的必要性就出来了。
        Map<String, Object> upstreamBody = Map.of(
                "model", model,
                "messages", request.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content()))
                        .toList(),
                // 1c 只做非流式，流式是 G2
                "stream", false
        );

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upstreamBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                // 上游原始长什么样，留着给排错用。注意它只活在日志里，不出本方法
                .doOnNext(raw -> log.info("[上游原始响应] {}", raw))
                .map(raw -> normalize(raw, model));
    }

    /**
     * 流式入口（G2-2a）。吃网关契约，吐<b>上游吐出来的逐块内容</b>。
     *
     * <p><b>⚠️ 3a 把它改名了</b>：原来叫 {@code chatCompletionStream}，
     * 现在叫 {@code chatCompletionStreamRaw} —— 因为 {@code chatCompletionStream}
     * 这个名字<b>让给 SPI 接口了</b>（接口定义的是"吐网关契约的块"）。
     * <p>如果保留原名，就会出现"接口方法和实现方法同签名但语义不同"的灾难 ——
     * 调用方以为拿到的是归一后的块，实际拿到的是上游原始行。
     * <b>同名不同义，比名字难看危险得多。</b>
     *
     * <p><b>和 {@link #chatCompletion} 的根本差别，一句话</b>：
     * 那边返回 {@code Mono} —— 一个元素，上游全写完才有；
     * 这边返回 {@code Flux} —— <b>多个元素，上游吐一块就往下发一块</b>。
     * 单纯把 {@code Mono} 换成 {@code Flux} 不叫流式，<b>元素必须是真的分多次到达的</b>，
     * 这就是 2a 要验的东西。
     *
     * <p><b>它为什么不在 {@link Provider} 接口上</b>：
     * 它吐的是"上游原始行"，那是<b>实现细节</b>，不是网关对外的承诺。
     * 提到接口上等于强迫每个厂商都暴露自己的原始协议 —— SPI 就漏了。
     * 它留在这里，只为让 2a 的验收物还能复现。
     *
     * <p><b>为什么 WebFlux 在这里才真正兑现价值</b>：一个流式请求可能持续几分钟。
     * 用 MVC，这条线程就被占住几分钟；用 WebFlux，事件循环只是在每次数据到达时被唤醒一下。
     * 前面几段还只是"理论上有优势"，到流式这里是<b>实打实的差别</b>。
     *
     * <p><b>G2-2c 取消传播 —— 这一段的"取消"是<b>自动</b>的，不需要写一行代码</b>：
     * Controller 把本方法返回的 {@code Flux} <b>原样交还</b>给 Spring，
     * Spring 订阅它、把每个元素写进 HTTP 响应。客户端一断开，Netty 的写就失败，
     * 订阅被取消，这个取消信号<b>原路往上冒</b>：
     * <pre>
     *   客户端断开 → Spring 取消订阅 → concatMap → filter → doFinally → WebClient → 上游连接被关闭
     * </pre>
     * 所以"客户端断开时上游请求确实被取消"是<b>框架送给你的</b>——
     * <b>前提是你别把它弄丢</b>。下面这个 {@code doFinally} 就是用来<b>证明它没丢</b>的观测点。
     *
     * <p><b>⚠️ 什么情况下会弄丢（这才是 2c 真正要记住的）</b>：
     * 如果在 Controller 里手写 {@code flux.subscribe(...)}，Controller 方法<b>立刻返回</b>，
     * 下游订阅的是那个 {@code Mono}（已经完成了），<b>和上游流再也没有任何关系</b>。
     * 客户端断开时下游没有东西可取消，取消信号也就传不到上游 ——
     * 于是上游那条连接会<b>一直吐到天荒地老</b>，白烧 token 和连接数。
     * <b>"返回 Flux"和"自己 subscribe 一个 Flux"是生与死的区别。</b>
     */
    public Flux<String> chatCompletionStreamRaw(ChatCompletionRequest request) {
        String model = StringUtils.hasText(request.model()) ? request.model() : defaultModel;

        Map<String, Object> upstreamBody = Map.of(
                "model", model,
                "messages", request.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content()))
                        .toList(),
                // ← 与 chatCompletion 唯一的实质差别：true 让上游改吐 SSE 流
                "stream", true
        );

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                // 必须显式声明 Accept —— Spring 靠它选 SSE 解码器，
                // 否则拿到一坨原始文本、分不出事件边界
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(upstreamBody)
                .retrieve()
                // 上游是 text/event-stream，Spring 的 SSE 解码器会剥掉 "data: " 前缀，
                // 每个事件解成一个 String。所以这里拿到的已经是纯 JSON 行，不含前缀
                .bodyToFlux(String.class)
                // ★ G2-2c 的观测点，刻意放在<b>最贴近网线</b>的位置。
                // 取消信号是"自下而上"传的：放在这里能收到，就证明它已经穿过了
                // filter / concatMap / timeout 所有算子，一路冒到了 HTTP 客户端边界 ——
                // 也就是上游那条 TCP 连接真的会被 Reactor Netty 关掉。
                // 放在链尾也能收到信号，但证明力弱得多（那只说明"信号进了链条"，不代表传得到底）。
                //
                // 为什么区分 CANCEL 和 COMPLETE 而不是笼统记一句"流结束"：
                // 对网关来说这两件事的商业含义完全相反 —— COMPLETE 是正常交付完，
                // CANCEL 是<b>用户中途放弃了</b>。后者要单独统计（判断是上游太慢还是用户没耐心），
                // 混在一起就永远查不出问题。
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        log.info("[cancel] 客户端断开，取消信号已传到上游 —— 上游连接将被关闭");
                    } else {
                        log.info("[stream-end] 上游流结束，signal={}", signal);
                    }
                })
                // ★ G2-2d：逐块空闲超时。timeout 的计时器<b>每收到一个块就重置</b>，
                // 所以量的是"块与块之间"的间隔，而不是整条流的寿命 ——
                // 吐 5 分钟的健康长回复不会被它杀掉，只有"卡住不动"才会。
                .timeout(Duration.ofMillis(idleTimeoutMs))
                // 换成网关自己的异常类型：原始的 TimeoutException 在链路上到处都有，
                // 分不清是连接超时、读超时还是这里；自定义类型只可能从这一行产生。
                .onErrorMap(TimeoutException.class,
                        e -> new UpstreamIdleTimeoutException(idleTimeoutMs, e))
                .doOnNext(chunk -> log.info("[上游分块] {}", chunk));
    }

    /**
     * 流式入口的<b>归一版</b>（G2-2b，3a 之后成为 {@link Provider} 的接口实现）。
     *
     * <p><b>它和 {@link #chatCompletionStreamRaw} 的分工</b>：那边是"2a 的验收物"，
     * 刻意吐上游原始行、留着证明"流能通"；这边是<b>对外的正式出口</b>，
     * 把每一块都重建成 {@link ChatCompletionChunk} —— 和 1c 用
     * {@link ChatCompletionResponse} 重建非流式响应是同一件事，只是发生在流上。
     *
     * <p><b>三个归一动作</b>（少一个都不算归一）：
     * <ol>
     *   <li>{@code object} 由我们钉死 —— 上游给不给、给得对不对都不影响出口；</li>
     *   <li>{@code model} 报<b>客户端请求的那个</b>，不是上游实际调用的那个
     *       （实测请求 {@code deepseek-chat}、上游回 {@code deepseek-flash}）；</li>
     *   <li>整个 {@code choices[].delta} 结构按我们的契约重建，上游多给的字段一律丢弃。</li>
     * </ol>
     *
     * <p><b>{@code filter} 掉哨兵而不是透传</b>：{@code [DONE]} 不是数据块，
     * 它由 Controller 在流末尾补上（谁负责协议尾巴，谁补）。
     * 这里用 {@code filter} 而非 {@code takeWhile}，是因为 {@code takeWhile}
     * 命中即 <b>cancel 上游</b> —— 上游此刻可能还没把连接优雅关掉，
     * 让流自然读完更稳。
     */
    @Override
    public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
        String model = StringUtils.hasText(request.model()) ? request.model() : defaultModel;

        return chatCompletionStreamRaw(request)
                .filter(chunk -> !ChatCompletionChunk.DONE_SENTINEL.equals(chunk))
                // concatMap 保证块的顺序 —— 流式响应<b>顺序即语义</b>，
                // 用 flatMap 会在并发解包时把字打乱
                .concatMap(chunk -> normalizeChunkSafely(chunk, model));
    }

    /**
     * <b>单块归一失败不能拖垮整条流。</b>
     *
     * <p>流可能持续几分钟、上千个块。如果某个块因上游抽风变成半截 JSON，
     * 让它抛出异常会把整条流打断 —— 用户已经看到一半的回复就此中断。
     * 正确做法是<b>丢掉坏块、记一条 warn、继续往下流</b>。
     * （这也是流式和非流式的又一个差别：非流式一次成型，失败重试即可；
     * 流式已经开始交付了，"重试"这个选项根本不存在。）
     */
    private Flux<ChatCompletionChunk> normalizeChunkSafely(String rawChunk, String requestedModel) {
        try {
            return Flux.just(normalizeChunk(rawChunk, requestedModel));
        } catch (Exception e) {
            log.warn("[分块归一失败，已跳过该块] raw={}", rawChunk, e);
            return Flux.empty();
        }
    }

    /**
     * 单块归一：上游的原始 JSON 文本 → 网关的 {@link ChatCompletionChunk}。
     *
     * <p>和非流式的 {@code normalize()} 完全是同一套动作，区别只在字段名：
     * {@code message} → {@code delta}（增量）。所以这里的 {@code requestedModel}
     * 同样是"1c 最该记住的那一行"的流式版本。
     */
    private ChatCompletionChunk normalizeChunk(String rawChunk, String requestedModel)
            throws JsonProcessingException {
        JsonNode raw = objectMapper.readTree(rawChunk);
        JsonNode choice = raw.path("choices").path(0);
        JsonNode delta = choice.path("delta");

        return new ChatCompletionChunk(
                raw.path("id").asText(),
                ChatCompletionChunk.OBJECT,
                raw.path("created").asLong(),
                // ← 这里就是流式版的"归一坐实"：报请求的模型，不报上游的
                requestedModel,
                List.of(new ChatCompletionChunk.Choice(
                        choice.path("index").asInt(),
                        new ChatCompletionChunk.Delta(
                                // 第一个块有 role、中间的块没有 —— 用 hasNonNull 区分
                                // "字段不存在"和"字段是 null"，直接 asText() 会拿到字符串 "null"
                                delta.hasNonNull("role") ? delta.path("role").asText() : null,
                                delta.hasNonNull("content") ? delta.path("content").asText() : null),
                        // finish_reason 只有最后一个块才非 null，客户端靠它判断结束
                        choice.hasNonNull("finish_reason")
                                ? choice.path("finish_reason").asText()
                                : null))
        );
    }

    /**
     * <b>1c 的核心：归一。</b>把上游的原始响应映射成网关对外的契约。
     *
     * <p>这里做了两件在 1b 里做不到的事：
     * <ol>
     *   <li><b>字段由我们决定。</b>上游少给 {@code object} 字段，我们照样吐得出来；
     *       上游给的是 camelCase，出口照样是 snake_case。</li>
     *   <li><b>{@code model} 报"客户端请求的那个"，不报"上游实际用的那个"。</b>
     *       实测发现请求 {@code deepseek-chat} 时上游返回的是 {@code deepseek-flash}。
     *       透传的话客户端会看到一个自己没请求过的模型名；归一后客户端看到的始终是自己要的。
     *       厂商偷偷换模型是网关内部的事。</li>
     * </ol>
     */
    private ChatCompletionResponse normalize(JsonNode raw, String requestedModel) {
        JsonNode choice = raw.path("choices").path(0);
        JsonNode usage = raw.path("usage");

        return new ChatCompletionResponse(
                raw.path("id").asText(),
                "chat.completion",
                raw.path("created").asLong(),
                // ← 注意：requestedModel，不是 raw.path("model")。这是 1c 最该记住的一行
                requestedModel,
                List.of(new ChatCompletionResponse.Choice(
                        0,
                        new ChatCompletionResponse.Message(
                                choice.path("message").path("role").asText("assistant"),
                                choice.path("message").path("content").asText()),
                        choice.path("finish_reason").asText())),
                new ChatCompletionResponse.Usage(
                        usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt(),
                        usage.path("total_tokens").asInt())
        );
    }
}
