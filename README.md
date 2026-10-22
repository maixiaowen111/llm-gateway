# LLM Gateway · 大模型统一接入网关

[![CI](https://github.com/maixiaowen111/llm-gateway/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maixiaowen111/llm-gateway/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F.svg)](https://spring.io/projects/spring-boot)

> 把「调用大模型」收拢到一个地方：业务侧只认一套 OpenAI 兼容接口，
> 厂商差异、密钥、限流、熔断、计费、观测全部关在网关门内。

**Java 17 · Spring Boot 3.5 · WebFlux (Reactor Netty) · Redis + Lua · Resilience4j · Micrometer**

59 个主类 / 约 7,900 行 · 23 个测试类（+1 辅助类）/ **92 个测试**（2026-10-22 全绿）

---

## 为什么需要它

假设订单平台、客服系统、Agent 三套业务都要调用大模型。如果各自直连厂商：

```text
订单平台 ──┐
客服系统 ──┼── 各自直连 ──→ DeepSeek / Moonshot / Anthropic
Agent   ──┘
```

| 问题 | 后果 |
|---|---|
| 每家厂商协议/字段不同 | 每个业务都要写一遍适配 |
| API Key 散落到 N 个项目 | 泄漏面 = N 倍 |
| 没人限流 | 某个业务写错循环，额度一夜刷爆 |
| 换厂商 | N 个系统一起改 |
| 花了多少钱 | 没人说得清 |

接入网关后，业务只改一个 `base_url`：

```text
订单平台 ──┐
客服系统 ──┼──→ LLM Gateway ──→ DeepSeek
Agent   ──┘     一套接口 + 一套账      ├→ Moonshot
                                      └→ Anthropic（协议翻译）
```

**网关的价值 = 把厂商差异关在门内，把风险和成本留在门内可见。**

---

## 核心能力

| 能力 | 状态 | 说明 |
|---|---|---|
| OpenAI 协议兼容 | ✅ | 客户端只改 `base_url`，OpenAI SDK / LangChain4j 可直接接入 |
| 非流式 / SSE 流式 | ✅ | `stream: true`，含 `[DONE]` 收尾、逐块空闲超时 |
| 取消传播 | ✅ | 客户端断开 → 上游连接关闭，停止推理、停止烧 token |
| Provider SPI | ✅ | 两家 OpenAI 兼容厂商共用 1 个实现类；Anthropic 走独立实现 |
| 多渠道 / 按模型路由 | ✅ | 渠道用 `models` 认领模型名，命中即优先 |
| 平滑加权轮询 | ✅ | Nginx 同款算法，5:1 权重下序列为 `A A A B A A` |
| 熔断 / 重试 / 故障转移 | ✅ | **按渠道隔离**熔断；**首字节之后禁止换渠道** |
| 限流 | ✅ | Redis + Lua 令牌桶，原子扣减，32 并发不超卖 |
| Token 计量与计费 | ✅ | 预扣 → 结算（多退少补）→ 退款；**断流也计费** |
| Function Calling 透传 | ✅ | OpenAI / Anthropic 双向翻译：`tools` ↔ `input_schema`、`tool_use` ↔ `tool_calls`，含流式参数拼接 |
| 客户端鉴权 | ✅ | `Authorization: Bearer` / `X-Api-Key`；只存 SHA-256 哈希，拒绝的请求不会到达上游 |
| 多实例渠道状态 | ✅ | Redis HASH + Lua 原子写 + Pub/Sub + 单调版本号，摘渠道对所有实例生效 |
| 观测 | ✅ | Micrometer 指标、Prometheus 端点、访问日志、渠道健康检查、traceId 贯穿 |
| 链路追踪 | ✅ | OpenTelemetry（OTLP），W3C `traceparent` 透传到上游，Jaeger 可查 |
| 可视化与告警 | ✅ | Grafana 预置面板 + Prometheus 告警规则（错误率 / P95 / 熔断 / 上游异常） |
| 管理接口 | ✅ | 渠道启停、用量查询、充值；Token 鉴权，默认 fail-closed |
| Docker Compose | ✅ | 网关 + Redis 一键起；观测栈用 override 文件按需拉起 |

---

## 架构

```mermaid
flowchart TD
    C["客户端<br/>OpenAI SDK / LangChain4j / curl"]
    C -->|"POST /v1/chat/completions"| LOG["AccessLogWebFilter<br/>requestId + 耗时"]
    LOG --> AUTH["ClientAuthWebFilter<br/>Bearer / X-Api-Key → clientId"]
    AUTH -->|"401"| C
    AUTH --> RL["RateLimitWebFilter<br/>Redis + Lua 令牌桶"]
    RL -->|"429"| C
    RL --> CT["ChatController"]
    CT --> SVC["GatewayChatService"]
    SVC --> ROUTER["ChannelRouter<br/>显式渠道 / 模型认领 / 加权轮询"]
    ROUTER --> STATES["ChannelStateService<br/>本地缓存 + Redis 多实例同步"]
    SVC --> BILL["BillingService<br/>预扣 → 结算 / 退款"]
    SVC --> CB["ChannelCircuitBreakers<br/>每个渠道一个熔断器"]
    CB --> P1["OpenAiCompatibleProvider<br/>deepseek / moonshot"]
    CB --> P2["AnthropicMessagesProvider<br/>协议翻译"]
    P1 --> U1["DeepSeek / Moonshot"]
    P2 --> U2["Anthropic Messages API"]
    SVC -.->|"Micrometer"| MET["/actuator/prometheus"]
    SVC -.->|"OTLP spans"| TRACE["Jaeger / OTLP Collector"]
    STATES -.->|"HASH + Pub/Sub"| R
    BILL -.-> R[("Redis<br/>余额 / 预扣凭据 / 令牌桶 / 渠道状态")]
```

关键分层：

- **SPI 层**：`Provider` 只承诺「一次性回答」和「逐字回答」，接口里不出现厂商名。
- **路由层**：`ChannelRouter` 输出**有序**候选列表，并过滤禁用/熔断的渠道。
- **保护层**：限流在过滤器中；熔断、重试、转移在服务层；计费在调上游之前。
- **观测层**：所有终态（成功 / 失败 / 取消）都汇入同一组指标。

---

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
# 1. 可选：注入真实密钥；不填也能跑通健康检查、管理接口和 402 计费拦截
export DEEPSEEK_API_KEY=sk-xxx

# 2. 构建并启动（网关 + Redis）
docker compose up -d --build

# 3. 等健康检查变绿
docker compose ps

# 4. 冒烟测试（compose 默认开了鉴权，dev key 见下）
BASE_URL=http://localhost:8081 ADMIN_TOKEN=dev-admin-token ./scripts/smoke.sh
```

compose 内置的开发客户端（**只能用于本地**）：

```text
clientId : demo
API Key  : sk-gw-dev-demo-key-0123456789
SHA-256  : 1fcd8ad3c239fa16ed49dd131f0e038f5447b8b8fefa75de15b15fb47de45ade
```

预期输出：

```text
== 1/6 健康检查 ==                    ✅ 200
== 2/6 客户端鉴权（G10）==            ✅ 401 / ✅ 401
== 3/6 管理接口 fail-closed ==        ✅ 403
== 4/6 管理接口可用（带 token）==     ✅ 200
== 5/6 计费拦截（不需要真实 Key）==   ✅ 402
== 6/6 真实调用（可选）==             ⏭ 未设置 DEEPSEEK_API_KEY
通过 6 项，失败 0 项，跳过 1 项
```

> **第 5 步不需要真实 API Key**：402 是预扣阶段拦下的，请求根本没打到上游。
> 这是刻意的设计 —— 核心链路应该能在离线、零成本的环境里被验证。

### 可选：把看板和链路追踪一起拉起来

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

| 组件 | 地址 | 说明 |
|---|---|---|
| Prometheus | http://localhost:9090 | Status → Targets 应看到 `llm-gateway` UP |
| Grafana | http://localhost:3000 | `admin` / `admin`，面板已自动加载 |
| Jaeger | http://localhost:16686 | Service 选 `llm-gateway`，可按 traceId 搜索 |

> 观测栈是 override 文件，**不参与**默认的 `docker compose up`：
> 改一行日志格式的人不该被迫下载三个监控镜像。
> 生产不要照抄单机配置（默认密码、Jaeger 内存存储），见文件顶部说明。

### 方式二：本地运行

```bash
# 环境：JDK 17+ / Maven 3.9+ / Redis 6+（限流和计费需要）
cp .env.example .env      # 填 DEEPSEEK_API_KEY
source .env

GATEWAY_RATE_LIMIT_ENABLED=true \
GATEWAY_BILLING_ENABLED=true \
GATEWAY_ADMIN_TOKEN=dev-admin-token \
GATEWAY_AUTH_ENABLED=true \
GATEWAY_AUTH_KEYS="demo:1fcd8ad3c239fa16ed49dd131f0e038f5447b8b8fefa75de15b15fb47de45ade" \
mvn spring-boot:run
```

> `GATEWAY_AUTH_KEYS` 的格式是 `clientId:sha256(apiKey)`，**明文 key 不落盘、不进配置**。
> 生成方式：
> `printf '%s' '你的明文key' | sha256sum`（Linux/macOS）
> 或 `py -c "import hashlib;print(hashlib.sha256(b'你的明文key').hexdigest())"`（Windows）。
> 不设 `GATEWAY_AUTH_ENABLED=true` 时网关会启动并打 WARN —— 它信任可伪造的 `X-Client-Id`，**只适合本地**。

启动后：

```bash
curl http://localhost:8081/actuator/health
```

---

## 调用示例

### 非流式

```bash
curl -s http://localhost:8081/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-gw-dev-demo-key-0123456789" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "用一句话解释什么是网关"}]
  }'
```

### 流式（注意 `-N` 关闭 curl 缓冲）

```bash
curl -N http://localhost:8081/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-gw-dev-demo-key-0123456789" \
  -d '{
    "model": "deepseek-chat",
    "stream": true,
    "messages": [{"role": "user", "content": "逐字讲一下 SSE"}]
  }'
