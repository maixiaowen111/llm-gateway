package io.github.maixiaowen.gateway.routing;

import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * <b>G11：渠道状态的"权威存储"接口。</b>
 *
 * <p><b>为什么要有这层接口，而不是直接在 {@code ChannelStateService} 里写 Redis</b>：
 * <ul>
 *   <li><b>测试</b>：可以用一个内存实现验证"多实例同步"这件事本身，
 *       不必每次起容器；</li>
 *   <li><b>可替换</b>：将来换成 Nacos / Apollo / etcd，只换实现，
 *       服务层和路由层一行不动 —— 和 {@code Provider} SPI 是同一条理由；</li>
 *   <li><b>职责边界</b>：存储负责"原子地改 + 通知"，服务负责"本地缓存 + 版本判断"。</li>
 * </ul>
 *
 * <p><b>★ 注意接口里没有"读单个渠道"的方法 ★</b>
 * <p>运行期读的是本地缓存（见 {@link ChannelStateRegistry}），
 * 存储只在<b>启动时</b>被拉一次快照。这不是遗漏 ——
 * 把"每次路由都读 Redis"设计进接口，等于把网络延迟写进了热路径。
 */
public interface ChannelStateStore {

    /**
     * 读取全量快照。只在启动期调用（可以阻塞等待）。
     *
     * <p><b>版本号必须和快照一起返回</b>：启动后订阅到的事件如果版本更小，
     * 说明它是"写入发生在拉快照之前、消息却现在才到"的旧事件，必须丢弃。
     * 只返回 Map 会让服务层从 0 重新计数，把旧事件当成新事件应用。
     */
    Snapshot loadSnapshot();

    /**
     * 原子地写入一个渠道的开关，并广播变更。
     *
     * @return 修改前的值（用于审计日志"从 x 改成 y"）
     * @throws ChannelStateUnavailableException 存储不可用
     */
    boolean setEnabled(String channel, boolean value);

    /**
     * 变更事件流。消息格式由实现定义，但必须能表达"{@code channel} 变成了 {@code value}，
     * 版本号是 {@code version}"。
     *
     * <p><b>为什么用 {@link Flux} 而不是注册回调</b>：底层连接断开重连、
     * 背压、取消，这些语义 Reactor 已经处理好了。自己维护一个回调列表，
     * 迟早会在"重连后回调丢了"这类问题上翻车。
     */
    Flux<StateChange> changes();

    /**
     * 启动快照。
     *
     * @param channels channel → enabled
     * @param version  快照对应的全局版本号
     */
    record Snapshot(Map<String, Boolean> channels, long version) {
    }

    /**
     * 一条变更通知。
     *
     * @param version 单调递增的全局版本号。<b>它解决的是消息乱序</b>：
     *                两个实例同时改同一条渠道时，到达顺序不保证等于写入顺序，
     *                只应用版本更大的那条，才能收敛到 Redis 里的最终值。
     */
    record StateChange(String channel, boolean enabled, long version) {
    }
}
