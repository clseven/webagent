package com.example.sandbox.web.service.mcpclient;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用户 MCP 配置与运行时连接的统一编排服务。
 *
 * <p>该服务通过 AIO File API 读写 {@code /home/gem/.mcp/servers.json}，
 * 但真实 MCP 连接、工具发现和调用仍由官方 SDK 驱动的 {@link McpClientManager} 完成。
 * 本服务不调用 AIO MCP API。</p>
 */
@Service
public class McpConfigurationService {

    /**
     * MCP 总开关关闭时返回给 Agent 的稳定错误说明。
     *
     * <p>该配置属于 WebAgent 后端进程，不能从用户沙箱中修改。错误文本同时要求 Agent
     * 停止继续搜索沙箱配置，避免把宿主应用配置和用户工作区混为一谈。</p>
     */
    private static final String MCP_DISABLED_MESSAGE =
            "MCP 客户端未启用。这是 WebAgent 后端启动配置，无法通过用户沙箱修改；"
                    + "请由管理员设置 MCP_ENABLED=true（即 agent.mcp.enabled=true）并重启后端。"
                    + "收到此错误后应停止安装流程，不要调用 shell、文件、浏览器或搜索工具寻找配置。";

    /** 用户 MCP 配置目录。 */
    public static final String CONFIG_DIRECTORY = "/home/gem/.mcp";

    /** 用户 MCP 配置文件。 */
    public static final String CONFIG_PATH = CONFIG_DIRECTORY + "/servers.json";

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 沙箱客户端工厂，用于通过 AIO File API 读写用户配置。 */
    private final SandboxClientFactory sandboxClientFactory;

    /** 会话仓库，用于稳定解析 sessionId 对应的 userId。 */
    private final ConversationSessionRepository sessionRepository;

    /** MCP Client 生命周期管理器。 */
    private final McpClientManager manager;

    /** 用户 MCP 配置安全校验器。 */
    private final McpUserConfigValidator validator;

    /** 已完成首次懒加载的用户集合。 */
    private final Set<Long> loadedUsers = ConcurrentHashMap.newKeySet();

    /** 用户级懒加载锁，避免同一用户并发首请求重复建立 Client。 */
    private final ConcurrentMap<Long, Object> userLoadLocks = new ConcurrentHashMap<>();

    /**
     * 创建 MCP 配置编排服务。
     *
     * @param objectMapper         JSON 序列化器
     * @param sandboxClientFactory 沙箱客户端工厂
     * @param sessionRepository    会话仓库
     * @param manager              MCP Client 管理器
     * @param validator            用户配置校验器
     */
    public McpConfigurationService(ObjectMapper objectMapper,
                                   SandboxClientFactory sandboxClientFactory,
                                   ConversationSessionRepository sessionRepository,
                                   McpClientManager manager,
                                   McpUserConfigValidator validator) {
        this.objectMapper = objectMapper;
        this.sandboxClientFactory = sandboxClientFactory;
        this.sessionRepository = sessionRepository;
        this.manager = manager;
        this.validator = validator;
    }

    /**
     * 从用户沙箱配置文件重新加载全部私有 MCP Server。
     *
     * <p>配置文件整体解析或校验失败时不会修改任何现有 Client。单个 Server 连接失败时，
     * 其他有效变更仍会应用，失败 Server 的旧 Client 会继续保留。</p>
     *
     * @param sessionId 当前会话 ID
     * @return 新增、更新、删除、未变化和失败项
     */
    public McpReloadResult reloadUserServers(String sessionId) {
        if (!manager.isEnabled()) {
            return configFailure(McpErrorCode.MCP_DISABLED, MCP_DISABLED_MESSAGE);
        }

        Long userId = resolveUserId(sessionId);
        try {
            McpUserConfigDocument document = readDocument(sessionId, true);
            List<McpServerConfig> configs =
                    validator.validateDocument(document, systemServerIds());
            McpReloadResult result = applyUserConfigs(userId, configs);
            loadedUsers.add(userId);
            return result;
        } catch (Exception e) {
            return configFailure(McpErrorCode.CONFIG_INVALID, safeError(e));
        }
    }

