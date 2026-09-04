package com.example.springharness.config;

import com.example.springharness.controller.ExampleController;
import com.example.springharness.memory.MemoryService;
import com.example.springharness.pse.TokenUsageTracker;
import com.example.springharness.task.TaskManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义监控指标：通过 Actuator 的 /actuator/metrics、/actuator/prometheus 暴露。
 * 覆盖：Token 消耗、长时任务活跃数、记忆条数、示例 LLM 生成次数。
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterBinder harnessMetrics(TokenUsageTracker tokenTracker,
                                      TaskManager taskManager,
                                      MemoryService memoryService,
                                      ExampleController exampleController) {
        return registry -> {
            // Token 消耗（累计）
            Gauge.builder("harness.tokens.prompt", tokenTracker,
                            t -> t.getTotal().promptTokens())
                    .description("累计 Prompt tokens")
                    .register(registry);
            Gauge.builder("harness.tokens.completion", tokenTracker,
                            t -> t.getTotal().completionTokens())
                    .description("累计 Completion tokens")
                    .register(registry);
            Gauge.builder("harness.tokens.total", tokenTracker,
                            t -> t.getTotal().totalTokens())
                    .description("累计总 tokens")
                    .register(registry);
            Gauge.builder("harness.llm.calls", tokenTracker, TokenUsageTracker::getCallCount)
                    .description("LLM 调用次数")
                    .register(registry);

            // 长时任务活跃数
            Gauge.builder("harness.tasks.active", taskManager, TaskManager::activeCount)
                    .description("运行中的长时任务数")
                    .register(registry);

            // 记忆条数
            Gauge.builder("harness.memory.count", memoryService, MemoryService::count)
                    .description("长期记忆条目数")
                    .register(registry);

            // 示例任务 LLM 生成次数
            Gauge.builder("harness.examples.llm", exampleController, ExampleController::getLlmGeneratedCount)
                    .description("AI 生成示例任务次数")
                    .register(registry);
        };
    }
}
