package com.example.gateway.controller;

import com.example.gateway.provider.DeepSeekProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 1b 的验收入口。刻意做得极简 —— 它只是「扳机」，真正的事在 {@link DeepSeekProvider} 里。
 *
 * <p><b>⚠️ G3-3a 暴露出来的一个坏味道：本类是唯一还在依赖具体厂商的 Controller。</b>
 *
 * <p>为什么：它调的是 {@code chat(String)} —— 1b 的便利方法，
 * 这个方法<b>不在 {@link com.example.gateway.provider.Provider} 接口上</b>，
 * 因为它只是"把一句话包成请求"的语法糖，不是网关对外的承诺。
 * 所以本类只能<b>指名道姓</b>地要 DeepSeek。
 *
 * <p><b>这恰恰说明它是一个"临时验收入口"</b>：
 * 正式端点 {@code ChatController} 已经彻底不认识任何厂商了，
 * 而这个调试口子认识。**只要有一个口子认识厂商，换厂商时它就得跟着改。**
 *
 * <p><b>正确处理（不是 3a 的范围，留给后续）</b>：把它 {@code @Profile("dev")} 掉，
 * 或者干脆删掉 —— <b>一个走默认模型、没有任何鉴权的调试接口，
 * 在生产上就是一个免费的、未授权的大模型消费入口。</b>
 * 面试里可以主动讲这个判断："我后来把早期验收入口收敛掉了。"
 */
@RestController
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final DeepSeekProvider deepSeekProvider;

    public AskController(DeepSeekProvider deepSeekProvider) {
        this.deepSeekProvider = deepSeekProvider;
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
        return deepSeekProvider.chat(q);
    }
}
