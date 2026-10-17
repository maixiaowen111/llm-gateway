# RedisTokenBucketLimiter

> **本页速览**
> - G4 的**核心执行者**:237 行,负责"把请求交给 Lua 脚本,把结果翻译成 `RateLimitDecision`"。
> - ★★ 本页最值钱的一段是**为什么用令牌桶而不是"每分钟 N 次"** —— 固定窗口有一个**结构性漏洞**。
> - ★ `tryAcquire()` 全程不阻塞,返回 `Mono`。**这不是优化,是 WebFlux 的硬要求**。
> - ★★ `.timeout()` 那一行背后有一条区分:**"Redis 挂了"fail-open 能兜住,"Redis 变慢"只有超时能兜住**。
> - ⚠️ 这个类里藏着**三处"静默失败"的防线**,深入页逐个讲。
>
> **导航**:[← 上一页:脚本-token_bucket.lua](脚本-token_bucket.lua.md) ｜ [下一页:类-RedisTokenBucketLimiter-深入 →](类-RedisTokenBucketLimiter-深入.md) ｜ [所属模块:M6 限流](_index.md)

## 一句话职责

**为某个客户端尝试取 1 个令牌,返回三态结果;Redis 不可用时按配置决定放行还是拒绝。**

## 位置

```text
src/main/java/com/example/gateway/limiter/RedisTokenBucketLimiter.java     (237 行)
```

## 为什么需要这个类(设计动机)

### ★★ 为什么是令牌桶,而不是"每分钟最多 N 次"

**Javadoc 里给了一个非常具体的反例,值得完整读**:

> *"固定窗口有**临界问题**:限制'每分钟 100 次',客户端在**第 59 秒打 100 次、第 61 秒再打 100 次** —— **两秒内 200 次,全部合法**。窗口边界是固定窗口算法的**结构性漏洞,不是配置问题**。"*

```text
   第 0 秒                第 59 秒    第 60 秒   第 61 秒            第 120 秒
     │                      │           │          │                   │
     ├────── 第 1 个窗口 ────┼─ 第 2 个窗口 ────────┼─── 第 3 个窗口 ────┤
                            │           │          │
                       ★ 打满 100 次  窗口重置  ★ 又打满 100 次
                            └───────────┴──────────┘
                              ★ 2 秒内 200 次,全部合法
```

> ★★ **关键认识**:**这不是"配得不严",是算法的结构性缺陷**。你把它配成"每分钟 50 次",攻击者照样能在边界上打出 100 次/2 秒。**只要窗口的边界存在,边界处就有一个双倍速率的窗口。**

### ★ 令牌桶的两个旋钮,对应两个真实的业务需求

> *"`capacity` 决定'最多能攒多少'(突发额度),`refillPerSecond` 决定'长期平均多快'。这两件事本来就是分开的业务需求 **("允许用户偶尔一次问 20 个问题,但不能持续每秒 20 个")**,固定窗口只有一个旋钮,表达不了。"*

| 需求 | 令牌桶的表达 | 固定窗口能表达吗 |
|---|---|---|
| 允许偶尔突发 | ★ `capacity` | ❌ 只能靠调大限额,而调大之后长期速率也松了 |
| 约束长期速率 | ★ `refillPerSecond` | ✅ 但和突发量绑死 |
| **两者独立** | ✅ 两个旋钮 | ★ ❌ **只有一个** |

```text
真实用户行为:一次粘贴一大段材料,连问几个问题 → ★ 这不是滥用,是正常用法
   → 固定窗口:要么放过去(长期速率也松了)、要么拒掉(体验很差)
   → 令牌桶:★ 桶里攒着 20 个令牌,一次性用完 → 之后再按每秒 1 个的速度来
   ↓ ★★ 这正是"限流"和"限制用户"的分界线:限流要拦的是持续滥用,不是短暂突发
```

> ★★ **规律**:「**限流器的目标不是"把速率压平",是"约束长期速率的同时容忍突发"**」。一个把用户正常用法也拦掉的限流器,会先被业务方要求关掉。

## 关键属性

| 字段 | 类型 | 说明 |
|---|---|---|
| `redis` | `ReactiveStringRedisTemplate` | ★ **Reactive** —— 不是普通的 `StringRedisTemplate` |
| `properties` | `RateLimitProperties` | 配置(capacity / refill / timeout / failOpen / keyPrefix) |
| `objectMapper` | `ObjectMapper` | 解析脚本返回的 JSON |
| `script` | `RedisScript<String>` | ★ **构造时就加载好**(预热,见下) |

