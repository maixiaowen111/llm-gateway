package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.billing.BillingService;
import io.github.maixiaowen.gateway.billing.BillingService.UsageSnapshot;
import io.github.maixiaowen.gateway.billing.Reservation;
import io.github.maixiaowen.gateway.dto.ChatCompletionRequest;
import io.github.maixiaowen.gateway.dto.ChatCompletionResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G7 的验收物：预扣 → 实结算 → 多退少补 → 断流收费。</b>
 *
 * <p><b>★ 为什么计费必须用"真 Redis + 端到端 HTTP"来验 ★</b>
 * <p>计费的 bug 有一个共同特征：<b>不会抛异常，只会让数字悄悄不对</b>。
 * 用 Mock 出来的 Redis 只能证明"我们调用了脚本"，不能证明"脚本连起来之后账是平的"。
 * 所以这里起真的 {@code redis:7}，让 Lua 真的执行、余额真的变化、
 * 再通过 {@code /admin/usage} 把最终账目读回来对账。
 * <p><b>对账测试的价值在于：它验的是"钱对不对"，而不是"代码跑没跑通"。</b>
 *
 * <p><b>★ 本类最值钱的一条：客户端断流也要收费 ★</b>
 * <p>见 {@code disconnectAfterFirstByteChargesReservation}。
 * 大多数网关的计费实现只挂在"正常完成"和"上游报错"上，
 * <b>而用户关掉页面走的是 CANCEL —— 两条常规路径都不会触发。</b>
 * 结果是：<b>越是用量大的用户，越容易因为中途取消而白嫖。</b>
 * 这条测试用真实的 SSE 分块 + 主动取消来复现这个场景，并断言"按预扣额度扣了钱"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("G7 端到端：预扣、结算、断流收费")
class BillingEndToEndTest {

    private static final String ADMIN_TOKEN = "test-admin-token";
    private static final String KEY_PREFIX = "gw:billing:test";

    /** 新客户初始额度。所有期望值都从这个数推出来，改它要同步改断言 */
    private static final long INITIAL_CREDITS = 1000L;

