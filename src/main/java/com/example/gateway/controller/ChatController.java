package com.example.gateway.controller;

import com.example.gateway.dto.ChatCompletionChunk;
import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.service.GatewayChatService;
import com.example.gateway.web.ClientIdentity;
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

    private final GatewayChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatController(GatewayChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
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
        // ★ G5/G6：路由决策从 Controller 挪进了 GatewayChatService。
        //
        //   Controller 现在的职责只剩三件：读请求、定响应头、把结果写出去。
        //   "走哪条渠道 / 要不要重试 / 能不能转移 / 记不记熔断" 全在服务层 ——
        //   ★ 这不是为了好看，是因为这些决策需要共享状态（熔断器、重试计数、首字节标记），
        //     而 Controller 是每个请求一个新实例，它根本存不住这些状态。 ★
        //
        //   注意这里**不再打"渠道=xxx"的日志**：在路由发生之前，
        //   我们并不知道最终走了哪条（可能是主选，也可能是转移后的备份）。
        //   如果在这里提前打一条"渠道=deepseek"，它在故障转移时就是**假的** ——
        //   而假日志比没有日志更糟，它会让排障的人往错误的方向查。
        //   真正的渠道日志由 GatewayChatService 在"确定之后"打。
        // ★ G7：计费主体必须和限流用的是同一个 —— 所以统一由 ClientIdentity 解析。
        //   这里只解析一次，然后作为参数传给服务层；服务层不知道 HTTP 头长什么样。
        String clientId = ClientIdentity.resolve(exchange);
        log.info("收到请求，client={}，请求指定渠道={}，model={}，stream={}，messages={} 条",
                clientId,
                request.provider(),
                request.model(),
                request.stream(),
                request.messages() == null ? 0 : request.messages().size());

        if (Boolean.TRUE.equals(request.stream())) {
            // ★必须显式钉死★ 不钉的话，客户端未声明 Accept 时 Spring 的内容协商会挑
            // JSON writer 去序列化 SSE 对象，直接 500（实测）。路由由业务字段决定，不由 HTTP 头决定。
            exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            // 关掉中间层缓冲。Nginx 默认会把 SSE 攒着，生产的经典坑
            exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache");
            // 吐的是 ChatCompletionChunk —— object 由我们钉死、model 报客户端请求的那个。
            // 客户端从 G2 起，永远看不到厂商的痕迹。
            return streamToClient(clientId, request);
        }

        return chatService.complete(clientId, request);
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
    private Flux<String> streamToClient(String clientId, ChatCompletionRequest request) {
        Flux<String> chunks = chatService.stream(clientId, request)
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
