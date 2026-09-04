# spring-harness

基于 **Spring AI Alibaba**（阿里云百炼 / 通义千问 DashScope）的全栈 AI Agent 开发框架，国内直连、无需代理。

> [English](./README.md) | 中文文档

## 功能特性

- **五种交互模式**：普通对话 / RAG 知识库 / ReAct Agent / PSE 协作 / 长时任务
- **ReAct Agent**：LLM 自动识别意图调用工具，SSE 流式输出，完整展示工具调用过程
- **PSE 协作编排**：Planner → Specialist → Evaluator 三角色流水线，支持子任务并行、失败重试、整体评审；基于 Soul 的角色定义（本地可配置）
- **RAG 知识库**：上传文档（PDF/TXT/MD/JSON/Java/Python 等）→ 切分向量化 → Rerank 精排检索，并接入 Agent / PSE / 长时任务（通用化 `search_knowledge` 工具）
- **8 个本地工具**：计算器、当前时间、股票实时行情、汇率、代码沙箱、文件读取、技能加载、知识库检索
- **MCP 集成**：filesystem（14 个工具）+ portfolio-check + pse-review（投资数据管线）；工具输出自动脱敏敏感凭证
- **Skills**：可加载 resolve-skills 技能库（code-review、weekly-investment 等），对齐 Claude Code / Codex 开放标准；作为 git submodule 管理
- **Docker 代码沙箱**：隔离执行 **8 种语言**（python / javascript / shell / java / go / rust / c / cpp），网络隔离、只读文件系统、内存/CPU 限制
- **长时任务**：SQLite 持久化，token 消耗统计、执行日志、可中断可续跑；过期任务自动清理（隐私保护）
- **长期记忆（Memory）**：自动从对话抽取用户偏好/事实/目标 → 存 SQLite → 跨会话注入各模式 System prompt；可选 AES-256-GCM 加密；方案 B 会话窗口记忆可选
- **动态示例任务**：输入框 / 长时任务的示例问题按「当前模式 + 环境能力（本地工具 / MCP / 沙箱语言 / 知识库 / 记忆 / Skills）」实时生成，可一键刷新
- **Agnes 模型接入**：免费额度，自动限流（可配置间隔/重试/退避）；可切换 DeepSeek / DashScope 多模型
- **上下文防护**：默认轻量上下文折叠（保留工具名+参数+结论骨架）；上下文超限时可选 LLM 语义摘要压缩
- **隐私合规**：XSS 防护（所有 Markdown 输出经 DOMPurify 消毒）、Actuator 信息泄露防护、MCP 工具输出脱敏、API Key 永不暴露给前端、文件上传校验（扩展名/MIME/魔数/可打印字符比例）
- **前端**：Vite + Vue 3，端口 5174 写死，大 chunk 代码分割，hover 才显示滚动条，Markdown 渲染带代码高亮，结果全屏查看器

## 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | 17 | 后端运行时 |
| Spring Boot | 3.5.16 | 后端 Web 框架 |
| Spring AI Alibaba | 1.1.2.3 | DashScope starter，自带 Spring AI 版本管理 |
| Spring AI MCP | 0.17.0 | Model Context Protocol 客户端 |
| SQLite | 3.x | 长时任务 + Memory 持久化（JDBC） |
| Vue | 3.5.x | 前端框架 |
| Vite | 5.4.x | 前端构建工具 + 开发服务器（端口 5174） |
| marked + DOMPurify | latest | Markdown 渲染 + XSS 消毒 |
| 构建工具 | Maven 3.9+ / npm 10+ | 后端 Maven，前端 npm |

> 说明：Spring AI Alibaba 2.0.x 需搭配 Spring Boot 4，本示例选用 **1.1.x + Boot 3.5** 的成熟稳定组合。

## 目录结构

