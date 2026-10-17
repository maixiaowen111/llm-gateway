# RedisTokenBucketLimiter · 深入

> **本页速览**
> - 主页面讲了"取令牌"这一件事,本页讲**剩下 95% 的代码**:三种"出错时怎么办"。
> - ★★ 全页的核心是一个**真实踩过的坑**:字段名差一个单词 → `Retry-After: 0` → **重试风暴**。
> - ★ `ScriptResult` 用**装箱类型**是刻意的 —— 理由是一句可以背下来的话:**基本类型的危险在于"缺失时它会静默变成一个看起来正常的值"**。
> - ★ 一行 `StandardCharsets.UTF_8` 防的是"中文 Windows 上 JDK 17 默认 GBK"。
> - ★★ `onLimiterFailure` 里做的**不是异常处理,是一次业务决策**。
>
> **导航**:[← 上一页:类-RedisTokenBucketLimiter](类-RedisTokenBucketLimiter.md) ｜ [下一页:类-RateLimitWebFilter →](类-RateLimitWebFilter.md) ｜ [所属模块:M6 限流](_index.md)

## 本页讲什么
```text
tryAcquire 六步(主页面只讲 ①②③④:拼 key/执行/超时;本页讲 ⑤⑥ 和三个辅助):
  ① key ② execute ③ .next() ④ .timeout()       ← 主页面(④ ★ 两种故障的区分)
  ⑤ .map(parseDecision) ★本页-防腐层  ⑥ .onErrorResume ★本页-业务决策 | 辅助 loadScript() ★字符集+SHA1  ScriptResult ★★真实踩过的坑
```

## ⑤ `parseDecision(String json)` —— 一行转换 + 一个刻意的"炸"
```java
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
```
### ★ 为什么解析失败要抛,而不是"默认放行"
> *"解析失败会抛异常,然后被 `onLimiterFailure` 接住 —— 这是刻意的:
> **'脚本返回了看不懂的东西'和'Redis 连不上'对网关的影响完全一样,都是'限流器此刻不可信'**。分开处理只会让代码更长,不会让结论更准。"*
```text
★ 抛的不是"炸掉程序",而是"★ 走降级路径":抛 → onErrorResume 接住 → 按 failOpen 决定
★★ 抛异常在这里是"路由手段"不是"错误处理手段" → 把"脚本返回垃圾"归并到"Redis 不可用"这条同源问题
```
> ★★ **规律**:「**归并的标准不是"原因相同",是"对策相同"**」—— 原因完全不同(网络 vs 数据),但**对策一样**,就该走同一条路径。★ 反过来说:原因相同但对策不同就**必须分开**(这就是 [类-RateLimitDecision](类-RateLimitDecision.md) 三态的全部理由)。

