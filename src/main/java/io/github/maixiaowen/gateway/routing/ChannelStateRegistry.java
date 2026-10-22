package io.github.maixiaowen.gateway.routing;

import io.github.maixiaowen.gateway.config.ProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>G6：渠道的"运行期开关"。</b>
 *
 * <p><b>它和配置文件里的 {@code enabled} 是什么关系</b>：
 * YAML 里的值是<b>初始值</b>（重启后回到这里），本类保存的是<b>运行期值</b>。
 * 运维在半夜发现某条渠道账单异常，要立刻把它摘掉 —— 不可能等一次重新部署。
 *
 * <p><b>★ 为什么不在原配置对象上改 ★</b>
 * <p>{@code ProviderProperties} 是 record，设计上就是<b>只读</b>的。
 * 让它可变会带来两个问题：① 并发读写没有保护；② "配置"和"状态"混成一个概念，
 * 之后做热更新时根本分不清"这个值是用户配的，还是某个接口改的"。
 * <b>配置是输入，状态是运行结果，两者必须分开存。</b>
 *
 * <p><b>★ G11：本类现在只负责"本地视图"，不再假装它是全局真相 ★</b>
 * <p>多实例部署时，权威状态在 Redis（见 {@link RedisChannelStateStore}），
 * 每个实例持有一份本地缓存，通过 Pub/Sub 更新。这样路由读的仍然是
 * {@link #isEnabled(String)}（纳秒级内存读），而不是每个请求打一次 Redis。
 * <p><b>⚠️ 单实例模式（默认）下，它仍然是唯一真相</b>：没有 Redis 时，
 * 一个实例改了、另一个实例不知道。启动日志会明确告诉你当前是哪种模式。
 */
public class ChannelStateRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelStateRegistry.class);

    private final Map<String, AtomicBoolean> enabled = new ConcurrentHashMap<>();
    private final Map<String, String> groups;
    private final Map<String, Integer> weights;

    public ChannelStateRegistry(ProviderProperties properties) {
        Map<String, String> groupView = new LinkedHashMap<>();
        Map<String, Integer> weightView = new LinkedHashMap<>();

        for (Map.Entry<String, ProviderProperties.Channel> entry : properties.channels().entrySet()) {
            String name = entry.getKey();
            ProviderProperties.Channel channel = entry.getValue();
            enabled.put(name, new AtomicBoolean(channel.enabled()));
            groupView.put(name, channel.group());
            weightView.put(name, channel.weight());
        }

        this.groups = Map.copyOf(groupView);
        this.weights = Map.copyOf(weightView);
    }

    public boolean isEnabled(String channel) {
        AtomicBoolean value = enabled.get(channel);
        return value == null || value.get();
    }

    /**
     * 运行期开关。
     *
     * @return 修改前的值（方便审计日志写"从 x 改成 y"）
     */
    public boolean setEnabled(String channel, boolean value) {
        AtomicBoolean flag = requireKnown(channel);
        return flag.getAndSet(value);
    }

    /**
     * <b>应用一条来自权威存储的变更。</b>
     *
     * <p>只改本地缓存，不写 Redis —— 写入由 {@link ChannelStateService} 负责。
     * <b>未知渠道直接忽略</b>：Redis 里可能残留着已从配置删除的渠道，
     * 本实例没有它的 Provider，记下来也没有意义。
     *
     * <p><b>为什么单独一个方法，而不是让同步组件直接调 {@code setEnabled}</b>：
     * 语义不同。{@code setEnabled} 是"我要改"，{@code applyRemote} 是"我被告知要改"。
     * 前者要返回旧值做审计，后者不需要；更重要的是，
     * <b>将来如果给 {@code setEnabled} 加"写审计日志"，applyRemote 不应该跟着写一份。</b>
     */
    public void applyRemote(String channel, boolean value) {
        AtomicBoolean flag = enabled.get(channel);
        if (flag == null) {
            log.debug("[渠道状态] 忽略未知渠道的远程状态：{}", channel);
            return;
        }
        flag.set(value);
    }

    public boolean isKnown(String channel) {
        return enabled.containsKey(channel);
    }

    private AtomicBoolean requireKnown(String channel) {
        AtomicBoolean flag = enabled.get(channel);
        if (flag == null) {
            throw new IllegalArgumentException("未知渠道：" + channel);
        }
        return flag;
    }

    public String groupOf(String channel) {
        return groups.getOrDefault(channel, ProviderProperties.DEFAULT_GROUP);
    }

    public int weightOf(String channel) {
        return weights.getOrDefault(channel, 1);
    }

    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> view = new LinkedHashMap<>();
        enabled.forEach((name, flag) -> view.put(name, flag.get()));
        return Map.copyOf(view);
    }
}
