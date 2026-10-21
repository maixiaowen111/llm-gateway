package io.github.maixiaowen.gateway.config;

import io.github.maixiaowen.gateway.billing.BillingProperties;
import io.github.maixiaowen.gateway.billing.BillingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;

/**
 * <b>G7 的装配 + 启动自检。</b>
 *
 * <p><b>★ 和限流最大的不同：BillingService 永远存在，开关只改变它的行为 ★</b>
 * <p>限流器是个"可选组件"——关掉之后整个 Bean 都可以不存在，请求链路上也没有它。
 * 但计费不一样：{@code GatewayChatService} <b>在代码层面依赖它</b>（预扣、结算、退款）。
 * 如果关掉开关就把它从容器里拿掉，服务层就得写一堆 {@code null} 判断 ——
 * <b>"这个能力关了"不应该污染调用方的代码。</b>
 * <p>所以这里让 Bean 常驻、{@code enabled=false} 时内部所有方法都返回 no-op。
 * 这是<b>空对象模式</b>：调用方永远可以放心调用，而不需要知道它是不是开着。
 *
 * <p><b>★ 启动自检：开着却连不上 Redis → 拒绝启动 ★</b>
 * <p>理由和限流器完全一致，但后果更严重一层：
 * 限流失效是"可能超卖"，计费失效是<b>"网关在正常对外服务，但每一笔消耗都不知道该记在谁头上"</b>。
 * 这不会报错、探针是绿的、日志里一行红色都没有 —— 直到月底对账时才发现。
 * <b>启动失败是响亮的；静默漏计是安静的。我们永远选响亮的那一个。</b>
 *
 * <p><b>关着的时候也要打 WARN</b>：本地开发不开计费完全合理，
 * 但"关着"和"忘了开"在监控上长得一模一样。让它在启动日志里存在，是成本最低的提醒。
 */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfig {

    private static final Logger log = LoggerFactory.getLogger(BillingConfig.class);

    /** 探测 key 只用于证明"连得上"，不会长期存在 */
    private static final String STARTUP_PROBE_KEY = "gw:billing:__startup_probe__";

    @Bean
    public BillingService billingService(ReactiveStringRedisTemplate redis,
                                         BillingProperties properties,
                                         ObjectMapper objectMapper) {
        return new BillingService(redis, properties, objectMapper);
    }

    @Bean
    public ApplicationRunner billingStartupCheck(BillingProperties properties,
                                                 ReactiveStringRedisTemplate redis) {
        return args -> {
            if (!properties.enabled()) {
                log.warn("[计费未启用] gateway.billing.enabled=false，网关不会预扣/结算任何 token 消耗。"
                        + "这适合本地开发，但生产环境请设 GATEWAY_BILLING_ENABLED=true —— "
                        + "★ 否则网关是在免费替所有人调用大模型 ★");
                return;
            }

            try {
                // 启动阶段的阻塞是可接受的：ApplicationRunner 跑在主线程，此刻还没有请求。
                // 见 RateLimitConfig 里同一段说明。
                redis.hasKey(STARTUP_PROBE_KEY).block(Duration.ofMillis(properties.timeoutMillis()));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "计费已启用（gateway.billing.enabled=true），但启动时连不上 Redis。"
                                + "请检查 spring.data.redis.* 配置（Boot 3 前缀，环境变量 SPRING_DATA_REDIS_HOST）。"
                                + "拒绝启动是刻意的：计费不可用时如果继续对外服务，"
                                + "这段时间的消耗将无法记账，而且不会有人发现。", e);
            }

            log.info("[计费已启用] 单价=输入 {} / 输出 {} credits per token，默认输出上限={}，"
                            + "预付额度={}，预扣 TTL={}s，Redis 超时={}ms，fail-open={}，key 前缀={}",
                    properties.promptTokenCredits(), properties.completionTokenCredits(),
                    properties.defaultMaxTokens(), properties.initialCredits(),
                    properties.reservationTtlSeconds(), properties.timeoutMillis(),
                    properties.failOpen(), properties.keyPrefix());
        };
    }
}
