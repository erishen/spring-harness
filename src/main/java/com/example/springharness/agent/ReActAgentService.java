package com.example.springharness.agent;

import com.example.springharness.util.ErrorSanitizer;
import com.example.springharness.memory.MemoryService;
import com.example.springharness.pse.McpToolProvider;
import com.example.springharness.service.MultiModelService;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.example.springharness.pse.TokenUsage;
import com.example.springharness.pse.TokenUsageTracker;
import com.example.springharness.service.PromptService;
import com.example.springharness.util.ContextGuard;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * ReAct Agent：推理（Reasoning）+ 行动（Acting）多轮循环。
 *
 * 与基础 Agent 的区别：
 * - 基础 Agent：LLM 决策一次 → 调用工具 → 返回结果（单轮）
 * - ReAct Agent：LLM 思考 → 调用工具 → 观察结果 → 再思考 → 再调用工具... 直到任务完成（多轮）
 *
 * 循环流程：
 * 1. Thought：LLM 分析问题，决定下一步行动
 * 2. Action：调用工具（可能同时调用多个）
 * 3. Observation：获取工具返回结果
 * 4. 重复 1-3，直到 LLM 认为任务完成，给出最终答案
 *
 * 最大循环次数由 maxIterations 控制（防止无限循环）。
 */
@Service
public class ReActAgentService {

    private final MultiModelService multiModelService;
    private final List<ToolCallback> toolCallbacks;
    private final TokenUsageTracker tokenUsageTracker;
    private final PromptService promptService;
    private final McpToolProvider mcpToolProvider;
    private final MemoryService memoryService;

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    /** 最大循环次数，防止无限循环 */
    private static final int MAX_ITERATIONS = 10;

    public ReActAgentService(MultiModelService multiModelService, List<ToolCallback> toolCallbacks,
                             TokenUsageTracker tokenUsageTracker,
                             PromptService promptService,
                             McpToolProvider mcpToolProvider,
                             MemoryService memoryService) {
        this.multiModelService = multiModelService;
        this.toolCallbacks = toolCallbacks;
        this.tokenUsageTracker = tokenUsageTracker;
        this.promptService = promptService;
        this.mcpToolProvider = mcpToolProvider;
        this.memoryService = memoryService;
    }

    /**
     * 执行 ReAct 循环，返回最终答案和完整的思考/行动/观察过程。
     */
    public ReActResult run(String userMessage, String model) {
        List<ReActStep> steps = new ArrayList<>();
        String answer = runStream(userMessage, model, event -> {
            if (event.type().equals("step")) {
                steps.add(event.step());
            }
        });
        return new ReActResult(answer, steps, tokenUsageTracker.getTotal(), tokenUsageTracker.getCallCount());
    }

    /**
     * 流式执行 ReAct 循环，每完成一个步骤就通过回调推送事件。
     * 事件类型：
     * - "thinking": LLM 正在思考（推送思考内容）
     * - "tool_call": 正在调用工具（推送工具调用信息）
     * - "tool_result": 工具调用完成（推送工具结果）
     * - "step": 完成一轮思考+行动（推送完整步骤）
     * - "answer": 最终答案（推送答案内容）
     * - "done": 执行完成
     */
    public String runStream(String userMessage, String model, Consumer<ReActStreamEvent> callback) {
        return runStream(userMessage, model, callback, this.tokenUsageTracker, () -> false);
    }

    /**
     * 流式执行 ReAct 循环（长时任务专用重载）。
     *
     * @param userMessage 用户消息
     * @param model       模型
     * @param callback    事件回调
     * @param tracker     独立 token 追踪器（每个任务一个，避免共享单例并发污染）
     * @param cancelled   取消信号（每轮循环前检查，true 则中断）
     * @return 最终答案；被中断时返回空串（调用方据 cancelled 判断）
     */
    public String runStream(String userMessage, String model, Consumer<ReActStreamEvent> callback,
                            TokenUsageTracker tracker, BooleanSupplier cancelled) {
        tracker.reset();
        TokenUsageTracker.bind(tracker);
        try {
            return runStreamInternal(userMessage, model, callback, cancelled);
        } finally {
            TokenUsageTracker.unbind();
        }
    }

