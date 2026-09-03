package com.example.springharness.controller;

import com.example.springharness.pse.ToolDescriptionService;
import com.example.springharness.pse.McpToolProvider;
import com.example.springharness.sandbox.DockerSandboxExecutor;
import com.example.springharness.service.SkillService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具与 MCP 状态查询 API。
 *
 * 提供前端 Runtime 面板所需的数据：
 * - GET /api/tools：所有已注册工具列表（本地 + MCP）
 * - GET /api/mcp/status：MCP 启用状态与工具统计
 */
@RestController
@RequestMapping("/api")
public class ToolsController {

    private final ToolDescriptionService toolDescriptionService;
    private final McpToolProvider mcpToolProvider;
    private final DockerSandboxExecutor sandboxExecutor;
    private final SkillService skillService;
    private final Environment env;

    public ToolsController(ToolDescriptionService toolDescriptionService,
                           McpToolProvider mcpToolProvider,
                           DockerSandboxExecutor sandboxExecutor,
                           SkillService skillService,
                           Environment env) {
        this.toolDescriptionService = toolDescriptionService;
        this.mcpToolProvider = mcpToolProvider;
        this.sandboxExecutor = sandboxExecutor;
        this.skillService = skillService;
        this.env = env;
    }

    /**
     * 从 application.yml 读取 MCP 服务器列表配置。
     * 配置格式：mcp.servers[0].name=filesystem, mcp.servers[0].type=stdio, ...
     */
    private List<Map<String, String>> loadMcpServers() {
        List<Map<String, String>> servers = new ArrayList<>();
        int i = 0;
        while (true) {
            String name = env.getProperty("mcp.servers[" + i + "].name");
            if (name == null) break;
            Map<String, String> server = new LinkedHashMap<>();
            server.put("name", name);
            server.put("type", env.getProperty("mcp.servers[" + i + "].type", "stdio"));
            server.put("description", env.getProperty("mcp.servers[" + i + "].description", ""));
            servers.add(server);
            i++;
        }
        return servers;
    }

    /**
     * 获取所有已注册工具列表。
     * 返回格式：{ tools: [ { name, description, source, fromMcp, mcpServer } ] }
     */
    @GetMapping("/tools")
    public Map<String, Object> getTools() {
        List<ToolCallback> allTools = toolDescriptionService.getToolCallbacks();
        List<Map<String, String>> mcpServers = loadMcpServers();

        // 获取第一个 MCP 服务器名称（当前只有一个服务器，所有 MCP 工具都属于它）
        String defaultMcpServer = (!mcpServers.isEmpty())
                ? mcpServers.get(0).get("name") : "unknown";

        List<Map<String, Object>> tools = allTools.stream()
                .map(tc -> {
                    var def = tc.getToolDefinition();
                    boolean fromMcp = tc.getClass().getSimpleName().contains("Mcp");
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", def.name());
                    tool.put("description", def.description());
                    tool.put("source", fromMcp ? "mcp" : "local");
                    tool.put("fromMcp", fromMcp);
                    if (fromMcp) {
                        tool.put("mcpServer", defaultMcpServer);
                    }
                    return tool;
                })
                .collect(Collectors.toList());

        long localCount = tools.stream().filter(t -> !(boolean) t.get("fromMcp")).count();
        long mcpCount = tools.size() - localCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        result.put("total", tools.size());
        result.put("localCount", localCount);
        result.put("mcpCount", mcpCount);
        return result;
    }

    /**
     * 获取 MCP 启用状态与工具统计。
     * 返回格式：{ enabled, toolCount, servers: [...], providers: [...] }
     */
    @GetMapping("/mcp/status")
    public Map<String, Object> getMcpStatus() {
        boolean enabled = mcpToolProvider.isEnabled();
        int mcpToolCount = enabled ? mcpToolProvider.getTools().size() : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("toolCount", mcpToolCount);
        result.put("servers", loadMcpServers());
        result.put("providers", toolDescriptionService.getProviderInfo());
        return result;
    }

    /**
     * 获取 Docker 沙箱状态。
     * 返回格式：{ enabled, dockerAvailable, supportedLanguages, config: {...} }
     */
    @GetMapping("/sandbox/status")
    public Map<String, Object> getSandboxStatus() {
        boolean enabled = sandboxExecutor.isDockerAvailable();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("dockerAvailable", sandboxExecutor.isDockerAvailable());
        result.put("supportedLanguages", sandboxExecutor.getSupportedLanguages());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("timeoutSeconds", env.getProperty("sandbox.timeout-seconds", "30"));
        config.put("memoryMb", env.getProperty("sandbox.memory-mb", "512"));
        config.put("cpus", env.getProperty("sandbox.cpus", "1"));
        config.put("maxOutputKb", env.getProperty("sandbox.max-output-kb", "100"));
        result.put("config", config);
        return result;
    }

    /**
     * 获取已加载的技能列表。
     * 返回格式：{ enabled, count, skills: [ { name, description } ] }
     */
    @GetMapping("/skills")
    public Map<String, Object> getSkills() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", skillService.isEnabled());
        result.put("count", skillService.getSkillCount());
        result.put("skills", skillService.getAllSkills());
        return result;
    }
}
