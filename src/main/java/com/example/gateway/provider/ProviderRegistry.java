package com.example.gateway.provider;

import com.example.gateway.exception.UnknownProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>渠道注册表：把"有哪些渠道"和"默认走哪个"集中到一处。</b>G3-3a 的产物。
 *
 * <p><b>它为什么存在</b>：抽了 {@link Provider} 接口之后，调用方需要一个地方问
 * "给我 DeepSeek 那个渠道" / "给我默认渠道"。这个问题不能由调用方自己回答 ——
 * 否则它又得认识所有具体实现（回到 if-else 堆）。
 * <b>注册表就是那个"唯一知道全部渠道"的地方</b>，其他人只跟它打交道。
 *
 * <p><b>★ 关键设计：启动时就校验默认渠道存在（fail-fast）</b>
 * 如果配置里写了 {@code gateway.providers.default=qwen} 但没有任何
 * {@code name()} 返回 {@code qwen} 的实现，本类<b>直接让应用启动失败</b>。
 *
 * <p>为什么不"启动成功、请求时才报错"：
 * <ul>
 *   <li><b>启动失败是可发现的事故</b> —— 部署流程会拦住，日志一眼看到；</li>
 *   <li><b>请求时才报错是静默的灾难</b> —— 服务看着是健康的、探针是绿的，
 *       但每一个请求都 500。等你发现时可能已经过去几小时。</li>
 * </ul>
 * <b>把错误推到尽可能早的地方，是配置类代码最重要的一条纪律。</b>
 * （这和 G1 里 {@code RequiredEnvValidator} 的思路是同一个。）
 *
 * <p><b>渠道是自己"找上门"的</b>：构造器注入 {@code List<Provider>}，
 * Spring 会把所有实现了 {@link Provider} 的 Bean 全塞进来。
 * 所以<b>新增一家厂商不需要改本类</b> —— 定义一个 Bean 就够了，这正是 SPI 想要的。
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    /** 用 LinkedHashMap 而不是 HashMap：日志里列渠道时顺序稳定，好对比 */
    private final Map<String, Provider> providersByName;
    private final String defaultProviderName;

    public ProviderRegistry(List<Provider> providers,
                            @Value("${gateway.providers.default:deepseek}") String defaultProviderName) {
        this.providersByName = indexByName(providers);
        this.defaultProviderName = defaultProviderName;

        // ★ fail-fast：默认渠道必须真实存在
        if (!providersByName.containsKey(defaultProviderName)) {
            throw new IllegalStateException(String.format(
                    "默认渠道 [%s] 不存在。已注册的渠道：%s。"
                            + "请检查 gateway.providers.default 配置，或确认对应 Provider Bean 已装配。",
                    defaultProviderName, providersByName.keySet()));
        }

        log.info("[渠道注册完成] 默认={}，全部={}", defaultProviderName, providersByName.keySet());
    }

    /**
     * 把 Bean 列表按 {@link Provider#name()} 建索引。
     *
     * <p><b>为什么必须检测重名</b>：两个实现都返回 {@code "deepseek"} 时，
     * 后注册的会<b>静默覆盖</b>前一个 —— 表现是"我改的代码没生效"，
     * 而且极难查（代码明明改了、测试也是绿的，就是跑的不是它）。
     * <b>直接抛异常，比让一个渠道人间蒸发强得多。</b>
     */
    private Map<String, Provider> indexByName(List<Provider> providers) {
        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "没有任何 Provider 被注册。网关至少需要一个上游渠道才能工作。");
        }

        Map<String, Provider> indexed = new LinkedHashMap<>();
        for (Provider provider : providers) {
            String name = provider.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "Provider [" + provider.getClass().getName() + "] 的 name() 返回了空值。"
                                + "渠道标识不能为空 —— 配置和日志都靠它。");
            }
            Provider previous = indexed.put(name, provider);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "渠道标识 [%s] 重复：[%s] 与 [%s] 都用了这个名字。"
                                + "重名会导致其中一个被静默覆盖，请改成不同的 name()。",
                        name, previous.getClass().getName(), provider.getClass().getName()));
            }
        }
        return Map.copyOf(indexed);
    }

    /**
     * 默认渠道。3a 阶段 {@code ChatController} 走的就是它。
     * <p>等 3b 有了第二家厂商，这里会换成"按请求里的 model / provider 字段路由"。
     */
    public Provider defaultProvider() {
        return providersByName.get(defaultProviderName);
    }

    /**
     * 按名字取渠道。
     *
     * @throws UnknownProviderException 名字不存在时。<b>不返回 null</b> ——
     *         返回 null 会让调用方在很远的地方 NPE，而那里已经看不出问题源头了。
     */
    public Provider resolve(String name) {
        Provider provider = providersByName.get(name);
        if (provider == null) {
            throw new UnknownProviderException(name, providersByName.keySet());
        }
        return provider;
    }

    /** 已注册的渠道名，供日志/监控/G9 的渠道面板使用 */
    public Set<String> names() {
        return providersByName.keySet();
    }
}
