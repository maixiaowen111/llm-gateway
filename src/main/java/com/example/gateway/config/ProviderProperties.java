package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>渠道配置：网关"有哪些渠道"的唯一声明处。</b>G3-3b 的产物。
 *
 * <p><b>它为什么存在 —— 先看 3a 留下的那个缺口</b>：
 * 3a 抽出了 {@link com.example.gateway.provider.Provider} SPI，
 * 但当时只有一家厂商，而且 {@code DeepSeekProvider} 是个
 * {@code @Component}，它的 base-url / api-key / model 是用
 * {@code @Value("${deepseek.base-url}")} <b>一个字段一个字段抠出来的</b>。
 *
 * <p>那种写法有个致命问题：<b>一个类只能是一个渠道。</b>
 * 想加第二家，就得再写一个类、把同样的逻辑再抄一遍 ——
 * <b>于是 3a 抽出来的 SPI 白抽了</b>（SPI 的意义是"加厂商不用改已有代码"，
 * 但如果每次加厂商都要新写一个类，成本并没有降下来）。
 *
 * <p><b>真正的事实是</b>：DeepSeek、Moonshot、通义、智谱、SiliconFlow、
 * 本地的 Ollama / vLLM —— <b>它们全都是 OpenAI 兼容协议</b>。
 * 同一个 {@code /chat/completions} 路径、同一个请求体结构、同一个响应结构，
 * <b>差别只有三样：base-url、api-key、模型名。</b>
 *
 * <p>所以正确的做法不是"一家厂商一个类"，而是：
 * <b>一个实现类 × N 份配置 = N 个渠道。</b>
 * 本类就是那"N 份配置"，而
 * {@link com.example.gateway.provider.OpenAiCompatibleProvider} 是那"一个实现类"。
 *
 * <p><b>★ 于是"加第二家厂商"的成本变成了：在 YAML 里加 4 行。</b>
 * 不写类、不改代码、不碰 Controller —— 这才是 SPI 该有的样子。
 *
 * <p><b>顺带兑现了 3a 里的一句 Javadoc</b>：3a 设计 {@code Provider#name()}
 * 时写过"让实现自己声明名字，因为同一个类想注册成两个渠道"——
 * 当时看着像假想的场景，在 3b 变成了主路径。
 *
 * <p><b>为什么用 record（构造器绑定）</b>：配置是<b>只读</b>的 ——
 * 启动时绑一次，之后谁也不该改它。<b>用 record 让"不可变"由语言保证，
 * 而不是靠约定</b>（类 + setter 的话，任何人都能在运行时把 base-url 改掉，
 * 而那种 bug 极难查）。Spring Boot 3 原生支持 record 的构造器绑定。
 */
@ConfigurationProperties(prefix = "gateway.providers")
public record ProviderProperties(String defaultChannel, Map<String, Channel> channels) {

    /**
     * 紧凑构造器：<b>把 null 归一掉，让下游代码不用到处判空</b>。
     *
     * <p>配置项缺失时 Spring 传进来的是 {@code null}（不是空串、不是空 map）。
     * 如果放任 {@code null} 流到 {@code ProviderConfig}，
     * 那里就得写 {@code channels == null ? ... : ...} —— <b>判空逻辑会扩散到每个使用点</b>。
     * <b>在边界处一次性收干净</b>，后面所有代码都可以假设"它一定不是 null"。
     */
    public ProviderProperties {
        defaultChannel = (defaultChannel == null || defaultChannel.isBlank())
                ? "deepseek"
                : defaultChannel;
        channels = (channels == null || channels.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(channels));
    }

    /**
     * 单个渠道的连接参数。
     *
     * <p><b>注意这里没有 {@code name} 字段</b> —— 渠道名就是
     * {@code channels} 这个 map 的 <b>key</b>（YAML 里写的那一行）。
     * <b>同一个事实只能有一个来源</b>：如果 map 的 key 叫 {@code deepseek}、
     * 而对象里还有个 {@code name: deepseek}，两份就可能对不上，
     * 而且对不上的时候你根本不知道该信哪个。
     */
    public record Channel(String baseUrl, String apiKey, String model) {
    }
}
