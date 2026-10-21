# ChatController

> **本页速览**
> - 网关**唯一的正式端点**,143 行,但真正"做决定"的只有**两行**。
> - ★ 路径 `/v1/chat/completions` 是 OpenAI 官方路径 —— 这是"客户端零改造"的兑现处。
> - ★★ 本页最值钱的一句:**「路由由业务字段决定,不由 HTTP 头决定。」**
> - ⚠️ 有一行**看起来多余但必须显式写**:钉死 `Content-Type`,不钉会**直接 500**(实测)。
> - 返回类型是 `Object` 而不是 `Mono<?>` —— 一个为了"一个方法走两条路"付出的代价。
>
> **导航**:[← 上一页:M4 模块首页](_index.md) ｜ [下一页:类-PingController →](类-PingController.md) ｜ [所属模块:M4 请求入口](_index.md)

> ⚠️ **G5/G6 已经改动本页代码,下面的片段是 G3b 版本**。
> 现在 Controller 只依赖 `GatewayChatService` + `ObjectMapper`;
> "选哪条渠道、要不要重试、能不能转移、怎么结算"全部下沉到服务层和 `ChannelRouter`。
> 本页保留旧片段是为了讲**设计演进**(Controller 如何一步步变薄),
> **面试时请以 `ChatController.java` 当前源码为准**。

---
## 一句话职责

**接收 OpenAI 格式的请求,选一个渠道,按 `stream` 分流,把结果写成 HTTP 响应。**

## 位置

```text
src/main/java/io/github/maixiaowen/gateway/controller/ChatController.java     (143 行)
```

---
## 为什么需要这个类

### 它经历过两次改动,而两次都很小

| 阶段 | 改动 | 改了几行 |
|---|---|---|
| **G3-3a** | 依赖从具体的 `DeepSeekClient` 换成 `ProviderRegistry` | 换了一个字段的类型 |
| **G3-3b** | `defaultProvider()` → `resolveOrDefault(request.provider())` | ★ **就一行** |
| **G5/G6** | 路由决策从 Controller 移进 `GatewayChatService` | ★ Controller 反而更薄:只读请求、定响应头、调服务层 |

> *"加了一家厂商、加了按请求路由 —— **本类没有 if/else 分支,没有新增字段,
> 没有 import 任何厂商相关的类。这就是 SPI 抽对了的样子。**"*

```text
❌ "我实现了一个网关" → 听不出设计
✅ "加第二家厂商 + 加上路由时,这个核心 Controller ★ 只改了一个表达式" → ★ 可验证的设计质量
```

---
## 关键方法

### 构造器 —— 只依赖两样东西
```java
public ChatController(ProviderRegistry providerRegistry, ObjectMapper objectMapper) {
    this.providerRegistry = providerRegistry;
    this.objectMapper = objectMapper;
}
```

| 依赖 | 干什么用 |
|---|---|
| `ProviderRegistry` | ★ 找渠道。**注意类型是注册表,不是某个 Provider 实现** |
| `ObjectMapper` | 把 `ChatCompletionChunk` 序列化成 JSON 文本 |

**注意依赖里没有的东西**:没有 `WebClient`、没有 `JsonNode`、没有厂商名。★ **这就是"抽象没漏"的直接体现。**

---
### ★ `completions(...)` —— 唯一的正式端点
```java
@PostMapping("/v1/chat/completions")
public Object completions(@RequestBody ChatCompletionRequest request, ServerWebExchange exchange) {
    Provider provider = providerRegistry.resolveOrDefault(request.provider());   // ① 选渠道

    log.info("收到请求，渠道={}（请求指定={}），model={}，stream={}，messages={} 条，处理线程 = {}",
            provider.name(), request.provider(), request.model(),
            request.stream(), request.messages() == null ? 0 : request.messages().size(),
            Thread.currentThread().getName());                                   // ② 打日志

    if (Boolean.TRUE.equals(request.stream())) {                                 // ③ 分流
        exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache");
        return streamToClient(provider, request);
    }

    return provider.chatCompletion(request);
}
```
#### ① 选渠道 —— 只有一行,但设计都在这一行背后
```text
这一行之所以能这么短,是因为判断逻辑【全部】在注册表里:
   "没指定 → 用默认" ← resolveOrDefault    "指定了不存在 → 抛异常" ← resolve
   "可用渠道有哪些" ← UnknownProviderException      ★ Controller 只"问一句、拿一个结果"
```

