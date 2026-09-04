package com.example.springharness.pse;

import com.example.springharness.memory.MemoryService;
import com.example.springharness.service.MultiModelService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Planner Agent：规划者/交付负责人。
 *
 * 职责：
 * 1. 接收用户需求，分析并分解为可执行的子任务
 * 2. 为每个子任务定义验收标准（AC）
 * 3. 根据 Specialist 执行结果和 Evaluator 评审结果决定下一步
 *
 * 约束：不写实现代码，只规划、分解、委托、验证。
 */
@Service
public class PlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    private final MultiModelService multiModelService;
    private final ToolDescriptionService toolDescriptionService;
    private final TokenUsageTracker tokenUsageTracker;
    private final SoulService soulService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    public PlannerAgent(MultiModelService multiModelService, ToolDescriptionService toolDescriptionService,
                        TokenUsageTracker tokenUsageTracker, SoulService soulService,
                        MemoryService memoryService) {
        this.multiModelService = multiModelService;
        this.toolDescriptionService = toolDescriptionService;
        this.tokenUsageTracker = tokenUsageTracker;
        this.soulService = soulService;
        this.memoryService = memoryService;
    }

    /**
     * 任务分解：把用户需求分解为子任务列表。
     * 返回 JSON 格式的子任务列表，由调用方解析。
     */
    public String decomposeTask(String userRequest, String model) {
        String toolList = toolDescriptionService.generateToolList();
        // 采用 souls/planner/SOUL.md 作为角色定义，追加任务特定的分解指令
        String soul = soulService.getSoul("planner");
        String systemPrompt = (soul.isBlank() ? "" : soul + "\n\n") + """
                你正在执行「任务分解」。请把用户需求分解为可执行的子任务。

                任务约束：
                - 每个子任务必须有明确的验收标准（AC）
                - 子任务之间可以有依赖关系
                - 子任务数量控制在 1-5 个，避免过度分解
                - 简单任务（如查询、计算）只需要 1 个子任务
                - 子任务必须覆盖到「产出用户最终想要的结果」，不要止步于准备工作
                - 若用户需求是「按 XX 技能/流程执行」（如生成本周投资周报），请把「加载技能/准备工作」
                  与「按技能实际执行并产出最终结果」拆分为不同子任务（用 dependencies 表达先后依赖），
                  确保最终有子任务真正产出用户要的周报/报告，而不是只加载技能就结束

                可用工具（Specialist 可以调用）：
                %s

                请以 JSON 数组格式返回子任务列表，格式如下：
                [
                  {
                    "name": "任务名称",
                    "description": "详细描述",
                    "acceptanceCriteria": ["验收标准1", "验收标准2"],
                    "dependencies": []
                  }
                ]

                只返回 JSON 数组，不要返回 markdown 代码块，不要返回任何解释文字。
                """.formatted(toolList) + memoryService.buildMemoryContext(userRequest);

        Prompt prompt = buildPrompt(systemPrompt, "用户需求：" + userRequest, model);
        var response = multiModelService.getChatModel(model).call(prompt);
        TokenUsageTracker.current(tokenUsageTracker).record(response);
        String responseText = response.getResult().getOutput().getText();

        // 提取 JSON（LLM 可能返回 markdown 代码块）
        memoryService.extractAndStore(userRequest, "pse");
        return extractJson(responseText);
    }

    /**
     * 决策：根据当前任务状态决定下一步行动。
     * 返回决策结果：continue（继续下一个任务）/ evaluate（调用评审）/ deliver（交付）/ fix（修复）
     */
    public Map<String, Object> decideNextAction(String userRequest,
                                                   List<PseTask> tasks,
                                                   String model) {
        StringBuilder taskStatus = new StringBuilder();
        for (PseTask task : tasks) {
            taskStatus.append(String.format("- [%s] %s: %s%n",
                    task.status(), task.name(),
                    task.executionResult() != null ? task.executionResult().substring(0, Math.min(100, task.executionResult().length())) : "未执行"));
            if (task.evaluationResult() != null) {
                taskStatus.append(String.format("  评审: %s - %s%n",
                        task.evaluationResult(),
                        task.evaluationFeedback() != null ? task.evaluationFeedback().substring(0, Math.min(100, task.evaluationFeedback().length())) : ""));
            }
        }

        String soul = soulService.getSoul("planner");
        String systemPrompt = (soul.isBlank() ? "" : soul + "\n\n") + """
                你正在「决定下一步行动」。根据当前任务状态决定下一步。

                可选行动：
                - continue: 还有未完成的子任务，继续执行下一个
                - evaluate: 所有子任务已完成，调用 Evaluator 进行整体评审
                - deliver: 评审通过，可以交付最终结果
                - fix: 评审不通过，需要修复问题

                请以 JSON 格式返回：
                - action: 行动类型（continue/evaluate/deliver/fix）
                - reason: 决策理由
                - nextTaskName: 如果 action=continue 或 fix，指定下一个要执行的任务名称
                - summary: 当前进度总结

                只返回 JSON。
                """;

        String userContent = String.format("""
                用户原始需求：%s

                当前任务状态：
                %s
                """, userRequest, taskStatus);

        Prompt prompt = buildPrompt(systemPrompt, userContent, model);
        var response = multiModelService.getChatModel(model).call(prompt);
        TokenUsageTracker.current(tokenUsageTracker).record(response);
        String responseText = response.getResult().getOutput().getText();

        try {
            return objectMapper.readValue(extractJson(responseText), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Planner 决策解析失败，使用默认策略: {}", e.getMessage());
            return Map.of("action", "deliver", "reason", "解析失败，默认交付", "summary", "任务完成");
        }
    }

    /**
     * 生成最终交付总结。
     */
    public String generateFinalDelivery(String userRequest, List<PseTask> tasks, String model) {
        StringBuilder taskResults = new StringBuilder();
        for (PseTask task : tasks) {
            taskResults.append(String.format("### %s%n%s%n%n",
                    task.name(),
                    truncateResult(task.executionResult())));
        }

        String soul = soulService.getSoul("planner");
        String systemPrompt = (soul.isBlank() ? "" : soul + "\n\n") + """
                你正在「生成最终交付」。所有子任务已执行完毕并通过评审，现在是最终交付阶段。

                严格约束（必须遵守）：
                - 唯一任务：把下方「各子任务执行结果」整合成一段面向用户的最终答案
                - 这是最终交付，不是规划：禁止输出任何新计划、下一步、待办、子任务、工具调用或 JSON
                - 禁止出现 "tool":、"delegate"、"下一步"、"等待返回"、"继续执行" 等规划性措辞
                - 若执行结果足以回答用户需求：直接给出完整、清晰、可直接使用的最终答案
                - 若执行结果只是中间步骤或不足以回答需求：诚实说明「已完成 X、未完成 Y、需要 Z」，
                  不要编造未执行的内容，不要假装报告已生成
                - 不要提及内部的 Planner/Specialist/Evaluator 角色

                用中文回答，直接给最终答案。
                """ + memoryService.buildMemoryContext(userRequest);

        String userContent = String.format("""
                用户原始需求：%s

                各子任务执行结果：
                %s
                """, userRequest, taskResults);

        Prompt prompt = buildPrompt(systemPrompt, userContent, model);
        var response = multiModelService.getChatModel(model).call(prompt);
        TokenUsageTracker.current(tokenUsageTracker).record(response);
        // 清理最终交付中混入的文本形式工具调用标记
        return com.example.springharness.util.ContextGuard.stripToolCallMarkers(
                response.getResult().getOutput().getText());
    }

    // ==================== 私有方法 ====================

    /** 截断过长的执行结果，避免最终交付输入过大 */
    private String truncateResult(String result) {
        if (result == null) return "未执行";
        if (result.length() <= MAX_RESULT_CHARS) return result;
        return result.substring(0, MAX_RESULT_CHARS)
                + "\n...[执行结果过长，已截断 " + (result.length() - MAX_RESULT_CHARS) + " 字符]...";
    }

    /** 最终交付时单任务执行结果最大字符数 */
    private static final int MAX_RESULT_CHARS = 6000;

    private Prompt buildPrompt(String systemPrompt, String userContent, String model) {
        List<org.springframework.ai.chat.messages.Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userContent)
        );
        if (model != null && !model.isBlank()) {
            return new Prompt(messages, org.springframework.ai.chat.prompt.ChatOptions.builder()
                    .model(model).maxTokens(maxTokens).build());
        }
        return new Prompt(messages);
    }

    /**
     * 从 LLM 响应中提取 JSON（处理 markdown 代码块包裹的情况）。
     */
    private String extractJson(String response) {
        if (response == null) return "[]";
        String trimmed = response.trim();
        // 去除 markdown 代码块
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-zA-Z]*\\n", "").replaceAll("\\n```$", "");
        }
        // 找到第一个 [ 或 { 和最后一个 ] 或 }
        int start = Math.min(
                trimmed.indexOf('[') >= 0 ? trimmed.indexOf('[') : Integer.MAX_VALUE,
                trimmed.indexOf('{') >= 0 ? trimmed.indexOf('{') : Integer.MAX_VALUE
        );
        int end = Math.max(trimmed.lastIndexOf(']'), trimmed.lastIndexOf('}'));
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
