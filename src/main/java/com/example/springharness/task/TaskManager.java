package com.example.springharness.task;

import com.example.springharness.agent.ReActAgentService;
import com.example.springharness.pse.McpToolProvider;
import com.example.springharness.pse.PseOrchestrator;
import com.example.springharness.pse.TokenUsage;
import com.example.springharness.pse.TokenUsageTracker;
import com.example.springharness.service.MultiModelService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 长时任务管理器：异步执行 Agent 任务（普通对话 / ReAct Agent / PSE 协作）。
 *
 * <p>长时任务增强：
 * <ul>
 *   <li><b>独立 token 统计</b>：每个任务绑定独立的 TokenUsageTracker（ThreadLocal 任务作用域），
 *       贯穿 ReAct/PSE 及内部所有 Agent，并发任务互不污染；完成后写入 LongTask。</li>
 *   <li><b>可随时中断</b>：cancel 时置中断信号 + Future.cancel(true)，ReAct/PSE 每轮循环检查信号协作式中断。</li>
 *   <li><b>日志落盘</b>：每个任务的全部事件写入 logs/tasks/{id}.log，可追溯执行过程。</li>
 *   <li><b>全工具注入</b>：chat 任务自动聚合本地工具（含 skills）+ MCP 工具，免审批调用。</li>
 * </ul>
 */
