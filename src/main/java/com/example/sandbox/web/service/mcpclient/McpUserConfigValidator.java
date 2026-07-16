package com.example.sandbox.web.service.mcpclient;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户 MCP 配置校验器。
 *
 * <p>用户动态配置允许远程 Streamable HTTP，以及在用户 Sandbox 内运行的 shell transport。
 * 仍然不允许用户动态配置宿主机 stdio。HTTP headers 会明文保存在用户自己的 Sandbox，
 * 因此本校验器限制名称、数量和长度，但不会把凭据提升为系统级配置。</p>
 */
@Component
public class McpUserConfigValidator {

    /** 当前支持的用户配置结构版本。 */
    public static final int SUPPORTED_VERSION = 1;

    /** 用户配置文件最大字节数。 */
    public static final int MAX_CONFIG_BYTES = 64 * 1024;

    /** 用户 Server ID 允许的格式。 */
    private static final Pattern SERVER_ID_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** shell 环境变量名允许的格式。 */
    private static final Pattern ENV_NAME_PATTERN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 单个 shell 命令和参数允许的最大长度。 */
    private static final int MAX_SHELL_TEXT_LENGTH = 512;

    /** 单个 shell Server 最多允许的参数数量。 */
    private static final int MAX_SHELL_ARGS = 64;

    /** 单个 shell Server 最多允许的环境变量数量。 */
    private static final int MAX_SHELL_ENV = 32;

    /** 单个 HTTP Server 最多允许的请求头数量。 */
    private static final int MAX_HTTP_HEADERS = 32;

    /** 请求头名称允许的格式。 */
    private static final Pattern HEADER_NAME_PATTERN =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");

    /** 单个请求头值允许的最大长度。 */
    private static final int MAX_HEADER_VALUE_LENGTH = 4096;

    /** 用户可配置的最小请求超时秒数。 */
    private static final int MIN_REQUEST_TIMEOUT_SECONDS = 1;

    /** 用户可配置的最大请求超时秒数。 */
    private static final int MAX_REQUEST_TIMEOUT_SECONDS = 3600;

    /** MCP 安全配置属性。 */
    private final McpClientProperties properties;

    /** 完整 MCP endpoint 解析器。 */
    private final McpEndpointResolver endpointResolver;

    /**
     * 创建用户 MCP 配置校验器。
     *
     * @param properties       MCP 安全配置属性
     * @param endpointResolver 完整 MCP endpoint 解析器
     */
    public McpUserConfigValidator(McpClientProperties properties,
                                  McpEndpointResolver endpointResolver) {
        this.properties = properties;
        this.endpointResolver = endpointResolver;
    }

    /**
     * 校验并复制完整用户配置文档。
     *
     * @param document         用户配置文档
     * @param reservedServerIds 系统保留的 Server ID
     * @return 已校验并可安全交给 Manager 的配置副本
     * @throws IllegalArgumentException 配置版本、数量、ID、transport 或 URL 不合法时抛出
     */
    public List<McpServerConfig> validateDocument(McpUserConfigDocument document,
                                                  Set<String> reservedServerIds) {
        if (document == null) {
            throw new IllegalArgumentException("MCP 配置文档不能为空");
        }
        if (document.getVersion() != SUPPORTED_VERSION) {
            throw new IllegalArgumentException(
                    "不支持的 MCP 配置版本: " + document.getVersion());
        }

        List<McpServerConfig> servers = document.getServers();
        if (servers.size() > properties.getMaxUserServers()) {
            throw new IllegalArgumentException(
                    "用户 MCP Server 数量超过上限 " + properties.getMaxUserServers());
        }

        Set<String> seen = new HashSet<>();
        Map<String, String> endpointOwners = new HashMap<>();
        List<McpServerConfig> validated = new ArrayList<>();
        for (McpServerConfig server : servers) {
            McpServerConfig safe = validateServer(server, reservedServerIds);
            if (!seen.add(safe.getId())) {
                throw new IllegalArgumentException("MCP Server ID 重复: " + safe.getId());
            }
            if (safe.getUrl() != null) {
                String previousId = endpointOwners.putIfAbsent(safe.getUrl(), safe.getId());
                if (previousId != null) {
                    throw new IllegalArgumentException(
                            "MCP endpoint 重复: " + safe.getUrl()
                                    + " 已由 " + previousId + " 配置");
                }
            }
            validated.add(safe);
        }
        return List.copyOf(validated);
    }

