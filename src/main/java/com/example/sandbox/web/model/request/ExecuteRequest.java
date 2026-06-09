package com.example.sandbox.web.model.request;

/**
 * 命令执行请求
 *
 * @author example
 * @date 2026/05/14
 */
public class ExecuteRequest {

    /**
     * 要执行的命令
     */
    private String command;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }
}
