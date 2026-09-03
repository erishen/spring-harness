package com.example.springharness.pse;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 技能注册中心：管理所有已注册的 Skill，提供查询和选择能力。
 *
 * 当前状态：预留接口，暂无具体 Skill 实现。
 *
 * 未来使用方式：
 * 1. 创建技能实现类（如 StockAnalysisSkill、CodeReviewSkill），实现 Skill 接口
 * 2. 注册为 Spring Bean，自动被 SkillRegistry 收集
 * 3. Planner 在分解任务时，通过 SkillRegistry 获取可用技能列表
 * 4. Specialist 执行任务时，可以调用 Skill 完成复杂操作
 *
 * 技能示例（未来）：
 * - stock_analysis: 股票分析（查询股价 + 计算指标 + 生成分析报告）
 * - code_review: 代码审查（读取代码 + 多轮分析 + 生成审查报告）
 * - doc_generation: 文档生成（检索资料 + 总结 + 格式化输出）
 * - web_research: 网络调研（多轮搜索 + 信息整合 + 生成调研报告）
 */
@Service
public class SkillRegistry {

    private final List<Skill> skills;

    public SkillRegistry(List<Skill> skills) {
        this.skills = skills;
    }

    /**
     * 获取所有已启用的技能。
     */
    public List<Skill> getEnabledSkills() {
        return skills.stream()
                .filter(Skill::isEnabled)
                .toList();
    }

    /**
     * 根据 ID 获取技能。
     */
    public Optional<Skill> getSkill(String id) {
        return skills.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst();
    }

    /**
     * 生成技能描述列表（用于 Planner prompt 注入）。
     */
    public String generateSkillList() {
        if (skills.isEmpty()) {
            return "(暂无可用技能)";
        }
        return getEnabledSkills().stream()
                .map(s -> "- " + s.getName() + " (" + s.getId() + "): " + s.getDescription())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 获取已注册技能数量。
     */
    public int getSkillCount() {
        return (int) skills.stream().filter(Skill::isEnabled).count();
    }

    /**
     * 执行指定技能。
     */
    public SkillResult executeSkill(String skillId, String input, String model) {
        return getSkill(skillId)
                .map(skill -> skill.execute(input, model))
                .orElse(SkillResult.failure("技能不存在: " + skillId));
    }
}
