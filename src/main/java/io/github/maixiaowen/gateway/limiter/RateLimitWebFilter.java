package io.github.maixiaowen.gateway.limiter;

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
import java.util.Set;

/**
 * <b>限流的入口：在请求到达任何业务代码之前先问一句"这个客户端还有额度吗"（G4）。</b>
 *
 * <p><b>★ 为什么做成 WebFilter，而不是在 {@code ChatController} 里调一下 ★</b>
 * <ol>
 *   <li><b>位置对</b>：限流是"最便宜的一道闸"。放在 Controller 里，
 *       意味着请求要先经过路由匹配、JSON 反序列化、日志——<b>被限流的请求白白干了这些活</b>。
 *       攻击流量打进来时，这些"白干的活"就是被消耗掉的 CPU。</li>
 *   <li><b>覆盖全</b>：filter 是<b>所有</b>请求的必经之路。放在某个 Controller 里，
 *       就天然只能守那一个端点 —— 哪天新加了端点，限流"忘了加"是必然会发生的事。</li>
 * </ol>
 *
 * <p><b>⚠️ 一个诚实的边界：本 filter 限不了"按渠道"</b>
 * <p>路线图里写的限流维度是"按 API Key / 按用户 / 按渠道"。前两个在这里都能做，
 * <b>但"按渠道"做不到 —— 因为渠道名在请求体里（{@code provider} 字段），
 * 而 WebFilter 跑在请求体被读取之前。</b>
 * <p>理论上可以在 filter 里把请求体整个读出来缓存，但那意味着
 * <b>每个请求（包括流式请求）都要先在内存里存一份完整的请求体</b> ——
 * 对一个代理来说这是不可接受的（请求体会被放大，长 prompt 可以到 MB 级）。
 * <p><b>所以"按渠道限流"只能在渠道被解析出来之后做（也就是 Controller 里）。
 * 这不是偷懒，是 WebFlux 里一个真实的物理约束 —— 而且它是可以被说清楚的，
 * 比含糊地说"我做了多维度限流"要值钱得多。</b>
 *
 * <p><b>★ 三层限流的正确叠法（这里只实现了一层，但你要知道另外两层在哪）★</b>
 * <pre>
 *   最外层  按来源 IP 限流      ← 在认证之前。目的是"别让未认证的流量打垮后面"
 *   中间层  按客户端标识限流    ← 本类。需要客户端自报身份（X-Client-Id）
 *   最内层  按渠道/账号限流     ← 需要请求体或认证结果，只能在 Controller 里
 * </pre>
 * 本类的 order 是 {@code HIGHEST_PRECEDENCE + 100}，
 * <b>刻意留出 100 个位置给将来要插到更前面的 IP 黑名单</b>——
 * 顺序就是这几层之间的关系，随便挑一个数会让自己以后没有插队空间。
 */
