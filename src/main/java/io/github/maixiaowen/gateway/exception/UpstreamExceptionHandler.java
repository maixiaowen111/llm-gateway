package io.github.maixiaowen.gateway.exception;

import io.github.maixiaowen.gateway.billing.BillingUnavailableException;
import io.github.maixiaowen.gateway.billing.InsufficientQuotaException;
import io.github.maixiaowen.gateway.dto.OpenAiErrorResponse;
import io.github.maixiaowen.gateway.observability.GatewayMetrics;
import io.github.maixiaowen.gateway.resilience.UpstreamErrorKind;
import io.github.maixiaowen.gateway.resilience.UpstreamFailure;
import io.github.maixiaowen.gateway.routing.NoHealthyChannelException;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 上游异常出口。G1-1d 的验收物。
 *
 * <p><b>它解决什么</b>：1d 配上超时后，超时确实会触发 —— 但默认会变成 500，
 * 响应体是 Spring 的默认格式，客户端 SDK 解析不了，运维也看不出是"上游慢"还是"自己崩了"。
 * 超时对外必须是 <b>504</b>。
 *
 * <p><b>状态码怎么选（面试会问）</b>
 * <ul>
 *   <li><b>504 Gateway Timeout</b>：连上了但上游没及时回（读超时），
 *       或者压根连不上（连接超时）。nginx 对 {@code proxy_connect_timeout} 和
 *       {@code proxy_read_timeout} 也都返回 504，跟它对齐。</li>
 *   <li><b>502 Bad Gateway</b>：上游<b>回了</b>，但回的是错误响应或垃圾。
 *       「没等到」和「等到了坏东西」是两件事，别混。</li>
 * </ul>
 *
 * <p><b>本类刻意不做的</b>：判断"这次失败该不该换渠道重试"。那是 G5 的错误分类 + 故障转移，
 * 它要读的输入就是这里填的 {@code code} 字段。
 */
