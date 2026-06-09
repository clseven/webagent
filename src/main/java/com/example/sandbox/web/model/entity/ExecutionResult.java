package com.example.sandbox.web.model.entity;

import java.time.Duration;

/**
 * 执行结果
 *
 * @author example
 * @date 2026/05/14
 */
public class ExecutionResult {

    /**
     * 执行状态
     */
    private final ExecutionStatus status;

    /**
     * 执行结果内容（成功时为输出，失败时为错误信息）
     */
    private final String body;

    /**
     * 执行耗时
     */
    private final Duration duration;

    private ExecutionResult(ExecutionStatus status, String body, Duration duration) {
        this.status = status;
        this.body = body;
        this.duration = duration;
    }

    /**
     * 创建成功结果
     */
    public static ExecutionResult success(String output, Duration duration) {
        return new ExecutionResult(ExecutionStatus.SUCCESS, output, duration);
    }

    /**
     * 创建错误结果
     */
    public static ExecutionResult error(String errorMessage, Duration duration) {
        return new ExecutionResult(ExecutionStatus.ERROR, errorMessage, duration);
    }

    /**
     * 创建超时结果
     */
    public static ExecutionResult timeout(Duration duration) {
        return new ExecutionResult(ExecutionStatus.TIMEOUT, "Execution timed out", duration);
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }

    public Duration getDuration() {
        return duration;
    }

    public boolean isSuccess() {
        return status == ExecutionStatus.SUCCESS;
    }

    @Override
    public String toString() {
        return "ExecutionResult{" +
                "status=" + status +
                ", body='" + body + '\'' +
                ", duration=" + duration +
                '}';
    }
}
