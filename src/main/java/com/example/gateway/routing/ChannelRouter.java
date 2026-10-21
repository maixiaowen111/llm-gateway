package com.example.gateway.routing;

import com.example.gateway.config.ProviderProperties;
import com.example.gateway.dto.ChatCompletionRequest;
import com.example.gateway.provider.Provider;
import com.example.gateway.provider.ProviderRegistry;
import com.example.gateway.resilience.ChannelCircuitBreakers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>G6：从"有哪些渠道"到"这次该走哪几条"。</b>
 *
 * <p><b>它和 {@link ProviderRegistry} 的分工</b>：
 * 注册表回答"渠道名 → Provider 对象"，路由器回答
 * "这个请求的<b>候选列表</b>是什么、按什么顺序试"。
 * <p>为什么拆成两层：注册表是<b>静态事实</b>（启动时就有，永远不变），
 * 路由是<b>动态决策</b>（取决于请求、权重、健康度、运行期开关）。
 * 把动态决策塞进注册表，会让注册表变成一个"什么都得知道"的上帝类。
 *
 * <p><b>★ 候选列表，而不是单个渠道 ★</b>
 * <p>这是 G5 和 G6 的交汇：G5 需要"失败了换下一个"，那它就要知道"下一个是谁"。
 * 如果路由器只返回一个 Provider，失败转移就得在服务层重新算一遍 ——
 * <b>而重新算的那一遍，和第一次算的规则很容易不一致。</b>
 * 返回有序候选列表，让"选谁"和"换谁"共用同一套规则。
 *
 * <p><b>★ 三种路由模式 ★</b>
 * <ol>
 *   <li><b>显式指定</b>（请求里带 {@code provider}）：点名优先，备份只从<b>同组</b>里找；</li>
 *   <li><b>按 model 推断</b>（自动路由开启时）：请求里的 model 命中某渠道的 {@code models} 就优先它；</li>
 *   <li><b>加权兜底</b>：前两者都没有结论时，在默认组里按平滑加权轮询挑。</li>
 * </ol>
 * <b>为什么备份只在同组里找</b>：把订单系统的流量转移到"给客服准备的小模型"上，
 * 比直接返回 503 更糟 —— 用户会得到一个看起来正常、实际完全不对的答案。
 * <b>故障转移不能改变业务语义。</b>
 */
