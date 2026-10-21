package io.github.maixiaowen.gateway.exception;

import java.util.Set;

/**
 * 请求了一个不存在的渠道。G3-3a 的产物。
 *
 * <p><b>为什么要有这个类型，而不是直接返回 null 或抛 {@code IllegalArgumentException}</b>
 * <ul>
 *   <li><b>返回 null</b>：调用方会在很远的地方 NPE，而那里已经看不出问题源头了。
 *       <b>错误应该在最靠近出错点的地方炸掉</b>，而不是漂移到别处。</li>
 *   <li><b>抛 {@code IllegalArgumentException}</b>：它太通用了 ——
 *       日志里看到它，你分不清是参数校验失败、还是渠道名写错了。</li>
 * </ul>
 * 定义一个语义明确的类型，日志一眼能定位，测试断言它也不会"碰巧通过"。
 * （和 G2-2d 的 {@link UpstreamIdleTimeoutException} 是同一个思路。）
 *
 * <p><b>它该返回什么 HTTP 状态码（G3-3b 处理）</b>：
 * 这是<b>客户端把渠道名写错了</b>，属于请求方的问题 —— 应该是 <b>400</b>，
 * 不是 502/504。<b>"上游有问题"和"你请求得不对"必须分得清</b>，
 * 否则运维会一直去查上游，而问题是调用方拼错了渠道名。
 * （对照 G1 的判据：502 = 上游回了坏东西，504 = 没等到上游 ——
 * 这里两者都不是，压根没轮到上游。）
 */
public class UnknownProviderException extends RuntimeException {

    /** 请求的渠道名，用于日志和错误响应 */
    private final String requestedName;
    /** 当时实际可用的渠道，直接写进错误信息 —— 排错时不用再去翻代码 */
    private final Set<String> availableNames;

    public UnknownProviderException(String requestedName, Set<String> availableNames) {
        super(String.format("渠道 [%s] 不存在。当前可用渠道：%s", requestedName, availableNames));
        this.requestedName = requestedName;
        this.availableNames = Set.copyOf(availableNames);
    }

    public String requestedName() {
        return requestedName;
    }

    public Set<String> availableNames() {
        return availableNames;
    }
}