```
spring-harness
├── .env                  # 本地配置（含密钥，已 gitignore）
├── .env.example          # 配置模板（可提交）
├── .gitmodules           # resolve-skills 作为 git submodule
├── Makefile              # 常用命令封装
├── pom.xml
├── README.md             # 英文文档
├── README.zh.md          # 中文文档
├── TODO.md               # TODO 待办（含隐私合规待办）
├── docker/
│   └── sandbox-gcc.Dockerfile  # 自建 GCC 沙箱镜像（alpine + gcc/g++）
├── frontend/             # 前端（Vite + Vue 3）
│   ├── package.json
│   ├── vite.config.js    # 开发代理：/api、/chat 等 → http://localhost:8080；端口 5174 写死
│   ├── index.html
│   └── src/
│       ├── main.js
│       ├── App.vue       # 五模式切换（对话/RAG/ReAct/PSE/长时任务）
│       ├── utils/markdown.js  # Markdown 渲染 + DOMPurify 消毒
│       └── components/   # MessageList / MessageItem / InputArea / RuntimePanel
│           ├── CollapsibleCard.vue   # 可折叠卡片（Tools/MCP/Skills/Knowledge/Memory）
│           ├── InputArea.vue          # 输入区（含动态示例问题）
│           ├── LongTaskPanel.vue      # 长时任务列表面板
│           ├── MessageItem.vue        # 单条消息（用户/AI）带复制按钮
│           ├── MessageList.vue        # 消息列表
│           ├── ModelManager.vue       # 模型管理（新增/删除模型）
│           ├── ModelSettings.vue      # 模型设置弹窗
│           ├── PrivacyPanel.vue       # 隐私合规面板
│           ├── RagPanel.vue           # RAG 知识库面板（上传/列表/预览）
│           ├── RuntimePanel.vue       # 右侧运行时面板（Tools/MCP/Skills/Knowledge/Memory/Service）
│           ├── SessionList.vue        # 会话列表
│           ├── TaskDetail.vue         # 长时任务详情（执行过程 + 结果）
│           ├── TaskExamples.vue       # 长时任务动态示例任务
│           ├── TaskForm.vue           # 长时任务创建表单
│           └── ToolCallCard.vue       # 工具调用展示卡片
├── resolve-skills/       # Git submodule：技能库（code-review、weekly-investment 等）
└── src/main/
    ├── java/com/example/springharness/
    │   ├── controller/   # 13 个控制器：Chat / Agent / Rag / RagChat / Pse / LongTask
    │   │                 #   / Tools / Models / Memory / Example / Privacy
    │   ├── agent/        # ReActAgentService（ReAct 模式，流式 + 工具调用）
    │   ├── pse/          # PSE 协作（20+ 类）
    │   │   ├── PlannerAgent.java       # 任务分解
    │   │   ├── SpecialistAgent.java    # 任务执行（工具调用，最大 15 次循环）
    │   │   ├── EvaluatorAgent.java     # 任务评审与验收
    │   │   ├── PseOrchestrator.java    # 编排（并行/重试/整体评审）
    │   │   ├── SoulService.java        # 基于 Soul 的角色定义（本地可配置）
    │   │   ├── SkillRegistry.java      # 技能加载与管理
    │   │   ├── LocalToolProvider.java  # 本地工具提供者
    │   │   ├── McpToolProvider.java    # MCP 工具提供者（自动从 MCP 服务器获取）
    │   │   ├── TokenUsageTracker.java  # Token 消耗追踪
    │   │   └── ToolDescriptionService.java  # 统一工具描述（用于 prompt）
    │   ├── task/         # 长时任务
    │   │   ├── TaskManager.java           # 任务执行管理器（线程池）
    │   │   ├── LongTaskStore.java         # SQLite 持久化
    │   │   └── TaskCleanupScheduler.java  # 过期任务自动清理
    │   ├── rag/          # RAG（向量库 + 切分 + Rerank）
    │   ├── sandbox/      # DockerSandboxExecutor（8 语言沙箱）
    │   ├── memory/       # 长期记忆
    │   │   ├── MemoryService.java       # 记忆检索与注入
    │   │   ├── MemoryExtractor.java     # 基于 LLM 的抽取（信号词预过滤）
    │   │   ├── MemoryStore.java         # SQLite 持久化
    │   │   ├── MemoryEncryptor.java     # AES-256-GCM 加密（可选）
    │   │   └── ChatMemoryService.java   # 方案 B 会话窗口记忆
    │   ├── service/      # Prompt / MultiModel / Skill / AgnesRateLimiter / ContextGuard
    │   ├── tool/         # 8 个本地工具
    │   │   ├── CalculatorTool.java
    │   │   ├── CodeExecutionTool.java
    │   │   ├── DateTimeTool.java
    │   │   ├── ExchangeRateTool.java
    │   │   ├── FileReadTool.java
    │   │   ├── RagSearchTool.java
    │   │   ├── SkillRunTool.java
    │   │   ├── StockTool.java
    │   │   └── ToolCallRecorder.java    # ThreadLocal 工具调用记录
    │   ├── config/       # ToolConfig（工具注册）/ CorsConfig / WebConfig
    │   └── util/         # ErrorSanitizer（错误 + 内容脱敏）
    └── resources/
        ├── application.yml
        └── prompts/      # 系统提示模板（抽取出来统一维护）
```

