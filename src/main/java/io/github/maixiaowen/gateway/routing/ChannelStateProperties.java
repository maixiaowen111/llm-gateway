package io.github.maixiaowen.gateway.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>G11：渠道运行期状态的存放方式。</b>
 *
 * <p><b>★ 为什么"单实例内存"必须被替换掉 ★</b>
 * <p>{@link ChannelStateRegistry} 最初的实现把"渠道是否启用"放在每个实例自己的内存里。
 * 单实例时没问题；一旦部署两个实例，运维半夜摘掉一条渠道，
 * <b>只有收到那次 HTTP 请求的那个实例知道，另一个实例继续把一半流量打过去。</b>
 * 更糟的是：<b>它不会报错，面板上还会显示"已禁用成功"</b> ——
 * 因为面板查的正是它自己那个实例。
 *
 * <p><b>★ 为什么不用"每次请求都查 Redis"★</b>
 * <p>渠道状态在<b>每个请求的路由决策</b>里都要读（甚至每个候选渠道读一次）。
 * 在事件循环线程上做一次网络往返，等于把网关最热的路径交给 Redis 的延迟。
 * <p>所以采用 <b>本地缓存 + Redis 权威值 + Pub/Sub 失效通知</b>：
 * <ul>
 *   <li>读：本地 {@code ConcurrentHashMap}，纳秒级；</li>
 *   <li>写：Lua 脚本原子更新 Redis，带版本号；</li>
 *   <li>同步：每个实例订阅变更频道，收到后更新本地缓存；</li>
 *   <li>兜底：实例启动时从 Redis 拉一次快照 —— <b>离线期间的变更不会丢</b>。</li>
 * </ul>
 * 这是配置中心 / 服务发现里通用的"推拉结合"模式：
 * <b>推送保证低延迟，拉取保证最终一致。</b>
 *
 * @param mode           {@code local}（默认，单实例）或 {@code redis}（多实例）
 * @param keyPrefix      Redis key / 频道前缀。多环境共用 Redis 时必须隔离
 * @param timeoutMillis  启动期拉快照和写入的超时。
 *                       运行期读本地缓存，不受它影响；<b>它只约束"同步动作"</b>
 */
@ConfigurationProperties(prefix = "gateway.channel-state", ignoreUnknownFields = false)
public record ChannelStateProperties(
        String mode,
        String keyPrefix,
        long timeoutMillis
) {

    private static final String DEFAULT_KEY_PREFIX = "gw:channel-state";
    private static final long DEFAULT_TIMEOUT_MILLIS = 300;

    public ChannelStateProperties {
        mode = (mode == null || mode.isBlank()) ? "local" : mode.trim().toLowerCase();
        keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? DEFAULT_KEY_PREFIX : keyPrefix;
        timeoutMillis = timeoutMillis <= 0 ? DEFAULT_TIMEOUT_MILLIS : timeoutMillis;

        if (!"local".equals(mode) && !"redis".equals(mode)) {
            // ★ 拼错 mode 必须启动失败：写成 "redsi" 时静默退回 local，
            //   就变成了"以为多实例同步了，其实没有"—— 那是最坏的故障形态。
            throw new IllegalStateException(
                    "gateway.channel-state.mode 只能是 local 或 redis，当前值 = " + mode);
        }
    }

    public boolean redisMode() {
        return "redis".equals(mode);
    }

    public String hashKey() {
        return keyPrefix + ":state";
    }

    public String channelTopic() {
        return keyPrefix + ":changed";
    }
}
