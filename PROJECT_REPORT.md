# Spring Harness 项目合并报告

> 自动生成于 2026-09-04

---

## 📊 项目概览

| 指标 | 数值 |
|------|------|
| 后端框架 | Spring Boot 3.5.16 |
| AI 框架 | Spring AI Alibaba 1.1.2.3 |
| 前端框架 | Vue 3.5.x + Vite 5.4.x |
| Java 版本 | 17 |
| 主要功能 | 普通对话 / ReAct Agent / PSE 协作 / RAG 知识库 / 长时任务 |

---

## 📁 目录结构

```
spring-harness/
├── .env                    # 本地配置（含密钥，已 gitignore）
├── .env.example            # 配置模板
├── Makefile                # 常用命令封装
├── pom.xml                 # Maven 依赖配置
├── README.md               # 项目文档
├── TODO.md                 # 任务清单
├── app.log                 # 运行日志
├── data/                   # 数据目录
│   ├── tasks.db            # 长时任务 SQLite 数据库
│   ├── document-registry.json
│   └── vector-store.json
├── docker/
│   └── sandbox-gcc.Dockerfile
├── frontend/               # 前端项目
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── main.js
│       ├── App.vue         # 主组件（五模式切换）
│       ├── components/     # UI 组件
│       ├── composables/    # 组合式函数
│       ├── services/       # API 服务层
│       └── utils/          # 工具函数
├── logs/tasks/             # 任务执行日志
├── resolve-skills/         # 技能库（git submodule）
│   └── skills/
│       ├── code-review/
│       ├── hot-news-post/
│       ├── post-comment/
│       ├── rust-review/
│       └── weekly-investment/
└── src/main/
    ├── java/com/example/springharness/
    │   ├── SpringHarnessApplication.java
    │   ├── agent/          # ReAct Agent 服务
    │   ├── config/         # 配置类
    │   ├── controller/     # Controller 层
    │   ├── dto/            # 数据传输对象
    │   ├── memory/         # 长期记忆服务
    │   ├── pse/            # PSE 协作编排
    │   ├── rag/            # RAG 知识库
    │   ├── sandbox/        # Docker 沙箱
    │   ├── service/        # 业务服务
    │   ├── task/           # 长时任务
    │   ├── tool/           # 本地工具
    │   └── util/           # 工具类
    └── resources/
        └── application.yml
```

---

## 🔌 核心功能模块

### 1. 交互模式（5种）

| 模式 | 端点 | 说明 |
|------|------|------|
| 普通对话 | `/chat`, `/chat/stream` | 基础聊天，支持多轮上下文 |
| ReAct Agent | `/chat/agent`, `/chat/agent/react/stream` | 自动工具调用，多轮推理 |
| PSE 协作 | `/chat/pse/stream` | Planner-Specialist-Evaluator 三角色 |
| RAG 知识库 | `/chat/rag` | 基于上传文档的检索增强生成 |
| 长时任务 | `/api/tasks` | 后台异步执行，SQLite 持久化 |

### 2. 内置工具（7个本地 + MCP）

**本地工具：**
- `calculator` - 加减乘除计算
- `get_datetime` - 获取当前日期时间
- `query_stock` - 股票实时行情（美股/港股/A股）
- `query_exchange_rate` - 汇率换算
- `execute_code` - Docker 沙箱执行代码（8种语言）
- `skill_run` - 加载技能指令
- `search_knowledge` - RAG 知识库检索

**MCP 工具：**
- `filesystem` - 文件系统操作
- `portfolio-check` - 投资数据体检
- `pse-review` - 深度投资周报

### 3. 支持的模型

| 模型 | 供应商 | 说明 |
|------|--------|------|
| qwen-plus | 阿里云百炼 | 默认推荐 |
| agnes-2.0-flash | Agnes | 免费额度 |
| glm-5.2 | 智谱 | 经 DashScope 网关 |
| deepseek | DeepSeek | 付费稳定 |

---

## 🛠️ 技术栈

### 后端依赖

| 依赖 | 版本 | 说明 |
|------|------|------|
| spring-boot-starter-web | 3.5.16 | Web 框架 |
| spring-ai-alibaba-starter-dashscope | 1.1.2.3 | DashScope starter |
| spring-ai-pdf-document-reader | 1.1.2 | PDF 文档解析 |
| spring-ai-starter-mcp-client | 1.1.2 | MCP 客户端 |
| spring-ai-openai | 1.1.2 | OpenAI 兼容接口 |
| sqlite-jdbc | 3.46.1.3 | 长时任务持久化 |

### 前端依赖

| 依赖 | 版本 | 说明 |
|------|------|------|
| vue | ^3.5.13 | 前端框架 |
| vite | ^5.4.11 | 构建工具 |
| marked | ^18.0.11 | Markdown 解析 |
| highlight.js | ^11.12.0 | 代码高亮 |

---

## 📈 代码统计

基于项目结构分析：

| 类别 | 数量 |
|------|------|
| Java 源文件 | ~50 个 |
| Vue 组件 | 6 个 |
| JavaScript 文件 | 4 个 |
| Controller | 9 个 |
| Service/Agent | 10+ 个 |
| 工具类 | 7 个 |
| API 端点 | 20+ 个 |

---

## 📋 Controller 列表

