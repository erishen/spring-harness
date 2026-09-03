package com.example.springharness.pse;

import java.util.List;

/**
 * 技能执行结果。
 */
public record SkillResult(
        /** 是否成功 */
        boolean success,
        /** 结果内容 */
        String output,
        /** 执行过程中调用的工具（用于展示） */
        List<PseStep.ToolCallInfo> toolCalls,
        /** 错误信息（失败时） */
        String error
) {
    public static SkillResult success(String output) {
        return new SkillResult(true, output, List.of(), null);
    }

    public static SkillResult success(String output, List<PseStep.ToolCallInfo> toolCalls) {
        return new SkillResult(true, output, toolCalls, null);
    }

    public static SkillResult failure(String error) {
        return new SkillResult(false, null, List.of(), error);
    }
}