## ⑥ `onLimiterFailure(...)` —— ★★ 这不是异常处理,是业务决策
> *"Redis 不可用(或脚本坏了)时的降级路径。**这里做的是一次业务决策,不是异常处理。**"*
```java
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
```
### ★★ fail-open 的完整论证(值得背下来)
> *"限流器是**保护性**组件,不是**功能性**组件 —— 它对'请求能不能被服务'没有贡献,只对'该不该被服务'提意见。**保护性组件的失败,不该升级成网关的失败。**
> 反过来算代价:Redis 抖动 3 秒,
> - **fail-open**:这 3 秒里超卖了若干个请求(**钱**),用户完全无感;
> - **fail-closed**:这 3 秒里 **100% 的请求被拒**(**事故**),而限流本来只是为了防万一。
> **用一次必然发生的全面故障,去防一次可能发生的超卖,这笔账是亏的。**"*
```text
fail-closed 代价 ★ 必然:Redis 每次抖动都 100% 变事故(抖动是常态:持久化/主从切换/网络重传)
fail-open 代价 ★ 可能:只有"这 3 秒恰好有人滥用"才真超卖,而且只有 3 秒
```
> ★★ **规律**:「**在不确定的时候,比较"必然的代价"和"可能的收益"**」—— 适用于所有"要不要加一道保险"的决策。★ **如果这道保险每次都要付出代价,而它防的是偶发事件,那它多半是亏的。**
### ★★ "静默 fail-open 绝对不能接受"
> *"放行必须留下 **ERROR** 日志。**限流悄悄失效和限流从来没配,在监控上是同一件事 —— 直到账单出来。**"*
```text
"限流悄悄失效" vs "限流从来没配":从外部看一样(都放行)、从监控看一样(无异常指标)
→ ★ 唯一区别在日志里 → ★★ "日志里有没有这条 ERROR"就是唯一的判据
```
| 细节 | 为什么 |
|---|---|
| ★ 级别是 **`log.error`** 不是 `warn` | 它意味着"网关当前没有任何额度防线" —— ★ 这是事故级别的信息 |
| 日志里带 `key={}` | ★ 知道是**哪个客户端**在故障期间被放行 |
| 日志里带 `原因={}` | ★ 区分是超时 / 连不上 / 脚本报错 |
| ★ 文案里有 **`★` 标记和"没有任何额度防线"** | ★ 让人在日志里**一眼看见**,而不是和普通日志混在一起 |
| 两个分支文案都写了"**返回 503,不是 429 —— 这是我们的问题,不是调用方的**" | ★ 把 [类-RateLimitDecision](类-RateLimitDecision.md) 的设计意图**写在了代码里** |
> ★★ **规律**:「**降级路径必须留下比正常路径更响的痕迹**」—— 正常路径有**指标**兜着(成功率、延迟),而**降级路径往往什么指标都不动**,它让系统"看起来一切正常",这正是它危险的地方。
### ⚠️ 一处诚实的标注:日志里的 `reason` 可能为空
```java
String reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
```
```text
★ 有些异常的 getMessage() 是 null(某些 NPE、部分超时异常)→ 拼出 "ReadTimeoutException: null" → 信息量为零
```
更好的写法是加上 key 的上下文或异常链,但**这不是本项目的重点**;**真正重要的是:这条日志一定会出现**,格式化的小瑕疵不影响它的核心价值。

