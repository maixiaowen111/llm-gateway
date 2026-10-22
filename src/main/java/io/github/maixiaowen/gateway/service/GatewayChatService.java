package io.github.maixiaowen.gateway.service;

import io.github.maixiaowen.gateway.billing.BillingService;
import io.github.maixiaowen.gateway.billing.BillingUnavailableException;
import io.github.maixiaowen.gateway.billing.InsufficientQuotaException;
import io.github.maixiaowen.gateway.billing.Reservation;
import io.github.maixiaowen.gateway.billing.TokenEstimator;
import io.github.maixiaowen.gateway.dto.ChatCompletionChunk;
import io.github.maixiaowen.gateway.dto.ChatCompletionRequest;
import io.github.maixiaowen.gateway.dto.ChatCompletionResponse;
import io.github.maixiaowen.gateway.observability.GatewayMetrics;
import io.github.maixiaowen.gateway.provider.Provider;
import io.github.maixiaowen.gateway.resilience.ChannelCircuitBreakers;
import io.github.maixiaowen.gateway.resilience.ErrorClassifier;
import io.github.maixiaowen.gateway.resilience.ResilienceProperties;
import io.github.maixiaowen.gateway.resilience.UpstreamErrorKind;
import io.github.maixiaowen.gateway.resilience.UpstreamFailure;
import io.github.maixiaowen.gateway.routing.ChannelRouter;
import io.github.maixiaowen.gateway.routing.NoHealthyChannelException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * <b>G5 的执行引擎：把"路由出来的候选列表"变成"一次真正成功的调用"。</b>
 *
 * <p><b>它和 {@link ChannelRouter} 的分工</b>：路由器只做<b>决策</b>（谁在前谁在后），
 * 不碰网络、不碰熔断器状态；本类只做<b>执行</b>（打、失败、重试、换人），
 * 不决定"该有谁"。<b>决策和执行分开，才能分别测试。</b>
 *
 * <p><b>★ 本类最重要的一条规则 ★</b>
 * <p><b>流式响应吐出第一个字节之后，绝对不允许再换渠道、也不允许重试。</b>
 * <p>为什么：
 * <ul>
 *   <li>A 渠道已经吐了"订单已经"四个字，然后断了；换到 B 渠道从头再讲一遍"订单已经" ——
 *       用户界面上就是<b>"订单已经订单已经..."</b>，这在生产上是被截图投诉的 bug；</li>
 *   <li>更糟的是<b>计费</b>：A、B 两份输出都产生了 token 费用，用户只看到一份；
 *       如果中间还有工具调用（function calling），甚至可能出现<b>副作用执行两次</b>。</li>
 * </ul>
 * 所以 {@code afterFirstByte} 不只是日志字段，它是<b>能不能重试的硬边界</b>。
 * 本类用 {@link AtomicBoolean} 记住"是否已经吐过块"，异常分类时把它交给
 * {@link ErrorClassifier}，重试和转移都读这个值。
 *
 * <p><b>★ 重试顺序：先同渠道，再换渠道 ★</b>
 * <p>同一个渠道上重试的代价最小（网络就近、上下文一致），所以"可重试"的错误先原地退避重试；
 * 重试用尽才转移。而 {@code CLIENT_ERROR}（400）和 {@code AUTH_ERROR}（401）两者都不做 ——
 * 前者换一百个渠道也不会成功，后者换渠道只会把配置问题藏起来（详见
 * {@link UpstreamErrorKind} 的注释）。
 *
 * <p><b>★ G7 计费插在这里：预扣 → 调用 → 结算/退款/断流收费 ★</b>
 * <p>预扣发生在<b>真正发请求之前</b>（路由都还没跑），结算发生在<b>成功交付之后</b>，
 * 失败则全额退款；<b>吐过字之后断开，按预扣额度收费</b> —— 上游已经烧了 token，我们不能假装没发生。
 * <p>这也意味着<b>重试/转移产生的多次上游调用，成本都算在这次预扣里</b>：
 * 用户只付一次，但我们替它承担了"我们自己决定重试"的成本 —— 这是网关应有的代价，
 * 因为重试是为了让用户成功，不是让用户多付钱。
 *
 * <p><b>⚠️ 计费主体（clientId）是参数，不是本类去解析的</b>：
 * 本类是服务层，不该知道 HTTP 头叫什么。客户端身份由 {@code ClientIdentity} 统一解析后传进来，
 * 这样限流和计费用的必然是同一个人。
 */
