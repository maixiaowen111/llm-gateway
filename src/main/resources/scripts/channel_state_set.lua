-- =============================================================================
-- G11：原子地修改一条渠道的运行期开关，并广播给所有实例。
--
-- ★ 为什么必须是一个脚本 ★
--   "读旧值 → 写新值 → 版本号 +1 → 发布通知" 如果拆成四次往返：
--     1) 两个实例同时改同一条渠道时，后写的可能先读旧值；
--     2) 中间任意一步失败，会留下"值已改、没人收到通知"的状态。
--   Redis 单线程执行 Lua，整个块是一个原子动作 —— 这是它唯一能给我们的保证，
--   而这里恰好只需要这一条。
--
-- KEYS[1] = hash key（如 gw:channel-state:state）
-- ARGV[1] = channel 名
-- ARGV[2] = "true" / "false"
-- ARGV[3] = 发布频道
--
-- 返回 {previous, version}
-- =============================================================================

local previous = redis.call('HGET', KEYS[1], ARGV[1]) or ''
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
local version = redis.call('HINCRBY', KEYS[1], '__version__', 1)

-- 用制表符分隔而不是 JSON：这里只有三个字段，解析越简单越不容易出错。
-- 制表符不会出现在渠道名里（渠道名来自 YAML key，又经过启动校验）。
redis.call('PUBLISH', ARGV[3], ARGV[1] .. '\t' .. ARGV[2] .. '\t' .. version)

return {previous, tostring(version)}
