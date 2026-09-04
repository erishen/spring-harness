package com.example.springharness.controller;

import com.example.springharness.memory.MemoryService;
import com.example.springharness.pse.McpToolProvider;
import com.example.springharness.pse.ToolDescriptionService;
import com.example.springharness.rag.RagService;
import com.example.springharness.sandbox.DockerSandboxExecutor;
import com.example.springharness.service.SkillService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 示例任务生成 API。
 *
 * <p>根据当前环境实际拥有的能力（本地工具 / MCP 工具 / 代码沙箱语言 / 知识库文档 /
 * 长期记忆 / Skills），结合请求的模式（chat / rag / agent / pse / longtask），
 * 动态生成可点击试用的示例问题。支持前端「刷新」重新获取——环境能力变化后
 * （上传新文档、切换沙箱语言、加载新技能）示例会自动跟随变化。
 *
 * <p>返回示例结构：{ title, type, typeLabel, tags }，tags 标注该示例依赖的能力。
 */
@RestController
@RequestMapping("/api")
public class ExampleController {

    private final ToolDescriptionService toolDescriptionService;
    private final McpToolProvider mcpToolProvider;
    private final DockerSandboxExecutor sandboxExecutor;
    private final RagService ragService;
    private final MemoryService memoryService;
    private final SkillService skillService;

    public ExampleController(ToolDescriptionService toolDescriptionService,
                             McpToolProvider mcpToolProvider,
                             DockerSandboxExecutor sandboxExecutor,
                             RagService ragService,
                             MemoryService memoryService,
                             SkillService skillService) {
        this.toolDescriptionService = toolDescriptionService;
        this.mcpToolProvider = mcpToolProvider;
        this.sandboxExecutor = sandboxExecutor;
        this.ragService = ragService;
        this.memoryService = memoryService;
        this.skillService = skillService;
    }

    /** 环境能力快照 */
    private record Capability(List<String> localTools, List<String> mcpTools,
                              Set<String> languages, List<String> documents,
                              boolean memoryEnabled, int memoryCount,
                              List<String> skillNames) {
        boolean has(String tool) { return localTools.contains(tool) || mcpTools.contains(tool); }
        boolean hasSkill(String name) { return skillNames.contains(name); }
        boolean hasMcp(String tool) { return mcpTools.contains(tool); }
    }

    /** 采集当前环境能力 */
    private Capability collect() {
        List<String> local = new ArrayList<>();
        List<String> mcp = new ArrayList<>();
        for (ToolCallback tc : toolDescriptionService.getToolCallbacks()) {
            String name = tc.getToolDefinition().name();
            if (tc.getClass().getSimpleName().contains("Mcp")) {
                mcp.add(name);
            } else {
                local.add(name);
            }
        }
        if (mcpToolProvider.isEnabled()) {
            for (ToolCallback tc : mcpToolProvider.getTools()) {
                String name = tc.getToolDefinition().name();
                if (!mcp.contains(name)) mcp.add(name);
            }
        }
        Set<String> languages = new LinkedHashSet<>(sandboxExecutor.getSupportedLanguages());
        List<String> docs = ragService.listDocuments().stream()
                .map(d -> d.fileName()).limit(3).toList();
        boolean memOn = memoryService.isEnabled();
        int memCount = memoryService.count();
        List<String> skills = skillService.getAllSkills().stream()
                .map(s -> s.name()).toList();
        return new Capability(local, mcp, languages, docs, memOn, memCount, skills);
    }

    /**
     * GET /api/examples?mode=chat|agent|pse|rag|longtask
     */
    @GetMapping("/examples")
    public Map<String, Object> getExamples(@RequestParam(defaultValue = "chat") String mode) {
        Capability cap = collect();
        List<Map<String, Object>> examples = switch (mode) {
            case "agent" -> buildAgent(cap);
            case "pse" -> buildPse(cap);
            case "rag" -> buildRag(cap);
            case "longtask" -> buildLongTask(cap);
            default -> buildChat(cap);
        };

        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("localTools", cap.localTools());
        capability.put("mcpTools", cap.mcpTools());
        capability.put("languages", cap.languages());
        capability.put("documentCount", cap.documents().size());
        capability.put("documents", cap.documents());
        capability.put("memoryEnabled", cap.memoryEnabled());
        capability.put("memoryCount", cap.memoryCount());
        capability.put("skills", cap.skillNames());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("capability", capability);
        result.put("examples", examples);
        return result;
    }

    // ==================== 各模式示例生成 ====================

