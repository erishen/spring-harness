package com.example.springharness.util;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextGuard 单元测试：工具输出截断、消息开销估算、历史压缩、标记清理。
 */
class ContextGuardTest {

    // ==================== truncateToolOutput ====================

    @Test
    void truncateKeepsShortOutput() {
        String s = "short";
        assertThat(ContextGuard.truncateToolOutput(s)).isEqualTo(s);
    }

    @Test
    void truncateCutsLongOutput() {
        String longOut = "x".repeat(ContextGuard.MAX_TOOL_OUTPUT_CHARS + 100);
        String cut = ContextGuard.truncateToolOutput(longOut);
        assertThat(cut.length()).isLessThan(longOut.length());
        assertThat(cut).contains("已截断");
        assertThat(cut).contains("100");
    }

    @Test
    void truncateHandlesNull() {
        assertThat(ContextGuard.truncateToolOutput(null)).isEmpty();
    }

    // ==================== estimateMessageChars ====================

    @Test
    void estimateCountsToolCallArguments() {
        // Assistant 消息的 toolCalls arguments 也应计入开销
        var toolCall = new AssistantMessage.ToolCall("call-1", "function", "calculator",
                "{\"a\":123,\"b\":456,\"operation\":\"add\"}");
        AssistantMessage am = AssistantMessage.builder().content("计算结果").toolCalls(List.of(toolCall)).build();
        int len = ContextGuard.estimateMessageChars(am);
        assertThat(len).isGreaterThan("计算结果".length());
        assertThat(len).isEqualTo("计算结果".length()
                + "calculator".length() + "{\"a\":123,\"b\":456,\"operation\":\"add\"}".length());
    }

    @Test
    void estimateCountsToolResponses() {
        var tr = new ToolResponseMessage.ToolResponse("call-1", "calculator", "{\"result\":42}");
        ToolResponseMessage msg = ToolResponseMessage.builder().responses(List.of(tr)).build();
        int len = ContextGuard.estimateMessageChars(msg);
        assertThat(len).isEqualTo("{\"result\":42}".length());
    }

    // ==================== trimMessages ====================

    @Test
    void trimKeepsSmallHistory() {
        List<Message> msgs = new ArrayList<>(List.of(
                new UserMessage("任务"),
                new AssistantMessage("回答"),
                new UserMessage("追问")
        ));
        ContextGuard.trimMessages(msgs, 10000);
        assertThat(msgs).hasSize(3);
    }

    @Test
    void trimRemovesOldestWhenOverLimit() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("系统任务描述"));
        msgs.add(new UserMessage("用户提问"));
        // 超长历史，肯定超过 maxChars
        msgs.add(new AssistantMessage("x".repeat(2000)));
        msgs.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("c1", "calculator", "y".repeat(2000)))).build());
        msgs.add(new AssistantMessage("z".repeat(2000)));

        ContextGuard.trimMessages(msgs, 2000);
        // 至少保留了 system(0) + user(1)
        assertThat(msgs.size()).isGreaterThanOrEqualTo(2);
        assertThat(msgs.get(0).getText()).isEqualTo("系统任务描述");
        assertThat(msgs.get(1).getText()).isEqualTo("用户提问");
        // 总量应被压到阈值附近
        int total = msgs.stream().mapToInt(ContextGuard::estimateMessageChars).sum();
        assertThat(total).isLessThanOrEqualTo(2000);
    }

    // ==================== stripToolCallMarkers ====================

    @Test
    void stripToolCallMarkersRemovesMarkers() {
        String text = "我调用 <tool_call>name=\"calculator\"</tool_call> 完成计算";
        assertThat(ContextGuard.stripToolCallMarkers(text)).doesNotContain("tool_call");
    }
}
