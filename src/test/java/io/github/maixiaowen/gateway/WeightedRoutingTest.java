package io.github.maixiaowen.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * <b>G6 的验收物：加权分流、按 model 路由、运行期摘/恢复渠道。</b>
 *
 * <p><b>为什么权重测试要"数 12 个请求的分布"</b>：
 * 只发 1 个请求，你只能知道"它选中了某条"，完全测不出权重。
 * 5:1 的配置下，12 次里应当是 10:2 —— <b>而且平滑加权轮询保证的是
 * "每个 6 次窗口都接近 5:1"</b>，不是"长期平均对就行"。
 * 如果换成"按权重随机"，12 次跑出 8:4 甚至 6:6 都是正常的，
 * 那样的网关在计费对账时会被业务方追着问。
 *
 * <p><b>测试顺序是刻意的</b>：加权分布必须最先跑（轮询状态是干净的），
 * 管理接口的摘除测试必须最后跑（它会改变渠道状态，之后要恢复）。
 * 顺序依赖不是"测试写得不好"，而是<b>全局状态本来就有顺序</b> ——
 * 与其假装它不存在，不如显式声明并让它在失败时容易定位。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("G6：加权路由与渠道管理")
class WeightedRoutingTest {

    private static final String ADMIN_TOKEN = "test-admin-token-please-change";

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
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port() + "/deepseek");
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "k1");
        registry.add("gateway.providers.channels.moonshot.base-url",
                () -> "http://localhost:" + UPSTREAM.port() + "/moonshot");
        registry.add("gateway.providers.channels.moonshot.api-key", () -> "k2");
        registry.add("gateway.providers.channels.anthropic.base-url",
                () -> "http://localhost:" + UPSTREAM.port() + "/anthropic");
        registry.add("gateway.providers.channels.anthropic.api-key", () -> "k3");

        registry.add("gateway.providers.routing.auto-route", () -> true);
        registry.add("gateway.admin.token", () -> ADMIN_TOKEN);
        // 关掉熔断重试：本类只验"选谁"，别让失败恢复逻辑干扰
        registry.add("gateway.resilience.enabled", () -> false);
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
        stubOk("/deepseek", "deepseek 的回答");
        stubOk("/moonshot", "moonshot 的回答");
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("权重 5:1 → 12 次请求按 10:2 分配，且是平滑的")
    void weightsShapeTheTraffic() {
        // model 既不被任何渠道认领 → 走默认组的加权兜底
        for (int i = 0; i < 12; i++) {
            callWithModel("unknown-model");
        }

        UPSTREAM.verify(10, postRequestedFor(urlEqualTo("/deepseek/chat/completions")));
        UPSTREAM.verify(2, postRequestedFor(urlEqualTo("/moonshot/chat/completions")));
    }

    @Test
    @Order(2)
    @DisplayName("model 命中 channels[].models → 即使权重低也优先走认领它的渠道")
    void modelMappingBeatsWeights() {
        for (int i = 0; i < 3; i++) {
            callWithModel("moonshot-v1-8k");
        }

        // 权重再低也不影响"按模型认领" —— 权重只在同一组内分配
        UPSTREAM.verify(3, postRequestedFor(urlEqualTo("/moonshot/chat/completions")));
        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/deepseek/chat/completions")));
    }

    @Test
    @Order(3)
    @DisplayName("显式点名渠道 → 无条件服从，不参与加权")
    void explicitProviderWins() {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"moonshot","model":"whatever",
                         "messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        UPSTREAM.verify(1, postRequestedFor(urlEqualTo("/moonshot/chat/completions")));
        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/deepseek/chat/completions")));
    }

    @Test
    @Order(4)
    @DisplayName("管理接口：无 token 403；有 token 能看面板；摘掉全部渠道后请求 503")
    void adminCanInspectAndDisableChannels() {
        // ① 没有 token：一律拒绝（fail-closed）
        client.get().uri("/admin/channels")
                .exchange()
                .expectStatus().isForbidden();

        // ② 有 token：能看到渠道面板（三个渠道，含熔断状态和权重）
        client.get().uri("/admin/channels")
                .header("X-Admin-Token", ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(3)
                // 渠道顺序由 Map 迭代决定，不能按下标断言；用"名字集合包含"更稳。
                // 这也是一条测试经验：**断言顺序无关的事实，别把实现细节锁进测试。**
                .jsonPath("$.channels[*].name").value(names ->
                        org.assertj.core.api.Assertions.assertThat((java.util.List<String>) names)
                                .contains("deepseek", "moonshot", "anthropic"));

        // ③ 运行期摘掉默认组的全部渠道
        toggle("deepseek", false);
        toggle("moonshot", false);

        // 此时自动路由在 default 组里没有任何可用渠道 → 503，而不是继续打上游
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"unknown-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("no_healthy_channel");

        // ④ 恢复现场 —— 让后面的用例（以及人工排查）看到的是正常状态
        toggle("deepseek", true);
        toggle("moonshot", true);

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"unknown-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk();
    }

    // ------------------------------------------------------------------ 工具

    private void callWithModel(String model) {
        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"%s","messages":[{"role":"user","content":"hi"}]}
                        """.formatted(model))
                .exchange()
                .expectStatus().isOk();
    }

    private void toggle(String channel, boolean enabled) {
        client.post().uri("/admin/channels/" + channel + "/enabled")
                .header("X-Admin-Token", ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":" + enabled + "}")
                .exchange()
                .expectStatus().isOk();
    }

    private void stubOk(String prefix, String content) {
        UPSTREAM.stubFor(post(urlEqualTo(prefix + "/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id":"chatcmpl-w","object":"chat.completion","created":1700000000,
                                  "model":"upstream-model",
                                  "choices":[{"index":0,
                                    "message":{"role":"assistant","content":"%s"},
                                    "finish_reason":"stop"}],
                                  "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}
                                }
                                """.formatted(content))));
    }
}