```java
// ❌ 反例:Controller 开始懂业务
if (request.provider() == null) { provider = defaultOne; }
else if (!all.containsKey(request.provider())) { throw new ...("可用渠道:" + all.keySet()); }
else { provider = all.get(request.provider()); }
   ↓ ★ 每个调用点都要重写一遍这个判断 —— 三处写、两处漏
```

> ★ **规律**:「**Controller 只该做"HTTP 层"的判断**」。"哪个渠道、什么策略"是业务判断,不属于这一层。

**⚠️ 一个顺序上的细节**:注释里专门说明了"先解析再打日志"的原因 —— 为了日志里能带上
**最终走了哪个渠道**;如果解析失败,这里不会有日志,但异常处理器会打 `[渠道不存在]`
**而且带了可用渠道列表**。★ **这个推理值得学**:它没有为了"保证一定有日志"而包 `try/catch`,
而是先确认**另一条路径也有足够的日志**,才放心让异常直接抛出去。

#### ② 日志 —— 第一个字段必须是"走了哪个渠道"
```text
★ 同时打"实际渠道"和"请求指定的渠道",是因为 ★ 在指定了但被兜底的情况下这两者不一样
   —— 而那正是需要被看见的情况。
请求指定=null,  渠道=deepseek   → 正常(没指定,走默认)
请求指定=null,  渠道=moonshot   → 正常(默认渠道就是 moonshot)
请求指定=qwen,  渠道=qwen       → 正常
★ 请求指定=xxx, 渠道=deepseek   → ⚠️ 现在不会出现(指定了不存在会报 400)
                                 → 但未来做了"按 model 推断 + 降级"之后,就会出现
```

> ★ **规律**:「**日志要能区分"实际发生的"和"被请求的"**」。只打一个值,就永远看不出"请求被改写了"。

**还有一个字段:`处理线程`** —— 它是 WebFlux 的实证(见 [类-PingController](类-PingController.md))。

#### ★★ ③ 分流 —— 本页最值钱的两行
```java
if (request.stream())                          // ❌ stream 是 Boolean,可能为 null → NPE
if (Boolean.TRUE.equals(request.stream()))     // ✅ null 和 false 走同一条路
```

> 这正是 [类-ChatCompletionRequest](../03-对外契约/类-ChatCompletionRequest.md) 里"为什么用包装类型"的**下游代价** ——
> ★ 用 `Boolean` 换来了语义可见性,代价是每个使用点都要显式判 null。**这个取舍是划算的。**

**★ 那一行"看起来多余"的 `setContentType`** —— Javadoc 原话:

> *"★必须显式钉死★ 不钉的话,客户端未声明 `Accept` 时 Spring 的内容协商会挑
> JSON writer 去序列化 SSE 对象,**直接 500(实测)**。**路由由业务字段决定,不由 HTTP 头决定。**"*

```text
客户端发 body: {"stream": true},★ 但没带 Accept: text/event-stream
   ↓ Spring 的内容协商去猜"客户端想要什么格式"
它看到方法返回 Object → ★ 泛型信息在运行时拿不到 → 猜了个 JSON
   ↓ ★ 用 JSON 序列化器去写 SSE 的字符串 → 500
```

> ★★ **「路由由业务字段决定,不由 HTTP 头决定。」** —— 这句话值得背下来。
> ```text
> stream 是请求体里的 ★ 业务字段(客户端明确的意图);Accept 是 HTTP 层的 ★ 偏好(可能没发、可能不准)
> ★ 当一个决定可以被两种东西表达时,必须明确 ★ 谁说了算。
>   否则:同一个请求,因为客户端有没有发某个头而行为不同。
> ```

**`Cache-Control: no-cache`** —— *"关掉中间层缓冲。**Nginx 默认会把 SSE 攒着**,生产的经典坑。"*
代理攒着不发 → 用户转圈 20 秒 → 哗!全部出现。★ **流式体验被完全抹掉,而且网关这边看不出任何异常**。

