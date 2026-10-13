package com.example.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.gateway.provider.DeepSeekProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G2 段末的验收物：客户端断开时，上游请求确实被取消。</b>
 *
 * <p><b>这个测试在防什么</b>：G2 前半段已经证明"流能通、也归一了"。
 * 但如果取消传不下去，网关就是个"漏水的管子"——
 * 用户关了浏览器，上游还在吭哧吭哧吐字，<b>每一个字都在烧钱、占着一条连接</b>。
 * 单看功能测试（"能拿到回复"）永远发现不了这个，
 * 它只有在"用户中途关掉"这一种情况下才暴露，而线上这种情况非常常见。
 *
 * <p><b>三级证据链（这是这个测试的骨架）</b>
 * <ol>
 *   <li><b>先证明流真的在动</b> —— 必须真的收到 ≥3 块才断开。
 *       否则"取消成功"可能只是因为压根没东西可取消，测试等于没测。</li>
 *   <li><b>断开的方式必须是真断</b> —— 用 {@code Disposable.dispose()} 取消订阅，
 *       Reactor Netty 随即关闭与服务端的 TCP 连接。不是"假装断"。</li>
 *   <li><b>取消必须传到上游</b> —— 断言 {@link DeepSeekClient} 的日志里出现 {@code [cancel]}。
 *       那个观测点刻意放在<b>最贴近网线</b>的位置（见 client 里的注释），
 *       它能收到，就说明信号已经穿过 filter / concatMap / timeout 一路冒到了 HTTP 客户端边界。</li>
 * </ol>
 *
 * <p><b>为什么用日志而不是给生产代码加一个"取消计数器"</b>：
 * 为了测试往生产类里塞一个静态字段或额外 Bean，是<b>把测试的私货写进产品</b>——
 * 上线后它还在那里，没人敢删。而这条 {@code [cancel]} 日志<b>本身就是生产需要的</b>
 * （网关必须知道有多少用户中途放弃），测试只是顺手复用了它。
 * <b>让生产代码承担测试职责，而不是让测试污染生产代码。</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("G2-2c 取消传播")
class CancelPropagationTest {

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    private static final String REQUESTED_MODEL = "deepseek-chat";

    /**
     * 上游吐 20 块、在 8 秒内陆续吐完 —— 每块间隔约 400ms。
     * <p>
     * <b>为什么必须"吐得够慢"</b>：如果上游一瞬间就吐完了，我们还没等到 3 块，
     * 整条流就已经正常结束了 —— 那时再 dispose，流已经完成，取消无从谈起。
     * 要验取消，就得让上游<b>一直活着</b>。
     * <p>
     * <b>为什么每块字节数必须完全相同</b>：WireMock 的 {@code withChunkedDribbleDelay}
     * 按<b>字节等分</b>切响应体。长度不一时某个块会正好切在事件中间，
     * SSE 解码器要等下一块才敢吐事件，到达节奏就乱了。
     */
    private static final int UPSTREAM_CHUNK_COUNT = 20;
    private static final int TOTAL_DRIBBLE_MS = 8000;

    private static final String UPSTREAM_SSE = buildUpstreamSse();

    private static String buildUpstreamSse() {
        // 每块内容固定（全是 ASCII），保证 20 块字节数严格相等
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < UPSTREAM_CHUNK_COUNT; i++) {
            sb.append("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,")
                    .append("\"model\":\"").append(REQUESTED_MODEL).append("\",\"choices\":[{\"index\":0,")
                    .append("\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n");
        }
        return sb.toString();
    }

    @DynamicPropertySource
    static void pointAtFakeUpstream(DynamicPropertyRegistry registry) {
        registry.add("deepseek.base-url", () -> "http://localhost:" + UPSTREAM.port());
        registry.add("deepseek.api-key", () -> "test-key-not-a-real-secret");
    }

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .build();
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @Test
    @DisplayName("客户端断开 → 取消信号确实传到上游（不是只断了自己这头）")
    void clientDisconnectPropagatesCancelToUpstream() throws Exception {
        stubDribblingUpstream();
        ListAppender<ILoggingEvent> appender = attachToClientLogger();

        Flux<String> responseBody = openStream();

        // ① 先确认流真的在动
        CountDownLatch receivedThree = new CountDownLatch(3);
        Disposable subscription = responseBody.subscribe(chunk -> receivedThree.countDown());

        assertThat(receivedThree.await(15, TimeUnit.SECONDS))
                .as("取消之前必须真的收到至少 3 块，否则'取消成功'什么都证明不了")
                .isTrue();

        // ② 真的断开（不是假装断）—— 取消订阅，底层 TCP 连接随之关闭
        subscription.dispose();

        // ③ 取消必须一路传到上游那条连接上
        assertThat(awaitLogMarker(appender, "[cancel]", 15))
                .as("网关必须观测到取消信号传到了上游边界 —— 日志里应出现 [cancel]。"
                        + "没有它，说明上游还在继续吐字烧钱")
                .isTrue();
    }

    @Test
    @DisplayName("流正常读完时不得误报取消 —— CANCEL 和 COMPLETE 必须分得开")
    void normalCompletionIsNotReportedAsCancel() throws Exception {
        // 上游 300ms 内痛快吐完，客户端老实读到底
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(UPSTREAM_SSE)
                        .withChunkedDribbleDelay(UPSTREAM_CHUNK_COUNT, 300)));

        ListAppender<ILoggingEvent> appender = attachToClientLogger();

        List<String> events = openStream().collectList().block();

        assertThat(events)
                .as("20 个数据块 + 网关补的 [DONE]")
                .hasSize(UPSTREAM_CHUNK_COUNT + 1);

        // 这条是"负向"断言，比上一条更重要：
        // 如果代码里无脑把每次结束都记成取消，上一条测试照样会绿，但日志就彻底失去意义了。
        assertThat(hasLogMarker(appender, "[cancel]"))
                .as("正常读完的流绝不能出现取消日志 —— 否则统计出来的'用户中途放弃率'全是假的")
                .isFalse();
        assertThat(hasLogMarker(appender, "[stream-end]"))
                .as("正常结束应当被记成 [stream-end]")
                .isTrue();
    }

    /** 发一个流式请求，把 SSE 响应体作为 Flux 拿回来（还没订阅） */
    private Flux<String> openStream() {
        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", REQUESTED_MODEL,
                        "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "你好"))))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        return result.getResponseBody();
    }

    private void stubDribblingUpstream() {
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(UPSTREAM_SSE)
                        .withChunkedDribbleDelay(UPSTREAM_CHUNK_COUNT, TOTAL_DRIBBLE_MS)));
    }

    /**
     * 给 {@link DeepSeekClient} 的 logger 挂一个内存 appender。
     * <p>
     * 这是"观测生产代码在运行时到底做了什么"的标准手法 ——
     * 不用为了测试去改生产代码的接口。
     */
    private ListAppender<ILoggingEvent> attachToClientLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger(DeepSeekProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private boolean hasLogMarker(ListAppender<ILoggingEvent> appender, String marker) {
        return appender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains(marker));
    }

    /**
     * 轮询等待某条日志出现。
     * <p>
     * 不能"断开后立刻断言" —— 取消信号要经过 Netty 的 channelInactive、
     * Reactor 的算子链才落到日志里，是<b>异步</b>的。用轮询而不是 sleep 固定时长，
     * 既不会偶发失败，也不会白白拖慢测试。
     */
    private boolean awaitLogMarker(ListAppender<ILoggingEvent> appender, String marker, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (hasLogMarker(appender, marker)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
