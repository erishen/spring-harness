package com.example.springharness.pse;

import java.util.List;

/**
 * Skill（技能）接口：高层次的能力封装，可能包含多个工具调用、工作流和提示词模板。
 *
 * 与 Tool（工具）的区别：
 * - Tool：原子操作，如 calculator、get_datetime、query_stock
 * - Skill：复合能力，如"股票分析"（调用 query_stock + calculator + 生成分析报告）、
 *   "代码审查"（调用多个工具 + 多轮分析）、"文档生成"（检索 + 总结 + 格式化）
 *
 * 参考 agentic-souls 项目的 skills 目录：api-development、database-ops、frontend-development 等。
 *
 * 当前状态：预留接口，暂无具体实现。
 *
 * 未来实现计划：
 * 1. 为每个技能创建实现类，实现 execute() 方法
 * 2. 技能内部可以调用多个 Tool、执行多轮 LLM 调用、生成结构化输出
 * 3. Planner 在分解任务时，可以选择合适的 Skill 委托给 Specialist
 * 4. Specialist 执行任务时，可以调用 Skill 完成复杂操作
 */
public interface Skill {

    /**
     * 技能唯一标识。
     */
    String getId();

    /**
     * 技能名称。
     */
    String getName();

    /**
     * 技能描述（用于 Planner 选择和 LLM 理解）。
     */
    String getDescription();

    /**
     * 技能所需的工具列表（工具名称）。
     */
    List<String> getRequiredTools();

    /**
     * 执行技能。
     *
     * @param input 技能输入（JSON 字符串或自然语言描述）
     * @param model 使用的模型
     * @return 技能执行结果
     */
    SkillResult execute(String input, String model);

    /**
     * 技能是否启用。
     */
    default boolean isEnabled() {
        return true;
    }
}