@RestControllerAdvice
public class UpstreamExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstreamExceptionHandler.class);

    private final GatewayMetrics metrics;

    public UpstreamExceptionHandler(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 超时与连接失败。捕获三种类型是因为异常可能被包在不同层：
     * WebClient 通常包成 {@link WebClientRequestException}，个别路径会直接抛 netty 原生异常。
     */
    @ExceptionHandler({
            WebClientRequestException.class,
            ReadTimeoutException.class,
            ConnectTimeoutException.class
    })
    public ResponseEntity<OpenAiErrorResponse> handleUpstreamFailure(Throwable e) {
        if (hasCause(e, ReadTimeoutException.class)) {
            log.warn("[上游读超时] {}", e.getMessage());
            return build(HttpStatus.GATEWAY_TIMEOUT, "上游响应超时，请稍后重试", "upstream_read_timeout");
        }
        if (hasCause(e, ConnectTimeoutException.class)) {
            log.warn("[上游连接超时] {}", e.getMessage());
            return build(HttpStatus.GATEWAY_TIMEOUT, "上游连接超时，请稍后重试", "upstream_connect_timeout");
        }
        // 连得上但握手/传输层直接失败（DNS 解析不了、连接被拒、TLS 失败……）
        log.warn("[上游不可达] {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, "上游服务不可用", "upstream_unreachable");
    }

    /**
     * 上游<b>回了</b>，但状态码是错误。跟超时是两回事。
     * <p>
     * 1d 只做最朴素的转换：一律 502，并把上游状态码记进日志。
     * G5 会在这里分叉 —— 429 该退避重试、5xx 该转移渠道、4xx 该直接透传给客户端。
     */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<OpenAiErrorResponse> handleUpstreamHttpError(WebClientResponseException e) {
        log.warn("[上游返回错误] status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
        return build(HttpStatus.BAD_GATEWAY,
                "上游服务返回错误（HTTP " + e.getStatusCode().value() + "）",
                "upstream_http_error");
    }

    /**
     * <b>请求的渠道不存在（G3-3a 新增）—— 注意它是 400，不是 502/504。</b>
     *
     * <p>这里最值得记的是<b>状态码的选择</b>：
     * <ul>
     *   <li>{@code 502} = <b>上游回了坏东西</b>；{@code 504} = <b>没等到上游</b>。
     *       两者都意味着"轮到上游了，是它的问题"。</li>
     *   <li>而渠道名写错，<b>压根没轮到上游</b> —— 是我们连"该找谁"都没搞清楚。</li>
     * </ul>
     * 这属于<b>请求方的问题</b>，是 400。
     *
     * <p><b>为什么这个区分在运维上很值钱</b>：如果这里也返回 502，
     * 告警会显示"上游故障率上升" —— 值班的人会去查上游、联系厂商，
     * 而真正的问题是<b>调用方把渠道名拼错了</b>。
     * <b>错误分类的意义不是"好看"，是让排查方向不跑偏。</b>
     */
    @ExceptionHandler(UnknownProviderException.class)
    public ResponseEntity<OpenAiErrorResponse> handleUnknownProvider(UnknownProviderException e) {
        log.warn("[渠道不存在] {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), "unknown_provider");
    }

    /**
     * <b>G5 的主出口：已经分好类的上游失败。</b>
     *
     * <p><b>为什么它必须排在 {@link WebClientResponseException} 之前被匹配</b>：
     * 服务层在 {@code onErrorMap} 里已经把原始异常<b>换成了</b> {@link UpstreamFailure}，
     * 所以正常路径到这里都是本类型。原始类型那条 handler 留着兜"没经过服务层的调用"
     * （将来可能有别的入口），<b>但它是兜底，不是主路径</b>。
     *
     * <p><b>★ 状态码怎么给 ★</b>：用 {@link UpstreamFailure#clientStatus()} 的策略 ——
     * 400/404/422 透传原状态码，其余按分类给（429 / 502 / 504 / 503）。
     * 401 对外是 502：<b>密钥是我们自己的问题，不该让客户端以为自己的 key 错了。</b>
     *
     * <p><b>★ Retry-After 必须透出去 ★</b>：上游告诉我们"30 秒后再来"，
     * 如果我们在出口丢掉这个头，客户端的重试库就只能猜 ——
     * 而它通常猜得比上游允许的更早，于是把一个"稍等就能好"的限流
     * 变成了一波持续打向已经过载的上游的重试风暴。
     */
    @ExceptionHandler(UpstreamFailure.class)
    public ResponseEntity<OpenAiErrorResponse> handleClassifiedFailure(UpstreamFailure e) {
        HttpStatus status = e.clientStatus();
        log.warn("[上游失败] channel={} kind={} 对外={} 已吐字节={} 原因={}",
                e.channel(), e.kind(), status.value(), e.afterFirstByte(), e.getMessage());

        HttpHeaders headers = new HttpHeaders();
        if (e.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }

        return ResponseEntity.status(status)
                .headers(headers)
                .body(new OpenAiErrorResponse(new OpenAiErrorResponse.ErrorBody(
                        e.getMessage(), errorType(e.kind()), e.kind().code(), null)));
    }

    /**
     * <b>G7：余额不足 → 402 Payment Required。</b>
     *
     * <p>选 402 而不是 429/403 的完整理由见 {@link InsufficientQuotaException}。
     * 这里只补一句工程上的取舍：<b>余额、所需、还差多少，全部写进 message</b>。
     * 客户端开发者看到"还差 120 credits"能立刻做充值判断，
     * 而看到"余额不足"只能去问人 —— <b>错误信息是 API 的一部分，不是日志。</b>
     */
    @ExceptionHandler(InsufficientQuotaException.class)
    public ResponseEntity<OpenAiErrorResponse> handleInsufficientQuota(InsufficientQuotaException e) {
        log.warn("[计费-余额不足] balance={} required={} shortfall={}",
                e.balance(), e.required(), e.shortfall());
        metrics.billingRejected("insufficient_quota");
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(new OpenAiErrorResponse(new OpenAiErrorResponse.ErrorBody(
                        e.getMessage(), "insufficient_quota", "insufficient_quota", null)));
    }

    /**
     * <b>G7：计费组件不可用且 fail-closed → 503。</b>
     *
     * <p>这是"依赖暂时坏了"，不是"请求有问题"：客户端应该<b>稍后重试</b>，
     * 而不是修改请求。503 正好表达这个语义；500 会让人去查网关代码。
     * <p><b>这里不返回 402。</b>402 意味着"你没钱"，503 意味着"我现在收不了钱"——
     * 两者都会让请求失败，但客户端该做的事完全不同：
     * 前者要去充值（重试没用），后者要等一会（充值也没用）。
     */
    @ExceptionHandler(BillingUnavailableException.class)
    public ResponseEntity<OpenAiErrorResponse> handleBillingUnavailable(BillingUnavailableException e) {
        log.error("[计费-组件不可用] {}", e.getMessage(), e);
        metrics.billingRejected("billing_unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new OpenAiErrorResponse(new OpenAiErrorResponse.ErrorBody(
                        "计费组件暂不可用，请稍后重试。本次请求未被扣费。",
                        "server_error", "billing_unavailable", null)));
    }

    /**
     * <b>所有候选渠道都不可用（G5/G6）→ 503。</b>
     *
     * <p>这条不是"上游故障"，是<b>"网关当前没有一条路能走"</b>：
     * 渠道全被禁用、或者熔断器全开着。用 502 会让告警把它算进"上游故障率"，
     * 而我们真正要查的是<b>自己的渠道配置和熔断状态</b>。
     */
    @ExceptionHandler(NoHealthyChannelException.class)
    public ResponseEntity<OpenAiErrorResponse> handleNoHealthyChannel(NoHealthyChannelException e) {
        log.error("[无可用渠道] {}", e.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), "no_healthy_channel");
    }

    /** OpenAI 的 {@code type} 字段只有几种值，客户端 SDK 按它决定"要不要重试" */
    private String errorType(UpstreamErrorKind kind) {
        return switch (kind) {
            case CLIENT_ERROR -> "invalid_request_error";
            case AUTH_ERROR -> "upstream_auth_error";
            case RATE_LIMITED -> "rate_limit_error";
            default -> "server_error";
        };
    }

    private ResponseEntity<OpenAiErrorResponse> build(HttpStatus status, String message, String code) {
        return ResponseEntity.status(status)
                .body(new OpenAiErrorResponse(
                        new OpenAiErrorResponse.ErrorBody(message, "upstream_error", code, null)));
    }

    /**
     * 沿 cause 链找指定类型。
     * <p>
     * 不能只检查"最底层的那一个" —— 超时异常常常被包在中间层，
     * 而最底层可能是 {@code ClosedChannelException} 之类的次生异常。
     */
    private boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur)) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return false;
    }
}