@Component
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Map<String, LongTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    private final MultiModelService multiModelService;
    private final List<ToolCallback> localToolCallbacks;
    private final McpToolProvider mcpToolProvider;
    private final ReActAgentService reActAgentService;
    private final PseOrchestrator pseOrchestrator;
    private final LongTaskStore store;

    private final Path logDir;

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    /** 默认模型：model 为空时的兜底值 */
    @Value("${DASHSCOPE_MODEL:agnes-2.0-flash}")
    private String defaultModel;

    public TaskManager(MultiModelService multiModelService,
                       List<ToolCallback> localToolCallbacks,
                       McpToolProvider mcpToolProvider,
                       ReActAgentService reActAgentService,
                       PseOrchestrator pseOrchestrator,
                       LongTaskStore store,
                       @Value("${TASK_POOL_SIZE:4}") int poolSize,
                       @Value("${TASK_LOG_DIR:logs/tasks}") String taskLogDir) {
        this.multiModelService = multiModelService;
        this.localToolCallbacks = localToolCallbacks;
        this.mcpToolProvider = mcpToolProvider;
        this.reActAgentService = reActAgentService;
        this.pseOrchestrator = pseOrchestrator;
        this.store = store;
        // 固定线程池：避免无界线程；队列默认无界
        this.executor = Executors.newFixedThreadPool(Math.max(1, poolSize));
        this.logDir = Paths.get(taskLogDir);
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            log.warn("创建任务日志目录失败: {}", taskLogDir, e);
        }
    }

    /**
     * 启动时从 SQLite 恢复历史任务。
     * 运行中/排队中的任务因进程重启无法续跑，标记为「已取消」（重启中断）。
     */
    @PostConstruct
    public void restoreFromStore() {
        List<LongTask> restored = store.loadAll();
        for (LongTask task : restored) {
            if (!task.isTerminal()) {
                task.setInterruptRequested(true);
                task.markCancelled();
                task.recordLog("== 应用重启，任务中断 ==");
                store.save(task);
            }
            tasks.put(task.getId(), task);
        }
        if (!restored.isEmpty()) {
            log.info("从 SQLite 恢复 {} 个历史任务", restored.size());
        }
    }

    /**
     * 提交一个长时任务，立即返回任务 ID。
     *
     * @param type    chat / agent / pse
     * @param message 用户消息 / 任务描述
     * @param model   模型名（可空）
     * @return 任务 ID
     */
    public String submit(String type, String message, String model) {
        // model 为空时兜底为默认模型，避免 "Model not exist"
        if (model == null || model.isBlank()) {
            model = defaultModel;
        }
        String normalizedType = normalizeType(type);
        LongTask task = new LongTask(normalizedType, model, message);
        tasks.put(task.getId(), task);
        task.recordLog("提交任务 type=" + normalizedType + " model=" + (model == null ? "(默认)" : model));
        writeLog(task, "== 任务提交 ==");
        store.save(task);
        Future<?> future = executor.submit(() -> runTask(task));
        futures.put(task.getId(), future);
        log.info("提交长时任务 [{}] type={} model={}", task.getId(), normalizedType, model);
        return task.getId();
    }

    /** 查询单个任务（返回 null 表示不存在） */
    public LongTask get(String id) {
        return tasks.get(id);
    }

    /** 任务列表（按创建时间倒序） */
    public List<LongTask> list() {
        List<LongTask> sorted = new ArrayList<>(tasks.values());
        sorted.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return sorted;
    }

    /** 当前运行中任务数（监控指标挂点） */
    public long activeCount() {
        return tasks.values().stream()
                .filter(t -> LongTask.STATUS_RUNNING.equals(t.getStatus()))
                .count();
    }

    /**
     * 中断任务：置中断信号（ReAct/PSE 协作式中断）+ 尝试 interrupt 执行线程。
     *
     * @return true=已发送中断；false=任务不存在或已结束
     */
    public boolean cancel(String id) {
        LongTask task = tasks.get(id);
        if (task == null) return false;
        task.setInterruptRequested(true);
        if (!task.isTerminal()) {
            task.markCancelled();
            task.recordLog("== 用户请求中断 ==");
            writeLog(task, "== 用户请求中断 ==");
            store.save(task);
            Future<?> future = futures.get(id);
            if (future != null) {
                future.cancel(true);
            }
            log.info("中断长时任务 {}", id);
            return true;
        }
        return false;
    }

    /**
     * 续跑（重跑）：用相同参数重新提交一个任务，返回新任务 ID。
     * 原任务保留（completed/cancelled）供查看对比。
     */
    public String restart(String id) {
        LongTask old = tasks.get(id);
        if (old == null) return null;
        return submit(old.getType(), old.getMessage(), old.getModel());
    }

    /** 删除任务（终态任务） */
    public boolean delete(String id) {
        futures.remove(id);
        tasks.remove(id);
        store.delete(id);
        return true;
    }

    // ==================== 内部执行 ====================

    private void runTask(LongTask task) {
        // 每个任务独立的 token 追踪器，传给执行入口（内部会 bind 到任务作用域）
        TokenUsageTracker taskTracker = new TokenUsageTracker();
        task.markRunning();
        task.recordLog("== 开始执行 ==");
        try {
            switch (task.getType()) {
                case LongTask.TYPE_CHAT -> runChatTask(task);
                case LongTask.TYPE_AGENT -> runAgentTask(task, taskTracker);
                case LongTask.TYPE_PSE -> runPseTask(task, taskTracker);
                default -> task.markFailed("未知任务类型: " + task.getType());
            }
        } catch (Exception e) {
            log.error("长时任务 [{}] 执行失败: {}", task.getId(), e.getMessage(), e);
            if (!task.isInterruptRequested() && !task.isTerminal()) {
                task.markFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        } finally {
            // 写回 token 统计（taskTracker 已被执行入口内部 record 累计）
            TokenUsage usage = taskTracker.getTotal();
            task.addTokens(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), taskTracker.getCallCount());
            task.setProgressText("");
            if (task.isTerminal()) {
                task.recordLog("== 任务结束：status=" + task.getStatus()
                        + " tokens=" + task.getTotalTokens() + " calls=" + task.getLlmCallCount()
                        + " duration=" + task.getDurationMs() + "ms ==");
                writeLog(task, "== 任务结束 status=" + task.getStatus()
                        + " tokens=" + task.getTotalTokens() + " calls=" + task.getLlmCallCount()
                        + " duration=" + task.getDurationMs() + "ms ==");
                // 终态持久化（含完整 steps / logs / token 统计）
                store.save(task);
            }
        }
    }

    /** 普通对话任务 */
    private void runChatTask(LongTask task) {
        ChatClient.Builder builder = multiModelService.createChatClientBuilder(task.getModel());
        var promptBuilder = builder.build().prompt()
                .user(task.getMessage())
                .toolCallbacks(getAllTools().toArray(new ToolCallback[0]));
        if (task.getModel() != null && !task.getModel().isBlank()) {
            promptBuilder.options(ChatOptions.builder().model(task.getModel()).maxTokens(maxTokens).build());
        }
        task.setProgressText("正在生成回答...");
        task.recordLog("调用 LLM（chat） model=" + task.getModel());
        // 用 chatResponse() 拿 ChatResponse 以记录 token
        ChatResponse response = promptBuilder.call().chatResponse();
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var u = response.getMetadata().getUsage();
            task.addTokens(u.getPromptTokens() == null ? 0 : u.getPromptTokens(),
                    u.getCompletionTokens() == null ? 0 : u.getCompletionTokens(),
                    u.getTotalTokens() == null ? 0 : u.getTotalTokens(), 1);
        } else {
            task.addTokens(0, 0, 0, 1);
        }
        String answer = response != null && response.getResult() != null
                ? response.getResult().getOutput().getText() : "";
        task.setProgressText("");
        task.markCompleted(answer == null ? "" : answer);
        task.recordLog("chat 完成");
    }

    /** ReAct Agent 任务：流式收集步骤到任务记录 */
    private void runAgentTask(LongTask task, TokenUsageTracker taskTracker) {
        task.setProgressText("ReAct Agent 正在推理执行...");
        reActAgentService.runStream(task.getMessage(), task.getModel(), event -> {
            if (task.isInterruptRequested()) return;
            recordEvent(task, event.type(), event.iteration(),
                    event.content(), event.toolCall(), event.step());
        }, taskTracker, task::isInterruptRequested);
        Object answer = task.getMeta().get("answer");
        task.setProgressText("");
        if (task.isInterruptRequested()) {
            task.markCancelled();
            task.recordLog("== ReAct 被中断，checkpoint=" + task.getCheckpoint() + " ==");
        } else {
            task.markCompleted(answer != null ? String.valueOf(answer) : "(ReAct Agent 执行完成，无明确最终答案)");
        }
    }

    /** PSE 协作任务：流式收集步骤到任务记录 */
    private void runPseTask(LongTask task, TokenUsageTracker taskTracker) {
        task.setProgressText("PSE 正在规划执行...");
        pseOrchestrator.executeStream(task.getMessage(), task.getModel(), event -> {
            if (task.isInterruptRequested()) return;
            recordPseEvent(task, event);
        }, taskTracker, task::isInterruptRequested);
        Object deliver = task.getMeta().get("deliver");
        task.setProgressText("");
        if (task.isInterruptRequested()) {
            task.markCancelled();
            task.recordLog("== PSE 被中断，checkpoint=" + task.getCheckpoint() + " ==");
        } else {
            task.markCompleted(deliver != null ? String.valueOf(deliver) : "(PSE 协作执行完成)");
        }
    }

    // ==================== 事件记录 ====================

    private void recordEvent(LongTask task, String type, int iteration, String content,
                             ReActAgentService.ToolCallInfo toolCall,
                             ReActAgentService.ReActStep step) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("iteration", iteration);
        if (content != null) s.put("content", content);
        if (toolCall != null) {
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("name", toolCall.name());
            tc.put("input", toolCall.input());
            tc.put("output", toolCall.output());
            tc.put("durationMs", toolCall.durationMs());
            s.put("toolCall", tc);
        }
        if (step != null) s.put("step", step.toString());
        task.addStep(s);
        task.setCheckpoint(iteration);
        // 日志
        if ("tool_call".equals(type) && toolCall != null) {
            task.recordLog("[ReAct] 第" + iteration + "轮 调用工具 " + toolCall.name()
                    + " 入参=" + truncate(toolCall.input(), 200));
        } else if ("tool_result".equals(type) && toolCall != null) {
            task.recordLog("[ReAct] 第" + iteration + "轮 工具结果=" + truncate(toolCall.output(), 200));
        } else if ("answer".equals(type)) {
            task.getMeta().put("answer", content);
            task.recordLog("[ReAct] 最终答案（" + (content == null ? 0 : content.length()) + " 字符）");
        } else if ("interrupted".equals(type)) {
            task.recordLog("[ReAct] 中断：" + content);
        }
        writeLog(task, "[ReAct:" + iteration + "] " + type + " " + truncate(content == null ? "" : content, 300));
        store.save(task);
    }

    private void recordPseEvent(LongTask task, PseOrchestrator.PseStreamEvent event) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sequence", event.sequence());
        s.put("type", event.type());
        if (event.step() != null) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("role", event.step().role());
            st.put("action", event.step().action());
            st.put("title", event.step().title());
            st.put("content", event.step().content());
            if (event.step().toolCalls() != null) st.put("toolCalls", event.step().toolCalls());
            s.put("step", st);
        }
        if (event.content() != null) s.put("content", event.content());
        task.addStep(s);
        task.setCheckpoint(event.sequence());
        if ("answer".equals(event.type()) && event.content() != null) {
            task.getMeta().put("deliver", event.content());
        }
        if (event.step() != null) {
            task.recordLog("[PSE] " + event.step().role() + " | " + event.step().title()
                    + (event.step().content() == null ? "" : " | " + truncate(event.step().content(), 150)));
        } else if (event.content() != null) {
            task.recordLog("[PSE:" + event.type() + "] " + truncate(event.content(), 150));
        }
        writeLog(task, "[PSE:" + event.sequence() + "] " + event.type() + " "
                + (event.step() != null ? event.step().role() + ":" + event.step().title() : truncate(event.content() == null ? "" : event.content(), 300)));
        store.save(task);
    }

    private void writeLog(LongTask task, String line) {
        try {
            Files.writeString(logDir.resolve(task.getId() + ".log"),
                    "[" + LocalDateTime.now().format(TS) + "] " + line + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 日志写入失败不影响任务执行
            log.warn("写任务日志失败 [{}]: {}", task.getId(), e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 聚合全部可用工具：本地工具（含 skills 的 skill_run）+ MCP 工具。
     * 长时任务免审批自动获得全部能力。
     */
    private List<ToolCallback> getAllTools() {
        List<ToolCallback> all = new ArrayList<>(localToolCallbacks);
        if (mcpToolProvider != null && mcpToolProvider.isEnabled()) {
            List<ToolCallback> mcpTools = mcpToolProvider.getTools();
            if (!mcpTools.isEmpty()) {
                all.addAll(mcpTools);
            }
        }
        return all;
    }

    private String normalizeType(String type) {
        if (type == null) return LongTask.TYPE_CHAT;
        return switch (type.trim().toLowerCase()) {
            case "agent", "react" -> LongTask.TYPE_AGENT;
            case "pse" -> LongTask.TYPE_PSE;
            default -> LongTask.TYPE_CHAT;
        };
    }

    /** 关闭线程池（应用关闭时调用） */
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
