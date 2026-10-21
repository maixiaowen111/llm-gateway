--[[
  G7 全额退款：请求在上游产生任何输出之前就失败了，这笔预扣不应收费。

  KEYS[1] = 余额 key
  KEYS[2] = 预扣凭据 key

  返回：
    成功     {"status":"ok","refunded":N}
    凭据已无 {"status":"missing"}   ← 说明已经结算/退款过，调用方什么也不做

  ★ 断流为什么不走这里 ★
  客户端中途断开时，上游已经生成了内容、已经产生了成本 —— 即使我们只收到半句。
  那种情况走"按预扣额度结算"（billing_settle），而不是退款。
  **退款只用于"上游一个字都没给"的情况。**
]]

local balanceKey = KEYS[1]
local reservationKey = KEYS[2]

if redis.call('EXISTS', reservationKey) == 0 then
  return cjson.encode({ status = 'missing' })
end

local reserved = tonumber(redis.call('HGET', reservationKey, 'amount'))
if reserved == nil then
  redis.call('DEL', reservationKey)
  return cjson.encode({ status = 'missing' })
end

redis.call('INCRBY', balanceKey, reserved)
redis.call('DEL', reservationKey)

return cjson.encode({ status = 'ok', refunded = reserved })
