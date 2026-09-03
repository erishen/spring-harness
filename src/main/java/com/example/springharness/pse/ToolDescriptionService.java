package com.example.springharness.pse;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具描述服务：聚合所有 ToolProvider 的工具，动态生成工具描述。
 *
 * 工具来源（可扩展）：
 * - {@link LocalToolProvider}：本地注册的 FunctionToolCallback
 * - {@link McpToolProvider}：MCP 外部工具服务器（预留）
 * - 未来可新增自定义 ToolProvider
 *
 * 后续新增工具来源时，只需实现 ToolProvider 接口并注册为 Spring Bean，
 * PlannerAgent 和 SpecialistAgent 的 prompt 会自动包含新工具，无需修改代码。
 */
@Service
public class ToolDescriptionService {

    private final List<ToolProvider> toolProviders;
    private final List<ToolCallback> allTools;

    public ToolDescriptionService(List<ToolProvider> toolProviders) {
        this.toolProviders = toolProviders;
        this.allTools = new ArrayList<>();
        for (ToolProvider provider : toolProviders) {
            if (provider.isEnabled()) {
                allTools.addAll(provider.getTools());
            }
        }
    }

    /**
     * 获取所有已注册工具（聚合所有启用的 ToolProvider）。
     */
    public List<ToolCallback> getToolCallbacks() {
        return allTools;
    }

    /**
     * 生成格式化的工具描述列表（用于 prompt 注入）。
     * 格式：- toolName: description
     */
    public String generateToolList() {
        return allTools.stream()
                .map(tc -> "- " + tc.getToolDefinition().name() + ": " + tc.getToolDefinition().description())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 生成带序号的工具描述（用于 Planner 分解任务时参考）。
     */
    public String generateNumberedToolList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < allTools.size(); i++) {
            var def = allTools.get(i).getToolDefinition();
            sb.append(String.format("%d. **%s**: %s%n", i + 1, def.name(), def.description()));
        }
        return sb.toString();
    }

    /**
     * 获取工具名称列表。
     */
    public List<String> getToolNames() {
        return allTools.stream()
                .map(tc -> tc.getToolDefinition().name())
                .toList();
    }

    /**
     * 获取已注册工具数量。
     */
    public int getToolCount() {
        return allTools.size();
    }

    /**
     * 获取所有工具来源信息（用于调试和展示）。
     */
    public List<String> getProviderInfo() {
        return toolProviders.stream()
                .map(p -> String.format("%s: %d 个工具 (enabled=%s)",
                        p.getSourceName(), p.getTools().size(), p.isEnabled()))
                .toList();
    }
}