    /**
     * 校验单个用户 MCP Server。
     *
     * @param server            待校验配置
     * @param reservedServerIds 系统保留的 Server ID
     * @return 规范化后的配置副本
     * @throws IllegalArgumentException 配置不满足用户动态 MCP 安全约束时抛出
     */
    public McpServerConfig validateServer(McpServerConfig server,
                                          Set<String> reservedServerIds) {
        if (server == null) {
            throw new IllegalArgumentException("MCP Server 配置不能为空");
        }

        String id = server.getId() != null
                ? server.getId().trim().toLowerCase(Locale.ROOT)
                : "";
        if (!SERVER_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "MCP Server ID 只能包含小写字母、数字、点、下划线和短横线，长度不超过 64");
        }
        if (reservedServerIds.contains(id)) {
            throw new IllegalArgumentException("MCP Server ID 与系统内置配置冲突: " + id);
        }

        String type = server.getType() != null
                ? server.getType().trim().toLowerCase(Locale.ROOT)
                : "";
        boolean httpType = "streamable-http".equals(type) || "http".equals(type);
        boolean shellType = "shell".equals(type);
        if (!httpType && !shellType) {
            throw new IllegalArgumentException(
                    "用户动态 MCP 只允许 streamable-http 或 shell，不允许宿主机 stdio");
        }
        Integer timeoutSeconds = validateRequestTimeout(server.getRequestTimeoutSeconds());

        if (shellType) {
            return validateShellServer(id, server);
        }
        if (server.getCommand() != null && !server.getCommand().isBlank()
                || !server.getArgs().isEmpty() || !server.getEnv().isEmpty()) {
            throw new IllegalArgumentException("streamable-http MCP 不允许配置 command、args 或 env");
        }

