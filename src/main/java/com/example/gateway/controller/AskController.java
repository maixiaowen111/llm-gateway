package com.example.gateway.controller;

import com.example.gateway.client.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 1b 的验收入口。刻意做得极简 —— 它只是「扳机」，真正的事在 DeepSeekClient 里。
 */
@RestController
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final DeepSeekClient deepSeekClient;

    public AskController(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
    }

    /**
     * GET /test/ask?q=你的问题
     *
     * <p>返回类型同样是 {@code Mono<String>}：外部调用要等好几秒，
     * 但处理这个请求的 Netty 线程不会陪着等。这一行是整个 1b 的重点。
     */
    @GetMapping("/test/ask")
    public Mono<String> ask(@RequestParam(defaultValue = "用一句话解释什么是 API 网关") String q) {
        log.info("收到提问：{}，处理线程 = {}", q, Thread.currentThread().getName());
        return deepSeekClient.chat(q);
    }
}
