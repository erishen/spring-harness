package com.example.springharness.util;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * 上下文防护工具：防止 Agent 对话历史无限膨胀导致 LLM 上下文超长
 * （如 agnes 免费模型 524288 tokens 限制）。
 *
 * 两层防护：
 * 1. 工具输出截断：单次工具返回结果超过阈值时截断，避免超大输出（如读大文件、脚本大量打印）塞满上下文。
 * 2. 消息历史裁剪：ReAct/Specialist 循环中，消息累积超过阈值时，裁剪最旧的工具往返记录，保留 system + user + 最新上下文。
 *
 * 注意：消息开销估算必须包含 toolCalls 的 arguments 和 ToolResponse 的 responseData，
 * 仅用 getText() 会严重低估实际发送给模型的 token 量（曾导致 63 万 token 超限）。
 */
public final class ContextGuard {

    /** 单次工具输出最大字符数 */
    public static final int MAX_TOOL_OUTPUT_CHARS = 8000;

    /** 消息历史总字符阈值（按真实消息开销估算），超过则裁剪 */
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
     * 估算单条消息发送给 LLM 时的真实字符开销。
     * 覆盖容易被遗漏的部分：
     * - Assistant 消息：getText() 不含 toolCalls 的 arguments，需额外计入
     * - ToolResponse 消息：计入所有 responseData
     */
    public static int estimateMessageChars(Message m) {
        int len = safeLen(m.getText());
        if (m instanceof AssistantMessage am) {
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    len += safeLen(tc.name()) + safeLen(tc.arguments());
                }
            }
        }
        if (m instanceof ToolResponseMessage trm) {
            if (trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    len += safeLen(tr.responseData());
                }
            }
        }
        return len;
    }

    /**
     * 裁剪消息历史：当真实消息开销超过阈值时，从最旧的 assistant/tool 往返开始删除。
     * 始终保留 system(0) 和 user(1)（任务描述），只裁剪中间的工具调用往返。
     */
    public static void trimMessages(List<Message> messages, int maxChars) {
        if (messages.size() <= 2) return;
        int total = 0;
        for (Message m : messages) {
            total += estimateMessageChars(m);
        }
        int guard = 0;
        // 保留前 2 条（system + user），从 index 2 开始删除最旧的历史
        while (total > maxChars && messages.size() > 2 && guard++ < 500) {
            Message removed = messages.remove(2);
            total -= estimateMessageChars(removed);
        }
    }

    /**
     * 发送给 LLM 前的兜底护栏：确保消息列表真实开销在安全阈值内。
     * 基于真实消息开销（含 toolCalls arguments / tool responses）估算并裁剪，
     * 防止循环累积导致上下文超长。
     */
    public static void ensureSafeMessages(List<Message> messages) {
        trimMessages(messages, MAX_HISTORY_CHARS);
    }

    private static int safeLen(String s) {
        return s == null ? 0 : s.length();
    }
}
