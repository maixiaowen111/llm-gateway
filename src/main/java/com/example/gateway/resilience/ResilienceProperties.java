package com.example.gateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>G5：熔断与重试的可调参数。</b>
 *
 * <p><b>★ 为什么这些值必须外置，不能写死在代码里 ★</b>
 * <p>熔断器的参数不是"技术常量"，是<b>业务对可用性的判断</b>：
 * <ul>
 *   <li>窗口太小 → 一次抖动就熔断（误伤）；窗口太大 → 上游已经死了，你还在慢慢数错误；</li>
 *   <li>等待时间太短 → 半开时上游还没恢复，刚放过去的请求又失败（振荡）；</li>
 *   <li>并发阈值太低 → 正常流量也会被熔断器当成故障。</li>
 * </ul>
 * <b>这些值只能由"这个渠道平时长什么样"决定，而那是运维/业务知识，不是代码知识。</b>
 * 把它们写死在 Java 里，等于让写代码的人替运营做决定。
 *
 * <p><b>默认值为什么是这样</b>（给一个能直接跑起来的起点，不是最终答案）：
 * <ul>
 *   <li>窗口 10 次、至少 5 次才评估：<b>样本太少时算失败率没有意义</b>
 *       （2 次里错 1 次 = 50%，但可能只是巧合）；</li>
 *   <li>失败率 50%：一半都失败，基本可以确定不是偶发；</li>
 *   <li>打开后等 10 秒：给上游一个自愈窗口，又不至于让用户等太久；</li>
 *   <li>半开放 2 个请求：用极小的代价试探"它活了吗"。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "gateway.resilience", ignoreUnknownFields = false)
public record ResilienceProperties(
        /** 总开关。关掉时所有请求直接打上游，不做熔断/重试，只保留错误分类。 */
        boolean enabled,

        /** 失败率达到多少就打开（百分比，0~100） */
        int failureRateThreshold,

        /** 滑动窗口大小（按调用次数计） */
        int slidingWindowSize,

        /** 至少积累多少次调用才开始计算失败率 */
        int minimumNumberOfCalls,

        /** 打开后等多久进入半开（毫秒） */
        long waitDurationInOpenStateMillis,

        /** 半开状态下允许放过去几个试探请求 */
        int permittedNumberOfCallsInHalfOpenState,

        /** 同一个渠道上最多重试几次（不含第一次） */
        int maxRetryAttempts,

        /** 重试退避的基数（毫秒），按指数增长 */
        long retryBackoffMillis
) {

    public ResilienceProperties {
        failureRateThreshold = failureRateThreshold <= 0 ? 50 : failureRateThreshold;
        slidingWindowSize = slidingWindowSize <= 0 ? 10 : slidingWindowSize;
        minimumNumberOfCalls = minimumNumberOfCalls <= 0 ? 5 : minimumNumberOfCalls;
        waitDurationInOpenStateMillis = waitDurationInOpenStateMillis <= 0
                ? 10_000L
                : waitDurationInOpenStateMillis;
        permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState <= 0
                ? 2
                : permittedNumberOfCallsInHalfOpenState;
        maxRetryAttempts = Math.max(0, maxRetryAttempts);
        retryBackoffMillis = retryBackoffMillis <= 0 ? 100L : retryBackoffMillis;

        if (failureRateThreshold > 100) {
            throw new IllegalStateException(
                    "gateway.resilience.failure-rate-threshold 是百分比，必须在 0~100 之间，当前值 = "
                            + failureRateThreshold);
        }
        if (minimumNumberOfCalls > slidingWindowSize) {
            // 这是一个"永远触发不了"的配置：窗口里永远凑不够最小调用数，
            // 失败率永远不会被评估 = 熔断器形同虚设。必须炸，而不是让人以为它开着。
            throw new IllegalStateException(
                    "gateway.resilience.minimum-number-of-calls 不能大于 sliding-window-size，"
                            + "否则失败率永远算不出来，熔断器永远不会打开。当前 "
                            + minimumNumberOfCalls + " > " + slidingWindowSize);
        }
    }
}
