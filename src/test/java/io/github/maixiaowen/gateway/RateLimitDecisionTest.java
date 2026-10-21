package io.github.maixiaowen.gateway;

import io.github.maixiaowen.gateway.limiter.RateLimitDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>G4：限流结果类型的纯逻辑测试。</b>
 *
 * <p><b>★ 为什么这种"只有几行 getter"的类也值得单独测 ★</b>
 * <p>因为这里藏着一条<b>会被写进 HTTP 头、然后被客户端的重试库照着执行</b>的换算规则
 * （毫秒 → Retry-After 秒）。它一旦算错，症状是"调用方按我们说的等，然后又被拒一次"——
 * <b>用户看到的是一次莫名其妙的重试风暴，而没有任何地方会报错。</b>
 * <p>判据很简单：<b>一段逻辑的输出会被别人依赖着做决定，它就值得一条测试。</b>
 * 跟它有多少行代码没关系。
 *
 * <p>本类不起 Spring、不连 Redis，全部在微秒级跑完 ——
 * <b>换算规则是纯逻辑，纯逻辑就该用纯测试守。</b>
 */
@DisplayName("G4 限流结果与 Retry-After 换算")
class RateLimitDecisionTest {

    @Test
    @DisplayName("毫秒 → 秒【向上取整】—— 向下取整会让客户端照着等还过不去")
    void retryAfterSecondsRoundsUp() {
        // 1399ms：向下取整是 1 秒。客户端 1 秒后重试，桶里还差一点点，又被拒一次。
        // Retry-After 的意义是「照着这个数等就一定能过」，
        // 向下取整会让它变成「照着等也过不去」—— 那这个头还不如不发。
        assertThat(RateLimitDecision.denied(0, 1399).retryAfterSeconds())
                .as("宁可让客户端多等 1 秒，也不要让它白跑一趟")
                .isEqualTo(2);

        assertThat(RateLimitDecision.denied(0, 1000).retryAfterSeconds())
                .as("刚好整秒不该被多算 1 秒")
                .isEqualTo(1);

        assertThat(RateLimitDecision.denied(0, 1).retryAfterSeconds())
                .as("1 毫秒也必须报 1 秒 —— 报 0 秒等于让客户端立刻重试，直接形成重试风暴")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("桶永不回填（refill=0）→ 给一个偏保守的建议值，而不是省略这个头")
    void neverRefillsFallsBackToConservativeRetryAfter() {
        RateLimitDecision decision = RateLimitDecision.denied(0, RateLimitDecision.NEVER_REFILLS);

        assertThat(decision.retryAfterSeconds())
                .as("省略 Retry-After 的话，标准客户端会用【自己的默认退避】——"
                        + "通常比 60 秒激进得多，于是在「等多久都没用」的情况下反复重试。"
                        + "给一个明确的、偏保守的数字，比不给强")
                .isEqualTo(60L);
    }

    @Test
    @DisplayName("三态语义：ALLOWED / DENIED / UNAVAILABLE 必须能被区分开")
    void outcomesAreDistinguishable() {
        assertThat(RateLimitDecision.allowed(7).allowed()).isTrue();
        assertThat(RateLimitDecision.allowed(7).outcome())
                .isEqualTo(RateLimitDecision.Outcome.ALLOWED);

        assertThat(RateLimitDecision.denied(0, 500).allowed()).isFalse();
        assertThat(RateLimitDecision.denied(0, 500).outcome())
                .as("DENIED 是【调用方】的问题 → 429")
                .isEqualTo(RateLimitDecision.Outcome.DENIED);

        assertThat(RateLimitDecision.unavailable().allowed()).isFalse();
        assertThat(RateLimitDecision.unavailable().outcome())
                .as("UNAVAILABLE 是【我们】的问题 → 503。"
                        + "和 DENIED 共用 429 的话，客户端会对一次 Redis 故障做无效重试，"
                        + "值班的人会去查「谁在刷接口」")
                .isEqualTo(RateLimitDecision.Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("水位未知 ≠ 水位为零")
    void unknownRemainingIsNotZero() {
        assertThat(RateLimitDecision.allowedWithoutQuota().allowed())
                .as("fail-open：放行")
                .isTrue();
        assertThat(RateLimitDecision.allowedWithoutQuota().remaining())
                .as("但必须报「未知」而不是「0」。"
                        + "报 0 是在撒谎（「额度已用尽」），会让监控把一次 Redis 故障"
                        + "描述成一次流量高峰 —— 排查方向从一开始就跑偏了")
                .isEqualTo(RateLimitDecision.UNKNOWN_REMAINING)
                .isNegative();
    }
}