```java
private static final String SCRIPT_PATH = "scripts/token_bucket.lua";
private static final String TOKENS_PER_REQUEST = "1";
```

> ★ **`TOKENS_PER_REQUEST` 恒为 `"1"`** —— 限的是**请求次数**,不是 **token 数**。按 token 数计费是 G7 的事(要等上游返回 `usage` 才知道)。

### ★ 为什么是 `ReactiveStringRedisTemplate`

```text
WebFlux 里用非 reactive 的 Redis 客户端 → ★ 每次调用都会阻塞事件循环线程 → 几个并发就能让网关停止响应
```

> 这条在 [配置-pom.xml](../01-骨架与配置/配置-pom.xml.md) 里是"坑②",在 [类-PingController](../04-请求入口/类-PingController.md) 里是 **"WebFlux 的收益不来自 `Mono` 这个类型,来自 IO 是异步的"**。★ 同一个道理,这是它的第三个落点。

## 关键方法

### 构造器 —— 只有一行值得说

```java
public RedisTokenBucketLimiter(ReactiveStringRedisTemplate redis,
                               RateLimitProperties properties,
                               ObjectMapper objectMapper) {
    this.redis = redis;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.script = loadScript();      // ★ 构造时就加载
}
```

```text
❌ 每次 tryAcquire 都读一次 .lua 文件 → ★ 每个请求一次磁盘 IO → 而磁盘 IO 在事件循环线程上是阻塞的 → ★★ 限流器的开销可能比它保护的东西还大
✅ 构造时读一次,之后只传 SHA1 → 每次调用的网络开销 = ★ 40 个字符
```

> ★ 详见 [深入页](类-RedisTokenBucketLimiter-深入.md) 的 `loadScript()`。

### ★★ `tryAcquire(String clientId)` —— 唯一的公开方法

```java
public Mono<RateLimitDecision> tryAcquire(String clientId) {
    String key = properties.keyPrefix() + ":" + clientId;         // ① 拼 key

    return redis.execute(script, List.of(key),                    // ② 执行脚本
                    String.valueOf(properties.capacity()),
                    String.valueOf(properties.refillPerSecond()),
                    TOKENS_PER_REQUEST)
            .next()                                                // ③ Mono<String>
            .timeout(Duration.ofMillis(properties.timeoutMillis()))// ④ ★ 超时
            .map(this::parseDecision)                              // ⑤ 解析
            .onErrorResume(ex -> onLimiterFailure(key, ex));       // ⑥ ★ 降级
}
```

| 项 | 说明 |
|---|---|
| **做什么** | 取一个令牌,返回三态结果 |
| **参数** | `clientId` —— **限流的维度**。同一个 id 共用一个桶 |
| **返回值** | `Mono<RateLimitDecision>` —— ★ **不阻塞** |
| **被谁调用** | `RateLimitWebFilter`;**以及 `RateLimitConfig` 的启动自检** |
| **调用了谁** | `ReactiveStringRedisTemplate.execute()`、`loadScript()`、`parseDecision()`、`onLimiterFailure()` |

#### ① key 的拼法

```java
String key = properties.keyPrefix() + ":" + clientId;
// → "gw:rl:1.2.3.4"
```

**注意 `keyPrefix` 在 Properties 里已经兜底过 `null`**(变成 `"gw:rl"`),所以这里可以安全地直接拼接,不用再判一次 null。

> ★ **规律**:「**规范化在对象构造时做一次,使用点就都可以假设它是合法的**」。这和 [类-ProviderRegistry](../02-上游渠道抽象/类-ProviderRegistry.md) "默认渠道实例在构造时解析好"是同一个手法。

#### ② 参数为什么全部转成 `String`

```java
String.valueOf(properties.capacity())            // int    → "10"
String.valueOf(properties.refillPerSecond())     // double → "1.0"
TOKENS_PER_REQUEST                               // 常量   → "1"
```

```text
★ Lua 侧全部用 tonumber(ARGV[n]) 接 → 所以 Java 侧传什么类型最终都会被转成字符串 → ★ 那不如一开始就显式传 String,避免序列化器介入
```

