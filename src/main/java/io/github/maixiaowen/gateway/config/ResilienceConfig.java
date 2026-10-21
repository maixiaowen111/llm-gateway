package io.github.maixiaowen.gateway.config;

import io.github.maixiaowen.gateway.billing.BillingService;
import io.github.maixiaowen.gateway.observability.GatewayMetrics;
import io.github.maixiaowen.gateway.provider.ProviderRegistry;
import io.github.maixiaowen.gateway.resilience.ChannelCircuitBreakers;
import io.github.maixiaowen.gateway.resilience.ResilienceProperties;
import io.github.maixiaowen.gateway.routing.ChannelRouter;
import io.github.maixiaowen.gateway.routing.ChannelStateRegistry;
import io.github.maixiaowen.gateway.service.GatewayChatService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <b>G5/G6 的装配点：熔断器、渠道状态、路由器、执行引擎。</b>
 *
 * <p><b>为什么这四个类都不用 {@code @Component}</b>：
 * 它们都需要"渠道列表 + 每渠道配置"这种<b>运行期才知道</b>的参数
 * （渠道是从 YAML 里读出来的，不是编译期固定的）。
 * 构造函数里明确写出来的依赖，比分散在字段上的 {@code @Autowired} 更能说明
 * "这个对象活着需要什么"。<b>一个类的依赖越显式，它就越容易在单测里被造出来。</b>
 *
 * <p><b>装配顺序也是设计的一部分</b>：
 * <pre>
 *   ProviderRegistry（静态：有哪些渠道）
 *          ↓
 *   ChannelStateRegistry（运行期开关 + 分组 + 权重）
 *   ChannelCircuitBreakers（每渠道一个熔断器）
 *          ↓
 *   ChannelRouter（决策：这次走哪几条）
 *          ↓
 *   GatewayChatService（执行：打、重试、转移、熔断记账）
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceConfig {

    @Bean
    public ChannelStateRegistry channelStateRegistry(ProviderProperties properties) {
        return new ChannelStateRegistry(properties);
    }

    @Bean
    public ChannelCircuitBreakers channelCircuitBreakers(ResilienceProperties resilience,
                                                         ProviderRegistry registry,
                                                         MeterRegistry meterRegistry) {
        return new ChannelCircuitBreakers(resilience, registry.names(), meterRegistry);
    }

    @Bean
    public ChannelRouter channelRouter(ProviderRegistry registry,
                                       ProviderProperties properties,
                                       ChannelStateRegistry states,
                                       ChannelCircuitBreakers circuitBreakers) {
        return new ChannelRouter(registry, properties, states, circuitBreakers);
    }

    @Bean
    public GatewayChatService gatewayChatService(ChannelRouter router,
                                                 ChannelCircuitBreakers circuitBreakers,
                                                 ResilienceProperties resilience,
                                                 BillingService billing,
                                                 GatewayMetrics metrics) {
        return new GatewayChatService(router, circuitBreakers, resilience, billing, metrics);
    }
}
