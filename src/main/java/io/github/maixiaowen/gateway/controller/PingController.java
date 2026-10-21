package io.github.maixiaowen.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 健康探针。G1-1a 的验收物。
 *
 * <p><b>本类唯一的知识点：返回类型是 {@code Mono<String>} 而不是 {@code String}。</b>
 *
 * <p>这不是写法偏好，是 WebFlux 的契约。{@code Mono} 代表「一个还没有发生的值」——
 * 方法返回时值并不存在，<b>调用线程不会停在这里等它</b>，而是立刻返回去处理别的请求。
 * 值真正产生后会由 Reactor 回调下游。
 *
 * <p>后续每一段都建立在这个「不等待」的模型上：
 * <ul>
 *   <li>G2 SSE 流式：返回值会变成 {@code Flux<ServerSentEvent<String>>}，一个流而不是一个值</li>
 *   <li>G5 熔断：靠的就是「首字节到达前」这个时间窗做渠道转移</li>
 *   <li>G7 计量：靠 Reactor 的 doOnNext / doFinally 钩子，断流也能触发结算</li>
 * </ul>
 * 1a 没吃透 Mono，G2 必然卡死。
 */
@RestController
public class PingController {

    private static final Logger log = LoggerFactory.getLogger(PingController.class);

    /**
     * 存活探针。
     *
     * @return {@code Mono.just("pong")} —— 一个「立刻就有值」的 Mono
     */
    @GetMapping("/ping")
    public Mono<String> ping() {
        // 1a 的第二个验收点：打印当前线程名。
        //   WebFlux（Netty）  -> reactor-http-nio-1   少量事件循环线程服务全部请求
        //   Spring MVC（Tomcat）-> http-nio-8081-exec-1 每个请求独占一个线程
        // 这一行日志是面试答「为什么用 WebFlux 而不是 Spring MVC」时的实证，不是背来的话术。
        log.info("ping 命中，处理线程 = {}", Thread.currentThread().getName());
        return Mono.just("pong");
    }
}
