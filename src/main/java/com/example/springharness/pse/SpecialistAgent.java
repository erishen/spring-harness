package com.example.springharness.pse;

import com.example.springharness.service.MultiModelService;
import com.example.springharness.util.ContextGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Specialist Agent：实施者/执行者。
 *
 * 职责：
 * 1. 执行 Planner 分配的子任务
 * 2. 可以调用工具（计算器、时间、股票查询）
 * 3. 使用 ReAct 模式：思考→调用工具→观察→再思考
 * 4. 完成后汇报执行结果
 *
 * 约束：不越权、不规划、不判断整体完成，只做被分配的子任务。
 */
@Service
public class SpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(SpecialistAgent.class);

    private final MultiModelService multiModelService;
    private final List<ToolCallback> toolCallbacks;
    private final ToolDescriptionService toolDescriptionService;
    private final TokenUsageTracker tokenUsageTracker;
    private final SoulService soulService;
    private final McpToolProvider mcpToolProvider;

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    /** 最大工具调用循环次数 */
    private static final int MAX_ITERATIONS = 8;

    public SpecialistAgent(MultiModelService multiModelService, List<ToolCallback> toolCallbacks,
                           ToolDescriptionService toolDescriptionService,
                           TokenUsageTracker tokenUsageTracker, SoulService soulService,
                           McpToolProvider mcpToolProvider) {
        this.multiModelService = multiModelService;
        this.toolCallbacks = toolCallbacks;
        this.toolDescriptionService = toolDescriptionService;
        this.tokenUsageTracker = tokenUsageTracker;
        this.soulService = soulService;
        this.mcpToolProvider = mcpToolProvider;
    }

    /**
     * 执行子任务，返回执行结果和工具调用过程。
     */
    public ExecutionResult executeTask(PseTask task, String model) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(task)));
        messages.add(new UserMessage("请执行以下任务：\n\n" + task.description()));

        List<PseStep.ToolCallInfo> toolCallInfos = new ArrayList<>();
        String finalResult = "";

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            Prompt prompt = buildPrompt(messages, model);
            ChatResponse response = multiModelService.getChatModel(model).call(prompt);
            TokenUsageTracker.current(tokenUsageTracker).record(response);
            AssistantMessage assistantMsg = response.getResult().getOutput();

            // 检查是否有工具调用
            if (!response.hasToolCalls() || assistantMsg.getToolCalls() == null || assistantMsg.getToolCalls().isEmpty()) {
                finalResult = assistantMsg.getText() != null ? assistantMsg.getText() : "";
                break;
            }

            // 有工具调用，加入历史
            messages.add(assistantMsg);

            // 执行所有工具调用
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (var toolCall : assistantMsg.getToolCalls()) {
                String toolName = toolCall.name();
                String toolInput = toolCall.arguments();
                String toolId = toolCall.id();

                long startTime = System.currentTimeMillis();
                String toolResult = ContextGuard.truncateToolOutput(executeTool(toolName, toolInput));
                long durationMs = System.currentTimeMillis() - startTime;

                toolResponses.add(new ToolResponseMessage.ToolResponse(toolId, toolName, toolResult));
                toolCallInfos.add(new PseStep.ToolCallInfo(toolName, toolInput, toolResult, durationMs));
            }

            messages.add(ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .metadata(Map.of())
                    .build());

            // 上下文防护：裁剪过大的历史，防止 LLM 上下文超长
            ContextGuard.trimMessages(messages, ContextGuard.MAX_HISTORY_CHARS);
        }

        if (finalResult.isBlank()) {
            finalResult = "(达到最大循环次数，任务可能未完成)";
        }

        return new ExecutionResult(finalResult, toolCallInfos);
    }

    // ==================== 私有方法 ====================

    private String buildSystemPrompt(PseTask task) {
        StringBuilder ac = new StringBuilder();
        if (task.acceptanceCriteria() != null) {
            for (String criterion : task.acceptanceCriteria()) {
                ac.append("- ").append(criterion).append("\n");
            }
        }

        String toolList = toolDescriptionService.generateToolList();
        // 采用 souls/specialist/SOUL.md 作为角色定义，追加任务特定的执行指令
        String soul = soulService.getSoul("specialist");
        String soulBlock = soul.isBlank() ? "" : soul + "\n\n";

        return soulBlock + """
                你正在「执行一个子任务」。

                当前任务：%s

                验收标准：
                %s
                可用工具：
                %s

                请用中文汇报执行结果。
                """.formatted(task.name(), ac, toolList);
    }

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

    private String executeTool(String toolName, String toolInput) {
        for (ToolCallback callback : getAllTools()) {
            if (callback.getToolDefinition().name().equals(toolName)) {
                try {
                    return callback.call(toolInput);
                } catch (Exception e) {
                    return "工具执行失败: " + e.getMessage();
                }
            }
        }
        return "未找到工具: " + toolName;
    }

    /**
     * 聚合全部可用工具：本地工具（含 skills 的 skill_run）+ MCP 工具。
     */
    private List<ToolCallback> getAllTools() {
        List<ToolCallback> all = new ArrayList<>(toolCallbacks);
        if (mcpToolProvider != null && mcpToolProvider.isEnabled()) {
            List<ToolCallback> mcpTools = mcpToolProvider.getTools();
            if (!mcpTools.isEmpty()) {
                all.addAll(mcpTools);
            }
        }
        return all;
    }

    /**
     * 执行结果。
     */
    public record ExecutionResult(String result, List<PseStep.ToolCallInfo> toolCalls) {}
}
