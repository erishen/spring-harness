package com.example.springharness.pse;

/**
 * Token 用量记录。
 * 用于统计单次 LLM 调用或整个 PSE/Agent 流程的 token 消耗。
 */
public record TokenUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens
) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    /** 累加两个 TokenUsage */
    public TokenUsage add(TokenUsage other) {
        if (other == null) return this;
        return new TokenUsage(
                this.promptTokens + other.promptTokens,
                this.completionTokens + other.completionTokens,
                this.totalTokens + other.totalTokens
        );
    }
}
