# spring-harness

A full-stack AI Agent development framework based on **Spring AI Alibaba** (Alibaba Cloud Bailian / Tongyi Qianwen DashScope), with direct access in China and no proxy required.

> English | [中文文档](./README.zh.md)

## Features

- **Five Interaction Modes**: Chat / RAG Knowledge Base / ReAct Agent / PSE Collaboration / Long-running Tasks
- **ReAct Agent**: LLM automatically identifies intent and calls tools, SSE streaming output, complete tool call process display
- **PSE Collaboration Orchestration**: Planner → Specialist → Evaluator three-role pipeline, supports subtask parallelism, failure retry, and overall review
- **RAG Knowledge Base**: Upload documents (PDF/TXT/MD/JSON/Java/Python, etc.) → chunking & vectorization → Rerank precision retrieval, integrated with Agent / PSE / Long-running Tasks
- **7 Local Tools**: Calculator, Current Time, Stock Real-time Quotes, Exchange Rate, Code Sandbox, Skill Loading, Knowledge Base Retrieval
- **MCP Integration**: filesystem + portfolio-check + pse-review (investment data pipeline)
- **Skills**: Load resolve-skills library (code-review, weekly-investment, etc.), aligned with Claude Code / Codex open standards
- **Docker Code Sandbox**: Isolated execution of **8 languages** (python / javascript / shell / java / go / rust / c / cpp)
- **Long-running Tasks**: SQLite persistence, token consumption statistics, execution logs, interruptible and resumable
- **Long-term Memory (Memory)**: Automatically extract user preferences/facts/goals from conversations → store in SQLite → inject into System prompt across sessions and modes (Plan B session window memory optional)
- **Dynamic Example Tasks**: Example questions in input box / long-running tasks are generated in real-time based on "current mode + environment capabilities (local tools / MCP / sandbox languages / knowledge base / memory / Skills)", refreshable with one click
- **Agnes Model Integration**: Free quota, automatic rate limiting (switchable to DeepSeek / DashScope multi-model)

## Tech Stack

| Component | Version | Description |
| --- | --- | --- |
| Java | 17 | Backend runtime |
| Spring Boot | 3.5.16 | Backend web framework |
| Spring AI Alibaba | 1.1.2.3 | DashScope starter, with Spring AI version management |
| Vue | 3.5.x | Frontend framework |
| Vite | 5.4.x | Frontend build tool + dev server |
| Build Tools | Maven 3.9+ / npm 10+ | Backend Maven, frontend npm |

> Note: Spring AI Alibaba 2.0.x requires Spring Boot 4. This example uses the mature and stable combination of **1.1.x + Boot 3.5**.

## Directory Structure

```
spring-harness
├── .env                  # Local configuration (contains secrets, gitignored)
├── .env.example          # Configuration template (committable)
├── Makefile              # Common command wrappers
├── pom.xml
├── frontend/             # Frontend (Vite + Vue 3)
│   ├── package.json
│   ├── vite.config.js    # Dev proxy: /api, /chat, etc. → http://localhost:8080
│   ├── index.html
│   └── src/
│       ├── main.js
│       ├── App.vue       # Five-mode switching (Chat/RAG/ReAct/PSE/Long Tasks)
│       └── components/   # MessageList / InputArea / RuntimePanel / LongTaskPanel
└── src/main/
    ├── java/com/example/springharness/
    │   ├── controller/   # Chat / Agent / Rag / Pse / LongTask / Tools / Models
    │   ├── agent/        # ReActAgentService (ReAct mode)
    │   ├── pse/          # PSE collaboration (Planner/Specialist/Evaluator/Orchestrator/Soul/TokenUsage)
    │   ├── task/         # Long-running tasks (thread pool + SQLite)
    │   ├── rag/          # RAG (vector store + chunking + Rerank)
    │   ├── sandbox/      # DockerSandboxExecutor (8-language sandbox)
    │   ├── service/      # Prompt / MultiModel / Skill / AgnesRateLimiter
    │   ├── tool/         # 7 local tools (calculator/query_stock/execute_code/skill_run/search_knowledge...)
    │   └── config/       # ToolConfig (tool registration)
    └── resources/application.yml
```

