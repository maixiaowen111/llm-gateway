package io.github.maixiaowen.gateway.config;

import io.github.maixiaowen.gateway.auth.ApiKeyStore;
import io.github.maixiaowen.gateway.auth.AuthProperties;
import io.github.maixiaowen.gateway.auth.ClientAuthWebFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <b>G10：客户端鉴权的装配 + 启动自检。</b>
 *
 * <p>和限流/计费一样，<b>能力是否存在由配置决定，不由组件扫描决定</b>：
 * 关着的时候连 {@code ApiKeyStore} 都不会进容器，
 * 这样就不会出现"某个 Controller 偷偷注入了鉴权器、但开关是关的"这种半开状态。
 *
 * <p><b>★ 启动自检的三态，和 {@code RateLimitConfig} 完全同构 ★</b>
 * <ol>
 *   <li><b>关</b> → WARN。强调"任何人都能伪造 X-Client-Id"，
 *       <b>不做静默的能力缺失</b>；</li>
 *   <li><b>开但密钥表为空</b> → 启动失败（{@link AuthProperties} 的紧凑构造器负责）；</li>
 *   <li><b>开且有密钥</b> → INFO，只打印<b>客户端数量</b>，不打印任何摘要或明文。</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "gateway.auth", name = "enabled", havingValue = "true")
    public ApiKeyStore apiKeyStore(AuthProperties properties) {
        return new ApiKeyStore(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway.auth", name = "enabled", havingValue = "true")
    public ClientAuthWebFilter clientAuthWebFilter(ApiKeyStore keys, ObjectMapper objectMapper) {
        return new ClientAuthWebFilter(keys, objectMapper);
    }

    @Bean
    public ApplicationRunner authStartupCheck(AuthProperties properties) {
        return args -> {
            if (!properties.enabled()) {
                log.warn("[客户端鉴权未启用] gateway.auth.enabled=false。"
                        + "★ 网关当前信任请求里的 X-Client-Id，它是客户端自己填的，可以伪造 ★ ——"
                        + "任何人都能换一个 ID 花别人的余额、或让限流器把他当成新客户。"
                        + "生产环境必须设 GATEWAY_AUTH_ENABLED=true 并配置 GATEWAY_AUTH_KEYS。");
                return;
            }

            // 这里不重复解析格式：ApiKeyStore 的构造已经做过严格校验，
            // 能走到这一行说明密钥表是合法的。自检只负责把"生效状态"说出来。
            log.info("[客户端鉴权已启用] 已配置 {} 个客户端、{} 把有效 Key（轮换期可同时存在多把）。"
                            + "保护路径前缀={}，身份来自 API Key；X-Client-Id 不再参与决策。",
                    properties.keys().stream()
                            .map(entry -> entry.substring(0, entry.indexOf(':')))
                            .distinct()
                            .count(),
                    properties.keys().size(),
                    ClientAuthWebFilter.PROTECTED_PREFIX);
        };
    }
}
