package com.example.springharness.pse;

import java.util.List;

/**
 * PSE 执行最终结果。
 */
public record PseResult(
        /** 最终回答/交付内容 */
        String answer,
        /** 完整的执行步骤（用于前端展示） */
        List<PseStep> steps,
        /** 所有子任务 */
        List<PseTask> tasks,
        /** 最终状态：completed / failed / blocked */
        String status,
        /** 总耗时（毫秒） */
        long totalDurationMs,
        /** 模式标识 */
        String mode,
        /** Token 用量统计 */
        TokenUsage tokenUsage,
        /** LLM 调用次数 */
        long llmCallCount
) {
    public static PseResult success(String answer, List<PseStep> steps,
                                      List<PseTask> tasks, long durationMs,
                                      TokenUsage tokenUsage, long llmCallCount) {
        return new PseResult(answer, steps, tasks, "completed", durationMs, "pse-agent", tokenUsage, llmCallCount);
    }

    public static PseResult failed(String answer, List<PseStep> steps,
                                     List<PseTask> tasks, long durationMs,
                                     TokenUsage tokenUsage, long llmCallCount) {
        return new PseResult(answer, steps, tasks, "failed", durationMs, "pse-agent", tokenUsage, llmCallCount);
    }
}
