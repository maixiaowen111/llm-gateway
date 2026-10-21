package io.github.maixiaowen.gateway.observability;

import io.github.maixiaowen.gateway.web.ClientIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * <b>G9：每个请求一条访问日志，并给这个请求一个可以在全链路里被引用的 ID。</b>
 *
 * <p><b>★ 为什么 requestId 是排障的第一个前提 ★</b>
 * <p>线上用户说"我刚刚那一下报错了"。没有 requestId，你能做的只有：
 * 按时间范围翻日志 → 几百条混在一起 → 猜哪一条是他的。
 * 有了 requestId，用户把错误信息里的 ID 发给你，<b>一条命令定位到那一次请求的全部日志</b>：
 * 路由到哪、上游报什么、计费扣了多少。<b>这一个字段省掉的时间，比整个日志系统加起来还多。</b>
 *
 * <p><b>★ 为什么接受客户端传进来的 requestId ★</b>
 * <p>业务方（项目一的 Agent）自己会生成 requestId 并向后传递。网关如果覆盖它，
 * 同一个业务流程在网关前后就有了两个 ID，反而切断了链路。
 * <b>但必须校验</b>：不校验的话，客户端塞进换行符就能伪造日志行（日志注入），
 * 塞进超长字符串就能把日志撑爆。这里只接受短且字符集受限的值。
 *
 * <p><b>为什么放在限流之前</b>：被限流的请求也需要被记录 ——
 * "哪个客户端一直在撞限流"是容量规划的重要输入。
 * 如果访问日志在限流之后，<b>最需要被看见的那部分流量恰好没有日志。</b>
 */
@Component
public class AccessLogWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogWebFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 放进 exchange attributes 的 key，供后续 handler/日志使用 */
    public static final String REQUEST_ID_ATTRIBUTE = AccessLogWebFilter.class.getName() + ".requestId";

    /** 允许透传的 requestId 最大长度。够长到能装下 UUID / traceId，又不至于撑爆日志 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolveRequestId(exchange);
        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        long startedAt = System.nanoTime();
        String clientId = ClientIdentity.resolve(exchange);

        return chain.filter(exchange)
                .doFinally(signal -> {
                    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
                    // statusCode 可能为 null（还没提交响应就被取消），此时如实打 "-"，
                    // 而不是假装 200 —— 假数据比缺数据更危险。
                    var status = exchange.getResponse().getStatusCode();
                    log.info("[访问日志] requestId={} client={} method={} path={} status={} duration={}ms signal={}",
                            requestId,
                            clientId,
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getPath().pathWithinApplication().value(),
                            status == null ? "-" : status.value(),
                            elapsedMillis,
                            signal);
                });
    }

    /**
     * 优先沿用上游传来的 requestId，否则生成一个。
     *
     * <p><b>校验规则是白名单不是黑名单</b>：只允许字母、数字和 {@code - _ . :}，
     * 长度不超过 64。黑名单（比如"去掉换行"）永远会漏 ——
     * 而白名单的失效方式很温和：不合格的值被替换成我们生成的随机 ID，仅此而已。
     */
    private String resolveRequestId(ServerWebExchange exchange) {
        String incoming = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (StringUtils.hasText(incoming)
                && incoming.length() <= MAX_REQUEST_ID_LENGTH
                && incoming.matches("[A-Za-z0-9_.:-]+")) {
            return incoming;
        }
        // 取 UUID 的前 12 位：完整 UUID 在日志里太占地方，12 位对单机已经足够不重复
        return UUID.randomUUID().toString().substring(0, 12);
    }

    @Override
    public int getOrder() {
        // 比限流（HIGHEST_PRECEDENCE + 100）更靠外：所有请求都要被记录
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