public class GatewayChatService {

    private static final Logger log = LoggerFactory.getLogger(GatewayChatService.class);

    /** 退避上限。指数退避不能无限涨：客户端等不起，而且上游恢复时会被瞬间打爆 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final ChannelRouter router;
    private final ChannelCircuitBreakers circuitBreakers;
    private final ResilienceProperties resilience;
    private final BillingService billing;
    private final GatewayMetrics metrics;

    public GatewayChatService(ChannelRouter router,
                              ChannelCircuitBreakers circuitBreakers,
                              ResilienceProperties resilience,
                              BillingService billing,
                              GatewayMetrics metrics) {
        this.router = router;
        this.circuitBreakers = circuitBreakers;
        this.resilience = resilience;
        this.billing = billing;
        this.metrics = metrics;
    }

    // ========================================================================
    // 非流式
    // ========================================================================

    /**
     * 非流式：一次拿到完整回复。
     *
     * <p>{@code Mono.defer} 包住整段，是为了让<b>路由和预扣都发生在"订阅时"而不是"组装时"</b>。
     * 如果路由在组装时就定了，平滑加权轮询会变成"谁先组装谁永远赢"。
     *
     * <p><b>★ 顺序：先路由，再预扣，最后调用 ★</b>
     * <p>路由失败（全部渠道不可用）时压根不该冻结用户的钱 —— 所以路由放在预扣之前。
     * 而预扣必须在"发请求之前"完成，否则并发请求会同时通过余额检查，造成超卖。
     *
     * @param clientId 计费主体，来自 {@code ClientIdentity}
     */
    public Mono<ChatCompletionResponse> complete(String clientId, ChatCompletionRequest request) {
        return Mono.deferContextual(context -> {
            // ★ G13：span 从 Reactor Context 里取，不靠 ThreadLocal ★
            //   WebFlux 在收到请求时把 server observation 放进 Reactor Context；
            //   而 ThreadLocal 只有在"恰好还是那个线程"时才有效 ——
            //   Netty 换个线程、或某个算子在别的调度器上执行，Span.current() 就变成空的。
            //   从 Context 取是唯一在异步链路里确定成立的方式。
            Span span = spanFrom(context);
            long startedAt = System.nanoTime();
            tag(span, "gateway.client.id", clientId);
            tag(span, "gateway.request.model", request.model());
            tag(span, "gateway.stream", false);

            List<Provider> candidates = router.candidates(request);
            Mono<ChatCompletionResponse> chain = billing.reserve(clientId, request)
                    .flatMap(reservation -> attemptComplete(candidates, 0, request, span)
                            // 成功：按 usage 结算。结算必须发生在"把响应交还给调用方之前"，
                            // 否则请求一结束、资源一释放，这笔账就可能没机会记上。
                            .flatMap(response -> settleComplete(reservation, request, response)
                                    .thenReturn(response))
                            // 失败：上游一个字都没交付（非流式本来也没有"半个响应"），全额退款。
                            // ★ settle 不会以错误结束（内部已兜住并记 ERROR），所以这里
                            //   一旦走到 onErrorResume，一定是"调用失败"，不会把已结算的钱再退一遍。 ★
                            .onErrorResume(error -> billing
                                    .refund(reservation, "非流式调用失败：" + error.getClass().getSimpleName())
                                    .then(Mono.error(error))))
                    // ★ 指标挂在这里，而不是 Controller ★
                    //   Controller 只知道"HTTP 返回了没有"，不知道"钱扣了没有、熔断了没有"。
                    //   把指标放在业务链路的末端，才能保证它和真实发生的事是同一件事。
                    .doOnSuccess(response -> metrics.requestSuccess(
                            false, GatewayMetrics.nanosSince(startedAt)))
                    .doOnError(error -> metrics.requestFailure(
                            false, errorKind(error), GatewayMetrics.nanosSince(startedAt)))
                    // ★ G13：收到请求的日志放在这里，而不是 Controller ★
                    //   Controller 执行时 observation 的 MDC 作用域还没恢复到当前线程，
                    //   那里打出来的 traceId 是空的 —— 排查时会把同一请求的日志切成两截。
                    //   放在订阅回调里，MDC 已由 Reactor Context 恢复，天然带同一个 traceId。
                    .doOnSubscribe(ignored -> log.info(
                            "[收到请求] client={} model={} stream=false messages={} 条",
                            clientId, request.model(), messageCount(request)));
            return withTraceMdc(chain, span);
        });
    }