    private static Map<String, Object> example(String type, String typeLabel, String title, List<String> tags) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("typeLabel", typeLabel);
        e.put("title", title);
        e.put("tags", tags);
        return e;
    }

    /** 普通对话 */
    private List<Map<String, Object>> buildChat(Capability cap) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(example("chat", "对话", "用 Markdown 写一份 Spring AI 简介", List.of("markdown")));
        list.add(example("chat", "对话", "解释 RAG 与微调的区别，各适合什么场景", List.of()));
        // 知识库增强
        if (!cap.documents().isEmpty()) {
            String doc = cap.documents().get(0);
            list.add(example("chat", "对话", "基于知识库：\"" + doc + "\" 主要讲了什么", List.of("knowledge")));
        }
        // 长期记忆增强
        if (cap.memoryEnabled() && cap.memoryCount() > 0) {
            list.add(example("chat", "对话", "根据你对我长期记忆的了解，总结一下我的偏好与事实", List.of("memory")));
        }
        list.add(example("chat", "对话", "写一段 Python 快速排序代码并解释", List.of()));
        return list;
    }

    /** RAG 知识库 */
    private List<Map<String, Object>> buildRag(Capability cap) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (cap.documents().isEmpty()) {
            list.add(example("rag", "RAG", "当前知识库为空，先上传 PDF/MD/TXT 文档再体验检索", List.of("upload")));
            list.add(example("rag", "RAG", "上传文档后可基于内容提问，如「文档提到了哪些功能特性」", List.of("hint")));
            return list;
        }
        for (String doc : cap.documents()) {
            list.add(example("rag", "RAG", "\"" + doc + "\" 有哪些核心内容 / 功能特性", List.of("knowledge")));
        }
        if (cap.documents().size() >= 2) {
            list.add(example("rag", "RAG", "对比知识库中 \"" + cap.documents().get(0) + "\" 与 \"" + cap.documents().get(1) + "\" 的侧重点", List.of("knowledge")));
        }
        list.add(example("rag", "RAG", "基于知识库回答：Spring AI 支持哪些向量库", List.of("knowledge")));
        return list;
    }

    /** ReAct Agent */
    private List<Map<String, Object>> buildAgent(Capability cap) {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean hasStock = cap.has("query_stock");
        boolean hasFx = cap.has("query_exchange_rate");
        boolean hasCalc = cap.has("calculator");
        boolean hasTime = cap.has("get_datetime");
        boolean hasCode = cap.has("execute_code");
        boolean hasMcpFs = cap.hasMcp("list_directory") || cap.hasMcp("read_file");

        // 工具组合
        if (hasStock && hasFx && hasCalc) {
            list.add(example("agent", "ReAct", "查 AAPL / MSFT / TSLA 实时行情，算买 100 股各需多少人民币并比较今日涨幅",
                    List.of("query_stock", "query_exchange_rate", "calculator")));
        } else if (hasStock && hasCalc) {
            list.add(example("agent", "ReAct", "查 AAPL 实时股价，算买 100 股要多少钱", List.of("query_stock", "calculator")));
        }
        if (hasTime && hasCalc && hasFx) {
            list.add(example("agent", "ReAct", "现在几点？算 123×456，再查美元兑人民币汇率", List.of("get_datetime", "calculator", "query_exchange_rate")));
        } else if (hasTime && hasCalc) {
            list.add(example("agent", "ReAct", "现在几点？算 123×456 等于多少", List.of("get_datetime", "calculator")));
        }

        // 代码沙箱：每个语言一条
        if (hasCode) {
            int count = 0;
            for (String lang : cap.languages()) {
                String task = codeTask(lang);
                if (task == null) continue;
                list.add(example("agent", "ReAct", task, List.of("execute_code", lang)));
                if (++count >= 3) break; // 最多 3 条语言任务
            }
        }

        // MCP 文件能力
        if (hasMcpFs) {
            list.add(example("agent", "ReAct", "列出项目目录结构，读取 README.md 并总结项目要点",
                    List.of("mcp list_directory", "mcp read_file")));
        }

        // Skills
        if (cap.has("skill_run") && cap.hasSkill("code-review")) {
            list.add(example("agent", "ReAct", "用代码审查技能审查 src 下一个 Java 文件，输出结构化审查报告",
                    List.of("skill_run", "code-review")));
        }

        // 知识库工具联动
        if (cap.has("search_knowledge") && !cap.documents().isEmpty()) {
            list.add(example("agent", "ReAct", "在知识库检索并回答：\"" + cap.documents().get(0) + "\" 讲了什么",
                    List.of("search_knowledge")));
        }

        if (list.isEmpty()) {
            list.add(example("agent", "ReAct", "解释一下 ReAct Agent 的执行流程", List.of()));
        }
        return list;
    }

    /** PSE 协作 */
    private List<Map<String, Object>> buildPse(Capability cap) {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean hasStock = cap.has("query_stock");
        boolean hasFx = cap.has("query_exchange_rate");
        boolean hasCalc = cap.has("calculator");

        if (hasStock && hasFx && hasCalc) {
            list.add(example("pse", "PSE", "查询三只股票实时行情并比较涨幅（Planner 分解 → Specialist 并行 → Evaluator 评审）",
                    List.of("query_stock", "query_exchange_rate", "calculator")));
        }
        if (hasStock && hasCalc) {
            list.add(example("pse", "PSE", "买 100 股 MSFT 要多少钱", List.of("query_stock", "calculator")));
        }
        if (cap.has("get_datetime")) {
            list.add(example("pse", "PSE", "基于当前时间规划今天的学习 / 工作计划", List.of("get_datetime")));
        }
        if (cap.has("skill_run") && cap.hasSkill("weekly-investment")) {
            list.add(example("pse", "PSE", "生成一份本周投资组合周报（调用投资周报技能）",
                    List.of("skill_run", "weekly-investment")));
        }
        if (cap.has("skill_run") && cap.hasSkill("code-review")) {
            list.add(example("pse", "PSE", "用 PSE 三角色审查一个代码文件，输出结构化报告",
                    List.of("skill_run", "code-review")));
        }
        if (list.isEmpty()) {
            list.add(example("pse", "PSE", "把「写一篇 Spring AI 学习总结」拆解为多个子任务并协作完成", List.of()));
        }
        return list;
    }

    /** 长时任务 */
    private List<Map<String, Object>> buildLongTask(Capability cap) {
        List<Map<String, Object>> list = new ArrayList<>();
        boolean hasCode = cap.has("execute_code");
        // 代码沙箱各语言计算密集型任务
        if (hasCode) {
            for (String lang : cap.languages()) {
                String task = longCodeTask(lang);
                if (task != null) {
                    list.add(example("agent", "ReAct", task, List.of("execute_code", lang)));
                }
            }
        }
        // MCP 文件长任务
        if (cap.hasMcp("list_directory") && cap.hasMcp("read_file")) {
            list.add(example("agent", "ReAct", "遍历项目目录，按扩展名统计文件数量并汇总总行数",
                    List.of("mcp list_directory", "mcp read_file")));
        }
        // 技能长任务
        if (cap.has("skill_run") && cap.hasSkill("weekly-investment")) {
            list.add(example("pse", "PSE", "加载投资周报技能并生成本周投资周报",
                    List.of("skill_run", "weekly-investment")));
        }
        if (list.isEmpty()) {
            list.add(example("agent", "ReAct", "写一段长文，介绍 spring-harness 项目的五大模式", List.of()));
        }
        return list;
    }

    /** 语言 → 短代码任务（Agent 快速验证） */
    private static String codeTask(String lang) {
        return switch (lang) {
            case "python" -> "用 Python 写快速排序并输出排序结果";
            case "javascript" -> "用 JavaScript 写冒泡排序并输出";
            case "java" -> "用 Java 写冒泡排序并输出";
            case "go" -> "用 Go 并发计算 1~100000 内素数个数";
            case "rust" -> "用 Rust 计算斐波那契第 25 项";
            case "c" -> "用 C 列出 1 到 100 的素数";
            case "cpp" -> "用 C++ 反转并排序一个数组";
            case "shell" -> "用 Shell 统计当前目录文件数量与总大小";
            default -> null;
        };
    }

    /** 语言 → 长时计算任务（分钟级，适合后台执行） */
    private static String longCodeTask(String lang) {
        return switch (lang) {
            case "go" -> "用 Go 并发筛出 1~1000000 的全部素数，统计个数与耗时（计算密集型）";
            case "java" -> "用 Java 生成 10 万条随机整数并排序，输出最大的 10 个数与耗时";
            case "python" -> "用 Python 计算 1 万以内所有质数之和，并统计每个数位的出现次数";
            case "rust" -> "用 Rust 计算 1000000 以内所有质数之和（多线程）";
            case "c" -> "用 C 计算 100000 以内所有质数并统计数量";
            case "cpp" -> "用 C++ 对 100 万随机整数做归并排序并输出耗时";
            case "javascript" -> "用 JavaScript 计算 500000 以内斐波那契数列中偶数的和";
            case "shell" -> "用 Shell 遍历项目目录，统计各类源码文件的总行数";
            default -> null;
        };
    }
}
