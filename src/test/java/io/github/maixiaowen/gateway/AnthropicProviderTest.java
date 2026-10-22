package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.dto.ChatCompletionChunk;
import io.github.maixiaowen.gateway.dto.ChatCompletionRequest;
import io.github.maixiaowen.gateway.dto.ChatCompletionResponse;
import io.github.maixiaowen.gateway.dto.Tool;
import io.github.maixiaowen.gateway.dto.ToolCall;
import io.github.maixiaowen.gateway.provider.AnthropicMessagesProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G3c 的验收物：一个协议真的不同的渠道，能被同一套 SPI 吃下去。</b>
 *
 * <p><b>这个测试在防什么</b>：
 * <ol>
 *   <li><b>"假装支持"</b> —— 代码里写着 anthropic，实际发的还是 OpenAI 的请求体。
 *       所以这里<b>逐字段断言了发出去的请求</b>（路径、鉴权头、顶层 system、必填 max_tokens）；</li>
 *   <li><b>"归一漏了"</b> —— 把 Anthropic 的 {@code content[]} / {@code stop_reason}
 *       原样透出去。这里断言出口是 OpenAI 形状，且 {@code max_tokens → length} 的映射真的发生了；</li>
 *   <li><b>流式事件漏处理</b> —— 只处理 {@code content_block_delta} 而忘了
 *       {@code message_start} 里的 input_tokens，计费就会少掉一半（G7 会吃这个亏）。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code @SpringBootTest}</b>：本类验的是"一个 Provider 实现"，
 * 和 Spring 容器没有任何关系。直接 new 出来测，启动快、失败信息也更聚焦 ——
 * <b>能不用框架就用不上，测试才有价值。</b>
 */
@DisplayName("G3c：Anthropic Messages 协议适配")
class AnthropicProviderTest {

    private static final WireMockServer UPSTREAM = startUpstream();

    private static WireMockServer startUpstream() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    private AnthropicMessagesProvider provider;

    @BeforeEach
    void setUp() {
        UPSTREAM.resetAll();
        provider = new AnthropicMessagesProvider(
                "anthropic",
                "http://localhost:" + UPSTREAM.port(),
                "test-anthropic-key",
                "claude-default-model",
                512,                 // 渠道默认 max_tokens
                5_000,               // 逐块空闲超时
                WebClient.builder(),
                new ObjectMapper());
    }

    // ------------------------------------------------------------------ 非流式

