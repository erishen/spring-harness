package com.example.springharness.util;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE 心跳支持。
 *
 * <p>在长任务流式输出（ReAct / PSE）中，Agent 思考或工具调用间隙可能长时间
 * 没有事件发出（几十秒甚至更久），中间代理 / 浏览器可能因「连接空闲」将其断开，
 * 产生 AsyncRequestTimeoutException / 断流。心跳通过周期性发送 SSE 注释行
 * （{@code : ping}）保持连接活跃；注释行对客户端不可见，也不触发任何事件。
 */
public final class SseSupport {

    private static final long HEARTBEAT_INTERVAL_MS = 15000L;

    private SseSupport() {}

    /**
     * 为 SseEmitter 启动守护心跳线程，并在 emitter 完成 / 超时 / 出错时自动停止。
     */
    public static void startHeartbeat(SseEmitter emitter) {
        if (emitter == null) return;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                // 连接已关闭，停止心跳
                scheduler.shutdownNow();
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(scheduler::shutdownNow);
        emitter.onTimeout(scheduler::shutdownNow);
        emitter.onError(e -> scheduler.shutdownNow());
    }
}
