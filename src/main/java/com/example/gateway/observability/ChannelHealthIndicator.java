package com.example.gateway.observability;

import com.example.gateway.routing.ChannelRouter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>G9：把"渠道健康度"纳入 /actuator/health。</b>
 *
 * <p><b>★ 为什么健康检查不能只返回 "UP" ★</b>
 * <p>默认的 health 只告诉编排层"进程还活着"。但一个进程活着、所有渠道都被禁用
 * 或者全部熔断的网关，<b>对外提供的价值和挂掉没有区别</b> ——
 * 它只会用 503 礼貌地拒绝每一个用户。
 * <p>把渠道状态聚合进 health，编排层才能做出正确的动作
 * （不再给它导流量 / 重启 / 告警），而不是看着一片绿色什么都不做。
 *
 * <p><b>⚠️ 这里有一个必须讲清楚的取舍：熔断全部打开时，健康状态是 DOWN。</b>
 * <p>这会让 K8s 把 Pod 从 Service 里摘掉。这是对的：一个所有上游都连不上的实例，
 * 继续接流量只会让用户等更久。<b>但它不是"重启就能好"的问题</b> ——
 * 所以 details 里必须把每条渠道的状态写清楚，让值班的人一眼看出
 * "这是上游全挂了，不是我的 Pod 坏了"。<b>健康检查既要做判断，也要给证据。</b>
 */
@Component("channels")
public class ChannelHealthIndicator implements HealthIndicator {

    private final ChannelRouter router;

    public ChannelHealthIndicator(ChannelRouter router) {
        this.router = router;
    }

    @Override
    public Health health() {
        List<ChannelRouter.ChannelInfo> channels = router.channelInfos();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("total", channels.size());

        boolean anyUsable = false;
        int enabledCount = 0;
        for (ChannelRouter.ChannelInfo channel : channels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enabled", channel.enabled());
            item.put("group", channel.group());
            item.put("weight", channel.weight());
            item.put("circuit", channel.circuitState());
            item.put("failureRatePercent", channel.failureRatePercent());
            details.put(channel.name(), item);

            if (channel.enabled()) {
                enabledCount++;
                if (!isOpen(channel.circuitState())) {
                    anyUsable = true;
                }
            }
        }
        details.put("enabledCount", enabledCount);

        if (channels.isEmpty()) {
            // 一条渠道都没有：这不是"降级"，是配置根本错了。启动期就该校验，这里再兜一次。
            return Health.down().withDetails(details)
                    .withDetail("reason", "没有配置任何渠道").build();
        }
        if (!anyUsable) {
            return Health.down().withDetails(details)
                    .withDetail("reason", "所有渠道都被禁用或熔断，网关无法服务任何请求")
                    .build();
        }
        return Health.up().withDetails(details).build();
    }

    /**
     * 只有 OPEN / FORCED_OPEN 算"不可用"。
     * <p>{@code HALF_OPEN} 必须算可用 —— 它正在进行恢复探测，
     * 把它算成 DOWN 会让熔断器<b>永远没有机会收到那个用来恢复的请求</b>，从而永远开下去。
     * <p>{@code DISABLED}（Resilience4j 里表示"熔断被关掉了"）当然也算可用。
     */
    private boolean isOpen(String circuitState) {
        return "OPEN".equals(circuitState) || "FORCED_OPEN".equals(circuitState);
    }
}