1. **ChatController.java** - 普通对话接口
2. **AgentController.java** - ReAct Agent 接口
3. **RagController.java** - RAG 文档管理接口
4. **RagChatController.java** - RAG 对话接口
5. **PseController.java** - PSE 协作接口
6. **LongTaskController.java** - 长时任务接口
7. **MemoryController.java** - 长期记忆管理接口
8. **ModelsController.java** - 模型列表接口
9. **ToolsController.java** - 工具列表接口

---

## 🔧 工具类列表

1. **CalculatorTool.java** - 计算器工具
2. **DateTimeTool.java** - 日期时间工具
3. **StockTool.java** - 股票行情工具
4. **ExchangeRateTool.java** - 汇率工具
5. **CodeExecutionTool.java** - 代码执行工具
6. **SkillRunTool.java** - 技能加载工具
7. **RagSearchTool.java** - 知识库检索工具
8. **ToolCallRecorder.java** - 工具调用记录器

---

## 🔌 API 端点列表

### 对话接口
- `GET /chat` - 非流式对话
- `GET /chat/stream` - 流式对话
- `GET /chat/agent` - 基础 Agent
- `GET /chat/agent/react` - ReAct Agent
- `GET /chat/agent/react/stream` - ReAct Agent 流式
- `GET /chat/pse/stream` - PSE 协作流式
- `GET /chat/rag` - RAG 对话

### RAG 接口
- `POST /rag/documents` - 上传文档
- `GET /rag/documents` - 列出文档
- `DELETE /rag/documents/{docId}` - 删除文档
- `GET /rag/stats` - 文档统计
- `GET /rag/search` - 语义检索

### 长时任务接口
- `GET /api/tasks` - 任务列表
- `POST /api/tasks` - 创建任务
- `GET /api/tasks/{id}` - 任务详情
- `POST /api/tasks/{id}/restart` - 续跑任务
- `DELETE /api/tasks/{id}` - 删除任务

### 记忆接口
- `GET /api/memory` - 记忆列表
- `POST /api/memory/toggle` - 开关记忆
- `POST /api/memory/clear` - 清空记忆
- `DELETE /api/memory/{id}` - 删除单条记忆

### 其他接口
- `GET /models` - 模型列表
- `GET /api/examples` - 示例任务
- `GET /health` - 健康检查

---

## 📁 前端组件列表

1. **App.vue** - 主组件（~350 行）
2. **MessageList.vue** - 消息列表组件
3. **InputArea.vue** - 输入区域组件
4. **RuntimePanel.vue** - Runtime 面板组件
5. **RagPanel.vue** - RAG 面板组件
6. **LongTaskPanel.vue** - 长时任务面板组件
7. **ModelSettings.vue** - 模型设置组件
8. **SessionList.vue** - 会话列表组件

---

## 🚀 快速启动

```bash
# 1. 配置 API Key
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY

# 2. 启动开发模式
make dev

# 3. 访问应用
# 前端: http://localhost:5174
# 后端: http://localhost:8080
```

---

## 📝 常用命令

| 命令 | 说明 |
|------|------|
| `make dev` | 启动前后端开发模式 |
| `make run` | 前台启动后端 |
| `make run-bg` | 后台启动后端 |
| `make stop` | 停止所有服务 |
| `make compile` | 编译后端 |
| `make package` | 打包（跳过测试） |
| `make test` | 运行测试 |
| `make health` | 健康检查 |

---

## 🔐 安全特性

- **Docker 沙箱隔离**：代码执行在隔离容器中，禁用网络、只读文件系统
- **API Key 保护**：密钥存储在 `.env`（已 gitignore），不提交到版本控制
- **限流保护**：Agnes 免费 API 自动限流（6秒间隔）+ 429 指数退避重试
- **上下文防护**：工具输出截断 8000 字符，消息历史超 40000 字符自动裁剪

---

## 📋 待办事项

详见 [TODO.md](./TODO.md)，当前优先级：
1. 多轮对话上下文（已完成基础实现）
2. 停止生成 + 重新生成（已完成）
3. 消息历史持久化（使用 localStorage）
4. 全局异常处理（已完成 GlobalExceptionHandler）

---

## 📊 项目亮点

### 1. ReAct Agent 多轮推理
- LLM 自动识别意图调用工具
- 思考→行动→观察循环
- SSE 流式输出，实时展示工具调用过程

### 2. PSE 三角色协作
- Planner：任务分解规划
- Specialist：并行执行子任务
- Evaluator：独立评审验收
- 支持依赖分层并行执行

### 3. RAG 知识库
- 支持 PDF/TXT/MD/JSON 等格式
- 智能文本切分（段落/Markdown/Token）
- DashScope Embedding + Rerank
- 向量库持久化

### 4. Docker 代码沙箱
- 支持 8 种语言：Python/JS/Shell/Java/Go/Rust/C/C++
- 安全隔离：禁用网络、只读文件系统、资源限制
- 超时自动 kill、输出截断

### 5. 长期记忆
- 自动从对话抽取用户偏好/事实/目标
- SQLite 持久化，跨会话注入
- 信号词预过滤控制成本

### 6. 长时任务
- 后台线程池执行，不受 HTTP 超时限制
- SQLite 持久化，重启后自动恢复
- Token 独立统计、可中断可续跑

---

*报告生成时间：2026-09-04*
