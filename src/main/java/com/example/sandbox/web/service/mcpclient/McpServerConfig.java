package com.example.sandbox.web.service.mcpclient;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpServerConfig {

    /** 服务器逻辑标识，唯一，决定生成的工具名前缀。 */
    private String id;

    /** 是否启用该 Server，默认启用。 */
    private boolean enabled = true;

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

    /**
     * 判断该 Server 是否启用。
     *
     * @return true 表示应建立连接并向 Agent 暴露工具
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置该 Server 是否启用。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    /**
     * 创建独立副本，避免配置绑定对象在运行期间被外部修改。
     *
     * @return 当前配置的深度足够的运行时副本
     */
    public McpServerConfig copy() {
        McpServerConfig copy = new McpServerConfig();
        copy.setId(id);
        copy.setEnabled(enabled);
        copy.setType(type);
        copy.setCommand(command);
        copy.setArgs(List.copyOf(args));
        copy.setEnv(Map.copyOf(env));
        copy.setUrl(url);
        copy.setHeaders(Map.copyOf(headers));
        return copy;
    }

    /**
     * 比较两个配置是否会建立相同的 MCP 连接。
     *
     * @param other 待比较配置
     * @return transport 及其连接参数完全相同时返回 true
     */
    public boolean sameConnectionConfig(McpServerConfig other) {
        if (other == null) {
            return false;
        }
        return enabled == other.enabled
                && Objects.equals(id, other.id)
                && Objects.equals(normalize(type), normalize(other.type))
                && Objects.equals(command, other.command)
                && Objects.equals(args, other.args)
                && Objects.equals(env, other.env)
                && Objects.equals(url, other.url)
                && Objects.equals(headers, other.headers);
    }

    /**
     * 规范化可忽略大小写的配置文本。
     *
     * @param value 原始文本
     * @return 去除首尾空白并转为小写后的文本
     */
    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
