package com.example.springharness.util;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 上下文防护工具：防止 Agent 对话历史无限膨胀导致 LLM 上下文超长
 * （如 agnes 免费模型 524288 tokens 限制）。
 *
 * 两层防护：
 * 1. 工具输出截断：单次工具返回结果超过阈值时截断，避免超大输出（如读大文件、脚本大量打印）塞满上下文。
 * 2. 消息历史裁剪：ReAct/Specialist 循环中，消息累积超过阈值时，裁剪最旧的工具往返记录，保留 system + user + 最新上下文。
 */
public final class ContextGuard {

    /** 单次工具输出最大字符数 */
    public static final int MAX_TOOL_OUTPUT_CHARS = 8000;

    /** 消息历史总字符阈值，超过则裁剪 */
    public static final int MAX_HISTORY_CHARS = 40000;

    private ContextGuard() {}

    /**
     * 截断单次工具输出。超过阈值时保留头部并标注截断信息。
     */
    public static String truncateToolOutput(String output) {
        if (output == null) return "";
        if (output.length() <= MAX_TOOL_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_TOOL_OUTPUT_CHARS)
                + "\n...[输出过长，已截断 " + (output.length() - MAX_TOOL_OUTPUT_CHARS)
                + " 字符，共 " + output.length() + " 字符]...";
    }

    /**
     * 裁剪消息历史：当总字符数超过阈值时，从最旧的 assistant/tool 往返开始删除。
     * 始终保留 system(0) 和 user(1)（任务描述），只裁剪中间的工具调用往返。
     */
    public static void trimMessages(List<Message> messages, int maxChars) {
        if (messages.size() <= 2) return;
        int total = 0;
        for (Message m : messages) {
            total += safeLen(m.getText());
        }
        int guard = 0;
        // 保留前 2 条（system + user），从 index 2 开始删除最旧的历史
        while (total > maxChars && messages.size() > 2 && guard++ < 200) {
            Message removed = messages.remove(2);
            total -= safeLen(removed.getText());
        }
    }

    private static int safeLen(String s) {
        return s == null ? 0 : s.length();
    }
}
