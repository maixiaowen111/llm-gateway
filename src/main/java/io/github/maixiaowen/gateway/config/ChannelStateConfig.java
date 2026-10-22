package io.github.maixiaowen.gateway.config;

import io.github.maixiaowen.gateway.routing.ChannelStateProperties;
import io.github.maixiaowen.gateway.routing.ChannelStateRegistry;
import io.github.maixiaowen.gateway.routing.ChannelStateService;
import io.github.maixiaowen.gateway.routing.ChannelStateStore;
import io.github.maixiaowen.gateway.routing.RedisChannelStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * <b>G11：渠道状态的装配。</b>
 *
 * <p>单实例模式（{@code local}）下不创建任何 Store Bean ——
 * 和服务层拿到 {@code null} 是同一个意思：<b>"没有权威存储"是一个明确的状态，
 * 不是一个需要到处判空的意外。</b>
 *
 * <p>Redis 模式下多了一个启动动作 {@link ChannelStateService#start()}：
 * 先订阅、再拉快照、回放缓冲。它必须发生在应用开始接流量之前，
 * 所以用 {@link ApplicationRunner} 而不是 {@code @PostConstruct} ——
 * 前者在所有 Bean 就绪后、Web 服务器开始接请求前执行，时序更清楚。
 */
@Configuration
@EnableConfigurationProperties(ChannelStateProperties.class)
public class ChannelStateConfig {

    @Bean
    @ConditionalOnProperty(prefix = "gateway.channel-state", name = "mode", havingValue = "redis")
    public ChannelStateStore channelStateStore(ReactiveStringRedisTemplate redis,
                                               ChannelStateProperties properties) {
        return new RedisChannelStateStore(redis, properties);
    }

    @Bean
    public ChannelStateService channelStateService(ChannelStateRegistry registry,
                                                   ObjectProvider<ChannelStateStore> storeProvider) {
        return new ChannelStateService(registry, storeProvider.getIfAvailable());
    }

    @Bean
    public ApplicationRunner channelStateStartupSync(ChannelStateService service) {
        return args -> service.start();
    }
}
