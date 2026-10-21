--[[
  G7 结算：按实际情况"多退少补"，并记录用量。

  KEYS[1] = 余额 key          gw:billing:balance:{clientId}
  KEYS[2] = 预扣凭据 key      gw:billing:reservation:{reservationId}
  KEYS[3] = 用量统计 key      gw:billing:usage:{clientId}
  ARGV[1] = 本次应扣的 credits（按 usage 算出来的真实费用）
  ARGV[2] = prompt tokens
  ARGV[3] = completion tokens
  ARGV[4] = 备注（usage / estimated / reservation）—— 只进日志，不进 Redis

  返回 cjson：
    成功     {"status":"ok","charged":N,"refunded":R,"extra":E}
    凭据已无 {"status":"missing"}

  ★ 多退少补的两半 ★
  - 预扣 100、实际 60  → delta = +40 → 退回 40（绝大多数情况）；
  - 预扣 100、实际 130 → delta = -30 → 再扣 30（客户端没写 max_tokens、
    或者上游越过了上限）。这时余额可能变负 —— **这是对的**：
    钱已经花了，账必须记上；下一次请求会因为余额不足被拒（402）。
    把余额截断到 0 等于"花掉的钱不用还"，那才是真的资损。

  ★ 为什么"凭据不存在"要原样返回、而不是当成 0 ★
  结算和退款可能因为重试/超时被调用两次。凭据是"只结算一次"的锁：
  第一次成功后 DEL，第二次进来拿到 missing。
  **把 missing 当成"成功结算了 0"会让调用方误以为账已平**，
  而实际上它可能已经被退款了（断流后用户重试）。
  调用方拿到 missing 必须"不做任何事"，并记日志。

  ★ 时间从哪来？★
  本脚本不取时间。用量统计如果需要按天分桶，key 里的日期必须由 Java 侧传入
  （用 Redis 的 TIME 也行，但要保证全集群统一）。这里不做分桶，
  因为"按天/按月对账"是账务系统的职责，网关只记"累计"。
]]

local balanceKey = KEYS[1]
local reservationKey = KEYS[2]
local usageKey = KEYS[3]
local charged = tonumber(ARGV[1])
local promptTokens = tonumber(ARGV[2])
local completionTokens = tonumber(ARGV[3])

if redis.call('EXISTS', reservationKey) == 0 then
  return cjson.encode({ status = 'missing' })
end

local reserved = tonumber(redis.call('HGET', reservationKey, 'amount'))
if reserved == nil then
  -- 凭据在但字段没了 = 数据损坏。删掉它，避免它永远占着"未结算"的状态。
  redis.call('DEL', reservationKey)
  return cjson.encode({ status = 'missing' })
end

if charged < 0 then
  charged = 0
end

local delta = reserved - charged
if delta > 0 then
  redis.call('INCRBY', balanceKey, delta)
elseif delta < 0 then
  redis.call('DECRBY', balanceKey, -delta)
end

redis.call('DEL', reservationKey)

-- 用量统计：这是 /admin/usage 的数据来源，也是和上游账单对账的原始凭据。
-- requests 在"结算"时 +1，而不是在"收到请求"时 +1 ——
-- 预扣失败（402）的请求根本没花上游一分钱，不该出现在用量里。
redis.call('HINCRBY', usageKey, 'requests', 1)
redis.call('HINCRBY', usageKey, 'promptTokens', promptTokens)
redis.call('HINCRBY', usageKey, 'completionTokens', completionTokens)
redis.call('HINCRBY', usageKey, 'creditsSpent', charged)

return cjson.encode({
  status = 'ok',
  charged = charged,
  refunded = delta > 0 and delta or 0,
  extra = delta < 0 and -delta or 0
})