## Quick Start

### 1. Get API Key

Go to [Alibaba Cloud Bailian Console](https://bailian.console.aliyun.com/) → API-KEY Management, create and copy the Key.

### 2. Configure .env

```bash
cd work/spring/spring-harness
cp .env.example .env
# Edit .env, fill in DASHSCOPE_API_KEY
```

`.env` is already in `.gitignore`, so secrets won't be committed. You can also override with environment variable `export DASHSCOPE_API_KEY=sk-xxx`.

### 3. Start

```bash
# Dev mode: start both backend (8080) and frontend (5174) simultaneously, auto-install frontend dependencies
make dev

# Or start separately
make run-bg          # Backend in background
make frontend-dev    # Frontend dev server
```

After startup, visit **http://localhost:5174** to open the chat interface.
The frontend proxies `/chat` requests to the backend `http://localhost:8080` through Vite dev proxy.

### 4. Call API

```bash
# Non-streaming: return complete answer at once
curl "http://localhost:8080/chat?message=Introduce%20Spring%20AI%20Alibaba%20in%20one%20sentence"

# Streaming (SSE): return word by word
curl -N "http://localhost:8080/chat/stream?message=Tell%20a%20programmer%20joke"
```

## Makefile Commands

| Command | Description |
| --- | --- |
| `make help` | Show all commands (default) |
| `make run` | Start application in foreground |
| `make run-bg` | Start in background, logs written to app.log |
| `make stop` | Stop background application |
| `make restart` | Stop and restart |
| `make compile` | Compile |
| `make package` | Package (skip tests) |
| `make test` | Run tests |
| `make clean` | Clean build artifacts |
| `make health` | Health check (call /chat) |

## API Reference

| Method | Path | Description |
| --- | --- | --- |
| GET | `/chat?message=xxx` | Non-streaming, return complete answer |
| GET | `/chat/stream?message=xxx` | Streaming, SSE word by word |
| GET | `/chat/agent?message=xxx` | Agent mode, automatic tool calling, return answer + tool call process |
| GET | `/chat/rag?message=xxx` | RAG mode, retrieval-augmented answer based on knowledge base |
| POST | `/rag/documents` | Upload document (multipart/form-data, field name file) |
| GET | `/rag/documents` | List all indexed documents |
| DELETE | `/rag/documents/{docId}` | Delete document and all its vector chunks |
| GET | `/rag/stats` | Document statistics |
| GET | `/rag/search?query=xxx&topK=5` | Semantic search (for debugging) |
| GET | `/api/memory` | Long-term memory list + toggle status |
| POST | `/api/memory/toggle` | Enable/disable memory `{"enabled": true}` |
| POST | `/api/memory/clear` | Clear all memories |
| DELETE | `/api/memory/{id}` | Delete single memory |
| GET | `/api/examples?mode=chat\|agent\|pse\|rag\|longtask` | Dynamically generate example tasks based on mode + current environment capabilities |

### Long-term Memory (Memory)

Automatically extract **stable user preferences / facts / goals** from conversations (e.g., "I prefer Chinese", "I live in Shanghai"), store in SQLite, retrieve based on current question in subsequent conversations / ReAct / PSE, and inject into System prompt, achieving cross-session memory.

- **Signal word pre-filtering** before extraction (only call LLM when hitting "I prefer/I am/I live in/my goal", etc.), controlling cost and rate limiting
- Memory categorized by `preference / fact / goal`, keyword LIKE search + access frequency sorting, take top `MEMORY_TOP_K` items for injection
- Management: Right Runtime panel → Memory card to view / toggle / clear / delete single item

**Configuration** (`.env`):

| Variable | Default | Description |
| --- | --- | --- |
| `MEMORY_ENABLED` | `true` | Enable long-term memory |
| `MEMORY_EXTRACT_ENABLED` | `true` | Enable extraction (call LLM only when signal words hit) |
| `MEMORY_TOP_K` | `5` | Number of memories injected each time |
| `MEMORY_MAX_ITEMS` | `200` | Memory upper limit, delete oldest when exceeded |
| `MEMORY_MODEL` | empty | Extraction model, leave empty to follow main model |
| `MEMORY_CHAT_ENABLED` | `false` | Session window memory (Plan B): maintain multi-turn window by conversationId on backend |
| `MEMORY_CHAT_WINDOW` | `20` | Session window size |

> Session window memory (Plan B) is disabled by default: the current frontend already passes back history through `messages` parameter. Plan B is for scenarios requiring backend unified window management (`/chat` and `/chat/stream` pass `conversationId` to take effect).

### Agent Mode Example

```bash
curl "http://localhost:8080/chat/agent?message=What%20is%20123%20times%20456"
```

Response:
```json
{
  "answer": "123 times 456 equals 56088.",
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

### Built-in Tools

**7 Local Tools** (callable by Agent / PSE / Long-running Tasks):

| Tool Name | Function | Trigger Example |
| --- | --- | --- |
| `calculator` | Arithmetic operations | "What is 123 times 456" |
| `get_datetime` | Current date and time | "What time is it now", "What day is today" |
| `query_stock` | Stock real-time quotes | "How much is Apple stock", "AAPL price" |
| `query_exchange_rate` | Exchange rate conversion | "USD to CNY exchange rate" |
| `execute_code` | Docker sandbox execution of 8 languages code | "Calculate Fibonacci 20th term with Python" |
| `skill_run` | Load skill instructions (code-review / weekly-investment, etc.) | "Review XX.java with code review skill" |
| `search_knowledge` | RAG knowledge base retrieval (Rerank precision) | "What vector databases are mentioned in the document" |

**16 MCP Tools**: `filesystem` (14 file operations) + `portfolio-check` (investment data health check) + `pse-review` (deep investment weekly report).

> Stock quotes (Yahoo Finance / Finnhub real-time, with rate limiting and retry), exchange rates from public APIs. Sensitive API Keys always go in `.env` (gitignored), do not write into conversations or code.

### Tool Calling Principle

1. User question → LLM determines whether tool calling is needed
2. LLM returns tool name + parameters (JSON)
3. Spring AI's `ToolCallingAdvisor` automatically executes corresponding `FunctionToolCallback`
4. Tool results fed back to LLM, LLM generates natural language answer based on results
5. `ToolCallRecorder` (ThreadLocal) records input, output, duration of each tool call

### RAG Knowledge Base Mode

RAG (Retrieval-Augmented Generation): After uploading documents, LLM answers questions based on document content, avoiding hallucination.

**Usage Flow**:
1. Switch to "RAG Knowledge Base" mode in frontend, click "Upload Document" to select file (supports PDF, TXT, MD, JSON, Java, Python and other text formats)
2. Backend automatically completes: document loading → text chunking (TokenTextSplitter) → vectorization (DashScope text-embedding-v2) → store in vector store (SimpleVectorStore)
3. Ask questions in input box, LLM automatically retrieves relevant document fragments and answers based on content

**CLI Example**:
```bash
# Upload document
curl -X POST -F "file=@knowledge.pdf" http://localhost:8080/rag/documents

# List documents
curl http://localhost:8080/rag/documents

# RAG Q&A
curl "http://localhost:8080/chat/rag?message=What%20vector%20databases%20are%20mentioned%20in%20the%20document"
```

**RAG Tech Stack**:
| Component | Implementation | Description |
| --- | --- | --- |
| Vector Store | `SimpleVectorStore` | In-memory vector store, for dev demo; production can switch to Redis/PGVector/Milvus |
| Embedding | DashScope `qwen3.7-text-embedding` | 1 million Token free, 1024 dimensions |
| Rerank | DashScope `qwen3.7-text-rerank` | Precision ranking after RAG retrieval (with free quota) |
| Text Chunking | `ParagraphTextSplitter` / `MarkdownTextSplitter` / `TokenTextSplitter` | DOC/PDF by paragraph, Markdown by structure, others by token |
| Retrieval Augmentation | `RetrievalAugmentationAdvisor` | Spring AI 1.1 RAG Advisor, auto retrieval + prompt injection |
| Document Loading | `PagePdfDocumentReader` + plain text reading | PDF read by page, other formats read text directly |

> **RAG Generalization**: `search_knowledge` tool is registered to Agent / PSE / Long-running Tasks, all three modes can retrieve knowledge base. Switching vector store only requires replacing `VectorStore` Bean in `RagConfig`, RagService and Controller need no changes.

## Docker Code Sandbox

`execute_code` tool executes code in isolated Docker containers, supporting **8 languages**:

| Language | Image | Description |
| --- | --- | --- |
| python | python:3.11-slim | Interpreted execution |
| javascript | node:20-slim | Interpreted execution |
| shell | alpine:3.19 | Interpreted execution |
| java | eclipse-temurin:17-jdk | `java Main.java` source mode |
| go | golang:1.22-alpine | `go run` (cache redirected to /tmp) |
| rust | rust:1.75-alpine | `rustc` compile then run |
| c / cpp | sandbox-gcc:alpine (self-built) | `gcc` / `g++` compile then run |

**Security Isolation**: Each execution in independent container (`--rm` auto-destroy), `--network none` disable network, `--read-only` read-only root filesystem (only /tmp writable and executable), memory 512MB / 1 core / 100 process limit, timeout auto-kill, output 100KB truncation.

Sandbox toggle in `.env`: `SANDBOX_ENABLED=true`. c/cpp uses self-built image `docker/sandbox-gcc.Dockerfile` (alpine + gcc/g++, 209MB, musl libc fully compatible with standard programs).

## PSE Collaboration and Long-running Tasks

- **PSE Collaboration**: Planner decomposes tasks into subtasks → Specialist executes in parallel (can call tools) → Evaluator reviews and accepts, failure auto-retry; supports overall review and final delivery. `PSE_TIMEOUT_SECONDS` controls overall timeout (default 90s, .env set to 600s).
- **Long-running Tasks**: Background thread pool execution (can run multiple tasks concurrently), SQLite (`data/tasks.db`) persistence, auto-recover after restart; records token consumption, execution logs, execution process (copyable error report), execution result Markdown rendering.

## Common Models

| Model Name | Positioning |
| --- | --- |
| `qwen-plus` | DashScope balanced, default recommended |
| `agnes-2.0-flash` | Agnes free model (default, auto rate limiting) |
| `glm-5.2` | Zhipu model (via DashScope gateway) |
| `deepseek` | DeepSeek (paid, stable) |

Models switched through `.env`'s `DASHSCOPE_MODEL` / Agnes configuration. Full list see [Bailian Model List](https://help.aliyun.com/zh/model-studio/models).

## FAQ

- **Startup error related to API Key**: `DASHSCOPE_API_KEY` not configured, configure according to steps above.
- **Error 401 / InvalidApiKey**: Key error or expired, check Bailian console.
- **Error 400 / model not found**: `DASHSCOPE_MODEL` doesn't match models activated on account, switch to an activated model.
- **Want to switch back to OpenAI compatible protocol**: Replace dependency with `spring-ai-starter-model-openai`, configure `spring.ai.openai.base-url`, Controller code needs no changes (Spring AI abstraction layer shields differences).

## Next Steps for Extension

- Chat memory (`ChatMemory` / vector store)
- Multi-turn conversation (`Advisor` + Message History)
- RAG (`VectorStore` + `QuestionAnswerAdvisor`, supports PGVector / Redis / Milvus and 25+ other vector stores)
- Structured output (Bean output / JSON Schema constraints)
- Agent Framework (Spring AI Alibaba `ReactAgent` + Graph runtime, multi-Agent orchestration)
- MCP integration (Spring AI 1.1 natively supports Model Context Protocol, can encapsulate tools as MCP Server)
- Multimodal (image understanding `qwen-vl`, speech recognition/synthesis)
