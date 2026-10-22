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
# ★ G10 之后，冒烟必须带客户端身份 ★
#   默认用 compose 里的开发 key（demo）。如果你的环境换了 GATEWAY_AUTH_KEYS，
#   必须同时传 API_KEY 和 CLIENT_ID：
#     API_KEY=sk-xxx CLIENT_ID=my-client ./scripts/smoke.sh
#   对没有开鉴权的本地环境：EXPECT_AUTH=false ./scripts/smoke.sh
#
# ★ 不需要真实大模型 Key ★
# 关键的 402 检查在"调用上游之前"就被计费拦下，所以没有密钥也能验证
# 鉴权、计费、路由、管理鉴权这些最核心的接线。
# 如果提供了 DEEPSEEK_API_KEY，则额外跑一次真实调用。
# =============================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
ADMIN_TOKEN="${ADMIN_TOKEN:-${GATEWAY_ADMIN_TOKEN:-dev-admin-token}}"
CLIENT_ID="${CLIENT_ID:-demo}"
API_KEY="${API_KEY:-sk-gw-dev-demo-key-0123456789}"
EXPECT_AUTH="${EXPECT_AUTH:-true}"
TMP_BODY="$(mktemp)"
trap 'rm -f "$TMP_BODY"' EXIT

# 所有 /v1 请求共用：鉴权头 + 兼容旧模式的 X-Client-Id。
# ★ 开了鉴权后，X-Client-Id 会被忽略（身份的真相是 key），
#   但保留它可以让同一份脚本在未开鉴权的本地环境也能跑。★
AUTH_HEADERS=(-H "Authorization: Bearer $API_KEY" -H "X-Client-Id: $CLIENT_ID")
ADMIN_HEADER=(-H "X-Admin-Token: $ADMIN_TOKEN")

PASS=0
FAIL=0
SKIP=0
BILLING_ENABLED="unknown"

# check <名称> <期望状态码> <实际状态码>
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

skip() {
  echo "  ⏭  $1"
  SKIP=$((SKIP + 1))
}

status_of() {
  curl -s -o "$TMP_BODY" -w '%{http_code}' "$@"
}

echo "== 1/6 健康检查 =="
code=$(status_of "$BASE_URL/actuator/health")
check "actuator/health 可访问" "200" "$code"
if ! grep -q '"status":"UP"' "$TMP_BODY"; then
  echo "  ❌ health 不是 UP：$(head -c 300 "$TMP_BODY")"
  FAIL=$((FAIL + 1))
fi

echo "== 2/6 客户端鉴权（G10）=="
if [[ "$EXPECT_AUTH" == "true" ]]; then
  code=$(status_of -X POST "$BASE_URL/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"no key"}]}')
  check "不带 API Key 应 401" "401" "$code"

  code=$(status_of -X POST "$BASE_URL/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer definitely-not-a-valid-key" \
    -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"bad key"}]}')
  check "无效 API Key 应 401" "401" "$code"
else
  skip "EXPECT_AUTH=false，跳过鉴权检查"
fi

echo "== 3/6 管理接口 fail-closed =="
code=$(status_of "$BASE_URL/admin/channels")
check "不带 token 访问 /admin/channels 应 403" "403" "$code"

echo "== 4/6 管理接口可用（带 token）=="
code=$(status_of "${ADMIN_HEADER[@]}" "$BASE_URL/admin/channels")
check "带 token 访问 /admin/channels" "200" "$code"
if ! grep -q '"channels"' "$TMP_BODY"; then
  echo "  ❌ 响应里没有 channels 字段"
  FAIL=$((FAIL + 1))
fi

echo "== 5/6 计费拦截（不需要真实 Key）=="
# 把余额精确调到 1，再用巨大 max_tokens 请求：
# 预扣金额远超余额 → 必须在调用上游之前返回 402。
# ★ 不能假设"新客户端余额是 0"：客户端是固定的 demo，上一次冒烟可能留了余额。★
code=$(status_of "${ADMIN_HEADER[@]}" "$BASE_URL/admin/usage/$CLIENT_ID")
if [[ "$code" != "200" ]]; then
  echo "  ❌ 读取用量失败（HTTP $code）：$(head -c 300 "$TMP_BODY")"
  FAIL=$((FAIL + 1))
elif grep -q '"billingEnabled":false' "$TMP_BODY"; then
  BILLING_ENABLED="false"
  skip "计费未启用（billingEnabled=false），跳过 402 检查"
else
  BILLING_ENABLED="true"
  balance=$(grep -o '"balance":[0-9-]*' "$TMP_BODY" | head -1 | cut -d: -f2)
  balance="${balance:-0}"
  delta=$((1 - balance))
  if [[ "$delta" -ne 0 ]]; then
    code=$(status_of -X POST "$BASE_URL/admin/clients/$CLIENT_ID/credits" \
      "${ADMIN_HEADER[@]}" -H "Content-Type: application/json" \
      -d "{\"amount\":$delta}")
    if [[ "$code" != "200" ]]; then
      echo "  ❌ 调整余额失败（HTTP $code）：$(head -c 300 "$TMP_BODY")"
      FAIL=$((FAIL + 1))
    fi
  fi

  code=$(status_of -X POST "$BASE_URL/v1/chat/completions" \
    -H "Content-Type: application/json" \
    "${AUTH_HEADERS[@]}" \
    -d '{"model":"deepseek-chat","max_tokens":1000000,
         "messages":[{"role":"user","content":"smoke"}]}')
  check "余额不足应 402（未打上游）" "402" "$code"
  if ! grep -q '"insufficient_quota"' "$TMP_BODY"; then
    echo "  ❌ 错误码不是 insufficient_quota"
    FAIL=$((FAIL + 1))
  fi
fi

echo "== 6/6 真实调用（可选）=="
if [[ -n "${DEEPSEEK_API_KEY:-}" ]]; then
  # 真实调用要花钱，先确保 demo 有足够额度（内部 credit，不是真钱）
  if [[ "$BILLING_ENABLED" != "false" ]]; then
    curl -s -o /dev/null -X POST "$BASE_URL/admin/clients/$CLIENT_ID/credits" \
      "${ADMIN_HEADER[@]}" -H "Content-Type: application/json" -d '{"amount":100}'
  fi
  code=$(status_of -X POST "$BASE_URL/v1/chat/completions" \
    -H "Content-Type: application/json" \
    "${AUTH_HEADERS[@]}" \
    -d '{"model":"deepseek-chat","max_tokens":32,
         "messages":[{"role":"user","content":"只回复两个字：在线"}]}')
  check "真实 DeepSeek 调用" "200" "$code"
else
  skip "未设置 DEEPSEEK_API_KEY，跳过真实调用"
fi

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAIL 项，跳过 $SKIP 项"
echo "======================================"
[[ "$FAIL" -eq 0 ]]
