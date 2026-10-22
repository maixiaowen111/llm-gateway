package io.github.maixiaowen.gateway.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>G11：把渠道状态放进 Redis —— 多实例部署时"摘渠道"才能真的摘掉。</b>
 *
 * <p><b>★ 数据布局 ★</b>
 * <pre>
 *   HASH  gw:channel-state:state
 *         deepseek    = true
 *         moonshot    = false
 *         __version__ = 42          ← 全局单调版本号，见 ChannelStateStore 的注释
 *
 *   PUBSUB gw:channel-state:changed
 *          "moonshot\tfalse\t42"     ← 渠道、新值、版本号
 * </pre>
 * <p><b>为什么状态放在一个 HASH 里，而不是每个渠道一个 key</b>：
 * 启动拉快照只需要一次 {@code HGETALL}；每个渠道一个 key 会变成 N 次往返。
 * 渠道数量是配置驱动的、可控的，单 HASH 不会退化成大 key。
 *
 * <p><b>★ 为什么写操作用 Lua ★</b>：见 {@code scripts/channel_state_set.lua} 的注释。
 * 一句话：读旧值、写新值、版本 +1、发通知，这四步必须是一个原子动作。
 *
 * <p><b>⚠️ 本实现不做"本地缓存更新"</b> —— 那是 {@link ChannelStateService} 的职责。
 * 存储层只回答"Redis 现在是什么"，不关心"这个实例的内存里是什么"。
 * <b>把两件事混在一起，重连/漏消息时就会出现"内存改了、Redis 没改"的分裂。</b>
 */
public class RedisChannelStateStore implements ChannelStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChannelStateStore.class);

    /** HASH 里的内部版本字段。渠道名来自 YAML 配置，启动校验不允许它以 __ 开头 */
    static final String VERSION_FIELD = "__version__";

    /** 脚本只在类加载时读一次；之后每次执行只传 KEYS/ARGV */
    private static final DefaultRedisScript<List> SET_ENABLED =
            new DefaultRedisScript<>(readScript("scripts/channel_state_set.lua"), List.class);

    private final ReactiveStringRedisTemplate redis;
    private final ChannelStateProperties properties;

    public RedisChannelStateStore(ReactiveStringRedisTemplate redis,
                                  ChannelStateProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Snapshot loadSnapshot() {
        // ★ 这里 block 是刻意的，而且只发生在启动期 ★
        //   理由和 RateLimitConfig 的启动自检相同：启动阶段的主线程上还没有任何请求，
        //   阻塞不会占住事件循环。运行期读的是本地缓存，不会走到这里。
        Map<?, ?> raw = redis.opsForHash()
                .entries(properties.hashKey())
                .collectMap(entry -> entry.getKey(), entry -> entry.getValue())
                .block(Duration.ofMillis(properties.timeoutMillis()));

        Map<String, Boolean> channels = new LinkedHashMap<>();
        long version = 0;
        if (raw != null) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                String field = String.valueOf(entry.getKey());
                String value = String.valueOf(entry.getValue());
                if (VERSION_FIELD.equals(field)) {
                    version = parseLong(value, 0);
                } else {
                    channels.put(field, Boolean.parseBoolean(value));
                }
            }
        }
        return new Snapshot(channels, version);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean setEnabled(String channel, boolean value) {
        List<String> result;
        try {
            // execute(...) 返回的是 Flux<T>：脚本可能在集群的多个节点上执行并逐个返回结果。
            // 本脚本没有 KEYS 分片，只有一个结果，所以 next() 就是它；
            // 用 blockLast 会掩盖"返回了多条"这种异常形态。
            result = redis.execute(
                            SET_ENABLED,
                            List.of(properties.hashKey()),
                            List.of(channel, String.valueOf(value), properties.channelTopic()))
                    .next()
                    .block(Duration.ofMillis(properties.timeoutMillis()));
        } catch (RuntimeException e) {
            // ★ 统一成一种异常：上层（AdminController）只需要认识一个类型 ★
            //   否则 Redis 超时、连接拒绝、Lua 报错会分别以三四种异常冒到 Controller，
            //   每加一种底层故障就要改一次 Controller —— 那是泄漏抽象。
            throw new ChannelStateUnavailableException(
                    "写入渠道状态失败（channel=" + channel + "，Redis 可能不可用）", e);
        }

        if (result == null || result.isEmpty()) {
            throw new ChannelStateUnavailableException(
                    "Redis 未返回渠道状态写入结果（channel=" + channel + "）", null);
        }
        // 之前不存在时 Lua 返回空串 —— 此时"前值"按配置默认值（启用）理解。
        // 这和 ChannelStateRegistry 的初始化语义一致：没写过 = 配置里的初始值。
        String previous = String.valueOf(result.get(0));
        return previous.isEmpty() || Boolean.parseBoolean(previous);
    }

    @Override
    public Flux<StateChange> changes() {
        // listenTo 返回的 Flux 在订阅时建立连接，断线由 Lettuce 负责重连；
        // 重连期间漏掉的消息由"启动快照 + 版本号"兜住 —— 但运行期漏消息
        // 仍然可能让本地缓存短暂陈旧。这是 Pub/Sub 的固有限制（它不是持久化队列）。
        // 生产上如果要求强一致，应改为 Redis Streams 或配置中心长轮询；
        // 本项目选择 Pub/Sub，是因为渠道开关的"秒级最终一致"已经足够。
        return redis.listenTo(ChannelTopic.of(properties.channelTopic()))
                // listenTo 给的是 Message 包装（带频道名），不是裸的 payload。
                // 频道名这里用不上 —— 一个连接只订阅了这一个频道。
                .map(message -> message.getMessage())
                .map(this::parse)
                .filter(change -> change != null)
                .doOnError(error -> log.error(
                        "[渠道状态] 订阅变更频道失败，本实例将只依赖启动快照（可能陈旧）", error));
    }

    private StateChange parse(String message) {
        String[] parts = message.split("\t");
        if (parts.length != 3) {
            log.warn("[渠道状态] 忽略无法解析的变更消息：{}", message);
            return null;
        }
        try {
            return new StateChange(parts[0], Boolean.parseBoolean(parts[1]),
                    Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            log.warn("[渠道状态] 忽略版本号非法的变更消息：{}", message);
            return null;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String readScript(String path) {
        try {
            return new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Lua 脚本失败：" + path, e);
        }
    }
}
