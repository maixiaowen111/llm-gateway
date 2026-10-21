package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.billing.BillingService;
import io.github.maixiaowen.gateway.config.ProviderProperties;
import io.github.maixiaowen.gateway.observability.GatewayMetrics;
import io.github.maixiaowen.gateway.dto.ChatCompletionChunk;
import io.github.maixiaowen.gateway.dto.ChatCompletionRequest;
import io.github.maixiaowen.gateway.dto.ChatCompletionResponse;
import io.github.maixiaowen.gateway.provider.Provider;
import io.github.maixiaowen.gateway.provider.ProviderRegistry;
import io.github.maixiaowen.gateway.resilience.ChannelCircuitBreakers;
import io.github.maixiaowen.gateway.resilience.ResilienceProperties;
import io.github.maixiaowen.gateway.resilience.UpstreamErrorKind;
import io.github.maixiaowen.gateway.resilience.UpstreamFailure;
import io.github.maixiaowen.gateway.routing.ChannelRouter;
import io.github.maixiaowen.gateway.routing.ChannelStateRegistry;
import io.github.maixiaowen.gateway.service.GatewayChatService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G5 的验收物：重试 / 故障转移 / 首字节边界 / 熔断绕行。</b>
 *
 * <p><b>为什么这些测试不用 Spring、不用 WireMock</b>：
 * 本类验的是<b>编排规则</b>（什么情况下试谁、什么时候不许试），
 * 不是 HTTP 细节。用假 Provider 可以让"第几次调用返回什么"完全可控 ——
 * 而 WireMock 要靠 stub 顺序 + 场景状态，写起来啰嗦、读起来也难看出意图。
 * <p><b>规则类代码用规则类测试；协议类代码用协议类测试。</b>
 *
 * <p><b>★ 最值钱的是 {@code streamMustNotFailoverAfterFirstByte} ★</b>
 * <p>它锁住的是一条<b>不会报错、只会坑用户</b>的规则：流式响应吐过字之后换渠道，
 * 用户会看到重复内容，并且两家的输出都会被计费。这种 bug 在人工测试里几乎发现不了
 * （要刚好制造"吐了一半就断"的时机），只有代码级的边界测试能守住。
 */
@DisplayName("G5：故障转移与熔断编排")
class GatewayChatServiceTest {

    // ------------------------------------------------------------------ 场景：非流式