public class RateLimitWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);

    /**
     * 客户端身份的声明头。
     * <p>G7 起这个常量的<b>唯一来源</b>是 {@link ClientIdentity} ——
     * 限流和计费必须按同一个主体计算，见那个类的注释。
     */
    public static final String CLIENT_ID_HEADER = ClientIdentity.CLIENT_ID_HEADER;

    /**
     * 需要限流的路径白名单。
     *
     * <p><b>★ 用白名单，不用黑名单 ★</b>：写黑名单意味着"新加的端点默认不限流"——
     * 于是"忘了加限流"会在每次加端点时重演。
     * 白名单的失效方式是"新端点忘了加进去，于是没被限"，同样不好，
     * 但它至少是<b>一个集中、可见、可以写测试守住的清单</b>。
     *
     * <p><b>为什么健康检查/探针天然不在里面</b>：限流器一旦误伤探针，
     * Kubernetes 会把整个 Pod 重启掉 —— <b>你会同时失去服务和可观测性</b>，
     * 然后在重启的循环里找不到原因。
     */
    private static final Set<String> PROTECTED_PATHS = Set.of("/v1/chat/completions");

    private final RedisTokenBucketLimiter limiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitWebFilter(RedisTokenBucketLimiter limiter,
                              RateLimitProperties properties,
                              ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 未启用时**不该有本 Bean**（由 RateLimitConfig 的 @ConditionalOnProperty 保证）。
        // 这里再判一次是防御：Bean 的装配条件将来可能被改，而"限流开关失效但代码还在跑"
        // 属于那种不会报错、只会悄悄改变行为的故障。多这一行，代价是零。
        if (!properties.enabled() || !isProtected(exchange)) {
            return chain.filter(exchange);
        }

        String clientId = resolveClientId(exchange);
        return limiter.tryAcquire(clientId)
                .flatMap(decision -> apply(decision, exchange, chain, clientId));
    }

    private Mono<Void> apply(RateLimitDecision decision,
                             ServerWebExchange exchange,
                             WebFilterChain chain,
                             String clientId) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(properties.capacity()));
        if (decision.remaining() != RateLimitDecision.UNKNOWN_REMAINING) {
            // 水位未知时**不发这个头**，而不是发个 0 或 -1：
            // 客户端和监控会把这个头当事实用，发一个假的事实比不发更坏。
            headers.set("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }

        return switch (decision.outcome()) {
            case ALLOWED -> chain.filter(exchange);

            case DENIED -> {
                // ★ Retry-After 是 429 响应里**唯一被标准客户端真正消费**的字段。
                //   OpenAI SDK、requests 的重试适配器、各种退避库都读它。
                //   只返回 429 而不给这个头，等于把"等多久"交给客户端猜 ——
                //   而它们的默认退避策略往往比需要的激进，会形成无效重试风暴。
                headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
                log.warn("[限流-拒绝] client={}，剩余={}，建议 {}s 后重试（容量 {}，回填 {}/s）",
                        clientId, decision.remaining(), decision.retryAfterSeconds(),
                        properties.capacity(), properties.refillPerSecond());
                yield writeError(exchange, HttpStatus.TOO_MANY_REQUESTS,
                        "rate_limit_error", "rate_limit_exceeded",
                        "请求过于频繁，请 " + decision.retryAfterSeconds() + " 秒后重试");
            }

            case UNAVAILABLE -> {
                // ★ 这里是 503，不是 429。区别很重要，理由见 RateLimitDecision 的类注释。
                log.error("[限流-拒绝] client={}，限流器不可用且 fail-open=false，返回 503", clientId);
                yield writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "server_error", "rate_limiter_unavailable",
                        "网关限流组件暂不可用，请稍后重试");
            }
        };
    }

    /**
     * 写出错误响应体。
     *
     * <p><b>★ 为什么这里要手工写 body，而不是抛个异常让 {@code @RestControllerAdvice} 处理 ★</b>
     * <p>因为<b>异常处理器管不到 WebFilter</b>。{@code @RestControllerAdvice} 的生效范围是
     * 「DispatcherHandler 调用的 handler 抛出的异常」—— 而 filter 在 handler <b>之前</b>，
     * 压根不在那条调用链上。在 filter 里抛异常，得到的是 Spring 的默认错误页，
     * 不是我们的 {@code {"error":{...}}} 契约。
     * <p><b>这是个很容易踩的坑：你以为错误格式统一了，但有一个入口从来没走过那条统一的路。</b>
     *
     * <p>格式仍然复用 {@link OpenAiErrorResponse} —— 成功和失败的契约必须一致，
     * 这一点不因为"写在 filter 里"而改变。
     */
    private Mono<Void> writeError(ServerWebExchange exchange,
                                  HttpStatus status,
                                  String type,
                                  String code,
                                  String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new OpenAiErrorResponse(
                    new OpenAiErrorResponse.ErrorBody(message, type, code, null)));
        } catch (JsonProcessingException e) {
            // 序列化我们自己的错误体都失败了 —— 这是 bug，但此刻**不能让它把响应变成空的**。
            // 退化成一段最小 JSON：状态码和 code 必须活着，因为客户端的重试逻辑就靠这两样。
            log.error("[限流] 错误响应体序列化失败，退化为最小 JSON", e);
            body = ("{\"error\":{\"code\":\"" + code + "\"}}").getBytes(StandardCharsets.UTF_8);
        }

        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /**
     * 解析"这个请求是谁发的" —— 也就是<b>限流的维度</b>。
     *
     * <p><b>★ 维度选谁，是个产品决策不是技术决策 ★</b>
     * 按 IP 限：一个公司出口 IP 后面的所有人共用一个桶（会议室里三个人同时用就互相挤）；
     * 按用户限：公平，但需要先认证；
     * 按 API Key 限：能对应到账单，是 to-B 大模型网关的标准做法。
     * <b>选哪个取决于"你想保护什么"—— 保护成本就按 Key，保护稳定性就按 IP。</b>
     *
     * <p><b>★ 这里有个已知的生产问题，得说清楚（而不是假装没有）★</b>
     * <p>没有 {@code X-Client-Id} 时退化到来源 IP。但网关前面一旦有 nginx / SLB，
     * {@code getRemoteAddress()} 拿到的是<b>代理的 IP</b> ——
     * <b>所有用户会被算成同一个人，限流器一开就把全员一起限死</b>。
     * <p>正确做法是读 {@code X-Forwarded-For} 的<b>第一个</b>地址，
     * 并且<b>只在"来自我们信任的代理"的请求上才认这个头</b> ——
     * 否则客户端自己伪造一个 XFF 就能随便换身份绕过限流。
     * <p><b>这里先不实现，是因为现在还没有真实的部署拓扑可以验证这段逻辑；
     * 等 G9 接上真实拓扑时再写，那时候才有办法证明它是对的。
     * 写一段无法验证的转发头解析代码，比不写更危险 —— 它会给人"已经处理过了"的错觉。</b>
     */
    private String resolveClientId(ServerWebExchange exchange) {
        // 实现搬到 ClientIdentity 了：计费需要同一个主体，两处各写一份迟早会漂移。
        return ClientIdentity.resolve(exchange);
    }

    private boolean isProtected(ServerWebExchange exchange) {
        // pathWithinApplication() 而不是 getPath()：前者已经去掉了 base-path，
        // 所以以后给网关加了统一前缀，这个白名单不用跟着改。
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        return PROTECTED_PATHS.contains(path);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