> ⚠️ **诚实标注**:这个头**能不能真的关掉缓冲,取决于代理配置**。有些代理只认自己的
> `proxy_buffering off`。★ 发这个头是**必要条件,不是充分条件**。

---
### ★ `streamToClient(...)` —— 唯一的流式出口
```java
private Flux<String> streamToClient(Provider provider, ChatCompletionRequest request) {
    Flux<String> chunks = provider.chatCompletionStream(request).map(this::toJson);
    return chunks.concatWith(Mono.just(ChatCompletionChunk.DONE_SENTINEL));
}
```

#### 为什么返回 `Flux<String>` 而不是 `Flux<ServerSentEvent>`
> *"响应头已经被钉成 `text/event-stream`,Spring 的 SSE writer 会把**每个裸元素自动包成一条
> `data: ...` 事件**。自己再包一层 `ServerSentEvent` 是重复劳动,而且**这个方法声明返回 `Object`
> (为了兼容非流式分支),泛型信息在运行时拿不到**,Spring 未必认得出元素类型。"*

```text
✅ Flux<String>          → Spring 自动加 "data: " 前缀
❌ Flux<ServerSentEvent> → 自己包一层,Spring 可能又包一层 → 格式错
★ 而且签名是 Object → ★ 泛型被擦除,Spring 判断元素类型更不可靠
```

> ★ **规律**:「**框架已经帮你做的事,不要做第二遍**」。尤其在**类型信息不完整**(泛型擦除)时,越"显式"越容易出问题。

#### 为什么序列化放在这里,不放在 Provider 里
```text
Provider:上游 JSON → 我们的 DTO (领域逻辑)   Controller:我们的 DTO → 线上的字节 (传输逻辑)
   ↓ ★ 收益:测 Provider 时只需断言"对象对不对",不用解析 JSON 字符串
```

#### `concatWith` —— `[DONE]` 的唯一补上处
```text
上游的 [DONE] 已经被 Provider ★ 过滤掉了(它不是 JSON,不能当数据块解析)
   ↓ 由 Controller 在末尾补上我们自己的 —— ★ 收尾是"对外协议"的事,该由唯一一个地方决定
```

> 少了它会怎样 → *"OpenAI SDK 会一直等下一个块,表现为「回复完了但客户端不结束」。"*
> 详见 [类-ChatCompletionChunk](../03-对外契约/类-ChatCompletionChunk.md)

---
### `toJson(...)` —— 一个"必须炸"的异常
```java
private String toJson(ChatCompletionChunk chunk) {
    try {
        return objectMapper.writeValueAsString(chunk);
    } catch (JsonProcessingException e) {
        // 走到这里说明是我们自己的 DTO 序列化不出来 —— 那是 bug，不是上游的问题。
        // 这种错误必须炸出来，不能被当成"上游抖动"糊过去。
        throw new IllegalStateException("网关自身的流式契约序列化失败", e);
    }
}
```

```text
"我们自己的 DTO 序列化失败" = ★ 网关自己的 bug     → ★ 立刻炸,让它在测试和日志里无处可藏
"上游返回了垃圾"           = ★ 上游的问题          → 换渠道 / 重试 / 返回 502(容错)
```

**如果这里"宽容处理"(比如返回空对象)**:客户端收到畸形块 → 报"未知错误" →
排查的人去查上游、查网络、查客户端版本……★ **永远查不到"网关自己序列化炸了"**。

> ★ **规律**:「**自己的 bug 要炸,别人的问题要容错 —— 两者绝不能混**」。
> 混了之后的症状是:**所有问题看起来都像"上游抖动"** —— 一个本该 5 分钟修掉的代码 bug,被当成运维问题查了三天。
>
> 这和 [深入页](../02-上游渠道抽象/类-OpenAiCompatibleProvider-深入.md) 里"流式坏块要跳过(不中断整条流)"
> 是**一对反面**:**坏块是"别人的数据",跳过;自己的序列化失败是"我的 bug",炸掉。**

