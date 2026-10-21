package io.github.maixiaowen.gateway.billing;

/**
 * <b>G7：一次"已经冻结额度"的凭据。</b>
 *
 * <p><b>为什么需要这个对象，而不是"扣款"一把梭</b>：
 * <p>大模型请求<b>不知道会花多少钱</b> —— 在模型开口之前，谁也不知道它会说 10 个字还是 1000 个字。
 * 所以计费只能两步走：
 * <ol>
 *   <li><b>预扣</b>：按"最坏情况"冻结一笔（prompt + max_tokens）；</li>
 *   <li><b>结算</b>：拿到真实 usage 后，<b>多退少补</b>。</li>
 * </ol>
 * 这个对象就是两步之间的<b>凭据</b>：结算时必须知道"当时冻了多少"，
 * 才能算出该退还是该补。它<b>只存在于这一次请求的生命周期里</b>，不落业务库。
 *
 * <p><b>★ {@code noop} 不是"没计费"，而是"计费没开" ★</b>
 * <p>计费关闭时返回一个 noop 凭据，让服务层的代码路径<b>只有一条</b>。
 * 如果让每个调用点都写 {@code if (billing.enabled()) { ... }}，
 * 那么"计费关闭"这条路径就永远不会被测试到，而它偏偏是本地开发时唯一在跑的那条。
 * <b>用空对象代替分支，是为了让分支两侧都能走同一段代码。</b>
 */
public record Reservation(
        String id,
        String clientId,
        long reservedCredits,
        boolean noop
) {

    /** 计费关闭时的占位凭据：结算/退款都是无操作 */
    public static Reservation noop(String clientId) {
        return new Reservation("noop", clientId, 0, true);
    }
}
