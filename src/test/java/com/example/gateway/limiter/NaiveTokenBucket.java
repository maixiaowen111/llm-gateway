package com.example.gateway.limiter;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * <b>G4 的对照组：一个"不用 Lua"的令牌桶实现。它<b>故意</b>会超卖。</b>
 *
 * <p><b>★★ 它为什么只存在于 {@code src/test}，而不在 {@code src/main} ★★</b>
 * <p>因为它<b>不是备选方案，是一份证据</b>。
 * 少了它，{@code RateLimitConcurrencyTest} 里的"32 并发 → 恰好放行 5 个"
 * 只能证明<b>"我的实现没超卖"</b>；有了它，同一套测试代码跑出"32 并发 → 放行 32 个"，
 * 才能证明<b>"超卖这件事真的会发生，而 Lua 是拦住它的那一道墙"</b>。
 * <p><b>一个不会失败的对照组，等于没有对照组。</b>
 * 把它放进 {@code src/main} 就会变成一个"看起来能用"的坑，
 * 迟早有人在线上把它 new 出来 —— <b>反面教材不应该可以被 import。</b>
 *
 * <p><b>★ 这段代码不是稻草人 ★</b>
 * <p>它是一份<b>写得很像样</b>的实现：读了当前令牌，按流逝时间回填，判断够不够，扣掉，写回。
 * 逻辑上一行都没错。真实项目里绝大多数的限流"实现"就长这样 ——
 * 而且它们<b>在单机压测时往往是绿的</b>（请求串行到达，读改写之间没有别的请求插进来）。
 * 它只在并发下错，而且错得<b>无声无息</b>：不报错、不告警，只是偶尔多放行几个。
 *
 * <p><b>★ 为什么天真实现"看起来没问题"、一到生产就出问题 ★</b>
 * <p>关键在于「读」和「写」之间那段时间。在单机低并发下它只有几百微秒，
 * 两次读撞在同一个窗口里的概率极低 —— 所以测试是绿的。
 * 但在生产量级（每秒几千请求）下，这个窗口每天会被撞上成千上万次。
 * <p><b>这才是并发 bug 最可怕的地方：概率低到你的测试永远抓不到，
 * 但量级大到账单一定会体现。</b>
 */
public class NaiveTokenBucket {

    /** 刻意用不同的前缀，避免和真实实现的 key 混在一起（混了会让两个测试互相污染） */
    private static final String KEY_PREFIX = "gw:rl:naive";

    private final ReactiveStringRedisTemplate redis;
    private final int capacity;
    private final double refillPerSecond;

    /**
     * 「读」和「写」之间的耗时。
     *
     * <p><b>它模拟的是什么</b>：真实世界里这一步之间至少夹着<b>一次 Redis 往返的网络延迟</b>
     * （读回来 → 应用算 → 写回去），加上业务处理。生产环境里这个窗口通常在 1~3ms。
     * <p><b>为什么测试里要把它放大到 20ms</b>：不是为了"造假"，而是为了让一个
     * <b>本来靠运气才会发生</b>的竞态变成<b>必然发生</b>的。测试如果要用"跑一百次碰上一次"
     * 来证明一个 bug 存在，那它在 CI 上就是一条随机失败的测试 —— 比没有更糟。
     * <p><b>放大窗口只是让证据变可靠，没有改变结论：那段时间真实存在，只是长短不同。</b>
     */
    private final Duration readWriteWindow;

    public NaiveTokenBucket(ReactiveStringRedisTemplate redis,
                            int capacity,
                            double refillPerSecond,
                            Duration readWriteWindow) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.readWriteWindow = readWriteWindow;
    }

    public Mono<RateLimitDecision> tryAcquire(String clientId) {
        String key = KEY_PREFIX + ":" + clientId;
        long now = System.currentTimeMillis();

        // ① 读 —— 一次独立的 Redis 调用
        return redis.<String, String>opsForHash()
                .entries(key)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMap(state -> {
                    double tokens = state.containsKey("tokens")
                            ? Double.parseDouble(state.get("tokens"))
                            : capacity;
                    long ts = state.containsKey("ts")
                            ? Long.parseLong(state.get("ts"))
                            : now;

                    // ② 补
                    long elapsed = Math.max(0L, now - ts);
                    double refilled = Math.min(capacity, tokens + elapsed * refillPerSecond / 1000.0);

                    // ③ 判断 + 扣减
                    boolean allowed = refilled >= 1.0;
                    double after = allowed ? refilled - 1.0 : refilled;
                    long retryAfterMs = allowed || refillPerSecond <= 0
                            ? -1L
                            : (long) Math.ceil((1.0 - refilled) / (refillPerSecond / 1000.0));

                    // ★★ 就是这里 ★★
                    //    「判断完了」和「写回去」之间，**别的请求可以随便插进来读**。
                    //    20 个请求全部在这里读到同一个"还剩 5 个"，然后全部判定通过，
                    //    最后全部写回 4 —— 桶里只有 5 个令牌，却放行了 20 个。
                    //
                    //    ★ 注意：这个 bug 和"Redis 是不是单线程"完全无关。
                    //      Redis 的单线程只保证【每一条命令】原子；
                    //      「读→判断→写」是三条命令，它们之间永远是敞开的。
                    //      加锁也没用 —— 应用层锁不住别的进程/别的实例。
                    //      唯一的解法是把这四步搬到 Redis 内部去，也就是 Lua。
                    return Mono.delay(readWriteWindow)
                            // ④ 写回 —— 另一次独立的 Redis 调用
                            .flatMap(ignored -> redis.<String, String>opsForHash().putAll(key, Map.of(
                                    "tokens", String.valueOf(after),
                                    "ts", String.valueOf(now))))
                            .thenReturn(allowed
                                    ? RateLimitDecision.allowed((long) after)
                                    : RateLimitDecision.denied((long) after, retryAfterMs));
                });
    }
}
