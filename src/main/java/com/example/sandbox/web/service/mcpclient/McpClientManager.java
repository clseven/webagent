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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MCP Client 生命周期管理器。
 *
 * <p>系统配置在应用启动时加载；用户配置由 {@link McpConfigurationService} 显式触发加载。
 * Client 使用 {@link McpClientKey} 按系统和用户作用域隔离，用户之间不会共享连接或工具缓存。</p>
 *
 * <p>替换连接时先完成新 Client 的 initialize 和 tools/list，再原子替换旧 Client。
 * 新连接失败不会关闭旧连接，避免错误配置破坏仍可用的 MCP 能力。</p>
 */
@Component
@EnableConfigurationProperties(McpClientProperties.class)
public class McpClientManager {

    /** 组件日志。 */
    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    /** MCP 功能和系统 Server 配置。 */
    private final McpClientProperties properties;

    /** 完整 MCP URL 解析器。 */
    private final McpEndpointResolver endpointResolver;

    /** Client 键到已初始化同步 Client 的映射。 */
    private final ConcurrentMap<McpClientKey, McpSyncClient> clients = new ConcurrentHashMap<>();

    /** Client 键到当前工具缓存的映射。 */
    private final ConcurrentMap<McpClientKey, List<McpSchema.Tool>> toolCache = new ConcurrentHashMap<>();

    /** Client 键到当前成功生效配置的映射。 */
    private final ConcurrentMap<McpClientKey, McpServerConfig> activeConfigs = new ConcurrentHashMap<>();

    /** Client 键到当前连接代次的映射，用于忽略旧连接迟到的工具变更通知。 */
    private final ConcurrentMap<McpClientKey, String> activeGenerations = new ConcurrentHashMap<>();

    /** Client 键到最近一次连接错误的映射。 */
    private final ConcurrentMap<McpClientKey, McpOperationError> lastErrors =
            new ConcurrentHashMap<>();

    /**
     * 创建 MCP Client 管理器。
     *
     * @param properties       MCP 配置属性
     * @param endpointResolver 完整 MCP URL 解析器
     */
    public McpClientManager(McpClientProperties properties,
                            McpEndpointResolver endpointResolver) {
        this.properties = properties;
        this.endpointResolver = endpointResolver;
    }

