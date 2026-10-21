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
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * <b>G4 的端到端验收：429 + Retry-After 真的出现在 HTTP 响应上。</b>
 *
 * <p><b>★ 它和 {@code RateLimitConcurrencyTest} 的分工 ★</b>
 * <ul>
 *   <li>那边验的是<b>算法</b>："并发下放行数严格等于桶容量"（不超卖）；</li>
 *   <li>这边验的是<b>接线</b>："限流真的挂在了请求链路上，而且对外表现是 429 + Retry-After"。</li>
 * </ul>
 * <b>两个都要有。</b>一个算法完全正确、但从没被调用过的限流器，
 * 和一个算法错了的限流器，在生产上的表现是一样的 —— <b>都等于没有限流。</b>
 *
 * <p><b>★ 本类里最重要的一条断言：{@code UPSTREAM.verify(3, ...)} ★</b>
 * <p>它证明<b>被限流的请求压根没有打到上游</b>。
 * 只断言"第 4 个请求返回 429"是不够的 —— 一个先转发再去拒绝的实现同样会返回 429，
 * 而它已经把三次上游调用变成了四次：<b>钱照花，429 只是给用户看的安慰。</b>
 * <p><b>限流的价值一半在"拒绝"，另一半在"拒绝得足够早"。</b>
 *
 * <p><b>⚠️ 运行前提：本机 Docker 必须可用</b>（Testcontainers 起 {@code redis:7}）。
 * 这里是真的需要一个能跑的 Redis —— 因为本类同时也要证明
 * <b>启动自检确实能连上 Redis 并放行启动</b>，那是 G4 里"不做静默的能力缺失"那一条的实现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("G4 端到端：429 + Retry-After")
class RateLimitEndToEndTest {

    /** 桶容量。用 3 是为了让"第 4 个请求被拒"读起来最直观 */
    private static final int CAPACITY = 3;

    /** 假上游。★ 用它来证明"被限流的请求没打到上游" */
    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    /** 和 {@code RateLimitConcurrencyTest} 同一个理由：static 块启动，时序确定 */
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static {
        try {
            REDIS.start();
        } catch (Throwable t) {
            // ⚠️ 刻意不猜原因 —— 理由见 RateLimitConcurrencyTest 里同一段注释
            throw new IllegalStateException(
                    "G4 的端到端测试需要一个真实 Redis（Testcontainers 起 redis:7 容器）。"
                            + "本机 Docker 需可用；原始错误见下方 caused by。", t);
        }
    }