    @Test
    @DisplayName("非流式：请求体 / 鉴权头按 Anthropic 协议转换，响应归一成 OpenAI 形状")
    void nonStreamTranslatesBothWays() {
        UPSTREAM.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_01ABC",
                                  "type": "message",
                                  "role": "assistant",
                                  "content": [
                                    { "type": "text", "text": "你好，" },
                                    { "type": "text", "text": "我是克劳德。" }
                                  ],
                                  "model": "claude-sonnet-5-20250929",
                                  "stop_reason": "max_tokens",
                                  "usage": { "input_tokens": 12, "output_tokens": 7 }
                                }
                                """)));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "claude-sonnet-5",
                List.of(
                        new ChatCompletionRequest.Message("system", "你是订单系统的客服助手"),
                        new ChatCompletionRequest.Message("user", "帮我查一下订单")),
                false,
                "anthropic",
                64);

        ChatCompletionResponse response = provider.chatCompletion(request)
                .block(Duration.ofSeconds(5));

        assertThat(response).isNotNull();

        // ---- 发出去的请求：三处协议差异 + 一个兜底规则 ----
        UPSTREAM.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                // ① 鉴权是 x-api-key，不是 Authorization: Bearer
                .withHeader("x-api-key", equalTo("test-anthropic-key"))
                // ② 必须带版本头
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                // ③ system 被提到顶层，messages 里只剩 user
                .withRequestBody(matchingJsonPath("$.system", equalTo("你是订单系统的客服助手")))
                .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                // ④ 客户端显式传的 max_tokens 优先于渠道默认值 512
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("64")))
                // ⑤ 非流式请求必须显式关掉 stream
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false"))));

        // ---- 回来的响应：归一 ----
        assertThat(response.object()).isEqualTo("chat.completion");
        // 报客户端请求的模型，不是上游实际用的版本
        assertThat(response.model()).isEqualTo("claude-sonnet-5");
        // content[] 里的多个 text 块被拼成一段
        assertThat(response.choices().get(0).message().content()).isEqualTo("你好，我是克劳德。");
        // ★ max_tokens → length。不做这个映射，客户端会把"被截断"当成"说完了"
        assertThat(response.choices().get(0).finishReason()).isEqualTo("length");
        assertThat(response.usage().promptTokens()).isEqualTo(12);
        assertThat(response.usage().completionTokens()).isEqualTo(7);
        assertThat(response.usage().totalTokens()).isEqualTo(19);
    }

    @Test
    @DisplayName("客户端没传 max_tokens 时：用渠道默认值兜底，而不是让上游 400")
    void missingMaxTokensFallsBackToChannelDefault() {
        UPSTREAM.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "id":"msg_x", "content":[{"type":"text","text":"ok"}],
                                  "stop_reason":"end_turn", "usage":{"input_tokens":1,"output_tokens":1} }
                                """)));

        provider.chatCompletion(new ChatCompletionRequest(
                        "claude-sonnet-5",
                        List.of(new ChatCompletionRequest.Message("user", "hi")),
                        false, "anthropic"))
                .block(Duration.ofSeconds(5));

        UPSTREAM.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("512"))));
    }

    // ------------------------------------------------------------------ 流式

    @Test
    @DisplayName("流式：event 事件被归一出 OpenAI 的 chunk，usage 两半能拼起来")
    void streamNormalizesEventsAndUsage() {
        UPSTREAM.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                event: message_start
                                data: {"type":"message_start","message":{"id":"msg_stream_1","usage":{"input_tokens":12,"output_tokens":1}}}

                                event: ping
                                data: {"type":"ping"}

                                event: content_block_start
                                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                                event: content_block_delta
                                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"订单"}}

                                event: content_block_delta
                                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"已发货"}}

                                event: message_delta
                                data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":7}}

                                event: message_stop
                                data: {"type":"message_stop"}

                                """)));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "claude-sonnet-5",
                List.of(new ChatCompletionRequest.Message("user", "订单到哪了")),
                true,
                "anthropic");

        List<ChatCompletionChunk> chunks = provider.chatCompletionStream(request)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).isNotNull();
        // message_start + 2 个 delta + message_delta = 4 块；
        // ping / content_block_start / message_stop 不产生数据块
        assertThat(chunks).hasSize(4);

        // 第一块：角色帧（OpenAI 协议的第一个块就是这个形状）
        assertThat(chunks.get(0).choices().get(0).delta().role()).isEqualTo("assistant");
        assertThat(chunks.get(0).choices().get(0).delta().content()).isEmpty();

        // 中间块：纯增量，没有 role
        assertThat(chunks.get(1).choices().get(0).delta().content()).isEqualTo("订单");
        assertThat(chunks.get(1).choices().get(0).delta().role()).isNull();
        assertThat(chunks.get(2).choices().get(0).delta().content()).isEqualTo("已发货");

        // 最后一块：finish_reason 映射 + 完整 usage
        ChatCompletionChunk last = chunks.get(3);
        assertThat(last.choices().get(0).finishReason()).isEqualTo("length");
        assertThat(last.usage()).isNotNull();
        // ★ input 来自 message_start、output 来自 message_delta ——
        //   少了任何一半，G7 的结算就对不上账
        assertThat(last.usage().promptTokens()).isEqualTo(12);
        assertThat(last.usage().completionTokens()).isEqualTo(7);
        assertThat(last.usage().totalTokens()).isEqualTo(19);

        // 每一个块对外都必须报"客户端请求的模型"
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.model()).isEqualTo("claude-sonnet-5"));
    }

    // ------------------------------------------------------------------ 工具调用（G12）

    @Test
    @DisplayName("G12 非流式：tools / tool_use / tool_result / finish_reason 五处翻译全部生效")
    void toolCallingTranslatesBothWays() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        UPSTREAM.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_tool_1",
                                  "type": "message",
                                  "role": "assistant",
                                  "content": [
                                    { "type": "tool_use", "id": "toolu_1",
                                      "name": "queryOrder", "input": { "orderNo": "A1001" } }
                                  ],
                                  "stop_reason": "tool_use",
                                  "usage": { "input_tokens": 10, "output_tokens": 5 }
                                }
                                """)));

        Tool tool = new Tool("function", new Tool.Function(
                "queryOrder", "按订单号查询",
                mapper.readTree("""
                        {"type":"object","properties":{"orderNo":{"type":"string"}},"required":["orderNo"]}
                        """)));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "claude-sonnet-5",
                List.of(
                        new ChatCompletionRequest.Message("user", "查一下 A1001"),
                        new ChatCompletionRequest.Message("assistant", null,
                                List.of(new ToolCall("call_1", "function",
                                        new ToolCall.Function("queryOrder", "{\"orderNo\":\"A1001\"}"))),
                                null, null),
                        new ChatCompletionRequest.Message("tool", "已发货", null, "call_1", null)),
                false, "anthropic", null, null,
                List.of(tool),
                mapper.readTree("{\"type\":\"function\",\"function\":{\"name\":\"queryOrder\"}}"),
                null);

        ChatCompletionResponse response = provider.chatCompletion(request)
                .block(Duration.ofSeconds(5));

        assertThat(response).isNotNull();

        // ---- 发出去的请求：工具定义与消息形状 ----
        UPSTREAM.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.tools[0].name", equalTo("queryOrder")))
                // parameters → input_schema
                .withRequestBody(matchingJsonPath("$.tools[0].input_schema.properties.orderNo.type",
                        equalTo("string")))
                // tool_choice 对象形态：{"type":"function","function":{"name"}} → {"type":"tool","name"}
                .withRequestBody(matchingJsonPath("$.tool_choice.type", equalTo("tool")))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("queryOrder")))
                // assistant 的工具调用 → content[] 里的 tool_use；arguments 字符串 → input 对象
                .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("assistant")))
                .withRequestBody(matchingJsonPath("$.messages[1].content[0].type", equalTo("tool_use")))
                .withRequestBody(matchingJsonPath("$.messages[1].content[0].input.orderNo",
                        equalTo("A1001")))
                // role=tool → user + tool_result，而不是一条 "tool" 角色的消息
                .withRequestBody(matchingJsonPath("$.messages[2].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].type",
                        equalTo("tool_result")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[0].tool_use_id",
                        equalTo("call_1"))));

        // ---- 回来的响应：tool_use → tool_calls ----
        ChatCompletionResponse.Choice choice = response.choices().get(0);
        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        // 只有工具调用、没有文字时，content 必须是 null（OpenAI 约定）
        assertThat(choice.message().content()).isNull();
        assertThat(choice.message().toolCalls()).hasSize(1);
        assertThat(choice.message().toolCalls().get(0).id()).isEqualTo("toolu_1");
        assertThat(choice.message().toolCalls().get(0).function().name()).isEqualTo("queryOrder");
        // input 对象 → arguments JSON 字符串，且内容不丢
        assertThat(mapper.readTree(choice.message().toolCalls().get(0).function().arguments()))
                .isEqualTo(mapper.readTree("{\"orderNo\":\"A1001\"}"));
    }

    @Test
    @DisplayName("G12 流式：tool_use 块 → OpenAI tool_calls 片段，参数按 index 拼接")
    void streamingToolCallIsTranslated() {
        UPSTREAM.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                        .withBody("""
                                event: message_start
                                data: {"type":"message_start","message":{"id":"msg_tool_stream","usage":{"input_tokens":8,"output_tokens":0}}}

                                event: content_block_start
                                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_9","name":"queryOrder"}}

                                event: content_block_delta
                                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"order"}}

                                event: content_block_delta
                                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"No\\":\\"A1001\\"}"}}

                                event: content_block_stop
                                data: {"type":"content_block_stop","index":0}

                                event: message_delta
                                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}

                                event: message_stop
                                data: {"type":"message_stop"}

                                """)));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "claude-sonnet-5",
                List.of(new ChatCompletionRequest.Message("user", "查订单")),
                true, "anthropic");

        List<ChatCompletionChunk> chunks = provider.chatCompletionStream(request)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).isNotNull();
        // message_start + tool_start + 2 个参数片段 + message_delta = 5
        assertThat(chunks).hasSize(5);

        // 第一帧工具块：带 index / id / name，arguments 为空串
        var start = chunks.get(1).choices().get(0).delta().toolCalls();
        assertThat(start).hasSize(1);
        assertThat(start.get(0).path("index").asInt()).isZero();
        assertThat(start.get(0).path("id").asText()).isEqualTo("toolu_9");
        assertThat(start.get(0).path("function").path("name").asText()).isEqualTo("queryOrder");
        assertThat(start.get(0).path("function").path("arguments").asText()).isEmpty();

        // 后续帧：只带 index 和 arguments 片段
        assertThat(chunks.get(2).choices().get(0).delta().toolCalls().get(0)
                .path("function").path("arguments").asText()).isEqualTo("{\"order");
        assertThat(chunks.get(3).choices().get(0).delta().toolCalls().get(0)
                .path("function").path("arguments").asText()).isEqualTo("No\":\"A1001\"}");

        // 结束原因：tool_use → tool_calls（否则 Agent 会以为模型说完了）
        assertThat(chunks.get(4).choices().get(0).finishReason()).isEqualTo("tool_calls");
        assertThat(chunks.get(4).usage().completionTokens()).isEqualTo(12);
    }

    @Test
    @DisplayName("G12 tool_choice=none：整段省略 tools —— 客户端说“别调”就不能让它调")
    void toolChoiceNoneOmitsTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UPSTREAM.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"msg_x","content":[{"type":"text","text":"ok"}],
                                 "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}
                                """)));

        Tool tool = new Tool("function", new Tool.Function(
                "queryOrder", "按订单号查询",
                mapper.readTree("{\"type\":\"object\",\"properties\":{}}")));
        ChatCompletionRequest request = new ChatCompletionRequest(
                "claude-sonnet-5",
                List.of(new ChatCompletionRequest.Message("user", "不用工具，直接回答")),
                false, "anthropic", null, null, List.of(tool),
                mapper.readTree("\"none\""), null);

        provider.chatCompletion(request).block(Duration.ofSeconds(5));

        String sentBody = UPSTREAM.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(sentBody).doesNotContain("\"tools\"");
        assertThat(sentBody).doesNotContain("\"tool_choice\"");
    }
}
