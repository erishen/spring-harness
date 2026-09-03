package com.example.springharness.dto;

import java.util.List;

/**
 * Agent 对话响应，包含最终回答和工具调用过程。
 */
public record AgentResponse(
        String answer,
        List<ToolCall> toolCalls
) {
    public record ToolCall(
            String name,
            Object input,
            String output,
            long durationMs
    ) {}
}
