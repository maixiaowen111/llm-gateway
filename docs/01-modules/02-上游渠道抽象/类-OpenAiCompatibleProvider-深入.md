# OpenAiCompatibleProvider · 深入

> **本页速览**
> - 承主页面,专讲**流式**这一半:372 行里最难、最容易出错的部分。
> - ★ 隐藏事实:这个类有 **3 个业务方法,但接口上只有 2 个** ——
>   `chatCompletionStreamRaw` 是**故意的实现细节**,不暴露给调用方。
> - `doFinally` 里区分 **CANCEL 和正常结束** —— 因为"用户关页面了"不是错误。
> - `timeout()` 加在**每个元素之间** —— 这才是流式该有的超时,`responseTimeout` 管不到。
> - **坏块不能拖垮整条流**:`normalizeChunkSafely` 单块失败只警告,不中断。
> - 它改过三次名字,而**每次改名都对应一次设计认知的升级**。
>
> **导航**:[← 上一页:类-OpenAiCompatibleProvider](类-OpenAiCompatibleProvider.md) ｜ [下一页:流程-渠道路由 →](流程-渠道路由.md) ｜ [所属模块:M2 上游渠道抽象](_index.md)

---

## 前置:先看三个方法的关系
```java
// ① 接口上有,调用方能看见
@Override public String name()
@Override public Mono<ChatCompletionResponse>  chatCompletion(...)        // 非流式,见主页面
@Override public Flux<ChatCompletionChunk>     chatCompletionStream(...)  // ③,包装了 ②

// ② 接口上没有,包可见/私有 —— ★ 内部实现细节
public Flux<String> chatCompletionStreamRaw(ChatCompletionRequest request)
```

**调用关系**:

```text
chatCompletionStream()                       ← 接口方法(对外)
   └─ chatCompletionStreamRaw()             ← 发 HTTP,吐"上游原始行"(WebClient → SSE)
   └─ .filter(过滤 [DONE]) → .concatMap(normalizeChunkSafely)  ← 每行翻译成 DTO
```
---
## ★ 为什么 `chatCompletionStreamRaw` **不在接口上**
### 它返回的是什么
```java
Flux<String> chatCompletionStreamRaw(ChatCompletionRequest request)
//   ↑ 注意是 String,不是 DTO
```

它吐出来的是**上游 SSE 的原始文本行**,形如:

```text
data: {"id":"...","choices":[{"delta":{"content":"你"}}]}
data: {"id":"...","choices":[{"delta":{"content":"好"}}]}
data: [DONE]
```

**这是"厂商味道"最重的东西** —— 直接暴露了"上游用 SSE 协议、以 `data: ` 开头"。
### 如果把它放进接口会怎样
```java
// ❌ 反例
public interface Provider {
    Flux<String> chatCompletionStreamRaw(...);   // ← 泄露上游协议
    Flux<ChatCompletionChunk> chatCompletionStream(...);
}
```

| 后果 | 说明 |
|---|---|
| ★ **接口被厂商协议污染** | 一个用 WebSocket 或 gRPC 的渠道,根本没有"原始行"这种东西 |
| 逼所有实现类都实现它 | 包括那些协议完全不同的(它们只能返回空或抛异常) |
| 违反了"接口无厂商痕迹" | 这正是 [类-Provider](类-Provider.md) 立的那条铁律 |

