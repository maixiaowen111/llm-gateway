package com.example.gateway.observability;

import com.example.gateway.billing.BillingProperties;
import com.example.gateway.limiter.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * <b>G9：Redis 的健康度，只在"它真的是依赖"的时候才算数。</b>
 *
 * <p><b>★ 为什么不能用 Spring 自带的 Redis 健康检查 ★</b>
 * <p>Boot 的 {@code RedisHealthIndicator} 是无条件探活的：只要 Redis 在 classpath 上，
 * 它就去 ping 一下。于是会出现一个很别扭的状态：
 * <b>限流和计费都关着、网关完全不需要 Redis、所有请求都正常，/actuator/health 却是 DOWN。</b>
 * <p>反过来，如果为了让本地开发好看就把 Redis 健康检查关掉，
 * <b>生产上限流开着、Redis 挂掉时，健康检查又会假装一切正常</b> —— 那更危险。
 *
 * <p>正确的判断是<b>由依赖关系决定</b>：
 * <ul>
 *   <li>限流或计费任一启用 → Redis 是硬依赖，探活，失败则 DOWN；</li>
 *   <li>两者都关闭 → Redis 不是依赖，直接 UP，但在 details 里明确写 {@code required=false}。</li>
 * </ul>
 * <b>健康检查不该只有一个结论，它应该先回答"这个组件对我到底重不重要"。</b>
 *
 * <p><b>⚠️ 为什么这里能 block</b>：健康检查由 actuator 在独立线程池里调用，
 * 不在 Netty 事件循环上。真正的纪律是"不要在事件循环线程上阻塞"，
 * 而不是"任何地方都不许 block"。见 RateLimitConfig 里同一段说明。
 */
@Component("redis")
public class ProtectionDependencyHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ProtectionDependencyHealthIndicator.class);

    private static final String PROBE_KEY = "gw:__health_probe__";

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties rateLimit;
    private final BillingProperties billing;

    public ProtectionDependencyHealthIndicator(ReactiveStringRedisTemplate redis,
                                               RateLimitProperties rateLimit,
                                               BillingProperties billing) {
        this.redis = redis;
        this.rateLimit = rateLimit;
        this.billing = billing;
    }

    @Override
    public Health health() {
        boolean required = rateLimit.enabled() || billing.enabled();
        if (!required) {
            return Health.up()
                    .withDetail("required", false)
                    .withDetail("reason", "限流和计费均未启用，Redis 不是本实例的依赖")
                    .build();
        }

        long timeoutMillis = billing.enabled()
                ? billing.timeoutMillis()
                : rateLimit.timeoutMillis();

        try {
            redis.hasKey(PROBE_KEY).block(Duration.ofMillis(timeoutMillis));
            return Health.up()
                    .withDetail("required", true)
                    .withDetail("rateLimitEnabled", rateLimit.enabled())
                    .withDetail("billingEnabled", billing.enabled())
                    .build();
        } catch (Exception e) {
            // ★ 打进日志的必须是"哪个能力依赖它"，而不是一句 Redis 挂了 ★
            //   值班的人看到 required=true + billingEnabled=true，才知道这次故障
            //   意味着"所有计费请求会被 503"，而不是"某个可选组件抖动了一下"。
            log.error("[健康检查] Redis 不可用，但限流/计费依赖它：rateLimit={} billing={}",
                    rateLimit.enabled(), billing.enabled(), e);
            return Health.down(e)
                    .withDetail("required", true)
                    .withDetail("rateLimitEnabled", rateLimit.enabled())
                    .withDetail("billingEnabled", billing.enabled())
                    .build();
        }
    }
}