## ★ `loadScript()` —— 两件事:字符集 + SHA1
```java
private RedisScript<String> loadScript() {
    ClassPathResource resource = new ClassPathResource(SCRIPT_PATH);
    String lua;
    try (InputStream in = resource.getInputStream()) {
        lua = StreamUtils.copyToString(in, StandardCharsets.UTF_8);   // ★ 显式 UTF-8
    } catch (IOException e) {
        throw new IllegalStateException("加载限流脚本失败：" + SCRIPT_PATH, e);
    }
    return new DefaultRedisScript<>(lua, String.class);
}
```
### ★★ 为什么不能省掉那个 `StandardCharsets.UTF_8`
> *"`DefaultRedisScript` 从 `Resource` 构造时,走的是 `ResourceScriptSource`,它用 `Charset.defaultCharset()` 读文件。**JDK 17 在中文 Windows 上这个值是 GBK**(JDK 18 才把 UTF-8 定为默认),于是脚本里的中文注释会变成乱码 —— **不会报错,只是 EVAL 出来的脚本没法看**。
> **凡是"读文件"的地方,编码都该写死,不该依赖运行环境的默认值。**"*
```text
开发者机器(-Dfile.encoding=UTF-8)→ ✅   CI 容器(Linux 通常 UTF-8)→ ✅   ★ 中文 Windows 同事/JDK 17 → ❌ 脚本中文变乱码
★★ 且它 ★ 不报错:注释乱码但 --[[ ]] 仍是注释 → 脚本正常执行 → 限流正常,★ 唯一症状是 redis-cli 里 EVAL 出来看时注释读不懂
```
> ★★ **规律**:「**"不会报错"的坑,是最值得主动防的坑**」—— 没有任何信号提醒你它出了问题,而防它的成本通常极低:**一行显式指定编码**。
| 依赖默认值的地方 | 风险 |
|---|---|
| ★ **文件编码** | 换机器/换 JDK 版本就变 |
| ★ **时区** | 服务器换成 UTC 就变 |
| ★ **Locale**(数字/日期格式) | `1,5` vs `1.5` |
| ★ **换行符** | `\n` vs `\r\n` |
| ★ **默认字符集** | ★ **本项目踩的就是这个** |
> ★ 这些统称 **"环境隐式依赖"** —— 共同特征:**代码在任何机器上都能跑,但行为不一样。**
### ★ 脚本文件缺失为什么必须炸
```java
throw new IllegalStateException("加载限流脚本失败：" + SCRIPT_PATH, e);
```
> *"脚本文件缺失 = **构建产物坏了**。这种情况必须炸,不能'降级成不限流' —— **那会让一个打包事故悄悄变成一次安全事件。**"*
```text
区别:Redis 挂了 → ★ 运行时故障 → 降级(放行);   脚本文件不存在 → ★ 构建/部署错误 → ★ 炸
★★ 判据:"会自己好吗?" 会(Redis 抖动会恢复)→ 降级;  不会(打包错不会自愈)→ ★ 炸,让人立刻修
```
> ★★ **规律**:「**同一个组件里,不同的故障可以有不同的处理方式 —— 按"能不能自愈"分**」—— 这和 [类-RateLimitConfig](类-RateLimitConfig.md) 里"启动连不上必须 fail-fast,运行时连不上可以 fail-open"是**同一条判据**。
### ★★ 每次调用传的是 SHA1,不是脚本体
> *"`DefaultRedisScript` 会算出脚本的 SHA1,`DefaultReactiveScriptExecutor` 首次执行时用 `EVALSHA`,遇到 `NOSCRIPT` 才回退到 `EVAL` 把脚本推上去。**所以脚本多大都不影响每次调用的网络开销** —— 传的只有 40 个字符的 SHA1。"*
```text
首次 EVAL <124 行脚本体> → Redis 缓存起来;  之后 EVALSHA <40 字符 SHA1> ← ★ 只传哈希
★ Redis 返回 NOSCRIPT(重启过,缓存没了)→ 自动回退 EVAL 重推 → ★ Spring Data Redis 自动做,不用管
```
> ★★ **规律**:「**"每次都要传的东西"如果能换成"一个引用",网络开销就和它的大小无关了**」—— 同类设计:ETag、内容寻址存储、Git object hash、Docker 镜像层。

