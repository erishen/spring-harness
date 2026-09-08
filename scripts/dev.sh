#!/usr/bin/env bash
# ============================================================
# spring-harness 一键开发编排：后端(8080) + 前端(5174)
# ============================================================
# 设计要点（源自 resolve-studio / spring-harness 踩坑复盘）：
#   1. 探活 curl 一律 --noproxy '*'（本机代理会把未监听端口回 502 → 假就绪）
#   2. 后端未就绪绝不启动前端（fail fast，禁止假成功）
#   3. set -m 开 job control：后台 job 独立进程组，kill -TERM -$PID 带走整棵树
#   4. 信号 trap 只 exit，清理统一交给 EXIT trap（避免清理执行两次）
#   5. 巡检循环不用裸 wait（前端崩了脚本会一直挂着）：前端 kill -0 判活，
#      后端用 HTTP 探针判活（管道 job 的 $! 是 tee，不是服务进程）
#   6. 任一服务退出 → 打印是谁 + 各自日志尾部，exit 1；Ctrl+C → exit 0
# ============================================================
set -u

PORT="${PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5174}"
MVN="${MVN:-mvn}"
BE_LOG="${BE_LOG:-app.log}"
FE_LOG="${FE_LOG:-/tmp/spring-harness-vite.log}"

FE_PID=""

cleanup() {
  # 仅由 EXIT trap 调用
  if [ -n "$FE_PID" ]; then
    kill -TERM -"$FE_PID" 2>/dev/null   # set -m 下 $! 即 PGID，带走 vite 整棵树
  fi
  pkill -f "spring-boot:run" 2>/dev/null
  lsof -ti:"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null
  lsof -ti:"$FRONTEND_PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null
}
trap cleanup EXIT
trap 'exit 0' INT
trap 'exit 1' TERM HUP

probe() { # $1=url → HTTP 状态码（000/空 = 不可达）
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -m 2 "$1" 2>/dev/null
}

wait_ready() { # $1=url $2=最长秒数 $3=存活pid(可空)
  local url="$1" max="$2" pid="${3:-}" i code
  i=0
  while [ "$i" -lt "$max" ]; do
    code=$(probe "$url")
    if [ -n "$code" ] && [ "$code" != "000" ]; then
      return 0
    fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      return 1   # 进程已死，别硬等超时
    fi
    i=$((i + 1))
    sleep 1
  done
  return 1
}

# ---------- 预清理（重复 make dev 时避免端口冲突） ----------
pkill -f "spring-boot:run" 2>/dev/null
lsof -ti:"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null
lsof -ti:"$FRONTEND_PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null
sleep 1
mkdir -p data/mcp-workspace logs/tasks

echo ""
echo "  🚀 后端 :$PORT ＋ 🌐 前端 :$FRONTEND_PORT"
echo "  前端页面: http://localhost:$FRONTEND_PORT  （Ctrl+C 停止）"
echo ""

set -m   # 必须在起后台 job 之前

# 后端探活地址：/actuator/health 返回 200 且不产生 NoResourceFoundException 噪音日志
# （打 / 会每 2 秒在后端日志刷一条 404 WARN）
BE_PROBE_URL="http://localhost:$PORT/actuator/health"

# ---------- 后端 ----------
"$MVN" spring-boot:run 2>&1 | tee "$BE_LOG" &
BE_WRAP=$!   # 注意：这是管道末端 tee 的 pid，仅作进程存活性参考；判活以下方 HTTP 探针为准

echo "  等待后端就绪（最多 60 秒）..."
if ! wait_ready "$BE_PROBE_URL" 60 "$BE_WRAP"; then
  if kill -0 "$BE_WRAP" 2>/dev/null; then
    echo "  ❌ 后端 60 秒未就绪，日志 ${BE_LOG}（最后 20 行）："
  else
    echo "  ❌ 后端启动失败，日志 ${BE_LOG}（最后 20 行）："
  fi
  tail -20 "$BE_LOG"
  exit 1
fi
echo "  ✅ 后端已就绪 (: $PORT)"

# ---------- 前端 ----------
# 绕过 npm 包装直接起 vite（npm run dev 偶发挂起零输出；package.json 的 dev 本来就是裸 vite）
( cd frontend && exec node node_modules/vite/bin/vite.js ) > "$FE_LOG" 2>&1 &
FE_PID=$!

echo "  等待前端就绪（最多 15 秒）..."
if ! wait_ready "http://localhost:$FRONTEND_PORT/" 15 "$FE_PID"; then
  echo "  ❌ 前端未就绪，日志 ${FE_LOG}（最后 20 行）："
  tail -20 "$FE_LOG"
  exit 1
fi
echo "  ✅ 前端已就绪 (http://localhost:$FRONTEND_PORT)"

# ---------- 巡检：任一退出即停止全部，并报出是谁 ----------
echo ""
echo "  👀 巡检中：任一服务退出即停止全部"
echo "     后端日志: $BE_LOG   前端日志: $FE_LOG"
echo ""
BE_MISS=0
while :; do
  if ! kill -0 "$FE_PID" 2>/dev/null; then
    echo "  ❌ 前端已退出，日志 ${FE_LOG}（最后 30 行）："
    tail -30 "$FE_LOG"
    exit 1
  fi
  code=$(probe "$BE_PROBE_URL")
  if [ -z "$code" ] || [ "$code" = "000" ]; then
    BE_MISS=$((BE_MISS + 1))
    if [ "$BE_MISS" -ge 2 ]; then   # 容忍 devtools 重启的瞬时抖动
      echo "  ❌ 后端已退出（连续 2 次探活失败），日志 ${BE_LOG}（最后 30 行）："
      tail -30 "$BE_LOG"
      exit 1
    fi
  else
    BE_MISS=0
  fi
  sleep 2
done
