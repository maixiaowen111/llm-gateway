package io.github.maixiaowen.gateway.auth;

import io.github.maixiaowen.gateway.dto.OpenAiErrorResponse;
import io.github.maixiaowen.gateway.web.ClientIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * <b>G10：客户端鉴权入口 —— 在限流和计费之前，先证明"你是谁"。</b>
 *
 * <p><b>★ 为什么顺序必须是「认证 → 限流 → 计费」★</b>
 * <pre>
 *   认证（本类）     你是谁？            ← 身份是后面两件事的前提
 *   限流（+100）     你能打多快？        ← 按已认证的 clientId 计数
 *   计费（服务层）   你要付多少钱？      ← 记到已认证的 clientId 账上
 * </pre>
 * 顺序反过来的话，限流器会先按一个<b>还没被证明的身份</b>扣令牌 ——
 * 攻击者只要每次换一个 {@code X-Client-Id}，就能拿无限个新桶。
 * <b>认证不是一个"可选的安全加固"，它是限流和计费能成立的地基。</b>
 *
 * <p><b>★ 为什么只保护 {@code /v1/} 前缀，而不是"保护所有路径"★</b>
 * <ul>
 *   <li>{@code /ping}、{@code /actuator/health} 必须匿名可达 ——
 *       探针被鉴权挡住，K8s 会不断重启一个其实健康的 Pod；</li>
 *   <li>{@code /admin/**} 有自己的管理员 token（见 {@code AdminController}），
 *       两套身份体系不应该互相混用：<b>客户端 key 不该拥有管理权限，
 *       管理员 token 也不该能花客户的钱。</b></li>
 * </ul>
 * <p>按<b>前缀</b>而不是按"精确路径列表"保护，是为了让新加的 {@code /v1/xxx}
 * 默认就被保护 —— <b>白名单的失效方向必须是"多保护"，不能是"忘了保护"。</b>
 *
 * <p><b>★ 为什么不认 {@code X-Client-Id} 了 ★</b>
 * <p>认证开启后，客户端身份<b>只能</b>来自 API Key。请求里如果还带着
 * {@code X-Client-Id}，它会被<b>忽略</b>（并在不一致时打一条 WARN）——
 * 这是为了兼容老客户端不让它们直接 400，但<b>绝不能让这个头参与任何决策</b>。
 * 否则"鉴权"只是给伪造的身份加了一道装饰。
 */
public class ClientAuthWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ClientAuthWebFilter.class);

    /** 需要客户端身份的路径前缀。只有业务流量需要，管理/探针各有自己的机制 */
    public static final String PROTECTED_PREFIX = "/v1/";

    /** RFC 6750 标准的 Bearer 方案 */
    private static final String BEARER_PREFIX = "bearer ";

    /** 很多 OpenAI 兼容 SDK 用这个头传 key；支持它是为了"客户端零改造" */
    public static final String API_KEY_HEADER = "X-Api-Key";

    private static final String REALM = "llm-gateway";

    private final ApiKeyStore keys;
    private final ObjectMapper objectMapper;

    public ClientAuthWebFilter(ApiKeyStore keys, ObjectMapper objectMapper) {
        this.keys = keys;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith(PROTECTED_PREFIX)) {
            return chain.filter(exchange);
        }

        KeyExtraction extraction = extract(exchange);
        if (extraction.errorCode() != null) {
            return unauthorized(exchange, extraction.errorCode(), extraction.message());
        }

        Optional<String> clientId = keys.authenticate(extraction.key());
        if (clientId.isEmpty()) {
            // ★ 日志里绝不打印 key，也不打印它的前缀 ★
            //   哪怕只打前 8 位，配合日志收集系统也足以拼出可用凭证的一部分。
            //   这里只记录"来自哪个 IP 的哪次尝试失败了"。
            log.warn("[鉴权-拒绝] 无效 API Key，path={}，remote={}",
                    path, remoteAddress(exchange));
            return unauthorized(exchange, "invalid_api_key", "API Key 无效或已被吊销");
        }

        String authenticated = clientId.get();

        // ★ 身份只认 key。如果客户端还自作主张发了 X-Client-Id：
        //   不一致时记录 —— 这既可能是老客户端没改干净，也可能是伪造尝试。
        //   两种情况都值得在日志里留下痕迹，但都不影响本次请求的身份。
        String declared = exchange.getRequest().getHeaders().getFirst(ClientIdentity.CLIENT_ID_HEADER);
        if (declared != null && !declared.isBlank() && !declared.trim().equals(authenticated)) {
            log.warn("[鉴权-身份头被忽略] 已认证 client={}，但请求声明 X-Client-Id={}。"
                            + "认证开启后该头不参与任何决策。", authenticated, declared.trim());
        }

        // ★ 把认证结果放进 exchange attributes，而不是重写请求头：
        //   下游（限流/计费/Controller）从同一个地方读身份，
        //   避免"各处自己解析一次"导致的漂移。
        exchange.getAttributes().put(ClientIdentity.AUTHENTICATED_CLIENT_ATTRIBUTE, authenticated);

        return chain.filter(exchange);
    }

    /**
     * 从两种常见的头里取 key。
     *
     * <p><b>★ 两个头都带了怎么办 ★</b>
     * <p>如果值相同，没问题；<b>如果不同，必须拒绝</b>，不能"随便挑一个"。
     * 两个互相矛盾的凭证同时出现，要么是客户端配置错了，要么是有人试图
     * 用一个头的合法 key 掩盖另一个头的伪造身份 —— <b>这种情况没有"正确选择"，
     * 只有"拒绝并让人去查"。</b>
     */
    private KeyExtraction extract(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();

        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        String bearer = null;

        if (authorization != null && !authorization.isBlank()) {
            if (authorization.length() > BEARER_PREFIX.length()
                    && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                bearer = authorization.substring(BEARER_PREFIX.length()).trim();
            } else {
                // 带了 Authorization 但不是 Bearer：不猜它想干什么。
                return KeyExtraction.error("unsupported_authorization_scheme",
                        "仅支持 Authorization: Bearer <API Key>");
            }
        }

        String xApiKey = headers.getFirst(API_KEY_HEADER);
        if (xApiKey != null) {
            xApiKey = xApiKey.trim();
            if (xApiKey.isEmpty()) {
                xApiKey = null;
            }
        }

        if (bearer != null && xApiKey != null && !bearer.equals(xApiKey)) {
            return KeyExtraction.error("conflicting_credentials",
                    "Authorization 与 X-Api-Key 指向不同的凭证，拒绝处理");
        }

        String key = bearer != null ? bearer : xApiKey;
        if (key == null || key.isEmpty()) {
            return KeyExtraction.error("missing_api_key",
                    "缺少 API Key。请使用 Authorization: Bearer <key> 或 X-Api-Key: <key>");
        }
        return new KeyExtraction(key, null, null);
    }

    /**
     * 401 响应。
     *
     * <p>错误体沿用 {@link OpenAiErrorResponse}：<b>成功和失败的契约必须一致</b>，
     * 否则 OpenAI SDK 解析失败，用户看到的是"未知错误"。
     * <p>{@code WWW-Authenticate} 不是装饰：标准客户端据此知道该用哪种认证方案，
     * 也是 401 响应在协议层面的必备字段。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer realm=\"" + REALM + "\"");
        // 401 的响应绝不能被缓存：中间层缓存一个 401 会让后续合法请求也失败。
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new OpenAiErrorResponse(
                    new OpenAiErrorResponse.ErrorBody(message, "authentication_error", code, null)));
        } catch (JsonProcessingException e) {
            // 连自己的错误体都序列化不出来：退化成最小 JSON，状态码和 code 必须活着。
            log.error("[鉴权] 错误响应体序列化失败，退化为最小 JSON", e);
            body = ("{\"error\":{\"code\":\"" + code + "\"}}").getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private String remoteAddress(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
    }

    /**
     * 认证必须早于限流（{@code HIGHEST_PRECEDENCE + 100}）。
     * <p>留出 50 个位置给将来"认证前"的组件（比如 IP 黑名单、WAF 前置检查）——
     * 顺序本身是架构的一部分，不该用随手挑的数字表达。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    private record KeyExtraction(String key, String errorCode, String message) {
        static KeyExtraction error(String code, String message) {
            return new KeyExtraction(null, code, message);
        }
    }
}