> ★ **规律**:「**能用的东西,不一定该暴露**」。一个方法"技术上可以被外部调用",和"它应该出现在接口上"是两件事。
> **判据**:问"这个方法返回的东西,是不是**所有**实现类都能有意义地提供?"只有 OpenAi 兼容类能 → **它就该是私有/包可见的**。
### 那为什么不干脆私有?
因为 **G2 阶段的测试需要它**(单独验证"上游原始行有没有被正确转发"),调试时也想看到原始数据。所以是**包可见**:测试同包能拿到,外部调用方拿不到。
> **规律**:可见性修饰符可以是"给谁看"的精确表达,不必在 `private` 和 `public` 之间二选一。
---
## `chatCompletionStreamRaw` 里的三件事
```java
public Flux<String> chatCompletionStreamRaw(ChatCompletionRequest request) {
    return webClient.post()
            .uri("/chat/completions")
            .header(AUTHORIZATION, "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)          // ①
            .bodyValue(Map.of(..., "stream", true))       // ★ 和非流式的唯一差别
            .retrieve()
            .bodyToFlux(String.class)                     // ②
            .doFinally(signal -> log(...))                // ③ 取消传播
            .timeout(Duration.ofMillis(idleTimeoutMs))    // ④ 逐块空闲超时
            .onErrorMap(TimeoutException.class,           // ⑤ 换成自己的异常
                    e -> new UpstreamIdleTimeoutException(idleTimeoutMs, e));
}
```
### ① `.accept(TEXT_EVENT_STREAM)` —— 告诉上游"我要流"
不加这个头,有些上游会返回**攒齐的一次性响应**(而不是 SSE)。**这是流式能不能真的流起来的前置条件。**
### ② `.bodyToFlux(String.class)` —— 按行拆
Reactor Netty 收到 SSE 时,会**按事件边界**拆成一个个 `String`,所以这个 `Flux<String>` 的每个元素 ≈ 上游的一行 `data: ...`。
> ⚠️ 这里**不做任何解析** —— 解析是下一步(`normalizeChunkSafely`)的事。"收字节"和"理解字节"分成两段,是为了让"收"的部分能独立测试。
### ★ ③ `doFinally` —— 取消传播,本页最重要的概念
```java
.doFinally(signalType -> {
    if (signalType == SignalType.CANCEL) {
        log.info("流被调用方取消,渠道={}", name);      // ★ 用户提前断开
    } else {
        log.info("流正常结束,渠道={}", name);
    }
});
```
#### 先解释"取消传播"是什么
```text
用户点"停止生成"/关页面 → HTTP 断开 → WebFlux 感知 → 发 CANCEL 信号
   → ★ 信号沿操作符链一路向上 → WebClient 关闭上游连接 → ★★ 上游停止推理,不再烧 token
```

**这就是"取消传播"的价值**:**它是一路传到底的资源释放,不是只关自己的门。**
#### ⚠️ 如果不传播会怎样
```text
用户关页面 → 网关 Flux 被 cancel 但没往上传播 → 上游还在生成完整回答
   → ★ 用户什么都没看到,账单却算了完整一次调用(并发多时上游连接被占满)
```
> ★ **规律**:「**取消必须是"级联的",否则它只是"本地放弃"**」。这类问题的特征是:**功能上完全正常,只有成本/资源上出问题** —— 最难发现的那种。
#### 为什么要区分 CANCEL 和正常结束(而不是都打一条日志)
```text
正常结束 → 是"这一个请求的业务结果"
CANCEL   → 是"一个客户端行为",不是错误、不是失败
```

**混在一起打的后果**:日志里全是"流结束",**看不出有多少是用户主动中断的**。
> 这条区分的价值在**排查和度量**上:
> ```text
> CANCEL 比例突然升高 → 可能上游变慢,用户等不及了
> CANCEL 几乎为零     → 可能是客户端不支持中断(或者根本没人在用流式)
> ```
### ★★ ④ `.timeout(...)` —— 逐块空闲超时,以及它和 `responseTimeout` 的区别
**这是 M1 留的那个"坑"的正式补上**(详见 [类-WebClientConfig](../01-骨架与配置/类-WebClientConfig.md)):M1 配的 `responseTimeout` 度量的是"请求发出 → 收到**响应头**"。流式的响应头**几十毫秒就回来了**,所以它**管不到响应体中途的停滞**。

**Reactor 的 `timeout()` 语义**:

```text
★ 关键:它度量的是"两个元素之间"的间隔,不是"整条流的时长"
```

```java
.timeout(Duration.ofMillis(idleTimeoutMs))
```

**用时间轴对比着看**:

```text
┌ 健康:整条流 5 分钟,块之间 0.1s~0.3s,每个间隔 < 20s → ★ 不会被杀 ✅
└ 卡死:块─0.1s─块── 静止 20s ──→ ★ 超过 idleTimeoutMs → 超时 ✅
```

**所以 20s 这个值的含义是**:

```text
❌ 不是"整条流最多活 20 秒"
✅ 是"两个数据块之间最多允许隔 20 秒"
```

