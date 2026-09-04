package com.example.springharness.config;

import com.example.springharness.util.ErrorSanitizer;
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
        // 使用统一脱敏工具过滤敏感信息
        String message = ErrorSanitizer.sanitize(e);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /** 处理所有其他异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        String message = ErrorSanitizer.sanitize(e);
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
}
