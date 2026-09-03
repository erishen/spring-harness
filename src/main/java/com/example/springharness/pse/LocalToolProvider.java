package com.example.springharness.pse;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地工具提供者：封装当前在 ToolConfig 中注册的 FunctionToolCallback。
 *
 * 当前工具：calculator、get_datetime、query_stock
 * 后续新增本地工具时，只需在 ToolConfig 中注册，这里自动包含。
 */
@Component
public class LocalToolProvider implements ToolProvider {

    private final List<ToolCallback> toolCallbacks;

    public LocalToolProvider(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks;
    }

    @Override
    public List<ToolCallback> getTools() {
        return toolCallbacks;
    }

    @Override
    public String getSourceName() {
        return "local";
    }
}
