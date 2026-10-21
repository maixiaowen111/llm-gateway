package io.github.maixiaowen.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * <b>G5 的端到端验收：走到 HTTP 这一层，错误码和转移行为是否真的对。</b>
 *
 * <p><b>它和 {@code GatewayChatServiceTest} 的分工</b>：
 * 那个锁的是"编排规则"（纯逻辑），这个锁的是"规则 × HTTP" ——
 * 客户端最终看到的<b>状态码、错误码、响应体</b>。
 * 两者都要有：光有单测，可能规则对了但 {@code @ExceptionHandler} 映射错了；
 * 光有端到端，失败时很难判断是路由还是映射的问题。
 *
 * <p><b>路径前缀区分两家渠道</b>：两个渠道都指向同一个 WireMock，
 * 但 base-url 分别是 {@code /deepseek} 和 {@code /moonshot}，
 * 于是"这次打到了谁"可以从请求路径直接读出来 —— <b>比数调用次数更直白。</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("G5：故障转移的端到端行为")
class ResilienceEndToEndTest {

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @DynamicPropertySource
    static void pointAtFakeUpstream(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port() + "/deepseek");
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "k1");
        registry.add("gateway.providers.channels.moonshot.base-url",
                () -> "http://localhost:" + UPSTREAM.port() + "/moonshot");
        registry.add("gateway.providers.channels.moonshot.api-key", () -> "k2");
        // anthropic 在单独分组（claude），所以它是"没有备份渠道"的情况 ——
        // 用来验证"429 最终会原样给客户端"这条路径。
        registry.add("gateway.providers.channels.anthropic.base-url",
                () -> "http://localhost:" + UPSTREAM.port() + "/anthropic");
        registry.add("gateway.providers.channels.anthropic.api-key", () -> "k3");

        // 自动路由打开：请求里的 model 命中 deepseek 的 models 列表就走 deepseek
        registry.add("gateway.providers.routing.auto-route", () -> true);
        registry.add("gateway.providers.routing.failover-enabled", () -> true);
        // 关掉重试，让"一次失败 → 一次转移"变成确定的时间线
        registry.add("gateway.resilience.max-retry-attempts", () -> 0);
        // 熔断阈值放高：本类不验熔断，别让它在中途打开干扰后面的用例
        registry.add("gateway.resilience.minimum-number-of-calls", () -> 100);
        registry.add("gateway.resilience.sliding-window-size", () -> 100);
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    @Test
    @DisplayName("主渠道 500 → 自动转移到同组备份，客户端收到 200 而不是错误")
    void upstream500FailsOverAndClientSees200() {
        stubDeepseek(500, "{\"error\":\"upstream is down\"}");
        stubMoonshot(200, okBody("来自备份渠道的回答"));

        // ★ 为什么这里是"显式点名 deepseek"，而不是靠 model 自动路由 ★
        // 按 model 路由的规则是"只有认领了这个模型的渠道才是候选" ——
        // 也就是说：deepseek-chat 这条路只有 deepseek 认领时，它挂了就没有同模型备份，
        // 网关会诚实地报错，而不是把 "deepseek-chat" 发给一家根本没有这个模型的厂商。
        // 想验证"同组备份能顶上"，就让调用方显式点名一个渠道，
        // 由 routing.failover-on-explicit 决定它挂了要不要转移（默认允许）。
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"deepseek","model":"deepseek-chat",
                         "messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                // ★ 对用户来说这次请求是成功的 ★
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("来自备份渠道的回答");

        UPSTREAM.verify(1, postRequestedFor(urlEqualTo("/deepseek/chat/completions")));
        UPSTREAM.verify(1, postRequestedFor(urlEqualTo("/moonshot/chat/completions")));
    }

    @Test
    @DisplayName("上游 400 → 原样透传 400，且一次都不碰备份渠道")
    void upstream400IsPassedThrough() {
        stubDeepseek(400, "{\"error\":\"messages is required\"}");
        stubMoonshot(200, okBody("不该被调用"));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                // 客户端需要知道"是我的请求错了"，而不是"网关坏了"
                .jsonPath("$.error.code").isEqualTo("invalid_request_error")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error");

        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/moonshot/chat/completions")));
    }

    @Test
    @DisplayName("上游 401 → 对外 502（密钥是我们的问题，不是客户端的），且不换渠道")
    void upstream401IsOurProblemNotTheClients() {
        stubDeepseek(401, "{\"error\":\"invalid api key\"}");

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("upstream_auth_error");

        // ★ 关键：401 不转移。换过去只会把"我们的 key 过期了"藏起来 ★
        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/moonshot/chat/completions")));
    }

    @Test
    @DisplayName("上游 429 且没有备份 → 对外 429，并把 Retry-After 透出去")
    void upstream429CarriesRetryAfter() {
        // ★ 为什么用 anthropic 渠道来验这一条 ★
        // deepseek 和 moonshot 同组，429 会被转移到健康的备份（用户拿到 200）——
        // 那对用户是好事，但就看不到 429 本身了。anthropic 单独一组、没有备份，
        // 于是"429 最终长什么样"才能被断言到。
        UPSTREAM.stubFor(post(urlEqualTo("/anthropic/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "30")
                        .withBody("{\"error\":\"slow down\"}")));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"anthropic","model":"claude-sonnet-5",
                         "messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "30")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("upstream_rate_limited");
    }

    // ------------------------------------------------------------------ 假上游

    private void stubDeepseek(int status, String body) {
        UPSTREAM.stubFor(post(urlEqualTo("/deepseek/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubMoonshot(int status, String body) {
        UPSTREAM.stubFor(post(urlEqualTo("/moonshot/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private String okBody(String content) {
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "some-upstream-model",
                  "choices": [{
                    "index": 0,
                    "message": { "role": "assistant", "content": "%s" },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
                }
                """.formatted(content);
    }
}
