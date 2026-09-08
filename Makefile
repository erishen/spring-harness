# ============================================
# Spring Harness - AI Agent 开发框架 Makefile
# ============================================
# 自动加载 .env 中的环境变量（文件不存在时忽略）
-include .env
export

# 可覆盖的变量
APP_NAME  ?= spring-harness
MVN       ?= mvn
# .env 缺 SERVER_PORT 时回退 8080（?=: 惰性展开，SERVER_PORT 为空则 PORT 也为空）
PORT      ?= $(if $(SERVER_PORT),$(SERVER_PORT),8080)
FRONTEND_PORT ?= 5174

.PHONY: help dev run run-bg stop compile clean test package restart health \
        frontend-install frontend-dev frontend-build frontend-stop

help: ## 显示帮助（默认目标）
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""
	@echo "  配置文件：.env（已 gitignore），首次使用请 cp .env.example .env"

dev: compile frontend-install ## 开发模式：一键起后端(8080)＋前端(5174)，任一崩溃立即报出是谁，Ctrl+C 一起退出
	@PORT=$(PORT) FRONTEND_PORT=$(FRONTEND_PORT) MVN=$(MVN) bash scripts/dev.sh

run: ## 仅启动后端（前台，自动加载 .env）
	@mkdir -p data/mcp-workspace logs/tasks
	$(MVN) spring-boot:run

run-bg: ## 仅后台启动后端，日志写入 app.log
	@mkdir -p data/mcp-workspace logs/tasks
	nohup $(MVN) spring-boot:run > app.log 2>&1 &
	@echo "后端已后台启动，日志：app.log，端口：${PORT}"
	@echo "查看日志：tail -f app.log"
	@echo "停止：make stop"

stop: ## 停止后端和前端
	@pkill -f "spring-boot:run" 2>/dev/null && echo "已停止 spring-boot:run" || true
	@pkill -f "$(APP_NAME)" 2>/dev/null && echo "已停止 $(APP_NAME) 进程" || true
	@lsof -ti:${PORT} 2>/dev/null | xargs -r kill -9 2>/dev/null && echo "已释放后端端口 ${PORT}" || true
	@lsof -ti:${FRONTEND_PORT} 2>/dev/null | xargs -r kill -9 2>/dev/null && echo "已释放前端端口 ${FRONTEND_PORT}" || true

compile: ## 编译后端项目
	$(MVN) compile

package: ## 打包后端（跳过测试）
	$(MVN) clean package -DskipTests

test: ## 运行后端测试
	$(MVN) test

clean: ## 清理构建产物
	$(MVN) clean

restart: stop run ## 停止并重新启动后端

health: ## 健康检查（调用后端 /chat 测试）
	@curl -s -m 5 -w "\nHTTP_CODE=%{http_code}\n" \
		"http://localhost:${PORT}/chat?message=hi" || \
		echo "后端未启动或端口 ${PORT} 不可达"

# ---------- 前端 ----------

frontend-install: ## 安装前端依赖
	@if [ ! -d "frontend/node_modules" ]; then \
		echo "安装前端依赖..."; \
		cd frontend && npm install; \
	else \
		echo "前端依赖已存在，跳过安装（如需更新请手动 cd frontend && npm install）"; \
	fi

frontend-dev: frontend-install ## 仅启动前端开发服务器
	@echo "前端页面: http://localhost:${FRONTEND_PORT}"
	cd frontend && npm run dev

frontend-build: frontend-install ## 构建前端（输出到 frontend/dist）
	cd frontend && npm run build

frontend-stop: ## 仅停止前端
	@lsof -ti:${FRONTEND_PORT} 2>/dev/null | xargs -r kill -9 2>/dev/null && echo "已停止前端，释放端口 ${FRONTEND_PORT}" || echo "前端未运行"