## ★★ `ScriptResult` —— 本页最重要的一段
```java
@JsonIgnoreProperties(ignoreUnknown = true)
private record ScriptResult(
        Boolean allowed,
        Long remaining,
        @JsonProperty("retryAfterMs") Long retryAfterMillis
) {
    ScriptResult {
        if (allowed == null || remaining == null || retryAfterMillis == null) {
            throw new IllegalStateException("限流脚本的返回缺少必需字段：allowed=" + allowed
                    + "，remaining=" + remaining + "，retryAfterMs=" + retryAfterMillis);
        }
    }
}
```
### ★★ 那个真实踩过的坑
**Javadoc 里完整记录了它 —— 这段记录本身就是一个很好的"工程复盘"样本**:
> *"第一版这里写的是 `long retryAfterMillis`,而脚本返回的字段名叫 `retryAfterMs`。**两个名字差了一个单词,Jackson 匹配不上**,于是这个字段用 `long` 的默认值 **0** 顶上,整个反序列化过程**一声不响地成功返回**。
> 后果非常具体:被限流的响应里带上了 `Retry-After: 0`,语义是"**立刻重试**" —— 而客户端的重试库真的会立刻重试,形成**重试风暴**,把刚刚才缓过来的桶再次打满。"*
```text
Lua 返回 {"allowed":0,"remaining":0,"retryAfterMs":1500} ← 脚本叫 retryAfterMs; Java 字段 long retryAfterMillis ← 我叫 retryAfterMillis
★ Jackson 按名匹配找不到 retryAfterMs → ignoreUnknownFields 默认 true → 多出的字段被忽略
→ retryAfterMillis 没赋值 → 基本类型默认 ★ 0 → ★★ 反序列化 ★ 成功返回,无异常
→ RateLimitDecision.denied(0,0) → retryAfterSeconds() 里 <=0 → DEFAULT(60) → Retry-After:60(被兜住?)
```
> ⚠️ **重要修正**:`retryAfterSeconds()` 里有一个 `retryAfterMillis <= 0` 的兜底,它会返回 60 秒。**所以在当前代码里,这个 bug 的实际表现是"永远返回 60 秒",而不是"Retry-After: 0"。**
> ★★ **而这恰恰是它更危险的地方**:兜底让症状从"明显错误"变成了**"看起来合理但不对"** —— 桶明明 1.5 秒后就能用,却告诉客户端等 60 秒。**如果不是那条测试断言了区间,这个偏差会一直存在。**
### ★★ 抓住它的那条测试
> *"这是被 `RateLimitConcurrencyTest#tokensAreRefilledOverTime` 抓住的 —— 那条断言写的是'`retryAfterMillis` 应该在 **1~200** 之间',它拿到的实际值是 **0**。**'断言一个值落在合理区间'比'断言它不为 null'值钱得多。**"*
**三种断言的强度对比(这是本页最该带走的一条)**:
| 断言写法 | 能抓住这个 bug 吗 | 强度 |
|---|---|---|
| `assertNotNull(result)` | ❌ 抓不住 —— 它是个合法的 `0` | ★ 最弱 |
| `assertTrue(retryAfterMillis >= 0)` | ❌ 抓不住 —— `0 >= 0` 成立 | ★ 弱 |
| ★ `assertTrue(1 <= retryAfterMillis && retryAfterMillis <= 200)` | ★★ **抓住了** | ★★ **强** |
```text
★★ 规律:「断言"落在合理区间"比"不为空/不为负"值钱得多」—— 错误默认值几乎总是"合法但不可能"的值:
0 合法但"1.5秒等待"不可能 0; false 合法但"有权限"不该默认 false; "" 合法但"渠道名"不该空串 → ★★ "合理区间"天然把默认值排除在外
```
> ★ **推广成一条写测试的纪律**:「**凡是"算出来的值",断言它落在合理区间,而不只是断言类型正确**」。
### ★★ "基本类型的危险" —— 可以背下来的一句话
> *"**基本类型的危险恰恰在于:缺失时它会静默变成一个'看起来正常'的值**(0 / false),而 0 和 false 在很多场景下都是合法输入,于是**没有任何东西会报错**。换成装箱类型,缺失就是 `null`,我们才有可能在这里把它拦下来。"*
| | ★ 基本类型 `long` | ★ 装箱类型 `Long` |
|---|---|---|
| 字段缺失时 | ★ **`0`**(静默) | ★ **`null`**(显式) |
| 能判断"缺失"吗 | ❌ **不能** —— 分不清"真的是 0"和"没读到" | ✅ **能** |
| 编译期检查 | —— | ⚠️ **可能 NPE**(代价) |
```text
★★ 有意的取舍:基本类型=类型安全但 ★ 丢失"缺失"信息;  装箱类型=能区分"缺失"但 ★ 每处要判空
→ ★ 项目选择:在 ★ "跨越边界"位置用装箱 → 因为"脚本字段名对不对"是 ★ 需要被检测 的东西
```
> ★★ **规律**:「**在跨越边界的地方用装箱类型 —— 因为边界是"缺失"发生的地方**」。对象内部、循环变量、计算结果,用基本类型完全没问题。
### ★ 那个紧凑构造器:把"检测"变成"不可能忽略"
```java
ScriptResult {
    if (allowed == null || remaining == null || retryAfterMillis == null) {
        throw new IllegalStateException("限流脚本的返回缺少必需字段：allowed=" + allowed
                + "，remaining=" + remaining + "，retryAfterMs=" + retryAfterMillis);
    }
}
```
```text
✅ 放构造器:创建出来就一定合法 → 使用点不用判空 → "不可能构造出缺字段的 ScriptResult"
❌ 放调用处:每个使用点都要检查(现在一个,将来可能不止)
```
> ★ **这和 [类-ProviderRegistry](../02-上游渠道抽象/类-ProviderRegistry.md)、[类-RateLimitProperties](类-RateLimitProperties.md) 是同一个手法**:**不可变对象 + 构造器校验 = "不合法的东西根本造不出来"**。
### ★ `@JsonProperty("retryAfterMs")` —— 为什么不是"改个名字对上就行"
> *"显式绑定字段名:脚本里叫 `retryAfterMs`,这里叫 `retryAfterMillis`。**两者不一致时不要靠'改个名字让它们碰巧对上'来修** —— 那等于让 Lua 里的字段名决定了 Java 的命名。在防腐层的这一个点上把映射写明白,两边的命名就能各自演化。"*
```text
❌ 修法A:Java 字段改名 retryAfterMs → 立刻对上,但 Java 命名跟着 Lua 走 → ★★ 脚本改 retry_after_ms 又得改
✅ 修法B(项目采用):加 @JsonProperty 显式声明映射 → ★ 命名各自独立,且"这里有映射"★ 在代码里看得见
```
> ★★ **规律**:「**两边的命名碰巧一致,是一个巧合,不是一条契约**」。★ 把巧合变成显式映射,代价是一行注解,收益是"它们可以独立演化"。
### ★ `@JsonIgnoreProperties(ignoreUnknown = true)` —— 和上面不矛盾
> *"脚本以后加字段时,老的 Java 侧不会因为'多了个不认识的字段'而炸 —— **让升级可以只升一边。** 注意它和上面那段并不矛盾,两者合起来是一条完整的原则:**多出来的字段可以容忍(附加是安全的),必需的字段绝不能缺席(缺失是危险的)。**"*
| | 脚本多了字段 | 脚本少了一个必需字段 |
|---|---|---|
| 处理 | ★ **容忍**(忽略) | ★★ **炸** |
| 为什么 | 附加信息无害 → 让升级可以单边进行 | ★ 缺失 = 两边对不上了 |
| 备注 | 这和 [M3 的"额外字段容忍、必需字段致命"](../03-对外契约/契约-OpenAI兼容约定.md) **是同一条原则** | |
> ★★ **规律**:「**"多了"通常是安全的,"少了"几乎总是危险的**」—— 在 API 版本演进、配置兼容、协议设计里都成立。