    private Mono<ChatCompletionResponse> attemptComplete(List<Provider> candidates,
                                                         int index,
                                                         ChatCompletionRequest request,
                                                         Span span) {
        if (index >= candidates.size()) {
            // 路由器保证候选非空；走到这里说明调用链被改坏了，明确报出来。
            return Mono.error(new NoHealthyChannelException("候选渠道已耗尽，但仍有请求未完成"));
        }

        Provider provider = candidates.get(index);
        String channel = provider.name();
        long startedAt = System.nanoTime();
        // 每次尝试都覆盖：最终留下的就是"真正服务了这个请求（或最后失败）的渠道"。
        tag(span, "gateway.channel", channel);
        tag(span, "gateway.attempt", index);

        Mono<ChatCompletionResponse> call = Mono
                // ★ defer：每次订阅（含重试）都重新发一次请求。
                //   不包 defer 的话，重试的会是"上一次已经完成的 Mono"，拿到的还是旧结果。
                .defer(() -> provider.chatCompletion(request))
                // 先分类再进熔断器：熔断器只认 UpstreamFailure，
                // 原始 WebClientResponseException 进来会被当成"不认识"而不计数 —— 熔断器就永远不会开。
                .onErrorMap(error -> ErrorClassifier.classify(channel, error, false));

        call = applyResilience(call, channel);

        return call
                .doOnSuccess(response -> {
                    log.info("[调用成功] channel={} model={} 耗时={}ms usage={}",
                            channel, response.model(), elapsedMillis(startedAt), summarizeUsage(response));
                    tag(span, "gateway.upstream.model", response.model());
                    if (response.usage() != null) {
                        tag(span, "gateway.usage.total_tokens", response.usage().totalTokens());
                    }
                    metrics.upstreamSuccess(channel, false, GatewayMetrics.nanosSince(startedAt));
                })
                .onErrorResume(error -> {
                    UpstreamFailure failure = asFailure(channel, error);
                    log.warn("[调用失败] channel={} kind={} 是否可转移={} 原因={}",
                            channel, failure.kind(), failure.kind().failoverable(), failure.getMessage());
                    metrics.upstreamFailure(channel, false, failure.kind().code(),
                            GatewayMetrics.nanosSince(startedAt));
                    tag(span, "gateway.error.kind", failure.kind().code());

                    boolean hasNext = index + 1 < candidates.size();
                    if (failure.kind().failoverable() && hasNext) {
                        String next = candidates.get(index + 1).name();
                        log.warn("[故障转移] {} → {}（用户无感）", channel, next);
                        metrics.failover(channel, next);
                        tag(span, "gateway.failover", true);
                        return attemptComplete(candidates, index + 1, request, span);
                    }
                    return Mono.error(failure);
                });
    }

    // ========================================================================
    // 流式
    // ========================================================================