---
## 这个类在项目中的角色
```mermaid
sequenceDiagram
    participant C as 客户端
    participant F as RateLimitWebFilter(M6)
    participant CC as ChatController
    participant REG as ProviderRegistry(M2)
    participant P as Provider
    C->>F: POST /v1/chat/completions
    F->>F: 检查额度(超了 → 429,根本到不了下面)
    F->>CC: 放行
    CC->>REG: resolveOrDefault(request.provider())
    REG-->>CC: Provider 实例(或抛 400)
    CC->>CC: log(渠道 / 请求指定 / 线程)
    alt stream = true
        CC->>CC: 钉 Content-Type + no-cache
        CC->>P: chatCompletionStream()
        P-->>CC: Flux&lt;Chunk&gt;
        CC->>CC: map(toJson) + concatWith [DONE]
        CC-->>C: SSE 流
    else stream 不是 true
        CC->>P: chatCompletion()
        P-->>CC: Mono&lt;Response&gt;
        CC-->>C: JSON
    end
```

---
## 值得学习的点
### 1. 返回 `Object` 是一个"明知不好但更稳"的选择

代价是明确的:★ **泛型信息丢失**(正是上面"Spring 猜错序列化器 → 500"的根因)、编译期不检查返回类型、读签名看不出返回什么。

```text
两条分支的返回类型 ★ 本质上不同:非流式 Mono<…Response>(一个对象) / 流式 Flux<String>(一个流)
而 Java 方法只能声明一个返回类型 ↓
选项 A:拆成两个端点 ❌ ★ 破坏"客户端零改造" —— OpenAI 只有一个端点
选项 B:返回 Object,让 Spring 运行时按实际类型处理 ✅ 保住契约,代价是类型安全
★ 当"协议契约"和"类型安全"冲突时,契约优先。
```

> ★ **规律**:「**在边界处,兼容性 > 内部优雅**」。而这类取舍**必须写注释** —— 否则后来的人会觉得"返回 Object 是不懂泛型",然后"顺手改成 `Mono<ResponseEntity<Object>>`",**把流式改坏**。

### 2. ★ 这个类最该学的,是它"没写"的东西
```java
import io.github.maixiaowen.gateway.dto.*;                  // 自己的契约
import io.github.maixiaowen.gateway.provider.Provider;      // ★ SPI 接口
import io.github.maixiaowen.gateway.provider.ProviderRegistry;
import com.fasterxml.jackson.*;                    // JSON
import org.springframework.*;                      // 框架
// ★ 没有任何厂商相关的 import
// ★ 没有 JsonNode(全项目到处在用,但这里没有)
```
**验收命令一行**:
```bash
grep -i "deepseek\|moonshot\|JsonNode" ChatController.java
# → 无输出 = 抽象完整
```

> ★ **规律**:「**一个类好不好,有时候 best measured by what's missing**」。具体到面试:被问"你的抽象做得怎么样",**能给出一个可执行的验证命令**,比说"我觉得挺好的"强得多。
### 3. ★ 这条规律在很多地方适用

| 场景 | 意图 | 事实 |
|---|---|---|
| 渠道路由 | 请求指定的 `provider` | 实际用的 provider |
| 限流 | 配置的容量 | 实际剩余的令牌 |
| 缓存(将来) | 请求的 model | 实际命中的模型 |

> ★ **规律**:「**当系统可能改写用户输入时,"改写前"和"改写后"都要记**」。只记一个,你就永远发现不了"改写"。

---
## 相关页

- 模块首页(含 `AskController` 的删除理由) → [M4 请求入口](_index.md)
- 兄弟类:探针 → [类-PingController](类-PingController.md)
- 入参出参的结构 → [M3 对外契约](../03-对外契约/_index.md)
- `resolveOrDefault` 的设计判断 → [流程-渠道路由](../02-上游渠道抽象/流程-渠道路由.md)
- 异常怎么变响应 → [M5 错误处理](../05-错误处理/_index.md)
- 为什么限流在它之前 → [类-RateLimitWebFilter](../06-限流/类-RateLimitWebFilter.md)

> **导航**:[← 上一页:M4 模块首页](_index.md) ｜ [下一页:类-PingController →](类-PingController.md) ｜ [所属模块:M4 请求入口](_index.md)
