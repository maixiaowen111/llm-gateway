package com.example.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 网关<b>对外</b>的流式响应契约（OpenAI 兼容）。G2-2b 的验收物。
 *
 * <p><b>它和 {@link ChatCompletionResponse} 的关系</b>：同一条消息，两种交付形态。
 * 非流式是「等模型写完整段，一把给你」；流式是「模型每写几个字就给你一块」。
 * 所以结构几乎一样，只有一个关键差别 ——
 * {@code message} 变成了 {@code delta}（增量）：<b>每个块只带"这一块新增的内容"</b>，
 * 客户端要自己把它们拼起来才是完整回复。
 *
 * <p><b>为什么必须自己定义这个类，而不是把上游的块透传出去</b>：
 * 和 1c 一模一样的理由 —— 上游的块里有厂商痕迹（比如实测中 {@code model} 报的是
 * {@code deepseek-flash}）。透传等于把"我们用了哪家模型、实际调了哪个版本"漏给客户端。
 * 归一是每一层出口的责任，流式也不例外。
 *
 * <p>{@code object} 钉死为 {@code chat.completion.chunk}：OpenAI 的流式协议就是靠这个值
 * 把"流式块"和"完整响应"区分开的，客户端（OpenAI SDK）会据此选解析路径。
 */
public record ChatCompletionChunk(
        String id,
        /** 固定 {@code chat.completion.chunk} */
        String object,
        long created,
        /** 报<b>客户端请求的 model</b>，不是上游实际用的 —— 和 1c 同一条铁律 */
        String model,
        List<Choice> choices,
        /**
         * <b>G7 新增：流式响应里的 token 用量。</b>平时为 null，只在最后一帧出现。
         *
         * <p><b>为什么它必须是"可选"的</b>：OpenAI 协议默认<b>不</b>在流里返回 usage ——
         * 客户端要发 {@code stream_options: {include_usage: true}} 才有。
         * 只支持旧协议的厂商（以及大量第三方兼容实现）根本不会给。
         * 所以计费侧必须有"拿不到 usage 也能结算"的退路（按已收到的内容估算 + WARN），
         * 而不是假设它一定存在。
         *
         * <p><b>为什么 {@code @JsonInclude(NON_NULL)} 只加在这个字段上</b>：
         * 中间块的 {@code finish_reason} 按 OpenAI 协议<b>必须</b>显式输出 null，
         * 所以不能给整个 record 加 NON_NULL —— 那会把协议要求的 null 也吃掉。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ChatCompletionResponse.Usage usage
) {

    public static final String OBJECT = "chat.completion.chunk";

    /** 兼容 G1~G6 的 5 参数构造（只有测试/旧调用在用） */
    public ChatCompletionChunk(String id, String object, long created, String model, List<Choice> choices) {
        this(id, object, created, model, choices, null);
    }

    /**
     * OpenAI 流式的结束哨兵。
     *
     * <p><b>它不是一个数据块</b> —— 它压根不是 JSON，是协议约定的一串字面量。
     * 客户端（OpenAI SDK）收到它才知道"流正常结束了，可以停止读取"。
     * 所以它不能被塞进归一逻辑当 JSON 解析，只能原样转发。
     */
    public static final String DONE_SENTINEL = "[DONE]";

    public record Choice(
            int index,
            Delta delta,
            /**
             * 只有最后一个块才非 null（stop / length）。中间的块全是 null ——
             * 客户端靠它判断"流结束了"。注意这里<b>不加</b> NON_NULL：
             * OpenAI 协议里中间块的 {@code finish_reason} 就是显式的 null。
             */
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    /**
     * 增量内容。
     *
     * <p><b>为什么这个类要 {@code NON_NULL}</b>：OpenAI 的中间块长这样
     * {@code "delta":{"content":"你"}} —— <b>没有 role 字段</b>；
     * 而第一个块是 {@code "delta":{"role":"assistant","content":""}}。
     * 如果让 {@code role=null} 也序列化出去，客户端会看到 {@code "role":null}，
     * 严格的 SDK 会判为协议不符。所以 null 字段直接不输出。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(
            String role,
            String content,
            /**
             * <b>G9：流式工具调用片段。</b>
             *
             * <p><b>为什么这里是 {@code List<JsonNode>} 而不是 {@code List<ToolCall>}</b>：
             * 流式协议里一次调用是<b>被切成好几块</b>发过来的 ——
             * 第一块带 {@code index:0, id, function.name}，后续块只带
             * {@code index:0, function.arguments} 的字符串片段（一个字一个字地到）。
             * 它们各自都<b>不是一个完整的 ToolCall</b>，而拼装规则是"按 index 分组、
             * 把 arguments 首尾相接"。
             *
             * <p>网关<b>不做这个拼装</b>：拼装是客户端的职责（LangChain4j / OpenAI SDK
             * 都实现好了），网关替它拼反而会把"增量"变回"全量"，破坏流式的意义。
             * 所以这里保留原始片段的形状，原样转发 —— <b>只做搬运，不做解释。</b>
             */
            @JsonProperty("tool_calls") List<JsonNode> toolCalls
    ) {
        /** 兼容 G2~G8 的两参数构造。 */
        public Delta(String role, String content) {
            this(role, content, null);
        }
    }
}
