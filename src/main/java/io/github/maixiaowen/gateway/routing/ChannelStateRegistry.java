package io.github.maixiaowen.gateway.routing;

import io.github.maixiaowen.gateway.config.ProviderProperties;

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
 * <p><b>⚠️ 一个诚实的限制：本类的状态只在单实例内存里</b>
 * <p>多实例部署时，A 实例把渠道禁用了，B 实例不知道 —— 流量会有一半继续打过去。
 * 生产做法是把这个状态放进 Redis / 配置中心，并带版本号。
 * <b>现在不做，是因为还没有多实例部署可以验证这段同步逻辑；
 * 写一段无法验证的分布式状态同步，比不写更危险。</b>
 */
public class ChannelStateRegistry {

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
        AtomicBoolean flag = enabled.get(channel);
        if (flag == null) {
            throw new IllegalArgumentException("未知渠道：" + channel);
        }
        return flag.getAndSet(value);
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
