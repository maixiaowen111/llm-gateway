package com.example.gateway.exception;

import com.example.gateway.dto.OpenAiErrorResponse;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
