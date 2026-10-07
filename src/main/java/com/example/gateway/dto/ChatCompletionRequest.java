package com.example.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 网关<b>对外</b>的请求契约（OpenAI 兼容）。G1-1c 的验收物之一。
 *
 * <p><b>为什么网关要定义"自己的"契约，而不是直接用上游的？</b>
 * 因为从这一刻起，<b>这个类就是网关对外的承诺</b>。客户端（项目一的 Agent、
 * LangChain4j、任何 OpenAI SDK）只认这个结构。
 * 以后接 DeepSeek 之外的厂商时，<b>改的是网关内部，改不到这里</b> —— 客户端零改造。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：真实的 OpenAI 客户端
 * 会带一堆我们不认识的字段（temperature / top_p / tools / stream_options …）。
 * 少一个字段就 400，那不叫网关，那叫障碍。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        /** 1c 只做非流式；流式是 G2 的事 */
        Boolean stream
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }
}
