package com.example.springharness.tool;

import com.example.springharness.sandbox.DockerSandboxExecutor;
import com.example.springharness.sandbox.SandboxResult;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.function.Function;

/**
 * 代码执行工具：在 Docker 沙箱中执行 Python / JavaScript / Shell 代码。
 *
 * 安全特性：
 * - 独立 Docker 容器，执行完自动销毁
 * - 禁用网络、只读文件系统
 * - 限制内存（512MB）、CPU（1核）、进程数
 * - 超时自动终止（默认 30 秒）
 *
 * 使用场景：
 * - 复杂数学计算、数据分析
 * - 算法验证、代码运行
 * - 文件处理、数据转换
 * - 任何需要实际运行代码的任务
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("在 Docker 沙箱中执行代码，支持 Python、JavaScript、Shell。返回标准输出、标准错误、退出码和执行时间。适用于复杂计算、数据分析、算法验证、文件处理等需要实际运行代码的场景。")
public class CodeExecutionTool implements Function<CodeExecutionTool.Request, CodeExecutionTool.Response> {

    private final DockerSandboxExecutor sandboxExecutor;

    public CodeExecutionTool(DockerSandboxExecutor sandboxExecutor) {
        this.sandboxExecutor = sandboxExecutor;
    }

    public record Request(
            @JsonProperty(required = true, value = "code")
            @JsonPropertyDescription("要执行的代码，完整可运行的代码片段")
            String code,

            @JsonProperty(required = false, value = "language")
            @JsonPropertyDescription("编程语言：python（默认）、javascript、shell")
            String language,

            @JsonProperty(required = false, value = "timeout")
            @JsonPropertyDescription("超时时间（秒），默认 30 秒，最大 120 秒")
            Integer timeout
    ) {}

    public record Response(
            String stdout,
            String stderr,
            int exitCode,
            long durationMs,
            boolean timedOut,
            boolean error,
            String summary
    ) {}

    @Override
    public Response apply(Request request) {
        // 限制超时时间最大 120 秒
        Integer timeout = request.timeout();
        if (timeout != null && timeout > 120) {
            timeout = 120;
        }

        SandboxResult result = sandboxExecutor.execute(
                request.code(),
                request.language(),
                timeout
        );

        return new Response(
                result.stdout(),
                result.stderr(),
                result.exitCode(),
                result.durationMs(),
                result.timedOut(),
                result.error(),
                result.toSummary()
        );
    }
}
