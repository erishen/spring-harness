package com.example.springharness.pse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SoulService：加载 PSE 各 Agent 的角色定义（Soul）。
 *
 * <p>角色定义来自 resolve-skills 仓库的 souls/ 目录（已作为 git submodule 引入），
 * 每个角色目录下有一个 SOUL.md，描述该角色的定位、职责、约束与行为准则。
 *
 * <p>目录结构：
 * <pre>
 * resolve-skills/
 *   souls/
 *     planner/SOUL.md
 *     specialist/SOUL.md
 *     evaluator/SOUL.md
 * </pre>
 *
 * <p>配置：HARNESS_SOULS_DIR 指定 souls 目录路径（相对路径基于项目根目录解析）。
 * 未配置时，从 HARNESS_SKILLS_DIR（resolve-skills/skills）推导上级目录下的 souls。
 */
@Service
public class SoulService {

    private static final Logger log = LoggerFactory.getLogger(SoulService.class);

    @Value("${HARNESS_SOULS_DIR:}")
    private String soulsDirPath;

    @Value("${HARNESS_SKILLS_DIR:}")
    private String skillsDirPath;

    /** 角色名 → SOUL.md 正文缓存 */
    private final Map<String, String> soulCache = new ConcurrentHashMap<>();

    /**
     * 获取指定角色的 Soul 定义（SOUL.md 正文，不含 frontmatter）。
     *
     * @param role 角色名（planner / specialist / evaluator）
     * @return Soul 定义文本；目录未配置或文件不存在时返回空字符串
     */
    public String getSoul(String role) {
        if (role == null || role.isBlank()) return "";
        return soulCache.computeIfAbsent(role.trim().toLowerCase(), this::loadSoul);
    }

    /** 读取并解析 SOUL.md 文件 */
    private String loadSoul(String role) {
        try {
            Path soulsDir = resolveSoulsDir();
            if (soulsDir == null) {
                log.info("未配置 HARNESS_SOULS_DIR / HARNESS_SKILLS_DIR，Soul 功能未启用");
                return "";
            }
            Path soulFile = soulsDir.resolve(role).resolve("SOUL.md");
            if (!Files.exists(soulFile) || !Files.isRegularFile(soulFile)) {
                log.warn("Soul 文件不存在: {}", soulFile);
                return "";
            }
            String content = Files.readString(soulFile, StandardCharsets.UTF_8);
            String body = stripFrontMatter(content);
            if (body.isBlank()) {
                log.warn("Soul 文件内容为空: {}", soulFile);
                return "";
            }
            log.info("加载 Soul: {} ({} 字符)", role, body.length());
            return body;
        } catch (Exception e) {
            log.error("加载 Soul 失败 [{}]: {}", role, e.getMessage());
            return "";
        }
    }

    /** 解析 souls 目录路径 */
    private Path resolveSoulsDir() {
        // 优先使用 HARNESS_SOULS_DIR
        if (soulsDirPath != null && !soulsDirPath.isBlank()) {
            Path p = Paths.get(soulsDirPath);
            if (Files.isDirectory(p)) return p;
            log.warn("HARNESS_SOULS_DIR 不是有效目录: {}", soulsDirPath);
        }
        // 从 HARNESS_SKILLS_DIR 推导：skills/ 的上级目录下的 souls/
        if (skillsDirPath != null && !skillsDirPath.isBlank()) {
            Path skillsDir = Paths.get(skillsDirPath);
            Path soulsDir = skillsDir.getParent() != null ? skillsDir.getParent().resolve("souls") : null;
            if (soulsDir != null && Files.isDirectory(soulsDir)) {
                return soulsDir;
            }
        }
        return null;
    }

    /** 去除 SOUL.md 开头的 YAML frontmatter（--- 包裹的元信息） */
    private String stripFrontMatter(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("\n---", 3);
            if (end > 0) {
                return trimmed.substring(end + 4).trim();
            }
        }
        return trimmed;
    }
}