**为什么这个区别至关重要**:模型思考时**停顿几秒是完全正常的**。如果设成"整条流最多 20 秒",所有正常的长回复都会被腰斩 —— **那是比超时更严重的生产事故**(用户看到回答说到一半断掉)。
> ★ **规律**:「**超时值要匹配"你在等什么"**」:等 TCP 握手 → 秒级;等响应头 → 按业务(30s);等两个数据块之间 → 按"正常产出节奏"(20s);等整条流 → ⚠️ 通常不该设这个超时。
### ⑤ `onErrorMap` —— 把技术异常翻译成业务异常
```java
.onErrorMap(TimeoutException.class,
        e -> new UpstreamIdleTimeoutException(idleTimeoutMs, e));
```

**为什么要换成自己的异常**:

```text
❌ 直接抛 TimeoutException:上层 catch 的是 Reactor 通用异常 → "超时""连接失败"分不清,不知返回 504 还是 502,异常里也没有"哪个渠道、超时值是多少"。
✅ 换成 UpstreamIdleTimeoutException:名字即"上游空闲超时",携带 idleTimeoutMs 能打印"20s 无数据",定位一眼到位。
   ⚠️ 但注意:★ 它【故意不映射任何状态码】—— 流式走到这一步时响应头早就发出去了,
      给不了 504,只能把流切断。★ 见 类-UpstreamIdleTimeoutException
```
> ★ **规律**:「**异常类型是对上层的一份契约**」。用第三方异常类型 = 把你的错误契约外包给了第三方。详见 → [M5 错误处理](../05-错误处理/_index.md)
---
## `chatCompletionStream()` —— 接口方法的三个动作
```java
@Override
public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
    return chatCompletionStreamRaw(request)
            .filter(chunk -> !ChatCompletionChunk.DONE_SENTINEL.equals(chunk))   // ①
            .concatMap(chunk -> normalizeChunkSafely(chunk, resolveModel(request)));  // ②③
}
```
### ① `.filter(...)` —— 把 `[DONE]` 摘掉
**为什么要摘**:`[DONE]` 是 **SSE 协议的结束哨兵**,不是"一个数据块"。接口契约里写明"实现类不得吐 `[DONE]`"(见 [类-Provider](类-Provider.md)),由 `ChatController` 在末尾统一补。

**为什么由 Controller 补,而不是这里发**:

```text
[流结束] 属于"对外的 HTTP 响应协议" → 应由写响应的唯一一方(ChatController)决定
   → 若每个 Provider 各发各的,漏一个 = 调用方永远等不到结束
```
### ★ ② `.concatMap(...)` 而不是 `.flatMap(...)` —— 一个字之差,语义完全不同
**这是流式处理里最容易写错的一处**:

| | `flatMap` | `concatMap` |
|---|---|---|
| 处理顺序 | **并发**,谁先完成谁先发出 | **严格按原顺序,一个一个来** |
| 适合 | 无关的并行任务 | ★ **顺序有语义的流** |

**为什么这里必须 `concatMap`**:

```text
上游吐三块 ["你","好","吗"],normalize 每块要花点时间(解析 JSON):
flatMap(并发):块2 先好 → "好";块1 后好 → "你";块3 → "吗" → ★ 用户看到"好吗你"(语序乱)
concatMap(顺序):块1"你" → 块2"好" → 块3"吗" → ✅ 用户看到"你好吗"
```
> ★★ **规律**:「**在有序流上,任何并发操作符都是 bug 的温床**」。`flatMap` 用起来更"自然",所以特别容易顺手写上 —— **但流式文本的顺序是有语义的,"并发"在这里没有意义,只有风险。**
### ③ `normalizeChunkSafely` —— 见下一节
---
## ★ 坏块处理:为什么需要 `normalizeChunkSafely`
```java
private Flux<ChatCompletionChunk> normalizeChunkSafely(String chunk, String model) {
    try {
        return Flux.just(normalizeChunk(objectMapper.readTree(payload), model));
    } catch (Exception e) {
        log.warn("跳过无法解析的流式分片,渠道={}, 原始内容={}", name, chunk);
        return Flux.empty();     // ★ 吞掉异常,不中断整条流
    }
}
```
### 问题:流式里的一个坏块,该不该毁掉整条流
**先说一个事实**:流式响应里的**个别分片解析失败,是真实会发生的事**:

```text
① 上游 SSE 分片传输中被截断(网络层)    ② 返回非标准块(如心跳 ": keep-alive")
③ 上游在流中间插入不认识的字段/事件     ④ 网关与上游间的代理改写了内容
```
### 两种策略的对比

