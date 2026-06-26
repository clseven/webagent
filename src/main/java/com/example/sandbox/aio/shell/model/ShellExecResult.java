package com.example.sandbox.aio.shell.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Shell 执行接口的完整响应。
 *
 * <p>保留响应信封信息，并提供常用输出和退出码快捷访问。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShellExecResult {

    /** AIO 是否成功处理请求。 */
    private boolean success;

    /** AIO 返回的结果说明。 */
    private String message;

    /** Shell 命令执行数据。 */
    private ShellData data;

    /** @return AIO 是否成功处理请求 */
    public boolean isSuccess() {
        return success;
    }

    /** @param success AIO 是否成功处理请求 */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /** @return AIO 返回的结果说明 */
    public String getMessage() {
        return message;
    }

    /** @param message AIO 返回的结果说明 */
    public void setMessage(String message) {
        this.message = message;
    }

    /** @return Shell 命令执行数据 */
    public ShellData getData() {
        return data;
    }

    /** @param data Shell 命令执行数据 */
    public void setData(ShellData data) {
        this.data = data;
    }

    /** @return 命令输出；无执行数据时返回空字符串 */
    public String getOutput() {
        return data != null && data.getOutput() != null ? data.getOutput() : "";
    }

    /** @return 退出码；服务端尚未返回退出码时返回 -1 */
    public int getExitCode() {
        return data != null && data.getExitCode() != null ? data.getExitCode() : -1;
    }

    /** @return 命令执行状态；无执行数据时返回空字符串 */
    public String getStatus() {
        return data != null && data.getStatus() != null ? data.getStatus() : "";
    }

    /** @return Shell 会话 ID；无执行数据时返回空字符串 */
    public String getSessionId() {
        return data != null && data.getSessionId() != null ? data.getSessionId() : "";
    }

    /**
     * Shell 命令执行的 data 字段。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ShellData {

        /** AIO Shell 会话 ID。 */
        @JsonProperty("session_id")
        private String sessionId;

        /** 本次执行的命令。 */
        private String command;

        /** 命令执行状态。 */
        private String status;

        /** 命令标准输出。 */
        private String output;

        /** 命令退出码。 */
        @JsonProperty("exit_code")
        private Integer exitCode;

        /** @return Shell 会话 ID */
        public String getSessionId() {
            return sessionId;
        }

        /** @param sessionId Shell 会话 ID */
        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        /** @return 本次执行的命令 */
        public String getCommand() {
            return command;
        }

        /** @param command 本次执行的命令 */
        public void setCommand(String command) {
            this.command = command;
        }

        /** @return 命令执行状态 */
        public String getStatus() {
            return status;
        }

        /** @param status 命令执行状态 */
        public void setStatus(String status) {
            this.status = status;
        }

        /** @return 命令标准输出 */
        public String getOutput() {
            return output;
        }

        /** @param output 命令标准输出 */
        public void setOutput(String output) {
            this.output = output;
        }

        /** @return 命令退出码 */
        public Integer getExitCode() {
            return exitCode;
        }

        /** @param exitCode 命令退出码 */
        public void setExitCode(Integer exitCode) {
            this.exitCode = exitCode;
        }
    }
}