    @DynamicPropertySource
    static void wireEverythingUp(DynamicPropertyRegistry registry) {
        // ---- 上游：指向假服务器，不烧钱 ----
        registry.add("gateway.providers.default-channel", () -> "deepseek");
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key");

        // ---- Redis：指向 Testcontainers 起的那个容器 ----
        // ⚠️ 前缀是 spring.data.redis.*（Boot 3），不是 spring.redis.*（Boot 2.7）。
        //    写错了不会报错，只会连不上 —— 但这里连不上的话启动自检会直接拒绝启动，
        //    所以在这个测试里，前缀写错的表现是"应用起不来"而不是"限流不生效"。
        //    **这正是启动自检的价值：把一个静默失败变成了一个响亮的失败。**
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));

        // ---- 限流：打开，容量 3，不回填 ----
        // 不回填（refill-per-second=0）是刻意的：这样"第 4 个请求必然被拒"是一个确定的结论，
        // 不会因为回填速度和时间的关系变成一条偶尔绿偶尔红的测试。
        registry.add("gateway.rate-limit.enabled", () -> "true");
        registry.add("gateway.rate-limit.capacity", () -> String.valueOf(CAPACITY));
        registry.add("gateway.rate-limit.refill-per-second", () -> "0");

        // ---- ★ 刻意【不】覆盖 timeout-millis / fail-open / key-prefix ★ ----
        // 这三个键留给 src/main/resources/application.yml 提供，本类因此顺手成了一个
        // 【键名绑定的回归守卫】—— 这是被一个真实的 bug 换来的：
        //
        //   Java 字段 timeoutMillis 归一化成 "timeoutmillis"，
        //   而 YAML 里当时写的是 timeout-ms，归一化成 "timeoutms" —— 对不上。
        //   ★ 宽松绑定【不会把 ms 展开成 millis】，于是这个键被整个忽略，
        //     long 拿到默认值 0。同一个前缀下另外几个键全都绑得好好的，
        //     所以现象看起来完全不像"键名问题"，倒像"YAML 缩进错误"。
        //
        // 现在这个键由 application.yml 提供，一旦有人把键名改回去（或改成别的对不上的写法），
        // 本测试会因为启动自检/参数校验直接变红 —— 而不是悄悄跑着 timeout=0。
        // **让"配置键名写错"有一个测试能抓住，比在文档里写一句"注意别写错"可靠得多。**
        //
        // ⚠️ 这也意味着 timeout 用的是 yml 里的生产默认值（200ms），不是测试专用值：
        //    这是刻意的 —— 我们要验的正是"那份真实的 YAML 绑得上"。
    }

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient webTestClient;

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
                                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                                """)));

        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .build();
    }

    @AfterAll
    static void tearDown() {
        UPSTREAM.stop();
        REDIS.stop();
    }

    // ========================================================================

    @Test
    @DisplayName("★ 容量 3：前 3 个 200，第 4 个 429 + Retry-After，且第 4 个没打到上游")
    void fourthRequestIsRateLimited() {
        String clientId = "e2e-drain";

        // 前 3 个应该全部通过，而且 X-RateLimit-Remaining 逐次递减
        for (int i = 0; i < CAPACITY; i++) {
            int expectedRemaining = CAPACITY - i - 1;

            webTestClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Id", clientId)
                    .bodyValue(requestBody())
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Limit", String.valueOf(CAPACITY))
                    .expectHeader().valueEquals("X-RateLimit-Remaining", String.valueOf(expectedRemaining));
        }

        // 第 4 个：桶空了
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", clientId)
                .bodyValue(requestBody())
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Limit", String.valueOf(CAPACITY))
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                // ★ Retry-After 是 429 响应里【唯一被标准客户端真正消费】的字段。
                //   OpenAI SDK、requests 的重试适配器、各种退避库都读它。
                //   只返回 429 而不给这个头，等于把"等多久"交给客户端猜。
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("rate_limit_exceeded")
                .jsonPath("$.error.type").isEqualTo("rate_limit_error");

        // ★★ 本类最重要的一条断言 ★★
        //    被限流的请求【压根没到上游】。只断言状态码是不够的 ——
        //    一个"先转发、再拒绝"的实现同样返回 429，但它已经把 3 次上游调用变成了 4 次：
        //    钱照花，429 只是给用户看的安慰。
        //    **限流的价值一半在「拒绝」，另一半在「拒绝得足够早」。**
        UPSTREAM.verify(CAPACITY, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("限流维度隔离：一个客户端被打满，不影响另一个客户端")
    void bucketsAreIsolatedPerClient() {
        String noisy = "e2e-noisy";
        String quiet = "e2e-quiet";

        for (int i = 0; i < CAPACITY; i++) {
            postAs(noisy).expectStatus().isOk();
        }
        postAs(noisy).expectStatus().isEqualTo(429);

        // ★ 这条守的是"限流维度选得对不对"。
        //   如果所有客户端共用一个桶（比如 key 拼错、或者压根没拼 clientId），
        //   那么【任何一个客户端的 bug 都会把所有人一起限死】——
        //   那比不限流还糟：不限流只烧钱，这种限流会同时烧钱 + 掉用户。
        postAs(quiet)
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", String.valueOf(CAPACITY - 1));
    }

    @Test
    @DisplayName("缺 X-Client-Id 时退化到来源 IP 限流，而不是全放行")
    void fallsBackToRemoteAddressWhenNoClientId() {
        // ★ 这条守的是一个很容易写漏的洞：只在"带了 X-Client-Id"时才限流。
        //   那样的话，攻击者只要【不发这个头】就绕过了全部限流 ——
        //   一个可以被客户端单方面关掉的防护，等于没有防护。
        //   正确的默认是"缺少身份标识时按来源 IP 限"，宁可误伤也不能放行。
        for (int i = 0; i < CAPACITY; i++) {
            webTestClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody())          // 刻意不带 X-Client-Id
                    .exchange()
                    .expectStatus().isOk();
        }

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody())
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /**
     * 带客户端标识发一个请求。
     *
     * <p><b>⚠️ 这个方法名不能叫 {@code post}</b>：本类 static import 了 WireMock 的
     * {@code post(UrlPattern)} 用来搭假上游，而<b>类内声明的实例方法会遮蔽同名的静态导入</b> ——
     * 一旦这里也叫 {@code post}，上面 {@code post(urlEqualTo(...))} 就会去匹配
     * {@code post(String)}，报出"UrlPattern 无法转换为 String"这种和真正原因完全无关的错。
     * <p><b>起个不会撞的名字，比记住这条规则更划算。</b>
     */
    private ResponseSpec postAs(String clientId) {
        return webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", clientId)
                .bodyValue(requestBody())
                .exchange();
    }

    private Map<String, Object> requestBody() {
        return Map.of(
                "model", "any-model",
                "messages", List.of(Map.of("role", "user", "content", "hello")));
    }
}
