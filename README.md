# spring-harness

A full-stack AI Agent development framework based on **Spring AI Alibaba** (Alibaba Cloud Bailian / Tongyi Qianwen DashScope), with direct access in China and no proxy required.

> English | [中文文档](./README.zh.md)

## Features

- **Five Interaction Modes**: Chat / RAG Knowledge Base / ReAct Agent / PSE Collaboration / Long-running Tasks
- **ReAct Agent**: LLM automatically identifies intent and calls tools, SSE streaming output, complete tool call process display
- **PSE Collaboration Orchestration**: Planner → Specialist → Evaluator three-role pipeline, supports subtask parallelism, failure retry, and overall review; Soul-based role definitions (local configurable)
- **RAG Knowledge Base**: Upload documents (PDF/TXT/MD/JSON/Java/Python, etc.) → chunking & vectorization → Rerank precision retrieval, integrated with Agent / PSE / Long-running Tasks (generalized `search_knowledge` tool)
- **8 Local Tools**: Calculator, Current Time, Stock Real-time Quotes, Exchange Rate, Code Sandbox, File Read, Skill Loading, Knowledge Base Retrieval
- **MCP Integration**: filesystem (14 tools) + portfolio-check + pse-review (investment data pipeline); tool output auto-sanitized for sensitive credentials
- **Skills**: Load resolve-skills library (code-review, weekly-investment, etc.), aligned with Claude Code / Codex open standards; managed as git submodule
- **Docker Code Sandbox**: Isolated execution of **8 languages** (python / javascript / shell / java / go / rust / c / cpp), with network isolation, read-only filesystem, memory/CPU limits
- **Long-running Tasks**: SQLite persistence, token consumption statistics, execution logs, interruptible and resumable; auto-cleanup of expired tasks (privacy protection)
- **Long-term Memory (Memory)**: Automatically extract user preferences/facts/goals from conversations → store in SQLite → inject into System prompt across sessions and modes; optional AES-256-GCM encryption; Plan B session window memory optional
- **Dynamic Example Tasks**: Example questions in input box / long-running tasks are generated in real-time based on "current mode + environment capabilities (local tools / MCP / sandbox languages / knowledge base / memory / Skills)", refreshable with one click
- **Agnes Model Integration**: Free quota, automatic rate limiting (configurable interval/retries/backoff); switchable to DeepSeek / DashScope multi-model
- **Context Protection**: Lightweight context folding (preserve tool name + params + conclusion skeleton) by default; optional LLM semantic summary compression when context limit exceeded
- **Privacy & Compliance**: XSS protection (DOMPurify sanitization for all Markdown output), Actuator info leak prevention, MCP tool output sanitization, API Key never exposed to frontend, file upload validation (extension/MIME/magic number/printable char ratio)
- **Frontend**: Vite + Vue 3, port 5174 fixed, code splitting for large chunks, hover-only scrollbars, Markdown rendering with code highlighting, full-screen result viewer

## Tech Stack

| Component | Version | Description |
| --- | --- | --- |
| Java | 17 | Backend runtime |
| Spring Boot | 3.5.16 | Backend web framework |
| Spring AI Alibaba | 1.1.2.3 | DashScope starter, with Spring AI version management |
| Spring AI MCP | 0.17.0 | Model Context Protocol client |
| SQLite | 3.x | Long-running tasks + Memory persistence (JDBC) |
| Vue | 3.5.x | Frontend framework |
| Vite | 5.4.x | Frontend build tool + dev server (port 5174) |
| marked + DOMPurify | latest | Markdown rendering + XSS sanitization |
| Build Tools | Maven 3.9+ / npm 10+ | Backend Maven, frontend npm |

> Note: Spring AI Alibaba 2.0.x requires Spring Boot 4. This example uses the mature and stable combination of **1.1.x + Boot 3.5**.

## Directory Structure

