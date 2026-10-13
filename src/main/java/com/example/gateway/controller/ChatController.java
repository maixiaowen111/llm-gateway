package com.example.gateway.controller;

import com.example.gateway.dto.ChatCompletionChunk;
import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.provider.Provider;
import com.example.gateway.provider.ProviderRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 网关对外的正式端点。G1-1c 的验收物。
 *
 * <p><b>路径 {@code /v1/chat/completions} 是 OpenAI 的官方路径，这是刻意的。</b>
 * 任何 OpenAI SDK / LangChain4j 只要把 baseUrl 从 {@code https://api.deepseek.com}
 * 改成 {@code http://网关:8081}，其余代码一行都不用动 —— 这就是"客户端零改造"。
 *
 * <p>对比 1b 的 {@code /test/ask}：那是临时验收入口，收的是 {@code ?q=} 查询参数；
 * 这里是正式契约，收的是 OpenAI 格式的 JSON 请求体。
 *
 * <p><b>G3-3a 的改动：依赖从具体的 {@code DeepSeekClient} 换成了 {@link ProviderRegistry}。</b>
 * 在此之前，本类<b>认识 DeepSeek 这个名字</b>；现在它只知道"有一个渠道注册表，
 * 问它要一个渠道来干活"。
 *
 * <p>这就是 SPI 抽出来之后该有的样子 —— <b>本文件里再也搜不到任何厂商的名字</b>。
 * 加第二家、第三家厂商时，<b>本文件一个字都不用改</b>。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public ChatController(ProviderRegistry providerRegistry, ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /v1/chat/completions
     *
     * <p>入参和出参都是网关自己的 DTO —— Controller 层<b>看不到任何上游的痕迹</b>。
     * 这一层如果出现了 {@code JsonNode} 或 "deepseek" 字样，就说明归一漏了。
     */
    @PostMapping("/v1/chat/completions")
    public Object completions(@RequestBody ChatCompletionRequest request, ServerWebExchange exchange) {
        log.info("收到请求，model={}，stream={}，messages={} 条，处理线程 = {}",
                request.model(),
                request.stream(),
                request.messages() == null ? 0 : request.messages().size(),
                Thread.currentThread().getName());

        // G3-3a：这里不再 new 一个 DeepSeekClient，而是向注册表要一个渠道。
        // 3a 阶段只有一家、固定取默认的；3b 会换成"按请求里的 model / provider 字段路由"。
        // 关键是：**本方法不知道拿到的是哪一家** —— 这正是 SPI 想要的效果。
        Provider provider = providerRegistry.defaultProvider();

        if (Boolean.TRUE.equals(request.stream())) {
            // ★必须显式钉死★ 不钉的话，客户端未声明 Accept 时 Spring 的内容协商会挑
            // JSON writer 去序列化 SSE 对象，直接 500（实测）。路由由业务字段决定，不由 HTTP 头决定。
            exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            // 关掉中间层缓冲。Nginx 默认会把 SSE 攒着，生产的经典坑
            exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache");
            // 吐的是 ChatCompletionChunk —— object 由我们钉死、model 报客户端请求的那个。
            // 客户端从 G2 起，永远看不到厂商的痕迹。
            return streamToClient(provider, request);
        }

        return provider.chatCompletion(request);
    }

    /**
     * 流式出口：把归一后的分块序列化成文本，接上协议尾巴。
     *
     * <p><b>为什么返回 {@code Flux<String>} 而不是 {@code Flux<ServerSentEvent>}</b>：
     * 响应头已经被钉成 {@code text/event-stream}，Spring 的 SSE writer 会把
     * <b>每个裸元素自动包成一条 {@code data: ...} 事件</b>。自己再包一层
     * {@code ServerSentEvent} 是重复劳动，而且这个方法的声明返回类型是 {@code Object}
     * （为了兼容非流式分支），泛型信息在运行时拿不到，Spring 未必认得出元素类型。
     * 用最朴素的 {@code String} 反而最稳。
     *
     * <p><b>序列化放在这里、不放在 client</b>：client 只管"把上游的块重建成我们的对象"，
     * "对象怎么变成线上的字节"是出口的事。职责分开，client 的单测就不用碰 JSON 文本。
     */
    private Flux<String> streamToClient(Provider provider, ChatCompletionRequest request) {
        Flux<String> chunks = provider.chatCompletionStream(request)
                .map(this::toJson);

        // [DONE] 是协议尾巴，不是数据 —— 上游那份已经被 client 过滤掉了，这里补上我们自己的。
        // 少了它，OpenAI SDK 会一直等下一个块，表现为"回复完了但客户端不结束"。
        return chunks.concatWith(Mono.just(ChatCompletionChunk.DONE_SENTINEL));
    }

    private String toJson(ChatCompletionChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            // 走到这里说明是我们自己的 DTO 序列化不出来 —— 那是 bug，不是上游的问题。
            // 这种错误必须炸出来，不能被当成"上游抖动"糊过去。
            throw new IllegalStateException("网关自身的流式契约序列化失败", e);
        }
    }
}
