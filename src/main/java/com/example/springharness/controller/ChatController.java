package com.example.springharness.controller;

import com.example.springharness.service.MultiModelService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI ChatClient 的对话示例。
 *
 * <p>接口：
 * <ul>
 *   <li>GET /chat?message=xxx —— 非流式，一次返回完整回答</li>
 *   <li>GET /chat/stream?message=xxx —— 流式（SSE），逐字返回</li>
 * </ul>
 * 两个接口均支持可选的 model 参数，动态指定模型（覆盖默认配置）。
 * 支持多模型提供商：DashScope（阿里云百炼）和 agnes（OpenAI 兼容接口）。
 * 支持多轮对话上下文：通过 messages 参数传递历史消息。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final MultiModelService multiModelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    /** 最多保留的历史消息条数 */
    private static final int MAX_HISTORY_MESSAGES = 20;

    public ChatController(MultiModelService multiModelService) {
        this.multiModelService = multiModelService;
    }

    /** 非流式对话 */
    @GetMapping
    public String chat(
            @RequestParam(defaultValue = "你好，请用一句话介绍你自己") String message,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String messages,
            @RequestParam(required = false) Double temperature,
            @RequestParam(required = false) Integer maxTokens,
            @RequestParam(required = false) Double topP,
            @RequestParam(required = false) String systemPrompt) {
        ChatClient.Builder builder = multiModelService.createChatClientBuilder(model);
        var prompt = builder.build().prompt();

        // 自定义系统提示词
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.system(systemPrompt);
        }

        // 添加历史消息
        List<Message> historyMessages = parseHistoryMessages(messages);
        if (!historyMessages.isEmpty()) {
            prompt.messages(historyMessages);
        }
        prompt.user(message);

        // 构建 ChatOptions，支持自定义参数
        var optionsBuilder = ChatOptions.builder();
        if (model != null && !model.isBlank()) {
            optionsBuilder.model(model);
        }
        optionsBuilder.maxTokens(maxTokens != null ? maxTokens : this.maxTokens);
        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }
        if (topP != null) {
            optionsBuilder.topP(topP);
        }
        prompt.options(optionsBuilder.build());
        return prompt.call().content();
    }

    /** 流式对话（SSE） */
    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chatStream(
            @RequestParam(defaultValue = "请讲一个简短的笑话") String message,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String messages,
            @RequestParam(required = false) Double temperature,
            @RequestParam(required = false) Integer maxTokens,
            @RequestParam(required = false) Double topP,
            @RequestParam(required = false) String systemPrompt) {
        ChatClient.Builder builder = multiModelService.createChatClientBuilder(model);
        var prompt = builder.build().prompt();

        // 自定义系统提示词
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.system(systemPrompt);
        }

        // 添加历史消息
        List<Message> historyMessages = parseHistoryMessages(messages);
        if (!historyMessages.isEmpty()) {
            prompt.messages(historyMessages);
        }
        prompt.user(message);

        // 构建 ChatOptions，支持自定义参数
        var optionsBuilder = ChatOptions.builder();
        if (model != null && !model.isBlank()) {
            optionsBuilder.model(model);
        }
        optionsBuilder.maxTokens(maxTokens != null ? maxTokens : this.maxTokens);
        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }
        if (topP != null) {
            optionsBuilder.topP(topP);
        }
        prompt.options(optionsBuilder.build());
        return prompt.stream().content();
    }

    /**
     * 解析历史消息 JSON 字符串为 Message 列表。
     * 格式：[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]
     */
    private List<Message> parseHistoryMessages(String messagesJson) {
        List<Message> result = new ArrayList<>();
        if (messagesJson == null || messagesJson.isBlank()) {
            return result;
        }
        try {
            List<Map<String, String>> rawMessages = objectMapper.readValue(messagesJson,
                    new TypeReference<List<Map<String, String>>>() {});
            // 只保留最近的 MAX_HISTORY_MESSAGES 条
            int start = Math.max(0, rawMessages.size() - MAX_HISTORY_MESSAGES);
            for (int i = start; i < rawMessages.size(); i++) {
                Map<String, String> msg = rawMessages.get(i);
                String role = msg.getOrDefault("role", "").toLowerCase();
                String content = msg.getOrDefault("content", "");
                if (content.isBlank()) continue;
                if ("user".equals(role)) {
                    result.add(new UserMessage(content));
                } else if ("assistant".equals(role) || "ai".equals(role)) {
                    result.add(new AssistantMessage(content));
                }
            }
        } catch (Exception e) {
            // 解析失败时忽略历史消息，不影响正常对话
            System.err.println("解析历史消息失败: " + e.getMessage());
        }
        return result;
    }
}
