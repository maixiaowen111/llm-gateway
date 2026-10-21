package com.example.gateway.resilience;

import org.springframework.http.HttpStatus;

/**
 * <b>G5 的核心：把"上游出错了"拆成几种完全不同的事。</b>
 *
 * <p><b>为什么不能只有一个 {@code UpstreamException}</b>：
 * 上游失败对网关来说至少有四种截然不同的处理方式 ——
 * <table border="1">
 *   <caption>四类失败，四个方向</caption>
 *   <tr><th>失败</th><th>该怎么做</th><th>对客户端的含义</th></tr>
 *   <tr><td>超时 / 连不上 / 5xx</td><td><b>换渠道</b>（熔断器记一笔失败）</td>
 *       <td>不是你的错，我们换一条路</td></tr>
 *   <tr><td>上游 429</td><td><b>同渠道退避重试</b>，仍不行再换</td>
 *       <td>对方限流了，等一下还有机会</td></tr>
 *   <tr><td>上游 400 / 422</td><td><b>直接透传</b>，不重试不换渠道</td>
 *       <td><b>你的请求本身有问题</b> —— 换一百个渠道也一样</td></tr>
 *   <tr><td>上游 401 / 403</td><td><b>不换渠道</b>，喊人</td>
 *       <td>我们的密钥配错了，不是你的问题</td></tr>
 * </table>
 *
 * <p><b>★ 最容易写错的是第三行 ★</b>
 * <p>很多网关把上游所有非 2xx 都当成"上游故障"，于是 400 也去换渠道重试。
 * 后果是：<b>用户发了一个格式错误的请求，网关替他重试了 3 家厂商、花了 3 份钱、
 * 最后仍然返回 502</b> —— 用户不知道是自己错了，账单上却多了三笔。
 * <p><b>400 不是"上游坏了"，是"这个请求不可能成功"。</b>重试只会放大成本，不会改变结果。
 *
 * <p><b>★ 第四行为什么也不换渠道 ★</b>
 * <p>401 意味着这个渠道的密钥失效了。换到备份渠道确实能让用户这次成功，
 * 但<b>真正的故障被掩盖了</b> —— 可能三天后才有人发现主渠道的 key 早就过期。
 * <b>能自动恢复的故障自动恢复，不能自动恢复的故障必须喊人。</b>
 * 密钥失效属于后者：恢复动作是"人去换 key"，不是"路由绕开它"。
 */
public enum UpstreamErrorKind {

    /** 请求本身有问题（400/404/422）。<b>透传，不重试、不换渠道</b> */
    CLIENT_ERROR(false, false, HttpStatus.BAD_REQUEST, "invalid_request_error"),

    /** 渠道配置问题（401/403）。<b>不换渠道</b>，让网关 502 + 明确 code，值班的人去换 key */
    AUTH_ERROR(false, false, HttpStatus.BAD_GATEWAY, "upstream_auth_error"),

    /** 上游限流（429）。同渠道退避重试；仍然不行再换渠道 */
    RATE_LIMITED(true, true, HttpStatus.TOO_MANY_REQUESTS, "upstream_rate_limited"),

    /** 上游 5xx / 连不上 / 连接被拒。<b>换渠道</b> */
    UPSTREAM_UNAVAILABLE(true, true, HttpStatus.BAD_GATEWAY, "upstream_unavailable"),

    /** 读超时 / 逐块空闲超时 / 连接超时。<b>换渠道</b> */
    TIMEOUT(true, true, HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout"),

    /** 熔断器已经打开，这次请求压根没发出去。<b>换渠道</b>（但没有"重试本渠道"这回事） */
    CIRCUIT_OPEN(false, true, HttpStatus.SERVICE_UNAVAILABLE, "circuit_open"),

    /** 网关自己的 bug。不要重试 —— 重试自己的 bug 只会更慢地失败 */
    INTERNAL(false, false, HttpStatus.INTERNAL_SERVER_ERROR, "gateway_internal_error");

    private final boolean retryable;
    private final boolean failoverable;
    private final HttpStatus defaultStatus;
    private final String code;

    UpstreamErrorKind(boolean retryable,
                      boolean failoverable,
                      HttpStatus defaultStatus,
                      String code) {
        this.retryable = retryable;
        this.failoverable = failoverable;
        this.defaultStatus = defaultStatus;
        this.code = code;
    }

    /** 同一个渠道上值得再试一次吗？只有"对方暂时不行"这一类值得 */
    public boolean retryable() {
        return retryable;
    }

    /** 值得换一个渠道再试吗？<b>注意它和 {@link #retryable()} 不是一回事</b> */
    public boolean failoverable() {
        return failoverable;
    }

    /**
     * 这次失败要不要记进熔断器的失败计数。
     *
     * <p><b>注意 400 和 401 不在里面</b>：把"用户发错请求"记成"渠道不健康"，
     * 几次拼写错误就能把一条健康渠道熔断掉 —— 那叫<b>熔断器误伤</b>，
     * 而误伤比不熔断更危险：它会把一个本来只影响一个人的错误，
     * 升级成影响所有走这条渠道的人。
     */
    public boolean countsAsCircuitFailure() {
        return this == RATE_LIMITED || this == UPSTREAM_UNAVAILABLE || this == TIMEOUT;
    }

    public HttpStatus defaultStatus() {
        return defaultStatus;
    }

    /** 对外错误体里的机器可读 code。G1 埋的伏笔在这里被真正消费 */
    public String code() {
        return code;
    }
}