## 快速开始

### 1. 获取 API Key

前往 [阿里云百炼控制台](https://bailian.console.aliyun.com/) → API-KEY 管理，创建并复制 Key。

### 2. 配置 .env

```bash
cd work/spring/spring-harness
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY（以及可选的 AGNES_API_KEY、ALPHAVANTAGE_API_KEY、FINNHUB_API_KEY）
```

`.env` 已加入 `.gitignore`，不会提交密钥。也可用环境变量 `export DASHSCOPE_API_KEY=sk-xxx` 覆盖。

### 3. 初始化 git submodule（Skills）

```bash
git submodule update --init --recursive
```

这会将 `resolve-skills` 技能库（code-review、weekly-investment 等）加载到 `./resolve-skills/` 目录。

### 4. 启动

```bash
# 开发模式：kill 旧进程 → 编译后端 → 启动后端（等待就绪）→ 启动前端（5174）
make dev

# 或分别启动
make run-bg          # 后端后台启动（端口 8080）
make frontend-dev    # 前端开发服务器（端口 5174）
```

启动后访问 **http://localhost:5174** 打开聊天界面。
前端通过 Vite 开发代理将 `/chat`、`/api`、`/rag` 请求转发到后端 `http://localhost:8080`。

> **注意**：先启动后端，再启动前端。`make dev` 会自动处理（等待后端健康检查通过后再启动前端）。

### 5. 调用接口

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
| `make dev` | 完整开发模式：kill 旧进程 → 编译 → 启动后端 → 等待就绪 → 启动前端 |
| `make run` | 前台启动应用 |
| `make run-bg` | 后台启动，日志写入 app.log |
| `make stop` | 停止后台应用 |
| `make restart` | 停止并重新启动 |
| `make compile` | 编译 |
| `make package` | 打包（跳过测试） |
| `make test` | 运行测试 |
| `make clean` | 清理构建产物 |
| `make health` | 健康检查（调用 /chat） |
| `make frontend-dev` | 仅启动前端开发服务器 |
| `make frontend-build` | 构建前端生产版本 |

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
| GET | `/api/tools` | 列出所有可用工具（本地 + MCP）及描述 |
| GET | `/api/mcp/status` | MCP 服务器连接状态 |
| GET | `/models` | 列出所有已配置模型 |
| POST | `/models` | 新增模型配置 |
| DELETE | `/models/{id}` | 删除模型配置 |
| GET | `/api/privacy/status` | 隐私合规功能状态 |
| GET | `/api/tasks` | 长时任务列表 |
| POST | `/api/tasks` | 创建新长时任务 |
| GET | `/api/tasks/{id}` | 获取任务详情（执行过程 + 结果 + 日志） |
| POST | `/api/tasks/{id}/cancel` | 取消运行中的任务 |
| POST | `/api/tasks/{id}/resume` | 续跑已暂停/中断的任务 |
| GET | `/api/tasks/{id}/logs` | 获取任务执行日志 |

### 长期记忆（Memory）

自动从对话中抽取**稳定的用户偏好 / 事实 / 目标**（如「我喜欢用中文」「我常住上海」），存入 SQLite，在后续对话 / ReAct / PSE 中按当前问题检索并注入 System prompt，实现跨会话记忆。

- 抽取前做**信号词预过滤**（命中「我喜欢/我是/我住在/我的目标」等才调用 LLM），控制成本与限流
- 记忆按 `偏好 / 事实 / 目标` 分类，关键词 LIKE 检索 + 访问频率排序，取前 `MEMORY_TOP_K` 条注入
- **可选 AES-256-GCM 加密**：开启后记忆内容加密存储（防止数据库文件泄露导致隐私暴露）；SQL LIKE 降级为内存匹配
- 管理：右侧 Runtime 面板 → Memory 卡片可查看 / 开关 / 清空 / 删除单条

**配置项**（`.env`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `MEMORY_ENABLED` | `true` | 是否启用长期记忆 |
| `MEMORY_EXTRACT_ENABLED` | `true` | 是否启用抽取（信号词命中才调 LLM） |
| `MEMORY_TOP_K` | `5` | 每次注入的记忆条数 |
| `MEMORY_MAX_ITEMS` | `200` | 记忆条目上限，超出删除最旧 |
| `MEMORY_MODEL` | 空 | 抽取模型，留空跟随主模型 |
| `MEMORY_CHAT_ENABLED` | `false` | 方案 B 会话窗口记忆：按 conversationId 后端维护多轮窗口 |
| `MEMORY_CHAT_WINDOW` | `20` | 会话窗口大小 |
| `MEMORY_ENCRYPT_ENABLED` | `false` | 记忆内容 AES-256-GCM 加密存储 |
| `MEMORY_ENCRYPT_KEY` | 空 | 加密密钥（留空使用基于机器特征的默认密钥） |

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

**8 个本地工具**（Agent / PSE / 长时任务均可调用）：

| 工具名 | 功能 | 触发示例 |
| --- | --- | --- |
| `calculator` | 加减乘除 | "123乘以456等于多少" |
| `get_datetime` | 当前日期时间 | "现在几点了"、"今天星期几" |
| `query_stock` | 股票实时行情（Yahoo Finance + Finnhub 兜底，带限流与重试） | "苹果股票多少钱"、"AAPL 股价" |
| `query_exchange_rate` | 汇率换算 | "美元兑人民币汇率" |
| `execute_code` | Docker 沙箱执行 8 种语言代码 | "用Python算斐波那契第20项" |
| `read_file` | 读取文本文件内容（沙箱隔离路径） | "读取配置文件" |
| `skill_run` | 加载技能指令（code-review / weekly-investment 等） | "用代码审查技能审查XX.java" |
| `search_knowledge` | RAG 知识库检索（Rerank 精排） | "文档中提到了哪些向量数据库" |

**16+ MCP 工具**：`filesystem`（14 个文件操作：read_file、write_file、edit_file、list_directory、search_files 等）+ `portfolio-check`（投资数据体检）+ `pse-review`（深度投资周报）。

> 股票行情（Yahoo Finance / Finnhub 实时，带限流与重试）、汇率来自公开接口。敏感 API Key 一律放 `.env`（已 gitignore），勿写进对话或代码。MCP 工具输出返回前自动经 `ErrorSanitizer.sanitizeContent()` 过滤 API Key/token/secret 等敏感凭证。

### 工具调用原理

1. 用户提问 → LLM 判断是否需要调用工具
2. LLM 返回工具名 + 入参（JSON）
3. Spring AI 的 `ToolCallingAdvisor` 自动执行对应 `FunctionToolCallback`
4. 工具结果喂回 LLM，LLM 基于结果生成自然语言回答
5. `ToolCallRecorder`（ThreadLocal）记录每次工具调用的入参、出参、耗时
6. 工具输出自动经 `ErrorSanitizer.sanitizeContent()` 脱敏敏感凭证

### 上下文防护

为防止上下文窗口溢出（尤其在 ReAct/PSE 多次工具调用场景）：

- **轻量折叠（默认）**：上下文接近上限时，最旧的工具往返被折叠为骨架（工具名 + 参数 + 结论），零 LLM 成本
- **LLM 语义摘要（可选）**：当 `CONTEXT_SUMMARY_ENABLED=true` 时，用 LLM 将最旧的工具往返压缩为语义摘要（更智能，但每次压缩多消耗一次 LLM 调用）

**配置项**（`.env`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `CONTEXT_SUMMARY_ENABLED` | `false` | 启用 LLM 语义摘要压缩 |
| `CONTEXT_SUMMARY_MAX_CHARS` | `4000` | LLM 语义摘要最大字符数 |

### RAG 知识库模式

RAG（Retrieval-Augmented Generation，检索增强生成）：上传文档后，LLM 基于文档内容回答问题，避免幻觉。

**使用流程**：
1. 前端切换到「RAG 知识库」模式，点击「上传文档」选择文件（支持 PDF、TXT、MD、JSON、Java、Python 等文本格式）
2. 后端自动完成：文档加载 → 文本切分（DOC/PDF 用 ParagraphTextSplitter，MD 用 MarkdownTextSplitter，其他用 TokenTextSplitter）→ 向量化 → 存入向量库（SimpleVectorStore，磁盘持久化）
3. 在输入框提问，LLM 自动检索相关文档片段并基于内容回答
4. 文档分块按编号预览；向量库磁盘持久化，重启不丢失

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
| 向量库 | `SimpleVectorStore`（持久化） | 内存向量库 + 磁盘持久化，开发演示用；生产可切换 Redis/PGVector/Milvus |
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

**配置项**（`.env`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SANDBOX_ENABLED` | `true` | 启用代码执行沙箱 |
| `SANDBOX_TIMEOUT` | `30` | 代码执行超时时间（秒） |
| `SANDBOX_MEMORY_MB` | `512` | 容器内存限制（MB） |
| `SANDBOX_CPUS` | `1` | 容器 CPU 限制（核数） |
| `SANDBOX_MAX_OUTPUT_KB` | `100` | 标准输出/错误最大大小（KB），超过自动截断 |

## PSE 协作与长时任务

### PSE 协作

- **三角色流水线**：Planner 将任务分解为子任务 → Specialist 并行执行（可调用工具，最大 15 次循环）→ Evaluator 评审验收，失败自动重试（每个子任务最多 3 次重试）
- **整体评审**：所有子任务完成后，Evaluator 进行整体评审；若不通过，Planner 可能重新分解
- **基于 Soul 的角色定义**：每个角色（Planner/Specialist/Evaluator）有 SOUL.md 定义，可通过 `HARNESS_SOULS_DIR` 配置（本地路径，默认指向 resolve-skills/souls）
- **并行执行**：`PSE_PARALLEL_ENABLED=true` 时子任务并行执行
- **长耗时工具支持**：MCP 请求超时设为 600s，Specialist 系统提示包含长耗时工具注意事项（不要激进重试，耐心等待）
- **Token 追踪**：`TokenUsageTracker` 记录每个角色的 token 消耗

**配置项**（`.env`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PSE_TIMEOUT_SECONDS` | `600` | PSE 编排整体超时（秒） |
| `PSE_PARALLEL_ENABLED` | `true` | 启用子任务并行执行 |
| `HARNESS_SOULS_DIR` | 空 | Soul 定义目录（本地路径，默认指向 resolve-skills/souls） |

### 长时任务

- **后台执行**：线程池（可配置大小），可并发执行多个任务
- **SQLite 持久化**：`data/tasks.db`，重启后自动恢复；任务状态、执行过程、结果、日志全部持久化
- **Token 消耗**：记录任务中每次 LLM 调用的输入/输出 token
- **中断与续跑**：可取消运行中的任务；已中断的任务可从检查点续跑
- **执行日志**：存储在 `logs/tasks/`，可通过 API 或 UI 查看
- **错误报告复制**：一键复制结构化错误报告（正确步骤简写，出错步骤详细）
- **自动清理**：过期的已完成任务自动删除（隐私保护 + 防止数据库无限增长）

**配置项**（`.env`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `TASK_DB_PATH` | `data/tasks.db` | SQLite 任务数据库路径 |
| `TASK_POOL_SIZE` | `4` | 长时任务线程池大小 |
| `TASK_LOG_DIR` | `logs/tasks` | 任务执行日志目录 |
| `TASK_RETENTION_DAYS` | `30` | 已完成任务保留天数（超过自动清理） |
| `TASK_CLEANUP_ENABLED` | `true` | 启用定时清理（每天凌晨 3 点 + 启动时各执行一次） |

## 隐私合规

内置隐私合规功能：

| 功能 | 实现 |
| --- | --- |
| **XSS 防护** | 所有 Markdown 输出经 DOMPurify 消毒（白名单标签/属性，禁止 script/style/iframe，移除 on* 事件处理器，强制链接 noopener） |
| **API Key 防护** | API Key 永不返回给前端；仅存储在 `.env`（已 gitignore）；MCP 工具输出自动脱敏过滤 key/token/secret |
| **Actuator 信息泄露防护** | `management.endpoint.health.show-details=never`，仅返回 UP/DOWN，不泄露服务器配置详情 |
| **文件上传校验** | 扩展名白名单、大小限制、MIME 类型校验、魔数校验、文本文件可打印字符比例检查 |
| **CORS 安全** | `CORS_ALLOWED_ORIGINS` 默认 `http://localhost:5174`，不使用通配符；凭证可配置 |
| **记忆加密** | 可选 AES-256-GCM 加密记忆内容存储 |
| **任务数据保留** | 已完成任务超过保留期后自动删除 |
| **沙箱隔离** | 代码在 Docker 容器中执行，网络隔离、只读文件系统、资源限制 |

> 待办项（CSRF 防护、全局速率限制、审计日志、前端 localStorage 加密）已记录在 `TODO.md` 中。

## 常用模型

| 模型名 | 提供方 | 定位 |
| --- | --- | --- |
| `qwen-turbo` | DashScope | 快速、高性价比 |
| `qwen-plus` | DashScope | 均衡型，默认推荐 |
| `qwen-max` | DashScope | 高能力 |
| `qwen3-coder-plus` | DashScope | 代码生成专用 |
| `agnes-2.0-flash` | Agnes AI | 免费模型（默认，自动限流） |
| `glm-5.2` | 智谱（经 DashScope 网关） | 均衡型 |
| `deepseek` | DeepSeek | 付费、稳定 |

模型通过 `.env` 的 `DASHSCOPE_MODEL` / Agnes 配置切换。完整列表见 [百炼模型列表](https://help.aliyun.com/zh/model-studio/models)。

**Agnes 限流配置**（`.env`）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AGNES_RATE_INTERVAL_MS` | `6000` | Agnes API 调用最小间隔（毫秒） |
| `AGNES_RATE_MAX_RETRIES` | `3` | 429 限流时最大重试次数 |
| `AGNES_RATE_BACKOFF_BASE_MS` | `10000` | 重试基础退避时间（毫秒） |
| `AGNES_MAX_TOKENS` | `8192` | Agnes 模型最大输出 token 数 |

## 常见问题

- **启动报错 API Key 相关**：未配置 `DASHSCOPE_API_KEY`，按上方步骤配置。
- **报错 401 / InvalidApiKey**：Key 错误或已过期，检查百炼控制台。
- **报错 400 / model not found**：`DASHSCOPE_MODEL` 与账号开通的模型不匹配，换用已开通的模型。
- **Agnes 429 限流**：Agnes 免费版有限流；在 `.env` 中配置 `AGNES_RATE_*` 控制调用频率，或切换到 `qwen-plus` / `deepseek`。
- **MCP filesystem 服务器找不到**：用 `npm i -g @modelcontextprotocol/server-filesystem` 安装，或设置 `MCP_FS_COMMAND` 为绝对路径。
- **Docker 沙箱不可用**：确保 Docker（OrbStack / Docker Desktop）已启动；在 `.env` 中设置 `SANDBOX_ENABLED=true`。
- **ReAct/PSE 上下文窗口溢出**：开启 `CONTEXT_SUMMARY_ENABLED=true` 进行 LLM 语义压缩，或减少工具调用循环次数。
- **想换回 OpenAI 兼容协议**：把依赖换成 `spring-ai-starter-model-openai`，配置 `spring.ai.openai.base-url` 即可，Controller 代码无需改动（Spring AI 抽象层屏蔽了差异）。

## 下一步可扩展

- 聊天记忆（`ChatMemory` / 向量库）
- 多轮对话（`Advisor` + Message History）
- RAG（`VectorStore` + `QuestionAnswerAdvisor`，支持 PGVector / Redis / Milvus 等 25+ 向量库）
- 结构化输出（Bean 输出 / JSON Schema 约束）
- Agent Framework（Spring AI Alibaba `ReactAgent` + Graph 运行时，多 Agent 编排）
- MCP 集成（Spring AI 1.1 原生支持 Model Context Protocol，可封装工具为 MCP Server）
- 多模态（图片理解 `qwen-vl`、语音识别/合成）
- CSRF 防护与全局速率限制（已记录在 TODO.md）
- 所有工具调用和 API 访问的审计日志
- 前端聊天历史 localStorage 加密
