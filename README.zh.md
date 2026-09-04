# spring-harness

基于 **Spring AI Alibaba**（阿里云百炼 / 通义千问 DashScope）的全栈 AI Agent 开发框架，国内直连、无需代理。

> [English README](./README.md) | 中文文档

## 功能特性

- **五种交互模式**：普通对话 / RAG 知识库 / ReAct Agent / PSE 协作 / 长时任务
- **ReAct Agent**：LLM 自动识别意图调用工具，SSE 流式输出，完整展示工具调用过程
- **PSE 协作编排**：Planner → Specialist → Evaluator 三角色流水线，支持子任务并行、失败重试、整体评审
- **RAG 知识库**：上传文档（PDF/TXT/MD/JSON/Java/Python 等）→ 切分向量化 → Rerank 精排检索，并接入 Agent / PSE / 长时任务
- **本地工具 7 个**：计算器、当前时间、股票实时行情、汇率、代码沙箱、技能加载、知识库检索
- **MCP 集成**：filesystem + portfolio-check + pse-review（投资数据管线）
- **Skills**：可加载 resolve-skills 技能库（code-review、weekly-investment 等），对齐 Claude Code / Codex 开放标准
- **Docker 代码沙箱**：隔离执行 **8 种语言**（python / javascript / shell / java / go / rust / c / cpp）
- **长时任务**：SQLite 持久化，token 消耗统计、执行日志、可中断可续跑
- **长期记忆（Memory）**：自动从对话抽取用户偏好/事实/目标 → 存 SQLite → 跨会话注入各模式 System prompt（方案 B 会话窗口记忆可选）
- **动态示例任务**：输入框 / 长时任务的示例问题按「当前模式 + 环境能力（本地工具 / MCP / 沙箱语言 / 知识库 / 记忆 / Skills）」实时生成，可一键刷新
- **Agnes 模型接入**：免费额度，自动限流（可切换 DeepSeek / DashScope 多模型）

## 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | 17 | 后端运行时 |
| Spring Boot | 3.5.16 | 后端 Web 框架 |
| Spring AI Alibaba | 1.1.2.3 | DashScope starter，自带 Spring AI 版本管理 |
| Vue | 3.5.x | 前端框架 |
| Vite | 5.4.x | 前端构建工具 + 开发服务器 |
| 构建工具 | Maven 3.9+ / npm 10+ | 后端 Maven，前端 npm |

> 说明：Spring AI Alibaba 2.0.x 需搭配 Spring Boot 4，本示例选用 **1.1.x + Boot 3.5** 的成熟稳定组合。

## 目录结构

```
spring-harness
├── .env                  # 本地配置（含密钥，已 gitignore）
├── .env.example          # 配置模板（可提交）
├── Makefile              # 常用命令封装
├── pom.xml
├── frontend/             # 前端（Vite + Vue 3）
│   ├── package.json
│   ├── vite.config.js    # 开发代理：/api、/chat 等 → http://localhost:8080
│   ├── index.html
│   └── src/
│       ├── main.js
│       ├── App.vue       # 五模式切换（对话/RAG/ReAct/PSE/长时任务）
│       └── components/   # MessageList / InputArea / RuntimePanel / LongTaskPanel
└── src/main/
    ├── java/com/example/springharness/
    │   ├── controller/   # Chat / Agent / Rag / Pse / LongTask / Tools / Models
    │   ├── agent/        # ReActAgentService（ReAct 模式）
    │   ├── pse/          # PSE 协作（Planner/Specialist/Evaluator/Orchestrator/Soul/TokenUsage）
    │   ├── task/         # 长时任务（线程池 + SQLite）
    │   ├── rag/          # RAG（向量库 + 切分 + Rerank）
    │   ├── sandbox/      # DockerSandboxExecutor（8 语言沙箱）
    │   ├── service/      # Prompt / MultiModel / Skill / AgnesRateLimiter
    │   ├── tool/         # 7 个本地工具（calculator/query_stock/execute_code/skill_run/search_knowledge...）
    │   └── config/       # ToolConfig（工具注册）
    └── resources/application.yml
```

## 快速开始

