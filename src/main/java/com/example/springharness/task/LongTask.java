package com.example.springharness.task;

import com.example.springharness.pse.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长时任务实体。
 *
 * <p>一个异步执行的 Agent 任务（普通对话 / ReAct Agent / PSE 协作），
 * 提交后由 TaskManager 放入线程池后台执行，状态可随时查询。
 *
 * <p>状态流转：pending → running → completed / failed / cancelled
 */
public class LongTask {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    /** 任务类型 */
    public static final String TYPE_CHAT = "chat";   // 普通对话
    public static final String TYPE_AGENT = "agent"; // ReAct Agent
    public static final String TYPE_PSE = "pse";     // PSE 协作

    private final String id;
    private final String type;
    private final String model;
    private final String message;

    private volatile String status;
    /** 执行过程步骤（PSE 步骤 / ReAct 步骤） */
    private final List<Map<String, Object>> steps = new ArrayList<>();
    /** 最新进度文本（如当前正在执行的子任务） */
    private volatile String progressText = "";
    private volatile String result = "";
    private volatile String error = "";
    /** 额外元数据（tokenUsage、iterations 等） */
    private final Map<String, Object> meta = new LinkedHashMap<>();

    // ==================== token 统计（任务独立，不共享单例） ====================
    private volatile long promptTokens = 0;
    private volatile long completionTokens = 0;
    private volatile long totalTokens = 0;
    private volatile long llmCallCount = 0;

    // ==================== 执行日志（内存最近 N 条 + 落盘由 TaskManager 负责） ====================
    private static final int MAX_LOGS = 300;
    private final List<String> logs = new ArrayList<>();

    /** 中断信号：cancel 时置为 true，执行循环每轮检查 */
    private volatile boolean interruptRequested = false;
    /** 中断时已执行到的步数（checkpoint） */
    private volatile int checkpoint = 0;

    private final long createdAt;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile long durationMs;

    /** 取消标记 */
    private volatile boolean cancelled = false;

    public LongTask(String type, String model, String message) {
        this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.type = type;
        this.model = model;
        this.message = message;
        this.status = STATUS_PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * 从持久化快照恢复任务（重启后重建）。
     */
    public static LongTask restore(String id, String type, String model, String message,
                                   String status, List<Map<String, Object>> steps,
                                   String progressText, String result, String error,
                                   Map<String, Object> meta,
                                   long promptTokens, long completionTokens, long totalTokens,
                                   long llmCallCount, List<String> logs,
                                   int checkpoint, long createdAt, long startedAt,
                                   long finishedAt, long durationMs) {
        LongTask t = new LongTask(type, model, message);
        // 用反射覆写 final 字段：恢复真实 id / createdAt
        try {
            var f = LongTask.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
            var cf = LongTask.class.getDeclaredField("createdAt");
            cf.setAccessible(true);
            cf.set(t, createdAt);
        } catch (Exception ignored) {
            // 恢复失败则保留自动生成的 id / 当前时间
        }
        t.status = status;
        t.steps.addAll(steps);
        t.progressText = progressText;
        t.result = result;
        t.error = error;
        t.meta.putAll(meta);
        t.promptTokens = promptTokens;
        t.completionTokens = completionTokens;
        t.totalTokens = totalTokens;
        t.llmCallCount = llmCallCount;
        t.logs.addAll(logs);
        t.checkpoint = checkpoint;
        t.startedAt = startedAt;
        t.finishedAt = finishedAt;
        t.durationMs = durationMs;
        return t;
    }

    // ==================== 状态操作 ====================

    public void markRunning() {
        this.status = STATUS_RUNNING;
        this.startedAt = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = STATUS_COMPLETED;
        this.result = result;
        this.finishedAt = System.currentTimeMillis();
        this.durationMs = this.finishedAt - Math.max(this.startedAt, this.createdAt);
    }

    public void markFailed(String error) {
        this.status = STATUS_FAILED;
        this.error = error;
        this.finishedAt = System.currentTimeMillis();
        this.durationMs = this.finishedAt - Math.max(this.startedAt, this.createdAt);
    }

    public void markCancelled() {
        this.status = STATUS_CANCELLED;
        this.finishedAt = System.currentTimeMillis();
        this.durationMs = this.finishedAt - Math.max(this.startedAt, this.createdAt);
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    /** 追加一个执行步骤（线程安全） */
    public synchronized void addStep(Map<String, Object> step) {
        steps.add(step);
    }

    /** 追加一条执行日志（线程安全，最多保留 MAX_LOGS 条） */
    public synchronized void recordLog(String line) {
        logs.add(line);
        if (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
    }

    /** 累计 token 消耗（线程安全） */
    public synchronized void addTokens(long prompt, long completion, long total, long calls) {
        this.promptTokens += prompt;
        this.completionTokens += completion;
        this.totalTokens += total;
        this.llmCallCount += calls;
    }

    /** 累计一次 LLM 调用的 token 用量（usage 可能为 null） */
    public synchronized void addTokenUsage(TokenUsage usage) {
        if (usage == null) {
            llmCallCount++;
            return;
        }
        promptTokens += usage.promptTokens();
        completionTokens += usage.completionTokens();
        totalTokens += usage.totalTokens();
        llmCallCount++;
    }

    // ==================== getters ====================

    public String getId() { return id; }
    public String getType() { return type; }
    public String getModel() { return model; }
    public String getMessage() { return message; }
    public String getStatus() { return status; }
    public List<Map<String, Object>> getSteps() { return steps; }
    public String getProgressText() { return progressText; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public Map<String, Object> getMeta() { return meta; }
    public long getCreatedAt() { return createdAt; }
    public long getStartedAt() { return startedAt; }
    public long getFinishedAt() { return finishedAt; }
    public long getDurationMs() { return durationMs; }
    public boolean isCancelled() { return cancelled; }

    public void setProgressText(String progressText) { this.progressText = progressText; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public long getTotalTokens() { return totalTokens; }
    public long getLlmCallCount() { return llmCallCount; }
    public List<String> getLogs() { return logs; }
    public boolean isInterruptRequested() { return interruptRequested; }
    public void setInterruptRequested(boolean interruptRequested) { this.interruptRequested = interruptRequested; }
    public int getCheckpoint() { return checkpoint; }
    public void setCheckpoint(int checkpoint) { this.checkpoint = checkpoint; }

    /** 当前累计 token 用量（用于 ReAct/PSE 事件） */
    public TokenUsage currentTokenUsage() {
        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    /**
     * 转换为前端可用的 Map（含格式化后的时间/耗时）。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("type", type);
        map.put("model", model);
        map.put("message", message);
        map.put("status", status);
        map.put("steps", steps);
        map.put("progressText", progressText);
        map.put("result", result);
        map.put("error", error);
        map.put("meta", meta);
        map.put("createdAt", createdAt);
        map.put("durationMs", durationMs);
        map.put("terminal", isTerminal());
        map.put("checkpoint", checkpoint);
        // token 统计
        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        tokenUsage.put("promptTokens", promptTokens);
        tokenUsage.put("completionTokens", completionTokens);
        tokenUsage.put("totalTokens", totalTokens);
        tokenUsage.put("llmCallCount", llmCallCount);
        map.put("tokenUsage", tokenUsage);
        // 日志
        map.put("logs", new ArrayList<>(logs));
        return map;
    }
}
