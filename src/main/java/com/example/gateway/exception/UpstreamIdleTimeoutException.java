package com.example.gateway.exception;

/**
 * G2-2d 的产物：<b>流式响应"中途停滞"</b>被网关判定为超时。
 *
 * <p><b>为什么不用现成的 {@code ReadTimeoutException} / {@code TimeoutException}</b>：
 * 那两种异常在链路上会从很多地方冒出来（连接超时、读超时、客户端断开的次生异常……），
 * 排错时根本分不清是哪一种。定义一个<b>只可能由"逐块空闲超时"这一处产生</b>的类型，
 * 日志和告警才能一眼定位；测试断言它也不会"碰巧通过"。
 *
 * <p><b>它和 {@code responseTimeout} 的分工（这是 2d 要补的坑）</b>
 * <ul>
 *   <li>{@code responseTimeout} 量的是「请求发出 → 收到响应头」。非流式下响应头就是全部内容，够用。</li>
 *   <li>SSE 流式的响应头<b>几十毫秒就回来了</b>，然后内容可能吐几分钟。
 *       这时 {@code responseTimeout} 早就"成功"了，<b>中途卡死它一点都管不到</b> ——
 *       客户端会挂在那里永远等下一个字，而网关上留一条永不结束的连接。</li>
 * </ul>
 * 本异常对应的超时量的是<b>两个块之间最长允许隔多久</b>，这才是流式该有的超时语义。
 *
 * <p><b>为什么它不像其他上游异常那样返回 504</b>：能抛出它，说明响应头<b>已经发出去了</b>，
 * HTTP 状态码改不了了（见 {@code ChatController} 里流式分支先钉 Content-Type 那一步）。
 * 所以它不做状态码映射，只负责让流<b>干净地终止并留下明确日志</b>，
 * 而不是让连接悬在那里。G5 做错误分类时，会把它归到"该换渠道重试"的一类。
 */
public class UpstreamIdleTimeoutException extends RuntimeException {

    /** 触发本次超时的阈值（毫秒），用于日志和排查 */
    private final long idleTimeoutMs;

    public UpstreamIdleTimeoutException(long idleTimeoutMs, Throwable cause) {
        super("上游流式响应空闲超过 " + idleTimeoutMs + " ms，判定为中途停滞", cause);
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public long idleTimeoutMs() {
        return idleTimeoutMs;
    }
}
