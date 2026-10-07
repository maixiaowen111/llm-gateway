package com.example.gateway.controller;

import com.example.gateway.client.DeepSeekClient;
import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.dto.ChatCompletionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
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
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final DeepSeekClient deepSeekClient;

    public ChatController(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
    }

    /**
     * POST /v1/chat/completions
     *
     * <p>入参和出参都是网关自己的 DTO —— Controller 层<b>看不到任何上游的痕迹</b>。
     * 这一层如果出现了 {@code JsonNode} 或 "deepseek" 字样，就说明归一漏了。
     */
    @PostMapping("/v1/chat/completions")
    public Mono<ChatCompletionResponse> completions(@RequestBody ChatCompletionRequest request) {
        log.info("收到请求，model={}，messages={} 条，处理线程 = {}",
                request.model(),
                request.messages() == null ? 0 : request.messages().size(),
                Thread.currentThread().getName());
        return deepSeekClient.chatCompletion(request);
    }
}