```

### Function Calling

```bash
curl -s http://localhost:8081/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-gw-dev-demo-key-0123456789" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "帮我查订单 A1001"}],
    "tools": [{
      "type": "function",
      "function": {
        "name": "queryOrder",
        "description": "按订单号查询订单",
        "parameters": {
          "type": "object",
          "properties": {"orderNo": {"type": "string"}},
          "required": ["orderNo"]
        }
      }
    }],
    "tool_choice": "auto"
  }'
```

网关只做搬运：`tools` 原样转发，`tool_calls` 原样返回，
流式 `delta.tool_calls` 的增量片段**不由网关拼装**（拼装是客户端的职责）。

### 管理接口

```bash
# 查看渠道（运行期状态，不是配置回显）
curl -H "X-Admin-Token: $TOKEN" http://localhost:8081/admin/channels

# 运行期摘掉一条渠道（不重启）
curl -X POST -H "X-Admin-Token: $TOKEN" -H "Content-Type: application/json" \
     -d '{"enabled": false}' \
     http://localhost:8081/admin/channels/moonshot/enabled

# 查余额与用量
curl -H "X-Admin-Token: $TOKEN" http://localhost:8081/admin/usage/tenant-a

# 充值（正数）或对账修正（负数）
curl -X POST -H "X-Admin-Token: $TOKEN" -H "Content-Type: application/json" \
     -d '{"amount": 100000}' \
     http://localhost:8081/admin/clients/tenant-a/credits
