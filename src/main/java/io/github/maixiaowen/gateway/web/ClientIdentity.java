package io.github.maixiaowen.gateway.web;

import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * <b>G7：一次请求的"计费主体"是谁。</b>
 *
 * <p><b>★ 为什么这个判断必须只有一个实现 ★</b>
 * <p>限流和计费<b>必须按同一个主体算</b>。如果限流按 IP、计费按 header，
 * 客户端只要多发一个 header 就能换一个"新用户"去花别人的钱；
 * 反过来（限流按 header、计费按 IP）则会让同一个办公室的人共用一个账单，
 * 月底谁也说不清是谁花的。
 * <p><b>先有身份，才有限流和计费 —— 两者只是同一个人身上的两种约束。</b>
 *
 * <p><b>⚠️ 已知限制（说实话，不假装解决）</b>：
 * 没有 {@code X-Client-Id} 时退化为来源 IP。网关前面如果有 nginx / SLB，
 * 拿到的是代理的 IP，所有人会被算成一个主体。
 * 正确做法是校验并解析 {@code X-Forwarded-For}，且只信任受信代理写入的值 ——
 * 这需要真实拓扑才能验证，见 {@code RateLimitWebFilter} 里同一段说明。
 */
public final class ClientIdentity {

    /** 客户端身份的声明头。★ 这是网关自己的约定，不是 OpenAI 协议的一部分 */
    public static final String CLIENT_ID_HEADER = "X-Client-Id";

    /** 匿名请求的兜底标识。**不要**用 "unknown" 之外的随机值，否则每次请求都是一个新客户 */
    public static final String ANONYMOUS = "unknown";

    private ClientIdentity() {
    }

    public static String resolve(ServerWebExchange exchange) {
        String declared = exchange.getRequest().getHeaders().getFirst(CLIENT_ID_HEADER);
        if (StringUtils.hasText(declared)) {
            return declared.trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? ANONYMOUS
                : remote.getAddress().getHostAddress();
    }
}
