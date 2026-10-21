package io.github.maixiaowen.gateway.limiter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>限流的全部可调参数（G4）。</b>和 {@code ProviderProperties} 同一套写法：record + 紧凑构造器。
 *
 * <p><b>★ 为什么 {@code enabled} 默认是 false —— 这是个刻意的取舍，不是忘了打开 ★</b>
 * <p>打开限流，意味着网关多了一个<b>必须活着</b>的外部依赖（Redis）。
 * 如果默认打开，那么"本机没起 Redis"的环境会<b>连启动都起不来</b> ——
 * 而"起不来"和"没限流"之间，我们要的是后者<b>能被看见、能被告警</b>，
 * 而不是前者把人直接挡在门外。
 *
 * <p>所以本项目的做法是三件事一起：
 * <ol>
 *   <li>默认 {@code false}，本地开发不需要 Redis；</li>
 *   <li>但关着的时候启动会打一条 <b>WARN</b> ——
 *       <b>"不做静默的能力缺失"是这里的底线</b>（见 {@code RateLimitConfig}）；</li>
 *   <li>一旦打开，启动时必须真的连得上 Redis，<b>连不上直接拒绝启动</b>，
 *       和 G3a 里"默认渠道必须存在"是同一条纪律：<b>把错误推到尽可能早的地方</b>。</li>
 * </ol>
 *
 * <p><b>⚠️ fail-open 的默认值是一条业务判断，不是技术判断</b>
 * <p>{@link #failOpen()} 决定"Redis 抖动时放行还是拒绝"。
 * 这一点没有技术上的正确答案，只有业务上的：
 * <ul>
 *   <li>大模型是<b>按 token 花钱</b>的，"放行"意味着真金白银可能被超卖；</li>
 *   <li>但反向的代价是：一次 3 秒的 Redis 抖动会让<b>所有</b>请求被拒，
 *       把一次局部降级放大成全面故障。</li>
 * </ul>
 * 我们默认 {@code true}（可用性优先），理由是"<b>保护性组件的失败，不该升级成网关的失败</b>"。
 * 但要清楚：<b>在成本敏感的团队里，这个默认值应该被业务推翻</b>。
 * 真正错的做法是把它写死 —— 那等于替业务做了一个它没参与的决定。
 *
 * @param enabled          是否启用。默认 false，理由见类注释
 * @param capacity         桶容量：单客户端最多能"先花未来"多少个请求 = 允许的突发量
 * @param refillPerSecond  每秒回填多少令牌 = 长期平均吞吐。<b>0 表示不回填</b>（一次性总配额）
 * @param failOpen         Redis 不可用时：true=放行、false=拒绝（返 503）
 * @param timeoutMillis    单次 Redis 调用的超时。
 *                         <b>★ 这个值不设会出大事：Lettuce 的默认命令超时是 60 秒。</b>
 *                         Redis "变慢"（不是挂掉）时，每个请求会卡 60 秒 ——
 *                         <b>那比限流失效严重得多</b>：限流器把网关自己拖死了。
 *                         "挂掉"是 fail-open 能兜住的，"变慢"才是真正杀死网关的那种故障。
 * @param keyPrefix        Redis key 前缀。多环境共用一个 Redis 时用它隔离
 *
 * <p><b>★★★ ignoreUnknownFields = false：把"键名拼错"变成一次响亮的启动失败 ★★★</b>
 * <p>它的默认值是 {@code true} —— "配置里多出来的键，我装作没看见"。
 * 听起来很宽容，实际后果是：<b>你把键名拼错了，它一声不吭，字段拿到默认值，
 * 然后带着一份错误的配置一路跑到生产。</b>
 *
 * <p><b>本项目真实踩过一次，而且踩得非常典型：</b>
 * <pre>
 *   Java 字段名  timeoutMillis   → 宽松绑定归一化成 "timeoutmillis"
 *   YAML  键名   timeout-ms      → 宽松绑定归一化成 "timeoutms"
 * </pre>
 * 归一化的规则是"<b>转小写 + 去掉分隔符</b>"，它<b>不会把 {@code ms} 展开成 {@code millis}</b>。
 * 结果：同一个前缀下的 {@code capacity} / {@code refill-per-second} / {@code key-prefix}
 * 全都绑得好好的，<b>只有这一个键被整个忽略</b>，{@code long} 拿到默认值 {@code 0}。
 * <p>更值得记住的是<b>排查时被带偏的那一步</b>：错误信息说"timeout-ms 当前值 = 0"，
 * 第一反应必然是"YAML 缩进错了吧"，因为"键在文件里明明写着 200"。而 {@code grep | cat -A}
 * 一看，缩进完全正确 —— <b>因为问题从来不在缩进，在这个键压根没被识别。</b>
 * <p>打开这个开关之后，同一件事的表达方式变成启动时一句
 * {@code Property 'gateway.rate-limit.timeout-ms' is not valid for this context}：
 * <b>三十秒定位，而不是半小时。</b>
 *
 * <p><b>★ 可推广的原则：默认值存在的意义是"少写配置"，不是"可以写错配置"。★</b>
 * 凡是"由人手写、而程序又要拿它做决定"的配置项，宽容都是债。
 *
 * <p><b>⚠️ 代价也要说清楚（这是取舍，不是纯粹的升级）</b>：
 * 开了之后 YAML 和 Java 字段必须同步改 —— 老配置里留着已删除的键会直接启动失败。
 * 对内部服务这是好事（逼迫仓库里那份 YAML 保持正确、逼迫删除动作被显式做掉）；
 * 但如果哪天要做"旧版 YAML 喂给新版 jar"的向后兼容，就要重新评估这一条。
 */
@ConfigurationProperties(prefix = "gateway.rate-limit", ignoreUnknownFields = false)
public record RateLimitProperties(
        boolean enabled,
        int capacity,
        double refillPerSecond,
        boolean failOpen,
        long timeoutMillis,
        String keyPrefix
) {

    /** key 前缀的兜底值。为空时用它，避免拼出 ":client-1" 这种没有命名空间的 key */
    private static final String DEFAULT_KEY_PREFIX = "gw:rl";

    /**
     * <b>紧凑构造器：绑定完立刻校验，让配置错误在启动时就暴露。</b>
     *
     * <p><b>★ 为什么只在 {@code enabled} 时才校验</b>：
     * 关着的时候不会去读 capacity，为它报错是"因为一个用不到的字段拒绝启动" ——
     * 那会逼着每个不关心限流的环境也去填一堆值。<b>校验的严格程度应该匹配这个值会不会被真的用到。</b>
     *
     * <p><b>★ {@code capacity <= 0} 为什么必须是硬错误（而不是"纠正成默认值"）</b>：
     * 容量 0 的桶意味着<b>拒绝一切请求</b>。把它"悄悄纠正成 20"，你就永远不知道
     * 有个环境的配置写错了 —— <b>明明写了 0、实际跑了 20，这比启动失败难查一百倍。</b>
     */
    public RateLimitProperties {
        keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? DEFAULT_KEY_PREFIX : keyPrefix;

        if (enabled) {
            if (capacity <= 0) {
                throw new IllegalStateException(
                        "gateway.rate-limit.capacity 必须大于 0，当前值 = " + capacity
                                + "。容量 0 的桶会拒绝所有请求 —— 这几乎一定是配置写错了，"
                                + "而不是你真的想禁掉全部流量（要禁流量请把 enabled 设成 false）。");
            }
            if (refillPerSecond < 0) {
                throw new IllegalStateException(
                        "gateway.rate-limit.refill-per-second 不能为负，当前值 = " + refillPerSecond
                                + "。填 0 表示不回填（一次性总配额），这是合法的；负数没有意义。");
            }
            if (timeoutMillis <= 0) {
                // ⚠️ 报错信息里写的键名必须和 YAML 里【真正要写的那个】一致：
                //    这里如果是 timeout-ms，就等于把人往一个绑不上的键上引 ——
                //    照着报错去改，改完还是 0。见类注释里那段踩坑经过。
                throw new IllegalStateException(
                        "gateway.rate-limit.timeout-millis 必须大于 0，当前值 = " + timeoutMillis + "。"
                                + "（注意键名是 timeout-millis 而不是 timeout-ms："
                                + "宽松绑定不会把 ms 展开成 millis，写成 timeout-ms 会被静默忽略）");
            }
        }
    }
}
