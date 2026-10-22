package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.routing.ChannelStateProperties;
import io.github.maixiaowen.gateway.routing.ChannelStateStore;
import io.github.maixiaowen.gateway.routing.RedisChannelStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>G11 的真实 Redis 验收：Lua 原子写 + 版本号 + Pub/Sub 真的通。</b>
 *
 * <p>为什么不用 mock：<b>Lua 脚本的返回形态、Pub/Sub 的 Message 包装、
 * Reactive 模板的 Flux/Mono 差异</b> —— 这三样只有真 Redis 能证明。
 * 用 mock 测出来的"通过"，在换成真 Redis 时可能连编译期类型都对不上。
 *
 * <p><b>⚠️ 运行前提：本机 Docker 可用</b>（Testcontainers 起 {@code redis:7}）。
 */
@DisplayName("G11 Redis 渠道状态：原子写入与广播")
class RedisChannelStateStoreTest {

    private static final String KEY_PREFIX = "test:channel-state";

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static {
        try {
            REDIS.start();
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "G11 的 Redis 测试需要一个真实 Redis（Testcontainers 起 redis:7）。"
                            + "本机 Docker 需可用；原始错误见下方 caused by。", t);
        }
    }

    private LettuceConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private RedisChannelStateStore store;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
        // 每个用例一个独立前缀，避免用例之间互相污染
        String prefix = KEY_PREFIX + ":" + System.nanoTime();
        store = new RedisChannelStateStore(redis,
                new ChannelStateProperties("redis", prefix, 1000));
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("写入后快照能读回，版本号从 0 递增")
    void writeThenSnapshot() {
        assertThat(store.setEnabled("deepseek", false)).isTrue();   // 之前不存在 → 默认启用
        assertThat(store.setEnabled("moonshot", false)).isTrue();

        ChannelStateStore.Snapshot snapshot = store.loadSnapshot();

        assertThat(snapshot.channels())
                .containsEntry("deepseek", false)
                .containsEntry("moonshot", false)
                .doesNotContainKey("__version__");
        assertThat(snapshot.version()).isEqualTo(2);

        // 第二次改同一个渠道：前值必须是 false，而不是默认的 true
        assertThat(store.setEnabled("deepseek", true)).isFalse();
        assertThat(store.loadSnapshot().channels()).containsEntry("deepseek", true);
    }

    @Test
    @DisplayName("变更会通过 Pub/Sub 广播，且带正确版本号")
    void changeIsPublished() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CompletableFuture<ChannelStateStore.StateChange> received = store.changes()
                .doOnSubscribe(subscription -> subscribed.countDown())
                .next()
                .toFuture();

        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
        // listenTo 的 SUBSCRIBE 命令在 doOnSubscribe 之后异步发出；
        // 给它一个很短的窗口，避免"订阅还没建立就发布"的假失败。
        Thread.sleep(300);

        store.setEnabled("deepseek", false);

        ChannelStateStore.StateChange change = received.get(5, TimeUnit.SECONDS);
        assertThat(change.channel()).isEqualTo("deepseek");
        assertThat(change.enabled()).isFalse();
        assertThat(change.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("Redis 不可用时写入抛 ChannelStateUnavailableException（对外 503）")
    void unavailableRedisFailsWrite() {
        factory.destroy();   // 主动断开
        factory = null;      // tearDown 不要再 destroy 一次

        assertThatThrownBy(() -> store.setEnabled("deepseek", false))
                .isInstanceOf(io.github.maixiaowen.gateway.routing.ChannelStateUnavailableException.class);
    }
}
