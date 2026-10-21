package com.example.gateway.controller;

import com.example.gateway.billing.BillingService;
import com.example.gateway.routing.ChannelRouter;
import com.example.gateway.routing.ChannelStateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * <b>G6：运维面板 —— 看渠道状态、运行时摘/恢复渠道。</b>
 *
 * <p><b>为什么运维需要一个"不用重启"的开关</b>：
 * 半夜发现某条渠道的计费异常 / 返回内容明显错乱，第一反应是"先把它摘掉"。
 * 没有这个接口，动作就是改配置 + 重新部署 + 等滚动发布 —— <b>十几分钟里流量继续打过去</b>。
 * <b>故障处置的时间单位是秒，不是发布周期。</b>
 *
 * <p><b>★ 安全：这些接口天生"高权限"，默认必须关着 ★</b>
 * <p>它能改的是<b>所有用户请求走向哪家厂商</b>。放开的后果不是数据泄露那么"安静"，
 * 而是可以被拿来<b>把流量全部打到一条指定渠道、或全部摘掉造成全站 503</b>。
 * <p>所以：
 * <ol>
 *   <li>没有配置 {@code gateway.admin.token} 时，本组接口<b>一律 403</b>（fail-closed），
 *       而不是"没配密码就等于不设防"；</li>
 *   <li>Token 比较用 {@link MessageDigest#isEqual}（常量时间），
 *       避免通过响应时间一个字节一个字节地猜出 token；</li>
 *   <li>只挂在 {@code /admin/**} 下，<b>永远不要和 {@code /v1/**} 混在一起</b> ——
 *       将来加网关层路由规则时，"/v1 走公网、/admin 走内网"是个一眼能写对的规则。</li>
 * </ol>
 *
 * <p><b>⚠️ 本接口改的是单实例内存状态</b>（见 {@code ChannelStateRegistry} 的注释）。
 * 多实例部署时必须升级成 Redis/配置中心，并在响应里带版本号 —— 现在不做，
 * 是因为还没有多实例环境能验证它。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final ChannelRouter router;
    private final ChannelStateRegistry states;
    private final BillingService billing;
    private final String adminToken;

    public AdminController(ChannelRouter router,
                           ChannelStateRegistry states,
                           BillingService billing,
                           @Value("${gateway.admin.token:}") String adminToken) {
        this.router = router;
        this.states = states;
        this.billing = billing;
        this.adminToken = adminToken;

        if (adminToken == null || adminToken.isBlank()) {
            log.warn("[管理接口已禁用] 未配置 gateway.admin.token，/admin/** 将一律返回 403。"
                    + "这是刻意的 fail-closed：管理接口能改变所有请求的去向，不能默认敞开。");
        }
    }

    /** 渠道面板：类型、分组、权重、开关、熔断状态、最近失败率 */
    @GetMapping("/channels")
    public ResponseEntity<?> channels(@RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return forbidden();
        }
        List<ChannelRouter.ChannelInfo> infos = router.channelInfos();
        return ResponseEntity.ok(Map.of("channels", infos, "count", infos.size()));
    }

    /**
     * 运行期摘除 / 恢复一条渠道。
     *
     * <p><b>请求体用 {@code {"enabled": false}} 而不是两个端点</b>（enable/disable）：
     * 两个端点意味着两次改动、两份鉴权、两个审计点，而它们表达的是同一个状态。
     * <b>一个状态，一个接口。</b>
     */
    @PostMapping("/channels/{name}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable String name,
                                        @RequestBody ToggleRequest body,
                                        @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return forbidden();
        }
        if (body == null || body.enabled() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "请求体必须包含 enabled: true/false"));
        }

        boolean previous;
        try {
            previous = states.setEnabled(name, body.enabled());
        } catch (IllegalArgumentException e) {
            // 未知渠道 → 404，不是 400：路径里的资源不存在。
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }

        // ★ 这是一条审计日志，不是调试日志 ★
        // 谁在什么时候把哪条渠道从什么状态改成了什么状态 —— 这是事故复盘时唯一能回答
        // "为什么凌晨三点流量全跑到了 moonshot"的证据。日志里必须带上下文，不能只写"已更新"。
        log.warn("[管理员变更渠道状态] channel={} {} -> {}（操作者未认证为具体用户，仅校验了 token）",
                name, previous, body.enabled());

        return ResponseEntity.ok(Map.of(
                "channel", name,
                "previous", previous,
                "enabled", body.enabled()));
    }

    /**
     * <b>G7：查看某个客户端的用量与余额。</b>
     *
     * <p>做成"查单个客户端"而不是"列出全部"是刻意的：
     * 后者要扫 Redis 的 key 空间（{@code KEYS} 在生产上是禁用级操作），
     * 或者维护一份客户索引 —— <b>那是另一套系统的工作</b>。
     * 这里的定位是<b>排障工具</b>：客服拿到用户 ID，查一下"他为什么被 402 了"。
     *
     * <p>响应里带 {@code billingEnabled}，避免运维看着一份"永远为 0"的用量
     * 去怀疑计费代码坏了 —— <b>数字为零时，最需要知道的是"零是不是正常的"。</b>
     */
    @GetMapping("/usage/{clientId}")
    public Mono<ResponseEntity<?>> usage(@PathVariable String clientId,
                                         @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return Mono.just(forbidden());
        }
        return billing.usage(clientId)
                .map(snapshot -> ResponseEntity.ok(Map.of(
                        "clientId", snapshot.clientId(),
                        "requests", snapshot.requests(),
                        "promptTokens", snapshot.promptTokens(),
                        "completionTokens", snapshot.completionTokens(),
                        "totalTokens", snapshot.totalTokens(),
                        "creditsSpent", snapshot.creditsSpent(),
                        "balance", snapshot.balance(),
                        "billingEnabled", billing.enabled())));
    }

    /**
     * <b>G7：充值。</b>真实系统里这是支付回调的入口，这里保留同样的形状：
     * <b>改动的是余额，返回的是"改动后的真实余额"</b>，而不是"成功"两个字 ——
     * 调用方据此可以立刻展示最新数字，不用再查一次。
     *
     * <p><b>★ 为什么金额允许为负 —— 但这里拒绝 0 ★</b>
     * <p>负数（扣减）在真实系统里是有用的（对账纠错、退款），所以不做"必须大于 0"的硬限制；
     * 但 0 没有任何语义，只可能是调用方传错。
     * <p>金额是 {@code Long} 而不是 {@code long}：<b>缺失字段必须是 400，
     * 而不是被自动拆箱成 0 然后悄悄执行</b>。
     */
    @PostMapping("/clients/{clientId}/credits")
    public Mono<ResponseEntity<?>> topUp(@PathVariable String clientId,
                                         @RequestBody(required = false) CreditRequest body,
                                         @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String token) {
        if (!authorized(token)) {
            return Mono.just(forbidden());
        }
        if (body == null || body.amount() == null || body.amount() == 0) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "请求体必须包含非 0 的 amount（正数=充值，负数=扣减）")));
        }
        return billing.topUp(clientId, body.amount())
                .map(balance -> ResponseEntity.ok(Map.of(
                        "clientId", clientId,
                        "amount", body.amount(),
                        "balance", balance)));
    }

    /**
     * fail-closed：<b>没配 token = 全部拒绝</b>。
     *
     * <p>想象相反的做法（没配 token 就放行）：部署时漏配一个环境变量，
     * 结果不是"管理接口用不了"，而是<b>任何人都能摘掉你的全部渠道</b>。
     * 配置缺失必须让功能<b>不可用</b>，而不是让它<b>不设防</b>。
     */
    private boolean authorized(String token) {
        if (adminToken == null || adminToken.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "管理接口不可用：未配置 gateway.admin.token，或 X-Admin-Token 不正确"));
    }

    public record ToggleRequest(Boolean enabled) {
    }

    public record CreditRequest(Long amount) {
    }
}
