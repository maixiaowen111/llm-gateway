package io.github.maixiaowen.gateway.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * <b>G13：让 traceId / spanId 在 WebFlux 的异步线程之间跟着走。</b>
 *
 * <p><b>★ 为什么光有 micrometer-tracing 还不够 ★</b>
 * <p>OTel 的 span 作用域本质上是 ThreadLocal。WebFlux 一个请求会跨多个线程：
 * 接收请求的 {@code webflux-http-nio-x}、执行上游调用的 {@code reactor-http-nio-x}、
 * 以及各种超时/重试调度器。span 在 A 线程 attach，到 B 线程的日志里就没了 ——
 * 现象是<b>日志里的 tracing 字段时有时无，或者整段为空</b>。
 *
 * <p>Reactor 的解法是 {@code context-propagation}：把 ThreadLocal 的值捕获进 Reactor Context，
 * 在每一次回调执行前恢复、执行后清理。但<b>框架只自动注册 Observation 的访问器</b>，
 * MDC（traceId/spanId 就写在这里）需要应用自己注册。
 *
 * <p><b>为什么只注册 traceId/spanId 两个 key，而不是整个 MDC</b>：
 * 整个 MDC 会把业务随手放进去的东西也复制进每个回调线程 ——
 * 一旦某个字段是请求级的大对象，就会在整条异步链路上被反复传递。
 * <b>只传播明确需要跨线程的字段，是一道成本很低的防线。</b>
 *
 * <p>即使这里没注册，{@code TracingPropagationTest} 里的 traceparent 透传仍然成立；
 * 丢掉的只是"日志可读性"，不是链路本身。这两件事必须分开验证，否则很容易用一个通过掩盖另一个失败。
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    /** 与 logback-spring.xml 里 %X{traceId} / %X{spanId} 一一对应 */
    private static final String[] MDC_KEYS = {"traceId", "spanId"};

    @PostConstruct
    void registerMdcThreadLocalAccessor() {
        ContextRegistry registry = ContextRegistry.getInstance();
        boolean alreadyRegistered = registry.getThreadLocalAccessors().stream()
                .anyMatch(accessor -> Slf4jThreadLocalAccessor.KEY.equals(accessor.key()));
        if (alreadyRegistered) {
            // 重复注册会替换掉已有实例；如果将来升级 Boot 后它开始自动注册，
            // 我们不应该悄悄覆盖框架的行为 —— 保留框架的，只记一条日志。
            log.info("[链路追踪] MDC 传播已由其他组件注册（key={}），跳过", Slf4jThreadLocalAccessor.KEY);
            return;
        }
        registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor(MDC_KEYS));
        log.info("[链路追踪] 已注册 MDC 跨线程传播：keys={}。"
                + "没有它，WebFlux 回调线程上的 traceId 会是空的", java.util.Arrays.toString(MDC_KEYS));
    }
}