    /** "hello" 的 prompt 估算：内容 5 + role 4 = 9 字符 → ceil(9/2)=5，再加 4 = 9 */
    private static final int HELLO_PROMPT_ESTIMATE = 9;

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static {
        try {
            REDIS.start();
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "G7 的验收测试需要一个真实 Redis（Testcontainers 起 redis:7 容器）。"
                            + "本机 Docker 需可用；原始错误见下方 caused by。", t);
        }
    }

    @DynamicPropertySource
    static void wireEverythingUp(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.default-channel", () -> "deepseek");
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key");

        // 本类只验计费：把重试/转移/熔断全关掉，避免它们改变"调了几次上游"
        registry.add("gateway.resilience.enabled", () -> "false");
        registry.add("gateway.providers.routing.failover-enabled", () -> "false");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));

        // ★ 计费打开 —— 启动自检会真的连一次 Redis。
        //   连不上的话应用根本起不来，这条测试也就不会"假装通过"。
        registry.add("gateway.billing.enabled", () -> "true");
        registry.add("gateway.billing.initial-credits", () -> String.valueOf(INITIAL_CREDITS));
        registry.add("gateway.billing.prompt-token-credits", () -> "1");
        registry.add("gateway.billing.completion-token-credits", () -> "3");
        registry.add("gateway.billing.default-max-tokens", () -> "1024");
        registry.add("gateway.billing.reservation-ttl-seconds", () -> "900");
        registry.add("gateway.billing.key-prefix", () -> KEY_PREFIX);

        registry.add("gateway.admin.token", () -> ADMIN_TOKEN);
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private BillingService billing;

    @Autowired
    private ReactiveStringRedisTemplate redis;

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
                                 "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
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
    // 1. 正常路径：按 usage 结算，多退少补
    // ========================================================================

    @Test
    @DisplayName("★ 预扣 309、实际消耗 70 → 退回 239，余额与用量都对得上")
    void settlesByUsageAndRefundsDifference() {
        String clientId = "e2e-settle";

        postChat(clientId, false, 100).expectStatus().isOk();

        // 预扣 = 输入估算 9 × 1 + 输出上限 100 × 3 = 309
        // 实扣 = 上游 usage 10 × 1 + 20 × 3 = 70
        // 退回 = 309 - 70 = 239；余额 = 1000 - 309 + 239 = 930
        UsageSnapshot snapshot = usage(clientId);
        assertThat(snapshot.requests()).isEqualTo(1);
        assertThat(snapshot.promptTokens()).isEqualTo(10);
        assertThat(snapshot.completionTokens()).isEqualTo(20);
        assertThat(snapshot.totalTokens()).isEqualTo(30);
        assertThat(snapshot.creditsSpent()).isEqualTo(70);
        assertThat(snapshot.balance()).isEqualTo(930);

        // ★ 预扣凭据必须已经消失 ★
        //   如果结算后凭据还在，TTL 到期时它会被"数一遍"，运维无法区分
        //   "还没结算"和"结算失败"—— 账目的状态必须唯一。
        assertThat(countReservations(clientId)).isZero();
    }

    // ========================================================================
    // 2. 余额不足：402，且一分钱上游成本都不花
    // ========================================================================

    @Test
    @DisplayName("★ 余额不足 → 402，且请求没有打到上游；充值后同一请求成功")
    void insufficientQuotaReturns402WithoutCallingUpstream() {
        String clientId = "e2e-poor";

        // 上限 10000：预扣 = 9 + 30000，远超初始 1000
        postChat(clientId, false, 10_000)
                .expectStatus().isEqualTo(402)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("insufficient_quota")
                .jsonPath("$.error.type").isEqualTo("insufficient_quota")
                .jsonPath("$.error.message").value(message ->
                        assertThat((String) message).contains("余额"));

        // ★★ 这条断言和限流那边是同一条道理：拒绝得足够早 ★★
        //    如果先发上游、再发现没钱，402 只是给用户看的安慰 ——
        //    上游的 token 已经烧掉了，而且这个接口可以被刷成"免费调用 + 一堆 402"。
        UPSTREAM.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));

        // 402 的请求不进用量：它压根没花上游一分钱
        UsageSnapshot rejected = usage(clientId);
        assertThat(rejected.requests()).isZero();
        assertThat(rejected.creditsSpent()).isZero();
        assertThat(rejected.balance()).isEqualTo(INITIAL_CREDITS);

        // 充值 50000 → 余额 51000
        webTestClient.post()
                .uri("/admin/clients/" + clientId + "/credits")
                .header("X-Admin-Token", ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amount", 50_000))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.balance").isEqualTo(51_000);

        // 同一个请求现在能过了：预扣 30009（余额够），实扣 70，退回 29939
        postChat(clientId, false, 10_000).expectStatus().isOk();
        UsageSnapshot settled = usage(clientId);
        assertThat(settled.requests()).isEqualTo(1);
        assertThat(settled.creditsSpent()).isEqualTo(70);
        assertThat(settled.balance()).isEqualTo(51_000 - 70);
    }

    // ========================================================================
    // 3. 上游失败：全额退款，且不计入用量
    // ========================================================================

    @Test
    @DisplayName("上游 500 → 用户拿到 502，预扣全额退回，用量为 0")
    void upstreamFailureRefundsEntireReservation() {
        String clientId = "e2e-refund";
        UPSTREAM.resetAll();
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        postChat(clientId, false, 100).expectStatus().isEqualTo(502);

        UsageSnapshot snapshot = usage(clientId);
        // ★ 余额必须一分不少地回来 ★
        //   预扣是"冻结"，不是"收费"：上游没交付任何东西，这次请求就不该产生成本。
        //   少退一分钱，都意味着"失败也要付钱"—— 用户很快就会用脚投票。
        assertThat(snapshot.balance()).isEqualTo(INITIAL_CREDITS);
        assertThat(snapshot.requests()).isZero();
        assertThat(snapshot.creditsSpent()).isZero();
        assertThat(countReservations(clientId)).isZero();
    }

    // ========================================================================
    // 4. 幂等：结算只能生效一次
    // ========================================================================

    @Test
    @DisplayName("★ 同一笔预扣结算两次：第二次不扣钱、不重复记用量")
    void settleIsIdempotent() {
        String clientId = "e2e-idempotent";
        ChatCompletionRequest request = new ChatCompletionRequest(
                "any-model", List.of(new ChatCompletionRequest.Message("user", "hello")),
                false, "deepseek", 100);

        Reservation reservation = billing.reserve(clientId, request).block(Duration.ofSeconds(3));
        assertThat(reservation).isNotNull();

        billing.settle(reservation, new ChatCompletionResponse.Usage(10, 20, 30), "usage")
                .block(Duration.ofSeconds(3));
        // 第二次：凭据已被第一次 DEL，Lua 返回 missing，调用方什么都不做
        billing.settle(reservation, new ChatCompletionResponse.Usage(10, 20, 30), "usage")
                .block(Duration.ofSeconds(3));

        UsageSnapshot snapshot = billing.usage(clientId).block(Duration.ofSeconds(3));
        assertThat(snapshot).isNotNull();
        // 只记一次：如果第二次也生效，这里会是 requests=2、花费=140、余额=860
        assertThat(snapshot.requests()).isEqualTo(1);
        assertThat(snapshot.creditsSpent()).isEqualTo(70);
        assertThat(snapshot.balance()).isEqualTo(930);
    }

    // ========================================================================
    // 5. 预扣凭据的 TTL：崩溃时的防泄漏
    // ========================================================================

    @Test
    @DisplayName("预扣凭据带 TTL，进程崩溃后不会永久冻结用户额度")
    void reservationHasTtl() {
        String clientId = "e2e-ttl";
        ChatCompletionRequest request = new ChatCompletionRequest(
                "any-model", List.of(new ChatCompletionRequest.Message("user", "hello")),
                false, "deepseek", 100);

        Reservation reservation = billing.reserve(clientId, request).block(Duration.ofSeconds(3));
        assertThat(reservation).isNotNull();

        // Spring Data Redis 2.7 的 getExpire 返回 Mono<Duration>（不是秒数）
        Duration ttl = redis.getExpire(KEY_PREFIX + ":reservation:" + reservation.id()).block();
        // ★ 只断言"存在且不超过配置值" ★
        //   精确等于 900 会因为几毫秒的执行耗时变成 899，那是测试在跟自己较劲。
        //   我们要守的性质是"它有 TTL"，不是"它的秒数是精确的"。
        assertThat(ttl).isNotNull();
        assertThat(ttl.toSeconds()).isBetween(1L, 900L);
    }

    // ========================================================================
    // 6. ★ 断流收费：本类最重要的一条
    // ========================================================================

    @Test
    @DisplayName("★ 客户端收到首块后断开 → 按预扣额度收费，而不是免费")
    void disconnectAfterFirstByteChargesReservation() throws InterruptedException {
        String clientId = "e2e-disconnect";
        UPSTREAM.resetAll();

        // 三块 SSE，用 3 秒分三次吐。第一块到达后客户端立刻取消，
        // 这样网关一定能看到"吐过字 + CANCEL"，但看不到完成信号。
        String sse = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"upstream-alias\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"第一块\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"upstream-alias\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"第二块\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]\n\n";

        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sse)
                        .withChunkedDribbleDelay(3, 3000)));

        CountDownLatch firstChunk = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        WebClient.create("http://localhost:" + gatewayPort)
                .post()
                .uri("/v1/chat/completions")
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(streamBody(100))
                .retrieve()
                .bodyToFlux(String.class)
                // take(1) 收到第一块后主动 cancel —— 等价于用户关掉页面
                .take(1)
                .subscribe(
                        chunk -> firstChunk.countDown(),
                        failure::set);

        assertThat(firstChunk.await(10, TimeUnit.SECONDS))
                .as("10 秒内应收到第一个流式块（失败原因：%s）", failure.get())
                .isTrue();

        // 结算在 doFinally(CANCEL) 里异步发出，轮询等它落地
        UsageSnapshot snapshot = awaitUsage(clientId, 1);

        // 预扣 = 9 + 100×3 = 309。断流按【预扣额度】收费，而不是按收到的字数。
        // ★ 为什么按预扣收：客户端断开后，上游可能已经生成了远超我们收到的内容；
        //   按收到的字收费会系统性少收 —— 而成本已经真实发生在我们的账单上了。★
        assertThat(snapshot.requests()).isEqualTo(1);
        assertThat(snapshot.creditsSpent()).isEqualTo(HELLO_PROMPT_ESTIMATE + 100L * 3);
        assertThat(snapshot.balance()).isEqualTo(INITIAL_CREDITS - (HELLO_PROMPT_ESTIMATE + 300L));
        // 断流时拿不到完整 usage，所以 token 统计是 0；账目以 credits 为准
        assertThat(snapshot.promptTokens()).isZero();
        assertThat(snapshot.completionTokens()).isZero();
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private WebTestClient.ResponseSpec postChat(String clientId, boolean stream, int maxTokens) {
        return webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", clientId)
                .bodyValue(stream ? streamBody(maxTokens) : body(maxTokens))
                .exchange();
    }

    private Map<String, Object> body(int maxTokens) {
        return Map.of(
                "model", "any-model",
                "provider", "deepseek",
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", "hello")));
    }

    private Map<String, Object> streamBody(int maxTokens) {
        return Map.of(
                "model", "any-model",
                "provider", "deepseek",
                "stream", true,
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", "hello")));
    }

    private UsageSnapshot usage(String clientId) {
        UsageSnapshot snapshot = billing.usage(clientId).block(Duration.ofSeconds(3));
        assertThat(snapshot).as("应能从 Redis 读到 client=%s 的用量", clientId).isNotNull();
        return snapshot;
    }

    /** 结算可能异步落地（CANCEL 路径），所以轮询而不是 sleep 一个魔法数字 */
    private UsageSnapshot awaitUsage(String clientId, long expectedRequests) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        UsageSnapshot last = null;
        while (System.nanoTime() < deadline) {
            last = billing.usage(clientId).block(Duration.ofSeconds(2));
            if (last != null && last.requests() >= expectedRequests) {
                return last;
            }
            Thread.sleep(50);
        }
        assertThat(last).as("10 秒内没看到结算落地").isNotNull();
        return last;
    }

    /**
     * 数一下这个客户端名下还有多少条预扣凭据。
     *
     * <p>用 {@code KEYS} 而不是 {@code SCAN} —— <b>仅限测试</b>。
     * 生产环境禁止使用 {@code KEYS}：它会阻塞 Redis 单线程，在数据量大时直接拖垮整个实例。
     * 这里的 key 空间只属于一个测试客户端，可以接受。
     */
    private long countReservations(String clientId) {
        Long count = redis.keys(KEY_PREFIX + ":reservation:" + clientId + ":*")
                .count()
                .block(Duration.ofSeconds(3));
        return count == null ? 0 : count;
    }
}
