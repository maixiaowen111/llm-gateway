package com.example.gateway;

import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.provider.DeepSeekProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.util.function.Tuple2;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2-2a 的验收物：<b>上游的流，真的是一块一块到的。</b>
 *
 * <p><b>这个测试在防什么</b>：最容易犯的错是"以为返回 {@code Flux} 就叫流式了"。
 * 如果代码里某个地方把整段攒齐再发（比如中间加了个 {@code collectList()}、
 * 或者对响应做了缓冲），返回类型<b>照样是 Flux</b>，编译能过、肉眼也看不出来，
 * 但用户那边的"打字机效果"就没了 —— 还是等 5 秒然后一次全出来。
 *
 * <p>所以这里不只断言"拿到了 4 条内容"，还断言<b>"第 1 条和第 4 条之间隔了一段时间"</b>。
 * 后者才是"流"的证据。
 */
@SpringBootTest
@DisplayName("G2-2a 上游流式连通")
class UpstreamStreamingTest {

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    /**
     * 4 个 SSE 事件。
     *
     * <p><b>为什么每行都要凑成一样长</b>：WireMock 的 {@code withChunkedDribbleDelay}
     * 是按<b>字节等分</b>切响应体的。不等长的话，某个块会正好切在事件中间，
     * 解码器要等下一块到达才敢吐事件 —— 到达时刻就乱了，断言会飘。
     * 这里每个 {@code data: {"n":X}\n\n} 恰好 15 字节，4 块跟 4 个事件严格对齐。
     */
    private static final String SSE_BODY =
            "data: {\"n\":1}\n\n"
                    + "data: {\"n\":2}\n\n"
                    + "data: {\"n\":3}\n\n"
                    + "data: {\"n\":4}\n\n";

    private static final int EVENT_COUNT = 4;
    private static final int TOTAL_DRIBBLE_MS = 1200;

    @DynamicPropertySource
    static void pointAtFakeUpstream(DynamicPropertyRegistry registry) {
        registry.add("deepseek.base-url", () -> "http://localhost:" + UPSTREAM.port());
        registry.add("deepseek.api-key", () -> "test-key-not-a-real-secret");
    }

    /** G3-3a：类型从 DeepSeekClient 改成了 DeepSeekProvider，断言一个没动 */
    @Autowired
    private DeepSeekProvider client;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @Test
    @DisplayName("分块必须陆续到达，而不是攒齐了一次性给")
    void chunksArriveProgressively() {
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(SSE_BODY)
                        // 把响应体切成 4 份、在 1200ms 内陆续吐完 —— 模拟上游"边生成边发"
                        .withChunkedDribbleDelay(EVENT_COUNT, TOTAL_DRIBBLE_MS)));

        // G3-3a：方法名加了 Raw 后缀 —— 因为 chatCompletionStream 这个名字
        // 让给了 Provider 接口（语义是"吐归一后的块"）。这里要的仍是上游原始行。
        List<Tuple2<Long, String>> stamped = client.chatCompletionStreamRaw(
                        new ChatCompletionRequest(
                                null,
                                List.of(new ChatCompletionRequest.Message("user", "你好")),
                                true))
                // timestamp() 给每个元素盖上"到达时刻"的戳。
                // 没有它就只能断言"内容对"，断言不出"是不是流式"
                .timestamp()
                .collectList()
                .block();

        assertThat(stamped)
                .as("4 个 SSE 事件应解出 4 个元素")
                .hasSize(EVENT_COUNT);

        assertThat(stamped.get(0).getT2())
                .as("SSE 解码器应剥掉 'data: ' 前缀，只留 JSON")
                .isEqualTo("{\"n\":1}");

        long spreadMs = stamped.get(EVENT_COUNT - 1).getT1() - stamped.get(0).getT1();

        // 这条断言才是 2a 的核心。
        // 只断言"拿到 4 条"是不够的：攒齐再发也满足 hasSize(4)，但流式已经死了。
        assertThat(spreadMs)
                .as("首尾元素应相隔一段时间（实测滴流 %d ms），说明是真的陆续到达", TOTAL_DRIBBLE_MS)
                .isGreaterThan(TOTAL_DRIBBLE_MS / 3);
    }
}
