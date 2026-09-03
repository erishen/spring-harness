package com.example.springharness.pse;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 用量追踪器。
 * 在一次 PSE/Agent 流程中累计所有 LLM 调用的 token 消耗。
 *
 * <p>支持「任务作用域」：长时任务并发执行时，每个任务可绑定独立的 tracker 实例
 * （通过 {@link #bind}/{@link #unbind}），避免共享单例在多任务并发时串数据。
 * 未绑定时回退到默认单例。
 */
@Component
public class TokenUsageTracker {

    private static final ThreadLocal<TokenUsageTracker> CURRENT = new ThreadLocal<>();

    /** 任务作用域 tracker 绑定（进入长时任务时调用，finally 中必须 unbind） */
    public static void bind(TokenUsageTracker tracker) {
        CURRENT.set(tracker);
    }

    /** 解除任务作用域 tracker 绑定 */
    public static void unbind() {
        CURRENT.remove();
    }

    /** 获取当前生效的 tracker：优先任务作用域，否则返回传入的默认实例 */
    public static TokenUsageTracker current(TokenUsageTracker fallback) {
        TokenUsageTracker bound = CURRENT.get();
        return bound != null ? bound : fallback;
    }

    private final AtomicLong promptTokens = new AtomicLong(0);
    private final AtomicLong completionTokens = new AtomicLong(0);
    private final AtomicLong totalTokens = new AtomicLong(0);
    private final AtomicLong callCount = new AtomicLong(0);

    /** 记录一次 LLM 调用的 token 用量 */
    public void record(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            callCount.incrementAndGet();
            return;
        }
        var usage = response.getMetadata().getUsage();
        if (usage.getPromptTokens() != null) promptTokens.addAndGet(usage.getPromptTokens());
        if (usage.getCompletionTokens() != null) completionTokens.addAndGet(usage.getCompletionTokens());
        if (usage.getTotalTokens() != null) totalTokens.addAndGet(usage.getTotalTokens());
        callCount.incrementAndGet();
    }

    /** 获取累计的 token 用量 */
    public TokenUsage getTotal() {
        return new TokenUsage(promptTokens.get(), completionTokens.get(), totalTokens.get());
    }

    /** 获取 LLM 调用次数 */
    public long getCallCount() {
        return callCount.get();
    }

    /** 重置追踪器（开始新的流程前调用） */
    public void reset() {
        promptTokens.set(0);
        completionTokens.set(0);
        totalTokens.set(0);
        callCount.set(0);
    }
}
