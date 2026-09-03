package com.example.springharness.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器，统一错误返回格式。
 *
 * <p>返回格式：
 * <pre>
 * {
 *   "code": 500,
 *   "message": "错误描述",
 *   "data": null
 * }
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 处理文件上传大小超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return buildError(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过限制（最大 10MB）");
    }

    /** 处理非法参数异常 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return buildError(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 处理运行时异常 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        // 过滤可能包含敏感信息的异常消息
        String message = safeErrorMessage(e.getMessage());
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /** 处理所有其他异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        String message = safeErrorMessage(e.getMessage());
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /** 构建统一错误响应 */
    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", status.value());
        body.put("message", message != null ? message : "未知错误");
        body.put("data", null);
        return ResponseEntity.status(status).body(body);
    }

    /** 过滤异常消息中的敏感信息（API Key、URL 等） */
    private String safeErrorMessage(String message) {
        if (message == null) return "未知错误";
        // 过滤 API Key / token
        message = message.replaceAll("(?i)(token|api[_-]?key|secret|password)=[^&\\s\"]+", "$1=***");
        // 过滤完整 URL（可能包含 token 参数）
        message = message.replaceAll("https?://[^\\s\"]+", "[URL已隐藏]");
        // 限制长度
        if (message.length() > 300) {
            message = message.substring(0, 300) + "...";
        }
        return message;
    }
}
