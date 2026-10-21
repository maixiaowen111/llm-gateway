package com.example.gateway.billing;

/**
 * <b>G7：计费组件不可用，且策略是 fail-closed。</b>对外是 <b>503</b>。
 *
 * <p><b>为什么是 503 而不是 500</b>：
 * 这不是"网关代码有 bug"，而是<b>网关依赖的一个外部组件（Redis）暂时不可用</b>。
 * 503 告诉客户端的负载均衡器/重试库："这次是临时性的，换个实例或稍后再试"；
 * 500 则会让告警指向"网关自身崩溃"，把排查方向带偏。
 *
 * <p><b>★ 为什么保留这个异常，而不直接让 Redis 的异常冒出去 ★</b>
 * <p>Redis 抛出来的异常叫 {@code RedisConnectionException}，它<b>不知道"当前策略是 fail-closed"</b>。
 * 如果不转换，这个异常会掉进通用异常处理器变成 500，或者更糟 ——
 * 被某个"未知异常不重试"的规则放过去，变成一个没人看得懂的错误。
 * <b>把"基础设施的失败"翻译成"业务上明确的拒绝"，是这一层的职责。</b>
 */
public class BillingUnavailableException extends RuntimeException {

    public BillingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
