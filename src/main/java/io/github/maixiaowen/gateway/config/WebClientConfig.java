package io.github.maixiaowen.gateway.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 上游调用的超时装配。G1-1d 的验收物。
 *
 * <p><b>为什么必须有超时 —— 这是线上事故级的。</b>
 * 不配超时的默认行为是<b>无限等</b>。上游网络抖动、对方进程假死、机房丢包，
 * 你的请求就会一直挂在那里。而 WebFlux 的请求挂在事件循环上，
 * <b>挂住的不是一条线程，是一整条事件循环覆盖的所有连接</b>（见 Q6.2 的铁律）。
 *
 * <p><b>两种超时的区别（面试常问）</b>
 * <ul>
 *   <li><b>连接超时</b>：TCP 三次握手阶段。对端 IP 可达但端口没开 / 不回 SYN —— 卡在这里。
 *       典型值 1~3 秒，因为它只该覆盖"网络到达对端"这件事。</li>
 *   <li><b>读超时</b>：连上之后，等对方返回数据。大模型推理本来就要几秒到几十秒，
 *       所以这个值要按业务定，不能照抄连接超时的值。</li>
 * </ul>
 * 把两者设成同一个数是新手最常见的错 —— 一个 3 秒，一个 30 秒，量级根本不是一回事。
 *
 * <p><b>⚠️ 留给 G2 的坑</b>：{@code responseTimeout} 度量的是"请求发出 → 收到响应头"。
 * 非流式下响应头 = 全部内容，所以它安全。上了 SSE 流式之后，<b>响应头很早就回来了，
 * 内容却可能吐几分钟</b> —— 那时 {@code responseTimeout} 管不到中途的停滞，
 * 需要改用逐块的空闲超时（{@code ReadTimeoutHandler}）。G2 必须回来改这里。
 */
@Configuration
public class WebClientConfig {

    /**
     * 用 {@link WebClientCustomizer} 而不是自己 new 一个 {@code WebClient.Builder}：
     * 后者会把 Spring Boot 自动装配的 builder 顶掉，丢掉它内置的编解码器、观察等能力。
     * Customizer 是"在自动装配的基础上加一层"，两种都不丢。
     *
     * <p>这样也保证了 <b>G3 的多个渠道客户端</b>只要用注入的 builder，
     * 就自动继承同一套超时，不需要每个客户端各配一遍。
     */
    @Bean
    public WebClientCustomizer upstreamTimeoutCustomizer(
            @Value("${gateway.upstream.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${gateway.upstream.read-timeout-ms}") long readTimeoutMs) {

        HttpClient httpClient = HttpClient.create()
                // ① 连接超时 —— 只作用于 TCP 握手
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                // ② 读超时 —— 请求发出到收到响应头
                .responseTimeout(Duration.ofMillis(readTimeoutMs));

        return builder -> builder.clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
