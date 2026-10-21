package io.github.maixiaowen.gateway.limiter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * <b>基于 Redis + Lua 的令牌桶限流器（G4 的核心类）。</b>
 *
 * <p><b>它解决什么</b>：网关现在是一个"免费的大模型消费入口" ——
 * 任何一个客户端的 bug（while true 里发请求、忘了加 sleep 的重试循环）
 * 都能在一个晚上把一个月预算烧光。<b>限流是这道防线。</b>
 *
 * <p><b>★ 为什么是令牌桶，而不是"每分钟最多 N 次"（固定窗口）★</b>
 * <ul>
 *   <li><b>固定窗口有临界问题</b>：限制"每分钟 100 次"，
 *       客户端在第 59 秒打 100 次、第 61 秒再打 100 次 ——
 *       两秒内 200 次，全部合法。<b>窗口边界是固定窗口算法的结构性漏洞，不是配置问题。</b></li>
 *   <li><b>令牌桶允许突发，但约束长期速率</b>：{@code capacity} 决定"最多能攒多少"（突发额度），
 *       {@code refillPerSecond} 决定"长期平均多快"。这两件事本来就是分开的业务需求
 *       （"允许用户偶尔一次问 20 个问题，但不能持续每秒 20 个"），
 *       固定窗口只有一个旋钮，表达不了。</li>
 * </ul>
 *
 * <p><b>★ 原子性：本类存在的全部理由 ★</b>
 * <p>「读 → 判断 → 扣减 → 写回」四步必须原子完成，否则会<b>超卖</b>
 * （两个请求同时读到"还剩 1 个"，双双放行）。
 * 所以这四步被写成了一整段 Lua，由 Redis 保证"一次执行到底"。
 * <b>完整论证和反例见 {@code resources/scripts/token_bucket.lua} 的注释。</b>
 *
 * <p><b>★ 返回值为什么走 cjson 而不是 Lua 数组 ★</b>
 * <p>Lua 返回数组（table of integers）时，Spring Data Redis 把它反序列化成什么类型，
 * 取决于脚本声明的 resultType 和 template 的序列化器怎么组合 —— 这块行为琐碎、易踩坑，
 * 而且踩了之后报的是 ClassCastException 之类的间接错误。
 * 返回 cjson 编码的字符串则完全确定：{@link ReactiveStringRedisTemplate} 的
 * String 序列化器一定能处理它，我们再用 Jackson 解析成明确的类型。
 * <b>顺带一个真实好处：这个返回值在 {@code redis-cli} 里直接 EVAL 就能肉眼读，
 * 排查限流问题时不用写任何 Java 代码。</b>
 */
