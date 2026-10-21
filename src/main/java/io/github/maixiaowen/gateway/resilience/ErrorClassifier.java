package io.github.maixiaowen.gateway.resilience;

import io.github.maixiaowen.gateway.exception.UpstreamIdleTimeoutException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

/**
 * <b>G5：把"一个异常"翻译成"一个决策"。</b>
 *
 * <p><b>为什么分类要集中在一个类里，而不是散在各个 {@code catch} 块</b>：
 * 一旦分类规则散开，同一个 429 在 A 路径被当成"可重试"、在 B 路径被当成"直接失败"，
 * 是非常容易发生的 —— 而且不会报错，只会让行为随代码路径漂移。
 * <p><b>决策逻辑只有一个来源，才有资格被单元测试锁死。</b>
 * （{@code RoutingTest} 和 G5 的测试都直接验证这个映射。）
 *
 * <p><b>★ 分类之前必须回答的一个问题：流吐过字了吗？★</b>
 * <p>同一个异常，在"非流式"和"流式已经吐过第一个字"两种情况下，
 * <b>允许的动作完全不同</b>：前者可以换渠道重试，后者只能把错误交给用户。
 * 所以本类把 {@code afterFirstByte} 作为显式参数收进来 ——
 * <b>让"能不能重试"变成一个调用方必须回答的问题，而不是一个可以被忘记的细节。</b>
 */
public final class ErrorClassifier {

    private ErrorClassifier() {
    }

    /** 错误信息里最多保留多少上游响应体。太长会把日志系统打爆，太短又看不出原因 */
    private static final int MAX_BODY_IN_MESSAGE = 300;

    public static UpstreamFailure classify(String channel, Throwable error, boolean afterFirstByte) {
        // 已经分过类就只补上下文，不重新分类。
        // 否则 "WebClientResponseException -> UpstreamFailure" 的二次分类会把 kind 丢掉。
        if (error instanceof UpstreamFailure failure) {
            return failure.withContext(channel, afterFirstByte);
        }

        if (error instanceof CallNotPermittedException) {
            return new UpstreamFailure(UpstreamErrorKind.CIRCUIT_OPEN, channel, null,
                    afterFirstByte, null,
                    "渠道 [" + channel + "] 熔断器已打开，本次请求未发出", error);
        }

        if (error instanceof WebClientResponseException response) {
            return fromHttpStatus(channel, response, afterFirstByte);
        }

        if (error instanceof UpstreamIdleTimeoutException
                || error instanceof TimeoutException
                || hasCause(error, TimeoutException.class)
                || hasCause(error, ReadTimeoutException.class)
                || hasCause(error, ConnectTimeoutException.class)) {
            return new UpstreamFailure(UpstreamErrorKind.TIMEOUT, channel, null,
                    afterFirstByte, null,
                    "上游 [" + channel + "] 超时：" + safeMessage(error), error);
        }

        if (error instanceof WebClientRequestException) {
            // 连不上（DNS 失败、连接被拒、TLS 握手失败）
            return new UpstreamFailure(UpstreamErrorKind.UPSTREAM_UNAVAILABLE, channel, null,
                    afterFirstByte, null,
                    "上游 [" + channel + "] 不可达：" + safeMessage(error), error);
        }

        // 剩下的都是"我们自己的代码出问题"，不要重试 —— 重试自己的 bug 只会更慢地失败。
        // 特别注意：这里刻意不把未知异常当成"上游故障"，
        // 否则一个 NullPointerException 会被记到渠道头上，几次之后把健康渠道熔断掉。
        return new UpstreamFailure(UpstreamErrorKind.INTERNAL, channel, null,
                afterFirstByte, null,
                "网关内部错误（渠道 " + channel + "）：" + safeMessage(error), error);
    }

    private static UpstreamFailure fromHttpStatus(String channel,
                                                  WebClientResponseException response,
                                                  boolean afterFirstByte) {
        int status = response.getStatusCode().value();

        if (status == 429) {
            return new UpstreamFailure(UpstreamErrorKind.RATE_LIMITED, channel, status,
                    afterFirstByte, parseRetryAfter(response.getHeaders()),
                    "上游 [" + channel + "] 限流（429）", response);
        }
        if (status == 401 || status == 403) {
            return new UpstreamFailure(UpstreamErrorKind.AUTH_ERROR, channel, status,
                    afterFirstByte, null,
                    "上游 [" + channel + "] 鉴权失败（" + status + "），请检查该渠道的密钥配置。"
                            + "★ 这条不换渠道：换过去只会把配置问题藏起来 ★", response);
        }
        if (status >= 500) {
            return new UpstreamFailure(UpstreamErrorKind.UPSTREAM_UNAVAILABLE, channel, status,
                    afterFirstByte, null,
                    "上游 [" + channel + "] 返回 " + status + "：" + truncate(response.getResponseBodyAsString()),
                    response);
        }
        if (status >= 400) {
            // 400 / 404 / 422：请求本身不可成功。透传状态码和上游给的原因。
            return new UpstreamFailure(UpstreamErrorKind.CLIENT_ERROR, channel, status,
                    afterFirstByte, null,
                    "上游拒绝了这个请求（" + status + "）：" + truncate(response.getResponseBodyAsString()),
                    response);
        }

        // 1xx/2xx/3xx 走到这里说明是解析或别的环节出了问题，不是"上游回了错误码"
        return new UpstreamFailure(UpstreamErrorKind.INTERNAL, channel, status,
                afterFirstByte, null,
                "上游返回了非错误状态但处理失败（" + status + "）", response);
    }

    /**
     * 解析 Retry-After。
     *
     * <p>协议允许它是"秒数"或"HTTP 日期"。这里只处理秒数：
     * 日期格式需要拿服务器时间做减法，而<b>"谁的时钟"本身就是一个坑</b>
     * （见限流脚本里为什么时间必须从 Redis 取）。
     * 解析不出来就返回 null，让上层的退避策略用默认值 —— <b>不猜。</b>
     */
    private static Long parseRetryAfter(HttpHeaders headers) {
        String raw = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY_IN_MESSAGE
                ? body
                : body.substring(0, MAX_BODY_IN_MESSAGE) + "...(truncated)";
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
