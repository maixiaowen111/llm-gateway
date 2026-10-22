package io.github.maixiaowen.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * <b>G10 的端到端验收：没有合法 API Key，请求根本到不了上游。</b>
 *
 * <p><b>★ 本类最重要的一条断言，不是"返回 401"，而是 {@code verify(0, ...)} ★</b>
 * <p>一个"先转发、再返回 401"的实现同样能让 401 出现在响应里 ——
 * 但那时<b>钱已经花了、上游已经被调用了</b>，401 只是给攻击者看的装饰。
 * <p><b>认证的价值一半在"拒绝"，另一半在"拒绝得足够早"。</b>
 * 所以每一条拒绝用例都同时断言上游调用次数为 0。
 *
 * <p><b>★ 为什么不用 Redis ★</b>
 * <p>本类验的是"身份是否被证明"，不是"身份是否被记账"。
 * 限定流/计费的归属由 {@code RateLimitEndToEndTest} / {@code BillingEndToEndTest} 验，
 * 它们已经证明了两者都走 {@code ClientIdentity}。
 * 本类只补最关键的一环：<b>这个身份现在由 key 决定，而不是由客户端自报。</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("G10 端到端：认证失败不得触达上游")
class ClientAuthEndToEndTest {

    private static final String ALICE_KEY = "sk-gw-alice-0123456789abcdef";
    private static final String BOB_KEY = "sk-gw-bob-0123456789abcdef";
    private static final String ADMIN_TOKEN = "test-admin-token";

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

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.default-channel", () -> "deepseek");
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key");
        // 保持和契约测试同样的隔离：本类只关心认证，不要让转移把请求带到真实上游
        registry.add("gateway.providers.routing.failover-enabled", () -> false);
        registry.add("gateway.resilience.max-retry-attempts", () -> 0);

        registry.add("gateway.auth.enabled", () -> "true");
        registry.add("gateway.auth.keys",
                () -> "alice:" + sha256(ALICE_KEY) + ",bob:" + sha256(BOB_KEY));
        registry.add("gateway.admin.token", () -> ADMIN_TOKEN);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(10))
                .build();

        UPSTREAM.resetAll();
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"c1","object":"chat.completion","created":1,"model":"upstream-alias",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                                             "finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                                """)));
    }

    @Test
    @DisplayName("不带 key → 401，且上游调用次数为 0")
    void missingKeyIsRejectedBeforeUpstream() {
        postChat(null, null)
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer .*")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("missing_api_key");

        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("错误 key → 401，且上游调用次数为 0")
    void invalidKeyIsRejectedBeforeUpstream() {
        postChat("Bearer sk-gw-wrong-key-0123456789", null)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("authentication_error")
                .jsonPath("$.error.code").isEqualTo("invalid_api_key");

        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("合法 Bearer key → 200，请求正常到达上游")
    void validBearerKeyReachesUpstream() {
        postChat("Bearer " + ALICE_KEY, null)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("ok");

        UPSTREAM.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("X-Api-Key 也可以，兼容不认识 Bearer 的 SDK")
    void xApiKeyIsAccepted() {
        postChatWithHeaders(BOB_KEY, null, null)
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("两个头指向不同凭证 → 401（不猜哪一个才对）")
    void conflictingCredentialsAreRejected() {
        postChatWithHeaders(ALICE_KEY, "Bearer " + BOB_KEY, null)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("conflicting_credentials");

        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("Authorization 用了不支持的方案 → 401，而不是猜")
    void unsupportedAuthorizationSchemeIsRejected() {
        postChat("Basic dXNlcjpwYXNz", null)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("unsupported_authorization_scheme");

        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("★ 伪造 X-Client-Id 不能绕过认证 ★")
    void spoofedClientIdHeaderDoesNotBypassAuth() {
        // 关键：带了 X-Client-Id 但【没有】key —— 仍然是 401。
        postChatWithHeaders(null, null, "alice")
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("missing_api_key");

        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("探针不需要认证（否则 K8s 会重启一个健康的 Pod）")
    void pingStaysPublic() {
        client.get().uri("/ping").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("管理接口用自己的 token，不接受客户端 key 代替")
    void adminUsesItsOwnToken() {
        client.get().uri("/admin/channels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ALICE_KEY)
                .exchange()
                .expectStatus().isForbidden();

        client.get().uri("/admin/channels")
                .header("X-Admin-Token", ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec postChat(String authorization, String declaredClientId) {
        return postChatWithHeaders(null, authorization, declaredClientId);
    }

    private WebTestClient.ResponseSpec postChatWithHeaders(String xApiKey,
                                                           String authorization,
                                                           String declaredClientId) {
        WebTestClient.RequestBodySpec spec = client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);

        if (xApiKey != null) {
            spec = spec.header("X-Api-Key", xApiKey);
        }
        if (authorization != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (declaredClientId != null) {
            spec = spec.header("X-Client-Id", declaredClientId);
        }

        return spec.bodyValue("""
                        {"model":"any-model","messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
