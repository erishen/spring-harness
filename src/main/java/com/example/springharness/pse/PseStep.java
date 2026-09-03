package com.example.springharness.pse;

import java.util.List;

/**
 * PSE 执行过程中的一步记录，用于前端展示多 Agent 协作过程。
 */
public record PseStep(
        /** 步骤序号 */
        int sequence,
        /** 角色：planner / specialist / evaluator / system */
        String role,
        /** 动作类型：plan / delegate / execute / evaluate / decide / final */
        String action,
        /** 步骤标题 */
        String title,
        /** 步骤内容（详细描述） */
        String content,
        /** 关联的任务 ID（如有） */
        String taskId,
        /** 工具调用信息（如有，Specialist 执行时） */
        List<ToolCallInfo> toolCalls,
        /** 时间戳 */
        String timestamp
) {
    public static PseStep create(int sequence, String role, String action,
                                   String title, String content, String taskId) {
        return new PseStep(sequence, role, action, title, content, taskId, null,
                java.time.LocalDateTime.now().toString());
    }

    public static PseStep withToolCalls(int sequence, String role, String action,
                                          String title, String content, String taskId,
                                          List<ToolCallInfo> toolCalls) {
        return new PseStep(sequence, role, action, title, content, taskId, toolCalls,
                java.time.LocalDateTime.now().toString());
    }

    /**
     * 工具调用信息（Specialist 执行时调用的工具）。
     */
    public record ToolCallInfo(
            String name,
            String input,
            String output,
            long durationMs
    ) {}
}
