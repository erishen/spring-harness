package com.example.springharness.controller;

import com.example.springharness.pse.PseOrchestrator;
import com.example.springharness.pse.PseResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PSE（Planner-Specialist-Evaluator）多 Agent 协作接口。
 *
 * 与单 Agent ReAct（/chat/agent/react）的区别：
 * - ReAct：一个 Agent 自己思考→调用工具→再思考
 * - PSE：三个 Agent 分工协作，Planner 规划分解、Specialist 执行、Evaluator 独立评审
 *
 * 接口：
 * - GET /chat/pse?message=xxx[&model=xxx]  （一次性返回完整结果）
 * - GET /chat/pse/stream?message=xxx[&model=xxx]  （SSE 流式输出，实时推送各阶段步骤）
 */
@RestController
@RequestMapping("/chat/pse")
public class PseController {

    private static final Logger log = LoggerFactory.getLogger(PseController.class);

    private final PseOrchestrator pseOrchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public PseController(PseOrchestrator pseOrchestrator) {
        this.pseOrchestrator = pseOrchestrator;
    }

    @GetMapping
    public Map<String, Object> chat(
            @RequestParam String message,
            @RequestParam(required = false) String model) {

        PseResult result = pseOrchestrator.execute(message, model);

        return Map.of(
                "answer", result.answer(),
                "steps", result.steps(),
                "tasks", result.tasks(),
                "status", result.status(),
                "totalDurationMs", result.totalDurationMs(),
                "mode", "pse-agent",
                "tokenUsage", result.tokenUsage(),
                "llmCallCount", result.llmCallCount()
        );
    }

    /**
     * SSE 流式输出：实时推送 Planner 规划、Specialist 执行、Evaluator 评审等各阶段步骤。
     *
     * 健壮性处理：
     * - 客户端断开/超时后，停止发送事件，避免 IllegalStateException
     * - 所有 send 操作都有异常捕获，不会导致线程崩溃
     * - AtomicBoolean 保证完成状态的原子性
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam String message,
            @RequestParam(required = false) String model) {

        SseEmitter emitter = new SseEmitter(300000L); // 300秒超时（PSE 多任务可能耗时较长）
        AtomicBoolean completed = new AtomicBoolean(false);

        // 心跳：Planner/Specialist 思考或工具调用间隙无输出时保持连接活跃，防止中间代理超时断流
        com.example.springharness.util.SseSupport.startHeartbeat(emitter);

        // 客户端断开/超时/出错时标记完成
        Runnable markCompleted = () -> {
            if (completed.compareAndSet(false, true)) {
                log.debug("SSE emitter 已完成（客户端断开/超时/出错），停止发送 PSE 事件");
            }
        };
        emitter.onCompletion(markCompleted);
        emitter.onTimeout(markCompleted);
        emitter.onError(e -> markCompleted.run());

        executor.execute(() -> {
            try {
                pseOrchestrator.executeStream(message, model, event -> {
                    // 检查 emitter 是否已完成，已完成则不再发送
                    if (completed.get()) {
                        return;
                    }
                    try {
                        String json = objectMapper.writeValueAsString(Map.of(
                                "type", event.type(),
                                "sequence", event.sequence(),
                                "step", event.step() != null ? event.step() : "",
                                "content", event.content() != null ? event.content() : ""
                        ));
                        emitter.send(SseEmitter.event().data(json));
                    } catch (IOException | IllegalStateException e) {
                        // 发送失败（客户端断开等），标记完成，后续不再发送
                        if (completed.compareAndSet(false, true)) {
                            log.debug("SSE 发送失败，标记完成: {}", e.getMessage());
                        }
                    }
                });

                // PSE 执行完成，尝试发送 done 事件并完成 emitter
                if (!completed.get()) {
                    try {
                        emitter.complete();
                    } catch (IllegalStateException e) {
                        log.debug("emitter 已完成，忽略 complete 调用");
                    }
                }
            } catch (Exception e) {
                log.error("PSE 流式执行异常", e);
                if (!completed.get()) {
                    try {
                        emitter.send(SseEmitter.event().data(
                                objectMapper.writeValueAsString(Map.of("type", "error", "content", e.getMessage()))
                        ));
                        emitter.completeWithError(e);
                    } catch (IOException | IllegalStateException ignored) {
                        // 忽略，emitter 可能已完成
                    }
                }
            }
        });

        return emitter;
    }
}