> ⚠️ **注意 `double` 的坑**:`String.valueOf(1.0)` 得到 `"1.0"`,Lua 的 `tonumber` 能接;但 `refillPerSecond = 0.1` 会得到 `"0.1"` —— ★ **这个也是能接的**,不过某些语言环境的 `DecimalFormat` 会输出 `"0,1"`。★ 这里用 `String.valueOf`(不是格式化器)是安全的,因为它是**与 locale 无关**的。

#### ③④ ★★ `.timeout()` —— 一行区分了两种故障

> *"超时是必须的,不是可选的优化。**Lettuce 默认命令超时 60 秒。** Redis '变慢'(比如在做持久化、或网络丢包重传)时,没有这一行,每个请求都会挂 60 秒 —— 网关的并发能力会被瞬间吃干。**"Redis 挂了"fail-open 能兜住,"Redis 变慢"只有超时能兜住。**"*

### ★★ "挂了" 和 "变慢" 是两种故障

| | ★ **挂了**(连接拒绝 / 端口不通) | ★★ **变慢**(持久化 / 丢包重传) |
|---|---|---|
| 表现 | ★ **立刻**报错 | ★ **一直不返回**,直到超时 |
| fail-open 能兜住吗 | ✅ 能 —— 异常立刻抛出,走 `onErrorResume` | ❌ **不能** —— 没有异常可接 |
| 唯一的手段 | 异常处理 | ★ **超时** |
| 后果(无超时) | 无(异常立刻返回) | ★★ 每个请求挂 60 秒 → **并发被吃干** |

```text
假设事件循环有 8 条线程,Redis 每次调用挂 60 秒
   ↓ ① 8 个请求进来 → 8 条线程全部在等 Redis
② 第 9 个请求 → ★ 没有线程可用 → 排队 → ③ 队列越来越长 → ★★ 整个网关停止响应
   ↓ ★ 而这一切的起点只是"Redis 慢了一点" → 而 Redis 慢一点,在它做 RDB 持久化时 ★ 是常态
```

> ★★ **规律**:「**超时不是"防止程序卡住"的兜底,它是分布式系统里的一等公民**」。判据:**「如果我调用的这个服务,永远不返回,我会怎样?」** 如果答案是"我会挂",那就必须有超时 —— ★ 而"永远不返回"是一个**会发生**的情况,不是理论假设。

#### ⑤⑥ 剩下的三步在深入页

`parseDecision` 和 `onLimiterFailure` 各有一段值得细读的设计(前者是防腐层,后者是业务决策)→ [类-RedisTokenBucketLimiter-深入](类-RedisTokenBucketLimiter-深入.md)

## 这个类在项目中的角色

```mermaid
sequenceDiagram
    participant F as RateLimitWebFilter
    participant L as RedisTokenBucketLimiter
    participant R as Redis
    F->>L: tryAcquire("1.2.3.4")
    L->>L: key = "gw:rl:1.2.3.4"
    L->>R: EVALSHA &lt;sha1&gt; key 10 1.0 1
    alt 正常返回
        R-->>L: {"allowed":1,"remaining":9,"retryAfterMs":0}
        L->>L: parseDecision → RateLimitDecision.allowed(9)
    else 超时 / 连不上 / 脚本报错
        R--xL: ★ timeout / 异常
        L->>L: onLimiterFailure(key, ex)
        Note over L: ★ failOpen? 放行 : 拒绝(503)
    end
    L-->>F: Mono&lt;RateLimitDecision&gt;
```

**★ 注意 `alt` 的两个分支** —— 这个类**只有两条出口**:脚本成功 → 按结果构造;**任何失败 → 走同一条降级路径**。

## 值得学习的点

### 1. ★★ "结构性漏洞"和"配置问题"的区别

```text
★ 配置问题:调一调参数就能解决
★ 结构性漏洞:★ 换参数也解决不了,只能换算法
```

| | 固定窗口的临界问题 | ★ 本项目的选择 |
|---|---|---|
| 是配置问题吗 | ❌ 不是 | —— |
| 调参数能修吗 | ❌ 不能 | —— |
| 只能怎么办 | ★ **换算法** | 令牌桶 |

> ★★ **规律**:「**看到一个"调参数也修不好"的问题,就该意识到是选型错了**」。这条在技术讨论里很有用:当有人提议"我们把限额调小一点试试",你要能回答:**「调小只是把边界处的双倍从 200 变成 100,漏洞还在。」**

