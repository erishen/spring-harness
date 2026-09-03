package com.example.springharness.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能服务：加载和管理 Agent Skills（提示词包）。
 * 对齐 Agent Skills 开放标准（Claude Code / OpenAI Codex 采用）。
 *
 * 每个技能目录包含 SKILL.md 文件，格式：
 * ---
 * name: skill-name
 * description: 技能描述
 * ---
 * 技能详细说明...
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    @Value("${HARNESS_SKILLS_DIR:}")
    private String skillsDirPath;

    /** 技能元信息缓存：name -> SkillMeta */
    private final Map<String, SkillMeta> skillMetas = new ConcurrentHashMap<>();

    /** 技能完整内容缓存：name -> content */
    private final Map<String, String> skillContents = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (skillsDirPath == null || skillsDirPath.isBlank()) {
            log.info("未配置 HARNESS_SKILLS_DIR，技能功能未启用");
            return;
        }
        loadSkills();
    }

    /**
     * 扫描技能目录，加载所有技能的元信息。
     */
    public void loadSkills() {
        Path skillsDir = Paths.get(skillsDirPath);
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            log.warn("技能目录不存在: {}", skillsDirPath);
            return;
        }

        skillMetas.clear();
        skillContents.clear();

        try (var stream = Files.list(skillsDir)) {
            stream.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillFile = skillDir.resolve("SKILL.md");
                if (Files.exists(skillFile)) {
                    try {
                        String content = Files.readString(skillFile);
                        SkillMeta meta = parseFrontMatter(content, skillDir.getFileName().toString());
                        if (meta != null) {
                            skillMetas.put(meta.name(), meta);
                            skillContents.put(meta.name(), content);
                            log.info("加载技能: {} - {}", meta.name(), meta.description());
                        }
                    } catch (IOException e) {
                        log.error("读取技能文件失败: {}", skillFile, e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("扫描技能目录失败: {}", skillsDir, e);
        }

        log.info("共加载 {} 个技能", skillMetas.size());
    }

    /**
     * 解析 SKILL.md 的 YAML front matter。
     */
    private SkillMeta parseFrontMatter(String content, String dirName) {
        if (!content.startsWith("---")) {
            return new SkillMeta(dirName, "（无描述）");
        }
        int end = content.indexOf("---", 3);
        if (end < 0) {
            return new SkillMeta(dirName, "（无描述）");
        }
        String frontMatter = content.substring(3, end).trim();
        String name = dirName;
        String description = "（无描述）";

        for (String line : frontMatter.split("\n")) {
            line = line.trim();
            if (line.startsWith("name:")) {
                name = line.substring(5).trim();
            } else if (line.startsWith("description:")) {
                description = line.substring(12).trim();
            }
        }
        return new SkillMeta(name, description);
    }

    /**
     * 获取所有技能的元信息列表。
     */
    public List<SkillMeta> getAllSkills() {
        return new ArrayList<>(skillMetas.values());
    }

    /**
     * 获取技能数量。
     */
    public int getSkillCount() {
        return skillMetas.size();
    }

    /**
     * 技能是否启用。
     */
    public boolean isEnabled() {
        return !skillMetas.isEmpty();
    }

    /**
     * 加载指定技能的完整内容。
     * @param skillName 技能名称
     * @return 技能完整内容，不存在返回 null
     */
    public String loadSkill(String skillName) {
        return skillContents.get(skillName);
    }

    /**
     * 构建技能索引文本（用于注入系统提示）。
     */
    public String buildSkillIndex() {
        if (skillMetas.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("可用技能（通过 skill_run 工具加载完整指令）：\n");
        for (SkillMeta meta : skillMetas.values()) {
            sb.append("- ").append(meta.name()).append(": ").append(meta.description()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 技能元信息。
     */
    public record SkillMeta(String name, String description) {}
}
