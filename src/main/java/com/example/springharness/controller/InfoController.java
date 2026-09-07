package com.example.springharness.controller;

import com.example.springharness.config.ModelConfig;
import com.example.springharness.rag.RagService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读状态接口：供桌面 HUD 展示工具 / MCP / Knowledge / 模型概况。
 * 仅返回计数与名称，不暴露任何密钥或敏感配置。
 */
@RestController
public class InfoController {

    private final List<ToolCallback> toolCallbacks;
    private final RagService ragService;
    private final Environment env;

    public InfoController(List<ToolCallback> toolCallbacks, RagService ragService, Environment env) {
        this.toolCallbacks = toolCallbacks;
        this.ragService = ragService;
        this.env = env;
    }

    /** 从环境属性枚举 stdio MCP 服务器名（spring.ai.mcp.client.stdio.connections.<name>.*）。 */
    private Set<String> mcpServerNames() {
        Set<String> names = new LinkedHashSet<>();
        if (env instanceof AbstractEnvironment absEnv) {
            for (PropertySource<?> ps : absEnv.getPropertySources()) {
                if (ps instanceof EnumerablePropertySource<?> eps) {
                    for (String name : eps.getPropertyNames()) {
                        if (name.startsWith("spring.ai.mcp.client.stdio.")) {
                            String rest = name.substring("spring.ai.mcp.client.stdio.".length());
                            // Spring AI 1.x 结构为 stdio.connections.<server>.<prop>
                            if (rest.startsWith("connections.")) {
                                rest = rest.substring("connections.".length());
                            }
                            int dot = rest.indexOf('.');
                            names.add(dot > 0 ? rest.substring(0, dot) : rest);
                        }
                    }
                }
            }
        }
        return names;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Set<String> mcpServers = mcpServerNames();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tools", toolCallbacks.size());
        out.put("mcp", mcpServers.size());
        out.put("mcpServers", mcpServers);
        out.put("knowledge", ragService.listDocuments().size());
        out.put("models", ModelConfig.getEnabledModels().size());
        out.put("modelsAll", ModelConfig.getAllModels().size());
        out.put("uptimeSecs", (System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime()) / 1000);
        return out;
    }
}
