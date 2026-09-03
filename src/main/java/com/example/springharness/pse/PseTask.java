package com.example.springharness.pse;

import java.util.List;

/**
 * PSE（Planner-Specialist-Evaluator）子任务定义。
 *
 * 由 Planner 分解生成，委托给 Specialist 执行，最后由 Evaluator 验证。
 */
public record PseTask(
        /** 任务 ID */
        String id,
        /** 任务名称 */
        String name,
        /** 任务描述 */
        String description,
        /** 验收标准（Acceptance Criteria） */
        List<String> acceptanceCriteria,
        /** 依赖的任务 ID 列表 */
        List<String> dependencies,
        /** 任务状态：pending / in_progress / completed / failed */
        String status,
        /** Specialist 执行结果 */
        String executionResult,
        /** Evaluator 评审结果：pass / fail / blocked */
        String evaluationResult,
        /** Evaluator 评审意见（失败原因、证据等） */
        String evaluationFeedback
) {
    public static PseTask create(String id, String name, String description,
                                   List<String> acceptanceCriteria, List<String> dependencies) {
        return new PseTask(id, name, description, acceptanceCriteria, dependencies,
                "pending", null, null, null);
    }

    public PseTask withStatus(String newStatus) {
        return new PseTask(id, name, description, acceptanceCriteria, dependencies,
                newStatus, executionResult, evaluationResult, evaluationFeedback);
    }

    public PseTask withExecutionResult(String result) {
        return new PseTask(id, name, description, acceptanceCriteria, dependencies,
                "completed", result, evaluationResult, evaluationFeedback);
    }

    public PseTask withEvaluation(String result, String feedback) {
        return new PseTask(id, name, description, acceptanceCriteria, dependencies,
                status, executionResult, result, feedback);
    }
}
