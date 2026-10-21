package io.github.maixiaowen.gateway.config;

import io.github.maixiaowen.gateway.limiter.RateLimitProperties;
import io.github.maixiaowen.gateway.limiter.RateLimitWebFilter;
import io.github.maixiaowen.gateway.limiter.RedisTokenBucketLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;

/**
 * <b>限流的装配 + 启动自检（G4）。</b>
 *
 * <p>它和 {@code ProviderConfig} 是同一类东西：<b>把"配置里写了什么"变成"容器里有什么"。</b>
 * 区别在于 {@code ProviderConfig} 是循环创建 N 个对象，这里是按开关决定要不要创建。
 *
 * <p><b>★ 本类最值钱的一段是 {@link #rateLimitStartupCheck} ★</b>
 * <p>它把限流这个能力的<b>三种状态</b>全部变成了启动期就能看见的事实：
 * <ol>
 *   <li><b>关着</b> → 打一条 WARN。<b>不做静默的能力缺失。</b></li>
 *   <li><b>开着但 Redis 连不上</b> → <b>拒绝启动</b>，并指明要检查哪个配置前缀。</li>
 *   <li><b>开着且 Redis 通</b> → 打一条 INFO，把生效的参数全部列出来。</li>
 * </ol>
 * 为什么这三种都要在<b>启动期</b>处理：
 * <b>限流这种东西"悄悄没生效"是最坏的结果</b> —— 它不会报错、不会有异常、
 * 监控上一切正常，直到账单出来或者上游厂商打电话来说你超了配额。
 */
@Configuration
/* ★ RateLimitProperties 上开了 ignoreUnknownFields = false：
 *   "键名拼错"会变成启动失败，而不是静默取默认值。
 *   理由和真实的踩坑经过写在那两个类/文件上（RateLimitProperties、application.yml）。 */
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /** 启动自检用的探测 key。它只是用来证明"连得上"，不会长期存在 */
    private static final String STARTUP_PROBE_KEY = "gw:rl:__startup_probe__";

    /**
     * <b>限流器本体。</b>
     *
     * <p>注意它<b>不是</b> {@code @Component}，而是像 {@code OpenAiCompatibleProvider} 一样
     * 在这里手工创建 —— 理由一样：<b>它要不要存在，由配置决定，不由组件扫描决定。</b>
     * 组件扫描是编译期固定的，它没法"看 YAML 决定 new 不 new"。
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true")
    public RedisTokenBucketLimiter redisTokenBucketLimiter(ReactiveStringRedisTemplate redis,
                                                           RateLimitProperties properties,
                                                           ObjectMapper objectMapper) {
        return new RedisTokenBucketLimiter(redis, properties, objectMapper);
    }

    /**
     * <b>把限流器挂到请求链路上。</b>
     *
     * <p>Spring WebFlux 会自动收集容器里所有 {@code WebFilter} 类型的 Bean 并串成链，
     * 顺序由各自实现的 {@code Ordered} 决定（见 {@code RateLimitWebFilter#getOrder}）。
     * <b>不需要写任何"注册"代码</b> —— 这是"约定优于配置"的典型例子，也是为什么
     * filter 比在 Controller 里手写调用更不容易漏。
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true")
    public RateLimitWebFilter rateLimitWebFilter(RedisTokenBucketLimiter limiter,
                                                 RateLimitProperties properties,
                                                 ObjectMapper objectMapper) {
        return new RateLimitWebFilter(limiter, properties, objectMapper);
    }

    /**
     * <b>限流的启动自检。</b>一个 Bean 覆盖三种状态，避免把同一个开关判断写三遍。
     *
     * <p><b>★ 为什么"开着却连不上 Redis"必须拒绝启动 ★</b>
     * <p>先看看"不拒绝"会发生什么：应用正常启动、探针是绿的、日志里一行错都没有。
     * 然后每一个请求都会走到 {@code RedisTokenBucketLimiter} 的降级分支 ——
     * 也就是<b>全部 fail-open，限流一次都没生效过，而且没有任何人知道</b>。
     * <p>这和 G3a 里 {@code ProviderRegistry} 拒绝启动的理由是<b>同一条</b>：
     * <blockquote>启动失败是可发现的事故 —— 部署流程会拦住，日志一眼看到；
     * 请求时才报错是静默的灾难 —— 服务看着是健康的，但每个请求都 5xx。</blockquote>
     * 换个说法：<b>你明确打开了限流，说明你判断"没有它不行"。
     * 那么"它其实没生效"就是一次必须被拦下的事故，而不是一个可以容忍的降级。</b>
     *
     * <p><b>★ 这里的 {@code block()} 为什么不算违反 WebFlux 纪律 ★</b>
     * <p>本类禁止在<b>请求处理路径</b>上阻塞（那会占住事件循环线程）。
     * 但 {@link ApplicationRunner} 跑在<b>应用启动阶段的主线程</b>上，此刻还没有任何请求 ——
     * <b>启动阶段的阻塞是可接受的，运行阶段的阻塞不是。</b>
     * 换句话说：<b>不是"不能 block"，是"不能在事件循环线程上 block"。</b>
     * 分清这两件事，比背"WebFlux 里不能用 block"有用得多。
     *
     * @param redis 由 spring-boot-starter-data-redis-reactive 自动配置提供。
     *              ★ 它的存在和 {@code enabled} 无关 —— 只要 starter 在 classpath 上就有这个 Bean。
     *              所以这里不需要 {@code ObjectProvider} 去判空。
     */
    @Bean
    public ApplicationRunner rateLimitStartupCheck(RateLimitProperties properties,
                                                   ReactiveStringRedisTemplate redis) {
        return args -> {
            if (!properties.enabled()) {
                // ★ 这条 WARN 是本类存在的第二理由：避免"静默的能力缺失"。
                //   限流关着，本身完全合理（本地开发不需要 Redis）；
                //   **但"关着"和"忘了配"在监控上长得一模一样。**
                log.warn("[限流未启用] gateway.rate-limit.enabled=false，"
                        + "网关当前【没有任何额度上限】—— 一个循环调用的 bug 就能把预算烧光。"
                        + "生产环境请设 GATEWAY_RATE_LIMIT_ENABLED=true 并确保 Redis 可用。");
                return;
            }

            try {
                redis.hasKey(STARTUP_PROBE_KEY).block(Duration.ofMillis(properties.timeoutMillis()));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "限流已启用（gateway.rate-limit.enabled=true），但启动时连不上 Redis。"
                                + "请检查 spring.data.redis.* 配置 —— ★ Boot 3 的前缀是 spring.data.redis.*，"
                                + "不是 Boot 2.7 的 spring.redis.*，对应的环境变量是 SPRING_DATA_REDIS_HOST。"
                                + "写错前缀不会报错，只会连不上（静默失败）。"
                                + "拒绝启动是刻意的：限流器连不上时静默放行所有流量，等于限流从来没配过。", e);
            }

            log.info("[限流已启用] 容量={}，回填={}/s，单请求调用超时={}ms，fail-open={}，key 前缀={}",
                    properties.capacity(), properties.refillPerSecond(),
                    properties.timeoutMillis(), properties.failOpen(), properties.keyPrefix());
        };
    }
}
