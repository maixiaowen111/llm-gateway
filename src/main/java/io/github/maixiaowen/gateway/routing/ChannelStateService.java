package io.github.maixiaowen.gateway.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.Disposable;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>G11：渠道状态的"本地缓存 + 多实例同步"。</b>
 *
 * <p><b>★ 为什么读走本地、写走 Redis ★</b>
 * <p>路由决策里，每选一次候选渠道就要问一次"它启用了吗"。这个动作发生在
 * <b>事件循环线程</b>上，一次 Redis 往返至少是亚毫秒到几毫秒 ——
 * 平时看起来没事，直到 Redis 抖动（持久化、主从切换），
 * <b>网关的每个请求都开始等 Redis</b>。那正是限流器注释里写过的死法：
 * 不是"保护组件失效"，而是"保护组件把网关拖死"。
 * <p>所以：<b>热路径只读本地内存；Redis 只负责"权威值"和"通知"。</b>
 *
 * <p><b>★ 启动顺序里有一个容易被忽略的窗口 ★</b>
 * <p>如果"先拉快照、再订阅"，两步之间发生的变更会永远丢失
 * （Pub/Sub 不保留历史消息），本地缓存会一直陈旧到下一次有人改它。
 * <p>本类的做法是 <b>先订阅（把消息缓冲起来）→ 再拉快照 → 最后按版本号回放缓冲</b>：
 * <pre>
 *   t0  订阅开始，ready=false，收到的消息进 pending 队列
 *   t1  HGETALL 得到快照，版本 = v
 *   t2  ready=true，丢弃 pending 里 version ≤ v 的旧消息，应用剩余的
 * </pre>
 * 这样无论变更落在快照前还是快照后，最终状态都收敛到 Redis。
 * <b>这是一个真实存在的竞态，不是理论洁癖 —— 只差几毫秒，但它会随机发生。</b>
 */
public class ChannelStateService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChannelStateService.class);

    private final ChannelStateRegistry local;
    private final ChannelStateStore store;

    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicLong lastVersion = new AtomicLong(0);
    private final Queue<ChannelStateStore.StateChange> pending = new ConcurrentLinkedQueue<>();

    private volatile Disposable subscription;

    /**
     * @param store 为 null 表示单实例模式（{@code local}）：行为与 G6 完全一致
     */
    public ChannelStateService(ChannelStateRegistry local, ChannelStateStore store) {
        this.local = local;
        this.store = store;
    }

    /**
     * 启动同步。由 {@code ApplicationRunner} 调用 —— 启动阶段允许阻塞。
     *
     * @throws IllegalStateException 配置了 redis 模式却连不上 Redis。
     *         <b>拒绝启动是刻意的</b>：否则每个实例各自用配置默认值跑，
     *         管理员在面板上"成功"摘掉渠道，实际一半流量还在打过去。
     */
    public void start() {
        if (store == null) {
            log.info("[渠道状态] 单实例模式：状态只在本实例内存中。"
                    + "多实例部署请设 gateway.channel-state.mode=redis，否则摘渠道只对当前实例生效。");
            return;
        }

        try {
            // ① 先订阅并缓冲。subscribe 是异步的，但连接建立先于后续快照请求；
            //    即使有个别消息在 subscribe 返回后才到，它们也会进 pending。
            subscription = store.changes().subscribe(
                    change -> {
                        if (!ready.get()) {
                            pending.add(change);
                        } else {
                            apply(change);
                        }
                    },
                    error -> log.error("[渠道状态] 变更订阅中断，本地状态可能陈旧，"
                            + "直到下一次实例重启或 Redis 重连", error));

            // ② 拉快照
            ChannelStateStore.Snapshot snapshot = store.loadSnapshot();
            lastVersion.set(snapshot.version());
            snapshot.channels().forEach(local::applyRemote);

            // ③ 回放缓冲，只应用比快照更新的消息
            ready.set(true);
            ChannelStateStore.StateChange change;
            int applied = 0;
            while ((change = pending.poll()) != null) {
                if (change.version() > lastVersion.get()) {
                    apply(change);
                    applied++;
                }
            }

            log.info("[渠道状态] Redis 模式已启用。启动快照 {} 条，回放缓冲 {} 条，"
                            + "当前版本={}，订阅频道已建立。",
                    snapshot.channels().size(), applied, lastVersion.get());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "gateway.channel-state.mode=redis，但启动时无法连接 Redis 或读取状态。"
                            + "拒绝启动是刻意的：多实例状态下，一个连不上权威存储的实例"
                            + "会继续用本地旧值路由 —— 管理员以为摘掉的渠道，实际还在接流量。"
                            + "请检查 spring.data.redis.* 配置。", e);
        }
    }

    /**
     * 运行期修改。Redis 模式先写权威存储，成功后再改本地缓存。
     *
     * <p><b>为什么不是"先改本地再异步写 Redis"</b>：那样 Redis 写失败时，
     * 本实例已经按新状态路由了，而其它实例没有 —— <b>一次操作产生两种事实</b>。
     * 管理员看到 200，实际只生效了一部分，这是最难排查的故障。
     *
     * @return 修改前的值（审计日志需要）
     * @throws ChannelStateUnavailableException Redis 模式写失败（对外 503）
     */
    public boolean setEnabled(String channel, boolean value) {
        // ★ 先校验渠道存在，再碰 Redis ★
        //   否则未知渠道会被写进 Redis，启动快照里出现一个本实例没有 Provider 的孤儿字段。
        if (!local.isKnown(channel)) {
            throw new IllegalArgumentException("未知渠道：" + channel);
        }
        if (store == null) {
            return local.setEnabled(channel, value);
        }

        boolean previous = store.setEnabled(channel, value);
        local.applyRemote(channel, value);
        return previous;
    }

    public boolean redisMode() {
        return store != null;
    }

    /** 应用一条通知。版本号保证乱序消息不会把状态改回旧值。 */
    private void apply(ChannelStateStore.StateChange change) {
        if (change.version() <= lastVersion.get()) {
            // 旧消息：可能是重连后补发的，也可能是并发写入中较慢的那条。
            // 直接丢弃 —— 本地状态应该收敛到"最后一次成功写入"，不是"最后一条到达"。
            return;
        }
        lastVersion.set(change.version());
        local.applyRemote(change.channel(), change.enabled());
        log.info("[渠道状态] 收到远程变更 channel={} enabled={} version={}",
                change.channel(), change.enabled(), change.version());
    }

    @Override
    public void destroy() {
        Disposable current = subscription;
        if (current != null && !current.isDisposed()) {
            current.dispose();
        }
    }
}
