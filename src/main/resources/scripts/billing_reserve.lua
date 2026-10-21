--[[
  G7 预扣：原子地"检查余额 + 扣减 + 写入预扣凭据"。

  KEYS[1] = 余额 key         gw:billing:balance:{clientId}
  KEYS[2] = 预扣凭据 key     gw:billing:reservation:{reservationId}
  ARGV[1] = 要冻结的 credits
  ARGV[2] = 新客户初始额度
  ARGV[3] = 预扣凭据 TTL（秒）

  返回 cjson：
    成功   {"status":"ok","balance":123,"reserved":456}
    不足   {"status":"insufficient","balance":10,"required":456}

  ★ 为什么必须是原子操作 ★
  "GET 余额 → 比较 → DECRBY" 三步分开执行时，两个并发请求可能同时读到
  "余额 100"，各自判断"够扣 80"，然后各扣一次 —— 余额变成 -60。
  这不是"偶发的并发问题"：大模型请求动辄几十秒，并发窗口极宽，
  高峰期几乎必然踩到。Lua 脚本在 Redis 里单线程执行到底，彻底消除这个窗口。

  ★ 为什么新客户用 EXISTS 判断而不是先 SETNX ★
  余额 key 一旦存在，"初始额度"就永远不该再补发 ——
  否则客户把额度花光后删掉 key（或过期）就能无限续杯。
  这里只在 key 不存在时初始化一次，之后一切都以真实余额为准。
]]

local balanceKey = KEYS[1]
local reservationKey = KEYS[2]
local amount = tonumber(ARGV[1])
local initialCredits = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])

if redis.call('EXISTS', balanceKey) == 0 then
  redis.call('SET', balanceKey, initialCredits)
end

local balance = tonumber(redis.call('GET', balanceKey))

if balance < amount then
  -- ★ 关键：不足时【不扣、不写凭据】★
  -- 如果先扣再发现不足，就会出现"负数余额 + 一笔没人结算的凭据"，
  -- 而且下一次请求会继续扣，余额越滚越负。
  return cjson.encode({ status = 'insufficient', balance = balance, required = amount })
end

redis.call('DECRBY', balanceKey, amount)
redis.call('HSET', reservationKey, 'amount', amount, 'status', 'RESERVED')
-- TTL 是"防泄漏"：网关崩溃时这笔预扣会自动释放，代价是可能少收一次钱。
-- 没有 TTL 的话，每次崩溃都会永久冻结一笔额度，直到把客户余额冻结干净。
redis.call('EXPIRE', reservationKey, ttlSeconds)

return cjson.encode({ status = 'ok', balance = balance - amount, reserved = amount })