    /**
     * ReAct 循环主体（tracker 已由外部绑定到任务作用域）。
     */
    private String runStreamInternal(String userMessage, String model,
                                     Consumer<ReActStreamEvent> callback, BooleanSupplier cancelled) {
        // 构建对话历史：基础系统提示 + 长期记忆
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(userMessage)));
        messages.add(new UserMessage(userMessage));

        // 记录每一轮的过程
        List<ReActStep> steps = new ArrayList<>();

        String finalAnswer = "";

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            // 检查取消信号：每轮循环开始前可中断
            if (cancelled.getAsBoolean()) {
                callback.accept(new ReActStreamEvent("interrupted", iteration + 1, null,
                        "任务已被用户中断，已执行到第 " + iteration + " 轮", null, null));
                callback.accept(new ReActStreamEvent("done", iteration + 1, null, null, null, null));
                return "";
            }

            // 1. 调用 LLM
            callback.accept(new ReActStreamEvent("thinking", iteration + 1, null, null, null, null));
            Prompt prompt = buildPrompt(messages, model);
            ChatResponse response = multiModelService.getChatModel(model).call(prompt);
            TokenUsageTracker.current(tokenUsageTracker).record(response);
            AssistantMessage assistantMsg = response.getResult().getOutput();

            // 2. 检查是否有工具调用
            if (!response.hasToolCalls() || assistantMsg.getToolCalls() == null || assistantMsg.getToolCalls().isEmpty()) {
                // 没有工具调用，LLM 给出了最终答案
                finalAnswer = assistantMsg.getText() != null ? assistantMsg.getText() : "";
                ReActStep step = new ReActStep(iteration + 1, "最终回答", finalAnswer, null, null);
                steps.add(step);
                callback.accept(new ReActStreamEvent("answer", iteration + 1, null, finalAnswer, step, null));
                callback.accept(new ReActStreamEvent("done", iteration + 1, null, null, null, null));
                break;
            }

            // 3. 有工具调用，记录 Thought（LLM 的思考内容）
            // 注意：部分模型（如 agnes-2.0-flash）返回工具调用时 text 可能为空白
            //（推理内容在独立 reasoning 字段，未进入 getText()），需兜底避免空步骤
            String thought = (assistantMsg.getText() != null && !assistantMsg.getText().isBlank())
                    ? assistantMsg.getText().trim()
                    : "(正在思考，准备调用工具...)";
            messages.add(assistantMsg);
            callback.accept(new ReActStreamEvent("thinking", iteration + 1, null, thought, null, null));

            // 4. 执行所有工具调用
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            List<ToolCallInfo> toolCallInfos = new ArrayList<>();

            for (var toolCall : assistantMsg.getToolCalls()) {
                String toolName = toolCall.name();
                String toolInput = toolCall.arguments();
                String toolId = toolCall.id();

                callback.accept(new ReActStreamEvent("tool_call", iteration + 1,
                        new ToolCallInfo(toolName, toolInput, null, 0), null, null, null));

                long startTime = System.currentTimeMillis();
                String toolResult = ContextGuard.truncateToolOutput(executeTool(toolName, toolInput));
                long durationMs = System.currentTimeMillis() - startTime;

                ToolCallInfo toolCallInfo = new ToolCallInfo(toolName, toolInput, toolResult, durationMs);
                toolResponses.add(new ToolResponseMessage.ToolResponse(toolId, toolName, toolResult));
                toolCallInfos.add(toolCallInfo);

                callback.accept(new ReActStreamEvent("tool_result", iteration + 1, toolCallInfo, null, null, null));
            }

            // 5. 把工具结果加入对话历史
            messages.add(ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .metadata(Map.of())
                    .build());

            // 上下文防护：裁剪过大的历史，防止 LLM 上下文超长
            ContextGuard.trimMessages(messages, ContextGuard.MAX_HISTORY_CHARS);

            // 6. 记录这一轮的过程
            ReActStep step = new ReActStep(iteration + 1, "思考+行动", thought, toolCallInfos, null);
            steps.add(step);
            callback.accept(new ReActStreamEvent("step", iteration + 1, null, null, step, null));

            // 如果达到最大循环次数，强制结束
            if (iteration == MAX_ITERATIONS - 1) {
                finalAnswer = "(达到最大循环次数 " + MAX_ITERATIONS + "，任务可能未完成)";
                ReActStep finalStep = new ReActStep(iteration + 2, "强制结束", finalAnswer, null, null);
                steps.add(finalStep);
                callback.accept(new ReActStreamEvent("answer", iteration + 2, null, finalAnswer, finalStep, null));
                callback.accept(new ReActStreamEvent("done", iteration + 2, null, null, null, null));
            }
        }

        // 抽取用户消息中的长期记忆（异步）
        memoryService.extractAndStore(userMessage, "react");

        return finalAnswer;
    }

    /**
     * 构建 Prompt，包含工具回调配置。
     * 关键：internalToolExecutionEnabled(false) 禁用内部工具执行，
     * 这样 LLM 返回工具调用后不会自动执行，由我们手动执行并控制多轮循环。
     */
    private Prompt buildPrompt(List<Message> messages, String model) {
        ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder()
                .toolCallbacks(getAllTools())
                .internalToolExecutionEnabled(false)
                .maxTokens(maxTokens);

        if (model != null && !model.isBlank()) {
            optionsBuilder.model(model);
        }

        return new Prompt(messages, optionsBuilder.build());
    }

    /**
     * 执行单个工具。
     */
    private String executeTool(String toolName, String toolInput) {
        for (ToolCallback callback : getAllTools()) {
            if (callback.getToolDefinition().name().equals(toolName)) {
                try {
                    // 工具输出脱敏：过滤 API Key/token/secret 等敏感凭证，防止泄露到 LLM 上下文或持久化存储
                    return ErrorSanitizer.sanitizeContent(callback.call(toolInput));
                } catch (Exception e) {
                    return "工具执行失败: " + ErrorSanitizer.sanitize(e);
                }
            }
        }
        return "未找到工具: " + toolName;
    }

    /**
     * 聚合全部可用工具：本地工具（含 skills 的 skill_run）+ MCP 工具。
     * 自动去重：本地工具优先，MCP 工具与本地重名时跳过。
     */
    private List<ToolCallback> getAllTools() {
        // 使用 LinkedHashMap 按工具名去重，保持插入顺序，本地工具优先
        java.util.LinkedHashMap<String, ToolCallback> uniqueTools = new java.util.LinkedHashMap<>();
        for (ToolCallback tool : toolCallbacks) {
            uniqueTools.put(tool.getToolDefinition().name(), tool);
        }
        if (mcpToolProvider != null && mcpToolProvider.isEnabled()) {
            List<ToolCallback> mcpTools = mcpToolProvider.getTools();
            for (ToolCallback tool : mcpTools) {
                String name = tool.getToolDefinition().name();
                if (!uniqueTools.containsKey(name)) {
                    uniqueTools.put(name, tool);
                }
            }
        }
        return new ArrayList<>(uniqueTools.values());
    }

    /**
     * 构建 ReAct 系统提示。
     */
    private String buildSystemPrompt(String userMessage) {
        return promptService.reactAgentPrompt() + memoryService.buildMemoryContext(userMessage);
    }

    // ==================== 数据结构 ====================

    /**
     * ReAct 执行结果。
     */
    public record ReActResult(String answer, List<ReActStep> steps,
                               TokenUsage tokenUsage, long llmCallCount) {}

    /**
     * ReAct 流式事件。
     * type: thinking / tool_call / tool_result / step / answer / done
     */
    public record ReActStreamEvent(String type, int iteration,
                                    ToolCallInfo toolCall, String content,
                                    ReActStep step, TokenUsage tokenUsage) {}

    /**
     * ReAct 每一轮的步骤记录。
     */
    public record ReActStep(int iteration, String type, String thought, List<ToolCallInfo> toolCalls, String observation) {}

    /**
     * 工具调用信息。
     */
    public record ToolCallInfo(String name, String input, String output, long durationMs) {}
}
