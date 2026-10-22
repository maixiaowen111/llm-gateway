package io.github.maixiaowen.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.maixiaowen.gateway.service.GatewayChatService;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G13 的验收物：traceparent 真的从客户端传到了上游，而不是只在自己的日志里印了个 ID。</b>
 *
 * <p><b>这个测试在防什么</b>：
 * <ol>
 *   <li><b>"假追踪"</b> —— 网关自己的 server span 有 traceId，但调用上游时没带 {@code traceparent}。
 *       看板上网关这一段是完整的，上游那一段却凭空出现，<b>链路在最关键的地方断了</b>；</li>
 *   <li><b>上下文丢失</b> —— WebFlux 是异步的，如果 span 只存在于 ThreadLocal 而没随 Reactor
 *       Context 传播，回调线程上就是空的。表现是"有时有、有时没有"的标签和断掉的父子关系；</li>
 *   <li><b>标签没打上</b> —— 代码里写了 {@code setAttribute}，但 span 根本没在录制，
 *       或者写的不是当前请求的 span。这里从 SDK 导出的 {@link SpanData} 里把它们读回来。</li>
 * </ol>
 *
 * <p><b>★ 为什么断言"同一个 traceId"而不是"有 traceparent" ★</b>
 * <p>每次请求都会生成一个新的 traceparent，所以只要发出去就"有"。
 * 但只有<b>traceId 与客户端传入的一致</b>，才能证明网关是"接上了这条链路"，
 * 而不是"另起了一条链路"。这也是分布式追踪唯一有价值的性质。
 *
 * <p><b>⚠️ @AutoConfigureObservability 不能省</b>：Boot 在测试上下文里默认设置
 * {@code management.tracing.enabled=false}（防止测试往真实 collector 推数据），
 * 不打开的话 sampler 是 NeverSample，Span.current() 永远拿到 invalid span，
 * 测试会以"什么都没发生"的方式失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(TracingPropagationTest.SpanCollectorConfig.class)
@DisplayName("G13 端到端：traceparent 透传 + span 标签")
class TracingPropagationTest {

