package com.example.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
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

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G9：工具调用穿透网关的契约测试（接入项目一 Agent 的前置条件）。</b>
 *
 * <p><b>★ 为什么这个测试比"能调通"更重要 ★</b>
 * <p>项目一的 Agent 依赖三个 OpenAI 扩展字段：{@code tools}（模型能做什么）、
 * {@code tool_calls}（模型要做哪一件事）、{@code response_format}（最终回答必须是 JSON）。
 * 网关最初的 DTO 把它们当成"不认识的字段"直接丢掉 —— 请求依然 200，
 * 但 Agent 从此一个工具也调不动。
 *
 * <p><b>这种故障在监控上是"全绿"的</b>：延迟正常、成功率 100%、没有 5xx。
 * 只有用户会发现"助手变笨了"。所以它必须被一条契约测试钉死：
 * <b>请求方向的工具定义要到达上游，响应方向的工具调用要回到客户端。</b>
 *
 * <p>两个方向都要测，因为它们是两条独立的代码路径：
 * 请求方向是"翻译网关契约 → 上游请求体"，响应方向是"归一上游响应 → 网关契约"。
 * 只测一个方向，另一半照样会悄悄坏掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("G9 契约：Function Calling 必须能穿过网关")
class ToolCallingContractTest {

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.channels.deepseek.base-url",
                () -> "http://localhost:" + UPSTREAM.port());
        registry.add("gateway.providers.channels.deepseek.api-key", () -> "test-key");
        // 和 ChatCompletionContractTest 同样的理由：开着故障转移会把请求转去真实 moonshot，
        // 测到的就不是本类的结论了
        registry.add("gateway.providers.routing.failover-enabled", () -> false);
        registry.add("gateway.resilience.max-retry-attempts", () -> 0);
    }

    /** 模型只回工具调用时的典型响应：content 是 null，真正的意图在 tool_calls 里 */
    private static final String TOOL_CALL_RESPONSE = """
            {
              "id": "chatcmpl-tool-001",
              "object": "chat.completion",
              "created": 1759700000,
              "model": "deepseek-flash",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_abc",
                    "type": "function",
                    "function": { "name": "queryOrder", "arguments": "{\\"orderNo\\":\\"A1001\\"}" }
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": { "prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60 }
            }
            """;

    /** 客户端第一轮：用户提问 + 可用工具清单 + 要求最终输出 JSON */
    private static final String FIRST_ROUND_REQUEST = """
            {
              "model": "deepseek-chat",
              "temperature": 0,
              "response_format": { "type": "json_object" },
              "tools": [{
                "type": "function",
                "function": {
                  "name": "queryOrder",
                  "description": "按订单号查询订单状态",
                  "parameters": {
                    "type": "object",
                    "properties": { "orderNo": { "type": "string" } },
                    "required": ["orderNo"]
                  }
                }
              }],
              "tool_choice": { "type": "function", "function": { "name": "queryOrder" } },
              "messages": [
                { "role": "system", "content": "你是订单助手" },
                { "role": "user", "content": "帮我查一下 A1001" }
              ]
            }
            """;

    /** 客户端第二轮：把模型要的调用原样带回去，再回填工具执行结果 */
    private static final String SECOND_ROUND_REQUEST = """
            {
              "model": "deepseek-chat",
              "messages": [
                { "role": "system", "content": "你是订单助手" },
                { "role": "user", "content": "帮我查一下 A1001" },
                {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_abc",
                    "type": "function",
                    "function": { "name": "queryOrder", "arguments": "{\\"orderNo\\":\\"A1001\\"}" }
                  }]
                },
                { "role": "tool", "tool_call_id": "call_abc", "content": "{\\"status\\":\\"已发货\\"}" }
              ]
            }
            """;

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
        this.client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void tearDown() {
        UPSTREAM.stop();
    }

    @Test
    @DisplayName("第一轮：tools / tool_choice / response_format / temperature 到达上游，tool_calls 回到客户端")
    void toolDefinitionAndToolCallPassThrough() throws Exception {
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TOOL_CALL_RESPONSE)));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(FIRST_ROUND_REQUEST)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // ★ 响应方向：模型"决定调什么"这件事必须原样到客户端 ★
                .jsonPath("$.choices[0].message.tool_calls[0].id").isEqualTo("call_abc")
                .jsonPath("$.choices[0].message.tool_calls[0].function.name").isEqualTo("queryOrder")
                // arguments 是【字符串】—— 不能变成对象，否则客户端拿到的类型就变了
                .jsonPath("$.choices[0].message.tool_calls[0].function.arguments").isEqualTo("{\"orderNo\":\"A1001\"}")
                // content 必须是 null，而不是字符串 "null"（JsonNode.asText() 的经典坑）
                .jsonPath("$.choices[0].message.content").isEmpty()
                .jsonPath("$.choices[0].finish_reason").isEqualTo("tool_calls");

        // ★ 请求方向：网关有没有把工具定义真正发出去 ★
        JsonNode upstream = lastUpstreamRequest();
        assertThat(upstream.path("temperature").asDouble()).isEqualTo(0.0);
        assertThat(upstream.path("tools")).hasSize(1);
        assertThat(upstream.path("tools").path(0).path("function").path("name").asText())
                .isEqualTo("queryOrder");
        // parameters 里的 JSON Schema 必须原样保留 —— 网关不该"理解"它，只该搬运
        assertThat(upstream.path("tools").path(0).path("function").path("parameters")
                .path("required").path(0).asText()).isEqualTo("orderNo");
        assertThat(upstream.path("tool_choice").path("function").path("name").asText())
                .isEqualTo("queryOrder");
        assertThat(upstream.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    @DisplayName("第二轮：assistant 的 tool_calls 与 tool 消息的 tool_call_id 必须原样回传")
    void secondRoundPreservesToolCallId() throws Exception {
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TOOL_CALL_RESPONSE)));

        client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(SECOND_ROUND_REQUEST)
                .exchange()
                .expectStatus().isOk();

        JsonNode messages = lastUpstreamRequest().path("messages");

        // 关键点 1：assistant 消息里的 tool_calls 不能被丢掉，id 也不能变 ——
        // 变了之后模型会把工具结果认成"另一次调用"，从而重复发起调用
        JsonNode assistant = messages.path(2);
        assertThat(assistant.path("tool_calls").path(0).path("id").asText()).isEqualTo("call_abc");
        // content 为 null 的 assistant 消息不能被 Gateway 抛 NPE（Map.of 的经典坑）
        assertThat(assistant.has("content")).isFalse();

        // 关键点 2：tool 消息必须带着 tool_call_id 一起发出去
        JsonNode toolMessage = messages.path(3);
        assertThat(toolMessage.path("role").asText()).isEqualTo("tool");
        assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo("call_abc");
        assertThat(toolMessage.path("content").asText()).contains("已发货");
    }

    @Test
    @DisplayName("流式：delta.tool_calls 的增量片段原样转发，网关不替客户端拼装")
    void streamingToolCallFragmentsAreForwarded() {
        String sse = """
                data: {"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"queryOrder","arguments":""}}]},"finish_reason":null}]}

                data: {"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"orderNo\\":\\"A1001\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        UPSTREAM.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sse)));

        String body = client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"deepseek-chat","stream":true,
                         "tools":[{"type":"function","function":{"name":"queryOrder","parameters":{"type":"object"}}}],
                         "messages":[{"role":"user","content":"查 A1001"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                // 第一块保留 index + id + name —— 客户端靠 index 分组、靠 id 回填结果
                .contains("\"index\":0")
                .contains("\"id\":\"call_abc\"")
                // 第二块是 arguments 的字符串片段，必须仍是字符串
                .contains("{\\\"orderNo\\\":\\\"A1001\\\"}")
                .contains("\"finish_reason\":\"tool_calls\"");
    }

    /** 取网关发往上游的最后一次请求体 —— 这是断言"请求方向"的唯一可信来源 */
    private JsonNode lastUpstreamRequest() throws Exception {
        List<LoggedRequest> requests = UPSTREAM.findAll(
                WireMock.postRequestedFor(urlEqualTo("/chat/completions")));
        assertThat(requests).as("网关没有向上游发出请求").isNotEmpty();
        return objectMapper.readTree(requests.get(requests.size() - 1).getBodyAsString());
    }
}