public class ChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChannelRouter.class);

    /** 请求里 provider 写这个值 = "我不点名，你按策略挑" */
    public static final String AUTO = "auto";

    private final ProviderRegistry registry;
    private final ProviderProperties properties;
    private final ChannelStateRegistry states;
    private final ChannelCircuitBreakers circuitBreakers;
    private final SmoothWeightedRoundRobin roundRobin = new SmoothWeightedRoundRobin();

    public ChannelRouter(ProviderRegistry registry,
                         ProviderProperties properties,
                         ChannelStateRegistry states,
                         ChannelCircuitBreakers circuitBreakers) {
        this.registry = registry;
        this.properties = properties;
        this.states = states;
        this.circuitBreakers = circuitBreakers;
    }

    /**
     * 给一个请求算出<b>有序</b>候选渠道。第一个是主选，后面是故障转移顺序。
     *
     * @throws com.example.gateway.exception.UnknownProviderException 显式指定的渠道不存在（400）
     * @throws NoHealthyChannelException 所有候选都被禁用 / 熔断（503）
     */
    public List<Provider> candidates(ChatCompletionRequest request) {
        List<Provider> ordered = new ArrayList<>();

        String requested = request.provider();
        boolean explicit = StringUtils.hasText(requested) && !AUTO.equalsIgnoreCase(requested);

        if (explicit) {
            // resolve 而不是 resolveOrDefault：点名了不存在的渠道，必须报错，不能静默回落。
            // （这个决策在 G3b 的注册表里已经写过理由，这里只是复用。）
            Provider primary = registry.resolve(requested);
            ordered.add(primary);
            if (properties.routing().failoverEnabled() && properties.routing().failoverOnExplicit()) {
                appendPeers(ordered, states.groupOf(primary.name()));
            }
        } else if (properties.routing().autoRoute()) {
            List<Provider> byModel = byModel(request.model());
            if (!byModel.isEmpty()) {
                ordered.addAll(orderWeighted(byModel, "model:" + request.model()));
                log.debug("[路由] 按 model 命中 {}，候选={}", request.model(), names(ordered));
            } else {
                List<Provider> group = byGroup(ProviderProperties.DEFAULT_GROUP);
                ordered.addAll(orderWeighted(group, ProviderProperties.DEFAULT_GROUP));
                log.debug("[路由] 未指定渠道且无 model 映射，默认组加权候选={}", names(ordered));
            }
        } else {
            // G3b 的旧行为：默认渠道优先，失败时同组备份顶上。
            // 默认关闭 autoRoute 就是为了让升级 jar 不悄悄改变"请求打给谁"。
            Provider primary = registry.defaultProvider();
            ordered.add(primary);
            if (properties.routing().failoverEnabled()) {
                appendPeers(ordered, states.groupOf(primary.name()));
            }
        }

        if (ordered.isEmpty()) {
            throw new NoHealthyChannelException(
                    "没有任何渠道被配置为可用。请检查 gateway.providers.channels。");
        }

        // 去重（显式 + 同组备份可能把主渠道加两次）后过滤：禁用、熔断打开、无权限
        Set<String> seen = new LinkedHashSet<>();
        List<Provider> healthy = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();

        for (Provider provider : ordered) {
            String name = provider.name();
            if (!seen.add(name)) {
                continue;
            }
            if (!states.isEnabled(name)) {
                rejected.put(name, "已禁用");
            } else if (!circuitBreakers.allows(name)) {
                rejected.put(name, "熔断打开");
            } else {
                healthy.add(provider);
            }
        }

        if (healthy.isEmpty()) {
            throw new NoHealthyChannelException(
                    "没有可用渠道。被跳过的渠道及原因：" + rejected
                            + "。★ 这不是上游的问题，是网关当前没有一条能走的路（503）★");
        }

        if (!rejected.isEmpty()) {
            log.warn("[路由] 本次跳过渠道 {}，实际候选={}", rejected, names(healthy));
        }
        return healthy;
    }

    // ========================================================================
    // 候选集合
    // ========================================================================

    /** 同组里除已加入的渠道之外的全部渠道，按权重从高到低排 */
    private void appendPeers(List<Provider> ordered, String group) {
        Set<String> existing = new LinkedHashSet<>();
        for (Provider provider : ordered) {
            existing.add(provider.name());
        }
        List<Provider> peers = new ArrayList<>();
        for (Provider provider : byGroup(group)) {
            if (!existing.contains(provider.name())) {
                peers.add(provider);
            }
        }
        peers.sort(Comparator.comparingInt((Provider p) -> states.weightOf(p.name())).reversed());
        ordered.addAll(peers);
    }

    private List<Provider> byGroup(String group) {
        List<Provider> result = new ArrayList<>();
        for (Provider provider : registry.providers()) {
            if (group.equals(states.groupOf(provider.name()))) {
                result.add(provider);
            }
        }
        return result;
    }

    /**
     * 按 model 名找"认领它的渠道"。
     *
     * <p>匹配规则刻意简单：<b>精确匹配 {@code models} 列表，或匹配渠道的 default model</b>。
     * 不做前缀/正则匹配 —— 模型名一旦要"猜"，就会出现
     * "deepseek-chat 被 deepseek-chat-v3 抢走"这种没人能预期的路由。
     * <b>路由规则越简单，出问题时越好解释。</b>
     */
    private List<Provider> byModel(String model) {
        if (!StringUtils.hasText(model)) {
            return List.of();
        }
        List<Provider> matched = new ArrayList<>();
        for (Provider provider : registry.providers()) {
            ProviderProperties.Channel channel = properties.channels().get(provider.name());
            if (channel == null) {
                continue;
            }
            boolean claims = channel.models().contains(model)
                    || model.equals(channel.model());
            if (claims) {
                matched.add(provider);
            }
        }
        return matched;
    }

    /**
     * 加权排序：第一个由平滑加权轮询决定，其余按权重降序。
     *
     * <p><b>为什么"其余"不也走轮询</b>：轮询的语义是"主选均匀分布"；
     * 备份只在主选失败时才被用到，它们之间的顺序用固定权重排序更可预测。
     * 若对备份也轮询，同一次故障中"先试谁"会随机变化，排障时很难复盘。
     */
    private List<Provider> orderWeighted(List<Provider> candidates, String groupKey) {
        List<Provider> weighted = new ArrayList<>();
        Map<String, Integer> weightMap = new LinkedHashMap<>();
        for (Provider provider : candidates) {
            int weight = states.weightOf(provider.name());
            if (weight > 0) {
                weighted.add(provider);
                weightMap.put(provider.name(), weight);
            }
        }

        if (weighted.isEmpty()) {
            // 所有权重都是 0：仍然返回原列表（它们可以在显式指定时被用），但不参与轮询。
            return candidates;
        }

        String first = roundRobin.select(groupKey, weightMap);
        List<Provider> ordered = new ArrayList<>(weighted.size());
        weighted.stream()
                .filter(p -> p.name().equals(first))
                .findFirst()
                .ifPresent(ordered::add);
        weighted.stream()
                .filter(p -> !p.name().equals(first))
                .sorted(Comparator.comparingInt((Provider p) -> states.weightOf(p.name())).reversed())
                .forEach(ordered::add);
        return ordered;
    }

    // ========================================================================
    // 给 admin / 监控看的面板
    // ========================================================================

    public List<ChannelInfo> channelInfos() {
        List<ChannelInfo> infos = new ArrayList<>();
        Map<String, String> circuit = circuitBreakers.snapshot();
        Map<String, Float> failureRates = circuitBreakers.failureRates();

        for (Provider provider : registry.providers()) {
            String name = provider.name();
            ProviderProperties.Channel channel = properties.channels().get(name);
            infos.add(new ChannelInfo(
                    name,
                    channel == null ? "openai" : channel.type(),
                    channel == null ? null : channel.model(),
                    channel == null ? List.of() : channel.models(),
                    states.groupOf(name),
                    states.weightOf(name),
                    states.isEnabled(name),
                    circuit.getOrDefault(name, "UNKNOWN"),
                    failureRates.getOrDefault(name, -1f)));
        }
        return infos;
    }

    public String groupOf(String channel) {
        return states.groupOf(channel);
    }

    private List<String> names(List<Provider> providers) {
        return providers.stream().map(Provider::name).toList();
    }

    /** 管理端看到的渠道视图。字段刻意是"人看得懂"的，不是内部对象 */
    public record ChannelInfo(
            String name,
            String type,
            String defaultModel,
            List<String> models,
            String group,
            int weight,
            boolean enabled,
            String circuitState,
            float failureRatePercent
    ) {
    }
}
