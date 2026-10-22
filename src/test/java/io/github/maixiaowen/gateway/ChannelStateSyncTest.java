package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.config.ProviderProperties;
import io.github.maixiaowen.gateway.routing.ChannelStateRegistry;
import io.github.maixiaowen.gateway.routing.ChannelStateService;
import io.github.maixiaowen.gateway.routing.ChannelStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>G11 的核心验收：两个"实例"改同一个渠道，另一个必须看得见。</b>
 *
 * <p>用一个内存 Store 模拟 Redis 的 Pub/Sub，好处是<b>能精确控制版本号和消息顺序</b> ——
 * 真实的 Redis 很难稳定复现"旧消息后到"这种竞态，而在单测里它只是两行代码。
 *
 * <p><b>★ 本类不验 HTTP，也不验 Redis 协议 ★</b>
 * <p>它验的是同步<b>算法</b>：本地缓存是否更新、旧版本是否被丢弃、启动快照是否生效。
 * Redis 的真实读写和 Pub/Sub 由 {@code RedisChannelStateStoreTest} 验，
 * 两者缺一不可：<b>算法对但接线错 = 没同步；接线对但算法错 = 同步成错的值。</b>
 */
@DisplayName("G11 渠道状态：多实例同步算法")
class ChannelStateSyncTest {

    @Test
    @DisplayName("A 实例禁用渠道后，B 实例的路由视图立刻变为禁用")
    void changeOnOneInstancePropagatesToAnother() {
        FakeStore store = new FakeStore();
        ChannelStateRegistry registryA = registry();
        ChannelStateRegistry registryB = registry();

        ChannelStateService a = new ChannelStateService(registryA, store);
        ChannelStateService b = new ChannelStateService(registryB, store);
        a.start();
        b.start();

        assertThat(registryA.isEnabled("deepseek")).isTrue();
        assertThat(registryB.isEnabled("deepseek")).isTrue();

        boolean previous = a.setEnabled("deepseek", false);

        assertThat(previous).isTrue();
        assertThat(registryA.isEnabled("deepseek")).isFalse();
        // ★ 这一行才是 G11 的全部意义 ★
        assertThat(registryB.isEnabled("deepseek")).isFalse();

        a.destroy();
        b.destroy();
    }

    @Test
    @DisplayName("启动时从权威存储拉快照 —— 离线期间的变更不会丢")
    void startupLoadsSnapshotFromStore() {
        FakeStore store = new FakeStore();
        store.state.put("deepseek", false);
        store.version = 7;

        ChannelStateRegistry registry = registry();
        ChannelStateService service = new ChannelStateService(registry, store);
        service.start();

        assertThat(registry.isEnabled("deepseek")).isFalse();

        // 快照里的版本号必须被继承：随后到来的 version=6 属于旧消息，应被丢弃。
        store.emit("deepseek", true, 6);
        assertThat(registry.isEnabled("deepseek")).isFalse();

        // version=8 比快照新，必须应用。
        store.emit("deepseek", true, 8);
        assertThat(registry.isEnabled("deepseek")).isTrue();

        service.destroy();
    }

    @Test
    @DisplayName("乱序消息：旧版本不能覆盖新状态")
    void staleEventIsIgnored() {
        FakeStore store = new FakeStore();
        ChannelStateRegistry registry = registry();
        ChannelStateService service = new ChannelStateService(registry, store);
        service.start();

        store.emit("deepseek", false, 5);
        store.emit("deepseek", true, 3);   // 迟到的旧消息

        assertThat(registry.isEnabled("deepseek")).isFalse();
        service.destroy();
    }

    @Test
    @DisplayName("单实例模式（store=null）行为与 G6 一致，不需要 Redis")
    void localModeWorksWithoutStore() {
        ChannelStateRegistry registry = registry();
        ChannelStateService service = new ChannelStateService(registry, null);
        service.start();

        assertThat(service.redisMode()).isFalse();
        assertThat(service.setEnabled("deepseek", false)).isTrue();
        assertThat(registry.isEnabled("deepseek")).isFalse();

        service.destroy();
    }

    @Test
    @DisplayName("未知渠道在碰 Redis 之前就被拒绝（不留下孤儿字段）")
    void unknownChannelIsRejectedBeforeStoreWrite() {
        FakeStore store = new FakeStore();
        ChannelStateService service = new ChannelStateService(registry(), store);
        service.start();

        assertThatThrownBy(() -> service.setEnabled("not-a-channel", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知渠道");

        assertThat(store.state).doesNotContainKey("not-a-channel");
        service.destroy();
    }

    private static ChannelStateRegistry registry() {
        ProviderProperties.Channel channel = new ProviderProperties.Channel(
                "openai", "http://localhost:1", "k", "m", 1, true,
                List.of("m"), "default", null, false);
        Map<String, ProviderProperties.Channel> channels = new LinkedHashMap<>();
        channels.put("deepseek", channel);
        return new ChannelStateRegistry(
                new ProviderProperties("deepseek", channels, null));
    }

    /** 用 Sinks 模拟 Redis 的 Pub/Sub；multicast 让两个"实例"都收到 */
    private static final class FakeStore implements ChannelStateStore {

        private final Map<String, Boolean> state = new LinkedHashMap<>();
        private final Sinks.Many<StateChange> sink =
                Sinks.many().multicast().onBackpressureBuffer();
        private long version = 0;

        @Override
        public Snapshot loadSnapshot() {
            return new Snapshot(Map.copyOf(state), version);
        }

        @Override
        public boolean setEnabled(String channel, boolean value) {
            boolean previous = state.getOrDefault(channel, true);
            state.put(channel, value);
            version++;
            sink.tryEmitNext(new StateChange(channel, value, version));
            return previous;
        }

        @Override
        public Flux<StateChange> changes() {
            return sink.asFlux();
        }

        void emit(String channel, boolean value, long eventVersion) {
            sink.tryEmitNext(new StateChange(channel, value, eventVersion));
        }
    }
}