## 值得学习的点
### 1. ★★ 三处"静默失败",三种不同的防线
| 坑 | 静默的表现 | 防线 |
|---|---|---|
| ★ 字符集 | 中文变乱码,**仍然正常执行** | 显式 `UTF_8` |
| ★★ 字段名不匹配 | 用默认值 `0` 顶上,**反序列化成功** | 装箱类型 + 构造器判空 |
| ★ 脚本返回垃圾 | 解析异常,**可能被吞掉** | `throw` → 走降级路径 |
```text
★★ 三个坑都不是"程序崩溃",而是"继续跑但行为和你想要的不一样",且 ★ 没有任何天然发现渠道:
没异常→错误日志没有; 没指标变化→监控看不出; 没测试失败→★ 除非断言够精确 → ★★ 只能靠"主动设计检测点"发现,不能靠"等它暴露"
```
> ★★ **规律**:「**显式声明胜过依赖默认值 —— 因为默认值不会告诉你它被用上了**」。三种"默认值"来源:**运行环境的默认字符集、基本类型的默认 0、被吞掉的异常**。
### 2. ★★ "降级路径要有存在感" 是一个可操作的纪律
```java
// ❌ 常见的写法
} catch (Exception e) {
    log.warn("限流器异常，放行");       // ★ 一条和普通日志混在一起的 warn
    return RateLimitDecision.allowedWithoutQuota();
}

// ✅ 项目的写法
log.error("[限流器故障-已放行] Redis 不可用或脚本异常，本次请求不设限。"
        + "key={}，原因={}。★ 若这条日志持续出现，说明网关当前没有任何额度防线 ★", key, reason);
```
| 维度 | 常见写法 | ★ 项目的写法 |
|---|---|---|
| 级别 | `warn` | ★ **`error`** |
| 前缀 | 无 | ★ **`[限流器故障-已放行]`**(便于日志检索) |
| 上下文 | 无 | ★ **key + 原因** |
| **后果说明** | 无 | ★★ **"说明网关当前没有任何额度防线"** |
> ★★ **规律**:「**日志要回答"看到这条我该做什么",而不只是"发生了什么"**」—— ★ 最后那句"说明网关当前没有任何额度防线"就是答案:值班的人看到它,知道**现在要立刻去查 Redis**,而不是"哦,有一条 warn"。
### 3. ★ "抛异常"可以是一种控制流手段
| 位置 | 抛什么 | ★ 意图 |
|---|---|---|
| `parseDecision()` | `IllegalStateException` | ★ **路由**:把"解析失败"归并到降级路径 |
| `loadScript()` | `IllegalStateException` | ★★ **炸掉**:构建产物坏了,不能降级 |
| `ScriptResult` 构造器 | `IllegalStateException` | ★ **契约校验**:缺字段是致命错误 |
```text
★★ 三个 throw,三种意图,看起来一模一样 → 区别只靠注释和上下文:
第一处被 onErrorResume 接住 → 放行;  后两处构造期抛出 → ★ 让应用起不来
```
> ★★ **规律**:「**同一个 `throw`,在链条的不同位置后果完全不同**」—— ★ 所以写 `throw` 时,一定要知道**谁会接住它**。在 WebFlux 链式调用里,这个"谁接住"往往在**几行之外的一个 `onErrorResume`**。
### 4. ⚠️ 三处诚实的"没做"
| 没做的事 | 现状 | 影响 |
|---|---|---|
| **没有分布式追踪 ID** | 日志里只有 `key`(客户端标识) | ⚠️ 无法把"限流日志"和"那一次具体请求"关联起来 |
| **没有限流指标** | ❌ **没有任何 metrics** | ★★ 只能靠日志量估;无法做告警 |
| **没做本地兜底** | Redis 挂了要么全放行、要么全拒绝 | ⚠️ 中间态(本地内存限流兜一层)没做 |
> ⚠️ **第二条最值得说**:
> ```text
> ★ 没暴露指标 → 无法回答:当前多少请求被限流? fail-open 触发几次? 哪个客户端最接近限额?
> → ★★ 唯一途径是 ★ 数日志 → "事后"手段,做不了实时告警
> ```
> ★ 面试时主动说这条,比被问出来好:**"限流的核心逻辑是完整的,但我没接入 metrics。要补的话是三个计数器:放行数、拒绝数、降级数 —— 有了它们才能配'降级率超过 1% 就告警'。"**

## 相关页

- 主页面(为什么要令牌桶、`tryAcquire` 的六步) → [类-RedisTokenBucketLimiter](类-RedisTokenBucketLimiter.md)
- 被执行的算法 → [脚本-token_bucket.lua](脚本-token_bucket.lua.md)
- 返回的类型(三态 + 向上取整) → [类-RateLimitDecision](类-RateLimitDecision.md)
- 抓住那个 bug 的测试 → [测试-限流与并发](../07-测试验证/测试-限流与并发.md)
- 对照组:不做原子会怎样 → [测试-对照组NaiveTokenBucket](../07-测试验证/测试-对照组NaiveTokenBucket.md)
- 启动时的 fail-fast(同一条判据) → [类-RateLimitConfig](类-RateLimitConfig.md)
- M3 那条"额外容忍、缺失致命" → [契约-OpenAI兼容约定](../03-对外契约/契约-OpenAI兼容约定.md)

> **导航**:[← 上一页:类-RedisTokenBucketLimiter](类-RedisTokenBucketLimiter.md) ｜ [下一页:类-RateLimitWebFilter →](类-RateLimitWebFilter.md) ｜ [所属模块:M6 限流](_index.md)