    /**
     * 加载系统级 MCP Server。
     *
     * <p>单个 Server 启动失败只记录错误，不阻断应用启动；用户级 Server 不在此处加载。</p>
     */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("MCP 客户端未启用 (agent.mcp.enabled=false)，跳过初始化");
            return;
        }

        int configured = 0;
        for (McpServerConfig config : properties.getServers()) {
            if (!config.isEnabled()) {
                continue;
            }
            configured++;
            try {
                addOrReplaceSystemServer(config);
            } catch (Exception e) {
                log.warn("系统 MCP server 启动失败，已跳过: server={}, 原因={}",
                        config.getId(), e.getMessage());
            }
        }
        log.info("系统 MCP 客户端初始化完成：成功 {} 个 / 启用配置 {} 个",
                countByScope(McpClientScope.SYSTEM), configured);
    }

    /**
     * 关闭全部系统级和用户级 MCP Client。
     */
    @PreDestroy
    public void shutdown() {
        for (Map.Entry<McpClientKey, McpSyncClient> entry : clients.entrySet()) {
            closeQuietly(entry.getKey(), entry.getValue());
        }
        clients.clear();
        toolCache.clear();
        activeConfigs.clear();
        activeGenerations.clear();
        lastErrors.clear();
    }

    /**
     * 判断 MCP 总开关是否启用。
     *
     * @return true 表示允许加载和暴露 MCP 工具
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 新增或替换系统级 MCP Server。
     *
     * @param config 系统 MCP Server 配置
     * @throws IllegalArgumentException 配置缺少必要字段或 transport 不受支持时抛出
     * @throws IllegalStateException    连接、初始化或工具发现失败时抛出
     */
    public void addOrReplaceSystemServer(McpServerConfig config) {
        requireEnabled();
        addOrReplace(McpClientKey.system(requireServerId(config)), config);
    }

    /**
     * 新增或替换用户级 MCP Server。
     *
     * @param userId 用户 ID
     * @param config 用户 MCP Server 配置
     * @throws IllegalArgumentException 配置缺少必要字段或 transport 不受支持时抛出
     * @throws IllegalStateException    连接、初始化或工具发现失败时抛出
     */
    public void addOrReplaceUserServer(Long userId, McpServerConfig config) {
        requireEnabled();
        addOrReplace(McpClientKey.user(userId, requireServerId(config)), config);
    }

    /**
     * 移除并关闭用户级 MCP Server。
     *
     * @param userId   用户 ID
     * @param serverId MCP Server ID
     * @return 存在并移除 Client 时返回 true
     */
    public boolean removeUserServer(Long userId, String serverId) {
        return remove(McpClientKey.user(userId, serverId));
    }

    /**
     * 返回当前用户可使用的系统级和用户级 Client 键。
     *
     * <p>系统 Server 按 application.yml 声明顺序排列，用户 Server 按 ID 排列，
     * 以保持传给模型的工具定义顺序稳定。</p>
     *
     * @param userId 当前用户 ID
     * @return 已连接的 Client 键列表
     */
    public List<McpClientKey> listAvailableKeys(Long userId) {
        List<McpClientKey> result = new ArrayList<>();
        for (McpServerConfig config : properties.getServers()) {
            McpClientKey key = McpClientKey.system(config.getId());
            if (clients.containsKey(key)) {
                result.add(key);
            }
        }

        clients.keySet().stream()
                .filter(key -> key.scope() == McpClientScope.USER && key.userId().equals(userId))
                .sorted(Comparator.comparing(McpClientKey::serverId))
                .forEach(result::add);
        return List.copyOf(result);
    }

    /**
     * 列出指定用户当前已连接的私有 Server ID。
     *
     * @param userId 用户 ID
     * @return 已连接的用户 Server ID 列表
     */
    public List<String> listUserServerIds(Long userId) {
        return clients.keySet().stream()
                .filter(key -> key.scope() == McpClientScope.USER && key.userId().equals(userId))
                .map(McpClientKey::serverId)
                .sorted()
                .toList();
    }

    /**
     * 返回系统配置副本，用于生成安全状态视图。
     *
     * @return 系统 MCP Server 配置副本
     */
    public List<McpServerConfig> listSystemConfigs() {
        return properties.getServers().stream().map(McpServerConfig::copy).toList();
    }

    /**
     * 获取当前成功生效的连接配置。
     *
     * @param key Client 键
     * @return 配置副本；当前无成功连接时返回 null
     */
    public McpServerConfig getActiveConfig(McpClientKey key) {
        McpServerConfig config = activeConfigs.get(key);
        return config != null ? config.copy() : null;
    }

    /**
     * 判断指定 Client 是否已连接。
     *
     * @param key Client 键
     * @return Client 存在时返回 true
     */
    public boolean isConnected(McpClientKey key) {
        return clients.containsKey(key);
    }

    /**
     * 获取指定 Client 最近一次连接错误。
     *
     * @param key Client 键
     * @return 结构化错误；没有错误时返回 null
     */
    public McpOperationError getLastError(McpClientKey key) {
        return lastErrors.get(key);
    }

    /**
     * 列出指定 Client 当前缓存的工具。
     *
     * @param key Client 键
     * @return 工具列表，不存在或未初始化时返回空列表
     */
    public List<McpSchema.Tool> listTools(McpClientKey key) {
        return toolCache.getOrDefault(key, List.of());
    }

    /**
     * 调用指定 Client 的 MCP 工具。
     *
     * @param key       Client 键
     * @param toolName  MCP Server 内的原始工具名
     * @param arguments LLM 生成的参数
     * @return MCP 调用结果
     * @throws IllegalStateException Client 不存在或已断开时抛出
     */
    public McpSchema.CallToolResult callTool(McpClientKey key, String toolName,
                                             Map<String, Object> arguments) {
        McpSyncClient client = clients.get(key);
        if (client == null) {
            throw new IllegalStateException("MCP server 不可用: " + key.displayName());
        }
        Map<String, Object> safeArgs = arguments != null ? arguments : Map.of();
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
                .arguments(safeArgs)
                .build();
        return client.callTool(request);
    }

    /**
     * 强制刷新指定 Client 的工具列表。
     *
     * @param key Client 键
     */
    public void refresh(McpClientKey key) {
        McpSyncClient client = clients.get(key);
        if (client == null) {
            return;
        }
        try {
            McpSchema.ListToolsResult result = client.listTools();
            toolCache.put(key, List.copyOf(result.tools()));
            lastErrors.remove(key);
            log.info("MCP server 工具刷新: server={}, count={}",
                    key.displayName(), result.tools().size());
        } catch (Exception e) {
            McpOperationError error = McpConnectionErrorClassifier.classify(
                    e, McpConnectionStage.REFRESH_TOOLS);
            lastErrors.put(key, error);
            log.warn("MCP server 工具刷新失败: server={}, code={}, 原因={}",
                    key.displayName(), error.code(), error.detail());
        }
    }

    /**
     * 创建新连接并在成功后替换旧连接。
     *
     * @param key    Client 键
     * @param config MCP Server 配置
     */
    private void addOrReplace(McpClientKey key, McpServerConfig config) {
        String generation = UUID.randomUUID().toString();
        McpSyncClient replacement = null;
        McpConnectionStage stage = McpConnectionStage.INITIALIZE;
        try {
            McpClientTransport transport = createTransport(config);
            replacement = McpClient.sync(transport)
                    .requestTimeout(properties.getRequestTimeout())
                    .initializationTimeout(properties.getInitializationTimeout())
                    .toolsChangeConsumer(tools -> {
                        if (generation.equals(activeGenerations.get(key))) {
                            toolCache.put(key, List.copyOf(tools));
                            log.info("MCP server tools/list_changed: server={}, count={}",
                                    key.displayName(), tools.size());
                        }
                    })
                    .build();

            replacement.initialize();
            stage = McpConnectionStage.LIST_TOOLS;
            McpSchema.ListToolsResult tools = replacement.listTools();

            activeGenerations.put(key, generation);
            McpSyncClient previous = clients.put(key, replacement);
            toolCache.put(key, List.copyOf(tools.tools()));
            activeConfigs.put(key, config.copy());
            lastErrors.remove(key);
            closeQuietly(key, previous);
            log.info("MCP server 已就绪: server={}, type={}, tools={}",
                    key.displayName(), config.getType(), tools.tools().size());
        } catch (Exception e) {
            McpOperationError error = McpConnectionErrorClassifier.classify(e, stage);
            lastErrors.put(key, error);
            closeQuietly(key, replacement);
            log.warn("MCP server 连接失败: server={}, code={}, 原因={}",
                    key.displayName(), error.code(), error.detail());
            throw new McpConnectionException(error, e);
        }
    }

    /**
     * 移除指定 Client 及其运行时状态。
     *
     * @param key Client 键
     * @return 存在并移除 Client 时返回 true
     */
    private boolean remove(McpClientKey key) {
        activeGenerations.remove(key);
        toolCache.remove(key);
        activeConfigs.remove(key);
        lastErrors.remove(key);
        McpSyncClient client = clients.remove(key);
        closeQuietly(key, client);
        return client != null;
    }

    /**
     * 根据配置创建 MCP transport。
     *
     * @param config Server 配置
     * @return 尚未初始化的 transport
     */
    private McpClientTransport createTransport(McpServerConfig config) {
        String type = config.getType() != null ? config.getType().trim().toLowerCase() : "";
        return switch (type) {
            case "stdio" -> createStdioTransport(config);
            case "streamable-http", "http" -> createHttpTransport(config);
            default -> throw new IllegalArgumentException("不支持的 MCP transport 类型: " + config.getType());
        };
    }

    /**
     * 创建 stdio transport。
     *
     * <p>该 transport 会在主应用主机启动子进程，只允许系统管理员配置使用。</p>
     *
     * @param config stdio Server 配置
     * @return stdio transport
     */
    private McpClientTransport createStdioTransport(McpServerConfig config) {
        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalArgumentException("stdio MCP server 必须配置 command: " + config.getId());
        }
        ServerParameters.Builder builder = ServerParameters.builder(config.getCommand());
        if (!config.getArgs().isEmpty()) {
            builder.args(config.getArgs().toArray(new String[0]));
        }
        if (!config.getEnv().isEmpty()) {
            builder.env(new LinkedHashMap<>(config.getEnv()));
        }
        return new StdioClientTransport(builder.build(), McpJsonDefaults.getMapper());
    }

    /**
     * 创建 Streamable HTTP transport。
     *
     * <p>SDK 内部用 {@code URI.resolve(endpoint)} 拼接最终请求地址，因此必须把完整 URL
     * 拆成 origin 和精确 endpoint。路径为空时连接 HTTP 根路径 {@code /}，
     * 不自动猜测 {@code /mcp}。</p>
     *
     * <ul>
     *   <li>{@code https://learn.microsoft.com/api/mcp}
     *       → baseUri=https://learn.microsoft.com, endpoint=/api/mcp</li>
     *   <li>{@code https://mcp.deepwiki.com/mcp}
     *       → baseUri=https://mcp.deepwiki.com, endpoint=/mcp</li>
     *   <li>{@code https://example.com/}
     *       → baseUri=https://example.com, endpoint=/</li>
     *   <li>{@code https://example.com:8080/mcp}
     *       → baseUri=https://example.com:8080, endpoint=/mcp</li>
     * </ul>
     *
     * @param config HTTP Server 配置
     * @return Streamable HTTP transport
     */
    private McpClientTransport createHttpTransport(McpServerConfig config) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalArgumentException("streamable-http MCP server 必须配置 url: " + config.getId());
        }

        McpResolvedEndpoint resolved = endpointResolver.resolve(config.getUrl());

        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport.builder(resolved.baseUri())
                        .jsonMapper(McpJsonDefaults.getMapper())
                        .endpoint(resolved.endpoint());

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
     * 检查 MCP 功能总开关。
     *
     * @throws IllegalStateException MCP 功能关闭时抛出
     */
    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MCP 客户端未启用，请设置 agent.mcp.enabled=true");
        }
    }

    /**
     * 获取并校验 Server ID。
     *
     * @param config MCP Server 配置
     * @return 非空 Server ID
     */
    private String requireServerId(McpServerConfig config) {
        if (config == null || config.getId() == null || config.getId().isBlank()) {
            throw new IllegalArgumentException("MCP server 配置缺少 id");
        }
        return config.getId();
    }

    /**
     * 统计指定作用域的活跃 Client 数量。
     *
     * @param scope Client 作用域
     * @return 活跃 Client 数量
     */
    private long countByScope(McpClientScope scope) {
        return clients.keySet().stream().filter(key -> key.scope() == scope).count();
    }

    /**
     * 安静关闭 Client，关闭失败只记录日志。
     *
     * @param key    Client 键
     * @param client 待关闭 Client；可为 null
     */
    private void closeQuietly(McpClientKey key, McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            boolean graceful = client.closeGracefully();
            log.info("MCP 客户端关闭: server={}, graceful={}", key.displayName(), graceful);
        } catch (Exception e) {
            log.warn("MCP 客户端关闭失败: server={}, 原因={}",
                    key.displayName(), e.getMessage());
        }
    }

    /**
     * 仅供测试和诊断使用，返回活跃 Client 的不可变视图。
     *
     * @return Client 键到 Client 的不可变映射
     */
    Map<McpClientKey, McpSyncClient> currentClients() {
        return Collections.unmodifiableMap(clients);
    }
}