    /**
     * 确保当前用户在本次应用进程中至少加载过一次 MCP 配置。
     *
     * <p>该方法由 Agent 获取工具列表时触发，解决后端重启后用户 Client 尚未恢复的问题。
     * 同一用户只在首次使用时读取一次配置，不进行定时扫描；后续文件变化仍需显式调用 reload。</p>
     *
     * @param sessionId 当前会话 ID
     */
    public void ensureUserServersLoaded(String sessionId) {
        if (!manager.isEnabled()) {
            return;
        }

        Long userId = resolveUserId(sessionId);
        if (loadedUsers.contains(userId)) {
            return;
        }

        Object lock = userLoadLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            if (!loadedUsers.contains(userId)) {
                reloadUserServers(sessionId);
            }
        }
        userLoadLocks.remove(userId, lock);
    }

    /**
     * 添加或更新一个用户远程 MCP Server，并立即尝试连接。
     *
     * <p>配置会先写入沙箱文件，再触发完整 reload。连接失败时配置仍保留，方便用户修正后重试，
     * 同名旧 Client 不会被关闭。</p>
     *
     * @param sessionId 当前会话 ID
     * @param config    待添加或更新的用户 Server 配置
     * @return 重新加载结果
     */
    public McpReloadResult addOrReplaceUserServer(String sessionId, McpServerConfig config) {
        if (!manager.isEnabled()) {
            return configFailure(McpErrorCode.MCP_DISABLED, MCP_DISABLED_MESSAGE);
        }

        try {
            McpServerConfig safeConfig =
                    validator.validateServer(config, systemServerIds());
            McpUserConfigDocument document = readDocument(sessionId, true);
            List<McpServerConfig> servers = new ArrayList<>(
                    validator.validateDocument(document, systemServerIds()));

            boolean replaced = false;
            for (int i = 0; i < servers.size(); i++) {
                String currentId = servers.get(i).getId();
                if (currentId != null && currentId.equalsIgnoreCase(safeConfig.getId())) {
                    servers.set(i, safeConfig);
                    replaced = true;
                    break;
                }
            }

            String requestedId = safeConfig.getId();
            String reusedId = null;
            if (!replaced) {
                for (int i = 0; i < servers.size(); i++) {
                    McpServerConfig current = servers.get(i);
                    if (safeConfig.getUrl().equals(current.getUrl())) {
                        reusedId = current.getId();
                        safeConfig.setId(reusedId);
                        servers.set(i, safeConfig);
                        replaced = true;
                        break;
                    }
                }
            }
            if (!replaced) {
                servers.add(safeConfig);
            }

            document.setVersion(McpUserConfigValidator.SUPPORTED_VERSION);
            document.setServers(servers);
            validator.validateDocument(document, systemServerIds());
            writeDocument(sessionId, document);
            McpReloadResult result = reloadUserServers(sessionId);
            if (reusedId == null) {
                return result;
            }
            return new McpReloadResult(
                    result.added(),
                    result.updated(),
                    result.removed(),
                    result.unchanged(),
                    Map.of(requestedId, reusedId),
                    result.failed());
        } catch (Exception e) {
            return configFailure(McpErrorCode.CONFIG_INVALID, safeError(e));
        }
    }

    /**
     * 删除用户 MCP Server 配置并关闭对应 Client。
     *
     * @param sessionId 当前会话 ID
     * @param serverId  待删除 Server ID
     * @return 重新加载结果
     */
    public McpReloadResult removeUserServer(String sessionId, String serverId) {
        if (!manager.isEnabled()) {
            return configFailure(McpErrorCode.MCP_DISABLED, MCP_DISABLED_MESSAGE);
        }

        try {
            McpUserConfigDocument document = readDocument(sessionId, true);
            List<McpServerConfig> remaining = document.getServers().stream()
                    .filter(server -> server.getId() == null
                            || !server.getId().equalsIgnoreCase(serverId))
                    .toList();
            document.setServers(remaining);
            validator.validateDocument(document, systemServerIds());
            writeDocument(sessionId, document);
            return reloadUserServers(sessionId);
        } catch (Exception e) {
            return configFailure(McpErrorCode.CONFIG_INVALID, safeError(e));
        }
    }

    /**
     * 列出系统内置和当前用户私有 MCP Server 的安全状态。
     *
     * @param sessionId 当前会话 ID
     * @return 不包含 headers、环境变量和凭据的 Server 状态
     */
    public List<McpServerView> listServers(String sessionId) {
        ensureUserServersLoaded(sessionId);
        Long userId = resolveUserId(sessionId);
        List<McpServerView> views = new ArrayList<>();

        for (McpServerConfig config : manager.listSystemConfigs()) {
            McpClientKey key = McpClientKey.system(config.getId());
            views.add(toView(key, config));
        }

        McpUserConfigDocument document = readDocument(sessionId, true);
        List<McpServerConfig> userConfigs =
                validator.validateDocument(document, systemServerIds());
        for (McpServerConfig config : userConfigs) {
            views.add(toView(McpClientKey.user(userId, config.getId()), config));
        }
        return List.copyOf(views);
    }

    /**
     * 根据会话 ID 解析所属用户 ID。
     *
     * <p>该方法查询持久化会话，不依赖可能在异步线程中丢失的 UserContext。</p>
     *
     * @param sessionId 会话 ID
     * @return 会话所属用户 ID
     * @throws SessionNotFoundException 会话不存在时抛出
     * @throws IllegalStateException    会话没有用户归属时抛出
     */
    public Long resolveUserId(String sessionId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (session.getUserId() == null) {
            throw new IllegalStateException("会话没有绑定用户，无法加载私有 MCP");
        }
        return session.getUserId();
    }

    /**
     * 应用一份已通过整体校验的用户配置。
     *
     * @param userId  用户 ID
     * @param configs 已校验配置
     * @return 重新加载结果
     */
    private McpReloadResult applyUserConfigs(Long userId, List<McpServerConfig> configs) {
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        Map<String, McpOperationError> failed = new LinkedHashMap<>();
        Set<String> desiredEnabledIds = new HashSet<>();

        for (McpServerConfig config : configs) {
            if (!config.isEnabled()) {
                continue;
            }

            desiredEnabledIds.add(config.getId());
            McpClientKey key = McpClientKey.user(userId, config.getId());
            McpServerConfig active = manager.getActiveConfig(key);
            if (active != null && active.sameConnectionConfig(config)) {
                unchanged.add(config.getId());
                continue;
            }

            try {
                manager.addOrReplaceUserServer(userId, config);
                if (active == null) {
                    added.add(config.getId());
                } else {
                    updated.add(config.getId());
                }
            } catch (Exception e) {
                failed.put(config.getId(), operationError(e));
            }
        }

        for (String activeId : manager.listUserServerIds(userId)) {
            if (!desiredEnabledIds.contains(activeId)) {
                manager.removeUserServer(userId, activeId);
                removed.add(activeId);
            }
        }

        return new McpReloadResult(
                List.copyOf(added),
                List.copyOf(updated),
                List.copyOf(removed),
                List.copyOf(unchanged),
                Map.of(),
                Map.copyOf(failed));
    }

    /**
     * 将配置和运行时状态转换为安全展示视图。
     *
     * @param key    Client 键
     * @param config Server 配置
     * @return 安全状态视图
     */
    private McpServerView toView(McpClientKey key, McpServerConfig config) {
        List<String> toolNames = manager.listTools(key).stream()
                .map(tool -> tool.name())
                .toList();
        return new McpServerView(
                key.scope(),
                config.getId(),
                config.getType(),
                config.getUrl(),
                config.isEnabled(),
                manager.isConnected(key),
                toolNames,
                manager.getLastError(key));
    }

    /**
     * 读取用户 MCP 配置文档。
     *
     * @param sessionId       当前会话 ID
     * @param createIfMissing 文件不存在时是否创建空配置
     * @return 解析后的配置文档
     */
    private McpUserConfigDocument readDocument(String sessionId, boolean createIfMissing) {
        AioClient aio = sandboxClientFactory.getAioClient(sessionId);
        try {
            String json = aio.files().readText(CONFIG_PATH);
            return parseDocument(json);
        } catch (Exception firstFailure) {
            if (!createIfMissing) {
                throw firstFailure;
            }
            return createOrReadMissingDocument(aio, firstFailure);
        }
    }

    /**
     * 在首次使用时创建空配置；若文件其实存在，则保留原读取错误避免覆盖。
     *
     * @param aio          当前用户 AIO Client
     * @param readFailure 首次读取失败
     * @return 新建或重新读取的配置文档
     */
    private McpUserConfigDocument createOrReadMissingDocument(AioClient aio,
                                                               Exception readFailure) {
        aio.shell().exec("mkdir -p '" + CONFIG_DIRECTORY + "'");
        if (configFileExists(aio)) {
            throw new IllegalStateException("读取现有 MCP 配置失败: " + safeError(readFailure),
                    readFailure);
        }

        McpUserConfigDocument empty = new McpUserConfigDocument();
        writeDocument(aio, empty);
        return empty;
    }

    /**
     * 判断用户配置目录中是否已存在 servers.json。
     *
     * @param aio 当前用户 AIO Client
     * @return 文件存在时返回 true
     */
    private boolean configFileExists(AioClient aio) {
        Map<String, Object> response = aio.files().list(
                CONFIG_DIRECTORY, false, true, 1, true, "name", false);
        if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
            return false;
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return false;
        }
        Object files = dataMap.get("files");
        if (!(files instanceof List<?> fileList)) {
            return false;
        }
        for (Object item : fileList) {
            if (item instanceof Map<?, ?> file
                    && "servers.json".equals(String.valueOf(file.get("name")))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析并检查配置文件大小。
     *
     * @param json 配置 JSON
     * @return 配置文档；空文本视为空配置
     */
    private McpUserConfigDocument parseDocument(String json) {
        if (json == null || json.isBlank()) {
            return new McpUserConfigDocument();
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > McpUserConfigValidator.MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException(
                    "MCP 配置文件超过 "
                            + McpUserConfigValidator.MAX_CONFIG_BYTES + " 字节上限");
        }
        try {
            return objectMapper.readValue(json, McpUserConfigDocument.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("MCP 配置 JSON 格式错误: "
                    + e.getOriginalMessage(), e);
        }
    }

    /**
     * 将配置文档写回当前会话对应的用户沙箱。
     *
     * @param sessionId 当前会话 ID
     * @param document  配置文档
     */
    private void writeDocument(String sessionId, McpUserConfigDocument document) {
        writeDocument(sandboxClientFactory.getAioClient(sessionId), document);
    }

    /**
     * 使用指定 AIO Client 写入配置文档。
     *
     * @param aio      当前用户 AIO Client
     * @param document 配置文档
     */
    private void writeDocument(AioClient aio, McpUserConfigDocument document) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(document);
            if (!aio.files().writeText(CONFIG_PATH, json)) {
                throw new IllegalStateException("写入 MCP 配置文件失败");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 MCP 配置失败", e);
        }
    }

    /**
     * 获取系统保留 Server ID 集合。
     *
     * @return 小写系统 Server ID 集合
     */
    private Set<String> systemServerIds() {
        Set<String> ids = new HashSet<>();
        for (McpServerConfig config : manager.listSystemConfigs()) {
            if (config.getId() != null) {
                ids.add(config.getId().trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * 创建配置文件级失败结果。
     *
     * @param code   错误码
     * @param reason 失败原因
     * @return 仅包含配置错误的加载结果
     */
    private McpReloadResult configFailure(McpErrorCode code, String reason) {
        return new McpReloadResult(
                List.of(), List.of(), List.of(), List.of(),
                Map.of(),
                Map.of("_config", new McpOperationError(code, reason, null)));
    }

    /**
     * 将配置或 Manager 异常转换为结构化操作错误。
     *
     * @param error 原始异常
     * @return Manager 连接错误或通用配置错误
     */
    private McpOperationError operationError(Exception error) {
        if (error instanceof McpConnectionException connectionException) {
            return connectionException.getOperationError();
        }
        return new McpOperationError(
                McpErrorCode.CONFIG_INVALID,
                safeError(error),
                null);
    }

    /**
     * 生成稳定且非空的错误文本。
     *
     * @param error 原始异常
     * @return 错误文本
     */
    private String safeError(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
