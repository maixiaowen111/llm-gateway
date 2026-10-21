package com.example.gateway.resilience;

import org.springframework.http.HttpStatus;

/**
 * <b>G5：一次"已经分好类"的上游失败。</b>
 *
 * <p>它比原始的 {@code WebClientResponseException} 多带了四样东西，
 * 每一样都是后面某一层的决策依据：
 * <ol>
 *   <li>{@link #kind()} —— 该重试、该换渠道，还是该透传；</li>
 *   <li>{@link #channel()} —— 熔断器按渠道隔离，不能把 A 的失败记到 B 头上；</li>
 *   <li>{@link #afterFirstByte()} —— <b>★ 流式系统最关键的字段 ★</b>
 *       已经吐过第一个字就不能再换渠道，否则用户会看到重复内容；</li>
 *   <li>{@link #upstreamStatus()} / {@link #retryAfterSeconds()} —— 对外错误码和 Retry-After。</li>
 * </ol>
 *
 * <p><b>为什么它继承 {@code RuntimeException} 而不是受检异常</b>：
 * Reactor 的算子链只接受非受检异常，受检异常会被包成 {@code ReactiveException}，
 * 到 {@code @ExceptionHandler} 时类型信息已经丢了 ——
 * 那等于把刚刚分好的类又还回去。
 */
public class UpstreamFailure extends RuntimeException {

    private final UpstreamErrorKind kind;
    private final String channel;
    private final Integer upstreamStatus;
    private final boolean afterFirstByte;
    private final Long retryAfterSeconds;

    public UpstreamFailure(UpstreamErrorKind kind,
                           String channel,
                           Integer upstreamStatus,
                           boolean afterFirstByte,
                           Long retryAfterSeconds,
                           String message,
                           Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.channel = channel;
        this.upstreamStatus = upstreamStatus;
        this.afterFirstByte = afterFirstByte;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static UpstreamFailure of(UpstreamErrorKind kind, String channel, String message) {
        return new UpstreamFailure(kind, channel, null, false, null, message, null);
    }

    public UpstreamErrorKind kind() {
        return kind;
    }

    public String channel() {
        return channel;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    /** 流式响应是不是已经吐过数据了？是的话<b>禁止换渠道</b> */
    public boolean afterFirstByte() {
        return afterFirstByte;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * 对外该返回什么状态码。
     *
     * <p><b>CLIENT_ERROR 用上游的原始 4xx</b>（400 / 404 / 422）：
     * 客户端的 OpenAI SDK 对不同 4xx 的处理不一样，
     * 把 422 统一改成 400 会让"参数不合法"和"模型不存在"看起来一模一样。
     * <p>其余都用 {@link UpstreamErrorKind#defaultStatus()} ——
     * 401 对外是 502 而不是 401：<b>401 是我们和上游之间的事，
     * 不应该让客户端以为"自己的 key 错了"</b>（他们根本没有上游的 key）。
     */
    public HttpStatus clientStatus() {
        if (kind == UpstreamErrorKind.CLIENT_ERROR && upstreamStatus != null) {
            return HttpStatus.valueOf(upstreamStatus);
        }
        return kind.defaultStatus();
    }

    /**
     * 补上/更新上下文（渠道名、是否已吐字节）。
     *
     * <p><b>★ {@code afterFirstByte} 用"或"，不是用参数覆盖 ★</b>
     * <p>这是一个真实踩过的坑：服务层在流式路径上会<b>再次</b>调分类器补上下文，
     * 如果调用方传的是 {@code false}（它不知道流状态），直接覆盖就会把
     * "已经吐过字"这个事实<b>抹掉</b> —— 于是网关会"忘记"自己已经吐了半句话，
     * 义无反顾地转到备份渠道从头再说一遍。
     *
     * <p><b>"已经吐过字节"是单向事实，发生之后就不可能撤销。</b>
     * 单调的字段，写操作也必须单调。
     */
    public UpstreamFailure withContext(String channel, boolean afterFirstByte) {
        return new UpstreamFailure(kind, channel, upstreamStatus,
                this.afterFirstByte || afterFirstByte,
                retryAfterSeconds, getMessage(), getCause());
    }
}
