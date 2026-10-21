package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.limiter.NaiveTokenBucket;
import io.github.maixiaowen.gateway.limiter.RateLimitDecision;
import io.github.maixiaowen.gateway.limiter.RateLimitProperties;
import io.github.maixiaowen.gateway.limiter.RedisTokenBucketLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>★★★ G4 的验收物：并发压测下不超卖。这是整段 G4 存在的理由。★★★</b>
 *
 * <p>路线图里给这一段定的验收标准原话是：
 * <blockquote>「G4 的验收物就是"并发压测下不超卖，有测试"。
 * 这个测试要起 N 个线程同时打，断言放行数<b>严格等于</b>桶容量。」</blockquote>
 *
 * <p><b>★ 本类最重要的一条测试不是第一条，是第二条 ★</b>
 * <p>第一条（{@link #neverOversellsUnderConcurrency}）证明"我的实现没超卖"。
 * 第二条（{@link #naiveImplementationDoesOversell}）用<b>完全相同的测试代码</b>
 * 跑一个刻意不用 Lua 的实现，证明"超卖真的会发生"。
 * <p><b>只有两条一起，结论才成立：</b>
 * <ul>
 *   <li>只有第一条 → 可能是"这个测试压根测不出超卖"，绿的也没有什么意义；</li>
 *   <li>只有第二条 → 只是发现了一个 bug；</li>
 *   <li>两条一起 → <b>"Lua 是拦住超卖的那一道墙"，这是个被验证过的因果，不是一句断言。</b></li>
 * </ul>
 * <b>写测试时最值得花时间的地方，就是想清楚"如果这个功能是错的，我的测试会不会红"。</b>
 *
 * <p><b>★ 为什么用 Testcontainers 起一个真 Redis，而不是 mock 一个 ★</b>
 * <p>因为本测试要验的东西<b>恰恰是 Redis 自己的行为</b>：Lua 脚本在 Redis 内部的原子性。
 * mock 掉 Redis，等于把唯一要验的那个东西替换成了我自己写的假货 ——
 * "我的 mock 说它是原子的"，这句结论一文不值。
 * <p><b>判据：测试替身只能替换掉"无关的部分"。这里要验的就是 Redis，它不能是替身。</b>
 *
 * <p><b>★ 本类刻意不起 Spring ★</b>
 * <p>被测的是限流器这个纯组件（给它一个模板、几个参数，它就该给出确定结果）。
 * 起 Spring 只会让它变慢、变脆，并掩盖"它其实不依赖容器"这个事实 ——
 * 和 {@code ProviderRegistryTest} 是同一条纪律。
 * <p>所以要的 {@link ReactiveStringRedisTemplate} 是自己手工搭的，
 * 连接直接指向 Testcontainers 起的那个容器。
 *
 * <p><b>⚠️ 运行前提：本机 Docker 必须可用</b>（Testcontainers 起 {@code redis:7}）。
 * 这是集成测试的性质决定的，不是可以绕过的配置问题 —— 见上面那段。
 */
@DisplayName("G4 并发下不超卖")
class RateLimitConcurrencyTest {

    /** 桶容量。用小值是为了让"放行数"一眼就能数清楚 */
    private static final int CAPACITY = 5;

    /** 并发请求数。远大于容量，才有机会超卖 */
    private static final int CONCURRENCY = 32;

    /**
     * <b>Testcontainers 容器在 static 块里手工启动，而不是用 {@code @Container} 注解。</b>
     *
     * <p>这是踩过坑之后的写法：{@code @Container} 的启动发生在 {@code TestcontainersExtension}
     * 的回调里，而 Spring 的 {@code @DynamicPropertySource} 求值时机和它的先后关系<b>没有保证</b> ——
     * 一旦 Spring 先跑，就会拿到一个还没启动的容器（{@code getMappedPort} 直接抛异常），
     * 而且报出来的错和真正的原因（注解顺序）看起来毫无关系。
     * <p><b>在 static 块里启动，时序就是确定的</b>：类初始化一定早于所有扩展回调。
     * （本项目的 {@code RoutingTest} 里 WireMock 用 static 字段也是同一个原因。）
     */
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void startRedisAndConnect() {
        try {
            REDIS.start();
        } catch (Throwable t) {
            // ★ 不吞掉异常，而是补上一句"这个测试需要什么"，然后原样抛出。
            //
            //   ⚠️ 注意这里【刻意不猜原因】。第一版我写的是"请确认 Docker Desktop 正在运行"，
            //      结果真凶是 Testcontainers 被 pin 了一个旧的 socket 策略 ——
            //      docker CLI 一切正常、容器跑得好好的，和那句话指向的方向完全相反。
            //   ★ 报错信息里可以写"需要什么"，但不该写"你哪里错了" —— 除非你真的知道。 ★
            throw new IllegalStateException(
                    "G4 的并发测试需要一个真实 Redis（Testcontainers 起 redis:7 容器）。"
                            + "本机 Docker 需可用；原始错误见下方 caused by。", t);
        }

        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        REDIS.stop();
    }

    // ========================================================================
    // 第一条：真实实现（Lua）不超卖
    // ========================================================================

    @Test
    @DisplayName("★ 32 并发打容量 5 的桶 → 恰好放行 5 个，不多不少")
    void neverOversellsUnderConcurrency() {
        long allowed = countAllowed(limiterWith(CAPACITY, REFILL_DISABLED), "lua");

        // ★ 把观测值打出来，而不是只留在断言里。
        //   断言只在【红】的时候说话；而这个数字本身是结论的一部分 ——
        //   文档和 commit message 里要引用它，读日志的人也该看得到。
        //   **"我实测过"和"我认为应该"，在技术写作里是两种分量的句子。**
        System.out.printf("[G4-OBS] LUA impl   : %d concurrent -> bucket capacity %d -> allowed %d%n",
                CONCURRENCY, CAPACITY, allowed);

        assertThat(allowed)
                .as("桶里只有 %d 个令牌，并发下必须【严格】只放行 %d 个。"
                        + "放行多了 = 超卖（钱漏了）；放行少了 = 误杀（可用性掉了）。"
                        + "两个方向都是 bug，所以这里用 isEqualTo 而不是 isLessThanOrEqualTo",
                        CAPACITY, CAPACITY)
                .isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("对照组：同样的测试代码 + 不用 Lua 的实现 → 确实会超卖")
    void naiveImplementationDoesOversell() {
        long allowed = countAllowedNaive(CAPACITY, REFILL_DISABLED, Duration.ofMillis(20), "naive");

        System.out.printf("[G4-OBS] NAIVE impl : %d concurrent -> bucket capacity %d -> allowed %d (oversold by %d)%n",
                CONCURRENCY, CAPACITY, allowed, allowed - CAPACITY);

        // ★ 这条断言是"Lua 必需"这个结论的【唯一证据来源】。
        //   它和上一条跑的是同一段测试代码、同一个 Redis、同样的并发数 ——
        //   唯一的差别是被测实现有没有把那四步装进一次原子执行里。
        assertThat(allowed)
                .as("不用 Lua 的话，「读→判断→写」之间是敞开的，并发请求会读到同一个水位然后双双放行。"
                        + "这条断言【必须】是红的（对天真实现而言是绿的）—— "
                        + "如果它不红，说明第一组测试根本没测出并发，第一组的绿也就不值钱了")
                .isGreaterThan(CAPACITY);
    }

    // ========================================================================
    // 第三条：桶不是"只会拒绝的计数器"，它真的会回填
    //
    // 少了这一条，"限流器"退化成"每个客户端一辈子只能发 capacity 次请求"也能全绿。
    // 每个功能都要有一条测试证明它【不只是】在拒绝。
    // ========================================================================

    @Test
    @DisplayName("令牌会回填：容量 2 + 每秒 10 个 → 打光后等 300ms 又能过 1 个")
    void tokensAreRefilledOverTime() throws InterruptedException {
        // 每 100ms 回填 1 个令牌。容量 2 是为了让"打光"只需要 2 个请求。
        RedisTokenBucketLimiter limiter = limiterWith(2, 10);
        String clientId = uniqueClientId("refill");

        assertThat(limiter.tryAcquire(clientId).block().allowed()).isTrue();
        assertThat(limiter.tryAcquire(clientId).block().allowed()).isTrue();

        RateLimitDecision drained = limiter.tryAcquire(clientId).block();
        assertThat(drained).isNotNull();
        assertThat(drained.allowed())
                .as("桶容量 2，已经用掉 2 个，此刻必须被拒 —— 这是回填测试的前提")
                .isFalse();
        assertThat(drained.retryAfterMillis())
                .as("回填速度 10/s，缺 1 个令牌 → 大约 100ms。"
                        + "这个值会变成 Retry-After 头，所以它必须是个真实的估算而不是拍脑袋的常数")
                .isBetween(1L, 200L);

        Thread.sleep(300);

        assertThat(limiter.tryAcquire(clientId).block().allowed())
                .as("300ms 内按 10/s 回填了 3 个令牌（上限 2），所以必然能过。"
                        + "如果这里还是 false，说明桶根本没回填 —— 那它就不是令牌桶，是个计数器")
                .isTrue();
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /** 不回填：桶里就那 CAPACITY 个令牌，测完为止。这样"放行数"才是一个确定的数 */
    private static final double REFILL_DISABLED = 0.0;

    private RedisTokenBucketLimiter limiterWith(int capacity, double refillPerSecond) {
        return new RedisTokenBucketLimiter(redis, properties(capacity, refillPerSecond), new ObjectMapper());
    }

    private RateLimitProperties properties(int capacity, double refillPerSecond) {
        return new RateLimitProperties(
                true,               // enabled
                capacity,
                refillPerSecond,
                true,               // failOpen（本测试不涉及故障路径）
                200L,               // timeoutMillis
                "gw:rl:test");      // keyPrefix：加 test 段，和真实环境隔离
    }

    /**
     * 同一个桶上打 {@link #CONCURRENCY} 个并发请求，数一数放行了几个。
     *
     * <p><b>★ {@code flatMap(fn, CONCURRENCY)} 的第二个参数是关键 ★</b>
     * 它是"最多同时订阅多少个"。不写它的话默认只有 256 —— 对 32 来说够了，
     * 但把一个刚好等于并发数的值写出来，是为了让读代码的人一眼看出
     * <b>"这 32 个请求是同时发出去的"</b>，而不是"可能同时"。
     * <p>用 flatMap 而不是 concatMap：后者会<b>串行</b>执行，
     * 串行之下永远不会超卖 —— <b>那这个测试就变成了一个恒绿的摆设，
     * 而且它看起来还特别像在对的东西上做断言。</b>
     */
    private long countAllowed(RedisTokenBucketLimiter limiter, String label) {
        String clientId = uniqueClientId(label);

        List<RateLimitDecision> decisions = Flux.range(0, CONCURRENCY)
                .flatMap(i -> limiter.tryAcquire(clientId), CONCURRENCY)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(decisions).as("所有并发请求都应该拿到结果（而不是挂住或报错）").isNotNull();
        assertThat(decisions).hasSize(CONCURRENCY);

        return decisions.stream().filter(RateLimitDecision::allowed).count();
    }

    private long countAllowedNaive(int capacity, double refillPerSecond, Duration window, String label) {
        NaiveTokenBucket naive = new NaiveTokenBucket(redis, capacity, refillPerSecond, window);
        String clientId = uniqueClientId(label);

        List<RateLimitDecision> decisions = Flux.range(0, CONCURRENCY)
                .flatMap(i -> naive.tryAcquire(clientId), CONCURRENCY)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(decisions).isNotNull();
        return decisions.stream().filter(RateLimitDecision::allowed).count();
    }

    /** 每个测试用例用独立的桶：共用一个 key 会让测试之间互相消耗额度，红绿随机 */
    private static String uniqueClientId(String label) {
        return label + "-" + UUID.randomUUID();
    }
}