    /**
     * 流式：逐块交付，失败时按 {@code afterFirstByte} 决定"能不能挽回"。
     *
     * <p>返回的 Flux 里<b>不包含 {@code [DONE]}</b> —— 协议尾巴由 Controller 补（和 G2 的约定一致）。
     *
     * <p><b>★ 流式的计费必须挂在"最终交付的那个 Flux"上 ★</b>
     * <p>不能挂在某一次上游尝试上：重试和转移会让上游调用发生多次，而用户只看到一条流。
     * 计费的边界是<b>"用户收到了什么"</b>，不是"我们调了几次上游"。
     */
    public Flux<ChatCompletionChunk> stream(String clientId, ChatCompletionRequest request) {
        return Flux.deferContextual(context -> {
            Span span = spanFrom(context);
            long startedAt = System.nanoTime();
            tag(span, "gateway.client.id", clientId);
            tag(span, "gateway.request.model", request.model());
            tag(span, "gateway.stream", true);

            List<Provider> candidates = router.candidates(request);
            Flux<ChatCompletionChunk> chain = billing.reserve(clientId, request)
                    .flatMapMany(reservation -> withBilling(
                            reservation, request, attemptStream(candidates, 0, request, span)))
                    // 三种终态分别计：成功 / 失败 / 用户取消。
                    // ★ 取消必须单独记 ★ —— 把它算作失败会污染错误率告警；
                    //   完全不记又会让"用户大量中途离开"这个产品信号消失。
                    .doOnComplete(() -> metrics.requestSuccess(
                            true, GatewayMetrics.nanosSince(startedAt)))
                    .doOnError(error -> metrics.requestFailure(
                            true, errorKind(error), GatewayMetrics.nanosSince(startedAt)))
                    .doOnCancel(() -> metrics.requestCancelled(
                            true, GatewayMetrics.nanosSince(startedAt)))
                    .doOnSubscribe(ignored -> log.info(
                            "[收到请求] client={} model={} stream=true messages={} 条",
                            clientId, request.model(), messageCount(request)));
            return withTraceMdc(chain, span);
        });
    }

    /**
     * 给流式响应套上"结算生命周期"。
     *
     * <p><b>★ 为什么用 {@code doFinally} 而不是 {@code doOnComplete}/{@code doOnError} ★</b>
     * <p>因为流式有三种结束方式，而前两个回调只能覆盖两种：
     * <ol>
     *   <li><b>正常完成</b> → {@code ON_COMPLETE}：按 usage（或估算）结算；</li>
     *   <li><b>上游中断</b> → {@code ON_ERROR}：吐过字按预扣收费，没吐过全额退款；</li>
     *   <li><b>客户端断开</b> → {@code CANCEL}：<b>这才是最常被漏掉的一种</b>。</li>
     * </ol>
     * 客户端看不到 {@code ON_ERROR}，用户看到的只是"流没了"；如果我们只在错误回调里结算，
     * <b>关掉页面的用户就永远不用付钱</b> —— 而那一刻上游往往已经生成了大量 token。
     * {@code doFinally} 是唯一能同时看到这三种终态的地方。
     *
     * <p><b>★ 幂等靠 {@code finalized}，不靠 Redis ★</b>
     * <p>Reactor 规范下 {@code doFinally} 每个订阅只触发一次，但这里仍然用 CAS 兜底：
     * 计费代码将来可能被重构到别的地方，<b>"只结算一次"这件事不能依赖调用方的自觉</b>。
     * 即便 CAS 失效，Lua 侧"凭据不存在就跳过"是最后一道防线（多退少补的脚本见 billing_settle.lua）。
     *
     * <p><b>断流时按预扣额度收费，而不是按"我们收到的字数"</b>：
     * 客户端断开后，上游可能已经生成了远超我们收到的内容（TCP 缓冲、事件循环调度），
     * 按收到的内容收费会系统性少收。见 {@link BillingService#settleAtReservation}。
     */
    private Flux<ChatCompletionChunk> withBilling(Reservation reservation,
                                                  ChatCompletionRequest request,
                                                  Flux<ChatCompletionChunk> stream) {
        if (reservation.noop()) {
            // 计费关闭 / fail-open：不累计内容、不挂结算逻辑，零开销。
            return stream;
        }

        return Flux.defer(() -> {
            AtomicBoolean delivered = new AtomicBoolean(false);
            AtomicBoolean finalized = new AtomicBoolean(false);
            AtomicReference<ChatCompletionResponse.Usage> usage = new AtomicReference<>();
            StringBuilder generated = new StringBuilder();

            return stream
                    .doOnNext(chunk -> {
                        delivered.set(true);
                        if (chunk.usage() != null) {
                            usage.set(chunk.usage());
                        }
                        String content = firstContent(chunk);
                        if (content != null) {
                            generated.append(content);
                        }
                    })
                    .doFinally(signal -> {
                        if (!finalized.compareAndSet(false, true)) {
                            return;
                        }

                        Mono<Void> settlement;
                        switch (signal) {
                            case ON_COMPLETE -> settlement = settleStream(
                                    reservation, request, usage.get(), generated);
                            case CANCEL -> settlement = delivered.get()
                                    ? billing.settleAtReservation(reservation, "客户端中断")
                                    : billing.refund(reservation, "客户端在输出前中断");
                            case ON_ERROR -> settlement = delivered.get()
                                    ? billing.settleAtReservation(reservation, "上游中断")
                                    : billing.refund(reservation, "上游在输出前失败");
                            default -> settlement = Mono.empty();
                        }

                        // ★ 这里必须 subscribe，不能返回 Mono ★
                        //   doFinally 的回调签名是 void，主链路此刻已经终止。
                        //   如果只是构造一个 Mono 不订阅，Redis 命令永远不会发出 ——
                        //   而且不会有任何报错，账目就这样静默地漏了。
                        settlement.subscribe(
                                ignored -> {
                                },
                                error -> log.error("[计费-终态处理失败] client={} reservation={} signal={}",
                                        reservation.clientId(), reservation.id(), signal, error));
                    });
        });
    }

