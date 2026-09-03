# spring-harness

基于 **Spring AI Alibaba**（阿里云百炼 / 通义千问 DashScope）的全栈 AI Agent 开发框架，集成 ReAct Agent、PSE 多 Agent 协作、RAG 知识库、MCP、Skills、Docker 代码沙箱等完整能力。国内直连，无需代理。

## 功能特性

- **普通对话模式**：非流式 + SSE 流式输出
- **Agent 模式**：LLM 自动识别意图并调用工具，返回最终回答 + 完整工具调用过程
- **内置 3 个工具**：计算器、当前时间、股票行情（Alpha Vantage）
- **RAG 知识库模式**：上传文档（PDF/TXT/MD 等）→ 自动切分向量化 → 基于知识库内容问答
- **前端三模式切换**：Vue 3 聊天界面，可实时查看工具调用过程和文档管理

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
│   ├── vite.config.js    # 开发代理：/chat → http://localhost:8080
│   ├── index.html
│   └── src/
│       ├── main.js
│       ├── App.vue       # 聊天界面（普通对话/Agent/RAG 三模式切换）
│       └── style.css
└── src/main/
    ├── java/com/example/springaidemo/
    │   ├── SpringHarnessApplication.java
    │   ├── config/
    │   │   └── ToolConfig.java        # 工具注册（FunctionToolCallback）
    │   ├── controller/
    │   │   ├── ChatController.java       # 普通对话（/chat, /chat/stream）
    │   │   ├── AgentController.java      # Agent 模式（/chat/agent，工具调用）
    │   │   ├── RagController.java        # RAG 文档管理（/rag/documents）
    │   │   └── RagChatController.java    # RAG 增强对话（/chat/rag）
    │   ├── dto/AgentResponse.java        # Agent 响应 DTO
    │   ├── rag/
    │   │   ├── RagConfig.java            # RAG 配置（VectorStore + TextSplitter）
    │   │   ├── RagService.java           # RAG 服务（文档加载/切分/向量化/检索）
    │   │   └── DocumentInfo.java         # 文档元信息 DTO
    │   └── tool/
    │       ├── ToolCallRecorder.java     # 工具调用记录器（ThreadLocal）
    │       ├── CalculatorTool.java       # 计算器工具
    │       ├── DateTimeTool.java         # 当前时间工具
    │       └── StockTool.java            # 股票查询工具（Alpha Vantage）
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

| 工具名 | 功能 | 触发示例 |
| --- | --- | --- |
| `calculator` | 加减乘除 | "123乘以456等于多少" |
| `get_datetime` | 当前日期时间 | "现在几点了"、"今天星期几" |
| `query_stock` | 股票实时行情 | "苹果股票多少钱"、"AAPL 股价" |

> 股票查询需要配置 `ALPHAVANTAGE_API_KEY`（免费获取：https://www.alphavantage.co/support/#api-key）。未配置时 LLM 仍会调用工具，但工具会返回提示信息。

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
| Embedding | DashScope `text-embedding-v2` | 阿里云文本向量模型 |
| 文本切分 | `TokenTextSplitter` | 按 token 数切分，默认约 800 token/chunk |
| 检索增强 | `RetrievalAugmentationAdvisor` | Spring AI 1.1 RAG Advisor，自动检索+注入 Prompt |
| 文档加载 | `PagePdfDocumentReader` + 纯文本读取 | PDF 按页读取，其他格式直接读取文本 |

> **切换向量库**：仅需替换 `RagConfig` 中的 `VectorStore` Bean（如改为 RedisVectorStore），添加对应依赖和配置即可，RagService 和 Controller 无需改动。

## 常用模型

| 模型名 | 定位 |
| --- | --- |
| `qwen-turbo` | 轻量快速，成本低 |
| `qwen-plus` | 均衡型，默认推荐 |
| `qwen-max` | 旗舰能力 |
| `qwen3-coder-plus` | 代码生成 |

完整列表见 [百炼模型列表](https://help.aliyun.com/zh/model-studio/models)。

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
