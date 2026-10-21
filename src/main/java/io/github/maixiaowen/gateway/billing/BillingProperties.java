package io.github.maixiaowen.gateway.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>G7：计费的参数。</b>
 *
 * <p><b>★ 为什么"单价"必须放在配置里 ★</b>
 * <p>单价不是技术常量，是<b>商业合同</b>：不同客户、不同模型、不同促销期的价格都不一样。
 * 把它写进代码，每次调价都要发版 —— 而调价往往发生在"月底对账"这种不允许宕机的时刻。
 *
 * <p><b>⚠️ 本项目用"credits（信用点）"而不是"人民币"</b>：
 * 真实的计费系统要处理货币精度、汇率、税、退款政策、发票 —— 那是一个独立的系统。
 * 这里用一个抽象的整数单位，把<b>网关真正该负责的部分</b>（预扣 → 实结算 → 多退少补 → 对账）
 * 完整地做出来，并把"价格从哪来"明确地留给外部系统。
 * <b>不假装自己做了一个计费系统，比做一个假的计费系统诚实得多。</b>
 *
 * <p><b>★ 为什么 {@code fail-open} 默认 false ★</b>
 * <p>和限流器相反：限流失效是"可能超卖"，计费失效是<b>"不知道花了多少钱、也不知道该收谁的钱"</b>。
 * 后者在 to-B 场景里是财务事故。所以 Redis 不可用时<b>默认拒绝服务</b>（503），
 * 宁可短时间不可用，也不能产生无法对账的账单。
 * <p><b>这是一个可以被业务推翻的默认值</b> —— 有些公司会选可用性优先并接受"漏计"，
 * 那就把 {@code fail-open} 打开。<b>但必须是一个明确的决定，不是默认值碰巧如此。</b>
 */
@ConfigurationProperties(prefix = "gateway.billing", ignoreUnknownFields = false)
public record BillingProperties(
        /** 总开关。关闭时网关不预扣、不结算、不记录用量（本地开发默认关） */
        boolean enabled,

        /** Redis key 前缀。多环境共用 Redis 时必须隔离 */
        String keyPrefix,

        /** 单次 Redis 调用超时（毫秒）。理由同限流器：Lettuce 默认 60 秒太长 */
        long timeoutMillis,

        /** Redis 不可用时：true=放行但不计费（WARN），false=拒绝请求（503，默认） */
        boolean failOpen,

        /** 新客户端首次请求时自动发放的试用额度。真实系统里应该由账户系统充值 */
        long initialCredits,

        /** 每 1 个 prompt token 扣多少 credits */
        int promptTokenCredits,

        /** 每 1 个 completion token 扣多少 credits（输出通常比输入贵） */
        int completionTokenCredits,

        /** 客户端没传 max_tokens 时，预扣按这个上限估算（也用于裁剪默认值） */
        int defaultMaxTokens,

        /**
         * 预扣记录的存活时间（秒）。<b>这是一个"防泄漏"的设置</b>：
         * 如果网关在预扣之后崩溃（进程被 kill、机器掉电），那笔扣款就再也没有人来结算。
         * 设置 TTL 让它在 Redis 里自动消失 ——
         * <b>代价是"可能少扣一次"，但没有它，泄漏会无限累积。</b>
         */
        long reservationTtlSeconds
) {

    public BillingProperties {
        keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "gw:billing" : keyPrefix;
        timeoutMillis = timeoutMillis <= 0 ? 300 : timeoutMillis;
        initialCredits = initialCredits < 0 ? 0 : initialCredits;
        promptTokenCredits = promptTokenCredits <= 0 ? 1 : promptTokenCredits;
        completionTokenCredits = completionTokenCredits <= 0 ? 3 : completionTokenCredits;
        defaultMaxTokens = defaultMaxTokens <= 0 ? 1024 : defaultMaxTokens;
        reservationTtlSeconds = reservationTtlSeconds <= 0 ? 1800 : reservationTtlSeconds;
    }
}
