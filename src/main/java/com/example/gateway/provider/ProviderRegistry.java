package com.example.gateway.provider;

import com.example.gateway.exception.UnknownProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 如果配置里写了 {@code gateway.providers.default-channel=qwen}，但
 * {@code channels} 下面没有叫 {@code qwen} 的那一项，本类<b>直接让应用启动失败</b>。
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
 * <p><b>⚠️ G3b 改了它"怎么被创建"：从 {@code @Component} 变成由配置类手工创建。</b>
 * <p>3a 时它是 {@code @Component}，靠 Spring 的<b>集合注入</b>
 * （构造器要 {@code List<Provider>}，Spring 把所有实现塞进来）。
 * 那时这样做是对的 —— 有且只有一家厂商，一个 Bean 就够了。
 *
 * <p>3b 之后不行了：渠道数量<b>由配置决定</b>（YAML 里写几个就有几个），
 * 而 Spring 的组件扫描是<b>编译期固定的</b> —— 它没法"看 YAML 决定 new 几个 Bean"。
 * 所以改由 {@code config/ProviderConfig} 读配置、循环创建、
 * 把列表直接交给本类的构造器。
 *
 * <p><b>注意本类的构造器签名一个字没改</b> ——
 * 它依然只是"给一个渠道列表和一个默认名"。<b>它从来就不依赖 Spring，</b>
 * 只是之前借 Spring 的手拿参数而已。所以 G3a 写的 6 个单测<b>一行都不用改</b>：
 * <b>"这个类不依赖框架"这个事实，在它换掉创建方式的时候救了它一次。</b>
 */
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    /** 用 LinkedHashMap 而不是 HashMap：日志里列渠道时顺序稳定，好对比 */
    private final Map<String, Provider> providersByName;
    private final String defaultProviderName;

    public ProviderRegistry(List<Provider> providers, String defaultProviderName) {
        this.providersByName = indexByName(providers);
        this.defaultProviderName = defaultProviderName;

        // ★ fail-fast：默认渠道必须真实存在
        if (!providersByName.containsKey(defaultProviderName)) {
            throw new IllegalStateException(String.format(
                    "默认渠道 [%s] 不存在。已注册的渠道：%s。"
                            + "请检查 gateway.providers.default-channel 配置，"
                            + "并确认它指向的是 gateway.providers.channels 下面真实存在的一项。",
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
     * 默认渠道。
     *
     * <p>3a 阶段 {@code ChatController} 无条件走它。3b 之后它退居<b>兜底</b>：
     * 请求里没指定渠道时才用它（见 {@link #resolveOrDefault(String)}）。
     */
    public Provider defaultProvider() {
        return providersByName.get(defaultProviderName);
    }

    /**
     * <b>按请求路由（G3b 的核心）。</b>请求里指定了渠道就用指定的，没指定就走默认。
     *
     * <p><b>★ 这里做了一个刻意的设计决策，值得单独讲：</b>
     * <ul>
     *   <li><b>没指定</b>（{@code null} / 空白）→ <b>静默回落默认渠道</b>。
     *       因为绝大多数客户端（OpenAI SDK、LangChain4j）<b>根本不知道"渠道"这个概念</b>，
     *       它们只会发 model 和 messages。对它们来说"没指定"是<b>正常情况，不是错误</b>，
     *       必须能跑通 —— 否则网关就把所有标准客户端都拒之门外了。</li>
     *   <li><b>指定了但不存在</b>（{@code "kimi"} 这种）→ <b>抛异常，返 400</b>，
     *       <b>绝不静默回落默认</b>。因为指定了不存在的名字<b>永远是调用方的 bug</b>，
     *       而静默回落会让这个 bug 变成"钱从错误的渠道花出去了，而且没人知道"。</li>
     * </ul>
     *
     * <p><b>两者的区别不是"宽容 vs 严格"，是"能不能判断调用方的意图"</b>：
     * 没指定 —— 意图明确（"随便，你看着办"）；
     * 指定了不存在的 —— 意图明确（"我要 kimi"）但<b>我们满足不了，必须说</b>。
     *
     * <p>⚠️ <b>另一种常见做法是"指定了不存在的就回落默认"</b>（很多网关这么干，
     * 理由是"保证可用性"）。它在生产上有个致命的副作用：
     * <b>调用方把渠道名拼错三个月，日志里一条错误都没有</b>，
     * 只是账单一直在别的渠道上出。<b>可用性不能靠"假装请求是对的"来换。</b>
     *
     * @param requestedName 客户端指定的渠道名，可为 null / 空白
     * @throws UnknownProviderException 指定了名字但不存在时（→ 400）
     */
    public Provider resolveOrDefault(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return defaultProvider();
        }
        return resolve(requestedName);
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
