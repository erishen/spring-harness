package com.example.springharness.util;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
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

    /** 折叠摘要前缀标记，用于识别「已压缩的早期上下文」，避免重复套娃折叠 */
    private static final String SUMMARY_PREFIX = "【早期工具执行记录·已压缩】";

    /** LLM 语义摘要的最大字符数（超出截断） */
    public static final int MAX_SUMMARY_CHARS = 4000;

    /** 可选：LLM 语义摘要器（由配置类在开关开启时注入；为空时使用零成本的轻量折叠） */
    private static volatile java.util.function.Function<List<Message>, String> llmSummarizer;

    /**
     * 注入 / 移除 LLM 语义摘要器。
     * @param summarizer 将一组最旧往返压缩为一段语义摘要文本；传 null 恢复轻量折叠
     */
    public static void setLlmSummarizer(java.util.function.Function<List<Message>, String> summarizer) {
        llmSummarizer = summarizer;
    }

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
     * 压缩消息历史：当真实消息开销超过阈值时，优先把最旧的 assistant/tool 往返
     * 「折叠」成一条轻量摘要（保留工具名 + 截断参数/结论 + 思考，零 LLM 调用），
     * 仅当无法折叠（如孤立 tool response）才直接丢弃。
     * 始终保留 system(0) 和 user(1)（任务描述）。
     */
    public static void trimMessages(List<Message> messages, int maxChars) {
        if (messages.size() <= 2) return;
        int total = 0;
        for (Message m : messages) {
            total += estimateMessageChars(m);
        }
        int guard = 0;
        // 保留前 2 条（system + user），从 index 2 开始压缩最旧的历史
        while (total > maxChars && messages.size() > 2 && guard++ < 500) {
            if (compactOldestRoundTrip(messages)) {
                // 折叠成功：重新累计真实开销
                total = 0;
                for (Message m : messages) {
                    total += estimateMessageChars(m);
                }
            } else {
                Message removed = messages.remove(2);
                total -= estimateMessageChars(removed);
            }
        }
    }

    /**
     * 将消息列表中最旧的一组完整往返（一条 assistant 及随后的 tool response）折叠成一条摘要。
     * 摘要优先使用 LLM 语义压缩（开关开启时），失败或未开启时回退到零成本的轻量折叠
     * （保留工具名 + 截断参数/结论 + 思考）。
     *
     * @return true 表示折叠成功并已替换进列表；false 表示无法折叠（调用方将直接丢弃）
     */
    private static boolean compactOldestRoundTrip(List<Message> messages) {
        if (messages.size() < 3) return false;
        int i = 2;
        Message first = messages.get(i);
        // 孤立的 tool response：无关联的 assistant 思考，直接丢弃更干净
        if (first instanceof ToolResponseMessage) {
            return false;
        }
        // 最旧已是压缩摘要：不再次套娃折叠，返回 false 由调用方直接丢弃（价值最低）
        if (first.getText() != null && first.getText().startsWith(SUMMARY_PREFIX)) {
            return false;
        }
        // 收集一组完整往返：一条 assistant（可能带 toolCalls）+ 随后的若干 tool responses
        List<Message> roundTrip = new ArrayList<>();
        roundTrip.add(first);
        int j = i + 1;
        while (j < messages.size() && messages.get(j) instanceof ToolResponseMessage) {
            roundTrip.add(messages.get(j));
            j++;
        }
        // 生成摘要：优先 LLM 语义摘要（开关开启时），失败则回退轻量折叠
        String summaryText = null;
        var summarizer = llmSummarizer;
        if (summarizer != null) {
            try {
                String s = summarizer.apply(roundTrip);
                if (s != null && !s.isBlank()) {
                    summaryText = SUMMARY_PREFIX + "\n" + truncate(s, MAX_SUMMARY_CHARS);
                }
            } catch (Exception ex) {
                // 摘要调用失败（限流/超时等）：静默回退到轻量折叠
                summaryText = null;
            }
        }
        if (summaryText == null) {
            summaryText = buildLightSummary(roundTrip);
        }
        // 用折叠摘要替换原区间 [i, j)
        List<Message> sub = messages.subList(i, j);
        sub.clear();
        messages.add(i, new SystemMessage(summaryText));
        return true;
    }

    /** 轻量折叠：保留工具名 + 截断的参数/结论 + 思考，零 LLM 调用 */
    private static String buildLightSummary(List<Message> roundTrip) {
        StringBuilder sb = new StringBuilder(SUMMARY_PREFIX);
        for (Message m : roundTrip) {
            appendMessageSummary(sb, m);
        }
        return sb.toString();
    }

    /** 把单条消息折叠成一行摘要 */
    private static void appendMessageSummary(StringBuilder sb, Message msg) {
        if (msg instanceof AssistantMessage am) {
            String text = am.getText();
            if (text != null && !text.isBlank()) {
                sb.append("\n- 思考: ").append(truncate(text, 240));
            }
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    sb.append("\n- 调用 ").append(tc.name())
                            .append("(").append(truncate(tc.arguments(), 80)).append(")");
                }
            }
        } else if (msg instanceof ToolResponseMessage trm) {
            if (trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    String name = tr.name() != null ? tr.name() : "tool";
                    sb.append("\n  → ").append(name).append(": ")
                            .append(truncate(tr.responseData(), 240));
                }
            }
        } else {
            sb.append("\n- ").append(truncate(msg.getText(), 240));
        }
    }

    /** 截断字符串，超过 max 保留头部并加省略号 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    /**
     * 发送给 LLM 前的兜底护栏：确保消息列表真实开销在安全阈值内。
     * 基于真实消息开销（含 toolCalls arguments / tool responses）估算并裁剪，
     * 防止循环累积导致上下文超长。
     */
    public static void ensureSafeMessages(List<Message> messages) {
        trimMessages(messages, MAX_HISTORY_CHARS);
    }

    // ==================== 最终输出清理 ====================

    /** 完整的 tool_call / tool_calls 块（跨行，含单复数） */
    private static final java.util.regex.Pattern TOOL_CALL_BLOCK = java.util.regex.Pattern.compile(
            "(?is)<\\s*tool_calls?\\b.*?<\\s*/?\\s*tool_calls?\\s*>");

    /** invoke 块（Anthropic/OpenAI 风格文本模拟，tool 或 name 参数，含或省略尖括号） */
    private static final java.util.regex.Pattern INVOKE_BLOCK = java.util.regex.Pattern.compile(
            "(?is)<?\\s*invoke\\s+(?:tool|name)\\s*=\\s*\\\"[^\\\"]*\\\"[^>]*>?.*?<\\s*/?\\s*invoke\\s*>");

    /** function_call 块 */
    private static final java.util.regex.Pattern FUNCTION_CALL_BLOCK = java.util.regex.Pattern.compile(
            "(?is)<\\s*function_call\\b.*?<\\s*/?\\s*function_call\\s*>");

    /** tool_use 块 */
    private static final java.util.regex.Pattern TOOL_USE_BLOCK = java.util.regex.Pattern.compile(
            "(?is)<\\s*tool_use\\b.*?<\\s*/?\\s*tool_use\\s*>");

    /** Qwen 风格工具调用段 begin/end 之间的完整块 */
    private static final java.util.regex.Pattern QWEN_CALLS_SECTION = java.util.regex.Pattern.compile(
            "(?is)<\\|tool_calls_section_begin\\|>.*?<\\|tool_calls_section_end\\|>");

    /** Qwen 风格孤立标记：tool_call_begin / tool_call_argument_begin / tool_end 等 */
    private static final java.util.regex.Pattern QWEN_TOOL_TAG = java.util.regex.Pattern.compile(
            "(?i)<\\|tool_(?:call_begin|call_argument_begin|end|calls_section_begin|calls_section_end)\\|>");

    /** 独立参数行：param name="..." 或 parameter name="..." */
    private static final java.util.regex.Pattern PARAM_LINE = java.util.regex.Pattern.compile(
            "(?im)^\\s*(?:<)?\\s*param(?:eter)?\\s+name\\s*=\\s*\\\"[^\\\"]*\\\"[^>]*>?.*$");

    /** 孤立标签：tool_call/tool_calls / function / parameter / param / invoke */
    private static final java.util.regex.Pattern LONE_TAG = java.util.regex.Pattern.compile(
            "(?i)<\\s*/?\\s*(?:tool_calls?|function|parameter|param|invoke)\\s*>");

    /**
     * 清理 LLM 最终输出中混入的文本形式工具调用标记。
     * 部分模型在 tool calling 模式下会用文本模拟工具调用（tool_call(s) / invoke / function_call / tool_use / Qwen 等风格），
     * 这些是内部指令，不应展示给用户。
     */
    public static String stripToolCallMarkers(String text) {
        if (text == null) return "";
        String out = text;
        out = TOOL_CALL_BLOCK.matcher(out).replaceAll("");
        out = INVOKE_BLOCK.matcher(out).replaceAll("");
        out = FUNCTION_CALL_BLOCK.matcher(out).replaceAll("");
        out = TOOL_USE_BLOCK.matcher(out).replaceAll("");
        out = QWEN_CALLS_SECTION.matcher(out).replaceAll("");
        out = QWEN_TOOL_TAG.matcher(out).replaceAll("");
        out = PARAM_LINE.matcher(out).replaceAll("");
        out = LONE_TAG.matcher(out).replaceAll("");
        // 清理因移除标记产生的多余空行
        out = out.replaceAll("(?m)^\\s*\\n", "");
        return out.trim();
    }

    private static int safeLen(String s) {
        return s == null ? 0 : s.length();
    }
}
