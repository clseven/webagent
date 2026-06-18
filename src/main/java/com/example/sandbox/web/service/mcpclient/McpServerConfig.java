package com.example.sandbox.web.service.mcpclient;

import java.util.List;
import java.util.Map;

/**
 * 单个 MCP Server 的配置。
 *
 * <p>type 决定走哪种 transport：</p>
 * <ul>
 *   <li>{@code stdio} — 启动子进程，通过 stdin/stdout 通信，适合本地工具（filesystem/git 等）</li>
 *   <li>{@code streamable-http} — 通过 HTTP POST + 可选 SSE 流响应通信，适合远程托管的 MCP Server</li>
 * </ul>
 *
 * <p>没有用 sealed class，是因为 Spring Boot 配置绑定无法直接处理 sealed 多态；
 * 这里把所有可选字段都放在一个类上，由 {@code type} 来选择性使用。</p>
 */
public class McpServerConfig {

    /** 服务器逻辑标识，唯一，决定生成的工具名前缀。 */
    private String id;

    /** 传输方式：stdio | streamable-http。 */
    private String type;

    // ===== stdio =====

    /** stdio 启动命令，例如 npx、python、node。 */
    private String command;

    /** stdio 命令参数。 */
    private List<String> args = List.of();

    /** stdio 子进程附加环境变量，会与系统继承环境合并。 */
    private Map<String, String> env = Map.of();

    // ===== streamable-http =====

    /** Streamable HTTP base URI，例如 https://example.com/mcp。 */
    private String url;

    /** Streamable HTTP 自定义请求头，例如 Authorization。 */
    private Map<String, String> headers = Map.of();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args != null ? args : List.of();
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env != null ? env : Map.of();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers != null ? headers : Map.of();
    }
}
