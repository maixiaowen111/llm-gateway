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
 * <p><b>★ G10 起，身份有两个来源，优先级严格区分 ★</b>
 * <ol>
 *   <li><b>已认证身份</b>（{@link #AUTHENTICATED_CLIENT_ATTRIBUTE}）：
 *       由 {@code ClientAuthWebFilter} 校验 API Key 后写入。<b>这是唯一可信的来源</b>；</li>
 *   <li><b>未认证的声明</b>（{@code X-Client-Id} / 来源 IP）：只在鉴权关闭时使用。
 *       它是<b>兼容模式</b>，不是安全边界 —— 客户端可以随便填。</li>
 * </ol>
 * <p>这个优先级写在<b>一个方法</b>里，是因为限流和计费必须按同一个主体算。
 * 如果某个组件自己去读 header，就会出现"A 按 key 算、B 按 header 算"的账目分裂。
 *
 * <p><b>⚠️ 已知限制（说实话，不假装解决）</b>：
 * 鉴权关闭且没有 {@code X-Client-Id} 时退化为来源 IP。网关前面如果有 nginx / SLB，
 * 拿到的是代理的 IP，所有人会被算成一个主体。
 * 正确做法是校验并解析 {@code X-Forwarded-For}，且只信任受信代理写入的值 ——
 * 这需要真实拓扑才能验证，见 {@code RateLimitWebFilter} 里同一段说明。
 */
public final class ClientIdentity {

    /** 客户端身份的声明头。★ 这是网关自己的约定，不是 OpenAI 协议的一部分 */
    public static final String CLIENT_ID_HEADER = "X-Client-Id";

    /**
     * 已认证身份的 exchange attribute key。
     *
     * <p><b>为什么不改写请求头</b>：请求头是客户端输入，可以被任何下游代码再次读取并误信；
     * attribute 属于服务端上下文，只有认证器写、业务代码读。
     * <b>把"客户端说的"和"服务端认定的"分开放，是避免越权的最简单办法。</b>
     */
    public static final String AUTHENTICATED_CLIENT_ATTRIBUTE =
            ClientIdentity.class.getName() + ".authenticatedClientId";

    /** 匿名请求的兜底标识。**不要**用 "unknown" 之外的随机值，否则每次请求都是一个新客户 */
    public static final String ANONYMOUS = "unknown";

    private ClientIdentity() {
    }

    public static String resolve(ServerWebExchange exchange) {
        // ① 已认证身份优先。鉴权开启时，这个 attribute 一定存在；
        //    鉴权关闭时它不存在，走下面的兼容分支。
        Object authenticated = exchange.getAttributes().get(AUTHENTICATED_CLIENT_ATTRIBUTE);
        if (authenticated instanceof String id && StringUtils.hasText(id)) {
            return id;
        }

        // ② 兼容模式：客户端自报。⚠️ 这一层的值不可信，只应在鉴权关闭时使用。
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
