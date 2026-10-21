package com.example.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 网关<b>对外</b>的响应契约（OpenAI 兼容）。G1-1c 的验收物之一。
 *
 * <p><b>关键点：这个对象是网关自己拼出来的，不是把上游响应原样透传。</b>
 * 只有自己拼，才能把各家厂商的差异挡在网关内部 ——
 * 上游是 DeepSeek、通义千问还是本地 Ollama，出口长这样。
 *
 * <p>字段名用 {@code @JsonProperty} 显式钉成 snake_case，因为 OpenAI 协议就是 snake_case
 * （{@code finish_reason} / {@code prompt_tokens}）。不要为了这个去改全局 Jackson 命名策略，
 * 那会连带影响网关自己的所有接口。
 */
public record ChatCompletionResponse(
        String id,
        /** 固定 "chat.completion"，OpenAI 协议要求 */
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage
) {

    public record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    public record Message(
            String role,
            /** 带工具调用时可以是 null —— 模型这一轮只决定"调什么"，没有文字输出 */
            String content,
            /**
             * <b>G9：模型的工具调用请求。</b>
             * <p>没有工具调用时<b>整个字段都不输出</b>（NON_NULL）。
             * 这是刻意的：老客户端（G1~G8 时代写的、只认 role/content 的解析器）
             * 不该因为网关升级而突然看到一个陌生字段 ——
             * <b>给所有响应加字段是兼容的，加 null 字段则取决于对方解析器的严格程度。</b>
             */
            @JsonProperty("tool_calls")
            @JsonInclude(JsonInclude.Include.NON_NULL)
            List<ToolCall> toolCalls
    ) {
        /** 兼容 G1~G8 的两参数构造（网关内部和大量测试在用）。 */
        public Message(String role, String content) {
            this(role, content, null);
        }
    }

    /** token 用量。G7 计量直接吃这个结构 —— 计费的原始数据上游就给了，不用自己数 */
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }
}
