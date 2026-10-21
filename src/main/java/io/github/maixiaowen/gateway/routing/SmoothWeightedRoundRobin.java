package io.github.maixiaowen.gateway.routing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>G6：平滑加权轮询（Nginx 的 smooth weighted round-robin）。</b>
 *
 * <p><b>为什么不用"按权重随机"</b>：随机会有<b>短窗口抖动</b>。
 * 权重 5:1 的两条渠道，随机选 6 次完全可能选出 3:3 —— 那一刻的流量分配和配置完全不匹配。
 * 对计费、灰度、容量规划来说，"长期对、短期乱"是不合格的。
 *
 * <p><b>为什么不用"直接按权重排"（5 次 A、1 次 B 循环）</b>：
 * 那样会把 5 个请求连续打到 A，再 1 个到 B —— <b>突发是尖的</b>，
 * 对上游来说就是"一阵一阵地打"。平滑加权轮询的"平滑"两个字，就是为了消掉这个尖峰。
 *
 * <p><b>算法（三行，但值得逐行看懂）</b>：
 * <pre>
 *   每次选择：
 *     1. 每个候选：current += weight
 *     2. 选 current 最大的那个
 *     3. 被选中的那个：current -= totalWeight
 * </pre>
 * 以 A(5) / B(1) 为例：
 * <pre>
 *   轮次   current(A,B)   选   选后
 *    1     (5,1)          A    (0,1)
 *    2     (5,2)          A    (0,2)
 *    3     (5,3)          A    (0,3)
 *    4     (5,4)          A    (0,4)
 *    5     (5,5)          A    (0,5)
 *    6     (5,6)          B    (5,0)
 *    7     (10,1)         A    (5,1)
 * </pre>
 * A 在 6 次里正好 5 次，B 1 次；而且 B 不是"每 6 次里连续排最后"，
 * 它会在一个周期里被均匀地插进去（长周期更明显）。
 *
 * <p><b>★ 线程安全：整个 select 必须同步 ★</b>
 * <p>"读 current → 比大小 → 改 current"三步不是原子的。两个线程同时进来，
 * 可能都选中 A、然后各扣一次 —— 权重分布就偏了。
 * <b>这里加 synchronized 不是保守，是算法本身要求。</b>
 * 代价可以接受：每次选择只做几次整数加法和一次遍历，纳秒级；
 * 它绝对不该跟 IO 放在同一个临界区里（本类只做纯计算，不做任何 IO）。
 */
public class SmoothWeightedRoundRobin {

    /**
     * key（分组名）→ （渠道名 → 当前累计权重）。
     *
     * <p>为什么按分组存：不同分组的轮询应该互不干扰。
     * 如果共用一个 current，给客服准备的组会消耗掉订单组的轮询状态。
     */
    private final Map<String, Map<String, Integer>> currentWeights = new LinkedHashMap<>();

    /**
     * 从一组带权重的候选里选一个。
     *
     * @param group   分组名（决定轮询状态的隔离维度）
     * @param weights 渠道名 → 权重，<b>必须全部大于 0</b>（权重 0 的渠道应在调用前被过滤掉）
     * @return 被选中的渠道名；候选为空时返回 null
     */
    public synchronized String select(String group, Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return null;
        }

        Map<String, Integer> current = currentWeights.computeIfAbsent(group, ignored -> new LinkedHashMap<>());
        int totalWeight = 0;
        String best = null;
        int bestCurrent = Integer.MIN_VALUE;

        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            String name = entry.getKey();
            int weight = entry.getValue();
            if (weight <= 0) {
                continue;
            }
            totalWeight += weight;
            int next = current.getOrDefault(name, 0) + weight;
            current.put(name, next);
            if (next > bestCurrent) {
                bestCurrent = next;
                best = name;
            }
        }

        if (best == null) {
            return null;
        }

        // 只被选中的那个减 totalWeight —— 减全部候选的总权重，这是算法成立的关键。
        // 减自己的权重是常见的写错版本：它会让高权重渠道永远被选中。
        current.put(best, current.get(best) - totalWeight);

        // 清理已经不在候选里的渠道，避免配置删了渠道之后 currentWeights 只增不减。
        // （这属于"慢慢泄漏"的那类问题，不致命但迟早会变成事故。）
        current.keySet().removeIf(name -> !weights.containsKey(name));

        return best;
    }

    /** 测试/排障用：看一眼某个分组当前的累计权重 */
    public synchronized Map<String, Integer> snapshot(String group) {
        return Map.copyOf(currentWeights.getOrDefault(group, Map.of()));
    }
}
