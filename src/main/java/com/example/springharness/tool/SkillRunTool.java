package com.example.springharness.tool;

import com.example.springharness.service.SkillService;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.function.Function;

/**
 * 技能加载工具：AI 调用此工具加载指定技能的完整指令。
 * 对齐 Agent Skills 开放标准的 skill-run 工具。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("加载指定技能的完整指令。当用户的需求匹配某个技能时，先调用此工具加载技能的详细步骤和规则，再按技能要求执行。参数为技能名称，可用技能列表见系统提示。")
public class SkillRunTool implements Function<SkillRunTool.Request, SkillRunTool.Response> {

    private final SkillService skillService;

    public SkillRunTool(SkillService skillService) {
        this.skillService = skillService;
    }

    public record Request(
            @JsonProperty(required = true, value = "skill_name")
            @JsonPropertyDescription("技能名称，如 code-review、weekly-investment-review")
            String skillName
    ) {}

    public record Response(
            String skillName,
            String content,
            String message
    ) {}

    @Override
    public Response apply(Request request) {
        long start = System.currentTimeMillis();
        String skillName = request.skillName();

        if (skillName == null || skillName.isBlank()) {
            Response err = new Response(null, null, "技能名称不能为空");
            ToolCallRecorder.record("skill_run", request, err.toString(), System.currentTimeMillis() - start);
            return err;
        }

        String content = skillService.loadSkill(skillName);
        if (content == null) {
            Response err = new Response(skillName, null,
                    "技能不存在: " + skillName + "。可用技能: " + skillService.getAllSkills().stream()
                            .map(SkillService.SkillMeta::name).toList());
            ToolCallRecorder.record("skill_run", request, err.toString(), System.currentTimeMillis() - start);
            return err;
        }

        Response response = new Response(skillName, content, null);
        ToolCallRecorder.record("skill_run", request,
                "技能 " + skillName + " 加载成功，内容长度: " + content.length(),
                System.currentTimeMillis() - start);
        return response;
    }
}
