package com.example.springharness.sandbox;

/**
 * Docker 沙箱执行结果。
 */
public record SandboxResult(
        String stdout,
        String stderr,
        int exitCode,
        long durationMs,
        boolean timedOut,
        boolean error
) {
    /** 创建错误结果 */
    public static SandboxResult error(String message, long durationMs) {
        return new SandboxResult("", message, -1, durationMs, false, true);
    }

    /** 是否成功（退出码为 0 且未超时且无错误） */
    public boolean isSuccess() {
        return !error && !timedOut && exitCode == 0;
    }

    /** 获取摘要信息（用于工具返回） */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        if (error) {
            sb.append("【执行错误】\n").append(stderr);
            return sb.toString();
        }
        if (timedOut) {
            sb.append("【执行超时】已强制终止\n");
        }
        if (stdout != null && !stdout.isBlank()) {
            sb.append("【标准输出】\n").append(stdout);
        }
        if (stderr != null && !stderr.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("【标准错误】\n").append(stderr);
        }
        sb.append("\n【退出码】").append(exitCode)
          .append("  【耗时】").append(durationMs).append("ms");
        return sb.toString();
    }
}
