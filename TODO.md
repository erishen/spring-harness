# Spring AI Alibaba Demo - TODO
| 2026-09-03 | UI：单一竖向滚动条 | 页面只保留主内容区一个竖向滚动条：左侧会话/右侧 Runtime/长时任务详情步骤/日志/代码块/输入框全部隐藏滚动条（保留滚动能力）；长时任务输入框改为高度自适应（min 42px / max 120px），点击示例自动增高 |
| 2026-09-03 | agnes 推理模型 max_tokens 下限 | 排查确认 token 统计本身正常（此前查错字段：应读 tokenUsage.totalTokens 而非顶层 total_tokens）。发现 agnes 推理模型（agnes-2.0-flash）会把 max_tokens 配额先耗在 reasoning，过小会导致 content 为空/截断（curl 实测 max_tokens=10 → content 空）。新增 AGNES_MAX_TOKENS=8192 配置：agnres 模型 max_tokens = max(MAX_TOKENS, AGNES_MAX_TOKENS)。实测 agnes 回答"我是Agnes-2.0-Flash"不再为空，token 3686 正常 |
| 2026-09-03 | 并发压测 | SQLite 修复后两轮压测：①TASK_POOL_SIZE=4 并发提交8任务：running峰值4、8/8 completed、零SQLite报错 ②临时调TASK_POOL_SIZE=8 并发10任务：running峰值8、10/10 completed、零报错。测后已恢复=4。结论：每操作独立连接+WAL+busy_timeout 在高并发写下稳定 |
| 2026-09-03 | SQLite 并发安全 | LongTaskStore 原为单例共享单个 JDBC Connection，多任务线程并发 executeUpdate 存在 "database is locked"/数据错乱隐患。改为每操作独立连接（try-with-resources 自动释放）+ PRAGMA busy_timeout=5000 + WAL。已编译通过并重启验证：建表/loadAll恢复/save 均正常 |
| 2026-09-03 | 上下文防护 ContextGuard | 修复 PSE/ReAct 执行「收集投资组合数据」等任务时 ContextWindowExceededError（63万 tokens 超过 agnes 52.4万限制）。根因：工具输出无大小限制 + ReAct 循环历史无限累积。新增 ContextGuard：①单次工具输出截断至 8000 字符（execute_code 打印/MCP 读大文件等）②消息历史超 40000 字符自动裁剪最旧工具往返（保留 system+user）。实测 3000 行代码输出被截断、任务正常完成 |
| 2026-09-03 | Agnes API 限流 | Agnes 免费 API 频繁调用触发 429（rate limit for free users）。新增 AgnesRateLimiter 拦截器：客户端节流（两次调用最小间隔 6s，可配）+ 429 指数退避重试（10s/20s/40s，最多3次）；共享实例统一限制所有 agnes 模型调用频率。实测连续3次调用全部成功且自动间隔≥6s |

> 项目优化与功能迭代清单，按优先级和实施阶段排序。

## 📊 项目概览

- **后端**：Spring Boot 3.5.16 + Spring AI Alibaba 1.1.2.3
- **前端**：Vite + Vue 3（端口 5174）
- **后端端口**：8080
- **核心功能**：普通聊天 / ReAct Agent / PSE 多 Agent 协作 / RAG 知识库 / MCP 集成

---

## 🔴 第一阶段：核心体验（高优先级）

### [ ] 1. 多轮对话上下文
- **问题**：当前每次请求独立，AI 无法记住历史消息
- **方案**：前端维护 messages 数组，发送时传递历史；后端用 `ChatClient.prompt().messages(...)`
- **涉及文件**：`ChatController.java`、`App.vue`、`useChat.js`（待创建）

### [ ] 2. 停止生成 + 重新生成
- **问题**：流式输出无法中途停止，回答不满意无法重新生成
- **方案**：
  - loading 时发送按钮变"停止"，用 `AbortController` 取消 fetch
  - AI 消息气泡添加"重新生成"按钮
- **涉及文件**：`InputArea.vue`、`MessageList.vue`、`App.vue`

