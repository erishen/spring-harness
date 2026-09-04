package com.example.springharness.util;

import java.util.regex.Pattern;

/**
 * 错误信息脱敏工具类。
 *
 * <p>对异常消息进行敏感信息过滤，防止内部路径、API Key、URL、堆栈等泄露到前端。
 * 用于 SSE 流式输出（PSE/ReAct）和全局异常处理器。
 *
 * <p>脱敏规则：
 * <ul>
 *   <li>API Key / token / secret / password → ***</li>
 *   <li>完整 URL → [URL已隐藏]</li>
 *   <li>本地文件路径（/Users/...、/var/...、C:\Users\...）→ [文件路径已隐藏]</li>
 *   <li>IP 地址 → [IP已隐藏]</li>
 *   <li>Java 堆栈行（at com.example...）→ 移除</li>
 *   <li>长度限制 300 字符</li>
 * </ul>
 */
public final class ErrorSanitizer {

    private ErrorSanitizer() {}

    /** API Key / token / secret / password 正则 */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(token|api[_-]?key|secret|password|authorization|bearer)\\s*[=:]\\s*[^&\\s\"',;)]+");

    /** 完整 URL 正则 */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+");

    /** Unix 文件路径正则（/Users/、/var/、/tmp/、/home/ 等） */
    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile(
            "/(?:Users|var|tmp|home|opt|usr|etc|root|private|mnt|media)/[^\\s\"'<>|:*?]+");

    /** Windows 文件路径正则（C:\Users\、D:\...） */
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile(
            "[A-Za-z]:\\\\[^\\s\"'<>|:*?]+");

    /** Java 类路径正则（com.example.xxx） */
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile(
            "at\\s+(?:com\\.|org\\.|net\\.|io\\.|java\\.|javax\\.|sun\\.|jdk\\.)[^\\s]+");

    /** IP 地址正则（IPv4） */
    private static final Pattern IP_PATTERN = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /** 最大错误消息长度 */
    private static final int MAX_LENGTH = 300;

    /**
     * 对错误消息进行脱敏处理。
     *
     * @param message 原始错误消息
     * @return 脱敏后的错误消息
     */
    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }

        String result = message;

        // 1. 过滤 API Key / token / secret / password
        result = SECRET_PATTERN.matcher(result).replaceAll("$1=***");

        // 2. 过滤完整 URL（在路径过滤之前，避免 URL 中的路径被误匹配）
        result = URL_PATTERN.matcher(result).replaceAll("[URL已隐藏]");

        // 3. 过滤 Unix 文件路径
        result = UNIX_PATH_PATTERN.matcher(result).replaceAll("[文件路径已隐藏]");

        // 4. 过滤 Windows 文件路径
        result = WINDOWS_PATH_PATTERN.matcher(result).replaceAll("[文件路径已隐藏]");

        // 5. 过滤 Java 堆栈行
        result = JAVA_CLASS_PATTERN.matcher(result).replaceAll("");

        // 6. 过滤 IP 地址
        result = IP_PATTERN.matcher(result).replaceAll("[IP已隐藏]");

        // 7. 清理多余空白和换行
        result = result.replaceAll("\\s+", " ").trim();

        // 8. 限制长度
        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH) + "...";
        }

        // 9. 脱敏后如果为空，返回通用错误
        if (result.isBlank()) {
            return "服务内部错误，请查看后端日志";
        }

        return result;
    }

    /**
     * 对异常对象进行脱敏，返回脱敏后的错误消息。
     *
     * @param e 异常对象
     * @return 脱敏后的错误消息
     */
    public static String sanitize(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        return sanitize(e.getMessage());
    }
}
