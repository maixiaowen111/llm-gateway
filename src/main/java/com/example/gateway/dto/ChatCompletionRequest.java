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
        Boolean stream,
        /**
         * <b>G3b 新增：客户端想走哪个渠道。</b>可空。
         *
         * <p><b>⚠️ 注意这不是 OpenAI 协议里的字段 —— 它是网关的扩展。</b>
         * 这是刻意的，也是正确的：标准 OpenAI SDK 不知道"渠道"这个概念
         * （对它们来说世界上只有一个 OpenAI），所以它们<b>不会发这个字段</b>。
         * 靠 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 和这里的可空设计，
         * <b>标准客户端不传它也能正常工作</b>，走默认渠道。
         *
         * <p><b>那为什么还要有这个字段？</b>因为有些客户端<b>确实需要</b>指定：
         * 比如项目一的 Agent 想让"便宜的批量任务"走便宜渠道、
         * "需要长上下文的任务"走上下文长的渠道；又比如 A/B 对比两家模型的效果。
         * 这些需求<b>用 model 表达不了</b>（同一个 model 名可能在多家都有）。
         *
         * <p><b>它为什么可空 / 为什么在最后一位</b>：见
         * {@link com.example.gateway.provider.ProviderRegistry#resolveOrDefault(String)} ——
         * <b>"没指定"和"指定了不存在的"是两种完全不同的情况</b>，
         * 前者正常回落、后者必须报错。可空类型让这两种情况在类型层面就分得开。
         *
         * <p><b>⚠️ 一个 3b 没做、但要想清楚的设计题</b>：真实网关里更主流的做法是
         * <b>按 {@code model} 名字路由</b>（看到 {@code gpt-4} 就走 OpenAI、
         * 看到 {@code deepseek-chat} 就走 DeepSeek），因为客户端本来就只认识 model。
         * 那样做需要额外一张"模型名 → 渠道"的映射表，而且会有歧义
         * （多个渠道都有同名模型时怎么办？）。3b 只做"显式指定 + 默认兜底"这一层，
         * 把"按 model 推断"留给 G6 的渠道路由一起做 —— <b>那时才有健康度和权重需要一起考虑。</b>
         */
        String provider
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }
}