### [ ] 3. 消息历史持久化
- **问题**：刷新页面后对话丢失
- **方案**：用 `localStorage` 保存消息列表，页面加载时恢复
- **涉及文件**：`App.vue` 或 `useChat.js`

### [ ] 4. 全局异常处理
- **问题**：无 `@ControllerAdvice`，错误格式不统一
- **方案**：添加 `GlobalExceptionHandler`，统一返回 `{ code, message, data }`
- **涉及文件**：新建 `config/GlobalExceptionHandler.java`

---

## 🟡 第二阶段：代码质量（中优先级）

### [ ] 5. 前端逻辑抽离（composable）
- **问题**：App.vue 337 行，四个发送函数耦合
- **方案**：抽成 `composables/useChat.js`，组件只负责 UI
- **涉及文件**：新建 `composables/useChat.js`、修改 `App.vue`

### [ ] 6. API 服务层
- **问题**：fetch 调用硬编码在组件中
- **方案**：创建 `services/api.js`，封装所有 API 调用
- **涉及文件**：新建 `services/api.js`、修改各组件

### [ ] 7. Controller 合并
- **问题**：8 个 Controller，部分功能重叠
- **方案**：
  - 合并 `AgentController` + `ReActAgentController`
  - 合并 `RagChatController` + `RagController`
  - 统一 API 前缀 `/api/v1`
- **涉及文件**：`controller/` 目录

### [ ] 8. ChatClient 单例优化
- **问题**：每次请求都 `chatClientBuilder.build()`
- **方案**：在 `@Configuration` 中注入单例 `ChatClient` Bean
- **涉及文件**：`config/ModelConfig.java`、`ChatController.java`

---

## 🟢 第三阶段：功能完善（中低优先级）

### [ ] 9. 复制消息 + 清空对话
- **功能**：
  - AI 消息气泡添加"复制"按钮
  - 顶部添加"清空对话"按钮（带确认）
- **涉及文件**：`MessageList.vue`、`App.vue`

### [x] 10. Token 用量显示
- **功能**：后端返回 usage，前端在消息下方显示 Token 消耗
- **状态**：✅ 已完成（2026-09-03）
- **实现**：TokenUsageTracker 累计所有 LLM 调用，ReAct/PSE 模式返回 tokenUsage + llmCallCount，前端消息下方展示

### [ ] 11. 参数调节面板
- **功能**：可折叠"高级设置"，调节 temperature、max_tokens、top_p
- **涉及文件**：新建 `components/ModelSettings.vue`、`App.vue`

### [ ] 12. 系统提示词自定义
- **功能**：添加系统提示词输入框，自定义 AI 角色
- **涉及文件**：新建 `components/SystemPrompt.vue`、后端 Controller

---

## ⚪ 第四阶段：工程化（低优先级）

### [ ] 13. 前端 TypeScript 迁移
- **收益**：类型安全，IDE 提示更好
- **范围**：先从 API 响应类型和组件 props 开始

### [ ] 14. 深色模式
- **功能**：主题切换，CSS 变量实现
- **涉及文件**：`style.css`、各组件

### [ ] 15. Docker 化
- **功能**：Dockerfile + docker-compose.yml，一键启动
- **涉及文件**：新建 `Dockerfile`、`docker-compose.yml`

### [ ] 16. 测试覆盖
- **功能**：
  - 后端：JUnit 5 + Mockito 单元测试
  - 前端：Vitest + Vue Test Utils 组件测试
- **涉及文件**：`src/test/`、`frontend/src/__tests__/`

### [ ] 17. README + API 文档
- **功能**：
  - 项目 README（功能介绍、快速开始、架构图）
  - SpringDoc OpenAPI 自动生成 API 文档
- **涉及文件**：`README.md`、`pom.xml`

### [ ] 18. 对话导出
- **功能**：导出对话记录为 Markdown 或 JSON
- **涉及文件**：`MessageList.vue`、新建导出工具函数

---

## 📝 已完成记录