### 2. ★★ 保护性组件 vs 功能性组件

**Javadoc 里对 fail-open 的论证,第一句就划了这条线**:

> *"限流器是**保护性**组件,不是**功能性**组件 —— 它对'请求能不能被服务'没有贡献,只对'该不该被服务'提意见。**保护性组件的失败,不该升级成网关的失败。**"*

| | ★ 功能性组件 | ★ 保护性组件 |
|---|---|---|
| 例子 | Provider、注册表、Controller | ★ 限流、熔断、监控、日志采集 |
| 它挂了会怎样 | ★ **业务不可用** | ★ **业务照常,只是少了保护** |
| 失败时该做的 | 尽力恢复 / 报错 | ★ **退让,别拦住主流程** |

> ★★ **规律**:「**先给组件分类,再决定它失败时怎么办**」,这条判断在很多地方能直接用:
> - 日志系统挂了 → ⚠️ 不能因为打不了日志而让请求失败
> - 监控上报挂了 → ⚠️ 同上
> - 缓存挂了 → 回源(性能降级),不是拒绝请求
> - ★ **认证服务挂了 → 必须拒绝**(它是功能性的,不是保护性的)

**注意最后一行**:认证和限流看起来都是"拦请求的",但**认证挂了必须 fail-closed** —— 因为它决定了"你是谁",是功能性的。

> ★★ **所以"保护性 vs 功能性"才是判据,"会不会拦请求"不是。**

### 3. ★ 一个类的"核心"可以被一句话概括

**237 行里,真正执行限流的只有一段**:

```java
redis.execute(script, List.of(key), capacity, refill, "1")
```

**其余全部是三种"失败时怎么办"**:

| 行数占比 | 内容 |
|---|---|
| ~5% | ★ **正常路径**(上面那一行 + 解析) |
| ~95% | ★ **异常路径**(超时、降级、解析失败、脚本加载失败) |

> ★★ **规律**:「**一个"保护性组件"的代码里,异常路径应该比正常路径长**」—— 因为**它的价值恰恰体现在"出错的时候"**,正常时它只是个透传。★ 反过来也成立:**如果一个"保护性组件"里几乎全是正常路径,那它大概没想过自己坏掉会怎么办**。

### 4. ⚠️ 三处"静默失败"的防线,都在这个类里

**它们有一个共同特征**:**坏了但不报错**。这是最难查的一类问题。

| 防线 | 防的静默失败 | 在哪 |
|---|---|---|
| 显式 `UTF-8` 读脚本 | 中文注释变乱码,★ 不报错 | `loadScript()` |
| `ScriptResult` 用**装箱类型** | 缺字段时默认成 `0`,★ 不报错 | `ScriptResult` |
| 解析失败 `throw`,不默认放行 | 脚本变形时静默放行,★ 不报错 | `parseDecision()` |

> ★★ **规律**:「**显式声明,胜过依赖默认值 —— 因为默认值不会告诉你它被用上了**」。三条防线对应三种"默认值"的来源:① 运行环境的默认字符集 ② 基本类型的默认 0 ③ 异常被吞掉。★ **它们共同的特征是:都发生在"你没写,但系统替你决定了"的地方。**

## 相关页

- ★ **深入页**(三个方法 + 那个真实踩过的坑) → [类-RedisTokenBucketLimiter-深入](类-RedisTokenBucketLimiter-深入.md)
- 被执行的算法 → [脚本-token_bucket.lua](脚本-token_bucket.lua.md)
- 返回的类型 → [类-RateLimitDecision](类-RateLimitDecision.md)
- 谁调用它 → [类-RateLimitWebFilter](类-RateLimitWebFilter.md)
- 启动自检里也调它 → [类-RateLimitConfig](类-RateLimitConfig.md)
- 配置来源 → [类-RateLimitProperties](类-RateLimitProperties.md)
- 端到端一次被拒 → [流程-一次被限流的请求](流程-一次被限流的请求.md)

> **导航**:[← 上一页:脚本-token_bucket.lua](脚本-token_bucket.lua.md) ｜ [下一页:类-RedisTokenBucketLimiter-深入 →](类-RedisTokenBucketLimiter-深入.md) ｜ [所属模块:M6 限流](_index.md)
