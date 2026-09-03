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
     * 解析失败时自动重试一次，仍失败则用启发式判断，避免误判 FAIL 触发昂贵的 Specialist 重试。
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

                输出要求（严格遵守）：
                - 只输出一个 JSON 对象，不要 markdown 代码块、不要任何解释文字、不要前后缀
                - 格式：
                {
                  "verdict": "pass 或 fail 或 blocked",
                  "acCheck": [{"criterion": "验收标准", "passed": true, "evidence": "证据"}],
                  "feedback": "总体评审意见（fail 说明原因，pass 说明确认通过）",
                  "issues": []
                }
                - verdict 只允许 pass / fail / blocked 三种值
                """;

        String userContent = String.format("""
                子任务名称：%s

                验收标准：
                %s
                Specialist 执行结果：
                %s
                """, task.name(), ac,
                truncateResult(task.executionResult()));

        return evaluateWithRetry(systemPrompt, userContent, model, "Evaluator 评审");
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

                输出要求（严格遵守）：
                - 只输出一个 JSON 对象，不要 markdown 代码块、不要任何解释文字、不要前后缀
                - 格式：
                {
                  "verdict": "pass 或 fail",
                  "feedback": "总体评审意见",
                  "passedTasks": 通过的任务数量,
                  "failedTasks": ["未通过的任务名称"]
                }
                - verdict 只允许 pass / fail 两种值
                """;

        String userContent = String.format("""
                用户原始需求：%s

                各子任务状态：
                %s
                """, userRequest, taskSummary);

        return evaluateWithRetry(systemPrompt, userContent, model, "Evaluator 整体评审");
    }

    /** 调用 LLM 并解析；解析失败先重试一次，仍失败用启发式判断，杜绝解析失败直接判 FAIL */
    private EvaluationResult evaluateWithRetry(String systemPrompt, String userContent, String model, String phase) {
        String text1 = callLlm(systemPrompt, userContent, model);
        EvaluationResult r = parseEvaluation(text1);
        if (r == null) {
            log.warn("{} 输出解析失败（尝试重试一次），原文首行: {}", phase, firstLine(text1));
            String text2 = callLlm(systemPrompt, userContent, model);
            r = parseEvaluation(text2);
            if (r == null) {
                log.warn("{} 重试后仍解析失败，使用启发式判断", phase);
                r = heuristicResult(text2 != null ? text2 : text1);
            }
        }
        return r;
    }

    /** 调用一次 LLM，返回原始文本并记录 token */
    private String callLlm(String systemPrompt, String userContent, String model) {
        Prompt prompt = buildPrompt(systemPrompt, userContent, model);
        var response = multiModelService.getChatModel(model).call(prompt);
        TokenUsageTracker.current(tokenUsageTracker).record(response);
        return response.getResult().getOutput().getText();
    }

    /** 尝试把评审输出解析为 EvaluationResult；无法解析返回 null */
    private EvaluationResult parseEvaluation(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Map<String, Object> result = objectMapper.readValue(extractJson(text),
                    new TypeReference<Map<String, Object>>() {});
            String verdict = (String) result.getOrDefault("verdict", "");
            if (verdict != null && !verdict.isBlank()
                    && List.of("pass", "fail", "blocked").contains(verdict.toLowerCase())) {
                String feedback = (String) result.getOrDefault("feedback", "");
                return new EvaluationResult(verdict, feedback == null ? "" : feedback, result);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 启发式判断：从评审文本中识别通过/失败关键词，避免解析失败误判 */
    private EvaluationResult heuristicResult(String text) {
        if (text == null || text.isBlank()) {
            return new EvaluationResult("blocked", "评审输出为空，无法判断通过与否", Map.of());
        }
        String t = text.toLowerCase();
        boolean pass = t.contains("pass") || t.contains("通过") || t.contains("成功")
                || t.contains("达成") || t.contains("验收标准满足") || t.contains("✅");
        boolean fail = t.contains("fail") || t.contains("失败") || t.contains("未通过")
                || t.contains("未达成") || t.contains("未满足") || t.contains("❌");
        if (pass && !fail) {
            return new EvaluationResult("pass", "（启发式判断）" + truncateResult(text), Map.of());
        }
        if (fail && !pass) {
            return new EvaluationResult("fail", "（启发式判断）" + truncateResult(text), Map.of());
        }
        return new EvaluationResult("blocked", "评审输出格式无法解析，无法判断通过与否", Map.of());
    }

    /** 取原文首行用于日志 */
    private String firstLine(String text) {
        if (text == null || text.isBlank()) return "(空)";
        int nl = text.indexOf('\n');
        String line = nl > 0 ? text.substring(0, nl) : text;
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
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