    /** 正常结束：优先用上游给的 usage，拿不到才估算 —— 并且估算必须留下 WARN 痕迹 */
    private Mono<Void> settleStream(Reservation reservation,
                                    ChatCompletionRequest request,
                                    ChatCompletionResponse.Usage usage,
                                    CharSequence generated) {
        if (usage != null) {
            return billing.settle(reservation, usage, "usage");
        }
        int completionTokens = TokenEstimator.estimateCompletionTokens(generated);
        if (completionTokens == 0) {
            // 完整结束却没有任何内容：这是"空回复"，不该收费。
            return billing.refund(reservation, "流式结束但无内容");
        }
        int promptTokens = TokenEstimator.estimatePromptTokens(request);
        log.warn("[计费-估算] 上游未在流式响应中返回 usage，按字符数估算：client={} tokens={}+{}。"
                        + "★ 该渠道的账单精确度已下降，考虑在请求里开启 include_usage ★",
                reservation.clientId(), promptTokens, completionTokens);
        return billing.settle(reservation,
                new ChatCompletionResponse.Usage(promptTokens, completionTokens,
                        promptTokens + completionTokens),
                "estimated");
    }

    /** 非流式结算：同样坚持"有 usage 用 usage，没有才估算" */
    private Mono<Void> settleComplete(Reservation reservation,
                                      ChatCompletionRequest request,
                                      ChatCompletionResponse response) {
        if (response.usage() != null) {
            return billing.settle(reservation, response.usage(), "usage");
        }
        String content = firstMessageContent(response);
        int completionTokens = TokenEstimator.estimateCompletionTokens(content);
        if (completionTokens == 0) {
            return billing.refund(reservation, "非流式响应无内容");
        }
        int promptTokens = TokenEstimator.estimatePromptTokens(request);
        log.warn("[计费-估算] 上游未返回 usage，按字符数估算：client={} tokens={}+{}",
                reservation.clientId(), promptTokens, completionTokens);
        return billing.settle(reservation,
                new ChatCompletionResponse.Usage(promptTokens, completionTokens,
                        promptTokens + completionTokens),
                "estimated");
    }

    private String firstContent(ChatCompletionChunk chunk) {
        if (chunk.choices() == null || chunk.choices().isEmpty()) {
            return null;
        }
        ChatCompletionChunk.Delta delta = chunk.choices().get(0).delta();
        return delta == null ? null : delta.content();
    }

    private String firstMessageContent(ChatCompletionResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        ChatCompletionResponse.Message message = response.choices().get(0).message();
        return message == null ? null : message.content();
    }

