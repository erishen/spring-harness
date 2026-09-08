package com.example.springharness.config;

import java.time.Duration;

import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 客户端初始化超时：20s（SDK 默认）→ 60s。
 *
 * <p>背景：spring-ai 1.1.2 只暴露 {@code spring.ai.mcp.client.request-timeout}
 * （映射到 requestTimeout），<b>不暴露</b> initializationTimeout——而
 * McpSyncClient.initialize() 用的是后者。MCP stdio 子进程
 * （mcp-server-filesystem / portfolio-check / pse-review）在机器高负载
 * （多个 AI IDE 并行编译、CPU 满载）时冷启动可超过 20s，导致
 * McpClientAutoConfiguration 初始化超时 → Spring 上下文刷新失败 →
 * Tomcat 端口不绑定，后端"起了但没起来"。
 *
 * <p>实测：8080 探活 000，app.log 尾部为
 * {@code TimeoutException: Did not observe any item or terminal signal
 * within 20000ms}（McpSyncClient.initialize）。
 * 调到 60s 后，即使满载也能完成初始化。
 */
@Configuration
public class McpClientInitTimeoutConfig {

	@Bean
	public McpSyncClientCustomizer mcpInitTimeoutCustomizer() {
		return (name, spec) -> spec.initializationTimeout(Duration.ofSeconds(60));
	}
}
