--[[============================================================================
  令牌桶（Token Bucket）—— G4 的核心。整个项目的技术含金量最高的一段之一。

  ★ 为什么这段代码必须整段跑在 Redis 里 ★

  限流的四步是：「读令牌数 → 判断够不够 → 扣减 → 写回」。
  这四步在并发下**必须是一个原子操作**。

  如果分成四次 Redis 调用（哪怕每次都是原子的），会发生什么：
      时刻 T1   请求 A 读到「还剩 1 个令牌」
      时刻 T2   请求 B 读到「还剩 1 个令牌」   ← 同一个 1，被读到了两次
      时刻 T3   A 判断 1 >= 1，通过，写回 0
      时刻 T4   B 判断 1 >= 1，通过，写回 0
  结果：桶里只有 1 个令牌，却放行了 2 个请求 = **超卖**。

  注意这里最容易搞混的一点（面试高频）：
      「Redis 是单线程的，所以命令是原子的」—— 这句话没错，
      但它保的是**单条命令**。跨命令的组合从来都不是原子的。
      **这才是要用 Lua 的真正原因**，不是"因为 Lua 快"。

  Lua 脚本在 Redis 里是"一次执行到底、中间不插入任何其他客户端的命令"，
  所以上面那四步变成了一个不可分割的动作。放行数因此**严格**等于桶容量。

  --------------------------------------------------------------------------
  参数
    KEYS[1]  桶的 key（形如 gw:rl:<客户端标识>）
    ARGV[1]  capacity         桶容量（最多能攒多少令牌 = 允许的突发量）
    ARGV[2]  refillPerSecond  每秒回填多少令牌（= 长期平均吞吐）
    ARGV[3]  requested        本次请求消耗几个令牌（目前恒为 1）

  返回
    cjson 编码的 { allowed, remaining, retryAfterMs }
    ★ 返回 JSON 而不是 Lua 数组，理由见 Java 侧 RedisTokenBucketLimiter 的注释。
  ============================================================================]]

local capacity    = tonumber(ARGV[1])
local refillPerMs = tonumber(ARGV[2]) / 1000.0
local requested   = tonumber(ARGV[3])

--[[ ★ 时间从 Redis 取，不由应用传进来 ★

     多实例部署时，每台机器的系统时钟都有偏差（NTP 同步也不是零误差，
     跨机房更是几十毫秒起）。如果让应用把 now 传进来，就等于
     「每个实例拿自己的尺子去量同一个桶」—— 时钟快的那台会算出更多的令牌，
     表现是"某台机器限流总是松一点"，而且极难查（换个实例就好了）。

     限流器的时钟只能有一个来源，就是 Redis 自己。
     （分布式唯一 ID、分布式锁的时间判断，都是同一个道理。）]]
local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- ① 读：令牌数 + 上次结算时间
--    HMGET 对不存在的 key 返回 {false, false}，tonumber(false) 得到 nil —— 正好用来判"桶不存在"
local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil then
  -- 第一次见到这个客户端：从**满桶**开始，不是空桶。
  -- 空桶意味着"新客户的第一句话必然被拒"，那叫冷启动惩罚，不合理。
  tokens = capacity
  ts     = now
end

-- ② 补：**惰性回填**。
--    注意这里没有后台定时器在往桶里加令牌 —— 是"谁来了，顺便算一下
--    从上次结算到现在该补多少"。好处：没有访问的桶不消耗任何 CPU，
--    也不需要为每个客户端维护一个定时任务（十万个客户端就是十万个定时器）。
local elapsed = now - ts
if elapsed < 0 then
  elapsed = 0   -- 防时钟回拨：Redis 主从切换、NTP 校正都可能让 now < ts。
                -- 不挡这一下会算出负数令牌，把桶变成"欠债状态"。
end
tokens = math.min(capacity, tokens + elapsed * refillPerMs)

-- ③ 判断 + ④ 扣减
local allowed      = 0
local retryAfterMs = 0

if tokens >= requested then
  allowed = 1
  tokens  = tokens - requested
else
  if refillPerMs > 0 then
    -- 还要等多久才够？公式：缺的令牌数 ÷ 每秒补的令牌数。
    -- 这个值会变成 HTTP 的 Retry-After 头 —— 它必须**准确**，
    -- 因为客户端的重试库会照着它睡。给个拍脑袋的 1 秒，
    -- 会让调用方在还没攒够令牌时又来一次，然后又被拒一次，形成无效重试风暴。
    retryAfterMs = math.ceil((requested - tokens) / refillPerMs)
  else
    retryAfterMs = -1   -- refillPerSecond = 0 的桶：一次性总配额，等多久都没用
  end
end

-- ⑤ 写回：**放行与否都写**。
--    被拒时如果故意不写，下次请求会拿着旧的 ts 重算一遍 —— 结果虽然一样，
--    但桶的状态就不再代表"此刻的真实水位"了，排查问题时 hgetall 看不出任何东西。
--    **可观测性不是加个日志就有的，是让状态本身可读。**
redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)

-- 过期时间：桶空闲到「攒满一桶所需时间 × 2」就自动删除。
-- ★ 少了这一步，每一个访问过的客户端都会在 Redis 里留下一个**永不消失**的 key。
--   一个"内存只增不减"的限流器，迟早会把 Redis 打爆 ——
--   而它挂掉的方式是"慢慢变慢"，不是"突然报错"，所以等你发现时已经晚了。
--   （限流器自己把 Redis 撑爆，是这类系统最经典的死法。）
local ttl
if refillPerMs > 0 then
  ttl = math.ceil(capacity / refillPerMs) * 2   -- 单位已经是毫秒
else
  ttl = 3600000                                  -- 不回填的桶：给 1 小时兜底
end
if ttl < 60000 then
  ttl = 60000   -- 下限 1 分钟：太短的 TTL 会让"桶被删掉"变成事实上的额度重置
end
redis.call('PEXPIRE', KEYS[1], ttl)

-- cjson 是 Redis 内置的 JSON 编解码库，不需要额外装任何东西。
-- 返回 JSON 的取舍见 Java 侧注释（一句话：让这个返回值在 redis-cli 里肉眼可读）。
return cjson.encode({
  allowed      = allowed,
  remaining    = math.floor(tokens),
  retryAfterMs = retryAfterMs
})
