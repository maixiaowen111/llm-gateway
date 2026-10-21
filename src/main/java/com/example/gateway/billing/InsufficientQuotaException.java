package com.example.gateway.billing;

/**
 * <b>G7：余额不足。</b>对外是 <b>402 Payment Required</b>。
 *
 * <p><b>为什么是 402，而不是 429 或 403</b>：
 * <ul>
 *   <li><b>429 Too Many Requests</b> 的语义是"你太快了，慢点再来" ——
 *       客户端会<b>重试</b>。余额不足时重试一万次也还是没钱，只会制造无效流量；</li>
 *   <li><b>403 Forbidden</b> 是"你没有权限" —— 它是权限问题，需要管理员介入；
 *       而余额不足是<b>可自助解决</b>的（充值）。</li>
 * </ul>
 * <b>402 是 HTTP 里专门为"先付款"留的码</b>，OpenAI 官方在余额不足时用的就是 402 +
 * {@code insufficient_quota}。<b>错误码选对了，客户端的重试/告警逻辑才会做对的事。</b>
 */
public class InsufficientQuotaException extends RuntimeException {

    private final long balance;
    private final long required;

    public InsufficientQuotaException(String clientId, long balance, long required) {
        super("客户端 [" + clientId + "] 的额度不足：需要 " + required + " credits，当前余额 " + balance
                + "。请充值后重试。");
        this.balance = balance;
        this.required = required;
    }

    public long balance() {
        return balance;
    }

    public long required() {
        return required;
    }

    /** 给客户端一个"还差多少"的明确数字，比只返回"余额不足"有用得多 */
    public long shortfall() {
        return Math.max(0, required - balance);
    }
}
