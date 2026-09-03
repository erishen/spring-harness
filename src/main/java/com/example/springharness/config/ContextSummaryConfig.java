package com.example.springharness.config;

import com.example.springharness.pse.TokenUsageTracker;
import com.example.springharness.service.MultiModelService;
import com.example.springharness.util.ContextGuard;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文压缩「LLM 语义摘要」开关配置。
 *
 * <p>默认关闭（零成本的轻量折叠）；开启 {@code CONTEXT_SUMMARY_ENABLED=true} 后，
 * 上下文超限时会用 LLM 把最旧的工具往返压缩成语义摘要（更智能，但每次裁剪
 * 多消耗一次 LLM 调用，并在 agnes 限流下更慢）。
 */
@Configuration
public class ContextSummaryConfig {

    private final MultiModelService multiModelService;
    private final TokenUsageTracker tokenUsageTracker;

    /** LLM 语义摘要开关（默认关闭） */
    @Value("${CONTEXT_SUMMARY_ENABLED:false}")
    private boolean summaryEnabled;

    /** 摘要使用的模型（默认跟随主模型） */
    @Value("${DASHSCOPE_MODEL:qwen-plus}")
    private String summaryModel;

    /** LLM 摘要的最大字符数 */
    @Value("${CONTEXT_SUMMARY_MAX_CHARS:4000}")
    private int summaryMaxChars;

    public ContextSummaryConfig(MultiModelService multiModelService,
                                TokenUsageTracker tokenUsageTracker) {
        this.multiModelService = multiModelService;
        this.tokenUsageTracker = tokenUsageTracker;
    }

    @PostConstruct
    public void registerSummarizer() {
        if (!summaryEnabled) {
            return;
        }
        ContextGuard.setLlmSummarizer(roundTrip -> {
            ChatModel model = multiModelService.getChatModel(summaryModel);
            String content = formatRoundTrip(roundTrip);
            if (content.length() > ContextGuard.MAX_TOOL_OUTPUT_CHARS * 2) {
                content = content.substring(0, ContextGuard.MAX_TOOL_OUTPUT_CHARS * 2) + "\n…(过长已截断)";
            }
            String system = "你是上下文压缩器。把一段 Agent 的工具执行记录压缩成简洁的中文摘要。"
                    + "必须保留：关键数字、文件路径、工具结论、已完成的关键动作。"
                    + "忽略调试性噪音。只输出摘要正文，不要解释、不要加前缀。";
            String user = "以下是 Agent 早期的工具执行记录，请压缩成一段简洁摘要：\n\n" + content;
            ChatResponse response = model.call(new Prompt(List.of(
                    new SystemMessage(system), new UserMessage(user))));
            TokenUsageTracker.current(tokenUsageTracker).record(response);
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            return text.length() > summaryMaxChars
                    ? text.substring(0, summaryMaxChars)
                    : text;
        });
    }

    /** 把一组消息（assistant + tool responses）格式化为可读文本，供 LLM 压缩 */
    private static String formatRoundTrip(List<Message> messages) {
        return messages.stream().map(m -> {
            if (m instanceof AssistantMessage am) {
                StringBuilder sb = new StringBuilder("[assistant]");
                if (am.getText() != null && !am.getText().isBlank()) {
                    sb.append(" ").append(am.getText());
                }
                if (am.getToolCalls() != null) {
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        sb.append("\n  调用工具 ").append(tc.name())
                                .append(" 参数: ").append(tc.arguments());
                    }
                }
                return sb.toString();
            }
            if (m instanceof ToolResponseMessage trm) {
                StringBuilder sb = new StringBuilder("[tool_result]");
                if (trm.getResponses() != null) {
                    for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                        String name = tr.name() != null ? tr.name() : "tool";
                        sb.append("\n  ").append(name).append(": ")
                                .append(tr.responseData());
                    }
                }
                return sb.toString();
            }
            return "[" + (m.getMessageType() != null ? m.getMessageType() : "msg") + "] "
                    + (m.getText() == null ? "" : m.getText());
        }).collect(Collectors.joining("\n"));
    }
}
