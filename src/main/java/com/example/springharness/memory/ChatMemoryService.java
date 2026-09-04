package com.example.springharness.memory;

import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话窗口记忆（方案 B，可选）。
 *
 * <p>基于 Spring AI {@link MessageWindowChatMemory} 按 conversationId 维护多轮上下文窗口，
 * 由后端统一管理最近 N 条消息，替代前端回传全部历史（减小 payload、天然限窗）。
 *
 * <p>默认关闭（MEMORY_CHAT_ENABLED=false），不影响现有前端「前端传 messages」的交互。
 * 开启后，普通对话接口传入 conversationId 即走服务端窗口。
 *
 * <p>注意：窗口存于内存，重启即清；如需跨重启持久化，可替换 {@code ChatMemoryRepository}。
 */
@Service
public class ChatMemoryService {

    private final MessageWindowChatMemory chatMemory;

    @Value("${MEMORY_CHAT_ENABLED:false}")
    private boolean enabled;

    @Value("${MEMORY_CHAT_WINDOW:20}")
    private int windowSize;

    public ChatMemoryService() {
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    /** 会话窗口记忆是否启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 获取会话历史（按 conversationId） */
    public List<Message> getHistory(String conversationId) {
        return chatMemory.get(conversationId);
    }

    /** 追加消息到会话窗口 */
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        chatMemory.add(conversationId, messages);
    }

    /** 清空会话窗口 */
    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }

    /** 当前窗口大小（供诊断） */
    public int getWindowSize() {
        return windowSize;
    }
}
