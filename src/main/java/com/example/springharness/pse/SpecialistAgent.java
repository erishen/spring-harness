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
import java.util.Set;

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

    /** 单轮最多执行的工具调用数：LLM 可能一次返回大量工具调用（曾达 21 个），
     *  大量 arguments + responses 会撑爆上下文（单条消息无法被 trim 部分裁剪），故限流。 */
    private static final int MAX_TOOLS_PER_ROUND = 6;

    /** 单次子任务总工具调用预算，超过强制结束，防止无限膨胀 */
    private static final int MAX_TOOLS_PER_TASK = 30;

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
        int totalToolCalls = 0;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            // 发送前兜底：基于真实消息开销（含 toolCalls/响应数据）裁剪，防止 LLM 上下文超长
            ContextGuard.ensureSafeMessages(messages);
            Prompt prompt = buildPrompt(messages, model);
            ChatResponse response = multiModelService.getChatModel(model).call(prompt);
            TokenUsageTracker.current(tokenUsageTracker).record(response);
            AssistantMessage assistantMsg = response.getResult().getOutput();

            // 检查是否有工具调用
            if (!response.hasToolCalls() || assistantMsg.getToolCalls() == null || assistantMsg.getToolCalls().isEmpty()) {
                finalResult = assistantMsg.getText() != null ? assistantMsg.getText() : "";
                break;
            }

            // 限制本轮执行的工具数量：只执行前 MAX_TOOLS_PER_ROUND 个。
            // 同时重建 assistant 消息仅保留这 N 个 toolCalls，丢弃其余，
            // 避免一次性返回大量 toolCalls（其 arguments 会撑爆单条消息）。
            List<AssistantMessage.ToolCall> allToolCalls = assistantMsg.getToolCalls();
            boolean truncated = allToolCalls.size() > MAX_TOOLS_PER_ROUND;
            List<AssistantMessage.ToolCall> toExecute = truncated
                    ? new ArrayList<>(allToolCalls.subList(0, MAX_TOOLS_PER_ROUND))
                    : allToolCalls;

            if (truncated) {
                log.warn("任务 [{}] 本轮 LLM 返回 {} 个工具调用，上下文安全限制仅执行前 {} 个",
                        task.name(), allToolCalls.size(), MAX_TOOLS_PER_ROUND);
                assistantMsg = AssistantMessage.builder()
                        .content(assistantMsg.getText())
                        .toolCalls(toExecute)
                        .build();
            }
            messages.add(assistantMsg);

            // 执行本轮的受限工具调用
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (var toolCall : toExecute) {
                String toolName = toolCall.name();
                String toolInput = toolCall.arguments();
                String toolId = toolCall.id();

                long startTime = System.currentTimeMillis();
                String toolResult = ContextGuard.truncateToolOutput(executeTool(toolName, toolInput));
                long durationMs = System.currentTimeMillis() - startTime;

                toolResponses.add(new ToolResponseMessage.ToolResponse(toolId, toolName, toolResult));
                toolCallInfos.add(new PseStep.ToolCallInfo(toolName, toolInput, toolResult, durationMs));
                totalToolCalls++;
            }

            if (truncated) {
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        "truncate-" + System.currentTimeMillis(), "__system__",
                        "[系统提示] 你一次性请求了 " + allToolCalls.size() + " 个工具调用，出于上下文安全仅执行了前 "
                                + MAX_TOOLS_PER_ROUND + " 个。请基于已有结果继续，按需分批调用工具。"));
            }

            messages.add(ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .metadata(Map.of())
                    .build());

            // 上下文防护：裁剪过大的历史，防止 LLM 上下文超长（基于真实消息开销）
            ContextGuard.trimMessages(messages, ContextGuard.MAX_HISTORY_CHARS);

            if (totalToolCalls >= MAX_TOOLS_PER_TASK) {
                log.warn("任务 [{}] 达到总工具调用预算 {}，强制结束", task.name(), MAX_TOOLS_PER_TASK);
                finalResult = "(达到最大工具调用预算，任务可能未完成，已执行 " + totalToolCalls + " 个工具调用)";
                break;
            }
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

    /** 文件读取类工具：需要检查路径是否命中敏感/超大文件 */
    private static final Set<String> FILE_READ_TOOLS = Set.of(
            "read_text_file", "read_file", "read_media_file", "read_multiple_files");

    /** 敏感路径模式：命中则拒绝读取（密钥文件、日志、数据库、构建产物、依赖目录、git 内部） */
    private static final java.util.regex.Pattern SENSITIVE_PATH = java.util.regex.Pattern.compile(
            "(?i)(/node_modules/|/target/|/\\.git/|/data/tasks\\.db|\\.env(\\.example)?$|.*\\.(log|db|sqlite|key|pem)$|.*secret.*)");

    private String executeTool(String toolName, String toolInput) {
        // 拦截读取敏感文件的工具调用：避免密钥泄露进 LLM 上下文，以及大日志/数据库撑爆上下文
        if (FILE_READ_TOOLS.contains(toolName) && toolInput != null
                && SENSITIVE_PATH.matcher(toolInput).find()) {
            return "[安全拦截] 拒绝读取敏感/超大文件（.env、日志、数据库、node_modules、target、.git 等）。请基于已允许访问的文件继续。";
        }
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
