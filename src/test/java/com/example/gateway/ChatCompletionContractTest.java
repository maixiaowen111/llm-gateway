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

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1-1e 的验收物：网关对外的<b>契约测试</b>。
 *
 * <p><b>它和 1d 手工验证的区别</b>：1d 是我起个假上游、改环境变量、curl 一遍、看结果 —— 做一次就没了。
 * 这里把它固化成代码：以后任何一次改动，跑 {@code mvn test} 就能知道契约有没有被破坏。
 *
 * <p><b>为什么叫"契约"测试</b>：它不关心网关内部怎么实现，只锁死<b>对外的承诺</b> ——
 * 成功时返回什么形状、失败时返回什么形状。内部随便重构，只要契约不破，测试就该绿。
 *
 * <p><b>为什么用假上游而不是真的 DeepSeek</b>：真上游三个问题 —— 要真 Key、要花钱、
 * 结果不确定（模型每次回的话都不一样，没法断言）。假上游回的是<b>写死的</b>响应，
 * 才能断言出确定的结论。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("网关对外契约：/v1/chat/completions")
class ChatCompletionContractTest {

    /** 读超时设短一点，否则测试要等 30 秒。生产默认值是 30000ms */
    private static final long READ_TIMEOUT_MS = 1500;

    /**
     * 假上游。
     *
     * <p><b>为什么写在静态字段而不是 {@code @BeforeAll}</b>：{@code @DynamicPropertySource}
     * 是在 Spring 上下文启动时求值的，而 {@code @BeforeAll} 在那之后才跑 ——
     * 写在 {@code @BeforeAll} 里，端口号还没生成就被读走了，会拿到 0。
     * 静态字段在类加载时就初始化，赶得上。
     */
    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    /**
     * 把网关的"上游地址"从真实的 api.deepseek.com 改到假上游。
     *
     * <p>这能成立，靠的是 1d 把 base-url 和超时都做成了<b>可外部覆盖的配置</b> ——
     * 继承 {@code application.yml} 的默认值，只在测试里改这几个。一个字都不用改业务代码。
     */
    @DynamicPropertySource
    static void pointGatewayAtFakeUpstream(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.channels.deepseek.base-url", () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key-not-a-real-secret");
        registry.add("gateway.upstream.connect-timeout-ms", () -> 1000);
        registry.add("gateway.upstream.read-timeout-ms", () -> READ_TIMEOUT_MS);
    }

    /**
     * 假上游的固定响应。刻意做成和真实 DeepSeek 一样的"坑"：
     * <ul>
     *   <li>给了 {@code model: "deepseek-flash"} —— 而客户端请求的是 {@code deepseek-chat}</li>
     *   <li><b>没给</b> {@code object} 字段</li>
     * </ul>
     * 所以下面能断言出口是 {@code deepseek-chat} / {@code chat.completion}，
     * 就等于断言了"归一真的发生了"，而不是"把上游的东西透传了"。
     */
    private static final String UPSTREAM_BODY = """
            {
              "id": "chatcmpl-wiremock-001",
              "model": "deepseek-flash",
              "created": 1759700000,
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "这是假上游返回的固定答复" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18 }
            }
            """;

    private static final String CLIENT_REQUEST = """
            { "model": "deepseek-chat", "messages": [ { "role": "user", "content": "你好" } ] }
            """;

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        // 清掉上一个用例留下的桩，避免互相干扰
        UPSTREAM.resetAll();
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                // WebTestClient 自己也有默认超时（5 秒），设大一点免得它先掐断、掩盖真实结论
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    // ------------------------------------------------------------------ 场景一

    @Test
    @DisplayName("正常返回：上游给的是 deepseek-flash、且没给 object 字段，出口必须是我们要的样子")
    void normalResponseIsNormalized() {
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(UPSTREAM_BODY)));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CLIENT_REQUEST)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // ↓ 上游说的是 deepseek-flash，出口必须报客户端请求的 deepseek-chat
                .jsonPath("$.model").isEqualTo("deepseek-chat")
                // ↓ 上游压根没给这个字段，是我们补的
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.choices[0].message.content").isEqualTo("这是假上游返回的固定答复")
                // ↓ snake_case：上游是 finish_reason，出口也得是 finish_reason
                .jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
                .jsonPath("$.usage.total_tokens").isEqualTo(18);
    }

    // ------------------------------------------------------------------ 场景二

    @Test
    @DisplayName("上游超时：必须在读超时到点就返回 504，而不是陪着上游一直等")
    void upstreamReadTimeoutYields504() {
        // 假上游接了连接，但拖 6 秒才回 —— 远超 1.5 秒的读超时
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay((int) READ_TIMEOUT_MS * 4)
                        .withHeader("Content-Type", "application/json")
                        .withBody(UPSTREAM_BODY)));

        long startNanos = System.nanoTime();

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CLIENT_REQUEST)
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                // 错误也必须是 OpenAI 格式 —— 否则 OpenAI SDK 解析不出来
                .jsonPath("$.error.code").isEqualTo("upstream_read_timeout")
                .jsonPath("$.error.type").isEqualTo("upstream_error");

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 光断言 504 还不够：万一是等满 6 秒、上游回完了才报错，那也是 504，但语义完全错了。
        // 这条断言才真正锁住"到点就掐断"这个行为。
        assertThat(elapsedMs)
                .as("应在读超时附近返回，而不是等满上游的 6 秒延迟")
                .isLessThan(READ_TIMEOUT_MS * 4);
    }
}