    private Flux<ChatCompletionChunk> attemptStream(List<Provider> candidates,
                                                    int index,
                                                    ChatCompletionRequest request,
                                                    Span span) {
        if (index >= candidates.size()) {
            return Flux.error(new NoHealthyChannelException("候选渠道已耗尽，但仍有请求未完成"));
        }

        Provider provider = candidates.get(index);
        String channel = provider.name();
        long startedAt = System.nanoTime();
        tag(span, "gateway.channel", channel);
        tag(span, "gateway.attempt", index);

        // ★ 这个 flag 必须在 defer 内部创建 —— 每次订阅（含重试）一个全新的"是否吐过字"。
        //   放在外面会被上一次尝试的结果污染，导致本该重试的请求被当成"已经吐过"而放弃。
        Flux<ChatCompletionChunk> call = Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            return provider.chatCompletionStream(request)
                    .doOnNext(chunk -> {
                        if (emitted.compareAndSet(false, true)) {
                            log.debug("[首字节] channel={} 耗时={}ms", channel, elapsedMillis(startedAt));
                            // ★ TTFB 只量"第一次吐出内容"的那一下 ★
                            //   emitted 的 CAS 保证它每个上游尝试只记一次；
                            //   如果放在每个 chunk 上，量到的就是"平均吐字间隔"，不是首字延迟。
                            metrics.firstByte(channel, GatewayMetrics.nanosSince(startedAt));
                            // 首块带上上游真实模型名，排查"请求 claude，实际落脚到哪个版本"时有据可查
                            tag(span, "gateway.upstream.model", chunk.model());
                        }
                        if (chunk.usage() != null) {
                            tag(span, "gateway.usage.total_tokens", chunk.usage().totalTokens());
                        }
                    })
                    // 分类时带上"已经吐过没有"，这是后面能不能重试/转移的唯一依据
                    .onErrorMap(error -> ErrorClassifier.classify(channel, error, emitted.get()));
        });

        call = applyResilience(call, channel);

        return call
                .doOnComplete(() -> {
                    log.info("[流式完成] channel={} 耗时={}ms", channel, elapsedMillis(startedAt));
                    metrics.upstreamSuccess(channel, true, GatewayMetrics.nanosSince(startedAt));
                })
                .onErrorResume(error -> {
                    UpstreamFailure failure = asFailure(channel, error);
                    metrics.upstreamFailure(channel, true, failure.kind().code(),
                            GatewayMetrics.nanosSince(startedAt));

                    tag(span, "gateway.error.kind", failure.kind().code());

                    if (failure.afterFirstByte()) {
                        // ★ 已经吐过字：到此为止。不重试、不换渠道，让错误如实传出去。
                        log.error("[流式中断] channel={} kind={} 已向客户端吐过数据，禁止转移 —— "
                                        + "否则用户会看到重复内容，且两份输出都会被计费。原因={}",
                                channel, failure.kind(), failure.getMessage());
                        return Flux.error(failure);
                    }

                    boolean hasNext = index + 1 < candidates.size();
                    if (failure.kind().failoverable() && hasNext) {
                        String next = candidates.get(index + 1).name();
                        log.warn("[故障转移-流式] {} → {}（首字节尚未发出，用户无感）", channel, next);
                        metrics.failover(channel, next);
                        tag(span, "gateway.failover", true);
                        return attemptStream(candidates, index + 1, request, span);
                    }

                    log.warn("[流式失败] channel={} kind={} 原因={}",
                            channel, failure.kind(), failure.getMessage());
                    return Flux.error(failure);
                });
    }

    // ========================================================================
    // 熔断 + 重试（Mono / Flux 两个重载，逻辑共用一份配置）
    // ========================================================================

    private Mono<ChatCompletionResponse> applyResilience(Mono<ChatCompletionResponse> call, String channel) {
        if (!resilience.enabled()) {
            return call;
        }
        CircuitBreaker breaker = circuitBreakers.get(channel);
        Mono<ChatCompletionResponse> guarded = call.transform(CircuitBreakerOperator.of(breaker));
        if (resilience.maxRetryAttempts() <= 0) {
            return guarded;
        }
        return guarded.retryWhen(retryPolicy());
    }

    private Flux<ChatCompletionChunk> applyResilience(Flux<ChatCompletionChunk> call, String channel) {
        if (!resilience.enabled()) {
            return call;
        }
        CircuitBreaker breaker = circuitBreakers.get(channel);
        Flux<ChatCompletionChunk> guarded = call.transform(CircuitBreakerOperator.of(breaker));
        if (resilience.maxRetryAttempts() <= 0) {
            return guarded;
        }
        return guarded.retryWhen(retryPolicy());
    }

    /**
     * 重试策略：<b>只重试"对方暂时不行"且"还没吐过字"的错误。</b>
     *
     * <p><b>★ filter 里必须同时判断 {@code !afterFirstByte()} ★</b>
     * <p>这是本类最容易写漏、后果也最严重的检查。Reactor 的 {@code retryWhen} 会
     * <b>从上游第一个元素重新播放</b>；对流式响应来说，那意味着用户已经收到的内容后面
     * 又接了一段从头开始的回复。过滤掉"已吐字"的错误，重试就只发生在<b>用户什么都还没看到</b>的时候。
     *
     * <p>指数退避 + 抖动（jitter）：没有抖动的话，一次上游抖动会让所有客户端
     * <b>在同一毫秒一起重试</b>，把刚恢复的上游再次打垮（惊群）。
     */
    private Retry retryPolicy() {
        return Retry.backoff(resilience.maxRetryAttempts(),
                        Duration.ofMillis(resilience.retryBackoffMillis()))
                .maxBackoff(MAX_BACKOFF)
                .jitter(0.5)
                .filter(this::shouldRetry)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private boolean shouldRetry(Throwable error) {
        if (!(error instanceof UpstreamFailure failure)) {
            // 没经过 ErrorClassifier 的异常不该被重试 —— 它要么是我们的 bug，要么是分类漏了。
            return false;
        }
        return failure.kind().retryable() && !failure.afterFirstByte();
    }

    /**
     * 取出分类结果。
     *
     * <p><b>★ 已经分过类就原样用，绝不"再分一次" ★</b>
     * <p>二次分类会带上调用方此刻知道的上下文（这里是 {@code false}），
     * 把上游那一刻才知道的事实（是否已吐字节）覆盖掉。
     * {@link UpstreamFailure#withContext} 已经做了单调保护，但这里更彻底：
     * <b>事实只有一个来源，拿到就用。</b>
     */
    private UpstreamFailure asFailure(String channel, Throwable error) {
        if (error instanceof UpstreamFailure failure) {
            return failure;
        }
        return ErrorClassifier.classify(channel, error, false);
    }

    /**
     * 把异常翻译成指标标签。
     *
     * <p><b>用"机器码"而不是异常类名</b>：类名会随重构变化（改名、拆包），
     * 而看板上的查询是写死的 —— 一次无意的重命名就能让整个告警面板变成空白。
     * 标签值是<b>对外契约的一部分</b>，它应该跟 `code` 字段一样稳定。
     */
    private String errorKind(Throwable error) {
        if (error instanceof UpstreamFailure failure) {
            return failure.kind().code();
        }
        if (error instanceof InsufficientQuotaException) {
            return "insufficient_quota";
        }
        if (error instanceof BillingUnavailableException) {
            return "billing_unavailable";
        }
        if (error instanceof NoHealthyChannelException) {
            return "no_healthy_channel";
        }
        return error.getClass().getSimpleName();
    }

    /**
     * 给 span 打标签。<b>只在 span 真的在录制时才写</b>。
     *
     * <p>采样率降到 1% 后，99% 的请求拿到的是一个 non-recording span；
     * 对它调用 {@code setAttribute} 虽然不会报错，但会白白构造字符串、走一遍 SDK 的判断。
     * 网关是<b>每个 token 都要过一遍</b>的地方，这种"看起来无害"的分配值得挡掉。
     *
     * <p><b>标签里不放 prompt / 用户输入</b>：span 会被导出到第三方追踪系统，
     * 那等于把用户对话内容复制了一份到另一个存储里。要定位问题，clientId + channel + model 已经够了。
     */
    private void tag(Span span, String key, String value) {
        if (span != null && !span.isNoop() && value != null) {
            span.tag(key, value);
        }
    }

    private void tag(Span span, String key, long value) {
        if (span != null && !span.isNoop()) {
            span.tag(key, value);
        }
    }

    private void tag(Span span, String key, boolean value) {
        if (span != null && !span.isNoop()) {
            span.tag(key, value);
        }
    }

    /**
     * 从 Reactor Context 里取出当前请求的 span。
     *
     * <p><b>为什么要绕这一圈</b>：WebFlux 的请求观察（observation）由框架放进 Reactor Context，
     * 但 trace 包里并没有把 OTel Context 注册成 ThreadLocalAccessor。
     * 也就是说，<b>在异步回调线程上 {@code Span.current()} 会返回 noop</b> ——
     * 这解释了"本地单体测试标签都正常，一上 WebFlux 就全空"。
     *
     * <p>路径是：Reactor Context → observation → {@link TracingObservationHandler.TracingContext} → span。
     * 这三步都是公开 API，不是内部字段反射；升级 Micrometer 时编译器会替我们发现断裂。
     *
     * @return 当前 span；不在 HTTP 请求上下文里（例如单元测试直接调用 service）时返回 {@code null}
     */
    private Span spanFrom(ContextView context) {
        Object value = context.hasKey(ObservationThreadLocalAccessor.KEY)
                ? context.get(ObservationThreadLocalAccessor.KEY)
                : null;
        if (!(value instanceof Observation observation)) {
            return null;
        }
        TracingObservationHandler.TracingContext tracingContext = observation.getContext()
                .getOrDefault(TracingObservationHandler.TracingContext.class,
                        new TracingObservationHandler.TracingContext());
        return tracingContext.getSpan();
    }

    /**
     * 把 traceId / spanId 写进 Reactor Context，让下游算子（上游调用、重试、计费）的日志都能带上它们。
     *
     * <p><b>为什么不能只注册一个 MDC ThreadLocalAccessor 就完事</b>：
     * 那个访问器的职责是"在 A 线程捕获、在 B 线程恢复"，<b>前提是 A 线程的 MDC 里已经有值</b>。
     * 但 WebFlux 的 observation 是在订阅过程中才开始的，框架写 Reactor Context 的那一刻
     * MDC 还是空的 —— 捕获到的就是一张空表。这里相当于<b>补一次捕获</b>：
     * 等我们拿到了 span（此时 traceId 一定存在），再把这张表放进 Context。
     *
     * <p>只放 traceId/spanId，不放整个 MDC：理由见 {@code TracingConfig}。
     * span 为 null（单元测试直接 new service）时原样返回，不引入任何行为变化。
     */
    private <T> Mono<T> withTraceMdc(Mono<T> mono, Span span) {
        Map<String, String> mdc = traceMdc(span);
        if (mdc == null) {
            return mono;
        }
        return mono.contextWrite(context -> context.put(Slf4jThreadLocalAccessor.KEY, mdc));
    }

    private <T> Flux<T> withTraceMdc(Flux<T> flux, Span span) {
        Map<String, String> mdc = traceMdc(span);
        if (mdc == null) {
            return flux;
        }
        return flux.contextWrite(context -> context.put(Slf4jThreadLocalAccessor.KEY, mdc));
    }

    private Map<String, String> traceMdc(Span span) {
        if (span == null || span.isNoop() || span.context() == null) {
            return null;
        }
        return Map.of(
                "traceId", span.context().traceId(),
                "spanId", span.context().spanId());
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private int messageCount(ChatCompletionRequest request) {
        return request.messages() == null ? 0 : request.messages().size();
    }

    private String summarizeUsage(ChatCompletionResponse response) {
        if (response.usage() == null) {
            return "未知";
        }
        return response.usage().promptTokens() + "+" + response.usage().completionTokens()
                + "=" + response.usage().totalTokens();
    }

    /** 预留：G9 会把请求 ID 放进 Reactor Context，日志里带上它才能把一次请求的几条日志串起来 */
    public static Context withRequestId(String requestId) {
        return Context.of("requestId", requestId);
    }
}
