package com.example.gateway.routing;

/**
 * <b>G5/G6：所有候选渠道都不可用。</b>对外是 <b>503</b>，不是 502。
 *
 * <p>为什么不是 502：502 的语义是"上游回了坏东西"，而这里的情况是
 * <b>我们没有任何一条路可走</b> —— 所有渠道都被禁用，或者熔断器全开着。
 * 这是网关自己的容量/健康问题，503 更准确，也更能让客户端的重试库做出正确判断。
 *
 * <p><b>信息里必须列出"查了哪些渠道、各自为什么不可用"</b>：
 * 值班的人需要的不是"失败了"，而是"为什么没有一条能用"。
 */
public class NoHealthyChannelException extends RuntimeException {

    public NoHealthyChannelException(String message) {
        super(message);
    }
}
