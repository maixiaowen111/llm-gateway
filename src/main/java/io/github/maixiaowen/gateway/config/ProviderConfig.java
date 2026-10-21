package io.github.maixiaowen.gateway.config;

import io.github.maixiaowen.gateway.provider.AnthropicMessagesProvider;
import io.github.maixiaowen.gateway.provider.OpenAiCompatibleProvider;
import io.github.maixiaowen.gateway.provider.Provider;
import io.github.maixiaowen.gateway.provider.ProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>把"配置里写了几个渠道"变成"容器里有几个渠道对象"。</b>G3-3b 的产物。
 *
 * <p><b>这十几行是 3b 的核心，因为它一句话说清了整个设计：</b>
 * <pre>
 *   一个实现类（OpenAiCompatibleProvider） × N 份配置（ProviderProperties） = N 个渠道
 * </pre>
 *
 * <p><b>它有存在的必要吗？—— 有，而且必须有。</b>
 * <p>3a 时 {@code ProviderRegistry} 是 {@code @Component}，靠 Spring 的<b>集合注入</b>
 * 自动收集所有 {@code Provider} Bean。那套机制很好，但它有个前提：
 * <b>Bean 的种类和数量在编译期就定死了</b>（组件扫描看的是类，不是配置）。
 *
 * <p>而 3b 要的是"<b>YAML 里写几个渠道就有几个</b>"。<b>Spring 扫描不出配置文件里的循环</b> ——
 * 所以这里必须有人<b>手工把配置翻译成对象</b>。这个类就是那个人。
 *
 * <p><b>⚠️ 一个不显眼但会致命的顺序问题（值得单独记住）</b>
 * <p>{@link WebClient.Builder} 是<b>可变的</b>：{@code builder.baseUrl(x)} 会改掉它自己的状态。
 * 下面循环里把<b>同一个 builder</b> 传给了 N 个 Provider，
 * 每个 Provider 构造时调 {@code builder.baseUrl(自己的地址).build()}。
 *
 * <p>这在现在是<b>安全的</b>，因为 {@code build()} 会<b>当场把当前配置复制走</b>，
 * 之后 builder 再怎么变都不影响已经造出来的 WebClient。
 * <b>但如果哪天有人把 {@code build()} 挪到循环外面</b>（比如想省一次构建），
 * 那 N 个渠道会<b>全部指向同一个地址</b> —— 而且不报错，只是所有请求都打到最后一个渠道上。
 *
 * <p><b>这类"顺序 + 可变对象"的 bug 不报错、只出错数据，是最贵的一种。</b>
 * 写下来是为了让下一个人（包括三个月后的你）知道这里踩过。
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);

    /**
     * 唯一的 Bean 方法：把配置里的渠道逐个造出来，连注册表一起交付。
     *
     * <p><b>注意这里连 {@code ProviderRegistry} 也是在这造的</b>，
     * 而不是让它自己 {@code @Component} —— 因为它<b>必须拿到这个列表</b>，
     * 而列表是运行时才生成的。让"谁生产列表"和"谁消费列表"待在同一个方法里，
     * 依赖关系一眼可见，也不用担心 Spring 的注入顺序。
     */
    @Bean
    public ProviderRegistry providerRegistry(ProviderProperties properties,
                                             WebClient.Builder webClientBuilder,
                                             ObjectMapper objectMapper,
                                             @Value("${gateway.upstream.idle-timeout-ms}") long idleTimeoutMs,
                                             @Value("${gateway.billing.default-max-tokens:1024}") int defaultMaxTokens) {

        Map<String, ProviderProperties.Channel> channels = properties.channels();

        if (channels.isEmpty()) {
            // 和 3a 的 fail-fast 一脉相承：没有渠道的网关不能装作能工作。
            // 走到这里说明 application.yml 里的 gateway.providers.channels 被删空了。
            throw new IllegalStateException(
                    "没有配置任何上游渠道。请检查 gateway.providers.channels 配置项。");
        }

        List<Provider> providers = new ArrayList<>(channels.size());
        for (Map.Entry<String, ProviderProperties.Channel> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ProviderProperties.Channel channel = entry.getValue();

            if (!StringUtils.hasText(channel.baseUrl())) {
                throw new IllegalStateException(
                        "渠道 [" + channelName + "] 缺少 base-url 配置。");
            }

            // ★ 密钥为空不阻止启动，但要喊一声。
            // 为什么不像 base-url 那样直接拒绝启动：本地开发（不配 .env）时
            // 确实需要"能起来、只是调不通上游"这个状态来验证别的东西。
            // 但线上密钥为空 = 每个请求都 401，那时你希望日志里有明确的一句话。
            if (!StringUtils.hasText(channel.apiKey())) {
                log.warn("[渠道未配置密钥] channel={} —— 调用该渠道会被上游拒绝（401）。"
                        + "本地开发可忽略；若是生产环境，请检查密钥注入。", channelName);
            }

            // ★ G3c：这里是"通用实现"和"专用实现"的分岔口。
            //   openai    → 一份代码吃掉 N 家 OpenAI 兼容厂商
            //   anthropic → 协议不同，必须单独一个类
            //   两者的共同点：<b>外面那一圈（Controller / Registry / DTO）完全不知道这里分岔了</b>。
            int channelMaxTokens = channel.maxTokens() == null ? defaultMaxTokens : channel.maxTokens();
            Provider provider = switch (channel.type()) {
                case "openai" -> new OpenAiCompatibleProvider(
                        channelName,          // ← 渠道名就是 map 的 key，不另设字段
                        channel.baseUrl(),
                        channel.apiKey(),
                        channel.model(),
                        idleTimeoutMs,
                        channel.streamUsage(),
                        webClientBuilder,     // ← 注入的 builder 已带 G1-1d 的连接/读超时
                        objectMapper);
                case "anthropic" -> new AnthropicMessagesProvider(
                        channelName,
                        channel.baseUrl(),
                        channel.apiKey(),
                        channel.model(),
                        channelMaxTokens,
                        idleTimeoutMs,
                        webClientBuilder,
                        objectMapper);
                default -> throw new IllegalStateException(String.format(
                        "渠道 [%s] 的 type=[%s] 不支持。目前支持：openai、anthropic。"
                                + "协议不同就必须新写一个 Provider 实现 —— 这不是配置能解决的。",
                        channelName, channel.type()));
            };

            providers.add(provider);
        }

        // 构造器里的三重 fail-fast（默认渠道存在 / 不重名 / 名字非空）在这里生效。
        // 注意"重名"在这个设计下已经不可能发生了 —— map 的 key 天然唯一。
        // 那条校验留着，是为了将来有人写出第二个"手工注册"的路径时仍然兜得住。
        return new ProviderRegistry(providers, properties.defaultChannel());
    }
}