    @Test
    @DisplayName("主渠道 5xx → 转移到同组备份，用户无感")
    void upstream5xxFailsOverToBackup() {
        ScriptedProvider primary = new ScriptedProvider("primary",
                req -> Mono.error(httpError(500)),
                req -> Flux.error(httpError(500)));
        ScriptedProvider backup = new ScriptedProvider("backup",
                req -> Mono.just(response("BACKUP")),
                req -> Flux.just(chunk("BACKUP")));

        GatewayChatService service = newService(resilience(false, 0), primary, backup);

        ChatCompletionResponse response = service.complete("test-client", request(null)).block(Duration.ofSeconds(2));

        assertThat(response).isNotNull();
        assertThat(response.choices().get(0).message().content()).isEqualTo("BACKUP");
        assertThat(primary.monoCalls()).isEqualTo(1);
        assertThat(backup.monoCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("上游 400 → 直接透传，绝不换渠道（换一百家也不会成功，只会多花三份钱）")
    void clientErrorDoesNotFailOver() {
        ScriptedProvider primary = new ScriptedProvider("primary",
                req -> Mono.error(httpError(400)),
                req -> Flux.error(httpError(400)));
        ScriptedProvider backup = new ScriptedProvider("backup",
                req -> Mono.just(response("SHOULD-NOT-BE-CALLED")),
                req -> Flux.just(chunk("SHOULD-NOT-BE-CALLED")));

        GatewayChatService service = newService(resilience(false, 0), primary, backup);

        StepVerifier.create(service.complete("test-client", request(null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(UpstreamFailure.class);
                    UpstreamFailure failure = (UpstreamFailure) error;
                    assertThat(failure.kind()).isEqualTo(UpstreamErrorKind.CLIENT_ERROR);
                    assertThat(failure.clientStatus().value()).isEqualTo(400);
                })
                .verify(Duration.ofSeconds(2));

        assertThat(backup.monoCalls()).as("400 不允许转移").isZero();
    }

    @Test
    @DisplayName("同渠道可重试错误：先退避重试，第二次成功就不转移")
    void retryableErrorRetriesSameChannelFirst() {
        // 第一次 500，第二次成功 —— 用下标控制假上游的行为
        AtomicInteger attempt = new AtomicInteger();
        ScriptedProvider primary = new ScriptedProvider("primary",
                req -> attempt.incrementAndGet() == 1
                        ? Mono.error(httpError(503))
                        : Mono.just(response("OK-AFTER-RETRY")),
                req -> Flux.error(httpError(503)));
        ScriptedProvider backup = new ScriptedProvider("backup",
                req -> Mono.just(response("BACKUP")),
                req -> Flux.just(chunk("BACKUP")));

        GatewayChatService service = newService(resilience(true, 1), primary, backup);

        ChatCompletionResponse response = service.complete("test-client", request(null)).block(Duration.ofSeconds(3));

        assertThat(response).isNotNull();
        assertThat(response.choices().get(0).message().content()).isEqualTo("OK-AFTER-RETRY");
        assertThat(primary.monoCalls()).isEqualTo(2);
        assertThat(backup.monoCalls()).as("重试成功就不该转移").isZero();
    }

    // ------------------------------------------------------------------ 场景：流式

    @Test
    @DisplayName("★ 首字节之后出错：绝不转移、绝不重试，只把错误如实传出去 ★")
    void streamMustNotFailoverAfterFirstByte() {
        ScriptedProvider primary = new ScriptedProvider("primary",
                req -> Mono.just(response("X")),
                req -> Flux.concat(Flux.just(chunk("前半句")), Flux.error(httpError(500))));
        ScriptedProvider backup = new ScriptedProvider("backup",
                req -> Mono.just(response("BACKUP")),
                req -> Flux.just(chunk("BACKUP-从头的重复内容")));

        GatewayChatService service = newService(resilience(false, 0), primary, backup);

        List<String> received = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.stream("test-client", request(null))
                .doOnNext(c -> received.add(c.choices().get(0).delta().content()))
                .onErrorResume(e -> {
                    error.set(e);
                    return Flux.empty();
                })
                .blockLast(Duration.ofSeconds(3));

        // 用户只看到前半句，然后流出错终止 —— 这是"正确的失败"
        assertThat(received).containsExactly("前半句");
        assertThat(error.get()).isInstanceOf(UpstreamFailure.class);
        assertThat(((UpstreamFailure) error.get()).afterFirstByte()).isTrue();
        assertThat(backup.fluxCalls())
                .as("★ 核心断言：已经吐过字，备份渠道一次都不许碰 ★")
                .isZero();
    }

    @Test
    @DisplayName("首字节之前出错：可以安全转移到备份，用户什么都没看到")
    void streamFailsOverBeforeFirstByte() {
        ScriptedProvider primary = new ScriptedProvider("primary",
                req -> Mono.error(httpError(500)),
                req -> Flux.error(httpError(500)));
        ScriptedProvider backup = new ScriptedProvider("backup",
                req -> Mono.just(response("X")),
                req -> Flux.just(chunk("备份渠道的回答")));

        GatewayChatService service = newService(resilience(false, 0), primary, backup);

        List<String> received = service.stream("test-client", request(null))
                .map(c -> c.choices().get(0).delta().content())
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(received).containsExactly("备份渠道的回答");
        assertThat(primary.fluxCalls()).isEqualTo(1);
        assertThat(backup.fluxCalls()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 场景：熔断

    @Test
    @DisplayName("连续失败把熔断器打开后：路由直接跳过该渠道，不再浪费一次超时")
    void openCircuitIsSkippedByRouter() {
        ScriptedProvider primary = new ScriptedProvider("primary",
                req -> Mono.error(httpError(500)),
                req -> Flux.error(httpError(500)));
        ScriptedProvider backup = new ScriptedProvider("backup",
                req -> Mono.just(response("BACKUP")),
                req -> Flux.just(chunk("BACKUP")));

        // 窗口 2 次、至少 2 次、失败率 50% → 两次全错必然打开
        GatewayChatService service = newService(resilience(true, 0), primary, backup);

        service.complete("test-client", request(null)).block(Duration.ofSeconds(2));
        service.complete("test-client", request(null)).block(Duration.ofSeconds(2));

        CircuitBreaker breaker = circuitBreakers.get("primary");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 第三次：路由器在候选阶段就把 primary 过滤掉了，连一次网络尝试都不该有
        ChatCompletionResponse third = service.complete("test-client", request(null)).block(Duration.ofSeconds(2));
        assertThat(third).isNotNull();
        assertThat(primary.monoCalls())
                .as("熔断打开后不应再打这条渠道")
                .isEqualTo(2);
        assertThat(backup.monoCalls()).isEqualTo(3);
    }

    // ==================================================================
    // 测试夹具
    // ==================================================================

    private ChannelCircuitBreakers circuitBreakers;

    /**
     * 组装一套"主渠道 + 备份渠道"的完整链路。
     *
     * <p>注意这里<b>没有 Spring</b>：注册表、状态、熔断器、路由器、服务全是 new 出来的。
     * 这恰好证明了一件事 —— <b>这些类的依赖都是显式构造参数，没有偷偷依赖容器</b>，
     * 所以它们能被独立测试。这是当初选择"不用 @Component、在配置类里手工 new"的回报。
     */
    private GatewayChatService newService(ResilienceProperties resilience, Provider... providers) {
        Map<String, ProviderProperties.Channel> channels = new LinkedHashMap<>();
        for (Provider provider : providers) {
            channels.put(provider.name(), new ProviderProperties.Channel(
                    "openai", "http://unused", "key", "model",
                    1, true, List.of(), ProviderProperties.DEFAULT_GROUP, null, true));
        }

        ProviderProperties properties = new ProviderProperties("primary", channels,
                new ProviderProperties.Routing(false, true, true, "smooth-weighted"));

        ProviderRegistry registry = new ProviderRegistry(List.of(providers), "primary");
        ChannelStateRegistry states = new ChannelStateRegistry(properties);
        circuitBreakers = new ChannelCircuitBreakers(resilience, registry.names(), new SimpleMeterRegistry());
        ChannelRouter router = new ChannelRouter(registry, properties, states, circuitBreakers);

        // 计费在编排测试里不参与：disabled() 的所有方法都在碰 Redis 之前返回 no-op。
        // 指标用 SimpleMeterRegistry：它只把数字存在内存里，不启动任何 exporter/线程。
        return new GatewayChatService(router, circuitBreakers, resilience,
                BillingService.disabled(), new GatewayMetrics(new SimpleMeterRegistry()));
    }

    /** 默认配置：熔断窗口 2 次、至少 2 次、失败率 50%；重试次数由参数控制 */
    private ResilienceProperties resilience(boolean enabled, int retryAttempts) {
        return new ResilienceProperties(
                enabled,
                50,
                2,
                2,
                10_000L,
                1,
                retryAttempts,
                10L);
    }

    private ChatCompletionRequest request(String provider) {
        return new ChatCompletionRequest(
                null,
                List.of(new ChatCompletionRequest.Message("user", "hi")),
                false,
                provider);
    }

    private WebClientResponseException httpError(int status) {
        return WebClientResponseException.create(status,
                "stub-" + status,
                HttpHeaders.EMPTY,
                ("{\"error\":\"stub " + status + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private ChatCompletionResponse response(String content) {
        return new ChatCompletionResponse(
                "resp-1", "chat.completion", 1_700_000_000L, "model",
                List.of(new ChatCompletionResponse.Choice(0,
                        new ChatCompletionResponse.Message("assistant", content), "stop")),
                new ChatCompletionResponse.Usage(1, 1, 2));
    }

    private ChatCompletionChunk chunk(String content) {
        return new ChatCompletionChunk(
                "chunk-1", ChatCompletionChunk.OBJECT, 1_700_000_000L, "model",
                List.of(new ChatCompletionChunk.Choice(0,
                        new ChatCompletionChunk.Delta(null, content), null)));
    }

    /**
     * 可编程的假渠道：行为由两个函数决定，调用次数单独计数。
     *
     * <p>计数用 {@link AtomicInteger} 而不是 {@code int}：这些测试跑在 Reactor 的
     * Netty 线程上，断言读值发生在主线程 —— <b>两个线程之间共享的变量必须有可见性保证</b>。
     * 这是测试代码里也躲不掉的并发常识。
     */
    private static final class ScriptedProvider implements Provider {

        private final String name;
        private final Function<ChatCompletionRequest, Mono<ChatCompletionResponse>> monoBehavior;
        private final Function<ChatCompletionRequest, Flux<ChatCompletionChunk>> fluxBehavior;
        private final AtomicInteger monoCalls = new AtomicInteger();
        private final AtomicInteger fluxCalls = new AtomicInteger();

        private ScriptedProvider(String name,
                                 Function<ChatCompletionRequest, Mono<ChatCompletionResponse>> monoBehavior,
                                 Function<ChatCompletionRequest, Flux<ChatCompletionChunk>> fluxBehavior) {
            this.name = name;
            this.monoBehavior = monoBehavior;
            this.fluxBehavior = fluxBehavior;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
            monoCalls.incrementAndGet();
            // defer：让"第几次调用返回什么"在订阅时才求值，重试才能拿到新行为
            return Mono.defer(() -> monoBehavior.apply(request));
        }

        @Override
        public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
            fluxCalls.incrementAndGet();
            return Flux.defer(() -> fluxBehavior.apply(request));
        }

        int monoCalls() {
            return monoCalls.get();
        }

        int fluxCalls() {
            return fluxCalls.get();
        }
    }
}
