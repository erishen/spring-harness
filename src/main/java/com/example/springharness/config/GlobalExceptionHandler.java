package com.example.springharness.config;

import com.example.springharness.util.ErrorSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器，统一错误返回格式。
 *
 * <p>普通请求返回 JSON：
 * <pre>
 * {
 *   "code": 500,
 *   "message": "错误描述",
 *   "data": null
 * }
 * </pre>
 *
 * <p>SSE 请求（text/event-stream）返回纯文本错误信息，避免 HttpMessageNotWritableException。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 处理文件上传大小超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return buildError(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过限制（最大 10MB）");
    }

    /** 处理非法参数异常 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException e) {
        return buildError(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 处理运行时异常 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException e) {
        // 使用统一脱敏工具过滤敏感信息
        String message = ErrorSanitizer.sanitize(e);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /** 处理所有其他异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        String message = ErrorSanitizer.sanitize(e);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /**
     * 构建统一错误响应。
     * 自动检测 SSE 请求（text/event-stream），SSE 请求返回纯文本，
     * 普通请求返回 JSON。
     */
    private ResponseEntity<?> buildError(HttpStatus status, String message) {
        String safeMessage = message != null ? message : "未知错误";

        // 检测是否为 SSE 请求
        if (isSseRequest()) {
            // SSE 请求返回纯文本错误信息，避免 HttpMessageNotWritableException
            // 格式：event: error\ndata: {"error":"..."}\n\n
            String sseError = String.format(
                    "event: error\ndata: {\"code\":%d,\"message\":\"%s\"}\n\n",
                    status.value(),
                    safeMessage.replace("\"", "\\\"").replace("\n", "\\n")
            );
            return ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(sseError);
        }

        // 普通请求返回 JSON
        Map<String, Object> body = new HashMap<>();
        body.put("code", status.value());
        body.put("message", safeMessage);
        body.put("data", null);
        return ResponseEntity.status(status).body(body);
    }

    /** 检测当前请求是否为 SSE 请求（Accept 头包含 text/event-stream） */
    private boolean isSseRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return false;
            HttpServletRequest request = attrs.getRequest();
            String accept = request.getHeader("Accept");
            if (accept != null && accept.contains("text/event-stream")) {
                return true;
            }
            // 也检查请求路径是否为 SSE 端点
            String path = request.getRequestURI();
            if (path != null && (path.contains("/stream") || path.contains("/sse"))) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
