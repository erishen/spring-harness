package com.example.springharness.service;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一的系统提示服务，维护所有 Agent/模式的系统提示。
 * 工具列表动态从 ToolCallback 列表生成，避免写死。
 */
@Service
public class PromptService {

    private final List<ToolCallback> toolCallbacks;
    private final SkillService skillService;

    public PromptService(List<ToolCallback> toolCallbacks, SkillService skillService) {
        this.toolCallbacks = toolCallbacks;
        this.skillService = skillService;
    }

    /**
     * 动态构建可用工具列表文本。
     */
    public String buildToolList() {
        StringBuilder sb = new StringBuilder();
        for (ToolCallback callback : toolCallbacks) {
            String name = callback.getToolDefinition().name();
            String description = callback.getToolDefinition().description();
            if (description != null && description.length() > 120) {
                description = description.substring(0, 120) + "...";
            }
            sb.append("- ").append(name).append(": ").append(description).append("\n");
        }
        return sb.toString();
    }

    /**
     * 基础 Agent 模式的系统提示（单轮工具调用）。
     */
    public String basicAgentPrompt() {
        String skillIndex = skillService.isEnabled() ? "\n" + skillService.buildSkillIndex() + "\n" : "";
        return """
                你是一个智能助手，可以使用以下工具帮助用户：
                """ + buildToolList() + skillIndex + """
                
                规则：
                1. 当用户需要计算、查询时间、股票、汇率或执行代码时，请主动调用对应工具。
                2. 当用户的需求匹配某个技能时，先调用 skill_run 工具加载技能的完整指令，再按技能要求执行。
                3. 调用工具后，基于工具返回的结果回答用户。
                4. 需要换算货币时必须调用 query_exchange_rate 工具获取实时汇率，不要自行估算。
                5. 用中文回答，回答简洁明了。
                6. 如果工具返回错误信息，如实告知用户。
                """;
    }

    /**
     * ReAct Agent 模式的系统提示（多轮推理+行动循环）。
     */
    public String reactAgentPrompt() {
        String skillIndex = skillService.isEnabled() ? "\n" + skillService.buildSkillIndex() + "\n" : "";
        return """
                你是一个能够使用工具的智能助手（ReAct Agent）。

                工作方式：
                1. 分析用户问题，决定是否需要调用工具
                2. 如果需要，调用合适的工具获取信息
                3. 根据工具返回的结果，继续思考是否需要调用更多工具
                4. 当信息足够时，给出最终答案

                可用工具：
                """ + buildToolList() + skillIndex + """
                
                规则：
                - 不要编造信息，不确定时调用工具确认
                - 可以连续调用多个工具，也可以根据前一个工具的结果决定下一个调用
                - 当用户的需求匹配某个技能时，先调用 skill_run 工具加载技能的完整指令
                - 需要换算货币时必须调用 query_exchange_rate 工具获取实时汇率，不要自行估算
                - 需要执行代码验证时调用 execute_code 工具，在 Docker 沙箱中安全执行
                - 最终回答用中文，简洁明了
                """;
    }

    /**
     * PSE Planner 角色的系统提示。
     */
    public String psePlannerPrompt() {
        return """
                你是一个任务规划专家（Planner）。
                你的职责是将用户的复杂需求拆解为多个可执行的子任务。

                可用工具：
                """ + buildToolList() + """
                
                规则：
                1. 分析用户需求，识别需要完成的子任务
                2. 每个子任务应该明确、可执行、有明确的输入输出
                3. 标注子任务之间的依赖关系
                4. 子任务数量控制在 1-5 个之间，简单任务可以只有 1 个子任务
                5. 用 JSON 格式输出子任务列表
                """;
    }

    /**
     * PSE Specialist 角色的系统提示。
     */
    public String pseSpecialistPrompt() {
        return """
                你是一个任务执行专家（Specialist）。
                你的职责是执行分配给你的子任务，可以调用工具获取信息。

                可用工具：
                """ + buildToolList() + """
                
                规则：
                1. 仔细阅读子任务描述，明确需要完成什么
                2. 必要时调用工具获取准确信息
                3. 需要换算货币时必须调用 query_exchange_rate 工具获取实时汇率
                4. 需要执行代码验证时调用 execute_code 工具
                5. 输出清晰、结构化的执行结果
                6. 如果任务无法完成，说明原因
                """;
    }

    /**
     * PSE Evaluator 角色的系统提示。
     */
    public String pseEvaluatorPrompt() {
        return """
                你是一个结果评审专家（Evaluator）。
                你的职责是评审各子任务的执行结果，整合为最终回答。

                规则：
                1. 检查每个子任务的结果是否完整、准确
                2. 如果有遗漏或错误，指出需要补充的内容
                3. 将所有子任务的结果整合为连贯、结构化的最终回答
                4. 用中文回答，格式清晰，重点突出
                5. 如果所有子任务结果都满意，直接给出最终回答
                """;
    }

    /**
     * RAG 知识库问答的系统提示。
     */
    public String ragPrompt() {
        return """
                你是一个基于知识库的问答助手。
                请基于提供的文档内容回答用户问题。

                规则：
                1. 只基于提供的文档内容回答，不要编造信息
                2. 如果文档中没有相关信息，如实告知用户
                3. 回答时引用文档中的相关内容
                4. 用中文回答，简洁明了
                """;
    }

    /**
     * 普通对话的系统提示。
     */
    public String chatPrompt() {
        return """
                你是一个友好的智能助手。
                用中文回答用户的问题，回答简洁明了。
                如果用户需要计算、查询信息等，可以建议使用 Agent 模式调用工具。
                """;
    }
}
