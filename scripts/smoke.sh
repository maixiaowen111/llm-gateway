#!/usr/bin/env bash
# =============================================================================
# 冒烟测试：部署后 30 秒内回答一个问题 ——「这个环境现在是活的吗？」
#
# ★ 它和单元测试的分工 ★
# 单元测试验的是"代码在受控条件下对不对"；冒烟验的是
# "这一套真实部署（镜像、配置、Redis、密钥注入）现在能不能用"。
# 两者都不能少：一个通过了全部单测但从没被真正启动过的服务，
# 和一个没写过单测的服务，在发布那一刻的风险是一样的。
#
# 用法：
#   BASE_URL=http://localhost:8081 ADMIN_TOKEN=dev-admin-token ./scripts/smoke.sh
#   或： GATEWAY_ADMIN_TOKEN=... ./scripts/smoke.sh
#
# ★ 不需要真实大模型 Key ★
# 关键的 402 检查在"调用上游之前"就被计费拦下，所以没有密钥也能验证
# 计费、路由（不可达时）、管理鉴权这些最核心的接线。
# 如果提供了 DEEPSEEK_API_KEY，则额外跑一次真实调用。
# =============================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
ADMIN_TOKEN="${ADMIN_TOKEN:-${GATEWAY_ADMIN_TOKEN:-dev-admin-token}}"
CLIENT_ID="smoke-$(date +%s)"
TMP_BODY="$(mktemp)"
trap 'rm -f "$TMP_BODY"' EXIT

PASS=0
FAIL=0

# check <名称> <期望状态码> <实际状态码> [额外断言命令]
check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "  ✅ $name (HTTP $actual)"
    PASS=$((PASS + 1))
  else
    echo "  ❌ $name：期望 HTTP $expected，实际 HTTP $actual"
    echo "     响应: $(head -c 300 "$TMP_BODY")"
    FAIL=$((FAIL + 1))
  fi
}

status_of() {
  curl -s -o "$TMP_BODY" -w '%{http_code}' "$@"
}

echo "== 1/5 健康检查 =="
code=$(status_of "$BASE_URL/actuator/health")
check "actuator/health 可访问" "200" "$code"
if ! grep -q '"status":"UP"' "$TMP_BODY"; then
  echo "  ❌ health 不是 UP：$(head -c 300 "$TMP_BODY")"
  FAIL=$((FAIL + 1))
fi

echo "== 2/5 管理接口 fail-closed =="
code=$(status_of "$BASE_URL/admin/channels")
check "不带 token 访问 /admin/channels 应 403" "403" "$code"

echo "== 3/5 管理接口可用（带 token）=="
code=$(status_of -H "X-Admin-Token: $ADMIN_TOKEN" "$BASE_URL/admin/channels")
check "带 token 访问 /admin/channels" "200" "$code"
if ! grep -q '"channels"' "$TMP_BODY"; then
  echo "  ❌ 响应里没有 channels 字段"
  FAIL=$((FAIL + 1))
fi

echo "== 4/5 计费拦截（不需要真实 Key）=="
# 先把这个客户端的余额压到 1，再用巨大 max_tokens 请求：
# 预扣金额远超余额 → 必须在调用上游之前返回 402。
curl -s -o /dev/null -X POST "$BASE_URL/admin/clients/$CLIENT_ID/credits" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":1}'

code=$(status_of -X POST "$BASE_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "X-Client-Id: $CLIENT_ID" \
  -d '{"model":"deepseek-chat","max_tokens":1000000,
       "messages":[{"role":"user","content":"smoke"}]}')
check "余额不足应 402（未打上游）" "402" "$code"
if ! grep -q '"insufficient_quota"' "$TMP_BODY"; then
  echo "  ❌ 错误码不是 insufficient_quota"
  FAIL=$((FAIL + 1))
fi

echo "== 5/5 真实调用（可选）=="
if [[ -n "${DEEPSEEK_API_KEY:-}" ]]; then
  code=$(status_of -X POST "$BASE_URL/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -H "X-Client-Id: ${CLIENT_ID}-real" \
    -d '{"model":"deepseek-chat","max_tokens":32,
         "messages":[{"role":"user","content":"只回复两个字：在线"}]}')
  check "真实 DeepSeek 调用" "200" "$code"
else
  echo "  ⏭  未设置 DEEPSEEK_API_KEY，跳过真实调用"
fi

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAIL 项"
echo "======================================"
[[ "$FAIL" -eq 0 ]]