    /** W3C traceparent 里的 32 位十六进制 trace id，取自规范示例 */
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT =
            "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.default-channel", () -> "deepseek");
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key");
        // OTLP 导出指向 WireMock：让 BatchSpanProcessor 有地方可发，
        // 避免测试结束刷一堆 "connection refused" 的噪音（追踪失败不影响业务，但会淹没真实错误）
        registry.add("management.otlp.tracing.endpoint",
                () -> "http://localhost:" + UPSTREAM.port() + "/v1/traces");
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        SPANS.clear();
        serviceLogger = (Logger) LoggerFactory.getLogger(GatewayChatService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        UPSTREAM.resetAll();
        UPSTREAM.stubFor(post(urlEqualTo("/v1/traces"))
                .willReturn(aResponse().withStatus(200)));
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"c1","object":"chat.completion","created":1,"model":"upstream-alias",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                                 "finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                                """)));
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @AfterEach
    void detachAppender() {
        if (serviceLogger != null && logAppender != null) {
            serviceLogger.detachAppender(logAppender);
        }
    }

    @AfterAll
    static void tearDown() {
        UPSTREAM.stop();
    }

    @Test
    @DisplayName("★ 客户端传来的 traceId 被上游收到；网关 span 带上渠道 / 客户端 / 模型标签")
    void traceparentIsPropagatedAndSpanIsTagged() {
        client.post().uri("/v1/chat/completions")
                .header("traceparent", TRACEPARENT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body())
                .exchange()
                .expectStatus().isOk();

        // ---- ① 出站请求：同一个 traceId ----
        UPSTREAM.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("traceparent", containing(TRACE_ID)));

        // ---- ② SDK 里确实有一条带业务标签的 span ----
        // server span 的结束发生在响应交付之后，给它一个短暂的收敛窗口；
        // 不用 Thread.sleep 死等，而是轮询到条件成立或超时。
        SpanData tagged = awaitSpanWithAttribute("gateway.channel", "deepseek");
        assertThat(tagged)
                .as("已收集的 span：%s", SPANS.stream()
                        .map(span -> span.getName() + " " + span.getAttributes())
                        .toList())
                .isNotNull();
        assertThat(tagged.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(tagged.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("gateway.client.id")))
                .isNotBlank();
        assertThat(tagged.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("gateway.request.model")))
                .isEqualTo("deepseek-chat");
        assertThat(tagged.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.longKey("gateway.usage.total_tokens")))
                .isEqualTo(5L);

        // ---- ③ 业务线程上的日志，MDC 里必须有同一个 traceId ----
        // 这一条验的是 TracingConfig 注册的 MDC 访问器是否生效。
        // ★ 不能只断言"日志里有 traceId"：span 的 traceId 是链路事实，
        //   日志里的 traceId 是"人能不能看到链路"；两者失败的修法完全不同。★
        ILoggingEvent successLog = awaitLog("调用成功");
        assertThat(successLog).isNotNull();
        assertThat(successLog.getMDCPropertyMap())
                .containsEntry("traceId", TRACE_ID);
        assertThat(successLog.getMDCPropertyMap().get("spanId")).isNotBlank();

        // "收到请求"曾经在 Controller 里打，MDC 是空的 —— 同一请求的日志被切成两截。
        // 现在它挪到了服务层的订阅回调里，所以必须和"调用成功"带同一个 traceId。
        ILoggingEvent receiptLog = awaitLog("收到请求");
        assertThat(receiptLog).isNotNull();
        assertThat(receiptLog.getMDCPropertyMap())
                .containsEntry("traceId", TRACE_ID);
    }

    private ILoggingEvent awaitLog(String keyword) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (ILoggingEvent event : logAppender.list) {
                if (event.getFormattedMessage().contains(keyword)) {
                    return event;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private SpanData awaitSpanWithAttribute(String key, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (SpanData span : SPANS) {
                String actual = span.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(key));
                if (expected.equals(actual)) {
                    return span;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> body() {
        return Map.of(
                "model", "deepseek-chat",
                "messages", List.of(Map.of("role", "user", "content", "hello")));
    }

    // ==================================================================
    // 测试夹具：把结束的 span 收进内存，供断言读取
    // ==================================================================

    private static final List<SpanData> SPANS = new CopyOnWriteArrayList<>();

    /**
     * 注册一个同步 {@link SpanProcessor}。
     *
     * <p>为什么用 SpanProcessor 而不是自定义 SpanExporter：
     * <ul>
     *   <li>Processor 在 span <b>结束的那一刻</b>同步回调，测试不用等批量导出周期（默认 5 秒）；</li>
     *   <li>Exporter 需要和 Boot 自动配置的 OTLP exporter 共存，还要处理批量失败，
     *       而本测试只关心"span 长什么样"，不关心"怎么发出去"。</li>
     * </ul>
     *
     * <p>SpanData 必须在 onEnd 里 {@code toSpanData()} 拷贝一份：
     * {@link ReadableSpan} 在回调返回后可能被 SDK 复用/释放。
     */
    @TestConfiguration
    static class SpanCollectorConfig {

        @Bean
        SpanProcessor collectingSpanProcessor() {
            return new SpanProcessor() {
                @Override
                public void onStart(Context parentContext, ReadWriteSpan span) {
                    // 本测试只需要结束态
                }

                @Override
                public boolean isStartRequired() {
                    return false;
                }

                @Override
                public void onEnd(ReadableSpan span) {
                    Attributes attributes = span.toSpanData().getAttributes();
                    if (!attributes.isEmpty()) {
                        SPANS.add(span.toSpanData());
                    }
                }

                @Override
                public boolean isEndRequired() {
                    return true;
                }
            };
        }
    }
}