```
spring-harness
├── .env                  # Local configuration (contains secrets, gitignored)
├── .env.example          # Configuration template (committable)
├── .gitmodules           # resolve-skills as git submodule
├── Makefile              # Common command wrappers
├── pom.xml
├── README.md             # English documentation
├── README.zh.md          # Chinese documentation
├── TODO.md               # TODO items (including privacy & compliance backlog)
├── docker/
│   └── sandbox-gcc.Dockerfile  # Self-built GCC sandbox image (alpine + gcc/g++)
├── frontend/             # Frontend (Vite + Vue 3)
│   ├── package.json
│   ├── vite.config.js    # Dev proxy: /api, /chat, etc. → http://localhost:8080; port 5174 fixed
│   ├── index.html
│   └── src/
│       ├── main.js
│       ├── App.vue       # Five-mode switching (Chat/RAG/ReAct/PSE/Long Tasks)
│       ├── utils/markdown.js  # Markdown rendering + DOMPurify sanitization
│       └── components/   # MessageList / MessageItem / InputArea / RuntimePanel
│           ├── CollapsibleCard.vue   # Collapsible card (Tools/MCP/Skills/Knowledge/Memory)
│           ├── InputArea.vue          # Input area with dynamic example questions
│           ├── LongTaskPanel.vue      # Long-running task list panel
│           ├── MessageItem.vue        # Single message (user/AI) with copy button
│           ├── MessageList.vue        # Message list
│           ├── ModelManager.vue       # Model management (add/delete models)
│           ├── ModelSettings.vue      # Model settings dialog
│           ├── PrivacyPanel.vue       # Privacy & compliance panel
│           ├── RagPanel.vue           # RAG knowledge base panel (upload/list/preview)
│           ├── RuntimePanel.vue       # Right runtime panel (Tools/MCP/Skills/Knowledge/Memory/Service)
│           ├── SessionList.vue        # Chat session list
│           ├── TaskDetail.vue         # Long-running task detail (execution process + result)
│           ├── TaskExamples.vue       # Dynamic example tasks for long-running tasks
│           ├── TaskForm.vue           # Long-running task creation form
│           └── ToolCallCard.vue       # Tool call display card
├── resolve-skills/       # Git submodule: skills library (code-review, weekly-investment, etc.)
└── src/main/
    ├── java/com/example/springharness/
    │   ├── controller/   # 13 controllers: Chat / Agent / Rag / RagChat / Pse / LongTask
    │   │                 #   / Tools / Models / Memory / Example / Privacy
    │   ├── agent/        # ReActAgentService (ReAct mode, streaming + tool calling)
    │   ├── pse/          # PSE collaboration (20+ classes)
    │   │   ├── PlannerAgent.java       # Task decomposition
    │   │   ├── SpecialistAgent.java    # Task execution (tool calling, max 15 iterations)
    │   │   ├── EvaluatorAgent.java     # Task review & acceptance
    │   │   ├── PseOrchestrator.java    # Orchestration (parallel/retry/overall review)
    │   │   ├── SoulService.java        # Soul-based role definitions (local configurable)
    │   │   ├── SkillRegistry.java      # Skill loading & management
    │   │   ├── LocalToolProvider.java  # Local tool provider
    │   │   ├── McpToolProvider.java    # MCP tool provider (auto-fetch from MCP servers)
    │   │   ├── TokenUsageTracker.java  # Token consumption tracking
    │   │   └── ToolDescriptionService.java  # Unified tool description for prompts
    │   ├── task/         # Long-running tasks
    │   │   ├── TaskManager.java           # Task execution manager (thread pool)
    │   │   ├── LongTaskStore.java         # SQLite persistence
    │   │   └── TaskCleanupScheduler.java  # Auto-cleanup of expired tasks
    │   ├── rag/          # RAG (vector store + chunking + Rerank)
    │   ├── sandbox/      # DockerSandboxExecutor (8-language sandbox)
    │   ├── memory/       # Long-term memory
    │   │   ├── MemoryService.java       # Memory retrieval & injection
    │   │   ├── MemoryExtractor.java     # LLM-based extraction (signal word pre-filter)
    │   │   ├── MemoryStore.java         # SQLite persistence
    │   │   ├── MemoryEncryptor.java     # AES-256-GCM encryption (optional)
    │   │   └── ChatMemoryService.java   # Plan B session window memory
    │   ├── service/      # Prompt / MultiModel / Skill / AgnesRateLimiter / ContextGuard
    │   ├── tool/         # 8 local tools
    │   │   ├── CalculatorTool.java
    │   │   ├── CodeExecutionTool.java
    │   │   ├── DateTimeTool.java
    │   │   ├── ExchangeRateTool.java
    │   │   ├── FileReadTool.java
    │   │   ├── RagSearchTool.java
    │   │   ├── SkillRunTool.java
    │   │   ├── StockTool.java
    │   │   └── ToolCallRecorder.java    # ThreadLocal tool call recording
    │   ├── config/       # ToolConfig (tool registration) / CorsConfig / WebConfig
    │   └── util/         # ErrorSanitizer (error + content sanitization)
    └── resources/
        ├── application.yml
        └── prompts/      # System prompt templates (extracted for unified maintenance)
```

