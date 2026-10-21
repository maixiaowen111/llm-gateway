package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>渠道配置：网关"有哪些渠道"的唯一声明处。</b>G3-3b 的产物，G6 扩了路由字段。
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
 * <p><b>★★ G3c 的补丁：并非所有厂商都是 OpenAI 兼容协议 ★★</b>
 * <p>Anthropic 的 Messages API 就是反例：路径是 {@code /v1/messages}、
 * 鉴权头是 {@code x-api-key}、请求体用 {@code max_tokens} + 顶层 {@code system}、
 * 响应体是 {@code content: [{type: "text", text: ...}]}。
 * 这种差异<b>没法靠配置表达</b>，必须有一个单独的实现类。
 * <p>所以 {@link Channel#type()} 就是"选哪个实现类"的开关：
 * <ul>
 *   <li>{@code openai}（默认）→ {@code OpenAiCompatibleProvider}，一个类服务 N 家；</li>
 *   <li>{@code anthropic} → {@code AnthropicMessagesProvider}，协议真的不一样。</li>
 * </ul>
 * <b>注意这两条路是并存的，不是替换关系。</b>SPI 的价值恰恰在这里：
 * 99% 的厂商用一份通用实现偷懒，剩下 1% 不被那份通用实现绑架。
 *
 * <p><b>为什么用 record（构造器绑定）</b>：配置是<b>只读</b>的 ——
 * 启动时绑一次，之后谁也不该改它。<b>用 record 让"不可变"由语言保证，
 * 而不是靠约定</b>（类 + setter 的话，任何人都能在运行时把 base-url 改掉，
 * 而那种 bug 极难查）。Spring Boot 3 原生支持 record 的构造器绑定。
 */
@ConfigurationProperties(prefix = "gateway.providers")
public record ProviderProperties(String defaultChannel, Map<String, Channel> channels, Routing routing) {

    /** 默认分组名。所有没写 {@code group} 的渠道都落在这里 */
    public static final String DEFAULT_GROUP = "default";

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
        routing = routing == null ? new Routing(false, true, true, "smooth-weighted") : routing;
    }

    /**
     * 单个渠道的连接参数 + 路由参数。
     *
     * <p><b>注意这里没有 {@code name} 字段</b> —— 渠道名就是
     * {@code channels} 这个 map 的 <b>key</b>（YAML 里写的那一行）。
     * <b>同一个事实只能有一个来源</b>：如果 map 的 key 叫 {@code deepseek}、
     * 而对象里还有个 {@code name: deepseek}，两份就可能对不上，
     * 而且对不上的时候你根本不知道该信哪个。
     *
     * @param type        实现类型：{@code openai}（默认）或 {@code anthropic}。G3c
     * @param baseUrl     上游根地址
     * @param apiKey      上游密钥；为空不阻止启动，但会 WARN（见 ProviderConfig）
     * @param model       客户端没指定 model 时的兜底
     * @param weight      G6：加权轮询的权重。<b>0 表示不参与自动分流</b>（但仍可被显式指定）
     * @param enabled     G6：是否参与路由。运行期还能通过 admin 接口改
     * @param models      G6：这个渠道"认领"的模型名。请求里的 model 命中就优先走它
     * @param group       G6：分组。<b>故障转移只在同组内发生</b>，不会把订单流量甩到
     *                    给客服准备的小模型上
     * @param maxTokens   本渠道允许的最大输出 token。为空时用 billing 的默认值
     * @param streamUsage 是否要求上游在流式响应里返回 usage（G7 结算要用）。
     *                    OpenAI 协议靠 {@code stream_options.include_usage}；
     *                    对不支持这个字段的厂商，把它设成 false，网关会退回估算并 WARN
     */
    public record Channel(
            String type,
            String baseUrl,
            String apiKey,
            String model,
            Integer weight,
            Boolean enabled,
            List<String> models,
            String group,
            Integer maxTokens,
            Boolean streamUsage
    ) {

        public Channel {
            type = (type == null || type.isBlank())
                    ? "openai"
                    : type.trim().toLowerCase(Locale.ROOT);
            weight = weight == null ? 1 : weight;
            enabled = enabled == null || enabled;
            models = models == null ? List.of() : List.copyOf(models);
            group = (group == null || group.isBlank()) ? DEFAULT_GROUP : group;
            streamUsage = streamUsage == null || streamUsage;

            // 负权重没有意义，而且会让平滑加权算法的 currentWeight 变成一个负数陷阱。
            // 这里直接炸，而不是"纠正成 0" —— 配置写错就该在启动时被看见。
            if (weight < 0) {
                throw new IllegalStateException("渠道权重不能为负，当前值 = " + weight);
            }
            if (maxTokens != null && maxTokens <= 0) {
                throw new IllegalStateException("渠道 max-tokens 必须大于 0，当前值 = " + maxTokens);
            }
        }
    }

    /**
     * G6 的路由开关。
     *
     * @param autoRoute           <b>默认 false</b>：不指定 provider 时仍走 default-channel，
     *                            保持 G3b 的行为不变。打开后才启用"按 model / 权重自动分流"。
     *                            <b>为什么默认关闭</b>：加权路由会改变"每个请求打给谁"，
     *                            这是一次行为变更，不该因为升级了 jar 就悄悄发生。
     * @param failoverEnabled     <b>故障转移总开关</b>。默认 true。关掉后任何失败都直接返回，
     *                            <b>请求绝不会因为"主选挂了"而落到另一家厂商头上</b>。
     *                            合规/对账场景需要这个 —— "你答应过我这条数据只走这家"，
     *                            比可用性更重要
     * @param failoverOnExplicit  显式指定了 provider 时，它挂了要不要往同组备份渠道转移。
     *                            默认 true。设 false 表示"客户点名要它，失败就失败"——
     *                            对 A/B 实验、灰度对比这类场景是必要的。
     *                            ⚠️ 它只在 {@code failoverEnabled=true} 时有意义（是更细的一层约束）
     * @param strategy            目前只有 {@code smooth-weighted}。留字段是为了以后加
     *                            least-connections 时不用改配置结构
     */
    public record Routing(boolean autoRoute,
                          boolean failoverEnabled,
                          boolean failoverOnExplicit,
                          String strategy) {

        public Routing {
            strategy = (strategy == null || strategy.isBlank()) ? "smooth-weighted" : strategy;
        }
    }
}
