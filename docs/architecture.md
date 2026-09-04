# spring-harness 架构文档

> 本文档描述 spring-harness 项目的整体架构、模块设计、数据流和扩展点。

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 整体架构](#2-整体架构)
- [3. 前端架构](#3-前端架构)
- [4. 后端架构](#4-后端架构)
- [5. 核心模块详解](#5-核心模块详解)
  - [5.1 Agent 模块（ReAct）](#51-agent-模块react)
  - [5.2 PSE 模块（协作编排）](#52-pse-模块协作编排)
  - [5.3 RAG 模块（知识库）](#53-rag-模块知识库)
  - [5.4 Memory 模块（长期记忆）](#54-memory-模块长期记忆)
  - [5.5 Sandbox 模块（代码沙箱）](#55-sandbox-模块代码沙箱)
  - [5.6 MCP 模块（工具协议）](#56-mcp-模块工具协议)
  - [5.7 Skills 模块（技能）](#57-skills-模块技能)
  - [5.8 Task 模块（长时任务）](#58-task-模块长时任务)
- [6. 数据流](#6-数据流)
- [7. 技术选型](#7-技术选型)
- [8. 扩展点](#8-扩展点)

---

## 1. 项目概述

spring-harness 是一个基于 **Spring AI Alibaba** 的全栈 AI Agent 开发框架，提供五种交互模式：

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| 普通对话 | 直接与 LLM 对话 | 简单问答、文案生成 |
| RAG 知识库 | 基于上传文档检索增强回答 | 文档问答、知识管理 |
| ReAct Agent | LLM 自动识别意图调用工具 | 工具调用、任务执行 |
| PSE 协作 | Planner→Specialist→Evaluator 三角色流水线 | 复杂任务分解、多步骤执行 |
| 长时任务 | 后台异步执行，SQLite 持久化 | 耗时任务、批量处理 |

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端 (Vite + Vue 3)                       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐  │
│  │ 会话列表  │ │ 消息列表  │ │ 输入区域  │ │ 右侧运行时面板    │  │
│  │SessionList│ │MessageList│ │ InputArea│ │  RuntimePanel    │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────────┘  │
│         │            │            │               │               │
│         └────────────┴────────────┴───────────────┘               │
│                              │                                       │
│                    Vite Dev Proxy (5174)                           │
│                    /chat, /api, /rag, /models                      └──────────┐
└──────────────────────────────────────────────────────────────────┐           │
                                    │                                               │
                                    ▼                                               │
┌──────────────────────────────────────────────────────────────────┐           │
│                     后端 (Spring Boot 3.5)                        │           │
│  ┌─────────────────────────────────────────────────────────────┐  │           │
│  │                      Controller 层 (13个)                     │  │           │
│  │  Chat / Agent / Rag / RagChat / Pse / LongTask / Tools     │  │           │
│  │  Models / Memory / Example / Privacy                         │  │           │
│  └─────────────────────────────┬───────────────────────────────┘  │           │
│                                │                                     │           │
│  ┌─────────────────────────────▼───────────────────────────────┐  │           │
│  │                       Service 层                               │  │           │
│  │  MultiModelService / PromptService / SkillService            │  │           │
│  │  AgnesRateLimiter / ContextGuard                              │  │           │
│  └─────────────────────────────┬───────────────────────────────┘  │           │
│                                │                                     │           │
│  ┌──────────────┬──────────────▼──────────────┬────────────────┐  │           │
│  │   Agent 层    │       PSE 层                 │    Task 层      │  │           │
│  │ ReActAgent    │ PlannerAgent                 │ TaskManager    │  │           │
│  │   Service     │ SpecialistAgent              │ LongTaskStore  │  │           │
│  │               │ EvaluatorAgent               │ CleanupSched.  │  │           │
│  │               │ PseOrchestrator              │                │  │           │
│  └──────┬───────┴──────────────┬───────────────┴────────┬───────┘  │           │
│         │                       │                        │           │           │
│  ┌──────▼───────┬──────────────▼───────────────┬────────▼───────┐  │           │
│  │   RAG 层      │       Memory 层               │    Sandbox 层   │  │           │
│  │ RagService    │ MemoryService                │ DockerSandbox   │  │           │
│  │ RagConfig     │ MemoryExtractor              │   Executor      │  │           │
│  │ VectorStore   │ MemoryStore                  │                 │  │           │
│  │ (Simple+持久化)│ MemoryEncryptor              │                 │  │           │
│  └──────┬───────┴──────────────┬───────────────┴────────────────┘  │           │
│         │                       │                                      │           │
│  ┌──────▼───────────────────────▼──────────────────────────────────┐ │           │
│  │                        Tool 层 (8个本地工具)                      │ │           │
│  │  Calculator / DateTime / Stock / ExchangeRate / CodeExecution   │ │           │
│  │  FileRead / SkillRun / RagSearch                                 │ │           │
│  └───────────────────────────────┬──────────────────────────────────┘ │           │
│                                  │                                      │           │
│  ┌───────────────────────────────▼──────────────────────────────────┐ │           │
│  │                     MCP 层 (Model Context Protocol)               │ │           │
│  │  McpToolProvider / LocalToolProvider / ToolDescriptionService    │ │           │
│  │  filesystem (14 tools) + portfolio-check + pse-review            │ │           │
│  └───────────────────────────────┬──────────────────────────────────┘ │           │
│                                  │                                      │           │
│  ┌───────────────────────────────▼──────────────────────────────────┐ │           │
│  │                     Spring AI Alibaba (DashScope)                  │ │           │
│  │  ChatModel / EmbeddingModel / RerankModel / ToolCallingAdvisor   │ │           │
│  │  RetrievalAugmentationAdvisor / MCP Client                        │ │           │
│  └───────────────────────────────┬──────────────────────────────────┘ │           │
│                                  │                                      │           │
└──────────────────────────────────┼──────────────────────────────────────┘           │
                                   │                                                  │
                                   ▼                                                  │
                        ┌─────────────────────┐                              ┌──────────────┐
                        │   阿里云百炼 (LLM)    │                              │   Docker      │
                        │  qwen-plus / agnes   │◄─────────────────────────────│  代码沙箱     │
                        │  text-embedding       │                              │  8种语言      │
                        │  text-rerank          │                              └──────────────┘
                        └─────────────────────┘
                                   │
                                   ▼
                        ┌─────────────────────┐
                        │   SQLite (持久化)     │
                        │  tasks.db (长时任务)  │
                        │  memory.db (记忆)     │
                        │  vector-store.json    │
                        └─────────────────────┘
```

---

## 3. 前端架构

### 3.1 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5.x | 前端框架（Composition API） |
| Vite | 5.4.x | 构建工具 + 开发服务器（端口 5174） |
| marked | latest | Markdown 渲染 |
| DOMPurify | latest | XSS 消毒 |
| highlight.js | latest | 代码高亮 |

### 3.2 组件结构

```
frontend/src/
├── main.js                    # 入口
├── App.vue                    # 根组件（五模式切换、整体布局）
├── utils/
│   └── markdown.js            # Markdown 渲染 + DOMPurify 消毒
└── components/
    ├── SessionList.vue        # 会话列表（左侧）
    ├── MessageList.vue        # 消息列表（中间）
    ├── MessageItem.vue        # 单条消息（用户/AI，复制按钮）
    ├── InputArea.vue          # 输入区域（底部，动态示例问题）
    ├── RuntimePanel.vue       # 右侧运行时面板
    ├── CollapsibleCard.vue    # 可折叠卡片（Tools/MCP/Skills/Knowledge/Memory）
    ├── ToolCallCard.vue       # 工具调用展示卡片
    ├── RagPanel.vue           # RAG 知识库面板（上传/列表/预览）
    ├── LongTaskPanel.vue      # 长时任务列表面板
    ├── TaskForm.vue           # 长时任务创建表单
    ├── TaskDetail.vue         # 长时任务详情（执行过程+结果）
    ├── TaskExamples.vue       # 长时任务动态示例
    ├── ModelManager.vue       # 模型管理
    ├── ModelSettings.vue      # 模型设置弹窗
    └── PrivacyPanel.vue       # 隐私合规面板
```

### 3.3 布局结构

```
┌─────────────┬──────────────────────────────────┬─────────────────┐
│             │                                  │                 │
│  会话列表    │         消息列表                  │  右侧运行时面板  │
│  SessionList│       MessageList                │  RuntimePanel   │
│             │                                  │  - Tools        │
│             │                                  │  - MCP          │
│             │                                  │  - Skills       │
│             │                                  │  - Knowledge    │
│             │                                  │  - Memory       │
│             │                                  │  - Service      │
│             ├──────────────────────────────────┤                 │
│             │  输入区域 InputArea               │                 │
│             │  (动态示例问题 + 发送按钮)         │                 │
└─────────────┴──────────────────────────────────┴─────────────────┘
```

### 3.4 前端关键设计

1. **五模式切换**：App.vue 顶部 Tab 切换（普通对话/RAG/ReAct Agent/PSE 协作/长时任务），不同模式显示不同面板
2. **SSE 流式接收**：使用 EventSource 接收后端流式响应，逐字渲染
3. **Markdown 渲染**：所有 AI 输出经 `renderMarkdown()` 处理（marked + DOMPurify + highlight.js）
4. **代码分割**：Vite 配置 manualChunks，将 vue/marked/dompurify 等大库拆分为独立 chunk
5. **hover 滚动条**：自定义 CSS，滚动条默认隐藏，hover 时显示
6. **全屏查看器**：AI 回答支持全屏放大查看

---

## 4. 后端架构

### 4.1 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行时 |
| Spring Boot | 3.5.16 | Web 框架 |
| Spring AI Alibaba | 1.1.2.3 | DashScope starter |
| Spring AI MCP | 0.17.0 | Model Context Protocol |
| SQLite JDBC | latest | 持久化（长时任务+记忆） |
| Maven | 3.9+ | 构建工具 |

### 4.2 包结构

```
com.example.springharness/
├── controller/          # 控制器层（13个）
│   ├── ChatController.java           # 普通对话（流式+非流式）
│   ├── AgentController.java          # ReAct Agent
│   ├── RagController.java            # RAG 文档管理
│   ├── RagChatController.java        # RAG 对话
│   ├── PseController.java            # PSE 协作
│   ├── LongTaskController.java       # 长时任务
│   ├── ToolsController.java          # 工具列表
│   ├── ModelsController.java         # 模型管理
│   ├── MemoryController.java         # 记忆管理
│   ├── ExampleController.java        # 动态示例任务
│   └── PrivacyController.java        # 隐私合规状态
├── agent/               # ReAct Agent
│   └── ReActAgentService.java       # ReAct 循环（思考→工具调用→观察→回答）
├── pse/                 # PSE 协作（20+类）
│   ├── PlannerAgent.java             # 任务分解
│   ├── SpecialistAgent.java          # 任务执行（工具调用）
│   ├── EvaluatorAgent.java           # 任务评审
│   ├── PseOrchestrator.java         # 编排器（并行/重试/整体评审）
│   ├── SoulService.java              # Soul 角色定义
│   ├── SkillRegistry.java            # 技能注册
│   ├── LocalToolProvider.java        # 本地工具提供者
│   ├── McpToolProvider.java          # MCP 工具提供者
│   ├── ToolDescriptionService.java   # 统一工具描述
│   ├── TokenUsageTracker.java        # Token 消耗追踪
│   └── ... (PseTask/PseStep/PseResult/Skill/SkillResult)
├── task/                # 长时任务
│   ├── TaskManager.java              # 任务执行管理器（线程池）
│   ├── LongTaskStore.java            # SQLite 持久化
│   └── TaskCleanupScheduler.java     # 过期任务清理
├── rag/                 # RAG 知识库
│   ├── RagService.java               # RAG 服务
│   └── RagConfig.java                # 向量库配置
├── memory/              # 长期记忆
│   ├── MemoryService.java            # 记忆检索与注入
│   ├── MemoryExtractor.java          # LLM 抽取（信号词预过滤）
│   ├── MemoryStore.java              # SQLite 持久化
│   ├── MemoryEncryptor.java          # AES-256-GCM 加密
│   └── ChatMemoryService.java        # 会话窗口记忆（方案B）
├── sandbox/             # 代码沙箱
│   └── DockerSandboxExecutor.java    # Docker 容器执行（8种语言）
├── service/             # 服务层
│   ├── MultiModelService.java        # 多模型管理
│   ├── PromptService.java            # 系统提示管理
│   ├── SkillService.java             # 技能服务
│   ├── AgnesRateLimiter.java         # Agnes API 限流
│   └── ContextGuard.java             # 上下文防护（折叠/压缩）
├── tool/                # 本地工具（8个）
│   ├── CalculatorTool.java
│   ├── DateTimeTool.java
│   ├── StockTool.java
│   ├── ExchangeRateTool.java
│   ├── CodeExecutionTool.java
│   ├── FileReadTool.java
│   ├── SkillRunTool.java
│   ├── RagSearchTool.java
│   └── ToolCallRecorder.java         # ThreadLocal 工具调用记录
├── config/              # 配置
│   ├── ToolConfig.java               # 工具注册
│   ├── CorsConfig.java               # CORS 配置
│   └── WebConfig.java                # Web 配置
├── util/                # 工具类
│   └── ErrorSanitizer.java           # 错误/内容脱敏
└── dto/                 # 数据传输对象
```

### 4.3 后端关键设计

1. **统一工具描述**：`ToolDescriptionService` 统一管理本地工具和 MCP 工具的描述，注入到 Agent/PSE 的 System Prompt
2. **工具输出脱敏**：所有工具返回结果经 `ErrorSanitizer.sanitizeContent()` 过滤 API Key/token/secret
3. **上下文防护**：`ContextGuard` 在上下文接近上限时自动折叠最旧的工具往返（轻量骨架或 LLM 语义摘要）
4. **Agnes 限流**：`AgnesRateLimiter` 控制 Agnes API 调用频率，429 时指数退避重试
5. **SQLite 并发安全**：使用 `synchronized` + WAL 模式保证多线程并发安全
6. **SSE 流式输出**：使用 `SseEmitter` 实现流式响应，支持 ReAct/PSE 的逐步输出

---

## 5. 核心模块详解

### 5.1 Agent 模块（ReAct）

**ReAct (Reasoning + Acting)** 模式：LLM 循环执行"思考→工具调用→观察"，直到得出最终答案。

```
用户提问
   │
   ▼
┌─────────────────┐
│  构建 System Prompt │
│  (工具描述 + 记忆)  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   LLM 思考       │◄──────────────────┐
│  (是否需要工具?)  │                   │
└────────┬────────┘                   │
         │                            │
    ┌────┴────┐                       │
    │         │                       │
    ▼         ▼                       │
  需要工具   不需要工具                 │
    │         │                       │
    ▼         ▼                       │
┌────────┐  生成最终答案               │
│调用工具 │     │                      │
└───┬────┘     │                      │
    │          │                      │
    ▼          │                      │
┌────────┐     │                      │
│工具结果 │     │                      │
│(脱敏后) │     │                      │
└───┬────┘     │                      │
    │          │                      │
    └──────────┘                      │
         │                            │
         └────────────────────────────┘
```

**关键类**：
- `ReActAgentService`：核心 ReAct 循环，最大迭代次数可配置
- `ToolCallRecorder`：ThreadLocal 记录每次工具调用（入参/出参/耗时）
- `ContextGuard`：上下文超限时自动折叠历史工具往返

**工具调用流程**：
1. LLM 返回工具名 + 入参（JSON）
2. Spring AI `ToolCallingAdvisor` 自动执行对应 `FunctionToolCallback`
3. 工具结果经 `ErrorSanitizer.sanitizeContent()` 脱敏
4. 结果喂回 LLM，继续循环

---

### 5.2 PSE 模块（协作编排）

**PSE (Planner-Specialist-Evaluator)** 模式：三角色流水线，将复杂任务分解、执行、评审。

```
用户任务
   │
   ▼
┌──────────────────┐
│   Planner 规划    │
│  分解为 N 个子任务 │
└────────┬─────────┘
         │
    ┌────┴────┬────────┐
    ▼         ▼        ▼
┌───────┐ ┌───────┐ ┌───────┐
│子任务1 │ │子任务2 │ │子任务3 │  (并行执行)
└───┬───┘ └───┬───┘ └───┬───┘
    │         │         │
    ▼         ▼         ▼
┌───────────────────────────┐
│   Specialist 执行 (每个)    │
│  工具调用循环 (最大15次)    │
└───────────┬───────────────┘
            │
            ▼
┌───────────────────────────┐
│   Evaluator 评审 (每个)     │
│  验收标准检查 → PASS/FAIL   │
└───────────┬───────────────┘
            │
       ┌────┴────┐
       │         │
      PASS      FAIL
       │         │
       │    ┌────▼────┐
       │    │ 重试(≤3) │
       │    └────┬────┘
       │         │
       │    ┌────▼────┐
       │    │超过重试?  │
       │    └────┬────┘
       │         │
       │        是
       │         │
       │         ▼
       │    标记失败，继续下一个
       │
       ▼
┌───────────────────────────┐
│   整体评审 (Evaluator)      │
│  所有子任务完成后整体验收    │
└───────────┬───────────────┘
            │
            ▼
┌───────────────────────────┐
│   最终交付 (Final Answer)   │
└───────────────────────────┘
```

**关键类**：
- `PseOrchestrator`：编排器，管理整体流程（并行/重试/整体评审）
- `PlannerAgent`：任务分解，将用户任务拆分为子任务列表
- `SpecialistAgent`：任务执行，调用工具完成子任务（最大 15 次循环）
- `EvaluatorAgent`：任务评审，检查子任务是否满足验收标准
- `SoulService`：基于 Soul 的角色定义（每个角色有 SOUL.md）
- `TokenUsageTracker`：追踪每个角色的 token 消耗

**Soul 角色定义**：
- 每个角色（Planner/Specialist/Evaluator）有独立的 SOUL.md
- 可通过 `HARNESS_SOULS_DIR` 配置本地路径
- 默认指向 `resolve-skills/souls/`

**长耗时工具支持**：
- MCP 请求超时设为 600s
- Specialist 系统提示包含长耗时工具注意事项
- 不要激进重试，耐心等待工具执行完成

---

### 5.3 RAG 模块（知识库）

**RAG (Retrieval-Augmented Generation)**：上传文档 → 切分向量化 → 检索增强回答。

```
上传文档
   │
   ▼
┌─────────────────┐
│  文档加载         │
│  PDF按页 / 文本直接│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  文本切分         │
│  DOC/PDF: Paragraph│
│  MD: Markdown     │
│  其他: Token       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  向量化           │
│  qwen3.7-text-   │
│  embedding (1024维)│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  存入向量库       │
│  SimpleVectorStore│
│  (磁盘持久化)      │
└─────────────────┘

用户提问
   │
   ▼
┌─────────────────┐
│  语义检索 (TopK) │
│  余弦相似度匹配    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Rerank 精排      │
│  qwen3.7-text-   │
│  rerank           │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  注入 Prompt      │
│  RetrievalAug-   │
│  mentationAdvisor │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  LLM 生成回答     │
│  (基于检索内容)    │
└─────────────────┘
```

**关键类**：
- `RagService`：RAG 服务（文档上传/删除/检索/统计）
- `RagConfig`：向量库配置（SimpleVectorStore + 磁盘持久化）
- `RagSearchTool`：知识库检索工具（注册到 Agent/PSE/长时任务）

**切分策略**：
| 文件类型 | 切分器 | 说明 |
|---------|--------|------|
| PDF/DOC | ParagraphTextSplitter | 按段落切分 |
| Markdown | MarkdownTextSplitter | 按标题结构切分 |
| 其他 | TokenTextSplitter | 按 token 数切分 |

**RAG 通用化**：
- `search_knowledge` 工具注册到 Agent/PSE/长时任务
- 三类模式都能检索知识库
- 切换向量库仅需替换 `RagConfig` 中的 `VectorStore` Bean

---

### 5.4 Memory 模块（长期记忆）

**长期记忆**：自动从对话中抽取用户偏好/事实/目标，跨会话注入 System Prompt。

```
用户对话
   │
   ▼
┌─────────────────┐
│  信号词预过滤     │
│  (我喜欢/我是/    │
│   我住在/我的目标) │
└────────┬────────┘
         │
    命中?
    ┌────┴────┐
    否        是
    │         │
    │         ▼
    │    ┌─────────┐
    │    │ LLM 抽取 │
    │    │ (偏好/事实/目标)
    │    └────┬────┘
    │         │
    │         ▼
    │    ┌─────────┐
    │    │ 存入 SQLite│
    │    │ (可选加密)  │
    │    └───────────┘
    │
    ▼
后续对话
   │
   ▼
┌─────────────────┐
│  检索相关记忆     │
│  (关键词 LIKE +   │
│   访问频率排序)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  取 TopK 注入     │
│  System Prompt    │
└─────────────────┘
```

**关键类**：
- `MemoryService`：记忆检索与注入
- `MemoryExtractor`：基于 LLM 的抽取（信号词预过滤）
- `MemoryStore`：SQLite 持久化
- `MemoryEncryptor`：AES-256-GCM 加密（可选）
- `ChatMemoryService`：会话窗口记忆（方案 B，可选）

**记忆分类**：
| 类型 | 说明 | 示例 |
|------|------|------|
| preference | 用户偏好 | "我喜欢用中文"、"我偏好简洁回答" |
| fact | 用户事实 | "我住在上海"、"我是 Java 开发" |
| goal | 用户目标 | "我想学习 Rust"、"我的目标是升职" |

**加密选项**：
- `MEMORY_ENCRYPT_ENABLED=true` 时，记忆内容以 AES-256-GCM 加密存储
- 防止数据库文件泄露导致隐私暴露
- SQL LIKE 全文检索降级为内存匹配（数据量大时性能稍差）

---

### 5.5 Sandbox 模块（代码沙箱）

**Docker 代码沙箱**：在隔离的 Docker 容器中执行代码，支持 8 种语言。

```
execute_code 工具调用
   │
   ▼
┌─────────────────┐
│  语言归一化       │
│  (别名→标准名)    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  写入临时代码文件  │
│  (根据语言选扩展名)│
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────┐
│  构建 Docker 命令                  │
│  docker run --rm                  │
│    --network none                 │
│    --read-only                    │
│    --memory 512m                  │
│    --cpus 1                       │
│    --ulimit nproc=100             │
│    -v /tmp:/tmp:rw,exec           │
│    -v codefile:/code:ro           │
│    <image> <command>              │
└──────────────┬──────────────────┘
               │
               ▼
┌─────────────────┐
│  执行 (超时控制)   │
│  (默认30s)        │
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
   正常      超时
    │         │
    ▼         ▼
┌────────┐ ┌────────┐
│获取输出  │ │强制终止  │
│(stdout/ │ │(kill)   │
│ stderr) │ └────────┘
└───┬────┘
    │
    ▼
┌─────────────────┐
│  输出截断 (100KB) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  清理临时文件      │
└─────────────────┘
```

**支持的 8 种语言**：
| 语言 | 镜像 | 执行命令 | 说明 |
|------|------|---------|------|
| python | python:3.11-slim | python main.py | 解释执行 |
| javascript | node:20-slim | node main.js | 解释执行 |
| shell | alpine:3.19 | sh main.sh | 解释执行 |
| java | eclipse-temurin:17-jdk | java Main.java | 源码模式 |
| go | golang:1.22-alpine | go run main.go | 缓存重定向 /tmp |
| rust | rust:1.75-alpine | rustc main.rs -o /tmp/main && /tmp/main | 编译后运行 |
| c | sandbox-gcc:alpine | gcc main.c -o /tmp/main && /tmp/main | 编译后运行 |
| cpp | sandbox-gcc:alpine | g++ main.cpp -o /tmp/main && /tmp/main | 编译后运行 |

**安全隔离**：
- `--rm`：容器执行后自动销毁
- `--network none`：禁用网络（防止代码访问外部资源）
- `--read-only`：根文件系统只读（仅 /tmp 可写且可执行）
- `--memory 512m`：内存限制 512MB
- `--cpus 1`：CPU 限制 1 核
- `--ulimit nproc=100`：进程数限制 100
- 超时自动 kill（默认 30s）
- 输出截断（默认 100KB）

**自建 GCC 镜像**：
- `docker/sandbox-gcc.Dockerfile`
- alpine + gcc/g++，约 209MB
- musl libc 对标准程序完全兼容

---

### 5.6 MCP 模块（工具协议）

**MCP (Model Context Protocol)**：标准化的工具调用协议，连接外部工具服务器。

```
Agent / PSE / 长时任务
         │
         ▼
┌─────────────────────────┐
│   ToolDescriptionService  │
│   (统一工具描述管理)       │
└──────────┬──────────────┘
           │
    ┌──────┴──────┐
    ▼             ▼
┌─────────┐  ┌──────────┐
│LocalTool│  │ McpTool  │
│Provider │  │ Provider │
└────┬────┘  └────┬─────┘
     │              │
     ▼              ▼
  8个本地工具    MCP 服务器
                  │
          ┌───────┼───────┐
          ▼       ▼       ▼
      filesystem  portfolio pse-review
      (14 tools)  -check   (投资周报)
                  (数据体检)
```

**MCP 服务器配置**（application.yml）：
| 服务器 | 类型 | 说明 |
|--------|------|------|
| filesystem | stdio | 文件系统操作（14 个工具） |
| portfolio-check | stdio | 投资数据体检（node 脚本） |
| pse-review | stdio | 深度投资周报（node 脚本） |

**MCP 工具列表**：
- filesystem：read_file, read_text_file, read_media_file, read_multiple_files, write_file, edit_file, create_directory, list_directory, list_directory_with_sizes, directory_tree, move_file, search_files, get_file_info, list_allowed_directories
- portfolio-check：投资组合数据体检
- pse-review：生成深度投资周报

**关键类**：
- `McpToolProvider`：从 MCP 服务器自动获取工具列表
- `LocalToolProvider`：本地工具提供者
- `ToolDescriptionService`：统一工具描述（合并本地+MCP）

**MCP 超时配置**：
- `spring.ai.mcp.client.request-timeout: 600s`
- 支持长耗时工具（portfolio-check 1-3 分钟，pse-review 2-6 分钟）

**工具输出脱敏**：
- MCP 工具返回结果经 `ErrorSanitizer.sanitizeContent()` 过滤
- 防止 API Key/token/secret 泄露到 LLM 或前端

---

### 5.7 Skills 模块（技能）

**Skills**：可加载的技能指令包，对齐 Claude Code / Codex 开放标准。

```
skill_run 工具调用
   │
   ▼
┌─────────────────┐
│  技能名称解析     │
│  (code-review /  │
│   weekly-investment)│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  查找 SKILL.md   │
│  (HARNESS_SKILLS_DIR)│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  读取技能完整指令  │
│  (SKILL.md 内容)  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  注入当前上下文    │
│  (LLM 按技能指令  │
│   执行任务)        │
└─────────────────┘
```

**技能目录结构**：
```
resolve-skills/           # git submodule
└── skills/
    ├── code-review/
    │   └── SKILL.md      # 代码审查技能
    ├── weekly-investment/
    │   ├── SKILL.md      # 投资周报技能
    │   └── scripts/
    │       ├── portfolio-check.mjs
    │       └── pse-review.mjs
    ├── hot-news-post/
    ├── rust-review/
    └── post-comment/
```

**关键类**：
- `SkillRegistry`：技能注册与管理
- `SkillService`：技能服务
- `SkillRunTool`：技能加载工具（注册到 Agent/PSE/长时任务）

**技能标准**：
- 每个技能目录包含 `SKILL.md`
- SKILL.md 包含：name、description、适用场景、执行步骤、注意事项
- 对齐 Claude Code / Codex 开放标准

**git submodule 管理**：
- `resolve-skills` 作为 git submodule
- `.gitmodules` 配置 submodule 路径
- 初始化：`git submodule update --init --recursive`

---

### 5.8 Task 模块（长时任务）

**长时任务**：后台异步执行，SQLite 持久化，支持中断/续跑。

```
创建任务
   │
   ▼
┌─────────────────┐
│  存入 SQLite      │
│  (status=pending) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  线程池调度       │
│  (TASK_POOL_SIZE)│
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────┐
│  执行 (ReAct / PSE / 普通对话)    │
│  - 记录执行步骤                    │
│  - 记录 token 消耗                 │
│  - 写入执行日志                    │
│  - 定期保存检查点                   │
└──────────────┬──────────────────┘
               │
          ┌────┴────┐
          │         │
        完成       中断/取消
          │         │
          ▼         ▼
      ┌────────┐ ┌────────┐
      │更新状态  │ │更新状态  │
      │completed│ │cancelled│
      └───┬────┘ └────────┘
          │
          ▼
      ┌────────┐
      │保存结果  │
      │(Markdown)│
      └────────┘

查询任务
   │
   ▼
┌─────────────────┐
│  从 SQLite 读取   │
│  (状态/步骤/结果/日志)│
└─────────────────┘

续跑任务
   │
   ▼
┌─────────────────┐
│  从检查点恢复     │
│  (继续执行)       │
└─────────────────┘
```

**关键类**：
- `TaskManager`：任务执行管理器（线程池调度）
- `LongTaskStore`：SQLite 持久化
- `TaskCleanupScheduler`：过期任务自动清理
- `LongTask`：任务实体（状态/步骤/结果/日志/token消耗）

**任务状态**：
| 状态 | 说明 |
|------|------|
| pending | 等待执行 |
| running | 执行中 |
| completed | 已完成 |
| failed | 执行失败 |
| cancelled | 已取消 |
| paused | 已暂停（可续跑） |

**持久化内容**：
- 任务基本信息（ID、标题、类型、模型、创建时间）
- 执行状态（pending/running/completed/failed/cancelled）
- 执行步骤（每步的类型、内容、耗时）
- 执行结果（Markdown 格式）
- 执行日志（文件存储）
- Token 消耗（输入/输出 token 数）
- 耗时统计

**自动清理**：
- `TASK_RETENTION_DAYS`：已完成任务保留天数（默认 30 天）
- `TASK_CLEANUP_ENABLED`：启用定时清理（每天凌晨 3 点 + 启动时）
- 防止数据库无限增长 + 隐私保护

**错误报告复制**：
- 一键复制结构化错误报告
- 正确步骤简写，出错步骤详细
- 方便排查问题

---

## 6. 数据流

### 6.1 普通对话流程

```
用户输入 → InputArea.vue
    │
    ▼
Vite Proxy → /chat/stream?message=xxx
    │
    ▼
ChatController.chatStream()
    │
    ▼
构建 Prompt (System + 用户消息 + Memory)
    │
    ▼
DashScope ChatModel.stream()
    │
    ▼
SseEmitter 流式返回
    │
    ▼
前端 EventSource 接收
    │
    ▼
逐字渲染 (Markdown + DOMPurify)
```

### 6.2 ReAct Agent 流程

```
用户输入 → /chat/agent?message=xxx
    │
    ▼
AgentController → ReActAgentService.runStream()
    │
    ▼
循环:
  ├─ LLM 思考 (是否需要工具?)
  ├─ 需要工具 → 调用工具 → 结果脱敏 → 喂回 LLM
  └─ 不需要工具 → 生成最终答案
    │
    ▼
SSE 流式输出 (思考过程 + 工具调用 + 最终答案)
    │
    ▼
前端渲染 (ToolCallCard 展示工具调用)
```

### 6.3 PSE 协作流程

```
用户任务 → /pse/chat?message=xxx
    │
    ▼
PseController → PseOrchestrator.executeStream()
    │
    ▼
1. Planner 分解任务 (N 个子任务)
    │
    ▼
2. Specialist 并行执行每个子任务
    │  (工具调用循环, 最大15次)
    │
    ▼
3. Evaluator 评审每个子任务
    │  (PASS → 下一个)
    │  (FAIL → 重试, 最多3次)
    │
    ▼
4. 整体评审 (所有子任务完成后)
    │
    ▼
5. 最终交付 (Final Answer)
    │
    ▼
SSE 流式输出 (每一步都实时输出)
```

### 6.4 长时任务流程

```
创建任务 → POST /api/tasks
    │
    ▼
存入 SQLite (status=pending)
    │
    ▼
TaskManager 线程池调度
    │
    ▼
后台执行 (ReAct / PSE / 普通对话)
    │  - 定期保存检查点
    │  - 写入执行日志
    │  - 记录 token 消耗
    │
    ▼
完成 → 更新状态 (completed) + 保存结果
    │
    ▼
前端轮询 / SSE 获取进度
    │
    ▼
TaskDetail.vue 展示 (执行过程 + 结果)
```

### 6.5 RAG 检索流程

```
上传文档 → POST /rag/documents
    │
    ▼
文档加载 → 切分 → 向量化 → 存入向量库
    │
    ▼
用户提问 → /chat/rag?message=xxx
    │
    ▼
语义检索 (TopK) → Rerank 精排
    │
    ▼
检索内容注入 Prompt
    │
    ▼
LLM 生成回答 (基于检索内容)
```

---

## 7. 技术选型

### 7.1 后端选型

| 技术 | 选型 | 理由 |
|------|------|------|
| Web 框架 | Spring Boot 3.5 | 成熟稳定，生态丰富，与 Spring AI 无缝集成 |
| AI 框架 | Spring AI Alibaba 1.1.2.3 | 国内直连 DashScope，无需代理，自带版本管理 |
| 工具调用 | Spring AI ToolCallingAdvisor | 原生支持 Function Calling，自动执行工具 |
| MCP | Spring AI MCP 0.17.0 | 原生支持 Model Context Protocol |
| 持久化 | SQLite | 轻量级，无需额外服务，适合单机部署 |
| 代码沙箱 | Docker | 隔离性好，支持多语言，资源限制灵活 |
| 构建工具 | Maven | Java 生态标准，依赖管理成熟 |

### 7.2 前端选型

| 技术 | 选型 | 理由 |
|------|------|------|
| 框架 | Vue 3.5 | Composition API，响应式好，学习曲线平缓 |
| 构建工具 | Vite 5.4 | 开发服务器快，HMR 即时，构建优化好 |
| Markdown | marked | 轻量，速度快，支持 GFM |
| XSS 防护 | DOMPurify | 业界标准，白名单消毒，安全性高 |
| 代码高亮 | highlight.js | 支持语言多，主题丰富 |
| HTTP 客户端 | fetch + EventSource | 原生 API，无需额外依赖，支持 SSE |

### 7.3 为什么不用其他方案

| 方案 | 不选用原因 |
|------|-----------|
| LangChain / LlamaIndex | Spring AI 已提供抽象层，无需额外 Python 依赖 |
| React | 用户偏好 Vue，且 Vue 3 Composition API 足够强大 |
| PostgreSQL / MySQL | SQLite 足够单机使用，无需额外数据库服务 |
| Kubernetes | 单机部署，Docker Compose 足够 |
| Redis 向量库 | SimpleVectorStore + 磁盘持久化足够开发演示，生产可切换 |

---

## 8. 扩展点

### 8.1 新增本地工具

1. 在 `tool/` 包下创建工具类，实现 `FunctionCallback` 接口
2. 在 `ToolConfig` 中注册为 Bean
3. 工具自动被 `ToolDescriptionService` 收集，注入到 Agent/PSE 的 System Prompt

### 8.2 新增 MCP 服务器

1. 在 `application.yml` 的 `spring.ai.mcp.client` 下配置新的 MCP 服务器
2. `McpToolProvider` 自动从新服务器获取工具列表
3. 工具自动被 `ToolDescriptionService` 收集

### 8.3 新增技能

1. 在 `resolve-skills/skills/` 下创建新技能目录
2. 编写 `SKILL.md`（name、description、适用场景、执行步骤）
3. 技能自动被 `SkillRegistry` 发现，可通过 `skill_run` 工具加载

### 8.4 新增 Soul 角色

1. 在 `HARNESS_SOULS_DIR` 下创建角色目录（planner/specialist/evaluator）
2. 编写 `SOUL.md`（角色定义、系统提示、行为准则）
3. `SoulService` 自动加载新的 Soul 定义

### 8.5 切换向量库

1. 在 `RagConfig` 中替换 `VectorStore` Bean
2. Spring AI 支持 25+ 向量库（Redis、PGVector、Milvus、Chroma 等）
3. `RagService` 和 Controller 无需改动

### 8.6 新增模型

1. 在 `ModelsController` 或 `.env` 中配置新模型
2. `MultiModelService` 管理多模型切换
3. 支持 DashScope、Agnes、DeepSeek 等多提供商

### 8.7 新增交互模式

1. 在 `controller/` 下创建新的 Controller
2. 在 `service/` 或独立包下实现业务逻辑
3. 在前端 `App.vue` 中新增 Tab 和对应面板

---

## 附录

### A. 配置文件清单

| 文件 | 说明 |
|------|------|
| `.env` | 本地配置（含密钥，已 gitignore） |
| `.env.example` | 配置模板（可提交） |
| `application.yml` | Spring Boot 配置 |
| `pom.xml` | Maven 依赖配置 |
| `vite.config.js` | Vite 构建配置 |
| `Makefile` | 常用命令封装 |
| `.gitmodules` | git submodule 配置 |
| `docker/sandbox-gcc.Dockerfile` | 自建 GCC 沙箱镜像 |

### B. 端口分配

| 端口 | 服务 |
|------|------|
| 8080 | 后端 Spring Boot |
| 5174 | 前端 Vite Dev Server |

### C. 数据存储路径

| 路径 | 说明 |
|------|------|
| `data/tasks.db` | 长时任务 SQLite 数据库 |
| `data/memory.db` | 记忆 SQLite 数据库 |
| `data/vector-store.json` | 向量库持久化文件 |
| `data/mcp-workspace/` | MCP filesystem 工作目录 |
| `logs/tasks/` | 长时任务执行日志 |
| `app.log` | 后端应用日志 |

---

> 文档版本：v1.0
> 最后更新：2026-09-04