```

---

## 配置

全部配置在 `src/main/resources/application.yml`，密钥一律 `${ENV_VAR:}` 占位。

| 前缀 | 作用 | 关键项 |
|---|---|---|
| `gateway.providers` | 渠道定义 | `default-channel`、`channels.*`（type / base-url / api-key / group / weight / models） |
| `gateway.providers.routing` | 路由策略 | `auto-route`（默认 false）、`failover-enabled`、`failover-on-explicit` |
| `gateway.resilience` | 熔断与重试 | 失败率阈值、滑动窗口、半开探测数、重试次数与退避 |
| `gateway.billing` | 计费 | `enabled`、`fail-open`、单价、`default-max-tokens`、预扣 TTL |
| `gateway.rate-limit` | 限流 | 桶容量、每秒回填、`fail-open` |
| `gateway.upstream` | 超时 | connect 3s / read 30s / idle 20s |
| `gateway.admin.token` | 管理鉴权 | 不配置时 `/admin/**` 一律 403 |
| `gateway.auth` | 客户端鉴权 | `enabled`、`keys`（`clientId:sha256Hex`，可多个） |
| `gateway.channel-state` | 多实例状态 | `mode=local/redis`、`key-prefix`、`timeout-millis` |
| `management.tracing` | 链路追踪 | `enabled`、`sampling.probability`（生产调低） |
| `management.otlp.tracing` | OTLP 导出 | `endpoint`（默认 `http://localhost:4318/v1/traces`） |
| `spring.reactor.context-propagation` | 异步上下文 | 必须为 `auto`，否则日志 traceId 在回调线程为空 |
| `spring.data.redis` | Redis | host / port（⚠️ Boot 3 前缀，不是 `spring.redis`） |

**新增一家 OpenAI 兼容厂商**，只需在 `channels` 下加一段，不写 Java 类：

```yaml
gateway:
  providers:
    channels:
      qwen:
        type: openai
        base-url: ${QWEN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
        api-key: ${QWEN_API_KEY:}
        model: ${QWEN_MODEL:qwen-plus}
        group: default
        weight: 1
```

**协议不同**的厂商才需要新的 `Provider` 实现（见 `AnthropicMessagesProvider`）：
鉴权头、`system` 字段位置、必填字段、流式事件格式，没有一条能用配置表达。

---

## 关键设计

### 1. 一个实现类 × N 份配置，而不是 N 个类

`OpenAiCompatibleProvider` 描述「我能处理什么协议」，不是「我为谁服务」。
DeepSeek、Moonshot、通义、Ollama 的差异只有 `base-url / api-key / model` ——
这些是**数据**，不是代码。

```bash
grep -i "deepseek\|moonshot\|JsonNode" src/main/java/io/github/maixiaowen/gateway/controller/ChatController.java
# 无输出 = 抽象没有泄漏
```

### 2. 只有协议真的不同，才值得新写一个类

Anthropic 的 `x-api-key` + `anthropic-version`、顶层 `system`、
必填 `max_tokens`、`content_block_delta` 事件，
证明 `Provider` 抽象不是「一个接口一个实现」的形式主义。

### 3. 错误分类先于熔断：400 不重试、401 不换渠道

```text
超时 / 5xx        → 换渠道
429               → 同渠道退避重试，不行再换
400 / 422         → 直接透传（换一百个渠道还是 400，只会多花三份钱）
401 / 403         → 502 + 明确 code（能自动恢复的自动恢复，该喊人的喊人）
```

熔断器只给「超时 / 5xx / 429」计数。若把 400 也计数，
一个写错参数的客户端循环 10 次就能把健康渠道熔断 —— **一个坏客户端搞垮全站**。

### 4. 首字节之后，禁止换渠道

流式已吐 100 个字后上游断开，如果偷偷转到备份渠道重新生成：
用户会看到内容**从头再来一遍**，并且上游会对两份输出都计费。

规则：**转移判定只发生在首字节到达之前**，用每次订阅独立的
`AtomicBoolean` 表达「这次尝试吐没吐过字」。

### 5. 计费必须预扣，且预扣必须写在 Lua 里

`GET → 判断 → DECRBY` 三步分开执行必然超卖（大模型请求耗时长，并发窗口极宽）。
Lua 把「检查余额 + 扣减 + 写预扣凭据」合并成一次原子执行。

- 预扣额 = 输入 token + `max_tokens`（最坏情况）；
- 成功后有 `usage` → 按真实值多退少补；
- **已吐字后断流 → 按预扣额度收费**（上游可能已生成远超我们收到的内容）；
- 一个字没吐就失败 → 全额退款；
- 预扣凭据带 TTL：网关崩溃时最坏少收一次，而不是永久冻结用户的钱。

### 6. 默认值的方向，是业务判断

| | 失效后果 | 默认 |
|---|---|---|
| 限流 | 可能超卖几个请求 | `fail-open: true` |
| 计费 | 账目无法对账，钱收不回来 | `fail-closed: false`（拒绝 503） |
| 自动加权路由 | 悄悄改变老客户端行为 | `auto-route: false` |

**能改变行为的新配置，默认值必须是「保持旧行为」。**

### 7. 三层超时各管一段

| 超时 | 值 | 度量什么 |
|---|---|---|
| connect | 3s | TCP 建连 |
| read | 30s | 请求发出 → 收到**响应头** |
| idle | 20s | 流式**两个数据块之间**的间隔 |

流式响应头几十毫秒就回来，`read` 管不到中途停滞 —— 这是必须单独做 idle 超时的原因。

### 8. 「成功但不对」比报错更难查

接入项目一的 Agent 时发现：网关的请求 DTO 把 `tools` 当成未知字段**静默丢弃**，
请求仍然 200、延迟正常、成功率 100%，只是 Agent 再也调不动工具。

监控发现不了这种故障，**契约测试可以**：
断言上游真的收到了 `tools`，并且 `tool_calls` 真的回到了客户端。

---

### 9. 身份必须在限流和计费之前

限流按 `clientId` 计数、计费按 `clientId` 扣钱。如果 `clientId` 来自
客户端自己填的 `X-Client-Id`，那么这两件事同时失效：

- 换一个 ID 就换一个限流桶 —— **限流可绕过**；
- 填别人的 ID 就花别人的余额 —— **计费可嫁祸**。

所以鉴权过滤器排在限流之前（`HIGHEST_PRECEDENCE + 50` vs `+ 100`），
解析出的身份放进 exchange attribute，后续所有人只读这一份。
Key 只存 SHA-256：数据库/配置文件泄漏时，攻击者拿到的是哈希，不是可直接使用的凭据。

### 10. 异步链路上的 traceId：不能信 ThreadLocal

WebFlux 一个请求会换线程。OTel 的 span 作用域是 ThreadLocal，
在 A 线程 attach、到 B 线程的日志里就是空的。

本项目的做法是两条腿走路：

1. `spring.reactor.context-propagation=auto` + 注册 MDC 访问器，
   让 Reactor 在每次回调前恢复 `traceId/spanId`；
2. 业务代码从 **Reactor Context** 取 observation/span，而不是 `Span.current()`，
   并把 traceId 写回 Context（`withTraceMdc`），补上框架捕获快照时 MDC 还是空的那一拍。

`TracingPropagationTest` 同时断言三件事：上游收到同一个 `traceparent`、
span 上有渠道/客户端/模型标签、业务日志的 MDC 里有同一个 traceId。
**只断其中任何一条，都可能被另外两条的失败掩盖。**

---

## 测试

```bash
mvn test
# Tests run: 92, Failures: 0, Errors: 0, Skipped: 0
```

| 测试类 | 验证什么 |
|---|---|
| `ChatCompletionContractTest` | 对外 JSON 契约、字段归一、超时语义 |
| `StreamingContractTest` / `UpstreamStreamingTest` | SSE 分片、`[DONE]`、坏块跳过 |
| `CancelPropagationTest` / `IdleTimeoutTest` | 取消传到上游、块间空闲超时 |
| `ProviderRegistryTest` / `RoutingTest` / `WeightedRoutingTest` | 注册表 fail-fast、路由隔离、平滑加权 |
| `RateLimit*`（4 类） | 令牌桶换算、32 并发不超卖、429、fail-open |
| `ResilienceEndToEndTest` | 5xx 换渠道、400 不换、熔断后走备份 |
| `AnthropicProviderTest` | 协议翻译、流式事件、工具调用双向翻译 |
| `BillingEndToEndTest` | 预扣 / 结算 / 退款 / 断流 / 402 / 幂等 |
| `ObservabilityTest` | 指标、Prometheus、健康检查、requestId |
| `TracingPropagationTest` | traceparent 透传、span 标签、异步日志 MDC |
| `ApiKeyStoreTest` / `ClientAuthEndToEndTest` | Key 解析、401、伪造 `X-Client-Id` 无效、拒绝请求不打上游 |
| `ChannelStateSyncTest` / `RedisChannelStateStoreTest` | 多实例同步、版本号防乱序、Lua 原子写、Pub/Sub |
| `ToolCallingContractTest` | `tools` 透传、`tool_calls` 回传、流式片段 |
| `GatewayChatServiceTest` | 服务层单元行为 |

- 集成测试用 **WireMock**（假上游，动态端口）；
- 限流/计费用 **Testcontainers** 起真实 `redis:7` —— Lua 的原子性用 mock 测不出来；
- 冒烟脚本验证「部署后的真实接线」，20 秒内给出结论。

---

## 目录结构

```text
src/main/java/io/github/maixiaowen/gateway/
├── controller/     ChatController / AdminController / PingController
├── provider/       Provider SPI / ProviderRegistry / OpenAiCompatible / AnthropicMessages
├── auth/           ApiKeyStore / ClientAuthWebFilter / AuthProperties
├── routing/        ChannelRouter / SmoothWeightedRoundRobin / ChannelStateService（Redis 同步）
├── resilience/     ErrorClassifier / UpstreamErrorKind / ChannelCircuitBreakers
├── billing/        BillingService / TokenEstimator / Reservation
├── limiter/        RateLimitWebFilter / RedisTokenBucketLimiter
├── service/        GatewayChatService（路由 → 计费 → 请求 → 结算的编排）
├── observability/  GatewayMetrics / AccessLogWebFilter / 健康检查
├── dto/            请求 / 响应 / 流式分片 / Tool / ToolCall
└── config/         Provider / Resilience / Billing / RateLimit / WebClient / Auth / Tracing

src/main/resources/scripts/   billing_*.lua / channel_state_set.lua
deploy/observability/         Prometheus / Grafana provisioning / 告警规则
scripts/smoke.sh              部署后冒烟
Dockerfile / docker-compose.yml / docker-compose.observability.yml
src/test/java/...             23 个测试类，全部测试见上方 "测试" 一节
```

学习文档：[docs/README.md](docs/README.md)（G1~G4 的实现讲解与踩坑记录）

---

## 安全说明

- API Key **只从环境变量读取**，YAML 中一律 `${ENV_VAR:}` 空默认值；
  `.env` 已加入 `.gitignore`，仓库只保留 `.env.example` 模板。
- `/admin/**` 默认 **fail-closed**：不配置 `gateway.admin.token` 时一律 403。
- `X-Request-Id` 做长度与字符集校验，防止日志注入；
  不合法时由网关生成并回传。
- 容器以非 root 用户运行。
- **客户端鉴权（G10）**：`/v1/**` 只认 `Authorization: Bearer <key>` 或 `X-Api-Key`；
  配置里只存 `clientId:sha256(key)`，明文 key 不落盘。
  - 同时传两个不一致的凭据 → 401（不猜哪个是真的）；
  - 伪造 `X-Client-Id` 不会改变身份（鉴权通过后以 key 对应的 clientId 为准）；
  - 鉴权失败的请求**不会到达上游**（测试里用 WireMock 的调用次数证明）。
- ⚠️ compose 里的 `demo` key 是**公开的开发凭据**，只为了一键启动能冒烟。
  任何真实部署都必须用 `GATEWAY_AUTH_KEYS` 覆盖，并只在内网暴露网关端口。
- ⚠️ 当前只做了「API Key 身份」，没有 OAuth2/OIDC、租户配额策略和密钥轮换。
  这些属于接入方的身份体系；网关的边界是「证明你是谁」，不是「管理你的组织」。

---

## 未实现 / 已知限制

| 项 | 状态 | 原因 |
|---|---|---|
| OAuth2 / OIDC / 租户配额策略 | ❌ | 当前是 API Key 身份；组织、角色、配额属于接入方身份体系 |
| 密钥轮换 / 多 key 灰度 | ❌ | 支持多把 key，但没有「新 key 生效、旧 key 宽限期」的运营流程 |
| 观测栈高可用 | ❌ | compose 里是单机演示：Grafana 默认密码、Jaeger 内存存储 |
| 会话 / 上下文存储 | ❌ | **刻意不做**：网关无状态，多轮上下文由调用方管理；网关存对话会引入隐私和清理责任 |
| 精确 token 计数 | ❌ | 非流式优先用上游 `usage`，缺失时才按字符估算并打 WARN；精确计数需要各厂商 tokenizer |
| 成本预算 / 熔断式预算 | ❌ | 已有余额预扣，但没有「日预算、部门预算、超支告警」这类组织级成本控制 |

> 主动写清限制，而不是假装「全做了」——
> 面试官追问时，**说得出为什么现在不做**，比说「都做完了」更有说服力。

---

## 关联项目

- **项目一 · 订单 Agent 助手**：`order-agent-assistant`
  Agent 通过 `DEEPSEEK_BASE_URL=http://localhost:8081/v1` 接入本网关，
  工具调用经网关透传到 DeepSeek，再由网关完成计量与限流。

---

## License

[MIT](LICENSE) © 2026 任多志 (maixiaowen111)

可自由用于学习、二次开发和商业项目；如果你基于它做二次开发，欢迎在 Issue 里说一声，
我也想知道它被用到了哪里。
