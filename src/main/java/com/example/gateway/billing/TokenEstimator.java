package com.example.gateway.billing;

import com.example.gateway.dto.ChatCompletionRequest;

import java.util.List;

/**
 * <b>G7：在"没有 usage"时估算 token 数。</b>
 *
 * <p><b>为什么需要一个"估算"</b>：
 * <ul>
 *   <li><b>预扣</b>发生在<b>请求发出去之前</b> —— 那时上游还没回话，根本没有 usage，
 *       但我们必须先冻结一笔额度，否则"多头并发"会把余额花超；</li>
 *   <li><b>有些上游的流式响应压根不给 usage</b>（OpenAI 协议默认不给，
 *       要客户端额外声明 {@code stream_options.include_usage}；第三方兼容实现更是五花八门）。
 *       如果拿不到 usage 就不结算，那等于给这些渠道开了免费通道。</li>
 * </ul>
 *
 * <p><b>★ 估算规则：按字符数 / 2 ★</b>
 * <p>经验值：英文大约 4 个字符 1 个 token，中文大约 1 个字 1 个 token
 * （具体取决于分词器）。取 2 作为折中，<b>并刻意让它略微高估</b> ——
 * 预扣多扣一点，结算时会退回去；预扣少扣了，就只能指望"事后补扣"。
 * <b>在计费里，先多扣、后退款，永远比先少扣、后追债容易。</b>
 *
 * <p><b>⚠️ 它是估算，不是计量</b>：
 * <ul>
 *   <li>有 usage 时<b>永远以 usage 为准</b>；</li>
 *   <li>用估算结算时必须打 WARN ——
 *       这代表"这个渠道的账单精确度下降了"，是运维需要知道的事实；</li>
 *   <li>不同模型的分词器不同，估算会有系统性偏差（比如代码和 JSON 的 token 密度很高）。
 *       <b>想要准确，只有一条路：用厂商的 tokenizer 本地算 —— 那是另一个工程问题。</b></li>
 * </ul>
 */
public final class TokenEstimator {

    /** 平均多少个字符算一个 token。见类注释里的取舍 */
    private static final double CHARS_PER_TOKEN = 2.0;

    private TokenEstimator() {
    }

    /**
     * 估算请求侧 token。
     *
     * <p>它包含 role 之类的协议开销吗？<b>不包含，也不该精确包含</b> ——
     * 预扣只需要量级正确，追到个位数没有意义（真正的账由 usage 决定）。
     * 但把每条消息额外加 4 个 token 是个便宜且合理的近似（role + 分隔符），
     * 我这里选择加，因为低估的风险比高估大。
     */
    public static int estimatePromptTokens(ChatCompletionRequest request) {
        List<ChatCompletionRequest.Message> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return 1;
        }
        int chars = 0;
        for (ChatCompletionRequest.Message message : messages) {
            chars += message.content() == null ? 0 : message.content().length();
            chars += message.role() == null ? 0 : message.role().length();
        }
        return Math.max(1, (int) Math.ceil(chars / CHARS_PER_TOKEN) + messages.size() * 4);
    }

    /**
     * 估算已经生成的内容的 token 数。
     *
     * <p>流式断流 / 上游不给 usage 时用它。
     * 空内容返回 0 —— "什么都没生成"不该收费。
     */
    public static int estimateCompletionTokens(CharSequence generatedContent) {
        if (generatedContent == null || generatedContent.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(generatedContent.length() / CHARS_PER_TOKEN));
    }
}
