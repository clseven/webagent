package com.example.sandbox.web.service.impl;

import com.example.sandbox.agent.SandboxAgent;
import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.shell.model.ShellExecResult;
import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.entity.ExecutionResult;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.SandboxClient;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.SkillService;
import com.example.sandbox.web.model.entity.UserSandboxEntity;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.repository.UserSandboxRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙盒操作服务实现
 *
 * <p>沙箱所有权为用户级别：一个用户永久持有一个沙箱，所有会话共享。</p>
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class SandboxServiceImpl implements SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxServiceImpl.class);

    private static final Duration RENEW_INTERVAL = Duration.ofMinutes(30);

    /** AIO 工作目录创建命令，使用显式路径避免不同 shell 对花括号展开支持不一致。 */
    private static final String AIO_WORKSPACE_DIRS_COMMAND = """
            mkdir -p /home/gem/uploads /home/gem/workspace /home/gem/output /home/gem/skills /home/gem/temp /home/gem/tools /home/gem/knowledge
            """.trim();

    /** AIO 初始化命令最大重试次数，用于覆盖服务刚启动时连接被短暂中止的情况。 */
    private static final int AIO_INIT_RETRY_ATTEMPTS = 3;

    /** sandboxId → SandboxAgent */
    private final Map<String, SandboxAgent> sandboxAgents = new ConcurrentHashMap<>();
    /** sessionId → sandboxId */
    private final Map<String, String> sessionSandboxMap = new ConcurrentHashMap<>();
    /** sessionId → isAio */
    private final Map<String, Boolean> sessionTypeMap = new ConcurrentHashMap<>();
    /** userId → sandboxId（用户级永久沙箱） */
    private final Map<Long, String> userSandboxMap = new ConcurrentHashMap<>();
    /** sandboxId → userId（反向查找） */
    private final Map<String, Long> sandboxUserMap = new ConcurrentHashMap<>();
    /** 创建锁 */
    private final Map<String, Object> creationLocks = new ConcurrentHashMap<>();

    private final SkillService skillService;
    private final ConversationSessionRepository sessionRepository;
    private final AgentConfigProperties config;

    @Autowired
    private AioSandboxStore aioSandboxStore;

    @Autowired
    private UserSandboxRepository userSandboxRepository;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private FileSyncService fileSyncService;

    @Autowired
    private KnowledgeFileMigrationService migrationService;

    public SandboxServiceImpl(SkillService skillService,
                             ConversationSessionRepository sessionRepository,
                             AgentConfigProperties config) {
        this.skillService = skillService;
        this.sessionRepository = sessionRepository;
        this.config = config;
    }

    @PostConstruct
    public void cleanupStaleRecords() {
        // 已废弃：不再需要清理非 AIO 沙箱记录
        // AIO 模式下沙箱信息存储在 UserSandboxEntity 中
    }

    @PostConstruct
    public void restoreUserSandboxMap() {
        if (!isCurrentImageAio()) {
            return;
        }
        try {
            var userSandboxes = userSandboxRepository.findByDeletedFalse();
            int restored = 0;
            int unhealthy = 0;
            for (var userSandbox : userSandboxes) {
                Long userId = userSandbox.getUserId();
                String sandboxId = userSandbox.getSandboxId();
                if (userId == null || sandboxId == null) {
                    continue;
                }
                String endpoint = userSandbox.getAioEndpoint();
                if (endpoint != null && !endpoint.isBlank()) {
                    boolean healthy = isSandboxHealthy(endpoint);
                    if (!healthy) {
                        unhealthy++;
                        log.warn("用户沙箱启动恢复时暂不可达，跳过内存映射: userId={}, sandboxId={}, endpoint={}",
                                userId, sandboxId, endpoint);
                        continue;
                    }
                    aioSandboxStore.register("__user_" + userId, sandboxId, endpoint);
                }
                userSandboxMap.put(userId, sandboxId);
                sandboxUserMap.put(sandboxId, userId);
                restored++;
            }
            if (restored > 0 || unhealthy > 0) {
                log.info("恢复用户沙箱映射: 恢复 {} 个，其中 {} 个不健康", restored, unhealthy);
            }
        } catch (Exception e) {
            log.warn("恢复用户沙箱映射失败: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 20 * 60 * 1000)
    public void renewAllSandboxes() {
        if (isCurrentImageAio()) {
            // AIO 模式：沙箱由平台管理，不需要续期
            return;
        }
        for (var entry : userSandboxMap.entrySet()) {
            try {
                String sandboxId = entry.getValue();
                SandboxAgent agent = sandboxAgents.get(sandboxId);
                if (agent != null && agent.isHealthy()) {
                    agent.renew(RENEW_INTERVAL);
                    log.debug("续期沙箱 {}（用户 {}）", sandboxId, entry.getKey());
                } else {
                    log.warn("沙箱 {} 不健康，移除用户 {} 映射", sandboxId, entry.getKey());
                    userSandboxMap.remove(entry.getKey());
                    sandboxUserMap.remove(sandboxId);
                }
            } catch (Exception e) {
                log.warn("续期沙箱失败，用户 {}", entry.getKey(), e);
            }
        }
    }

    // ==================== 沙箱创建（用户级） ====================

    @Override
    public void createSandbox(String sessionId) {
        Long userId = getUserIdForSession(sessionId);
        if (userId == null) {
            log.warn("无法解析会话 {} 对应的用户，跳过沙箱创建", sessionId);
            return;
        }

        String existingSandboxId = userSandboxMap.get(userId);
        if (existingSandboxId != null) {
            // 用户已有沙箱，尝试复用
            if (isCurrentImageAio()) {
                // AIO 模式：查找 endpoint
                String endpoint = findEndpointForUser(userId, existingSandboxId);
                if (endpoint != null) {
                    // 检查沙箱是否健康
                    if (isSandboxHealthy(endpoint)) {
                        linkSessionToSandbox(sessionId, existingSandboxId, endpoint);
                        aioSandboxStore.register(sessionId, existingSandboxId, endpoint);
                        initAioDirectories(sessionId, endpoint);
                        log.info("用户 {} 已有沙箱 {}，关联会话 {}", userId, existingSandboxId, sessionId);
                        return;
                    }
                    // 沙箱不健康，清理数据库记录
                    log.warn("用户 {} 沙箱 {} 不健康，清理记录并重建", userId, existingSandboxId);
                    cleanupUnhealthySandbox(userId, existingSandboxId);
                } else {
                    // endpoint 找不到，沙箱可能不健康，需要重建
                    log.warn("用户 {} 沙箱 {} 找不到 endpoint，将重建", userId, existingSandboxId);
                }
            } else {
                // 非 AIO 模式：检查 SandboxAgent
                SandboxAgent existingAgent = sandboxAgents.get(existingSandboxId);
                if (existingAgent != null && existingAgent.isHealthy()) {
                    linkSessionToSandbox(sessionId, existingSandboxId, existingAgent.getAioEndpoint());
                    log.info("用户 {} 已有沙箱 {}，关联会话 {}", userId, existingSandboxId, sessionId);
                    return;
                }
                log.warn("用户 {} 沙箱 {} 不健康，将重建", userId, existingSandboxId);
            }
            // 清理旧映射
            userSandboxMap.remove(userId);
            sandboxUserMap.remove(existingSandboxId);
        }

        Object lock = creationLocks.computeIfAbsent("user:" + userId, k -> new Object());
        synchronized (lock) {
            try {
                // 双重检查
                existingSandboxId = userSandboxMap.get(userId);
                if (existingSandboxId != null) {
                    if (isCurrentImageAio()) {
                        String endpoint = findEndpointForUser(userId, existingSandboxId);
                        if (endpoint != null && isSandboxHealthy(endpoint)) {
                            linkSessionToSandbox(sessionId, existingSandboxId, endpoint);
                            aioSandboxStore.register(sessionId, existingSandboxId, endpoint);
                            initAioDirectories(sessionId, endpoint);
                            return;
                        }
                        // 沙箱不健康，清理记录
                        if (endpoint != null) {
                            cleanupUnhealthySandbox(userId, existingSandboxId);
                        }
                    } else {
                        if (sandboxAgents.containsKey(existingSandboxId)) {
                            linkSessionToSandbox(sessionId, existingSandboxId,
                                    sandboxAgents.get(existingSandboxId).getAioEndpoint());
                            return;
                        }
                    }
                }

                // 创建新沙箱
                SandboxAgent.Builder builder = SandboxAgent.builder()
                        .image(config.getSandbox().getImage())
                        .timeout(Duration.parse(config.getSandbox().getSandboxTimeout()))
                        .readyTimeout(Duration.parse(config.getSandbox().getReadyTimeout()));

                if (isCurrentImageAio()) {
                    builder.entrypoint("/opt/gem/run.sh");
                }

                SandboxAgent agent = builder.build();
                sandboxAgents.put(agent.getSandboxId(), agent);
                userSandboxMap.put(userId, agent.getSandboxId());
                sandboxUserMap.put(agent.getSandboxId(), userId);

                String endpoint = isCurrentImageAio() ? agent.getAioEndpoint() : null;
                linkSessionToSandbox(sessionId, agent.getSandboxId(), endpoint);

                log.info("为用户 {} 创建永久沙箱 {}（会话 {}）", userId, agent.getSandboxId(), sessionId);

                if (isCurrentImageAio()) {
                    aioSandboxStore.register(sessionId, agent.getSandboxId(), endpoint);
                    if (!initAioContext(sessionId, agent)) {
                        throw new IllegalStateException("AIO 服务未在启动窗口内就绪");
                    }
                    initAioDirectories(sessionId, endpoint);
                    restoreUserWorkspace(
                            userId,
                            sessionId,
                            new AioClient("http://" + endpoint));
                }
                syncAllEnabledSkills(sessionId);

            } catch (Exception e) {
                log.error("沙箱创建失败，用户 {} 会话 {}", userId, sessionId, e);
                throw new RuntimeException("沙箱创建失败：" + e.getMessage(), e);
            } finally {
                creationLocks.remove("user:" + userId);
            }
        }
    }

    private void linkSessionToSandbox(String sessionId, String sandboxId, String aioEndpoint) {
        sessionSandboxMap.put(sessionId, sandboxId);
        sessionTypeMap.put(sessionId, isCurrentImageAio());
        persistSandboxInfo(sessionId, sandboxId, aioEndpoint);
    }

    /**
     * 查找用户沙箱的 endpoint（优先从内存，其次从数据库）
     */
    private String findEndpointForUser(Long userId, String sandboxId) {
        // 防御性检查：sandboxId 为 null 时跳过内存查找
        if (sandboxId == null) {
            log.debug("findEndpointForUser: sandboxId 为 null，从数据库查找 userId={}", userId);
        } else {
            // 1. 从 aioSandboxStore 内存中查找
            String key = "__user_" + userId;
            if (aioSandboxStore.hasSandbox(key)) {
                return aioSandboxStore.getEndpoint(key);
            }
            // 2. 从 sandboxAgents 内存中查找（沙箱刚创建时在这里）
            SandboxAgent agent = sandboxAgents.get(sandboxId);
            if (agent != null) {
                String endpoint = agent.getAioEndpoint();
                if (endpoint != null && !endpoint.isBlank()) {
                    aioSandboxStore.register(key, sandboxId, endpoint);
                    return endpoint;
                }
            }
        }
        // 3. 从 UserSandboxEntity 数据库查找
        try {
            var userSandbox = userSandboxRepository.findByUserIdAndDeletedFalse(userId);
            if (userSandbox.isPresent()) {
                String endpoint = userSandbox.get().getAioEndpoint();
                if (endpoint != null && !endpoint.isBlank()) {
                    if (sandboxId != null) {
                        aioSandboxStore.register("__user_" + userId, sandboxId, endpoint);
                    }
                    return endpoint;
                }
            }
        } catch (Exception e) {
            log.warn("从数据库查找 endpoint 失败: userId={}", userId, e);
        }
        return null;
    }

    @Transactional
    public void persistSandboxInfo(String sessionId, String sandboxId, String aioEndpoint) {
        try {
            // 保存到会话表（只存 sandboxId）
            var session = sessionRepository.findById(sessionId);
            if (session.isPresent()) {
                ConversationSessionEntity entity = session.get();
                entity.setSandboxId(sandboxId);
                sessionRepository.save(entity);
            }
            // 保存到用户沙箱表（存 sandboxId + endpoint）
            Long userId = getUserIdForSession(sessionId);
            if (userId != null) {
                var userSandbox = userSandboxRepository.findByUserIdAndDeletedFalse(userId)
                        .orElse(new UserSandboxEntity(userId, sandboxId, aioEndpoint));
                userSandbox.setSandboxId(sandboxId);
                userSandbox.setDeleted(false);  // 确保不是软删除状态
                if (aioEndpoint != null && !aioEndpoint.isBlank()) {
                    userSandbox.setAioEndpoint(aioEndpoint);
                }
                userSandboxRepository.save(userSandbox);
            }
        } catch (Exception e) {
            log.warn("持久化沙箱信息失败: sessionId={}", sessionId, e);
        }
    }

    // ==================== 沙箱销毁 ====================

    @Override
    public void removeSandbox(String sessionId) {
        sessionSandboxMap.remove(sessionId);
        sessionTypeMap.remove(sessionId);
        if (isCurrentImageAio()) {
            aioSandboxStore.remove(sessionId);
        }
        clearSandboxRecord(sessionId);
    }

    private void destroyUserSandbox(Long userId) {
        String sandboxId = userSandboxMap.remove(userId);
        if (sandboxId == null) {
            return;
        }
        sandboxUserMap.remove(sandboxId);
        SandboxAgent agent = sandboxAgents.remove(sandboxId);
        if (agent != null) {
            try {
                agent.close();
                log.info("已销毁用户 {} 的沙箱 {}", userId, sandboxId);
            } catch (Exception e) {
                log.error("关闭沙箱失败: {}", e.getMessage());
            }
        }
    }

    @Transactional
    public void clearSandboxRecord(String sessionId) {
        try {
            var session = sessionRepository.findById(sessionId);
            if (session.isPresent()) {
                ConversationSessionEntity entity = session.get();
                entity.setSandboxId(null);
                sessionRepository.save(entity);
            }
        } catch (Exception e) {
            log.warn("清除沙箱记录失败: sessionId={}", sessionId, e);
        }
    }

    // ==================== 查询 ====================

    @Override
    public boolean hasSandbox(String sessionId) {
        // 1. 检查内存中的 session 映射
        String sandboxId = sessionSandboxMap.get(sessionId);
        if (sandboxId != null) {
            if (!isCurrentImageAio()) {
                return true;
            }
            try {
                String endpoint = getAioEndpoint(sessionId);
                if (endpoint != null && isSandboxHealthy(endpoint)) {
                    return true;
                }
                log.warn("会话 {} 绑定的 AIO 沙箱不可用，将触发重建", sessionId);
            } catch (Exception e) {
                log.debug("检查会话 AIO 沙箱失败: sessionId={}, reason={}", sessionId, e.getMessage());
            }
            sessionSandboxMap.remove(sessionId);
            sessionTypeMap.remove(sessionId);
        }

        // 2. 通过 userId 查找
        Long userId = getUserIdForSession(sessionId);
        if (userId != null && userSandboxMap.containsKey(userId)) {
            sandboxId = userSandboxMap.get(userId);
            if (!isCurrentImageAio()) {
                sessionSandboxMap.put(sessionId, sandboxId);
                return true;
            }
            String endpoint = findEndpointForUser(userId, sandboxId);
            if (endpoint != null && isSandboxHealthy(endpoint)) {
                sessionSandboxMap.put(sessionId, sandboxId);
                aioSandboxStore.register(sessionId, sandboxId, endpoint);
                return true;
            }
            log.warn("用户 {} 的 AIO 沙箱 {} 不可用，将触发重建", userId, sandboxId);
            userSandboxMap.remove(userId);
            sandboxUserMap.remove(sandboxId);
            aioSandboxStore.remove("__user_" + userId);
        }

        // 3. 检查 aioSandboxStore
        if (aioSandboxStore.hasSandbox(sessionId)) {
            String endpoint = aioSandboxStore.getEndpoint(sessionId);
            if (!isCurrentImageAio() || (endpoint != null && isSandboxHealthy(endpoint))) {
                return true;
            }
            aioSandboxStore.remove(sessionId);
        }

        return false;
    }

    /**
     * @deprecated 已废弃，AIO 模式下工具调用应通过 AioClient
     */
    @Deprecated
    public SandboxAgent getSandbox(String sessionId) {
        throw new UnsupportedOperationException("已废弃：AIO 模式下请使用 AioClient");
    }

    /**
     * @deprecated 已废弃，统一为 AIO 模式
     */
    @Deprecated
    @Override
    public boolean isAioSandbox(String sessionId) {
        return true;
    }

    // ==================== AIO ====================

    public AioClient getAioClient(String sessionId) {
        // 1. 从 aioSandboxStore 获取
        if (aioSandboxStore.hasSandbox(sessionId)) {
            return aioSandboxStore.getClient(sessionId);
        }

        // 2. 通过 userId 从 UserSandboxEntity 获取
        Long userId = getUserIdForSession(sessionId);
        if (userId != null) {
            String endpoint = findEndpointForUser(userId, userSandboxMap.get(userId));
            if (endpoint != null) {
                aioSandboxStore.register(sessionId, userSandboxMap.get(userId), endpoint);
                return new AioClient("http://" + endpoint);
            }
        }

        throw new SessionNotFoundException("No AIO sandbox for session: " + sessionId);
    }

    public String getAioEndpoint(String sessionId) {
        // 1. 从 aioSandboxStore 获取
        if (aioSandboxStore.hasSandbox(sessionId)) {
            return aioSandboxStore.getEndpoint(sessionId);
        }

        // 2. 通过 userId 从 UserSandboxEntity 获取
        Long userId = getUserIdForSession(sessionId);
        if (userId != null) {
            String endpoint = findEndpointForUser(userId, userSandboxMap.get(userId));
            if (endpoint != null) {
                return endpoint;
            }
        }

        throw new SessionNotFoundException("No AIO sandbox for session: " + sessionId);
    }

    private boolean isCurrentImageAio() {
        String image = config.getSandbox().getImage();
        if (image == null) {
            return false;
        }
        return image.contains("agent-infra/sandbox") || image.contains("all-in-one-sandbox");
    }

    /**
     * 等待新建 AIO 服务就绪并记录基础上下文。
     *
     * <p>连接拒绝、响应过早关闭和超时由 AIO 客户端在启动窗口内轮询处理；
     * 超过窗口仍不可用时返回 false，让创建流程停止后续目录初始化。</p>
     *
     * @param sessionId 会话 ID
     * @param agent     新建沙箱代理
     * @return AIO Shell API 在启动窗口内就绪时返回 true
     */
    private boolean initAioContext(String sessionId, SandboxAgent agent) {
        try {
            String endpoint = agent.getAioEndpoint();
            AioClient client = new AioClient("http://" + endpoint);

            log.info("等待 AIO 服务就绪，会话: {}", sessionId);
            if (!client.waitForReady()) {
                log.warn("AIO 服务未就绪，会话: {}", sessionId);
                return false;
            }

            try {
                SandboxClient.SandboxContext context = client.getContext();
                if (context != null) {
                    log.info("AIO 沙箱就绪 - 会话: {}, workspace: {}", sessionId, context.getWorkspace());
                }
            } catch (Exception e) {
                log.warn("获取 AIO 环境信息失败（不影响目录初始化），会话: {}, 原因: {}", sessionId, e.getMessage());
            }
            return true;
        } catch (Exception e) {
            log.warn("等待 AIO 服务就绪失败，会话: {}, 原因: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 初始化 AIO 沙箱目录结构和运行时辅助资源。
     *
     * <p>方法先等待 Shell API 就绪，再创建工作目录、写入 README、上传工具脚本并尝试安装
     * Browser Agent 运行时。服务未就绪或工作目录创建失败会抛出异常，因为后续文件恢复依赖这些目录；
     * README、工具脚本和浏览器运行时的非关键失败由各自方法降级记录。</p>
     *
     * @param sessionId 会话 ID
     * @param endpoint  AIO endpoint
     * @throws IllegalStateException 当 AIO 未就绪或工作目录创建失败时抛出
     */
    private void initAioDirectories(String sessionId, String endpoint) {
        AioClient client = new AioClient("http://" + endpoint);
        if (!client.waitForReady()) {
            throw new IllegalStateException("AIO 服务未就绪，无法初始化工作目录");
        }

        execShellWithRetry(client, AIO_WORKSPACE_DIRS_COMMAND, "创建 AIO 工作目录");
        log.info("AIO 沙箱目录已确认，会话: {}", sessionId);

        writeAioReadme(client, sessionId);
        uploadToolScript(client, "file_parser.py");
        installBrowserAgentRuntime(client, sessionId);
        log.info("AIO 沙箱初始化完成，会话: {}", sessionId);
    }

    /**
     * 写入 AIO 工作空间说明文件。
     *
     * <p>README 仅用于用户可读说明，写入失败不会阻断沙箱创建；目录创建才是工作空间可用的硬依赖。</p>
     *
     * @param client    当前 AIO 客户端
     * @param sessionId 会话 ID
     */
    private void writeAioReadme(AioClient client, String sessionId) {
        try {
            String readme = """
                    # 工作空间目录说明

                    - uploads/    - 用户上传文件
                    - workspace/  - Agent 工作目录
                    - output/     - 输出结果
                    - skills/     - 技能文件
                    - temp/       - 临时文件
                    - tools/      - 工具脚本

                    ## 文件解析工具

                    支持解析多种格式文件：
                    - PDF (.pdf)
                    - Word (.docx, .doc)  - .doc 需沙箱预装 catdoc
                    - Excel (.xlsx, .xls)
                    - PowerPoint (.pptx)

                    使用方法：
                    ```bash
                    # 解析文件（返回 JSON 格式）
                    python3 /home/gem/tools/file_parser.py parse /path/to/file.pdf
                    ```
                    """;
            String encoded = java.util.Base64.getEncoder().encodeToString(readme.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String writeCmd = "echo '" + encoded + "' | base64 -d > /home/gem/README.md";
            execShellWithRetry(client, writeCmd, "写入 AIO README");
            log.info("AIO 沙箱 README.md 已写入，会话: {}", sessionId);
        } catch (Exception e) {
            log.warn("AIO 沙箱 README.md 写入失败（不影响工作目录），会话: {}, 原因: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 带重试执行 AIO Shell 命令。
     *
     * <p>仅重试连接中止、响应过早关闭、服务刚启动等瞬时调用失败；命令返回非零退出码不会被视为可恢复成功，
     * 重试耗尽后抛出异常，让关键初始化失败可见，避免前端看到空工作空间却没有后端错误。</p>
     *
     * @param client    当前 AIO 客户端
     * @param command   Shell 命令
     * @param operation 操作说明，用于日志和异常信息
     * @return 最后一次成功的 Shell 执行结果
     */
    private ShellExecResult execShellWithRetry(AioClient client, String command, String operation) {
        Exception lastException = null;
        ShellExecResult lastResult = null;
        for (int attempt = 1; attempt <= AIO_INIT_RETRY_ATTEMPTS; attempt++) {
            try {
                ShellExecResult result = client.shell().exec(command);
                lastResult = result;
                if (result != null && result.isSuccess() && result.getExitCode() == 0) {
                    return result;
                }
                log.warn("{} 未成功，第 {} 次，exitCode={}, output={}",
                        operation, attempt, result != null ? result.getExitCode() : null,
                        result != null ? truncate(result.getOutput(), 300) : null);
            } catch (Exception e) {
                lastException = e;
                log.warn("{} 调用失败，第 {} 次，原因: {}", operation, attempt, e.getMessage());
            }
            if (attempt < AIO_INIT_RETRY_ATTEMPTS) {
                sleepBeforeRetry(attempt);
            }
        }

        if (lastException != null) {
            throw new IllegalStateException(operation + "失败: " + lastException.getMessage(), lastException);
        }
        throw new IllegalStateException(operation + "失败: exitCode="
                + (lastResult != null ? lastResult.getExitCode() : null)
                + ", output=" + (lastResult != null ? truncate(lastResult.getOutput(), 300) : null));
    }

    /**
     * 初始化重试前短暂等待。
     *
     * @param attempt 当前重试序号
     */
    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 AIO 初始化重试时被中断", e);
        }
    }

    /**
     * 截断日志文本，避免大输出刷屏。
     *
     * @param value 原始文本
     * @param max   最大长度
     * @return 截断后的文本
     */
    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    void restoreUserWorkspace(Long userId, String sessionId, AioClient client) {
        migrationService.migrateUser(userId);
        FileSyncService.SyncResult result = fileSyncService.syncUserWorkspace(userId, client);
        if (!result.allSucceeded()) {
            log.warn("用户工作空间恢复存在失败: userId={}, sessionId={}, failed={}",
                    userId, sessionId, result.failedPaths());
        } else {
            log.info("用户工作空间恢复完成: userId={}, sessionId={}, files={}",
                    userId, sessionId, result.successCount());
        }
    }

    /**
     * 上传工具脚本到沙箱
     */
    private void uploadToolScript(AioClient client, String scriptName) {
        try {
            // 从 resources/tools/ 读取脚本内容
            var inputStream = getClass().getResourceAsStream("/tools/" + scriptName);
            if (inputStream == null) {
                log.warn("工具脚本不存在: {}", scriptName);
                return;
            }
            try (inputStream) {
                String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String target = "/home/gem/tools/" + scriptName;
                if (!client.files().writeText(target, content)) {
                    throw new IllegalStateException("AIO 文件写入接口返回失败");
                }
                client.shell().exec("chmod +x '" + target + "'");
            }
        } catch (Exception e) {
            log.warn("上传工具脚本失败: {}", e.getMessage());
        }
    }

    /**
     * 为新建 AIO Sandbox 安装隐藏的 Browser Agent 运行时。
     *
     * <p>安装包含固定 Browser Agent 脚本和 playwright-core 依赖。目录不属于用户工作空间，
     * 不写入 README，也不参与用户文件恢复。目录创建、资源上传、npm 安装或验证失败均不重试，
     * 因为这些错误通常由网络、registry 或镜像环境导致；失败只禁用后续语义浏览器能力，
     * 不阻止 Shell、文件和知识库能力继续使用。</p>
     *
     * @param client    当前新建 Sandbox 的 AIO 客户端
     * @param sessionId 用于日志关联的会话 ID
     */
    private void installBrowserAgentRuntime(AioClient client, String sessionId) {
        String runtimeDir = "/home/gem/.runtime/browser-agent";
        try {
            client.shell().exec("mkdir -p '" + runtimeDir + "'");
            uploadRuntimeResource(
                    client,
                    "/tools/browser-agent/browser-agent.mjs",
                    runtimeDir + "/browser-agent.mjs");
            uploadRuntimeResource(
                    client,
                    "/tools/browser-agent/package.json",
                    runtimeDir + "/package.json");

            var install = client.shell().exec(
                    "cd '" + runtimeDir + "' && npm install --omit=dev --no-audit --no-fund");
            if (!install.isSuccess() || install.getExitCode() != 0) {
                log.warn("Browser Agent 依赖安装失败，会话: {}, output: {}",
                        sessionId, install.getOutput());
                return;
            }

            var verify = client.shell().exec(
                    "cd '" + runtimeDir
                            + "' && node -e \"Promise.all([import('playwright-core'), "
                            + "import('./browser-agent.mjs')]).then(() => console.log('ok'))\"");
            if (!verify.isSuccess() || verify.getExitCode() != 0) {
                log.warn("Browser Agent 依赖验证失败，会话: {}, output: {}",
                        sessionId, verify.getOutput());
                return;
            }
            log.info("Browser Agent 运行时已安装，会话: {}, 目录: {}", sessionId, runtimeDir);
        } catch (Exception e) {
            log.warn("Browser Agent 运行时初始化失败（不影响其他 Sandbox 能力），会话: {}, 原因: {}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * 将应用资源上传到 Sandbox 隐藏运行时目录。
     *
     * @param client       当前 Sandbox 的 AIO 客户端
     * @param resourcePath classpath 资源绝对路径
     * @param targetPath   Sandbox 内目标绝对路径
     * @throws IllegalStateException 当资源不存在或写入失败时抛出
     */
    private void uploadRuntimeResource(AioClient client, String resourcePath, String targetPath) {
        var inputStream = getClass().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IllegalStateException("运行时资源不存在: " + resourcePath);
        }
        try (inputStream) {
            String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!client.files().writeText(targetPath, content)) {
                throw new IllegalStateException("运行时资源写入失败: " + targetPath);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取运行时资源失败: " + resourcePath, e);
        }
    }

    // ==================== 技能同步 ====================

    /**
     * 沙箱启动时把所有已启用且来自本地仓库的 skill 推送到沙箱。
     *
     * <p>只推本地仓库存在的 skill；纯沙箱（如 Agent 之前下载过、但实际上沙箱重建的场景）需要重新下载。
     * 本地无副本的技能此处直接跳过，不视为失败。</p>
     *
     * @param sessionId 会话 ID
     */
    private void syncAllEnabledSkills(String sessionId) {
        Set<String> enabledSkillIds = getEnabledSkillIds(sessionId);
        for (String skillId : enabledSkillIds) {
            try {
                Skill skill = skillService.getSkill(skillId);
                if (skill.getLocalPath() == null) {
                    log.debug("技能 {} 无本地副本，跳过推送", skillId);
                    continue;
                }
                fileSyncService.syncSkill(sessionId, skill.getLocalPath(), skill.getId());
            } catch (Exception e) {
                log.warn("同步技能 {} 失败: {}", skillId, e.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public Set<String> getEnabledSkillIds(String sessionId) {
        try {
            var session = sessionRepository.findById(sessionId);
            if (session.isPresent()) {
                return session.get().getEnabledSkillIds();
            }
        } catch (Exception e) {
            log.warn("获取会话 {} 启用技能失败: {}", sessionId, e.getMessage());
        }
        return Set.of();
    }

    private Long getUserIdForSession(String sessionId) {
        try {
            var session = sessionRepository.findById(sessionId);
            return session.map(ConversationSessionEntity::getUserId).orElse(null);
        } catch (Exception e) {
            log.warn("获取会话 {} 对应用户失败: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 检查沙箱是否健康（快速检测，5 秒超时）
     */
    private boolean isSandboxHealthy(String endpoint) {
        try {
            AioClient client = new AioClient("http://" + endpoint);
            return client.shell().quickHealthCheck();
        } catch (Exception e) {
            log.debug("沙箱健康检查失败: endpoint={}, error={}", endpoint, e.getMessage());
            return false;
        }
    }

    /**
     * 清理不健康的沙箱记录（软删除）
     */
    @Transactional
    protected void cleanupUnhealthySandbox(Long userId, String sandboxId) {
        try {
            // 从内存映射中移除
            userSandboxMap.remove(userId);
            sandboxUserMap.remove(sandboxId);
            sandboxAgents.remove(sandboxId);

            // 软删除数据库记录
            var userSandbox = userSandboxRepository.findByUserIdAndDeletedFalse(userId);
            if (userSandbox.isPresent()) {
                userSandbox.get().setDeleted(true);
                userSandboxRepository.save(userSandbox.get());
            }

            // 清理会话表中的 sandboxId
            var sessions = sessionRepository.findAll();
            for (var session : sessions) {
                if (sandboxId.equals(session.getSandboxId())) {
                    session.setSandboxId(null);
                    sessionRepository.save(session);
                }
            }

            log.info("软删除不健康沙箱完成: userId={}, sandboxId={}", userId, sandboxId);
        } catch (Exception e) {
            log.error("软删除不健康沙箱失败: userId={}, sandboxId={}", userId, sandboxId, e);
        }
    }
}
