package com.example.springharness.pse;

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

import java.util.List;
import java.util.Map;

/**
 * Evaluator Agent：独立评审官。
 *
 * 职责：
 * 1. 独立验证子任务的验收标准（AC）是否达成
 * 2. 发现 AC 未覆盖的问题
 * 3. 输出判决结果：pass / fail / blocked
 *
 * 约束：不帮忙、不给建议、只输出判决。不信任 Planner 的陈述，基于执行结果独立判断。
 */
@Service
public class EvaluatorAgent {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorAgent.class);

    private final MultiModelService multiModelService;
    private final TokenUsageTracker tokenUsageTracker;
    private final SoulService soulService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    public EvaluatorAgent(MultiModelService multiModelService, TokenUsageTracker tokenUsageTracker,
                          SoulService soulService) {
        this.multiModelService = multiModelService;
        this.tokenUsageTracker = tokenUsageTracker;
        this.soulService = soulService;
    }

    /**
     * 评审单个子任务。
     * 返回判决结果：pass / fail / blocked，以及评审意见。
     */
    public EvaluationResult evaluateTask(PseTask task, String model) {
        StringBuilder ac = new StringBuilder();
        if (task.acceptanceCriteria() != null) {
            for (String criterion : task.acceptanceCriteria()) {
                ac.append("- ").append(criterion).append("\n");
            }
        }

        String soul = soulService.getSoul("evaluator");
        String systemPrompt = (soul.isBlank() ? "" : soul + "\n\n") + """
                你正在「评审一个子任务」。请验证子任务的验收标准是否达成。

                请以 JSON 格式返回：
                - verdict: 判决结果（pass / fail / blocked）
                - acCheck: 验收标准检查结果数组，每个包含 criterion（标准）、passed（是否通过）、evidence（证据）
                - feedback: 总体评审意见（如果 fail，说明原因；如果 pass，说明确认通过）
                - issues: 发现的问题列表（AC 未覆盖的问题）

                只返回 JSON。
                """;

        String userContent = String.format("""
                子任务名称：%s

                验收标准：
                %s
                Specialist 执行结果：
                %s
                """, task.name(), ac,
                truncateResult(task.executionResult()));

        Prompt prompt = buildPrompt(systemPrompt, userContent, model);
        var response = multiModelService.getChatModel(model).call(prompt);
        TokenUsageTracker.current(tokenUsageTracker).record(response);
        String responseText = response.getResult().getOutput().getText();

        try {
            Map<String, Object> result = objectMapper.readValue(extractJson(responseText),
                    new TypeReference<Map<String, Object>>() {});
            String verdict = (String) result.getOrDefault("verdict", "fail");
            String feedback = (String) result.getOrDefault("feedback", "");
            return new EvaluationResult(verdict, feedback, result);
        } catch (Exception e) {
            log.warn("Evaluator 评审解析失败，默认判 fail: {}", e.getMessage());
            return new EvaluationResult("fail", "评审解析失败: " + e.getMessage(), Map.of());
        }
    }

    /**
     * 整体评审：所有子任务完成后，对整体交付进行评审。
     */
    public EvaluationResult evaluateOverall(String userRequest, List<PseTask> tasks, String model) {
        StringBuilder taskSummary = new StringBuilder();
        for (PseTask task : tasks) {
            taskSummary.append(String.format("### %s\n- 状态: %s\n- 评审: %s\n- 结果: %s\n\n",
                    task.name(),
                    task.status(),
                    task.evaluationResult() != null ? task.evaluationResult() : "未评审",
                    task.executionResult() != null ? task.executionResult().substring(0, Math.min(200, task.executionResult().length())) : "无"));
        }

        String soul = soulService.getSoul("evaluator");
        String systemPrompt = (soul.isBlank() ? "" : soul + "\n\n") + """
                你正在「整体评审」。所有子任务已完成，请对整体交付进行最终评审。

                请以 JSON 格式返回：
                - verdict: 判决结果（pass / fail）
                - feedback: 总体评审意见
                - passedTasks: 通过的任务数量
                - failedTasks: 未通过的任务名称列表

                只返回 JSON。
                """;

        String userContent = String.format("""
                用户原始需求：%s

                各子任务状态：
                %s
                """, userRequest, taskSummary);

        Prompt prompt = buildPrompt(systemPrompt, userContent, model);
        var response = multiModelService.getChatModel(model).call(prompt);
        TokenUsageTracker.current(tokenUsageTracker).record(response);
        String responseText = response.getResult().getOutput().getText();

        try {
            Map<String, Object> result = objectMapper.readValue(extractJson(responseText),
                    new TypeReference<Map<String, Object>>() {});
            String verdict = (String) result.getOrDefault("verdict", "fail");
            String feedback = (String) result.getOrDefault("feedback", "");
            return new EvaluationResult(verdict, feedback, result);
        } catch (Exception e) {
            log.warn("Evaluator 整体评审解析失败，默认判 fail: {}", e.getMessage());
            return new EvaluationResult("fail", "整体评审解析失败", Map.of());
        }
    }

    // ==================== 私有方法 ====================

    /** 截断过长的 Specialist 执行结果，避免评审输入过大 */
    private String truncateResult(String result) {
        if (result == null) return "(无执行结果)";
        if (result.length() <= MAX_RESULT_CHARS) return result;
        return result.substring(0, MAX_RESULT_CHARS)
                + "\n...[执行结果过长，已截断 " + (result.length() - MAX_RESULT_CHARS) + " 字符]...";
    }

    /** 评审时单任务执行结果最大字符数 */
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

    private String extractJson(String response) {
        if (response == null) return "{}";
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-zA-Z]*\\n", "").replaceAll("\\n```$", "");
        }
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

    /**
     * 评审结果。
     */
    public record EvaluationResult(
            String verdict,
            String feedback,
            Map<String, Object> rawResult
    ) {
        public boolean isPass() {
            return "pass".equalsIgnoreCase(verdict);
        }
    }
}
