package com.example.springharness.controller;

import com.example.springharness.util.ErrorSanitizer;
import com.example.springharness.agent.ReActAgentService;
import com.example.springharness.dto.AgentResponse;
import com.example.springharness.service.MultiModelService;
import com.example.springharness.service.PromptService;
import com.example.springharness.tool.ToolCallRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 对话接口：支持工具调用和 ReAct 多轮推理。
 *
 * <p>接口：
 * <ul>
 *   <li>GET /chat/agent?message=xxx —— 基础 Agent（单轮工具调用，返回最终回答+工具调用过程）</li>
 *   <li>GET /chat/agent/react?message=xxx —— ReAct Agent（一次性返回完整结果）</li>
 *   <li>GET /chat/agent/react/stream?message=xxx —— ReAct Agent（SSE 流式输出，实时推送思考过程和工具调用）</li>
 * </ul>
 *
 * <p>基础 Agent 与 ReAct Agent 的区别：
 * <ul>
 *   <li>基础 Agent：单轮工具调用，LLM 决策一次 → 调用工具 → 返回结果</li>
 *   <li>ReAct Agent：多轮循环，LLM 思考 → 调用工具 → 观察结果 → 再思考 → 再调用工具... 直到任务完成</li>
 * </ul>
 */
@RestController
@RequestMapping("/chat/agent")
public class AgentController {

    private final MultiModelService multiModelService;
    private final List<ToolCallback> toolCallbacks;
    private final PromptService promptService;
    private final ReActAgentService reActAgentService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    public AgentController(MultiModelService multiModelService,
                           List<ToolCallback> toolCallbacks,
                           PromptService promptService,
                           ReActAgentService reActAgentService) {
        this.multiModelService = multiModelService;
        this.toolCallbacks = toolCallbacks;
        this.promptService = promptService;
        this.reActAgentService = reActAgentService;
    }

    // ==================== 基础 Agent（单轮工具调用） ====================

    /**
     * 基础 Agent 对话：单轮工具调用，返回最终回答 + 工具调用过程。
     */
    @GetMapping
    public AgentResponse chat(
            @RequestParam(defaultValue = "你好") String message,
            @RequestParam(required = false) String model) {
        // 清空本次请求的工具调用记录
        ToolCallRecorder.clear();

        var prompt = multiModelService.createChatClientBuilder(model).build()
                .prompt()
                .user(message)
                .toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]))
                .system(promptService.basicAgentPrompt());
        if (model != null && !model.isBlank()) {
            prompt.options(ChatOptions.builder().model(model).maxTokens(maxTokens).build());
        }
        String answer = prompt.call().content();

        // 收集工具调用过程
        var toolCalls = ToolCallRecorder.getRecords().stream()
                .map(r -> new AgentResponse.ToolCall(r.name(), r.input(), r.output(), r.durationMs()))
                .toList();

        return new AgentResponse(answer, toolCalls);
    }

    // ==================== ReAct Agent（多轮推理） ====================

    /**
     * ReAct Agent：一次性返回完整结果（兼容旧接口）。
     */
    @GetMapping("/react")
    public Map<String, Object> reactChat(
            @RequestParam String message,
            @RequestParam(required = false) String model) {

        ReActAgentService.ReActResult result = reActAgentService.run(message, model);

        return Map.of(
                "answer", result.answer(),
                "steps", result.steps(),
                "mode", "react-agent",
                "iterations", result.steps().size(),
                "tokenUsage", result.tokenUsage(),
                "llmCallCount", result.llmCallCount()
        );
    }

    /**
     * ReAct Agent SSE 流式输出：实时推送思考过程、工具调用和最终答案。
     */
    @GetMapping(value = "/react/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reactChatStream(
            @RequestParam String message,
            @RequestParam(required = false) String model) {

        SseEmitter emitter = new SseEmitter(180000L); // 180秒超时
        AtomicBoolean completed = new AtomicBoolean(false);

        // 心跳：Agent 思考/工具调用间隙无输出时保持连接活跃，防止中间代理超时断流
        com.example.springharness.util.SseSupport.startHeartbeat(emitter);

        // 客户端断开/超时/出错时标记完成
        Runnable markCompleted = () -> completed.compareAndSet(false, true);
        emitter.onCompletion(markCompleted);
        emitter.onTimeout(markCompleted);
        emitter.onError(e -> markCompleted.run());

        executor.execute(() -> {
            try {
                reActAgentService.runStream(message, model, event -> {
                    if (completed.get()) return;
                    try {
                        String json = objectMapper.writeValueAsString(Map.of(
                                "type", event.type(),
                                "iteration", event.iteration(),
                                "toolCall", event.toolCall() != null ? event.toolCall() : "",
                                "content", event.content() != null ? event.content() : "",
                                "step", event.step() != null ? event.step() : "",
                                "tokenUsage", event.tokenUsage() != null ? event.tokenUsage() : ""
                        ));
                        emitter.send(SseEmitter.event().data(json));
                    } catch (IOException | IllegalStateException e) {
                        completed.compareAndSet(false, true);
                    }
                });
                if (!completed.get()) {
                    try { emitter.complete(); } catch (IllegalStateException ignored) {}
                }
            } catch (Exception e) {
                if (!completed.get()) {
                    try {
                        emitter.send(SseEmitter.event().data(
                                objectMapper.writeValueAsString(Map.of("type", "error", "content", ErrorSanitizer.sanitize(e)))
                        ));
                        emitter.completeWithError(e);
                    } catch (IOException | IllegalStateException ignored) {}
                }
            }
        });

        return emitter;
    }
}
