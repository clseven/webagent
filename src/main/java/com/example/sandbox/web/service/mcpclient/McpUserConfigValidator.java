package com.example.sandbox.web.service.mcpclient;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户 MCP 配置校验器。
 *
 * <p>用户动态配置仅允许 Streamable HTTP，不允许 stdio、环境变量或自定义 headers。
 * 这些限制避免用户配置在主应用主机执行命令，也避免凭据以明文写入沙箱配置文件。</p>
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
            String previousId = endpointOwners.putIfAbsent(safe.getUrl(), safe.getId());
            if (previousId != null) {
                throw new IllegalArgumentException(
                        "MCP endpoint 重复: " + safe.getUrl()
                                + " 已由 " + previousId + " 配置");
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
        if (!"streamable-http".equals(type) && !"http".equals(type)) {
            throw new IllegalArgumentException(
                    "用户动态 MCP 只允许 streamable-http，不允许 stdio");
        }
        if (server.getCommand() != null && !server.getCommand().isBlank()
                || !server.getArgs().isEmpty() || !server.getEnv().isEmpty()) {
            throw new IllegalArgumentException("用户动态 MCP 不允许配置 command、args 或 env");
        }
        if (!server.getHeaders().isEmpty()) {
            throw new IllegalArgumentException(
                    "用户动态 MCP 暂不允许 headers，避免凭据明文写入沙箱配置");
        }

        String normalizedUrl = validateUrl(server.getUrl());
        McpServerConfig safe = new McpServerConfig();
        safe.setId(id);
        safe.setEnabled(server.isEnabled());
        safe.setType("streamable-http");
        safe.setUrl(normalizedUrl);
        return safe;
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
