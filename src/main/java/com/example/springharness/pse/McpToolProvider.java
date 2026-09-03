package com.example.springharness.pse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * MCP（Model Context Protocol）工具提供者。
 *
 * 通过 Spring AI MCP 客户端连接外部 MCP 服务器，动态获取工具。
 * MCP 工具会自动转换为 Spring AI 的 ToolCallback 接口，可直接被 Planner/Specialist 使用。
 *
 * 支持的传输方式：
 * - STDIO：启动本地 MCP 服务器进程（如 filesystem、github）
 * - SSE：连接 SSE 协议的 MCP 服务器
 * - Streamable HTTP：连接 Streamable HTTP 协议的 MCP 服务器
 *
 * 配置示例（application.yml）：
 * ```yaml
 * spring:
 *   ai:
 *     mcp:
 *       client:
 *         stdio:
 *           connections:
 *             filesystem:
 *               command: npx
 *               args: -y @modelcontextprotocol/server-filesystem /path/to/dir
 *         streamable-http:
 *           connections:
 *             weather-server:
 *               url: http://localhost:8080
 * ```
 *
 * 启用后，PlannerAgent 和 SpecialistAgent 会自动获得 MCP 工具，无需修改代码。
 */
@Component
public class McpToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    @Value("${mcp.enabled:false}")
    private boolean mcpEnabled;

    /**
     * 使用 ObjectProvider 延迟注入，避免 MCP 未配置时启动失败。
     * 当 mcp.enabled=false 或未配置 MCP 服务器时，ToolCallbackProvider Bean 可能不存在。
     */
    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;

    public McpToolProvider(ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    @Override
    public List<ToolCallback> getTools() {
        if (!mcpEnabled) {
            return List.of();
        }

        ToolCallbackProvider provider = mcpToolCallbackProvider.getIfAvailable();
        if (provider == null) {
            log.warn("MCP 已启用但未找到 ToolCallbackProvider Bean，请检查 MCP 服务器配置");
            return List.of();
        }

        ToolCallback[] callbacks = provider.getToolCallbacks();
        if (callbacks == null || callbacks.length == 0) {
            log.info("MCP 已连接但暂无可用工具");
            return List.of();
        }

        List<ToolCallback> tools = Arrays.asList(callbacks);
        log.info("从 MCP 获取到 {} 个工具: {}", tools.size(),
                tools.stream().map(t -> t.getToolDefinition().name()).toList());
        return tools;
    }

    @Override
    public String getSourceName() {
        return "mcp";
    }

    @Override
    public boolean isEnabled() {
        return mcpEnabled;
    }
}
