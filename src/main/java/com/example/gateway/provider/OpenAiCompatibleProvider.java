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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * <b>一个「OpenAI 兼容渠道」的实现。注意：不是一个厂商，而是一类厂商。</b>G3-3b 的产物。
 *
 * <p><b>本类的来历（三次改名，每次含义都变了）</b>：
 * <pre>
 *   G1/G2   DeepSeekClient            住在 client 包 —— "这是我们的上游客户端"
 *   G3a     DeepSeekProvider          实现 Provider —— "这是其中一个渠道"
 *   G3b     OpenAiCompatibleProvider  由配置驱动 —— "这是一类渠道的通用实现"
 * </pre>
 * <b>名字的变化就是认知的变化。</b>前两次都还假定"一家厂商一个类"，
 * 第三次才发现：<b>这些厂商压根不需要各写一个类。</b>
 *
 * <p><b>★ 为什么一个类能顶 N 家厂商 ★</b>
 * <p>DeepSeek、Moonshot、通义、智谱、SiliconFlow、本地的 Ollama / vLLM ——
 * <b>它们全都提供 OpenAI 兼容接口</b>：同一个 {@code /chat/completions} 路径、
 * 同一个请求体结构、同一个响应结构。<b>差别只有三样：
 * {@code base-url}、{@code api-key}、{@code model}。</b>
 *
 * <p>所以"一家厂商一个类"是<b>把重复代码当成了架构</b> ——
 * 抄 N 遍同样的翻译、归一、流式处理逻辑，然后每次修 bug 要改 N 个地方。
 * 正确做法是<b>一个实现类 × N 份配置</b>：
 * 那些差异全部从构造参数进来（见 {@code config/ProviderConfig}），
 * <b>本类里没有任何一行是为某一家厂商写的</b>。
 *
 * <p><b>这就是"加第二家厂商 = 在 YAML 里加 4 行"的原因。</b>
 *
 * <p><b>那如果某家厂商协议真的不一样呢？</b>（比如用 {@code input} 而不是
 * {@code messages}、或者响应里是 {@code output.text}）
 * —— <b>那时才需要新写一个类实现 {@link Provider}</b>，
 * 而 {@code ChatController} / 注册表 / 本类<b>全都不用改</b>。
 * "通用实现"和"专用实现"是<b>并存的</b>，不是二选一。
 * 这也是 SPI 真正的价值：<b>它允许你在 99% 的情况下用一份通用实现偷懒，
 * 同时在剩下 1% 的情况下不受那份通用实现绑架。</b>
 *
 * <p><b>⚠️ 本类不是 {@code @Component}</b>：它需要构造参数（名字、地址、密钥…），
 * 而参数来自配置。<b>谁来 new 它、new 几个，由 {@code config/ProviderConfig} 决定</b> ——
 * 这正是"一个类 × N 份配置"能成立的原因。
 * （如果它是 {@code @Component}，Spring 只会造一个，而且不知道该用哪份配置。）
 */
public class OpenAiCompatibleProvider implements Provider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    /** 渠道标识，来自配置里 map 的 key。G3a 里这个字段还是常量，3b 变成了构造参数 */
    private final String name;

    private final WebClient webClient;
    private final String apiKey;
    private final String defaultModel;
    /** 归一用：把上游的块读成 JsonNode，再把网关自己的契约写成 JSON 文本 */
    private final ObjectMapper objectMapper;
    /** G2-2d：两个数据块之间最长允许隔多久（毫秒） */
    private final long idleTimeoutMs;

    /**
     * <b>只由 {@code ProviderConfig} 调用。</b>
     *
     * <p>参数看着多，但每一个都对应配置里的一项 —— <b>没有任何一个是本类自己猜的</b>。
     * 这也正是"通用实现"的代价：<b>通用性是从外面灌进来的，不是从里面长出来的。</b>
     *
     * @param name         渠道标识，必须与配置里的 key 一致（由 ProviderConfig 保证）
     * @param baseUrl      上游根地址，各家的差别主要就在这一个参数上
     * @param apiKey       上游密钥，<b>只存在内存里，绝不写日志</b>
     * @param defaultModel 客户端没指定 model 时的兜底
     * @param idleTimeoutMs 流式逐块空闲超时（G2-2d）
     */
    public OpenAiCompatibleProvider(String name,
                                    String baseUrl,
                                    String apiKey,
                                    String defaultModel,
                                    long idleTimeoutMs,
                                    WebClient.Builder builder,
                                    ObjectMapper objectMapper) {
        this.name = name;
        this.webClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.idleTimeoutMs = idleTimeoutMs;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return name;
    }

    // ==========================================================================
    // 以下全部是 G1 / G2 的既有逻辑，G3b 一行未改 —— 它们本来就跟厂商无关。
    // 这也反过来证明了一件事：G1 那层"看着像白写"的翻译代码，写的时候就是对的。
    // ==========================================================================

    /**
     * 1c 的主入口：吃网关自己的契约，吐网关自己的契约。
     *
     * @param request 客户端（项目一 Agent / OpenAI SDK）发来的请求
     */
    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        // 客户端没指定模型就用本渠道的默认值 —— 网关该有的兜底行为
        String model = StringUtils.hasText(request.model()) ? request.model() : defaultModel;

        // 把「我们的契约」翻译成「上游的请求体」。
        // ★ G3b 之后这一段的价值彻底兑现了：如果某家厂商的请求结构不一样，
        //   改动会被关在"那一个 Provider 类"里，Controller 和 DTO 一行不动。
        Map<String, Object> upstreamBody = Map.of(
                "model", model,
                "messages", request.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content()))
                        .toList(),
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
                .doOnNext(raw -> log.info("[上游原始响应] channel={} {}", name, raw))
                .map(raw -> normalize(raw, model));
    }

    /**
     * 流式入口（G2-2a）。吃网关契约，吐<b>上游吐出来的逐块内容</b>。
     *
     * <p><b>⚠️ 3a 把它改名了</b>：原来叫 {@code chatCompletionStream}，
     * 现在叫 {@code chatCompletionStreamRaw} —— 因为 {@code chatCompletionStream}
     * 这个名字<b>让给 SPI 接口了</b>（接口定义的是"吐网关契约的块"）。
     * 如果保留原名，就会出现"接口方法和实现方法同签名但语义不同"的灾难 ——
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
     * （G3b 的副作用：测试要拿到它，就必须从注册表取出具体实现再向下转型 ——
     * <b>这个"别扭"正是 SPI 边界在起作用</b>，不是设计缺陷。）
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
                //
                // ★ G3b：日志里带上 channel 名字。多家渠道并存之后，
                //   "哪个渠道的用户最没耐心"是个真问题 —— 不带名字就永远查不出来。
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        log.info("[cancel] channel={} 客户端断开，取消信号已传到上游 —— 上游连接将被关闭", name);
                    } else {
                        log.info("[stream-end] channel={} 上游流结束，signal={}", name, signal);
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
                .doOnNext(chunk -> log.info("[上游分块] channel={} {}", name, chunk));
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
            log.warn("[分块归一失败，已跳过该块] channel={} raw={}", name, rawChunk, e);
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
     *
     * <p><b>G3b 之后这一段的地位变了</b>：3a 时它还是"DeepSeek 的归一"，
     * 现在是<b>"所有 OpenAI 兼容渠道共用的归一"</b>。
     * 换句话说，<b>这一段代码现在服务的是 N 家厂商，而不是一家</b>——
     * 但代码一个字没改。这就是"通用实现"的含义。
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
