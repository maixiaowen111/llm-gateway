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
 * <p>对比 1b 的 {@code /test/ask}（<b>G3b 已删除</b>）：那是临时验收入口，
 * 收的是 {@code ?q=} 查询参数；这里是正式契约，收的是 OpenAI 格式的 JSON 请求体。
 * 删它的理由见下。
 *
 * <p><b>G3-3a 的改动：依赖从具体的 {@code DeepSeekClient} 换成了 {@link ProviderRegistry}。</b>
 * 在此之前，本类<b>认识 DeepSeek 这个名字</b>；现在它只知道"有一个渠道注册表，
 * 问它要一个渠道来干活"。
 *
 * <p><b>G3-3b 的改动：那一行 {@code defaultProvider()} 换成了
 * {@code resolveOrDefault(request.provider())}。就这一行。</b>
 * 加了一家厂商、加了按请求路由 —— <b>本类没有 if/else 分支，没有新增字段，
 * 没有 import 任何厂商相关的类。</b>这就是 SPI 抽对了的样子。
 *
 * <p><b>⚠️ G3b 同时删掉了 {@code AskController}（1b 的验收入口）。</b>
 * 原因有两层：
 * <ol>
 *   <li><b>架构上</b>：它调的是 {@code chat(String)} —— 一个<b>不在 SPI 上</b>的便利方法，
 *       所以它只能指名道姓地要具体实现类。
 *       <b>它是 3b 之后唯一还在认识厂商的 Controller</b>，留着就等于留着一条"换厂商要改的地方"。</li>
 *   <li><b>安全上（更重要）</b>：它<b>没有任何鉴权、走默认渠道、且不产生业务日志</b>。
 *       一个能被任何人扫到、白嫖大模型额度的接口，在生产上就是个漏洞。
 *       <b>它不会报错、不会告警，只会在月底的账单上体现出来。</b></li>
 * </ol>
 * 顺带，{@code chat(String)} 也一起删了 —— 它失去了唯一的调用方。
 * <b>死代码不会自己消失，它只会变成下一个人不敢删的东西。</b>
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
        // ★ G3-3b：路由只有这一行。从"固定取默认"换成"按请求里指定的渠道，
        //   没指定就用默认"。**注意这里没有 if/else，也没有任何厂商名字** ——
        //   判断逻辑全部在注册表里（连同"指定了不存在的该怎么办"那个设计决策）。
        //
        //   ★ 这一步的验收物就是它：加第二家厂商、加上路由，
        //     本文件从头到尾**只改了这一个表达式**。SPI 抽对了就该是这个效果。
        //
        //   顺序说明：先解析再打日志，是为了让日志里能带上**最终走了哪个渠道**。
        //   如果解析失败（渠道名不存在），这里不会有日志 —— 但那时
        //   UnknownProviderExceptionHandler 会打 [渠道不存在]，
        //   而且带了可用渠道列表，**排查信息一点没少**。
        Provider provider = providerRegistry.resolveOrDefault(request.provider());

        // 多家渠道并存之后，"这次走了哪条"必须是日志里的第一个字段。
        // 同时打出"请求指定了什么"，因为在客户端指定了但被兜底的情况下，
        // 这两者不一样 —— 而那正是需要被看见的情况。
        log.info("收到请求，渠道={}（请求指定={}），model={}，stream={}，messages={} 条，处理线程 = {}",
                provider.name(),
                request.provider(),
                request.model(),
                request.stream(),
                request.messages() == null ? 0 : request.messages().size(),
                Thread.currentThread().getName());

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