public class RedisTokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketLimiter.class);

    /** 脚本位置（classpath 相对路径） */
    private static final String SCRIPT_PATH = "scripts/token_bucket.lua";

    /** 每次请求消耗的令牌数。目前恒为 1（限的是"请求次数"，不是"token 数"—— 那是 G7 的事） */
    private static final String TOKENS_PER_REQUEST = "1";

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final RedisScript<String> script;

    public RedisTokenBucketLimiter(ReactiveStringRedisTemplate redis,
                                   RateLimitProperties properties,
                                   ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.script = loadScript();
    }

    /**
     * <b>尝试为某个客户端取 1 个令牌。</b>
     *
     * <p>整个方法<b>不会阻塞</b>：返回的是 {@link Mono}，Redis 的往返由 Lettuce 在
     * Netty 的事件循环上异步完成。<b>这一点不是优化，是 WebFlux 的硬要求</b> ——
     * 一旦在这里 {@code .block()}，被阻塞的就是处理所有请求的那个线程。
     * （唯一的例外是启动自检，那里确实 block 了 —— 但它跑在启动线程上，不在事件循环里。
     * 见 {@code RateLimitConfig}。）
     *
     * @param clientId 限流的维度（客户端标识）。同一个 id 共用一个桶
     * @return 三态结果。Redis 不可用时的行为由 {@code gateway.rate-limit.fail-open} 决定
     */
    public Mono<RateLimitDecision> tryAcquire(String clientId) {
        String key = properties.keyPrefix() + ":" + clientId;

        return redis.execute(script, List.of(key),
                        String.valueOf(properties.capacity()),
                        String.valueOf(properties.refillPerSecond()),
                        TOKENS_PER_REQUEST)
                .next()
                // ★ 超时是必须的，不是可选的优化。
                //   Lettuce 默认命令超时 60 秒。Redis "变慢"（比如在做持久化、或网络丢包重传）
                //   时，没有这一行，每个请求都会挂 60 秒 —— 网关的并发能力会被瞬间吃干。
                //   **"Redis 挂了"fail-open 能兜住，"Redis 变慢"只有超时能兜住。**
                .timeout(Duration.ofMillis(properties.timeoutMillis()))
                .map(this::parseDecision)
                .onErrorResume(ex -> onLimiterFailure(key, ex));
    }

    /**
     * 把脚本返回的 JSON 解析成领域类型。
     *
     * <p>解析失败会抛异常，然后被 {@link #onLimiterFailure} 接住 ——
     * 这是刻意的：<b>"脚本返回了看不懂的东西"和"Redis 连不上"对网关的影响完全一样，
     * 都是"限流器此刻不可信"</b>。分开处理只会让代码更长，不会让结论更准。
     */
    private RateLimitDecision parseDecision(String json) {
        try {
            ScriptResult result = objectMapper.readValue(json, ScriptResult.class);
            return result.allowed()
                    ? RateLimitDecision.allowed(result.remaining())
                    : RateLimitDecision.denied(result.remaining(), result.retryAfterMillis());
        } catch (Exception e) {
            throw new IllegalStateException("限流脚本返回了无法解析的结果，原文 = " + json, e);
        }
    }

    /**
     * Redis 不可用（或脚本坏了）时的降级路径。<b>这里做的是一次业务决策，不是异常处理。</b>
     *
     * <p><b>★ fail-open 的理由（为什么默认放行）★</b>
     * <p>限流器是<b>保护性</b>组件，不是<b>功能性</b>组件 —— 它对"请求能不能被服务"
     * 没有贡献，只对"该不该被服务"提意见。<b>保护性组件的失败，不该升级成网关的失败。</b>
     * <p>反过来算一下代价就很清楚：Redis 抖动 3 秒，
     * <ul>
     *   <li>fail-open：这 3 秒里超卖了若干个请求（钱），用户完全无感；</li>
     *   <li>fail-closed：这 3 秒里 <b>100% 的请求被拒</b>（事故），而限流本来只是为了防万一。</li>
     * </ul>
     * <b>用一次必然发生的全面故障，去防一次可能发生的超卖，这笔账是亏的。</b>
     *
     * <p><b>★ 但"静默 fail-open"是绝对不能接受的 ★</b>
     * <p>放行必须留下 ERROR 日志。<b>限流悄悄失效和限流从来没配，在监控上是同一件事 ——
     * 直到账单出来。</b>
     */
    private Mono<RateLimitDecision> onLimiterFailure(String key, Throwable ex) {
        String reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();

        if (properties.failOpen()) {
            log.error("[限流器故障-已放行] Redis 不可用或脚本异常，本次请求不设限。"
                    + "key={}，原因={}。★ 若这条日志持续出现，说明网关当前没有任何额度防线 ★", key, reason);
            return Mono.just(RateLimitDecision.allowedWithoutQuota());
        }

        log.error("[限流器故障-已拒绝] Redis 不可用或脚本异常，按 fail-open=false 拒绝本次请求"
                + "（返回 503，不是 429 —— 这是我们的问题，不是调用方的）。key={}，原因={}", key, reason);
        return Mono.just(RateLimitDecision.unavailable());
    }

    /**
     * 加载并预热 Lua 脚本。
     *
     * <p><b>★ 为什么显式用 UTF-8 读，而不是 {@code new DefaultRedisScript(Resource)} ★</b>
     * <p>{@code DefaultRedisScript} 从 {@code Resource} 构造时，走的是
     * {@code ResourceScriptSource}，它用 {@code Charset.defaultCharset()} 读文件。
     * JDK 17 在中文 Windows 上这个值是 <b>GBK</b>（JDK 18 才把 UTF-8 定为默认），
     * 于是脚本里的中文注释会变成乱码 —— 不会报错，只是 EVAL 出来的脚本没法看。
     * <b>凡是"读文件"的地方，编码都该写死，不该依赖运行环境的默认值。</b>
     *
     * <p><b>★ 网络里传的是 SHA1，不是脚本体 ★</b>
     * <p>{@link DefaultRedisScript} 会算出脚本的 SHA1，
     * {@code DefaultReactiveScriptExecutor} 首次执行时用 {@code EVALSHA}，
     * 遇到 NOSCRIPT 才回退到 {@code EVAL} 把脚本推上去。
     * <b>所以脚本多大都不影响每次调用的网络开销</b>—— 传的只有 40 个字符的 SHA1。
     */
    private RedisScript<String> loadScript() {
        ClassPathResource resource = new ClassPathResource(SCRIPT_PATH);
        String lua;
        try (InputStream in = resource.getInputStream()) {
            lua = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 脚本文件缺失 = 构建产物坏了。这种情况必须炸，不能"降级成不限流" ——
            // 那会让一个打包事故悄悄变成一次安全事件。
            throw new IllegalStateException("加载限流脚本失败：" + SCRIPT_PATH, e);
        }
        return new DefaultRedisScript<>(lua, String.class);
    }

    /**
     * 脚本返回的 JSON 结构（{@code {"allowed":1,"remaining":4,"retryAfterMs":0}}）。
     *
     * <p><b>它刻意是个私有嵌套类型，不对包外可见</b>：
     * 它是"Redis 侧的数据形状"，不是"网关的领域模型"。
     * 让 {@link RateLimitDecision} 直接反序列化这个 JSON，
     * 等于让 Lua 脚本里的字段名变成对外的契约 ——
     * 哪天脚本改个字段名，就成了一次接口不兼容变更。
     * <b>两边的形状各自演化，转换点只有一处，这是防腐层最小的形态。</b>
     *
     * <p><b>★★ 三个字段为什么都是装箱类型，而且必须判空 —— 这是本类真实踩过的坑 ★★</b>
     * <p>第一版这里写的是 {@code long retryAfterMillis}，而脚本返回的字段名叫
     * {@code retryAfterMs}。<b>两个名字差了一个单词，Jackson 匹配不上，</b>
     * 于是这个字段用 {@code long} 的默认值 <b>0</b> 顶上，
     * 整个反序列化过程<b>一声不响地成功返回</b>。
     * <p>后果非常具体：被限流的响应里带上了 {@code Retry-After: 0}，
     * 语义是"<b>立刻重试</b>" —— 而客户端的重试库真的会立刻重试，
     * 形成重试风暴，把刚刚才缓过来的桶再次打满。
     * <p><b>基本类型的危险恰恰在于：缺失时它会静默变成一个"看起来正常"的值</b>（0 / false），
     * 而 0 和 false 在很多场景下都是合法输入，于是没有任何东西会报错。
     * 换成装箱类型，缺失就是 {@code null}，我们才有可能在这里把它拦下来。
     * <p>这是被 {@code RateLimitConcurrencyTest#tokensAreRefilledOverTime}
     * 抓住的 —— 那条断言写的是"retryAfterMillis 应该在 1~200 之间"，
     * 它拿到的实际值是 0。<b>"断言一个值落在合理区间"比"断言它不为 null"值钱得多。</b>
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：
     * 脚本以后加字段时，老的 Java 侧不会因为"多了个不认识的字段"而炸 ——
     * <b>让升级可以只升一边。</b>
     * 注意它和上面那段并不矛盾，两者合起来是一条完整的原则：
     * <b>多出来的字段可以容忍（附加是安全的），必需的字段绝不能缺席（缺失是危险的）。</b>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScriptResult(
            Boolean allowed,
            Long remaining,
            /* ★ 显式绑定字段名：脚本里叫 retryAfterMs，这里叫 retryAfterMillis。
               两者不一致时不要靠"改个名字让它们碰巧对上"来修 ——
               那等于让 Lua 里的字段名决定了 Java 的命名。
               在防腐层的这一个点上把映射写明白，两边的命名就能各自演化。 */
            @JsonProperty("retryAfterMs") Long retryAfterMillis
    ) {
        ScriptResult {
            if (allowed == null || remaining == null || retryAfterMillis == null) {
                // 缺字段 = 我们和脚本对不上了（改了一边没改另一边、或者部署了旧的 jar）。
                // 必须炸，不能默认成 0 继续跑 —— 理由见上面的类注释。
                throw new IllegalStateException(
                        "限流脚本的返回缺少必需字段：allowed=" + allowed
                                + "，remaining=" + remaining
                                + "，retryAfterMs=" + retryAfterMillis);
            }
        }
    }
}