| 策略 | 后果 |
|---|---|
| **整条流失败**(抛异常) | 用户已经看到 500 字了,**突然整个连接报错** —— 前面白看 |
| ★ **跳过这一个块**(本项目) | 用户看到的内容**少了一个碎片**,但流正常结束 |

```text
★★ 核心判断:"已经交付的部分"是有价值的,不能因为后面的问题而作废。
```
> ★ **这个判断的对立面也值得想**:如果是**非流式**的单个响应解析失败呢?
> ```text
> 非流式:整个响应就是一个整体 → 解析失败 = 没有内容可交付 → 应该报错 ✅
> 流式:  已经吐了几百个字 → 解析失败的是"增量" → 跳过,保留已交付部分 ✅
> ```
> **同一个"解析失败",在两种模式下策略不同** —— 因为**"已交付的价值"不同**。
### 但 `.filter` 吞掉异常有个代价,必须写日志
```java
log.warn("跳过无法解析的流式分片,渠道={}, 原始内容={}", name, chunk);
```

**注意日志里带了 `原始内容`** —— 因为它可能是排查的唯一线索。
> ⚠️ **但要提醒一句**:打原始内容有**日志膨胀和敏感信息**的风险。本项目是学习项目、内容量可控,所以这么做。生产环境里通常要**截断长度 + 脱敏**。
> ★ **规律**:「**宽容处理必须伴随告警**」。静默跳过 = 用户少了内容但没人知道;**打 WARN + 原始内容 = 出问题时能复盘**。
---
## ★ 三次改名的历史(这段是设计认知的成长轨迹)
```text
G1/G2 DeepSeekClient ──有了 Provider 接口──▶ G3a DeepSeekProvider ──"一个类顶 N 家"──▶ G3b OpenAiCompatibleProvider ← 现在的名字
```

**每次改名对应的认知升级**:

| 名字 | 它当时表达了什么 | 它暴露了什么问题 |
|---|---|---|
| `DeepSeekClient` | "一个 HTTP 客户端" | 换个厂商就得换个类,**没有抽象** |
| `DeepSeekProvider` | "实现了 Provider 接口的 DeepSeek 实现" | ★ 名字里还是绑死了厂商 —— 而实现其实**不依赖 DeepSeek** |
| `OpenAiCompatibleProvider` | ★ "我处理的是**协议**,不是厂商" | 正确 |

### 为什么最后这个名字是正确的
```text
它描述"我能处理什么协议",不是"我为谁服务" → DeepSeek/Moonshot/通义/Ollama 用同一个类
   → "为谁服务"由 ★ 构造参数(name/baseUrl)决定,不由类名决定
```
> ★★ **规律**:「**类名要描述能力,不描述使用者**」。自查方法:如果有一天加了第五家厂商,**我的类名需要改吗?** 需要改 → 名字绑死了厂商,该改。
### 这段历史在面试里怎么用
```text
问:"你这个项目的抽象是怎么演进的?"
答:"一开始按厂商拆类(DeepSeekClient),后来发现所有目标厂商都提供 OpenAI 兼容接口,差异只有 base-url/api-key/model 三样,于是收敛成 OpenAiCompatibleProvider + N 份配置。★ 关键识别点:当抽象的产物是 N 份逐字相同的代码时,那不是抽象,是复制粘贴。"
```
> **规律**:**能讲出"为什么改",比能讲出"最终是什么"值钱得多。**
---

## 相关页

- 主页面(非流式、`normalize`、构造器) → [类-OpenAiCompatibleProvider](类-OpenAiCompatibleProvider.md)
- 它实现的接口(为什么 `Raw` 不在上面) → [类-Provider](类-Provider.md)
- `responseTimeout` 为什么管不到流式 → [类-WebClientConfig](../01-骨架与配置/类-WebClientConfig.md)
- 超时异常怎么变成 504 → [M5 错误处理](../05-错误处理/_index.md)
- 流式的完整端到端链路 → [02-flows/流式请求处理流程](../../02-flows/流式请求处理流程.md)
- 取消传播怎么被测试的 → [测试-流式与取消传播](../07-测试验证/测试-流式与取消传播.md)

> **导航**:[← 上一页:类-OpenAiCompatibleProvider](类-OpenAiCompatibleProvider.md) ｜ [下一页:流程-渠道路由 →](流程-渠道路由.md) ｜ [所属模块:M2 上游渠道抽象](_index.md)
