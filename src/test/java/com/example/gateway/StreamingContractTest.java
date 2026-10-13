package com.example.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2-2b 的验收物：<b>流式出口也是归一的，不是把上游的块透传出去。</b>
 *
 * <p><b>和 2a 那个测试的关系</b>：{@code UpstreamStreamingTest} 证明的是
 * "上游的块真的是一块块到的"（流没被攒齐）；这个测试证明的是
 * <b>"到客户端手里的块已经换过一身衣服"</b>。两件事必须分开验 ——
 * 流是真的、但透传了厂商协议，等于白干。
 *
 * <p><b>假上游故意埋的两个坑</b>（只要有一个漏出去，测试就红）：
 * <ol>
 *   <li>{@code model} 报 {@code deepseek-flash} —— 而客户端请求的是 {@code deepseek-chat}。
 *       透传的话客户端会看到一个自己从没请求过的模型名。</li>
 *   <li>{@code object} 报一个上游自造的 {@code upstream.weird} —— 归一后必须是
 *       OpenAI 协议的 {@code chat.completion.chunk}，否则 SDK 认不出这是流式块。</li>
 * </ol>
 *
 * <p><b>为什么走真实 HTTP（WebTestClient）而不是直接调 client</b>：
 * 2a 只验到"client 拿到了流"。2b 的新东西全在<b>出口</b>——
 * {@code Content-Type} 是不是 {@code text/event-stream}、每个元素有没有被包成
 * {@code data:}、流末尾有没有 {@code [DONE]}。这些只有走一遍真 HTTP 才验得到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("G2-2b 流式出口契约")
class StreamingContractTest {

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    /** 客户端请求的模型名 —— 出口必须报这个，不管上游报什么 */
    private static final String REQUESTED_MODEL = "deepseek-chat";
    /** 上游实际"偷偷用的"模型名，是被埋的坑 */
    private static final String UPSTREAM_MODEL = "deepseek-flash";
    /** 上游自造的 object 值，也是坑 */
    private static final String UPSTREAM_OBJECT = "upstream.weird";

    private static final String SSE_PREFIX =
            "data: {\"id\":\"chatcmpl-1\",\"object\":\"" + UPSTREAM_OBJECT + "\",\"created\":1700000000,"
                    + "\"model\":\"" + UPSTREAM_MODEL + "\",\"choices\":[{\"index\":0,";

    /**
     * 上游吐 3 个块 + 一个 [DONE]。
     * 第 1 块带 role（OpenAI 协议里首块的 delta 有 role），第 2 块只有 content，第 3 块带 finish_reason。
     */
    private static final String UPSTREAM_SSE =
            SSE_PREFIX + "\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                    + SSE_PREFIX + "\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
                    + SSE_PREFIX + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";

    /** 出口应有的块数：3 个数据块 + 1 个 [DONE] */
    private static final int EXPECTED_EVENT_COUNT = 4;

    @DynamicPropertySource
    static void pointAtFakeUpstream(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.channels.deepseek.base-url", () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key-not-a-real-secret");
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private ObjectMapper objectMapper;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
        // 直连本机随机端口，不经过任何 client 封装 —— 验的就是"网线上真正跑的东西"
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .build();
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    /** 打桩：假上游回一段 SSE，故意用错的 model / object */
    private void stubStreamingUpstream() {
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(UPSTREAM_SSE)));
    }

    /** 发一个流式请求，把出口的每个 SSE 事件（已剥掉 data: 前缀）收成一个列表 */
    private List<String> collectStreamEvents() {
        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", REQUESTED_MODEL,
                        "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "你好"))))
                .exchange()
                .expectStatus().isOk()
                // 出口必须是 SSE 的内容类型 —— 钉不住的话浏览器/SDK 会当成一坨 JSON 文本
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);

        List<String> events = result.getResponseBody().collectList().block();
        assertThat(events).isNotNull();
        return events;
    }

    @Test
    @DisplayName("出口每个块都是 chat.completion.chunk，model 报客户端请求的那个")
    void streamIsNormalizedToOpenAiContract() throws Exception {
        stubStreamingUpstream();

        List<String> events = collectStreamEvents();

        assertThat(events)
                .as("3 个数据块 + 1 个 [DONE]")
                .hasSize(EXPECTED_EVENT_COUNT);

        assertThat(events.get(EXPECTED_EVENT_COUNT - 1))
                .as("流末尾必须是 [DONE] 哨兵，否则 OpenAI SDK 会一直等下一个块")
                .isEqualTo("[DONE]");

        // 逐块检查前 3 个数据块
        for (int i = 0; i < EXPECTED_EVENT_COUNT - 1; i++) {
            JsonNode chunk = objectMapper.readTree(events.get(i));

            assertThat(chunk.path("object").asText())
                    .as("第 %d 块的 object 必须是 OpenAI 协议值，不能透传上游的 %s", i, UPSTREAM_OBJECT)
                    .isEqualTo("chat.completion.chunk");

            assertThat(chunk.path("model").asText())
                    .as("第 %d 块必须报客户端请求的模型，不是上游实际用的 %s", i, UPSTREAM_MODEL)
                    .isEqualTo(REQUESTED_MODEL);

            assertThat(chunk.path("choices").path(0).has("delta"))
                    .as("流式块的增量字段叫 delta，不是非流式的 message")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("增量内容逐块透出：role 只在首块、content 在中块、finish_reason 只在末块")
    void deltaFieldsAreCarriedThrough() throws Exception {
        stubStreamingUpstream();

        List<String> events = collectStreamEvents();

        JsonNode first = objectMapper.readTree(events.get(0));
        JsonNode middle = objectMapper.readTree(events.get(1));
        JsonNode last = objectMapper.readTree(events.get(2));

        // 首块带 role，且空 content 也要原样带出来（OpenAI 首块就是这样）
        assertThat(first.path("choices").path(0).path("delta").path("role").asText())
                .isEqualTo("assistant");

        // 中块的核心：增量内容必须一个字都不少
        assertThat(middle.path("choices").path(0).path("delta").path("content").asText())
                .as("这是用户真正看到的那几个字，丢了流就白流了")
                .isEqualTo("你好");

        // 末块带 finish_reason —— 客户端靠它判断"这轮说完了"
        assertThat(last.path("choices").path(0).path("finish_reason").asText())
                .isEqualTo("stop");

        assertThat(middle.path("choices").path(0).path("finish_reason").isNull())
                .as("中间块的 finish_reason 就是显式 null，不能凭空造一个值出来")
                .isTrue();
    }

    @Test
    @DisplayName("上游的 [DONE] 不原样透传，出口的 [DONE] 是网关自己补的")
    void doneSentinelIsNotForwardedFromUpstream() throws Exception {
        // 关键：让上游<b>不发</b> [DONE]，只发数据块。
        // 如果出口仍然以 [DONE] 收尾，就证明它是网关自己补的，而不是透传上游的。
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(SSE_PREFIX + "\"delta\":{\"content\":\"只有我\"},\"finish_reason\":null}]}\n\n")));

        List<String> events = collectStreamEvents();

        // 把出口内容拼起来看：必须恰好一个 [DONE]，且它是最后一行
        String joined = events.stream().collect(Collectors.joining("\n"));
        long doneCount = events.stream().filter("[DONE]"::equals).count();

        assertThat(doneCount)
                .as("上游没发 [DONE]，出口也应该有且只有一个 —— 说明是网关补的")
                .isEqualTo(1L);
        assertThat(events.get(events.size() - 1)).isEqualTo("[DONE]");
        assertThat(joined).contains("只有我");
    }
}