| 日期 | 任务 | 说明 |
|------|------|------|
| 2026-09-03 | 长时任务 SQLite 持久化 | 引入 sqlite-jdbc，新增 LongTaskStore（data/tasks.db，long_tasks 表，列表字段 JSON 存储）；TaskManager 提交/事件/终态实时 upsert，启动时 @PostConstruct 恢复全部历史任务（运行中任务标记为"应用重启中断"）；解决了"任务内存态重启丢失"问题 |
| 2026-09-03 | 长时任务 v2（自治 Agent 增强） | **token 独立统计**（ThreadLocal 任务作用域，贯穿 ReAct/PSE 全部 Agent，并发不串）；**可中断**（Future.cancel + 协作式取消信号，ReAct/PSE 每轮检查）；**续跑**（POST /api/tasks/{id}/restart 重跑，原任务保留）；**日志落盘**（logs/tasks/{id}.log + 内存最近 300 条 UI 展示）；**全工具注入**（ReAct/Specialist/TaskManager 聚合本地工具 + skill_run + MCP 14 工具，免审批调用） |
| 2026-09-03 | 长时任务（异步 Agent 任务） | 新建 task 包（LongTask/TaskManager/LongTaskController）：线程池后台执行 chat/agent/pse 三类任务，状态/步骤/结果实时可查；前端新增"长时任务"模式（LongTaskPanel.vue），3 秒轮询，不受 HTTP/SSE 超时限制；TASK_POOL_SIZE 可配。**已知限制：任务内存态，重启后丢失** |
| 2026-09-03 | Java 包名统一为 springharness | 47 个文件包名 com.example.springaidemo → com.example.springharness，目录同步移动；主类 SpringAiDemoApplication → SpringHarnessApplication，README 同步更新 |
| 2026-09-03 | PSE 接入 Souls 角色定义 | 新建 SoulService 加载 souls/{planner,specialist,evaluator}/SOUL.md，替换三 Agent 硬编码角色 prompt；souls 不入库（gitignore），HARNESS_SOULS_DIR 指向本地 work/harness/resolve-skills/souls |
| 2026-09-03 | resolve-skills 改为 git submodule | 将 resolve-skills 通过 git submodule 引入（替代绝对路径引用），HARNESS_SKILLS_DIR 改为相对路径 resolve-skills/skills |
| 2026-09-03 | Docker 代码沙箱 | 基于 Docker 的代码执行工具（execute_code），支持 Python/JavaScript/Shell，安全隔离（禁用网络、只读文件系统、资源限制、超时控制），前端 Runtime 面板显示沙箱状态 |
| 2026-09-03 | PSE 性能优化 | 整体超时（90s）、依赖分层并行执行、简单任务快速通道（1个子任务跳过整体评审） |
| 2026-09-03 | Token 用量统计 | TokenUsageTracker 累计所有 LLM 调用，ReAct/PSE 模式返回 tokenUsage + llmCallCount，前端消息下方展示 |
| 2026-09-02 | 前端组件拆分 | App.vue 从 1190 行拆分为 5 个组件（MessageList/RuntimePanel/RagPanel/InputArea） |
| 2026-09-02 | 顶部模式切换 UI 优化 | 改为两行布局，按钮文字简化，mode-hint 统一管理 |
| 2026-09-02 | 底部输入框优化 | 添加示例问题栏，修复竖向滚动条，示例问题自动换行 |
| 2026-09-02 | MCP 集成 | filesystem MCP 服务器，14 个文件系统工具，前端按服务器分组展示 |
| 2026-09-02 | PSE 多 Agent 协作 | Planner-Specialist-Evaluator 三角色架构，工具描述服务抽离 |
| 2026-09-02 | RAG 知识库 | SimpleVectorStore + JSON 持久化，按文件类型智能切分 |
| 2026-09-02 | ReAct Agent | 多轮推理+工具调用，思考→行动→观察循环 |

---

## 🎯 下一步建议

**推荐从任务 #1（多轮对话上下文）开始**，这是聊天应用最核心的体验提升。

实施顺序：
1. 第一阶段（核心体验）：#1 → #2 → #3 → #4
2. 第二阶段（代码质量）：#5 → #6 → #7 → #8
3. 第三阶段（功能完善）：#9 → #10 → #11 → #12
4. 第四阶段（工程化）：#13 → #14 → #15 → #16 → #17 → #18
