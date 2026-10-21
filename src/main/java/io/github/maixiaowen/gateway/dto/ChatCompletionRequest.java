package io.github.maixiaowen.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

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
         * {@link io.github.maixiaowen.gateway.provider.ProviderRegistry#resolveOrDefault(String)} ——
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
        String provider,
        /**
         * <b>G7 新增：客户端允许输出的最大 token 数。</b>OpenAI 协议里叫
         * {@code max_tokens}（新一点的接口叫 {@code max_completion_tokens}，
         * 这里先支持前者 —— 它是所有 OpenAI 兼容厂商的公共交集）。
         *
         * <p><b>它为什么对计费是必需的</b>：预扣需要知道"最坏情况要花多少"。
         * 没有上限，预扣就只能按一个拍脑袋的默认值来 —— 那等于让"请求没写上限"
         * 的客户占用了本不该占的额度。
         *
         * <p><b>⚠️ 但预扣的上限不等于实际收费</b>：响应回来后按上游返回的
         * {@code usage} 实结算，多退少补。预扣只是"先把刀架在那里"，
         * 不是"先按最大价扣钱"。
         *
         * <p>为空时用 {@code gateway.billing.default-max-tokens}。
         * <b>注意：我们不会擅自把这个值填进上游请求</b> —— 客户端没写上限，
         * 意味着它接受模型的默认行为；网关替它改成一个更小的值，
         * 会让回复莫名其妙被截断。预扣时按默认上限估，结算时按实际 usage 算即可。
         */
        @JsonProperty("max_tokens") Integer maxTokens,

        /**
         * <b>G9 新增：采样温度。</b>原样透传给上游，网关不解释它的语义。
         *
         * <p><b>为什么网关必须"透传"而不是"忽略"</b>：项目一的 Agent 用
         * {@code temperature=0} 保证工具选择稳定 —— 同一句话这次查单、下次取消，
         * 用户会认为系统坏了。网关把温度吃掉，等于替业务改了一个它没参与的决定。
         *
         * <p><b>为什么用可空的 {@code Double} 而不是 {@code double}</b>：
         * "没传"和"传了 0.0"必须能区分。前者交给上游默认值，后者是一个明确要求。
         */
        Double temperature,

        /**
         * <b>G9 新增：工具定义（Function Calling）。</b>
         *
         * <p><b>这是"能不能接入真实 Agent"的分水岭</b>：如果网关不认识这个字段，
         * 项目一的 Agent 经网关调用时工具会全部消失，模型只能纯聊天 ——
         * 请求仍然是 200，但业务能力已经没了。<b>这种"成功但不对"比报错难查得多。</b>
         *
         * <p>用 {@code List<Tool>} 而不是 {@code JsonNode}：工具定义的结构是稳定的
         * （type + function{name, description, parameters}），而 parameters 本身是任意
         * JSON Schema —— 所以只在 {@code parameters} 那一层用 {@link JsonNode} 兜住自由度。
         */
        List<Tool> tools,

        /**
         * <b>G9 新增：{@code tool_choice}。</b>
         * <p>它的取值既有字符串（{@code "auto"} / {@code "none"} / {@code "required"}），
         * 也有对象（{@code {"type":"function","function":{"name":"queryOrder"}}}）。
         * <b>两种形状共存的字段，用 JsonNode 原样透传才是对的</b> ——
         * 定义成 String 会在对象形态时解析失败，定义成枚举又会在上游加新值时失效。
         */
        @JsonProperty("tool_choice") JsonNode toolChoice,

        /**
         * <b>G9 新增：{@code response_format}。</b>
         * <p>项目一要求模型最终输出 JSON（{@code {"type":"json_object"}}）。
         * 同样是"结构简单但会演进"的字段（后来又出现了 json_schema），
         * 所以用 JsonNode 透传，不在网关里维护一份迟早过时的镜像。
         */
        @JsonProperty("response_format") JsonNode responseFormat
) {

    /**
     * 兼容 G1~G6 时代 4 参数构造（测试里在用）。
     * <p>record 的规范构造器必须全部写出来，这个便利构造器让旧代码一行不用改 ——
     * <b>加字段时"旧调用方不动"是可以做到的，代价只是多一个构造器。</b>
     */
    public ChatCompletionRequest(String model, List<Message> messages, Boolean stream, String provider) {
        this(model, messages, stream, provider, null);
    }

    /** 兼容 G7 的 5 参数构造（加 max_tokens 时留下的）。 */
    public ChatCompletionRequest(String model, List<Message> messages, Boolean stream,
                                 String provider, Integer maxTokens) {
        this(model, messages, stream, provider, maxTokens, null, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String role,
            /**
             * <b>允许为 null</b>：带 {@code tool_calls} 的 assistant 消息在 OpenAI 协议里
             * content 就是 null（模型这一轮没有文字，只有"我要调用某个工具"）。
             */
            String content,
            @JsonProperty("tool_calls") List<ToolCall> toolCalls,
            /**
             * <b>G9：tool 消息回填给哪一次调用。</b>
             * 模型一次可能并发发起多个工具调用，结果必须按 id 对号入座 ——
             * 丢了它，第二轮请求会直接 400。
             */
            @JsonProperty("tool_call_id") String toolCallId,
            /** OpenAI 的可选字段，少数上游用它区分同名 participant，原样透传 */
            String name
    ) {
        /** 兼容原有两参数构造（网关内部、大量测试在用）。 */
        public Message(String role, String content) {
            this(role, content, null, null, null);
        }
    }
}