        String normalizedUrl = validateUrl(server.getUrl());
        McpServerConfig safe = new McpServerConfig();
        safe.setId(id);
        safe.setEnabled(server.isEnabled());
        safe.setType("streamable-http");
        safe.setUrl(normalizedUrl);
        safe.setHeaders(validateHeaders(server.getHeaders()));
        safe.setRequestTimeoutSeconds(timeoutSeconds);
        return safe;
    }

    /**
     * 校验用户 Sandbox 内 shell transport 配置。
     *
     * <p>shell transport 的命令只会在用户自己的 Sandbox 内运行，不会在 WebAgent 主机运行。
     * 仍然限制字段长度和环境变量格式，避免异常配置造成命令构造失败；不会自动重试，
     * 因为 stdio MCP Server 可能在启动时产生副作用。</p>
     *
     * @param id     已规范化的 Server ID
     * @param server 原始 Server 配置
     * @return 规范化后的 shell 配置
     */
    private McpServerConfig validateShellServer(String id, McpServerConfig server) {
        if (server.getUrl() != null && !server.getUrl().isBlank()) {
            throw new IllegalArgumentException("shell MCP 不允许配置 url");
        }
        String command = requireShellText(server.getCommand(), "shell MCP 必须配置 command");
        if (server.getArgs().size() > MAX_SHELL_ARGS) {
            throw new IllegalArgumentException("shell MCP args 数量超过上限 " + MAX_SHELL_ARGS);
        }
        List<String> args = new ArrayList<>();
        for (String arg : server.getArgs()) {
            args.add(requireShellText(arg, "shell MCP args 不能包含空值"));
        }

        if (server.getEnv().size() > MAX_SHELL_ENV) {
            throw new IllegalArgumentException("shell MCP env 数量超过上限 " + MAX_SHELL_ENV);
        }
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : server.getEnv().entrySet()) {
            String name = entry.getKey() != null ? entry.getKey().trim() : "";
            if (!ENV_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("shell MCP env 名称不合法: " + name);
            }
            env.put(name, requireShellText(entry.getValue(), "shell MCP env 值不能为空"));
        }

        McpServerConfig safe = new McpServerConfig();
        safe.setId(id);
        safe.setEnabled(server.isEnabled());
        safe.setType("shell");
        safe.setCommand(command);
        safe.setArgs(args);
        safe.setEnv(env);
        safe.setRequestTimeoutSeconds(validateRequestTimeout(server.getRequestTimeoutSeconds()));
        return safe;
    }

    /**
     * 校验并复制 Streamable HTTP 自定义请求头。
     *
     * <p>请求头值允许包含 Token 等敏感信息，调用方必须确保响应和日志不回显这些值。</p>
     *
     * @param source 原始请求头
     * @return 保留输入顺序的安全副本
     */
    private Map<String, String> validateHeaders(Map<String, String> source) {
        Map<String, String> headers = source != null ? source : Map.of();
        if (headers.size() > MAX_HTTP_HEADERS) {
            throw new IllegalArgumentException("MCP headers 数量超过上限 " + MAX_HTTP_HEADERS);
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey() != null ? entry.getKey().trim() : "";
            if (!HEADER_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("MCP header 名称不合法: " + name);
            }
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("MCP header 值不能为空: " + name);
            }
            if (value.length() > MAX_HEADER_VALUE_LENGTH) {
                throw new IllegalArgumentException("MCP header 值过长: " + name);
            }
            safe.put(name, value);
        }
        return Map.copyOf(safe);
    }

    /**
     * 校验单 Server 请求超时。
     *
     * @param seconds 原始超时秒数
     * @return 合法秒数；为空时保持继承全局默认值
     */
    private Integer validateRequestTimeout(Integer seconds) {
        if (seconds == null) {
            return null;
        }
        if (seconds < MIN_REQUEST_TIMEOUT_SECONDS || seconds > MAX_REQUEST_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("MCP 请求超时必须在 1 到 3600 秒之间");
        }
        return seconds;
    }

    /**
     * 校验 shell 文本字段。
     *
     * @param value        原始字段值
     * @param emptyMessage 字段为空时的错误说明
     * @return 去除首尾空白后的字段值
     */
    private String requireShellText(String value, String emptyMessage) {
        String text = value != null ? value.trim() : "";
        if (text.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        if (text.length() > MAX_SHELL_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "shell MCP 字段长度超过上限 " + MAX_SHELL_TEXT_LENGTH);
        }
        return text;
    }

    /**
     * 校验用户远程 MCP URL 与目标网络地址。
     *
     * @param url 原始 URL
     * @return 规范化后仍精确保留 endpoint 路径和查询参数的 URL
     * @throws IllegalArgumentException URL、协议、端口或目标地址不安全时抛出
     */
    private String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("streamable-http MCP 必须配置 url");
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MCP URL 格式无效", e);
        }

        String scheme = uri.getScheme() != null
                ? uri.getScheme().toLowerCase(Locale.ROOT)
                : "";
        if (!"https".equals(scheme)
                && !("http".equals(scheme) && properties.isUserAllowHttp())) {
            throw new IllegalArgumentException(
                    properties.isUserAllowHttp()
                            ? "MCP URL 只允许 http 或 https"
                            : "MCP URL 必须使用 https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("MCP URL 不允许包含用户名或密码");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("MCP URL 不允许包含 fragment");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("MCP URL 缺少有效主机名");
        }
        if (uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("MCP URL 端口无效");
        }

        if (!properties.isUserAllowPrivateNetwork()) {
            rejectPrivateTarget(uri.getHost());
        }
        return endpointResolver.normalize(uri.toString());
    }

    /**
     * 拒绝环回、私网、链路本地和其他非公网地址。
     *
     * @param host URL 主机名
     * @throws IllegalArgumentException 主机名解析失败或解析到非公网地址时抛出
     */
    private void rejectPrivateTarget(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost")
                || normalized.endsWith(".local")) {
            throw new IllegalArgumentException("用户 MCP 不允许连接本机或本地域名");
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateOrLocal(address)) {
                    throw new IllegalArgumentException(
                            "用户 MCP 不允许连接环回、私网或链路本地地址: "
                                    + address.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析 MCP 主机名: " + host, e);
        }
    }

    /**
     * 判断地址是否属于不应由用户 MCP 访问的网络范围。
     *
     * @param address 待判断 IP 地址
     * @return true 表示地址不是公网目标
     */
    private boolean isPrivateOrLocal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0
                    || first == 100 && second >= 64 && second <= 127
                    || first >= 224;
        }
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