### 1. 获取 API Key

前往 [阿里云百炼控制台](https://bailian.console.aliyun.com/) → API-KEY 管理，创建并复制 Key。

### 2. 配置 .env

```bash
cd work/spring/spring-harness
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY
```

`.env` 已加入 `.gitignore`，不会提交密钥。也可用环境变量 `export DASHSCOPE_API_KEY=sk-xxx` 覆盖。

### 3. 启动

```bash
# 开发模式：同时启动后端(8080)和前端(5174)，自动安装前端依赖
make dev

# 或分别启动
make run-bg          # 后端后台启动
make frontend-dev    # 前端开发服务器
```

启动后访问 **http://localhost:5174** 打开聊天界面。
前端通过 Vite 开发代理将 `/chat` 请求转发到后端 `http://localhost:8080`。

### 4. 调用接口

```bash
# 非流式：一次返回完整回答
curl "http://localhost:8080/chat?message=用一句话介绍Spring%20AI%20Alibaba"

# 流式（SSE）：逐字返回
curl -N "http://localhost:8080/chat/stream?message=讲一个程序员笑话"
```

## Makefile 命令

| 命令 | 说明 |
| --- | --- |
| `make help` | 显示所有命令（默认） |
| `make run` | 前台启动应用 |
| `make run-bg` | 后台启动，日志写入 app.log |
| `make stop` | 停止后台应用 |
| `make restart` | 停止并重新启动 |
| `make compile` | 编译 |
| `make package` | 打包（跳过测试） |
| `make test` | 运行测试 |
| `make clean` | 清理构建产物 |
| `make health` | 健康检查（调用 /chat） |

## 接口说明

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/chat?message=xxx` | 非流式，返回完整回答 |
| GET | `/chat/stream?message=xxx` | 流式，SSE 逐字返回 |
| GET | `/chat/agent?message=xxx` | Agent 模式，自动工具调用，返回回答 + 工具调用过程 |
| GET | `/chat/rag?message=xxx` | RAG 模式，基于知识库检索增强回答 |
| POST | `/rag/documents` | 上传文档（multipart/form-data，字段名 file） |
| GET | `/rag/documents` | 列出所有已索引文档 |
| DELETE | `/rag/documents/{docId}` | 删除文档及其所有向量块 |
| GET | `/rag/stats` | 文档统计信息 |
| GET | `/rag/search?query=xxx&topK=5` | 语义检索（调试用） |
| GET | `/api/memory` | 长期记忆列表 + 开关状态 |
| POST | `/api/memory/toggle` | 开启/关闭记忆 `{"enabled": true}` |
| POST | `/api/memory/clear` | 清空全部记忆 |
| DELETE | `/api/memory/{id}` | 删除单条记忆 |
| GET | `/api/examples?mode=chat\|agent\|pse\|rag\|longtask` | 按模式 + 当前环境能力动态生成示例任务 |

### 长期记忆（Memory）

自动从对话中抽取**稳定的用户偏好 / 事实 / 目标**（如「我喜欢用中文」「我常住上海」），存入 SQLite，在后续对话 / ReAct / PSE 中按当前问题检索并注入 System prompt，实现跨会话记忆。

- 抽取前做**信号词预过滤**（命中「我喜欢/我是/我住在/我的目标」等才调用 LLM），控制成本与限流
- 记忆按 `偏好 / 事实 / 目标` 分类，关键词 LIKE 检索 + 访问频率排序，取前 `MEMORY_TOP_K` 条注入
- 管理：右侧 Runtime 面板 → Memory 卡片可查看 / 开关 / 清空 / 删除单条

**配置项**（`.env`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `MEMORY_ENABLED` | `true` | 是否启用长期记忆 |
| `MEMORY_EXTRACT_ENABLED` | `true` | 是否启用抽取（信号词命中才调 LLM） |
| `MEMORY_TOP_K` | `5` | 每次注入的记忆条数 |
| `MEMORY_MAX_ITEMS` | `200` | 记忆上限，超出删除最旧 |
| `MEMORY_MODEL` | 空 | 抽取模型，留空跟随主模型 |
| `MEMORY_CHAT_ENABLED` | `false` | 会话窗口记忆（方案 B）：按 conversationId 后端维护多轮窗口 |
| `MEMORY_CHAT_WINDOW` | `20` | 会话窗口大小 |

> 会话窗口记忆（方案 B）默认关闭：当前前端已通过 `messages` 参数回传历史，方案 B 供需要后端统一管理窗口的场景开启（`/chat` 与 `/chat/stream` 传入 `conversationId` 即生效）。

### Agent 模式示例

```bash
curl "http://localhost:8080/chat/agent?message=123乘以456等于多少"
```

返回：
```json
{
  "answer": "123 乘以 456 等于 56088。",
  "toolCalls": [
    {
      "name": "calculator",
      "input": { "a": 123, "b": 456, "operation": "multiply" },
      "output": "123.0 × 456.0 = 56088.0",
      "durationMs": 3
    }
  ]
}
```

### 内置工具

**本地工具 7 个**（Agent / PSE / 长时任务均可调用）：

| 工具名 | 功能 | 触发示例 |
| --- | --- | --- |
| `calculator` | 加减乘除 | "123乘以456等于多少" |
| `get_datetime` | 当前日期时间 | "现在几点了"、"今天星期几" |
| `query_stock` | 股票实时行情 | "苹果股票多少钱"、"AAPL 股价" |
| `query_exchange_rate` | 汇率换算 | "美元兑人民币汇率" |
| `execute_code` | Docker 沙箱执行 8 种语言代码 | "用Python算斐波那契第20项" |
| `skill_run` | 加载技能指令（code-review / weekly-investment 等） | "用代码审查技能审查XX.java" |
| `search_knowledge` | RAG 知识库检索（Rerank 精排） | "文档中提到了哪些向量数据库" |

**MCP 工具 16 个**：`filesystem`（14 个文件操作）+ `portfolio-check`（投资数据体检）+ `pse-review`（深度投资周报）。

> 股票行情（Yahoo Finance / Finnhub 实时，带限流与重试）、汇率来自公开接口。敏感 API Key 一律放 `.env`（已 gitignore），勿写进对话或代码。

### 工具调用原理

1. 用户提问 → LLM 判断是否需要调用工具
2. LLM 返回工具名 + 入参（JSON）
3. Spring AI 的 `ToolCallingAdvisor` 自动执行对应 `FunctionToolCallback`
4. 工具结果喂回 LLM，LLM 基于结果生成自然语言回答
5. `ToolCallRecorder`（ThreadLocal）记录每次工具调用的入参、出参、耗时

### RAG 知识库模式

RAG（Retrieval-Augmented Generation，检索增强生成）：上传文档后，LLM 基于文档内容回答问题，避免幻觉。

**使用流程**：
1. 前端切换到「RAG 知识库」模式，点击「上传文档」选择文件（支持 PDF、TXT、MD、JSON、Java、Python 等文本格式）
2. 后端自动完成：文档加载 → 文本切分（TokenTextSplitter）→ 向量化（DashScope text-embedding-v2）→ 存入向量库（SimpleVectorStore）
3. 在输入框提问，LLM 自动检索相关文档片段并基于内容回答

**命令行示例**：
```bash
# 上传文档
curl -X POST -F "file=@knowledge.pdf" http://localhost:8080/rag/documents

# 列出文档
curl http://localhost:8080/rag/documents

# RAG 问答
curl "http://localhost:8080/chat/rag?message=文档中提到了哪些向量数据库"
```

**RAG 技术栈**：
| 组件 | 实现 | 说明 |
| --- | --- | --- |
| 向量库 | `SimpleVectorStore` | 内存向量库，开发演示用；生产可切换 Redis/PGVector/Milvus |
| Embedding | DashScope `qwen3.7-text-embedding` | 100 万 Token 免费，1024 维 |
| 重排序 | DashScope `qwen3.7-text-rerank` | RAG 检索后精排（有免费额度） |
| 文本切分 | `ParagraphTextSplitter` / `MarkdownTextSplitter` / `TokenTextSplitter` | DOC/PDF 按段落、Markdown 按结构、其他按 token |
| 检索增强 | `RetrievalAugmentationAdvisor` | Spring AI 1.1 RAG Advisor，自动检索+注入 Prompt |
| 文档加载 | `PagePdfDocumentReader` + 纯文本读取 | PDF 按页读取，其他格式直接读取文本 |

> **RAG 通用化**：`search_knowledge` 工具已注册到 Agent / PSE / 长时任务，三类模式都能检索知识库。切换向量库仅需替换 `RagConfig` 中的 `VectorStore` Bean，RagService 和 Controller 无需改动。

## Docker 代码沙箱

`execute_code` 工具在隔离的 Docker 容器中执行代码，支持 **8 种语言**：

| 语言 | 镜像 | 说明 |
| --- | --- | --- |
| python | python:3.11-slim | 解释执行 |
| javascript | node:20-slim | 解释执行 |
| shell | alpine:3.19 | 解释执行 |
| java | eclipse-temurin:17-jdk | `java Main.java` 源码模式 |
| go | golang:1.22-alpine | `go run`（缓存重定向 /tmp） |
| rust | rust:1.75-alpine | `rustc` 编译后运行 |
| c / cpp | sandbox-gcc:alpine（自建） | `gcc` / `g++` 编译后运行 |

**安全隔离**：每次执行独立容器（`--rm` 自动销毁）、`--network none` 禁用网络、`--read-only` 只读根文件系统（仅 /tmp 可写且可执行）、内存 512MB / 1 核 / 100 进程限制、超时自动 kill、输出 100KB 截断。

沙箱开关在 `.env`：`SANDBOX_ENABLED=true`。c/cpp 使用自建镜像 `docker/sandbox-gcc.Dockerfile`（alpine + gcc/g++，209MB，musl libc 对标准程序完全兼容）。

## PSE 协作与长时任务

- **PSE 协作**：Planner 将任务分解为子任务 → Specialist 并行执行（可调用工具）→ Evaluator 评审验收，失败自动重试；支持整体评审与最终交付。`PSE_TIMEOUT_SECONDS` 控制整体超时（默认 90s，.env 已设 600s）。
- **长时任务**：后台线程池执行（可并发多任务），SQLite（`data/tasks.db`）持久化，重启后自动恢复；记录 token 消耗、执行日志、执行过程（可复制错误报告）、执行结果 Markdown 渲染。

## 常用模型

| 模型名 | 定位 |
| --- | --- |
| `qwen-plus` | DashScope 均衡型，默认推荐 |
| `agnes-2.0-flash` | Agnes 免费模型（默认，自动限流） |
| `glm-5.2` | 智谱模型（经 DashScope 网关） |
| `deepseek` | DeepSeek（付费、稳定） |

模型通过 `.env` 的 `DASHSCOPE_MODEL` / Agnes 配置切换。完整列表见 [百炼模型列表](https://help.aliyun.com/zh/model-studio/models)。

## 常见问题

- **启动报错 API Key 相关**：未配置 `DASHSCOPE_API_KEY`，按上方步骤配置。
- **报错 401 / InvalidApiKey**：Key 错误或已过期，检查百炼控制台。
- **报错 400 / model not found**：`DASHSCOPE_MODEL` 与账号开通的模型不匹配，换用已开通的模型。
- **想换回 OpenAI 兼容协议**：把依赖换成 `spring-ai-starter-model-openai`，配置 `spring.ai.openai.base-url` 即可，Controller 代码无需改动（Spring AI 抽象层屏蔽了差异）。

## 下一步可扩展

- 聊天记忆（`ChatMemory` / 向量库）
- 多轮对话（`Advisor` + Message History）
- RAG（`VectorStore` + `QuestionAnswerAdvisor`，支持 PGVector / Redis / Milvus 等 25+ 向量库）
- 结构化输出（Bean 输出 / JSON Schema 约束）
- Agent Framework（Spring AI Alibaba `ReactAgent` + Graph 运行时，多 Agent 编排）
- MCP 集成（Spring AI 1.1 原生支持 Model Context Protocol，可封装工具为 MCP Server）
- 多模态（图片理解 `qwen-vl`、语音识别/合成）
