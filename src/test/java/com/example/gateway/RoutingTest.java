package com.example.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G3b 的验收物：请求真的被路由到了不同的上游。</b>
 *
 * <p><b>它和 {@code ProviderRegistryTest} 的分工</b>：
 * 那边验的是"注册表这个纯逻辑类能不能正确解析渠道"（快，不起 Spring）；
 * 这边验的是<b>"配置 → Bean → 注册表 → Controller → 真正打出去的那条 HTTP 请求"
 * 这条完整链路是通的</b>。
 *
 * <p><b>为什么两个都要有 —— 这是本测试存在的核心理由</b>：
 * 一个纯逻辑测试全绿、而整个应用跑起来路由不生效，是完全可能的。
 * 只要在 {@code ProviderConfig} 里写错一行（比如所有渠道都用了同一个 builder 的
 * base-url、或者 map 遍历时对象被覆盖），注册表依然"正确"，
 * 而每个请求都打到同一个上游上。<b>只有端到端测试能发现这种错。</b>
 *
 * <p><b>★ 关键的测试设计：怎么知道请求打到了哪个上游 ★</b>
 * 起<b>两个</b> WireMock，各返回一句能区分身份的话（{@code from-deepseek} /
 * {@code from-moonshot}），然后<b>双向断言</b>：
 * <ol>
 *   <li>响应体里的内容是谁的 —— 证明"回来的数据来自哪"；</li>
 *   <li>两个假上游各自<b>收到了几个请求</b>（{@code verify(0, ...)} 也算断言）——
 *       证明"发出去的请求去了哪"。</li>
 * </ol>
 * 只断言第 1 条是不够的：如果网关把请求同时发给了两家（广播），
 * 响应体照样是"其中一家"的内容，测试会绿 —— 但那不是路由，那是灾难。
 *
 * <p><b>用 ASCII 内容而不是中文</b>：Windows/Git Bash 下中文会被编成 GBK，
 * 断言消息一旦乱码就没法读。这个项目已经在这个坑上栽过两次了。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("G3b 按请求路由")
class RoutingTest {

    /** 两个假上游。刻意用不同的端口和不同的应答内容，好区分。 */
    private static final WireMockServer DEEPSEEK = startUpstream();
    private static final WireMockServer MOONSHOT = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    private static final String DS_CONTENT = "from-deepseek";
    private static final String MS_CONTENT = "from-moonshot";

    @DynamicPropertySource
    static void pointAtTwoFakeUpstreams(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.default-channel", () -> "deepseek");

        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + DEEPSEEK.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key-deepseek");

        registry.add("gateway.providers.channels.moonshot.base-url",
                () -> "http://localhost:" + MOONSHOT.port());
        registry.add("gateway.providers.channels.moonshot.api-key", () -> "test-key-moonshot");
    }

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        DEEPSEEK.resetAll();
        MOONSHOT.resetAll();
        stubUpstream(DEEPSEEK, DS_CONTENT);
        stubUpstream(MOONSHOT, MS_CONTENT);

        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .build();
    }

    @AfterAll
    static void stopUpstreams() {
        DEEPSEEK.stop();
        MOONSHOT.stop();
    }

    // ========================================================================
    // 三条测试，对应三种输入。它们合起来才定义了"路由"这个行为。
    // ========================================================================

    @Test
    @DisplayName("指定 provider=moonshot → 打到 moonshot，deepseek 一个请求都没收到")
    void explicitProviderRoutesToThatUpstream() {
        String body = call(Map.of("provider", "moonshot"));

        assertThat(body)
                .as("响应内容应该来自 moonshot 的假上游")
                .contains(MS_CONTENT);

        // ★ 这条"零请求"断言和上面那条同样重要：
        // 如果网关把请求同时发给了两家（比如遍历时忘了 break、或者广播了），
        // 上面的断言照样会绿 —— 但那是重复计费，不是路由。
        MOONSHOT.verify(postRequestedFor(urlEqualTo("/chat/completions")));
        DEEPSEEK.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("不指定 provider → 落到配置里的默认渠道（deepseek）")
    void absentProviderFallsBackToDefault() {
        // 注意这里刻意<b>不传</b> provider 字段 —— 这正是所有标准 OpenAI SDK
        // 和 LangChain4j 的行为：它们根本不知道"渠道"这个概念。
        // 这条测试守的是"网关对标准客户端可用"。
        String body = call(Map.of());

        assertThat(body)
                .as("没指定渠道时必须走默认渠道，否则任何标准 OpenAI 客户端都调不通")
                .contains(DS_CONTENT);

        DEEPSEEK.verify(postRequestedFor(urlEqualTo("/chat/completions")));
        MOONSHOT.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("指定了不存在的渠道 → 400，且两个上游都没被打扰")
    void unknownProviderIsRejectedBeforeAnyUpstreamCall() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(Map.of("provider", "kimi")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("unknown_provider")
                // 错误信息里要带上实际可用的渠道 —— 排错的人不用翻代码/翻配置
                .jsonPath("$.error.message").value(msg -> assertThat((String) msg)
                        .contains("kimi")
                        .contains("deepseek")
                        .contains("moonshot"));

        // ★ 这条断言是"400 而不是 502/504"这个决策的直接证据：
        // 渠道名写错时，网关<b>压根没轮到上游</b>。
        // 如果这里返回的是 502，运维告警会显示"上游故障率上升"，
        // 值班的人会去查上游、联系厂商 —— 而真凶是调用方拼错了名字。
        DEEPSEEK.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
        MOONSHOT.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("流式请求同样走路由 —— 换渠道不能只对非流式生效")
    void streamingRequestIsRoutedToo() {
        // 流式是网关最主要的用法（项目一的 Agent 走的就是它）。
        // 路由那一行在 Controller 里位于 stream 分支<b>之前</b>，
        // 所以理论上两条路都覆盖了 —— 但"理论上"不是证据，这条测试才是。
        stubStreamingUpstream(MOONSHOT, MS_CONTENT);

        Map<String, Object> request = new HashMap<>(Map.of("provider", "moonshot"));
        request.put("stream", true);

        List<String> events = webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(request))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(String.join("", events))
                .as("流式的块也应该来自 moonshot")
                .contains(MS_CONTENT);
        assertThat(events.get(events.size() - 1))
                .as("网关补的协议尾巴不能因为换了渠道就丢了")
                .isEqualTo("[DONE]");

        MOONSHOT.verify(postRequestedFor(urlEqualTo("/chat/completions")));
        DEEPSEEK.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /** 发一个非流式请求，返回响应体原文 */
    private String call(Map<String, Object> extraFields) {
        Map<String, Object> body = new HashMap<>(extraFields);

        return webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(body))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .map(list -> String.join("", list))
                .block();
    }

    private Map<String, Object> requestBody(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("model", "any-model");
        body.put("messages", List.of(Map.of("role", "user", "content", "hello")));
        return body;
    }

    /** 假上游：非流式，内容里带上身份标记 */
    private void stubUpstream(WireMockServer server, String identity) {
        server.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"c1","object":"chat.completion","created":1,"model":"upstream-alias",
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},
                                 "finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                                """.formatted(identity))));
    }

    /** 假上游：流式，两个块 + 上游自己的 [DONE] */
    private void stubStreamingUpstream(WireMockServer server, String identity) {
        String sse = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"upstream-alias\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"" + identity + "\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]\n\n";

        server.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sse)));
    }
}
