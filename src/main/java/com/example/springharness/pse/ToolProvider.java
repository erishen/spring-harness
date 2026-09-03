package com.example.springharness.pse;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具提供者接口：统一不同来源的工具注册。
 *
 * 当前实现：
 * - {@link LocalToolProvider}：本地注册的 FunctionToolCallback（calculator、get_datetime、query_stock）
 *
 * 未来扩展：
 * - MCP 工具提供者：连接 MCP（Model Context Protocol）服务器，动态获取外部工具
 * - 自定义工具提供者：从数据库/配置中心动态加载工具
 *
 * 所有 ToolProvider 的工具会被 {@link ToolDescriptionService} 聚合，
 * PlannerAgent 和 SpecialistAgent 自动获得所有可用工具，无需修改代码。
 */
public interface ToolProvider {

    /**
     * 获取该来源提供的所有工具。
     */
    List<ToolCallback> getTools();

    /**
     * 工具来源名称（用于日志和展示）。
     */
    String getSourceName();

    /**
     * 是否启用（可通过配置控制）。
     */
    default boolean isEnabled() {
        return true;
    }
}
