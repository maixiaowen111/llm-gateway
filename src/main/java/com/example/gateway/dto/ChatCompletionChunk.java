package com.example.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        List<Choice> choices
) {

    public static final String OBJECT = "chat.completion.chunk";

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
    public record Delta(String role, String content) {
    }
}