## Quick Start

### 1. Get API Key

Go to [Alibaba Cloud Bailian Console](https://bailian.console.aliyun.com/) → API-KEY Management, create and copy the Key.

### 2. Configure .env

```bash
cd work/spring/spring-harness
cp .env.example .env
# Edit .env, fill in DASHSCOPE_API_KEY (and optional AGNES_API_KEY, ALPHAVANTAGE_API_KEY, FINNHUB_API_KEY)
```

`.env` is already in `.gitignore`, so secrets won't be committed. You can also override with environment variable `export DASHSCOPE_API_KEY=sk-xxx`.

### 3. Initialize git submodule (Skills)

```bash
git submodule update --init --recursive
```

This loads the `resolve-skills` library (code-review, weekly-investment, etc.) into `./resolve-skills/`.

### 4. Start

```bash
# Dev mode: kill old processes, compile backend, start backend (wait for ready), then start frontend (5174)
make dev

# Or start separately
make run-bg          # Backend in background (port 8080)
make frontend-dev    # Frontend dev server (port 5174)
```

After startup, visit **http://localhost:5174** to open the chat interface.
The frontend proxies `/chat`, `/api`, `/rag` requests to the backend `http://localhost:8080` through Vite dev proxy.

> **Note**: Start backend first, then frontend. `make dev` handles this automatically (waits for backend health check before starting frontend).

### 5. Call API

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
| `make dev` | Full dev mode: kill old processes → compile → start backend → wait for ready → start frontend |
| `make run` | Start application in foreground |
| `make run-bg` | Start in background, logs written to app.log |
| `make stop` | Stop background application |
| `make restart` | Stop and restart |
| `make compile` | Compile |
| `make package` | Package (skip tests) |
| `make test` | Run tests |
| `make clean` | Clean build artifacts |
| `make health` | Health check (call /chat) |
| `make frontend-dev` | Start frontend dev server only |
| `make frontend-build` | Build frontend for production |

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
| GET | `/api/tools` | List all available tools (local + MCP) with descriptions |
| GET | `/api/mcp/status` | MCP server connection status |
| GET | `/models` | List all configured models |
| POST | `/models` | Add a new model configuration |
| DELETE | `/models/{id}` | Delete a model configuration |
| GET | `/api/privacy/status` | Privacy & compliance feature status |
| GET | `/api/tasks` | List long-running tasks |
| POST | `/api/tasks` | Create a new long-running task |
| GET | `/api/tasks/{id}` | Get task detail (execution process + result + logs) |
| POST | `/api/tasks/{id}/cancel` | Cancel a running task |
| POST | `/api/tasks/{id}/resume` | Resume a paused/interrupted task |
| GET | `/api/tasks/{id}/logs` | Get task execution logs |

### Long-term Memory (Memory)

Automatically extract **stable user preferences / facts / goals** from conversations (e.g., "I prefer Chinese", "I live in Shanghai"), store in SQLite, retrieve based on current question in subsequent conversations / ReAct / PSE, and inject into System prompt, achieving cross-session memory.

- **Signal word pre-filtering** before extraction (only call LLM when hitting "I prefer/I am/I live in/my goal", etc.), controlling cost and rate limiting
- Memory categorized by `preference / fact / goal`, keyword LIKE search + access frequency sorting, take top `MEMORY_TOP_K` items for injection
- **Optional AES-256-GCM encryption**: when enabled, memory content is encrypted at rest (prevents database file leak from exposing privacy); SQL LIKE degrades to in-memory matching
- Management: Right Runtime panel → Memory card to view / toggle / clear / delete single item

**Configuration** (`.env`):

| Variable | Default | Description |
| --- | --- | --- |
| `MEMORY_ENABLED` | `true` | Enable long-term memory |
| `MEMORY_EXTRACT_ENABLED` | `true` | Enable extraction (call LLM only when signal words hit) |
| `MEMORY_TOP_K` | `5` | Number of memories injected each time |
| `MEMORY_MAX_ITEMS` | `200` | Memory upper limit, delete oldest when exceeded |
| `MEMORY_MODEL` | empty | Extraction model, leave empty to follow main model |
| `MEMORY_CHAT_ENABLED` | `false` | Plan B session window memory: maintain multi-turn window by conversationId on backend |
| `MEMORY_CHAT_WINDOW` | `20` | Session window size |
| `MEMORY_ENCRYPT_ENABLED` | `false` | AES-256-GCM encryption for memory content at rest |
| `MEMORY_ENCRYPT_KEY` | empty | Encryption key (leave empty to use machine-feature-based default key) |

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

**8 Local Tools** (callable by Agent / PSE / Long-running Tasks):

| Tool Name | Function | Trigger Example |
| --- | --- | --- |
| `calculator` | Arithmetic operations | "What is 123 times 456" |
| `get_datetime` | Current date and time | "What time is it now", "What day is today" |
| `query_stock` | Stock real-time quotes (Yahoo Finance + Finnhub fallback, with rate limiting & retry) | "How much is Apple stock", "AAPL price" |
| `query_exchange_rate` | Exchange rate conversion | "USD to CNY exchange rate" |
| `execute_code` | Docker sandbox execution of 8 languages code | "Calculate Fibonacci 20th term with Python" |
| `read_file` | Read text file content (sandbox-isolated path) | "Read the config file" |
| `skill_run` | Load skill instructions (code-review / weekly-investment, etc.) | "Review XX.java with code review skill" |
| `search_knowledge` | RAG knowledge base retrieval (Rerank precision) | "What vector databases are mentioned in the document" |

**16+ MCP Tools**: `filesystem` (14 file operations: read_file, write_file, edit_file, list_directory, search_files, etc.) + `portfolio-check` (investment data health check) + `pse-review` (deep investment weekly report).

> Stock quotes (Yahoo Finance / Finnhub real-time, with rate limiting and retry), exchange rates from public APIs. Sensitive API Keys always go in `.env` (gitignored), do not write into conversations or code. MCP tool outputs are auto-sanitized to filter API Keys/tokens/secrets before returning to LLM or frontend.

### Tool Calling Principle

1. User question → LLM determines whether tool calling is needed
2. LLM returns tool name + parameters (JSON)
3. Spring AI's `ToolCallingAdvisor` automatically executes corresponding `FunctionToolCallback`
4. Tool results fed back to LLM, LLM generates natural language answer based on results
5. `ToolCallRecorder` (ThreadLocal) records input, output, duration of each tool call
6. Tool outputs are auto-sanitized via `ErrorSanitizer.sanitizeContent()` to filter sensitive credentials

### Context Protection

To prevent context window overflow (especially in ReAct/PSE with multiple tool calls):

- **Lightweight folding (default)**: When context approaches limit, oldest tool round-trips are folded to skeleton (tool name + params + conclusion), zero LLM cost
- **LLM semantic summary (optional)**: When `CONTEXT_SUMMARY_ENABLED=true`, use LLM to compress oldest tool round-trips into semantic summaries (smarter, but costs one extra LLM call per compression)

**Configuration** (`.env`):

| Variable | Default | Description |
| --- | --- | --- |
| `CONTEXT_SUMMARY_ENABLED` | `false` | Enable LLM semantic summary compression |
| `CONTEXT_SUMMARY_MAX_CHARS` | `4000` | Max chars for LLM semantic summary |

### RAG Knowledge Base Mode

RAG (Retrieval-Augmented Generation): After uploading documents, LLM answers questions based on document content, avoiding hallucination.

**Usage Flow**:
1. Switch to "RAG Knowledge Base" mode in frontend, click "Upload Document" to select file (supports PDF, TXT, MD, JSON, Java, Python and other text formats)
2. Backend automatically completes: document loading → text chunking (ParagraphTextSplitter for DOC/PDF, MarkdownTextSplitter for MD, TokenTextSplitter for others) → vectorization → store in vector store (SimpleVectorStore, persisted to disk)
3. Ask questions in input box, LLM automatically retrieves relevant document fragments and answers based on content
4. Document chunks are numbered for preview; vector store persists across restarts (no longer lost on restart)

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
| Vector Store | `SimpleVectorStore` (persisted) | In-memory vector store with disk persistence, for dev demo; production can switch to Redis/PGVector/Milvus |
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

**Configuration** (`.env`):

| Variable | Default | Description |
| --- | --- | --- |
| `SANDBOX_ENABLED` | `true` | Enable code execution sandbox |
| `SANDBOX_TIMEOUT` | `30` | Code execution timeout (seconds) |
| `SANDBOX_MEMORY_MB` | `512` | Container memory limit (MB) |
| `SANDBOX_CPUS` | `1` | Container CPU limit (cores) |
| `SANDBOX_MAX_OUTPUT_KB` | `100` | Max stdout/stderr size (KB), auto-truncate |

## PSE Collaboration and Long-running Tasks

### PSE Collaboration

- **Three-role pipeline**: Planner decomposes tasks into subtasks → Specialist executes in parallel (can call tools, max 15 iterations) → Evaluator reviews and accepts, failure auto-retry (max 3 retries per subtask)
- **Overall review**: After all subtasks complete, Evaluator does overall review; if failed, Planner may re-decompose
- **Soul-based roles**: Each role (Planner/Specialist/Evaluator) has a SOUL.md definition, configurable via `HARNESS_SOULS_DIR` (local path, defaults to resolve-skills/souls)
- **Parallel execution**: Subtasks execute in parallel when `PSE_PARALLEL_ENABLED=true`
- **Long-running tool support**: MCP request timeout set to 600s, Specialist system prompt includes long-running tool guidelines (don't retry aggressively, wait patiently)
- **Token tracking**: `TokenUsageTracker` records token consumption for each role

**Configuration** (`.env`):

| Variable | Default | Description |
| --- | --- | --- |
| `PSE_TIMEOUT_SECONDS` | `600` | PSE orchestration overall timeout (seconds) |
| `PSE_PARALLEL_ENABLED` | `true` | Enable parallel subtask execution |
| `HARNESS_SOULS_DIR` | empty | Soul definitions directory (local path, defaults to resolve-skills/souls) |

### Long-running Tasks

- **Background execution**: Thread pool (configurable size), can run multiple tasks concurrently
- **SQLite persistence**: `data/tasks.db`, auto-recover after restart; task state, execution process, result, logs all persisted
- **Token consumption**: Records input/output tokens for each LLM call in the task
- **Interrupt & resume**: Can cancel running tasks; interrupted tasks can be resumed from checkpoint
- **Execution logs**: Stored in `logs/tasks/`, viewable via API or UI
- **Error report copy**: One-click copy of structured error report (correct steps abbreviated, error steps detailed)
- **Auto-cleanup**: Expired completed tasks auto-deleted (privacy protection + prevent database growth)

**Configuration** (`.env`):

| Variable | Default | Description |
| --- | --- | --- |
| `TASK_DB_PATH` | `data/tasks.db` | SQLite task database path |
| `TASK_POOL_SIZE` | `4` | Long-running task thread pool size |
| `TASK_LOG_DIR` | `logs/tasks` | Task execution log directory |
| `TASK_RETENTION_DAYS` | `30` | Completed task retention days (auto-cleanup after) |
| `TASK_CLEANUP_ENABLED` | `true` | Enable scheduled cleanup (3 AM daily + startup) |

## Privacy & Compliance

Built-in privacy and compliance features:

| Feature | Implementation |
| --- | --- |
| **XSS Protection** | All Markdown output sanitized with DOMPurify (whitelist tags/attrs, forbid script/style/iframe, strip on* handlers, force noopener links) |
| **API Key Protection** | API Keys never returned to frontend; only stored in `.env` (gitignored); MCP tool outputs auto-sanitized to filter keys/tokens/secrets |
| **Actuator Info Leak Prevention** | `management.endpoint.health.show-details=never`, only returns UP/DOWN, no server config details |
| **File Upload Validation** | Extension whitelist, size limit, MIME type check, magic number check, printable char ratio check for text files |
| **CORS Security** | `CORS_ALLOWED_ORIGINS` defaults to `http://localhost:5174`, no wildcard; credentials configurable |
| **Memory Encryption** | Optional AES-256-GCM encryption for memory content at rest |
| **Task Data Retention** | Completed tasks auto-deleted after configurable retention period |
| **Sandbox Isolation** | Code execution in Docker containers with network isolation, read-only filesystem, resource limits |

> TODO items (CSRF protection, global rate limiting, audit logs, frontend localStorage encryption) are recorded in `TODO.md`.

## Common Models

| Model Name | Provider | Positioning |
| --- | --- | --- |
| `qwen-turbo` | DashScope | Fast, cost-effective |
| `qwen-plus` | DashScope | Balanced, default recommended |
| `qwen-max` | DashScope | High capability |
| `qwen3-coder-plus` | DashScope | Code generation specialized |
| `agnes-2.0-flash` | Agnes AI | Free model (default, auto rate limiting) |
| `glm-5.2` | Zhipu (via DashScope gateway) | Balanced |
| `deepseek` | DeepSeek | Paid, stable |

Models switched through `.env`'s `DASHSCOPE_MODEL` / Agnes configuration. Full list see [Bailian Model List](https://help.aliyun.com/zh/model-studio/models).

**Agnes Rate Limiting Configuration** (`.env`):

| Variable | Default | Description |
| --- | --- | --- |
| `AGNES_RATE_INTERVAL_MS` | `6000` | Minimum interval between Agnes API calls (ms) |
| `AGNES_RATE_MAX_RETRIES` | `3` | Max retries on 429 rate limit |
| `AGNES_RATE_BACKOFF_BASE_MS` | `10000` | Base backoff time for retries (ms) |
| `AGNES_MAX_TOKENS` | `8192` | Max output tokens for Agnes model |

## FAQ

- **Startup error related to API Key**: `DASHSCOPE_API_KEY` not configured, configure according to steps above.
- **Error 401 / InvalidApiKey**: Key error or expired, check Bailian console.
- **Error 400 / model not found**: `DASHSCOPE_MODEL` doesn't match models activated on account, switch to an activated model.
- **Agnes 429 rate limit**: Agnes free tier has rate limits; configure `AGNES_RATE_*` in `.env` to control call frequency, or switch to `qwen-plus` / `deepseek`.
- **MCP filesystem server not found**: Install with `npm i -g @modelcontextprotocol/server-filesystem`, or set `MCP_FS_COMMAND` to absolute path.
- **Docker sandbox not working**: Ensure Docker (OrbStack / Docker Desktop) is running; set `SANDBOX_ENABLED=true` in `.env`.
- **Context window overflow in ReAct/PSE**: Enable `CONTEXT_SUMMARY_ENABLED=true` for LLM semantic compression, or reduce tool call iterations.
- **Want to switch back to OpenAI compatible protocol**: Replace dependency with `spring-ai-starter-model-openai`, configure `spring.ai.openai.base-url`, Controller code needs no changes (Spring AI abstraction layer shields differences).

## Next Steps for Extension

> Features already implemented: Chat memory (Memory), multi-turn conversation, RAG knowledge base, ReAct Agent, PSE collaboration, MCP integration, Docker code sandbox, long-running tasks, dynamic example tasks, Agnes model integration, context protection, privacy & compliance (XSS/Actuator/MCP sanitization).

- **Structured output**: Bean output / JSON Schema constraints for LLM responses
- **Multimodal**: Image understanding (`qwen-vl`), speech recognition/synthesis
- **Production-grade vector store**: Switch from SimpleVectorStore to Redis / PGVector / Milvus (25+ vector stores supported by Spring AI)
- **Spring AI Alibaba ReactAgent + Graph runtime**: Native multi-Agent orchestration framework (currently using custom ReAct + PSE implementation)
- **MCP Server**: Encapsulate local tools as MCP Server for external consumption
- **CSRF protection & global rate limiting**: Recorded in TODO.md (medium priority)
- **Audit logs**: Audit trail for all tool calls and API access (recorded in TODO.md, low priority)
- **Frontend localStorage encryption**: Encrypt chat history stored in browser localStorage (recorded in TODO.md, low priority)
- **User authentication & multi-tenant**: Login, user isolation, role-based access control
- **Plugin system**: Hot-pluggable tools/skills without code changes
- **Streaming task output**: Real-time streaming for long-running task execution (currently polling-based)
