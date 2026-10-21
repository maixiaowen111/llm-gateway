package com.example.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * <b>G9 的验收物：指标真的被记上了，健康检查真的反映了渠道状态。</b>
 *
 * <p><b>★ 为什么指标也要写测试 ★</b>
 * <p>指标代码有一个非常讨厌的特征：<b>它错了不会影响任何业务结果。</b>
 * 名字拼错、标签漏传、注册表没接上 —— 请求照样成功，测试照样绿，
 * 只有等到凌晨三点看板一片空白时才会发现。
 * <p>所以这里不看"代码里有没有 metrics.xxx()"，而是<b>从对外端点把数字读回来</b>：
 * {@code /actuator/metrics} 里有这个指标、值大于 0、标签齐全。
 * <b>这和计费测试"把余额读回来对账"是同一种思路。</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// ★ 这一行是必须的，但原因很隐蔽 ★
// Boot 3.4+ 在【测试上下文】里默认注入三条属性（来源名为 "test"）：
//   management.defaults.metrics.export.enabled=false
//   management.simple.metrics.export.enabled=true
//   management.tracing.enabled=false
// 意图是好的：单元测试不该往真实的 Prometheus/OTLP 后端推数据。
// 但它有副作用 —— 没有 PrometheusMeterRegistry，/actuator/prometheus 端点【根本不会注册】，
// 于是"测 Prometheus 抓取"这件事在默认测试上下文里永远 404。
// @AutoConfigureObservability 声明"这个测试就是要验证观测能力"，把这组默认值关掉。
// 排查时的关键证据：condition report 里写着
//   OnMetricsExportEnabledCondition: management.defaults.metrics.export.enabled is considered false
@AutoConfigureObservability
@DisplayName("G9 端到端：指标 / 健康检查 / requestId")
class ObservabilityTest {

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.default-channel", () -> "deepseek");
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key");
        // 本类不验熔断/计费/限流：全部走默认关闭状态，避免额外的 Redis 依赖
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"c1","object":"chat.completion","created":1,"model":"upstream-alias",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                                 "finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                                """)));
        this.client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void tearDown() {
        UPSTREAM.stop();
    }

    @Test
    @DisplayName("★ 一次成功请求后，gateway.requests 的 success 计数真的增加了")
    void requestIsCountedInMetrics() {
        postOnce();

        String body = client.get().uri("/actuator/metrics/gateway.requests")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"name\":\"gateway.requests\"")
                // 标签必须齐全：缺 stream 或 outcome，看板就无法按维度切分
                .contains("\"tag\":\"stream\"")
                .contains("\"tag\":\"outcome\"")
                // COUNT 是 Micrometer 约定的统计项名
                .contains("\"statistic\":\"COUNT\"");
    }

    @Test
    @DisplayName("Prometheus 端点能抓到网关指标（否则监控平台看不见）")
    void prometheusEndpointExposesGatewayMetrics() {
        postOnce();

        String body = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // Micrometer 把点号换成下划线，并加 _total 后缀（Prometheus 的计数器约定）
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("gateway_requests_total")
                .contains("gateway_upstream_calls_total");
    }

    @Test
    @DisplayName("健康检查里能看到每条渠道的状态，且不是只有一句 UP")
    void healthIncludesChannelDetails() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.channels.status").isEqualTo("UP")
                .jsonPath("$.components.channels.details.deepseek.enabled").isEqualTo(true)
                .jsonPath("$.components.channels.details.deepseek.circuit").isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★ 合法的 X-Request-Id 原样透传；过长/非法值则替换成我们生成的 ID")
    void requestIdIsPropagatedOrSanitized() {
        // 业务方传进来的 ID 必须保留 —— 否则一次业务流程在网关前后会有两个 ID，链路就断了
        client.post().uri("/v1/chat/completions")
                .header("X-Request-Id", "trace-abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "trace-abc-123");

        // 超长值必须被替换（日志注入 / 日志撑爆的防线）
        String tooLong = "x".repeat(100);
        String responseId = client.post().uri("/v1/chat/completions")
                .header("X-Request-Id", tooLong)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst("X-Request-Id");

        org.assertj.core.api.Assertions.assertThat(responseId)
                .isNotNull()
                .isNotEqualTo(tooLong)
                .hasSize(12);
    }

    private void postOnce() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isOk();
    }

    private Map<String, Object> body() {
        return Map.of(
                "model", "deepseek-chat",
                "messages", List.of(Map.of("role", "user", "content", "hello")));
    }
}
