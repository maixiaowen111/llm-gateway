package com.example.gateway.billing;

import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.dto.ChatCompletionResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>G7 的核心：预扣 → 实结算 → 多退少补。</b>
 *
 * <p><b>★ 为什么不能"等上游回完，再按 usage 扣一次" ★</b>
 * <p>因为那样会有<b>超卖</b>：客户余额 100，同时发起 10 个请求，
 * 每个都在"扣款前"检查余额 —— 10 个都通过，最后余额变成 -900。
 * 大模型请求往往要几十秒，这个并发窗口宽得能开卡车。
 *
 * <p>预扣的本质是<b>"把未来的支出先冻结"</b>：
 * <ul>
 *   <li>请求发出前，按<b>最坏情况</b>冻结（prompt + max_tokens）；</li>
 *   <li>上游回完后，按<b>真实 usage</b> 结算，冻结额度的剩余部分退回；</li>
 *   <li>一个字都没吐就失败的请求，<b>全额退款</b>；</li>
 *   <li>吐了一半断掉的请求，<b>按冻结额度收费</b> ——
 *       上游已经产生了成本，我们不能假装没发生。</li>
 * </ul>
 *
 * <p><b>★ 所有"扣钱"的动作都在 Lua 里 ★</b>
 * 见 {@code resources/scripts/billing_*.lua}。本类只负责：
 * 算出金额、拼出 key、把结果翻译成领域异常。<b>计算和持久化的边界清楚，各自都能测。</b>
 *
 * <p><b>⚠️ 本服务不负责"钱从哪来"</b>：初始额度是演示用的自动发放，
 * 真实系统里应该由账户系统充值（见 {@link BillingProperties} 的注释）。
 */
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private static final String RESERVE_SCRIPT = "scripts/billing_reserve.lua";
    private static final String SETTLE_SCRIPT = "scripts/billing_settle.lua";
    private static final String REFUND_SCRIPT = "scripts/billing_refund.lua";

    private final ReactiveStringRedisTemplate redis;
    private final BillingProperties properties;
    private final ObjectMapper objectMapper;

    private final RedisScript<String> reserveScript;
    private final RedisScript<String> settleScript;
    private final RedisScript<String> refundScript;

    public BillingService(ReactiveStringRedisTemplate redis,
                          BillingProperties properties,
                          ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.reserveScript = loadScript(RESERVE_SCRIPT);
        this.settleScript = loadScript(SETTLE_SCRIPT);
        this.refundScript = loadScript(REFUND_SCRIPT);
    }

    /**
     * <b>一个"关着的"计费服务，只给单元测试和本地无 Redis 场景用。</b>
     *
     * <p>它故意在构造时就置 {@code enabled=false}：所有公开方法都会在<b>碰 Redis 之前</b>返回，
     * 因此内部的 {@code redis} 为 null 也不会出问题。
     * <p><b>不要在生产装配里用它</b> —— 生产走 {@code BillingConfig}，那里有真实的 Redis 和启动自检。
     */
    public static BillingService disabled() {
        BillingProperties properties = new BillingProperties(
                false, "gw:billing:disabled", 300, false, 0, 1, 3, 1024, 1800);
        return new BillingService(null, properties, new ObjectMapper());
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * 预扣：按"最坏情况"冻结额度。
     *
     * <p>最坏情况 = 输入 token + 客户端允许的最大输出 token。
     * <b>这也是为什么 {@link ChatCompletionRequest#maxTokens()} 对计费是必需的</b>：
     * 没有它，只能按默认上限拍一个数 —— 客户不写上限，却是别人替它承担风险。
     *
     * @throws InsufficientQuotaException 余额不足（→ 402）
     * @throws BillingUnavailableException Redis 不可用且 fail-closed（→ 503）
     */
    public Mono<Reservation> reserve(String clientId, ChatCompletionRequest request) {
        if (!properties.enabled()) {
            return Mono.just(Reservation.noop(clientId));
        }

        int promptTokens = TokenEstimator.estimatePromptTokens(request);
        int maxOutput = request.maxTokens() == null
                ? properties.defaultMaxTokens()
                : request.maxTokens();
        long amount = (long) promptTokens * properties.promptTokenCredits()
                + (long) maxOutput * properties.completionTokenCredits();

        String reservationId = clientId + ":" + UUID.randomUUID();
        String balanceKey = balanceKey(clientId);
        String reservationKey = reservationKey(reservationId);

        return redis.execute(reserveScript, List.of(balanceKey, reservationKey),
                        String.valueOf(amount),
                        String.valueOf(properties.initialCredits()),
                        String.valueOf(properties.reservationTtlSeconds()))
                .next()
                .timeout(Duration.ofMillis(properties.timeoutMillis()))
                .map(json -> parseReserve(json, clientId, reservationId, amount))
                .doOnNext(reservation -> log.debug("[计费-预扣] client={} reservation={} credits={}",
                        clientId, reservationId, amount))
                .onErrorResume(error -> onReserveFailure(clientId, amount, error));
    }

    private Reservation parseReserve(String json, String clientId, String reservationId, long amount) {
        ScriptResult result = parse(json);
        if ("insufficient".equals(result.status())) {
            throw new InsufficientQuotaException(clientId,
                    result.balance() == null ? 0 : result.balance(), amount);
        }
        if (!"ok".equals(result.status())) {
            throw new IllegalStateException("预扣脚本返回了未知状态：" + json);
        }
        return new Reservation(reservationId, clientId, amount, false);
    }

    /**
     * 按真实 usage 结算（多退少补）。
     *
     * @param source 计费依据：{@code usage}（上游给了）/ {@code estimated}（我们估的）/
     *               {@code reservation}（断流，按冻结额收）。<b>只用于日志和排障</b>
     */
    public Mono<Void> settle(Reservation reservation,
                             ChatCompletionResponse.Usage usage,
                             String source) {
        if (reservation.noop() || !properties.enabled()) {
            return Mono.empty();
        }

        int promptTokens = usage == null ? 0 : usage.promptTokens();
        int completionTokens = usage == null ? 0 : usage.completionTokens();
        long charged = (long) promptTokens * properties.promptTokenCredits()
                + (long) completionTokens * properties.completionTokenCredits();

        return doSettle(reservation, charged, promptTokens, completionTokens, source);
    }

    /**
     * 断流结算：按<b>预扣额度</b>收费。
     *
     * <p><b>为什么不按"已经收到的字数"估</b>：客户端断开时，上游可能已经生成了
     * 远超我们收到的内容（TCP 缓冲、事件循环调度）。<b>按收到的内容收费会系统性少收。</b>
     * 而且断流时我们的 usage 数据本来就不完整 —— 用一个不完整的数据去算钱，
     * 不如用"事先双方都认可的预扣上限"。
     */
    public Mono<Void> settleAtReservation(Reservation reservation, String reason) {
        if (reservation.noop() || !properties.enabled()) {
            return Mono.empty();
        }
        log.warn("[计费-断流结算] client={} reservation={} 按预扣额度收费 {} credits，原因={}",
                reservation.clientId(), reservation.id(), reservation.reservedCredits(), reason);
        return doSettle(reservation, reservation.reservedCredits(), 0, 0, "reservation:" + reason);
    }

    /** 全额退款：上游一个字都没给 */
    public Mono<Void> refund(Reservation reservation, String reason) {
        if (reservation.noop() || !properties.enabled()) {
            return Mono.empty();
        }
        return redis.execute(refundScript, List.of(
                        balanceKey(reservation.clientId()),
                        reservationKey(reservation.id())))
                .next()
                .timeout(Duration.ofMillis(properties.timeoutMillis()))
                .doOnNext(json -> log.warn("[计费-退款] client={} reservation={} 原因={} 结果={}",
                        reservation.clientId(), reservation.id(), reason, json))
                .then()
                // ★ 退款失败不能把用户的错误"变成另一种错误" ★
                // 用户这次请求本来就失败了，再让他看到一个 500 只会更困惑；
                // 而账目问题必须由告警兜住 —— 所以这里是 ERROR 日志 + 吞掉异常。
                .onErrorResume(error -> {
                    log.error("[计费-退款失败] client={} reservation={} 这笔预扣将等到 TTL 自动释放。"
                                    + "★ 若持续出现，说明 Redis 不可用，账目会漂移 ★",
                            reservation.clientId(), reservation.id(), error);
                    return Mono.empty();
                });
    }

    /** 管理端看用量与余额 */
    public Mono<UsageSnapshot> usage(String clientId) {
        String usageKey = usageKey(clientId);
        return redis.opsForHash().entries(usageKey)
                .collectMap(entry -> String.valueOf(entry.getKey()),
                        entry -> String.valueOf(entry.getValue()))
                .timeout(Duration.ofMillis(properties.timeoutMillis()))
                .flatMap(usage -> redis.opsForValue().get(balanceKey(clientId))
                        .timeout(Duration.ofMillis(properties.timeoutMillis()))
                        .map(balance -> toSnapshot(clientId, usage, Long.parseLong(balance)))
                        .defaultIfEmpty(toSnapshot(clientId, usage, 0)))
                .onErrorMap(error -> new BillingUnavailableException(
                        "读取用量失败（client=" + clientId + "）", error));
    }

    /** 管理端充值。返回充值后的余额 */
    public Mono<Long> topUp(String clientId, long amount) {
        if (amount == 0) {
            return Mono.error(new IllegalArgumentException("充值金额不能为 0"));
        }
        return redis.opsForValue().increment(balanceKey(clientId), amount)
                .timeout(Duration.ofMillis(properties.timeoutMillis()))
                .doOnNext(balance -> log.warn("[计费-充值] client={} 变动={} 新余额={}",
                        clientId, amount, balance))
                .onErrorMap(error -> new BillingUnavailableException(
                        "充值失败（client=" + clientId + "）", error));
    }

    // ========================================================================
    // 内部
    // ========================================================================

    private Mono<Void> doSettle(Reservation reservation,
                                long charged,
                                int promptTokens,
                                int completionTokens,
                                String source) {
        return redis.execute(settleScript, List.of(
                        balanceKey(reservation.clientId()),
                        reservationKey(reservation.id()),
                        usageKey(reservation.clientId())),
                        String.valueOf(charged),
                        String.valueOf(promptTokens),
                        String.valueOf(completionTokens),
                        source)
                .next()
                .timeout(Duration.ofMillis(properties.timeoutMillis()))
                .doOnNext(json -> {
                    ScriptResult result = parse(json);
                    if ("missing".equals(result.status())) {
                        // 正常的幂等路径：结算/退款只生效一次。这里必须记日志，
                        // 因为"该结算却找不到凭据"也可能是 TTL 过期或 Redis 数据被清 ——
                        // 两种情况都意味着账目有缺口，需要人看一眼。
                        log.warn("[计费-结算] 凭据不存在或已处理，跳过。client={} reservation={} source={}",
                                reservation.clientId(), reservation.id(), source);
                        return;
                    }
                    log.info("[计费-结算] client={} source={} 扣费={} 退回={} 补扣={} tokens={}+{}",
                            reservation.clientId(), source, result.charged(),
                            result.refunded(), result.extra(), promptTokens, completionTokens);
                })
                .then()
                // 结算失败同理：钱已经花了，不能因为记账失败就把用户的成功响应变成错误。
                // 但必须 ERROR —— 这是一笔真实的账目缺口。
                .onErrorResume(error -> {
                    log.error("[计费-结算失败] client={} reservation={} source={} "
                                    + "★ 这笔请求的成本可能没有被记上 ★",
                            reservation.clientId(), reservation.id(), source, error);
                    return Mono.empty();
                });
    }

    private Mono<Reservation> onReserveFailure(String clientId, long amount, Throwable error) {
        if (error instanceof InsufficientQuotaException insufficient) {
            // 余额不足是正常业务结果，原样往上抛 —— 不要被下面的 fail-open 吞掉。
            return Mono.error(insufficient);
        }
        if (properties.failOpen()) {
            log.error("[计费-故障放行] Redis 不可用，本次请求不预扣（fail-open=true）。"
                    + "client={} amount={} 原因={}。★ 这意味着这段时间的用量无法计费 ★",
                    clientId, amount, error.toString());
            return Mono.just(Reservation.noop(clientId));
        }
        log.error("[计费-故障拒绝] Redis 不可用，按 fail-open=false 拒绝本次请求（503）。"
                + "client={} amount={} 原因={}", clientId, amount, error.toString());
        return Mono.error(new BillingUnavailableException(
                "计费组件暂不可用，请稍后重试", error));
    }

    private ScriptResult parse(String json) {
        try {
            ScriptResult result = objectMapper.readValue(json, ScriptResult.class);
            if (result.status() == null) {
                throw new IllegalStateException("缺少 status 字段");
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("计费脚本返回了无法解析的结果：" + json, e);
        }
    }

    private UsageSnapshot toSnapshot(String clientId, Map<String, String> usage, long balance) {
        return new UsageSnapshot(
                clientId,
                parseLong(usage.get("requests")),
                parseLong(usage.get("promptTokens")),
                parseLong(usage.get("completionTokens")),
                parseLong(usage.get("creditsSpent")),
                balance);
    }

    private long parseLong(String raw) {
        return raw == null ? 0 : Long.parseLong(raw);
    }

    private String balanceKey(String clientId) {
        return properties.keyPrefix() + ":balance:" + clientId;
    }

    private String reservationKey(String reservationId) {
        return properties.keyPrefix() + ":reservation:" + reservationId;
    }

    private String usageKey(String clientId) {
        return properties.keyPrefix() + ":usage:" + clientId;
    }

    private RedisScript<String> loadScript(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        String lua;
        try (InputStream in = resource.getInputStream()) {
            lua = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 缺脚本 = 构建产物坏了，必须炸。不能"降级成不计费"。
            throw new IllegalStateException("加载计费脚本失败：" + path, e);
        }
        return new DefaultRedisScript<>(lua, String.class);
    }

    /** 脚本返回的 JSON。字段都是装箱类型 —— 缺失必须能被发现，而不是静默变成 0 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScriptResult(
            String status,
            Long balance,
            Long required,
            Long charged,
            Long refunded,
            Long extra
    ) {
    }

    /** 管理端看到的用量视图 */
    public record UsageSnapshot(
            String clientId,
            long requests,
            long promptTokens,
            long completionTokens,
            long creditsSpent,
            long balance
    ) {
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
