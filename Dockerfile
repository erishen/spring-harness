# ============================================
# Spring Harness - 多阶段 Dockerfile
# 阶段 1: 前端构建 (node) → dist
# 阶段 2: 后端打包 (maven) → jar（含前端 static）
# 阶段 3: 运行时 (jre + node + docker-cli + mcp-server-filesystem)
# ============================================

# ===== 阶段 1：前端构建 =====
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY frontend/ ./
RUN npm run build

# ===== 阶段 2：后端构建 =====
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /app
COPY pom.xml ./
# 预热依赖缓存（失败不阻断，后续 package 会补）
RUN mvn -q dependency:go-offline -B || true
COPY src/ ./src/
# 前端静态资源打入后端 classpath:/static（生产模式由 Spring Boot 直接服务 SPA）
COPY --from=frontend /app/frontend/dist ./src/main/resources/static/
RUN mvn -q package -DskipTests -B

# ===== 阶段 3：运行时 =====
FROM eclipse-temurin:17-jre-alpine

# MCP 依赖 node；代码沙箱依赖 docker CLI（通过挂载宿主机 docker.sock 调用）
RUN apk add --no-cache nodejs npm docker \
    && npm i -g @modelcontextprotocol/server-filesystem \
    && rm -rf /var/cache/apk/* /root/.npm

# npm 全局 bin 路径（alpine 下为 /usr/local/bin）
ENV MCP_FS_COMMAND=/usr/local/bin/mcp-server-filesystem \
    JAVA_OPTS="-Xmx512m -Xms256m"

WORKDIR /app
COPY --from=backend /app/target/*.jar app.jar

EXPOSE 8080

# 数据目录（SQLite/任务日志）与工作区（MCP 文件系统挂载点）
VOLUME ["/app/data", "/workspace"]

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
