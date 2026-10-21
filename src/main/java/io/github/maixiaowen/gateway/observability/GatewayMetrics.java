package io.github.maixiaowen.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * <b>G9：把"网关跑得怎么样"变成可以被 Prometheus 抓取的数字。</b>
 *
 * <p><b>★ 为什么先有四组指标，而不是"把能想到的全加上" ★</b>
 * <p>指标是<b>有成本的</b>：每个 tag 组合在 Prometheus 里都是一条独立的时间序列。
 * 如果给每个请求都打上 {@code requestId} 或原始 prompt 的哈希，序列数会爆炸 ——
 * 这是真实的运维事故（被称为"指标基数爆炸"），能让监控系统先于业务垮掉。
 * <p>这里只留四组，每一组都对应一个<b>值班时真的会看的问题</b>：
 * <table>
 *   <tr><th>指标</th><th>回答的问题</th></tr>
 *   <tr><td>{@code gateway.request.duration}</td><td>用户等多久？P95 是多少？</td></tr>
 *   <tr><td>{@code gateway.upstream.ttfb}</td><td>首字什么时候到？用户觉得"卡"吗？</td></tr>
 *   <tr><td>{@code gateway.upstream.calls}</td><td>哪条渠道在失败？失败多少？</td></tr>
 *   <tr><td>{@code gateway.failover}</td><td>故障转移频繁吗？是不是某条渠道一直在坏？</td></tr>
 *   <tr><td>{@code gateway.billing.rejected}</td><td>有多少人因为余额/计费故障被挡在门外？</td></tr>
 * </table>
 *
 * <p><b>★ 为什么 TTFB 要单独量，而不是看平均延迟 ★</b>
 * <p>流式场景下，"总耗时"和"用户感受"是两回事：一个吐了 60 秒但首字 200ms 到的回复，
 * 用户觉得很快；一个首字 8 秒才到、之后哗啦一下吐完的回复，用户已经关页面了。
 * <b>平均延迟会把这两种情况混成一个数字，而它们需要完全不同的处置。</b>
 *
 * <p><b>⚠️ 标签值的基数</b>：{@code channel} 来自配置（数量可控），
 * {@code kind} 是有限的错误分类枚举。都是低基数的 —— 这不是巧合，是选择。
 */
@Component
public class GatewayMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ========================================================================
    // 请求级
    // ========================================================================

    /** 一次请求成功。{@code stream} 是布尔标签，看板可以分开看流式/非流式的延迟分布 */
    public void requestSuccess(boolean stream, long elapsedNanos) {
        registry.timer("gateway.request.duration",
                        "stream", String.valueOf(stream), "outcome", "success")
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        requestCounter(stream, "success").increment();
    }

    /** 一次请求失败。{@code kind} 用错误分类的机器码，不是异常类名 —— 类名会随重构变化 */
    public void requestFailure(boolean stream, String kind, long elapsedNanos) {
        registry.timer("gateway.request.duration",
                        "stream", String.valueOf(stream), "outcome", "error")
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        registry.counter("gateway.request.errors",
                        "stream", String.valueOf(stream), "kind", normalize(kind)).increment();
        requestCounter(stream, "error").increment();
    }

    /** 客户端主动断开。★ 它既不是成功也不是失败，单独一个 outcome ★ */
    public void requestCancelled(boolean stream, long elapsedNanos) {
        registry.timer("gateway.request.duration",
                        "stream", String.valueOf(stream), "outcome", "cancelled")
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        requestCounter(stream, "cancelled").increment();
    }

    // ========================================================================
    // 上游级
    // ========================================================================

    /**
     * 一次上游调用（注意：是"每次尝试"，不是"每个用户请求"）。
     * <p>重试和转移会让这两个数字分叉；<b>分叉本身就是信息</b>：
     * 用户请求数 << 上游调用数，说明故障转移在频繁工作。
     */
    public void upstreamSuccess(String channel, boolean stream, long elapsedNanos) {
        registry.timer("gateway.upstream.duration",
                        "channel", channel, "stream", String.valueOf(stream), "outcome", "success")
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        upstreamCounter(channel, stream, "success").increment();
    }

    public void upstreamFailure(String channel, boolean stream, String kind, long elapsedNanos) {
        registry.timer("gateway.upstream.duration",
                        "channel", channel, "stream", String.valueOf(stream), "outcome", "error")
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        upstreamCounter(channel, stream, "error").increment();
        registry.counter("gateway.upstream.errors",
                        "channel", channel, "kind", normalize(kind)).increment();
    }

    /** 首字节耗时。只在流式响应里量；非流式没有"首字节"这个概念 */
    public void firstByte(String channel, long elapsedNanos) {
        registry.timer("gateway.upstream.ttfb", "channel", channel)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /** 一次故障转移。from/to 都是配置里的渠道名，基数可控 */
    public void failover(String from, String to) {
        registry.counter("gateway.failover", "from", from, "to", to).increment();
    }

    // ========================================================================
    // 计费
    // ========================================================================

    /** 计费把请求挡下了。reason：insufficient_quota / billing_unavailable */
    public void billingRejected(String reason) {
        registry.counter("gateway.billing.rejected", "reason", normalize(reason)).increment();
    }

    /**
     * 记录一个耗时样本的便捷方法（Timer.Sample 的替代）。
     * <p>用 {@link System#nanoTime()} 而不是 {@code System.currentTimeMillis()}：
     * 后者会被系统对时（NTP）往回拨，算出负数时长 —— 那会在看板上变成一个诡异的尖峰。
     */
    public static Duration elapsedSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    public static long nanosSince(long startedAtNanos) {
        return System.nanoTime() - startedAtNanos;
    }

    private Counter requestCounter(boolean stream, String outcome) {
        return registry.counter("gateway.requests",
                "stream", String.valueOf(stream), "outcome", outcome);
    }

    private Counter upstreamCounter(String channel, boolean stream, String outcome) {
        return registry.counter("gateway.upstream.calls",
                "channel", channel, "stream", String.valueOf(stream), "outcome", outcome);
    }

    /** 标签值必须有限：任何可能为 null 或空的值都不能直接进 label */
    private String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
