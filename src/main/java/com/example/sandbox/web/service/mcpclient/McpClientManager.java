package com.example.sandbox.web.service.mcpclient;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MCP 客户端生命周期管理器。
 *
 * <p>启动时按配置为每个 MCP Server 建立一条长连接，调用 {@code initialize} 完成握手，
 * 然后缓存 {@code tools/list} 结果。失败的 server 只 warn，不阻塞应用启动；
 * 后续若 server 推送 {@code notifications/tools/list_changed}，会自动刷新缓存。</p>
 *
 * <p>所有 MCP Server 都是全局共享的，不区分会话——这是 MCP 协议的常见用法，
 * 也避免了为每个会话重复 fork 子进程或建立 HTTP session 带来的开销。</p>
 */
@Component
@EnableConfigurationProperties(McpClientProperties.class)
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final McpClientProperties properties;

    /** serverId -> 已初始化的 sync client。失败启动的 server 不会出现在这里。 */
    private final ConcurrentMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    /** serverId -> 缓存的工具列表。tools/list_changed 通知时被覆盖。 */
    private final ConcurrentMap<String, List<McpSchema.Tool>> toolCache = new ConcurrentHashMap<>();

    public McpClientManager(McpClientProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("MCP 客户端未启用 (agent.mcp.enabled=false)，跳过初始化");
            return;
        }
        if (properties.getServers().isEmpty()) {
            log.info("MCP 客户端已启用，但未配置任何 server");
            return;
        }

        for (McpServerConfig config : properties.getServers()) {
            startServer(config);
        }
        log.info("MCP 客户端初始化完成：成功 {} 个 / 配置 {} 个",
                clients.size(), properties.getServers().size());
    }

    @PreDestroy
    public void shutdown() {
        for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
            try {
                boolean ok = entry.getValue().closeGracefully();
                log.info("MCP 客户端关闭: server={}, graceful={}", entry.getKey(), ok);
            } catch (Exception e) {
                log.warn("MCP 客户端关闭失败: server={}, 原因={}", entry.getKey(), e.getMessage());
            }
        }
        clients.clear();
        toolCache.clear();
    }

    /**
     * 列出某个 server 当前缓存的工具。
     *
     * @param serverId server 标识
     * @return 工具列表，不存在或未初始化时返回空列表
     */
    public List<McpSchema.Tool> listTools(String serverId) {
        return toolCache.getOrDefault(serverId, List.of());
    }

    /**
     * 列出当前所有可用的 server id（已成功初始化）。
     *
     * @return server id 列表，按配置声明顺序
     */
    public List<String> listServerIds() {
        // 用 properties 的顺序而不是 clients 的迭代顺序，保证工具列表稳定
        List<String> ids = new ArrayList<>();
        for (McpServerConfig config : properties.getServers()) {
            if (clients.containsKey(config.getId())) {
                ids.add(config.getId());
            }
        }
        return ids;
    }

    /**
     * 调用某个 MCP 工具。
     *
     * @param serverId server 标识
     * @param toolName MCP Server 内的原始工具名
     * @param arguments LLM 生成的参数
     * @return MCP 调用结果
     * @throws IllegalStateException server 不存在或已断开
     */
    public McpSchema.CallToolResult callTool(String serverId, String toolName,
                                             Map<String, Object> arguments) {
        McpSyncClient client = clients.get(serverId);
        if (client == null) {
            throw new IllegalStateException("MCP server 不可用: " + serverId);
        }
        Map<String, Object> safeArgs = arguments != null ? arguments : Map.of();
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
                .arguments(safeArgs)
                .build();
        return client.callTool(request);
    }

    /**
     * 强制刷新某个 server 的工具列表。
     *
     * @param serverId server 标识
     */
    public void refresh(String serverId) {
        McpSyncClient client = clients.get(serverId);
        if (client == null) {
            return;
        }
        try {
            McpSchema.ListToolsResult result = client.listTools();
            toolCache.put(serverId, List.copyOf(result.tools()));
            log.info("MCP server 工具刷新: server={}, count={}", serverId, result.tools().size());
        } catch (Exception e) {
            log.warn("MCP server 工具刷新失败: server={}, 原因={}", serverId, e.getMessage());
        }
    }

    /**
     * 启动单个 MCP Server，失败只 warn 不抛。
     *
     * @param config server 配置
     */
    private void startServer(McpServerConfig config) {
        if (config.getId() == null || config.getId().isBlank()) {
            log.warn("跳过缺少 id 的 MCP server 配置");
            return;
        }
        if (clients.containsKey(config.getId())) {
            log.warn("MCP server id 重复，已忽略: {}", config.getId());
            return;
        }

        try {
            McpClientTransport transport = createTransport(config);

            // tools/list_changed 通知会触发刷新；用 server id 闭包捕获，避免传 client
            String serverId = config.getId();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(properties.getRequestTimeout())
                    .initializationTimeout(properties.getInitializationTimeout())
                    .toolsChangeConsumer(tools -> {
                        toolCache.put(serverId, List.copyOf(tools));
                        log.info("MCP server tools/list_changed: server={}, count={}",
                                serverId, tools.size());
                    })
                    .build();

            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();

            clients.put(serverId, client);
            toolCache.put(serverId, List.copyOf(tools.tools()));
            log.info("MCP server 已就绪: server={}, type={}, tools={}",
                    serverId, config.getType(), tools.tools().size());
        } catch (Exception e) {
            log.warn("MCP server 启动失败，已跳过: server={}, type={}, 原因={}",
                    config.getId(), config.getType(), e.getMessage());
        }
    }

    /**
     * 根据配置创建 transport。
     *
     * @param config server 配置
     * @return 已就绪的 transport 实例
     */
    private McpClientTransport createTransport(McpServerConfig config) {
        String type = config.getType() != null ? config.getType().toLowerCase() : "";
        return switch (type) {
            case "stdio" -> createStdioTransport(config);
            case "streamable-http", "http" -> createHttpTransport(config);
            default -> throw new IllegalArgumentException("不支持的 MCP transport 类型: " + config.getType());
        };
    }

    private McpClientTransport createStdioTransport(McpServerConfig config) {
        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalArgumentException("stdio MCP server 必须配置 command: " + config.getId());
        }
        ServerParameters.Builder builder = ServerParameters.builder(config.getCommand());
        if (!config.getArgs().isEmpty()) {
            builder.args(config.getArgs().toArray(new String[0]));
        }
        if (!config.getEnv().isEmpty()) {
            // ServerParameters 默认从系统继承一组安全环境变量，这里再追加用户配置
            builder.env(new LinkedHashMap<>(config.getEnv()));
        }
        return new StdioClientTransport(builder.build(), McpJsonDefaults.getMapper());
    }

    private McpClientTransport createHttpTransport(McpServerConfig config) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalArgumentException("streamable-http MCP server 必须配置 url: " + config.getId());
        }
        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport.builder(config.getUrl())
                        .jsonMapper(McpJsonDefaults.getMapper());

        // 自定义请求头通过 requestBuilder 注入；这里建一个 dummy URI，运行时 transport 会覆盖路径
        if (!config.getHeaders().isEmpty()) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
            for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
            builder.requestBuilder(requestBuilder);
        }
        return builder.build();
    }

    /**
     * 仅供测试用：返回当前活跃 client 的不可变视图。
     */
    Map<String, McpSyncClient> currentClients() {
        return Collections.unmodifiableMap(clients);
    }
}
