package com.example.sandbox.web.config;

import com.example.sandbox.web.exception.DuplicateFileException;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.model.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * @author example
 * @date 2026/05/14
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorized(UnauthorizedException e) {
        return ApiResponse.error(401, e.getMessage());
    }

    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleSessionNotFound(SessionNotFoundException e) {
        log.warn("Session not found: {}", e.getSessionId());
        return ApiResponse.notFound(e.getMessage());
    }

    @ExceptionHandler(SkillNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleSkillNotFound(SkillNotFoundException e) {
        log.warn("Skill not found: {}", e.getSkillId());
        return ApiResponse.notFound(e.getMessage());
    }

    /**
     * 处理重复文件异常
     *
     * <p>返回 409 Conflict，data 字段中包含已有文档的 ID，
     * 前端可以用此 ID 弹出对话框询问用户（替换 / 保留两份 / 跳过）。</p>
     */
    @ExceptionHandler(DuplicateFileException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleDuplicateFile(DuplicateFileException e) {
        log.info("检测到重复文件: fileName={}, existingDocId={}", e.getFileName(), e.getExistingDocId());
        Map<String, Object> data = new HashMap<>();
        data.put("fileName", e.getFileName());
        data.put("existingDocId", e.getExistingDocId());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(409, e.getMessage(), data));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneric(Exception e) {
        // 日志显示异常类型和消息，便于快速定位问题
        log.error("{}: {}", e.getClass().getSimpleName(), e.getMessage(), e);

        // 返回具体错误信息，便于前端调试和提示用户
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return ApiResponse.error(500, message);
    }
}