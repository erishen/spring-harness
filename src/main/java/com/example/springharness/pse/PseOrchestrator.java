package com.example.springharness.pse;

import com.example.springharness.util.ErrorSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * PSE Orchestrator：PSE（Planner-Specialist-Evaluator）多 Agent 协作编排器。
 *
 * 优化点：
 * 1. Token 用量统计：累计所有 LLM 调用的 token 消耗
 * 2. 整体超时：默认 90 秒，超时后返回已完成的部分结果
 * 3. 并行执行：无依赖的子任务并行执行，缩短总耗时
 * 4. 简单任务快速通道：只有 1 个子任务时跳过整体评审，直接交付
 */
@Service
public class PseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PseOrchestrator.class);

    private final PlannerAgent plannerAgent;
    private final SpecialistAgent specialistAgent;
    private final EvaluatorAgent evaluatorAgent;
    private final TokenUsageTracker tokenUsageTracker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单个任务最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 整体最大循环次数（防止无限循环） */
    private static final int MAX_TOTAL_ITERATIONS = 15;

    /** 整体超时时间（秒） */
    @Value("${pse.timeout-seconds:90}")
    private long timeoutSeconds;

    /** 是否启用并行执行 */
    @Value("${pse.parallel-enabled:true}")
    private boolean parallelEnabled;

    public PseOrchestrator(PlannerAgent plannerAgent,
                            SpecialistAgent specialistAgent,
                            EvaluatorAgent evaluatorAgent,
                            TokenUsageTracker tokenUsageTracker) {
        this.plannerAgent = plannerAgent;
        this.specialistAgent = specialistAgent;
        this.evaluatorAgent = evaluatorAgent;
        this.tokenUsageTracker = tokenUsageTracker;
    }

    /**
     * 执行 PSE 多 Agent 协作流程。
     */
    public PseResult execute(String userRequest, String model) {
        return executeStream(userRequest, model, event -> {});
    }

    /**
     * 流式执行 PSE 多 Agent 协作流程，每个步骤完成时通过回调推送事件。
     * 事件类型：
     * - "step": 完成一个步骤（Planner规划/Specialist执行/Evaluator评审）
     * - "answer": 最终答案
     * - "done": 执行完成
     * - "error": 执行出错
     */
    public PseResult executeStream(String userRequest, String model, Consumer<PseStreamEvent> callback) {
        return executeStream(userRequest, model, callback, this.tokenUsageTracker, () -> false);
    }

    /**
     * 流式执行 PSE（长时任务专用重载）。
     *
     * @param taskTracker 独立 token 追踪器（任务作用域，避免并发串数据）
     * @param cancelled   取消信号（关键阶段前检查，true 则中断并返回部分结果）
     */
    public PseResult executeStream(String userRequest, String model, Consumer<PseStreamEvent> callback,
                                   TokenUsageTracker taskTracker, BooleanSupplier cancelled) {
        taskTracker.reset();
        TokenUsageTracker.bind(taskTracker);
        try {
            return executeStreamInternal(userRequest, model, callback, cancelled);
        } finally {
            TokenUsageTracker.unbind();
        }
    }

    /**
     * PSE 执行主体（tracker 已绑定任务作用域）。
     */
    private PseResult executeStreamInternal(String userRequest, String model,
                                            Consumer<PseStreamEvent> callback, BooleanSupplier cancelled) {
        long startTime = System.currentTimeMillis();
        List<PseStep> steps = new ArrayList<>();
        int seq = 0;

        ExecutorService executor = Executors.newCachedThreadPool();

        try {
            // ===== 阶段 1：Planner 分解任务 =====
            PseStep planStart = PseStep.create(++seq, "planner", "plan",
                    "任务分解", "正在分析需求并分解为子任务...", null);
            steps.add(planStart);
            callback.accept(new PseStreamEvent("step", seq, planStart, null));

            String tasksJson = plannerAgent.decomposeTask(userRequest, model);
            List<PseTask> tasks = parseTasks(tasksJson);

            if (tasks.isEmpty()) {
                PseStep errorStep = PseStep.create(++seq, "system", "error",
                        "任务分解失败", "Planner 未能生成有效的子任务列表", null);
                steps.add(errorStep);
                callback.accept(new PseStreamEvent("step", seq, errorStep, null));
                callback.accept(new PseStreamEvent("error", seq, null, "任务分解失败"));
                return PseResult.failed("任务分解失败，请重试", steps, tasks,
                        System.currentTimeMillis() - startTime, currentTracker().getTotal(), currentTracker().getCallCount());
            }

            StringBuilder taskList = new StringBuilder();
            for (PseTask task : tasks) {
                taskList.append(String.format("- **%s**: %s%n", task.name(), task.description()));
                if (task.acceptanceCriteria() != null) {
                    for (String ac : task.acceptanceCriteria()) {
                        taskList.append(String.format("  - AC: %s%n", ac));
                    }
                }
            }
            PseStep planDone = PseStep.create(++seq, "planner", "plan",
                    String.format("任务分解完成（%d 个子任务）", tasks.size()),
                    taskList.toString(), null);
            steps.add(planDone);
            callback.accept(new PseStreamEvent("step", seq, planDone, null));

            // 简单任务快速通道：只有 1 个子任务时，直接执行+评审，跳过整体评审
            if (tasks.size() == 1) {
                log.info("简单任务快速通道：1 个子任务，跳过整体评审");
                PseTask task = tasks.get(0);
                task = executeTaskWithStream(task, model, steps, seq, executor, callback);
                tasks.set(0, task);
                seq = steps.size();

                // 获取任务执行结果作为备选答案
                String taskResult = task.executionResult() != null ? task.executionResult() : "任务执行完成";

                // 直接生成最终交付
                PseStep finalStart = PseStep.create(++seq, "planner", "final",
                        "生成最终交付", "正在汇总任务结果...", null);
                steps.add(finalStart);
                callback.accept(new PseStreamEvent("step", seq, finalStart, null));

                if (!isTimeout(startTime)) {
                    String finalAnswer = plannerAgent.generateFinalDelivery(userRequest, tasks, model);
                    PseStep finalDone = PseStep.create(++seq, "planner", "final",
                            "最终交付完成", finalAnswer, null);
                    steps.add(finalDone);
                    callback.accept(new PseStreamEvent("step", seq, finalDone, null));
                    callback.accept(new PseStreamEvent("answer", seq, null, finalAnswer));
                    callback.accept(new PseStreamEvent("done", seq, null, null));

                    return PseResult.success(finalAnswer, steps, tasks,
                            System.currentTimeMillis() - startTime, currentTracker().getTotal(), currentTracker().getCallCount());
                } else {
                    // 超时但任务已执行完成，用任务结果作为最终答案
                    String finalAnswer = taskResult;
                    PseStep finalDone = PseStep.create(++seq, "planner", "final",
                            "最终交付（超时，使用任务结果）", finalAnswer, null);
                    steps.add(finalDone);
                    callback.accept(new PseStreamEvent("step", seq, finalDone, null));
                    callback.accept(new PseStreamEvent("answer", seq, null, finalAnswer));
                    callback.accept(new PseStreamEvent("done", seq, null, null));

                    return PseResult.success(finalAnswer, steps, tasks,
                            System.currentTimeMillis() - startTime, currentTracker().getTotal(), currentTracker().getCallCount());
                }
            }

            // ===== 阶段 2：循环执行子任务 =====
            // 按依赖关系分层，无依赖的任务并行执行
            List<List<PseTask>> layers = buildDependencyLayers(tasks);
            log.info("任务分层：{} 层，并行执行={}", layers.size(), parallelEnabled);

            int totalIterations = 0;

            for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
                // 检查取消信号
                if (cancelled.getAsBoolean()) {
                    steps.add(PseStep.create(++seq, "system", "info",
                            "任务已中断", "用户中断执行，返回已完成的部分结果", null));
                    callback.accept(new PseStreamEvent("step", seq,
                            steps.get(steps.size() - 1), null));
                    break;
                }
                if (totalIterations >= MAX_TOTAL_ITERATIONS) {
                    steps.add(PseStep.create(++seq, "system", "error",
                            "达到最大循环次数", "任务可能未完全完成", null));
                    break;
                }

                // 检查超时
                if (isTimeout(startTime)) {
                    steps.add(PseStep.create(++seq, "system", "error",
                            "执行超时", String.format("已超过 %d 秒，返回已完成的部分结果", timeoutSeconds), null));
                    break;
                }

                List<PseTask> layer = layers.get(layerIdx);
                steps.add(PseStep.create(++seq, "system", "info",
                        String.format("执行第 %d/%d 层（%d 个任务）", layerIdx + 1, layers.size(), layer.size()),
                        parallelEnabled && layer.size() > 1 ? "并行执行" : "串行执行", null));

                if (parallelEnabled && layer.size() > 1) {
                    // 并行执行
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (PseTask task : layer) {
                        final int taskIndex = tasks.indexOf(task);
                        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                            // 子线程需重新绑定任务作用域 tracker，否则 token 会记到默认单例
                            TokenUsageTracker.bind(currentTracker());
                            try {
                                List<PseStep> taskSteps = new ArrayList<>();
                                PseTask updatedTask = executeTaskWithRetry(task, model, taskSteps, 0, executor);
                                synchronized (tasks) {
                                    tasks.set(taskIndex, updatedTask);
                                }
                                synchronized (steps) {
                                    steps.addAll(taskSteps);
                                }
                            } finally {
                                TokenUsageTracker.unbind();
                            }
                        }, executor);
                        futures.add(future);
                    }
                    // 等待所有任务完成，带超时
                    try {
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                .get(timeoutSeconds - (System.currentTimeMillis() - startTime) / 1000, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        log.warn("并行执行超时");
                        steps.add(PseStep.create(++seq, "system", "error",
                                "并行执行超时", "部分任务可能未完成", null));
                        break;
                    }
                } else {
                    // 串行执行
                    for (PseTask task : layer) {
                        if (cancelled.getAsBoolean()) {
                            steps.add(PseStep.create(++seq, "system", "info",
                                    "任务已中断", "用户中断执行，返回已完成的部分结果", null));
                            break;
                        }
                        if (isTimeout(startTime)) {
                            steps.add(PseStep.create(++seq, "system", "error",
                                    "执行超时", String.format("已超过 %d 秒", timeoutSeconds), null));
                            break;
                        }
                        int taskIndex = tasks.indexOf(task);
                        task = executeTaskWithStream(task, model, steps, seq, executor, callback);
                        seq = steps.size();
                        tasks.set(taskIndex, task);
                        totalIterations++;
                    }
                }
            }

            // ===== 阶段 3：整体评审 =====
            if (!cancelled.getAsBoolean() && !isTimeout(startTime)) {
                PseStep evalStart = PseStep.create(++seq, "evaluator", "evaluate",
                        "整体评审", "正在对所有子任务进行整体评审...", null);
                steps.add(evalStart);
                callback.accept(new PseStreamEvent("step", seq, evalStart, null));

                EvaluatorAgent.EvaluationResult overallEval = evaluatorAgent.evaluateOverall(userRequest, tasks, model);

                if (overallEval.isPass()) {
                    PseStep evalDone = PseStep.create(++seq, "evaluator", "evaluate",
                            "整体评审通过 ✅", overallEval.feedback(), null);
                    steps.add(evalDone);
                    callback.accept(new PseStreamEvent("step", seq, evalDone, null));

                    // 先汇总已完成的子任务结果，确保评审通过后立即有内容输出
                    StringBuilder summary = new StringBuilder();
                    int completedCount = 0;
                    for (PseTask task : tasks) {
                        if ("completed".equals(task.status()) && task.executionResult() != null) {
                            completedCount++;
                            summary.append("\n\n### ").append(task.name()).append("\n")
                                    .append(task.executionResult());
                        }
                    }

                    // ===== 阶段 4：Planner 生成最终交付 =====
                    if (!isTimeout(startTime)) {
                        PseStep finalStart = PseStep.create(++seq, "planner", "final",
                                "生成最终交付", "正在汇总所有子任务结果...", null);
                        steps.add(finalStart);
                        callback.accept(new PseStreamEvent("step", seq, finalStart, null));

                        String finalAnswer = plannerAgent.generateFinalDelivery(userRequest, tasks, model);
                        PseStep finalDone = PseStep.create(++seq, "planner", "final",
                                "最终交付完成", finalAnswer, null);
                        steps.add(finalDone);
                        callback.accept(new PseStreamEvent("step", seq, finalDone, null));
                        callback.accept(new PseStreamEvent("answer", seq, null, finalAnswer));
                        callback.accept(new PseStreamEvent("done", seq, null, null));

                        return PseResult.success(finalAnswer, steps, tasks,
                                System.currentTimeMillis() - startTime, currentTracker().getTotal(), currentTracker().getCallCount());
                    } else {
                        // 超时但评审已通过，用汇总结果作为最终答案
                        String finalAnswer = "任务执行完成（" + completedCount + "/" + tasks.size() + " 子任务完成）：" + summary;
                        PseStep finalDone = PseStep.create(++seq, "planner", "final",
                                "最终交付（超时，使用汇总结果）", finalAnswer, null);
                        steps.add(finalDone);
                        callback.accept(new PseStreamEvent("step", seq, finalDone, null));
                        callback.accept(new PseStreamEvent("answer", seq, null, finalAnswer));
                        callback.accept(new PseStreamEvent("done", seq, null, null));

                        return PseResult.success(finalAnswer, steps, tasks,
                                System.currentTimeMillis() - startTime, currentTracker().getTotal(), currentTracker().getCallCount());
                    }
                } else {
                    PseStep evalFail = PseStep.create(++seq, "evaluator", "evaluate",
                            "整体评审未通过 ❌", overallEval.feedback(), null);
                    steps.add(evalFail);
                    callback.accept(new PseStreamEvent("step", seq, evalFail, null));
                }
            }

            // 超时或评审未通过，返回已完成的部分结果
            String finalAnswer = "任务执行完成（部分结果）：";
            for (PseTask task : tasks) {
                if ("completed".equals(task.status()) && task.executionResult() != null) {
                    finalAnswer += "\n\n### " + task.name() + "\n" + task.executionResult();
                }
            }
            callback.accept(new PseStreamEvent("answer", seq, null, finalAnswer));
            callback.accept(new PseStreamEvent("done", seq, null, null));
            return PseResult.failed(finalAnswer, steps, tasks,
                    System.currentTimeMillis() - startTime, currentTracker().getTotal(), currentTracker().getCallCount());

        } catch (Exception e) {
            log.error("PSE 执行失败", e);
            String safeMsg = ErrorSanitizer.sanitize(e);
            PseStep errorStep = PseStep.create(++seq, "system", "error",
                    "执行异常", safeMsg, null);
            steps.add(errorStep);
            callback.accept(new PseStreamEvent("step", seq, errorStep, null));
            callback.accept(new PseStreamEvent("error", seq, null, safeMsg));
            return PseResult.failed("PSE 执行异常：" + safeMsg, steps, new ArrayList<>(),
                    System.currentTimeMillis() - startTime, currentTracker().getTotal(), currentTracker().getCallCount());
        } finally {
            executor.shutdownNow();
        }
    }

    // ==================== 私有方法 ====================

    /** 执行单个任务（含重试），支持流式回调 */
    private PseTask executeTaskWithRetry(PseTask task, String model, List<PseStep> steps,
                                       int startSeq, ExecutorService executor) {
        return executeTaskWithStream(task, model, steps, startSeq, executor, event -> {});
    }

    /** 执行单个任务（含重试），流式版本 */
    private PseTask executeTaskWithStream(PseTask task, String model, List<PseStep> steps,
                                        int startSeq, ExecutorService executor,
                                        Consumer<PseStreamEvent> callback) {
        int seq = startSeq;
        int retryCount = 0;

        while (retryCount <= MAX_RETRY) {
            // --- Specialist 执行 ---
            PseStep execStart = PseStep.create(++seq, "specialist", "execute",
                    String.format("执行任务：%s（第 %d 次）", task.name(), retryCount + 1),
                    "正在执行子任务...", task.id());
            steps.add(execStart);
            callback.accept(new PseStreamEvent("step", seq, execStart, null));

            SpecialistAgent.ExecutionResult execResult = specialistAgent.executeTask(task, model);
            task = task.withExecutionResult(execResult.result());

            PseStep execDone = PseStep.withToolCalls(++seq, "specialist", "execute",
                    String.format("任务执行完成：%s", task.name()),
                    execResult.result(), task.id(), execResult.toolCalls());
            steps.add(execDone);
            callback.accept(new PseStreamEvent("step", seq, execDone, null));

            // --- Evaluator 评审 ---
            PseStep evalStart = PseStep.create(++seq, "evaluator", "evaluate",
                    String.format("评审任务：%s", task.name()),
                    "正在独立验证验收标准...", task.id());
            steps.add(evalStart);
            callback.accept(new PseStreamEvent("step", seq, evalStart, null));

            EvaluatorAgent.EvaluationResult evalResult = evaluatorAgent.evaluateTask(task, model);
            task = task.withEvaluation(evalResult.verdict(), evalResult.feedback());

            String evalEmoji = evalResult.isPass() ? "✅" : "❌";
            PseStep evalDone = PseStep.create(++seq, "evaluator", "evaluate",
                    String.format("评审结果：%s %s", evalEmoji, evalResult.verdict().toUpperCase()),
                    evalResult.feedback(), task.id());
            steps.add(evalDone);
            callback.accept(new PseStreamEvent("step", seq, evalDone, null));

            if (evalResult.isPass()) {
                task = task.withStatus("completed");
                break;
            } else {
                retryCount++;
                if (retryCount <= MAX_RETRY) {
                    PseStep retryStep = PseStep.create(++seq, "planner", "decide",
                            String.format("任务未通过，准备重试（第 %d 次）", retryCount + 1),
                            "失败原因：" + evalResult.feedback(), task.id());
                    steps.add(retryStep);
                    callback.accept(new PseStreamEvent("step", seq, retryStep, null));
                } else {
                    task = task.withStatus("failed");
                    PseStep failStep = PseStep.create(++seq, "planner", "decide",
                            String.format("任务 %s 达到最大重试次数，标记失败", task.name()),
                            "将继续执行后续任务", task.id());
                    steps.add(failStep);
                    callback.accept(new PseStreamEvent("step", seq, failStep, null));
                }
            }
        }
        return task;
    }

    /** 按依赖关系分层，同一层的任务无依赖可并行执行 */
    private List<List<PseTask>> buildDependencyLayers(List<PseTask> tasks) {
        List<List<PseTask>> layers = new ArrayList<>();
        List<PseTask> remaining = new ArrayList<>(tasks);
        List<String> completedNames = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<PseTask> currentLayer = new ArrayList<>();
            for (PseTask task : remaining) {
                List<String> deps = task.dependencies();
                if (deps == null || deps.isEmpty() || completedNames.containsAll(deps)) {
                    currentLayer.add(task);
                }
            }
            if (currentLayer.isEmpty()) {
                // 有循环依赖，剩余任务全部放入一层
                layers.add(new ArrayList<>(remaining));
                break;
            }
            layers.add(currentLayer);
            for (PseTask task : currentLayer) {
                completedNames.add(task.name());
                remaining.remove(task);
            }
        }
        return layers;
    }

    /**
     * 获取当前生效的 token 追踪器：优先任务作用域绑定的，否则回退默认单例。
     */
    private TokenUsageTracker currentTracker() {
        return TokenUsageTracker.current(this.tokenUsageTracker);
    }

    /** 检查是否超时 */
    private boolean isTimeout(long startTime) {
        return (System.currentTimeMillis() - startTime) / 1000 >= timeoutSeconds;
    }

    /**
     * 解析 Planner 返回的任务 JSON。
     */
    private List<PseTask> parseTasks(String tasksJson) {
        try {
            List<Map<String, Object>> rawTasks = objectMapper.readValue(tasksJson,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<PseTask> tasks = new ArrayList<>();
            for (int i = 0; i < rawTasks.size(); i++) {
                Map<String, Object> raw = rawTasks.get(i);
                String id = "task-" + (i + 1);
                String name = (String) raw.getOrDefault("name", "任务 " + (i + 1));
                String description = (String) raw.getOrDefault("description", "");

                @SuppressWarnings("unchecked")
                List<String> ac = raw.get("acceptanceCriteria") instanceof List
                        ? (List<String>) raw.get("acceptanceCriteria")
                        : List.of();

                @SuppressWarnings("unchecked")
                List<String> deps = raw.get("dependencies") instanceof List
                        ? (List<String>) raw.get("dependencies")
                        : List.of();

                tasks.add(PseTask.create(id, name, description, ac, deps));
            }
            if (!tasks.isEmpty()) {
                return tasks;
            }
        } catch (Exception e) {
            log.warn("任务解析失败（JSON格式）: {}, 原始内容: {}", e.getMessage(),
                    tasksJson != null && tasksJson.length() > 200 ? tasksJson.substring(0, 200) + "..." : tasksJson);
        }

        // Fallback 1: 尝试用正则提取任务名称（LLM 可能返回非标准格式）
        List<PseTask> fallbackTasks = parseTasksFallback(tasksJson);
        if (!fallbackTasks.isEmpty()) {
            log.info("使用 fallback 解析到 {} 个子任务", fallbackTasks.size());
            return fallbackTasks;
        }

        // Fallback 2: 直接创建一个子任务，让 Specialist 处理整个请求
        log.warn("所有解析方式失败，创建单任务 fallback");
        return List.of(PseTask.create("task-1", "执行用户请求",
                "根据用户需求完成任务，调用可用工具获取所需信息并给出最终答案",
                List.of("成功获取所需信息", "给出清晰的最终答案"),
                List.of()));
    }

    /** Fallback 解析：从非标准 JSON 或文本中提取任务 */
    private List<PseTask> parseTasksFallback(String text) {
        if (text == null || text.trim().isEmpty()) return List.of();
        List<PseTask> tasks = new ArrayList<>();
        // 尝试匹配 "name": "xxx" 或 name: xxx 格式
        java.util.regex.Pattern namePattern = java.util.regex.Pattern.compile(
                "\"?name\"?\\s*[:=]\\s*\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = namePattern.matcher(text);
        int idx = 1;
        while (matcher.find() && idx <= 5) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) {
                tasks.add(PseTask.create("task-" + idx, name,
                        "执行任务：" + name,
                        List.of("任务完成并输出结果"),
                        List.of()));
                idx++;
            }
        }
        return tasks;
    }

    /**
     * PSE 流式事件。
     * type: step / answer / done / error
     */
    public record PseStreamEvent(String type, int sequence, PseStep step, String content) {}
}
