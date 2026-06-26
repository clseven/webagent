package com.example.sandbox.aio.shell.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO Shell 执行请求模型。
 *
 * <p>对应 OpenAPI 中的 {@code ShellExecRequest}。除命令本身外，调用方可指定
 * 复用的 Shell 会话、工作目录、异步执行模式和命令等待超时。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShellExecRequest {

    /** Shell 会话 ID；为空时 AIO 会自动创建临时会话。 */
    private String id;

    /** 命令执行目录；为空时使用 AIO 默认工作目录。 */
    @JsonProperty("exec_dir")
    private String execDir;

    /** 要执行的 Shell 命令。 */
    private String command;

    /** 是否异步启动命令；长进程必须使用异步模式避免阻塞 HTTP 请求。 */
    @JsonProperty("async_mode")
    private Boolean asyncMode;

    /** 命令同步等待上限，单位为秒；为空时使用 AIO 默认行为。 */
    private Integer timeout;

    /**
     * 获取目标 Shell 会话 ID。
     *
     * @return Shell 会话 ID；为空表示由 AIO 自动创建
     */
    public String getId() {
        return id;
    }

    /**
     * 设置目标 Shell 会话 ID。
     *
     * @param id Shell 会话 ID；为空表示由 AIO 自动创建
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * 获取命令执行目录。
     *
     * @return Sandbox 内绝对路径；为空表示使用默认目录
     */
    public String getExecDir() {
        return execDir;
    }

    /**
     * 设置命令执行目录。
     *
     * @param execDir Sandbox 内绝对路径；为空表示使用默认目录
     */
    public void setExecDir(String execDir) {
        this.execDir = execDir;
    }

    /**
     * 获取要执行的 Shell 命令。
     *
     * @return Shell 命令文本
     */
    public String getCommand() {
        return command;
    }

    /**
     * 设置要执行的 Shell 命令。
     *
     * @param command Shell 命令文本
     */
    public void setCommand(String command) {
        this.command = command;
    }

    /**
     * 判断是否异步执行。
     *
     * @return true 表示启动后立即返回，false 或 null 表示同步等待
     */
    public Boolean getAsyncMode() {
        return asyncMode;
    }

    /**
     * 设置是否异步执行。
     *
     * @param asyncMode true 表示启动后立即返回
     */
    public void setAsyncMode(Boolean asyncMode) {
        this.asyncMode = asyncMode;
    }

    /**
     * 获取命令同步等待上限。
     *
     * @return 等待秒数；为空表示使用 AIO 默认值
     */
    public Integer getTimeout() {
        return timeout;
    }

    /**
     * 设置命令同步等待上限。
     *
     * @param timeout 等待秒数；为空表示使用 AIO 默认值
     */
    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }
}
