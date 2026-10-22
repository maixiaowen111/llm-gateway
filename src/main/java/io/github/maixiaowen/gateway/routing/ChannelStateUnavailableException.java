package io.github.maixiaowen.gateway.routing;

/**
 * <b>渠道状态的权威存储（Redis）不可用。</b>
 *
 * <p><b>★ 它和"限流器不可用"的处理方向正好相反 ★</b>
 * <p>限流器挂了，默认可以 {@code fail-open}（放行）—— 因为"少限一次"的代价
 * 是可能超卖，而"全部拒绝"的代价是整个服务不可用。
 * <p>但<b>改渠道开关</b>这件事不能 fail-open：如果 Redis 挂了还让管理员"改成功"，
 * 实际只有当前实例改了、其它实例不知道 —— <b>管理员会以为流量已经切走，
 * 而一半流量还在往故障渠道上打。这种"看起来成功、实际没生效"比直接报错危险得多。</b>
 * <p>所以写入失败必须显式告诉调用方 <b>503</b>，让它重试或换手段，
 * 而不是给一个假的成功。
 */
public class ChannelStateUnavailableException extends RuntimeException {

    public ChannelStateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
