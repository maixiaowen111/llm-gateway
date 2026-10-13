package com.example.gateway.limiter;

/**
 * <b>一次限流判定的结果（G4）。</b>
 *
 * <p><b>★ 为什么是个三态枚举，而不是一个 {@code boolean allowed} ★</b>
 * <p>这是 G1-1d 和 G3a 那条"错误分类"纪律的延续：<b>让排查方向不跑偏</b>。
 * 被限流有<b>两种完全不同的原因</b>，它们该返回不同的 HTTP 状态码：
 *
 * <table border="1">
 *   <caption>三种结果</caption>
 *   <tr><th>结果</th><th>含义</th><th>是谁的问题</th><th>HTTP</th></tr>
 *   <tr><td>{@link Outcome#ALLOWED}</td><td>桶里够，放行</td><td>—</td><td>正常继续</td></tr>
 *   <tr><td>{@link Outcome#DENIED}</td><td>桶里不够</td><td><b>调用方</b>（请求发太快）</td><td><b>429</b></td></tr>
 *   <tr><td>{@link Outcome#UNAVAILABLE}</td><td>限流器自己坏了</td><td><b>我们</b>（Redis 挂了）</td><td><b>503</b></td></tr>
 * </table>
 *
 * <p>如果只有 {@code boolean}，这两种情况会共用 429。后果很具体：
 * <b>客户端看到 429 会"退避后重试"—— 而我们的 Redis 挂了，重试一万次也没用</b>；
 * 同时值班的人看到 429 会去查"谁在刷接口"，而真凶是 Redis。
 * <b>错误码选错，不只是不好看，是把所有人指错了方向。</b>
 *
 * <p><b>为什么不直接拿 Redis 返回的 JSON 反序列化成这个类</b>：
 * 脚本的返回结构是<b>脚本的事</b>，这个类是<b>我们 API 的事</b>。
 * 让它们共用一个类型，等于让 Redis 里的字段名决定了我们对外契约的形状 ——
 * 哪天脚本想改个字段名，就变成一次对外接口变更。
 * （这条边界的具体实现见 {@code RedisTokenBucketLimiter} 里的私有 record。）
 *
 * @param outcome          三态结果，见上表
 * @param remaining        本次之后桶里还剩几个令牌。<b>未知时为 {@link #UNKNOWN_REMAINING}</b>
 * @param retryAfterMillis 建议多久后重试（毫秒）。<b>桶不回填时为 {@link #NEVER_REFILLS}</b>
 */
public record RateLimitDecision(Outcome outcome, long remaining, long retryAfterMillis) {

    /** 结果三态 */
    public enum Outcome {
        /** 桶里够，放行 */
        ALLOWED,
        /** 桶里不够，拒绝。这是<b>调用方</b>的问题 → 429 Too Many Requests */
        DENIED,
        /** 限流器不可用，且配置要求 fail-closed。这是<b>我们</b>的问题 → 503 Service Unavailable */
        UNAVAILABLE
    }

    /**
     * {@link #remaining} 的哨兵值：水位未知。
     * <p>用于"Redis 挂了、我们根本没读到水位"的情况。
     * <b>用 -1 而不是 0</b>：报 0 是在撒谎（"已经用光了"），
     * 而调用方可能据此放弃重试 —— 用未知告诉它"我们不知道"，它才会按 Retry-After 行动。
     * 出 HTTP 头时会跳过这个值（负数不做成 X-RateLimit-Remaining）。
     */
    public static final long UNKNOWN_REMAINING = -1L;

    /** {@link #retryAfterMillis} 的哨兵值：这个桶永不回填（{@code refillPerSecond = 0}） */
    public static final long NEVER_REFILLS = -1L;

    /**
     * 桶不回填时给客户端的建议等待秒数。
     * <p>为什么给一个具体数字、而不是省略 Retry-After 头：
     * 标准客户端（OpenAI SDK、各类退避重试库）<b>看到没有 Retry-After 会用默认退避</b>，
     * 而默认退避通常比 60 秒激进得多 —— 于是它会在"等多久都没用"的情况下反复重试。
     * <b>给一个明确的、偏保守的数字，比不给强。</b>
     */
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 60L;

    public static RateLimitDecision allowed(long remaining) {
        return new RateLimitDecision(Outcome.ALLOWED, remaining, 0L);
    }

    public static RateLimitDecision denied(long remaining, long retryAfterMillis) {
        return new RateLimitDecision(Outcome.DENIED, remaining, retryAfterMillis);
    }

    /** 限流器坏了（Redis 不可用）+ 配置要求 fail-closed 时的结果。水位未知、无建议重试时间 */
    public static RateLimitDecision unavailable() {
        return new RateLimitDecision(Outcome.UNAVAILABLE, UNKNOWN_REMAINING, NEVER_REFILLS);
    }

    /** Redis 不可用 + 配置是 fail-open 时的结果：放行，但明确标记"水位未知" */
    public static RateLimitDecision allowedWithoutQuota() {
        return new RateLimitDecision(Outcome.ALLOWED, UNKNOWN_REMAINING, 0L);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOWED;
    }

    /**
     * 转成 HTTP {@code Retry-After} 头的值（秒）。
     *
     * <p><b>★ 向上取整，不是向下 ★</b>
     * <p>1399ms 向下取整是 1 秒 —— 客户端 1 秒后重试，<b>还是不够，又被拒一次</b>。
     * Retry-After 的意义是"照着这个数等就一定能过"，
     * 向下取整会让它变成"照着等也过不去"。<b>宁可让客户端多等 1 秒。</b>
     */
    public long retryAfterSeconds() {
        if (retryAfterMillis == NEVER_REFILLS || retryAfterMillis <= 0) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        return (retryAfterMillis + 999L) / 1000L;
    }
}
