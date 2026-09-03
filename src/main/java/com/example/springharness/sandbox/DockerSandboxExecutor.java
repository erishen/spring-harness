package com.example.springharness.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱执行器。
 *
 * 安全特性：
 * - 每次执行启动独立 Docker 容器，执行完自动销毁（--rm）
 * - 禁用网络（--network none）
 * - 只读根文件系统（--read-only），仅 /tmp 可写
 * - 限制内存（默认 512MB）、CPU（默认 1 核）、进程数（100）
 * - 超时自动 kill（默认 30 秒）
 * - 输出大小限制（默认 100KB）
 *
 * 支持语言：python、javascript、shell
 */
@Component
public class DockerSandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxExecutor.class);

    @Value("${sandbox.docker.enabled:true}")
    private boolean dockerEnabled;

    @Value("${sandbox.timeout-seconds:30}")
    private int defaultTimeoutSeconds;

    @Value("${sandbox.memory-mb:512}")
    private int memoryMb;

    @Value("${sandbox.cpus:1}")
    private double cpus;

    @Value("${sandbox.max-output-kb:100}")
    private int maxOutputKb;

    /** 语言 -> Docker 镜像映射 */
    private static final java.util.Map<String, String> LANGUAGE_IMAGES = java.util.Map.of(
            "python", "python:3.11-slim",
            "javascript", "node:20-slim",
            "shell", "alpine:3.19"
    );

    /** 语言 -> 执行命令映射 */
    private static final java.util.Map<String, String> LANGUAGE_COMMANDS = java.util.Map.of(
            "python", "python3 /tmp/code.py",
            "javascript", "node /tmp/code.js",
            "shell", "sh /tmp/code.sh"
    );

    /**
     * 执行代码，返回执行结果。
     *
     * @param code     要执行的代码
     * @param language 语言：python / javascript / shell
     * @param timeout  超时时间（秒），为 null 时用默认值
     * @return 执行结果（stdout、stderr、退出码、执行时间、是否超时）
     */
    public SandboxResult execute(String code, String language, Integer timeout) {
        long startTime = System.currentTimeMillis();

        // 检查 Docker 是否启用
        if (!dockerEnabled) {
            return SandboxResult.error("Docker 沙箱未启用（sandbox.docker.enabled=false）",
                    System.currentTimeMillis() - startTime);
        }

        // 校验语言
        String lang = language == null ? "python" : language.toLowerCase().trim();
        if (!LANGUAGE_IMAGES.containsKey(lang)) {
            return SandboxResult.error("不支持的语言：" + language + "，支持：" + LANGUAGE_IMAGES.keySet(),
                    System.currentTimeMillis() - startTime);
        }

        // 校验代码
        if (code == null || code.isBlank()) {
            return SandboxResult.error("代码不能为空", System.currentTimeMillis() - startTime);
        }

        int timeoutSeconds = timeout != null && timeout > 0 ? timeout : defaultTimeoutSeconds;

        // 创建临时代码文件
        File tempDir = null;
        File codeFile = null;
        try {
            tempDir = Files.createTempDirectory("sandbox-").toFile();
            String ext = switch (lang) {
                case "python" -> "py";
                case "javascript" -> "js";
                case "shell" -> "sh";
                default -> "txt";
            };
            codeFile = new File(tempDir, "code." + ext);
            Files.writeString(codeFile.toPath(), code, StandardCharsets.UTF_8);

            // 构建 docker run 命令
            List<String> command = buildDockerCommand(lang, codeFile, timeoutSeconds);
            log.info("执行 Docker 沙箱: language={}, timeout={}s, command={}", lang, timeoutSeconds,
                    String.join(" ", command.subList(0, Math.min(8, command.size()))) + "...");

            // 启动进程
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 读取 stdout 和 stderr（带大小限制）
            String stdout = readStreamWithLimit(process.getInputStream(), maxOutputKb);
            String stderr = readStreamWithLimit(process.getErrorStream(), maxOutputKb);

            // 等待进程结束（带超时）
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                long duration = System.currentTimeMillis() - startTime;
                log.warn("Docker 沙箱执行超时: language={}, duration={}ms", lang, duration);
                return new SandboxResult(stdout, stderr + "\n[执行超时，已强制终止]", -1,
                        duration, true, false);
            }

            int exitCode = process.exitValue();
            long duration = System.currentTimeMillis() - startTime;
            log.info("Docker 沙箱执行完成: language={}, exitCode={}, duration={}ms", lang, exitCode, duration);

            return new SandboxResult(stdout, stderr, exitCode, duration, false, false);

        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Docker 沙箱执行失败: {}", e.getMessage(), e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("No such file") || msg != null && msg.contains("docker")) {
                msg = "Docker 不可用，请确保 Docker daemon 已启动。错误：" + msg;
            }
            return SandboxResult.error(msg, duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long duration = System.currentTimeMillis() - startTime;
            return SandboxResult.error("执行被中断", duration);
        } finally {
            // 清理临时文件
            if (codeFile != null) codeFile.delete();
            if (tempDir != null) tempDir.delete();
        }
    }

    /**
     * 构建 docker run 命令。
     */
    private List<String> buildDockerCommand(String language, File codeFile, int timeoutSeconds) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");                          // 执行完自动删除
        cmd.add("--network"); cmd.add("none");   // 禁用网络
        cmd.add("--read-only");                   // 只读根文件系统
        cmd.add("--memory"); cmd.add(memoryMb + "m");  // 内存限制
        cmd.add("--cpus"); cmd.add(String.valueOf(cpus)); // CPU 限制
        cmd.add("--pids-limit"); cmd.add("100"); // 进程数限制
        cmd.add("--ulimit"); cmd.add("nofile=64:64"); // 文件描述符限制
        cmd.add("-v"); cmd.add(codeFile.getAbsolutePath() + ":/tmp/code." +
                switch (language) {
                    case "python" -> "py";
                    case "javascript" -> "js";
                    case "shell" -> "sh";
                    default -> "txt";
                } + ":ro");  // 挂载代码文件（只读）
        cmd.add("--tmpfs"); cmd.add("/tmp:rw,size=64m"); // /tmp 可写（内存文件系统）
        cmd.add("--stop-timeout"); cmd.add(String.valueOf(Math.min(timeoutSeconds, 10))); // 停止超时
        cmd.add(LANGUAGE_IMAGES.get(language));  // 镜像
        // 执行命令
        String[] execCmd = LANGUAGE_COMMANDS.get(language).split(" ");
        cmd.addAll(List.of(execCmd));
        return cmd;
    }

    /**
     * 读取输入流，带大小限制。
     */
    private String readStreamWithLimit(InputStream is, int maxKb) {
        try {
            int maxBytes = maxKb * 1024;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int totalRead = 0;
            int read;
            while ((read = is.read(buffer)) != -1) {
                if (totalRead + read > maxBytes) {
                    baos.write(buffer, 0, maxBytes - totalRead);
                    baos.write("\n...[输出超过限制，已截断]".getBytes(StandardCharsets.UTF_8));
                    break;
                }
                baos.write(buffer, 0, read);
                totalRead += read;
            }
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[读取输出失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 检查 Docker 是否可用。
     */
    public boolean isDockerAvailable() {
        if (!dockerEnabled) return false;
        try {
            Process process = new ProcessBuilder("docker", "info").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取支持的语言列表。
     */
    public java.util.Set<String> getSupportedLanguages() {
        return LANGUAGE_IMAGES.keySet();
    }
}
