package com.example.springharness.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;

/**
 * 文件读取工具：AI 调用此工具读取本地文本文件内容。
 *
 * <p>安全限制：
 * <ul>
 *   <li>只允许读取项目根目录下的文件（可通过 FILE_TOOL_ALLOWED_DIRS 配置）</li>
 *   <li>单文件最大 100KB，超过则截断并提示</li>
 *   <li>只允许文本文件（.java, .py, .js, .ts, .vue, .md, .txt, .yml, .yaml, .json, .xml, .properties, .gradle, .pom, .sql, .sh, .bat, .css, .html 等）</li>
 *   <li>禁止读取 .env、密钥文件、.git 目录</li>
 *   <li>防止路径穿越（../ 被拒绝）</li>
 * </ul>
 *
 * <p>用于 code-review、code-review 等需要读取文件的技能。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("读取本地文本文件内容。当需要审查代码、查看配置、分析文件内容时使用。参数为文件路径（相对于项目根目录）。注意：只能读取项目目录下的文本文件，不能读取 .env 等敏感文件。")
public class FileReadTool implements Function<FileReadTool.Request, FileReadTool.Response> {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    /** 单文件最大读取大小（100KB） */
    private static final int MAX_FILE_SIZE = 100 * 1024;

    /** 允许的文件扩展名 */
    private static final java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of(
            ".java", ".py", ".js", ".ts", ".vue", ".jsx", ".tsx",
            ".md", ".txt", ".rst",
            ".yml", ".yaml", ".json", ".xml", ".properties", ".toml",
            ".gradle", ".pom", ".sql", ".sh", ".bat", ".ps1",
            ".css", ".scss", ".less", ".html", ".htm",
            ".c", ".cpp", ".h", ".hpp", ".rs", ".go", ".rb", ".php",
            ".dockerfile", ".dockerignore", ".gitignore",
            ".env.example", ".env.template"
    );

    /** 禁止读取的文件名（敏感文件） */
    private static final java.util.Set<String> FORBIDDEN_FILES = java.util.Set.of(
            ".env", ".env.local", ".env.production", ".env.development",
            "id_rsa", "id_ed25519", "id_dsa",
            "keystore.jks", "keystore.p12", "truststore.jks",
            "credentials", "secret", "secrets"
    );

    /** 禁止读取的目录 */
    private static final java.util.Set<String> FORBIDDEN_DIRS = java.util.Set.of(
            ".git", ".svn", "node_modules", "target", "build", "dist",
            ".idea", ".vscode", ".settings"
    );

    /** 项目根目录（允许读取的基础目录） */
    private final Path baseDir;

    public FileReadTool() {
        this.baseDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public FileReadTool(String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    public record Request(
            @JsonProperty(required = true, value = "file_path")
            @JsonPropertyDescription("文件路径，相对于项目根目录，如 src/main/java/com/example/MyClass.java")
            String filePath
    ) {}

    public record Response(
            String filePath,
            String content,
            int lineCount,
            long fileSize,
            boolean truncated,
            String error
    ) {}

    @Override
    public Response apply(Request request) {
        long start = System.currentTimeMillis();
        String filePath = request.filePath();

        if (filePath == null || filePath.isBlank()) {
            Response err = new Response(null, null, 0, 0, false, "文件路径不能为空");
            ToolCallRecorder.record("read_file", request, err.toString(), System.currentTimeMillis() - start);
            return err;
        }

        try {
            // 1. 防止路径穿越
            Path targetPath = baseDir.resolve(filePath).normalize();
            if (!targetPath.startsWith(baseDir)) {
                Response err = new Response(filePath, null, 0, 0, false,
                        "安全限制：不能读取项目目录外的文件（路径穿越被拒绝）");
                ToolCallRecorder.record("read_file", request, err.toString(), System.currentTimeMillis() - start);
                return err;
            }

            // 2. 检查文件是否存在
            if (!Files.exists(targetPath)) {
                Response err = new Response(filePath, null, 0, 0, false,
                        "文件不存在: " + filePath);
                ToolCallRecorder.record("read_file", request, err.toString(), System.currentTimeMillis() - start);
                return err;
            }

            // 3. 检查是否为目录
            if (Files.isDirectory(targetPath)) {
                Response err = new Response(filePath, null, 0, 0, false,
                        "路径是目录，不是文件: " + filePath + "。请指定具体文件路径。");
                ToolCallRecorder.record("read_file", request, err.toString(), System.currentTimeMillis() - start);
                return err;
            }

            // 4. 检查禁止读取的文件名
            String fileName = targetPath.getFileName().toString().toLowerCase();
            if (FORBIDDEN_FILES.contains(fileName)) {
                Response err = new Response(filePath, null, 0, 0, false,
                        "安全限制：禁止读取敏感文件: " + fileName);
                ToolCallRecorder.record("read_file", request, err.toString(), System.currentTimeMillis() - start);
                return err;
            }

            // 5. 检查禁止读取的目录
            for (String forbiddenDir : FORBIDDEN_DIRS) {
                if (targetPath.toString().contains("/" + forbiddenDir + "/") ||
                        targetPath.toString().contains("\\" + forbiddenDir + "\\")) {
                    Response err = new Response(filePath, null, 0, 0, false,
                            "安全限制：禁止读取 " + forbiddenDir + " 目录下的文件");
                    ToolCallRecorder.record("read_file", request, err.toString(), System.currentTimeMillis() - start);
                    return err;
                }
            }

            // 6. 检查文件扩展名
            String ext = getFileExtension(fileName);
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                Response err = new Response(filePath, null, 0, 0, false,
                        "安全限制：不支持的文件类型: " + ext + "。只允许读取文本文件。");
                ToolCallRecorder.record("read_file", request, err.toString(), System.currentTimeMillis() - start);
                return err;
            }

            // 7. 检查文件大小
            long fileSize = Files.size(targetPath);
            boolean truncated = false;
            byte[] bytes;
            if (fileSize > MAX_FILE_SIZE) {
                // 大文件只读取前 MAX_FILE_SIZE 字节
                try (var is = Files.newInputStream(targetPath)) {
                    bytes = is.readNBytes(MAX_FILE_SIZE);
                }
                truncated = true;
                log.info("文件过大，已截断: {} ({} bytes, 只读取前 {} bytes)", filePath, fileSize, MAX_FILE_SIZE);
            } else {
                bytes = Files.readAllBytes(targetPath);
            }

            // 8. 读取内容
            String content = new String(bytes, StandardCharsets.UTF_8);
            int lineCount = content.split("\n", -1).length;

            if (truncated) {
                content += "\n\n... [文件已截断，完整大小 " + fileSize + " bytes，只读取前 " + MAX_FILE_SIZE + " bytes]";
            }

            Response response = new Response(filePath, content, lineCount, fileSize, truncated, null);
            ToolCallRecorder.record("read_file", request,
                    "读取成功: " + filePath + " (" + lineCount + " 行, " + fileSize + " bytes" + (truncated ? ", 已截断" : "") + ")",
                    System.currentTimeMillis() - start);
            return response;

        } catch (IOException e) {
            log.warn("读取文件失败: {}", filePath, e);
            Response err = new Response(filePath, null, 0, 0, false,
                    "读取文件失败: " + e.getMessage());
            ToolCallRecorder.record("read_file", request, err.toString(), System.currentTimeMillis() - start);
            return err;
        }
    }

    /** 获取文件扩展名（小写，含点） */
    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) return "";
        // 处理 .env.example 这类多扩展名文件
        if (fileName.startsWith(".") && dotIndex > 0) {
            return fileName.substring(dotIndex).toLowerCase();
        }
        return fileName.substring(dotIndex).toLowerCase();
    }
}
