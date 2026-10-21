package com.example.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>G5：每个渠道一个熔断器。</b>
 *
 * <p><b>★ 为什么必须按渠道隔离，而不是全网关共用一个 ★</b>
 * <p>假设 DeepSeek 挂了、Moonshot 是好的。共用一个熔断器会发生什么：
 * DeepSeek 的失败把全局熔断器打开，<b>连本来健康的 Moonshot 也一起被拒绝</b> ——
 * 一个上游的故障被放大成<b>全部上游的故障</b>。
 * <p>熔断器的隔离粒度决定了故障的影响半径。<b>按渠道隔离，故障半径就是那一条渠道。</b>
 * （这和线程池隔离/舱壁是同一条思路：**别让一种资源的问题吃掉全部资源**。）
 *
 * <p><b>★ 为什么只有"上游真的病了"才算失败 ★</b>
 * <p>见 {@link UpstreamErrorKind#countsAsCircuitFailure()}：400/401 不计数。
 * 否则一个客户端把请求体写错三次，就能把整条健康渠道熔断掉 ——
 * <b>熔断器误伤的受害者是所有用户，而肇事者只是一个写错代码的调用方。</b>
 *
 * <p><b>★ 熔断和降级是两件事，本项目只做了熔断 ★</b>
 * <p>熔断 = "别再打了，让它歇会儿"；降级 = "打不了它，用别的东西顶上"
 * （比如返回缓存、切到便宜的模型）。本项目的"顶上"是<b>故障转移到同组另一条渠道</b>
 * （见 {@code GatewayChatService}）。如果连备份都没有，请求会得到一个明确的 503，
 * <b>而不是一个看起来像业务错误的东西</b>。
 */
public class ChannelCircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(ChannelCircuitBreakers.class);

    private final CircuitBreakerRegistry registry;
    private final boolean enabled;

    public ChannelCircuitBreakers(ResilienceProperties properties,
                                  Collection<String> channelNames,
                                  MeterRegistry meterRegistry) {
        this.enabled = properties.enabled();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.failureRateThreshold())
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .waitDurationInOpenState(Duration.ofMillis(properties.waitDurationInOpenStateMillis()))
                .permittedNumberOfCallsInHalfOpenState(properties.permittedNumberOfCallsInHalfOpenState())
                // 打开后到点自动进半开。不开这个的话，只有"下一个请求进来"才会触发状态流转 ——
                // 对低流量渠道，可能几分钟都没人发现它已经该半开了。
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // ★ 关键：只有上游故障才计入失败率。其他异常一律视为"不算失败"。
                .recordException(error -> error instanceof UpstreamFailure failure
                        && failure.kind().countsAsCircuitFailure())
                .build();

        this.registry = CircuitBreakerRegistry.of(config);

        for (String channel : channelNames) {
            CircuitBreaker breaker = registry.circuitBreaker(channel);
            registerMetrics(channel, breaker, meterRegistry, properties);
            breaker.getEventPublisher().onStateTransition(event -> log.warn(
                    "[熔断状态变化] channel={} {} -> {}（失败率阈值 {}%，窗口 {} 次）",
                    channel,
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState(),
                    properties.failureRateThreshold(),
                    properties.slidingWindowSize()));
        }

        log.info("[熔断器已装配] enabled={}，渠道={}，阈值={}%，窗口={} 次，最小样本={}，打开等待={}ms",
                enabled, registry.getAllCircuitBreakers().stream().map(CircuitBreaker::getName).toList(),
                properties.failureRateThreshold(), properties.slidingWindowSize(),
                properties.minimumNumberOfCalls(), properties.waitDurationInOpenStateMillis());
    }

    /** 渠道是否"现在可以试一下"：关着的、半开的都可以；只有打开的不行 */
    public boolean allows(String channel) {
        if (!enabled) {
            return true;
        }
        CircuitBreaker.State state = registry.circuitBreaker(channel).getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    public CircuitBreaker get(String channel) {
        return registry.circuitBreaker(channel);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 渠道 → 熔断器状态。给 admin 接口和健康检查用 */
    public Map<String, String> snapshot() {
        Map<String, String> states = new LinkedHashMap<>();
        for (CircuitBreaker breaker : registry.getAllCircuitBreakers()) {
            states.put(breaker.getName(), breaker.getState().name());
        }
        return Map.copyOf(states);
    }

    /** 渠道 → 当前失败率（百分比）。样本不足时返回 -1 —— <b>不知道就说不知道，别报 0</b> */
    public Map<String, Float> failureRates() {
        Map<String, Float> rates = new ConcurrentHashMap<>();
        for (CircuitBreaker breaker : registry.getAllCircuitBreakers()) {
            float rate = breaker.getMetrics().getFailureRate();
            rates.put(breaker.getName(), rate);
        }
        return Map.copyOf(rates);
    }

    private void registerMetrics(String channel,
                                 CircuitBreaker breaker,
                                 MeterRegistry meterRegistry,
                                 ResilienceProperties properties) {
        // 值是枚举不是数字：Prometheus 只认数字，所以把"打开"映射成 1。
        // 看板上的查询就能写成 "gateway_circuit_open{channel=...} == 1"。
        Gauge.builder("gateway.circuit.open",
                        breaker,
                        b -> b.getState() == CircuitBreaker.State.OPEN ? 1.0 : 0.0)
                .description("1 = 熔断器打开（该渠道当前不可用）")
                .tag("channel", channel)
                .register(meterRegistry);

        Gauge.builder("gateway.circuit.failure.rate",
                        breaker,
                        b -> {
                            float rate = b.getMetrics().getFailureRate();
                            return rate < 0 ? 0.0 : rate;
                        })
                .description("当前滑动窗口内的失败率（百分比）；样本不足时为 0")
                .tag("channel", channel)
                .register(meterRegistry);

        Gauge.builder("gateway.circuit.buffered.calls",
                        breaker,
                        b -> b.getMetrics().getNumberOfBufferedCalls())
                .description("滑动窗口里已有的样本数")
                .tag("channel", channel)
                .register(meterRegistry);

        // 把参数记进日志已经做过了；这里只确认配置被读到了（否则 gauge 名字会漂移）
        if (properties.slidingWindowSize() <= 0) {
            log.warn("[熔断配置异常] sliding-window-size={}，gauge 可能不可用", properties.slidingWindowSize());
        }
    }
}
