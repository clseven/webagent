package com.example.sandbox.web.service.impl;

import com.example.sandbox.agent.SandboxAgent;
import com.example.sandbox.aio.AioSandboxClient;
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
                    // 检查沙箱是否健康（快速检测）
                    boolean healthy = isSandboxHealthy(endpoint);
                    if (!healthy) {
                        log.warn("沙箱不健康但仍恢复映射: userId={}, sandboxId={}, endpoint={}", userId, sandboxId, endpoint);
                        unhealthy++;
                    }
                    // 无论是否健康，都恢复内存映射，后续请求时再处理
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
                    initAioContext(sessionId, agent);
                    initAioDirectories(sessionId, agent);
                    restoreUserWorkspace(
                            userId,
                            sessionId,
                            new AioSandboxClient("http://" + endpoint));
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
            return true;
        }

        // 2. 通过 userId 查找
        Long userId = getUserIdForSession(sessionId);
        if (userId != null && userSandboxMap.containsKey(userId)) {
            sandboxId = userSandboxMap.get(userId);
            sessionSandboxMap.put(sessionId, sandboxId);
            return true;
        }

        // 3. 检查 aioSandboxStore
        if (aioSandboxStore.hasSandbox(sessionId)) {
            return true;
        }

        return false;
    }

    /**
     * @deprecated 已废弃，AIO 模式下工具调用应通过 AioSandboxClient
     */
    @Deprecated
    public SandboxAgent getSandbox(String sessionId) {
        throw new UnsupportedOperationException("已废弃：AIO 模式下请使用 AioSandboxClient");
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

    public AioSandboxClient getAioClient(String sessionId) {
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
                return new AioSandboxClient("http://" + endpoint);
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

    private void initAioContext(String sessionId, SandboxAgent agent) {
        try {
            String endpoint = agent.getAioEndpoint();
            AioSandboxClient client = new AioSandboxClient("http://" + endpoint);

            log.info("等待 AIO 服务就绪，会话: {}", sessionId);
            if (!client.waitForReady()) {
                log.warn("AIO 服务未就绪，会话: {}", sessionId);
                return;
            }

            SandboxClient.SandboxContext context = client.getContext();
            if (context != null) {
                log.info("AIO 沙箱就绪 - 会话: {}, workspace: {}", sessionId, context.getWorkspace());
            }
        } catch (Exception e) {
            log.warn("获取 AIO 环境信息失败（不影响使用），会话: {}, 原因: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 初始化 AIO 沙箱目录结构
     */
    private void initAioDirectories(String sessionId, SandboxAgent agent) {
        try {
            String endpoint = agent.getAioEndpoint();
            AioSandboxClient client = new AioSandboxClient("http://" + endpoint);

            // 创建目录结构
            String mkdirCmd = "mkdir -p /home/gem/{uploads,workspace,output,skills,temp,tools}";
            client.shellExec(mkdirCmd);
            log.info("AIO 沙箱目录已创建，会话: {}", sessionId);

            // 写入 README.md
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
            client.shellExec(writeCmd);
            log.info("AIO 沙箱 README.md 已写入，会话: {}", sessionId);

            // 上传 file_parser.py 工具脚本
            uploadToolScript(client, "file_parser.py");
            log.info("AIO 沙箱工具脚本已上传，会话: {}", sessionId);
        } catch (Exception e) {
            log.warn("初始化 AIO 沙箱目录失败（不影响使用），会话: {}, 原因: {}", sessionId, e.getMessage());
        }
    }

    void restoreUserWorkspace(Long userId, String sessionId, AioSandboxClient client) {
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
    private void uploadToolScript(AioSandboxClient client, String scriptName) {
        try {
            // 从 resources/tools/ 读取脚本内容
            var inputStream = getClass().getResourceAsStream("/tools/" + scriptName);
            if (inputStream == null) {
                log.warn("工具脚本不存在: {}", scriptName);
                return;
            }
            String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            inputStream.close();

            // 上传到沙箱
            String encoded = java.util.Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String writeCmd = "echo '" + encoded + "' | base64 -d > /home/gem/tools/" + scriptName;
            client.shellExec(writeCmd);

            // 设置执行权限
            client.shellExec("chmod +x /home/gem/tools/" + scriptName);
        } catch (Exception e) {
            log.warn("上传工具脚本失败: {}", e.getMessage());
        }
    }

    // ==================== 技能同步 ====================

    private void syncAllEnabledSkills(String sessionId) {
        Set<String> enabledSkillIds = getEnabledSkillIds(sessionId);
        for (String skillId : enabledSkillIds) {
            try {
                Skill skill = skillService.getSkill(skillId);
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
            AioSandboxClient client = new AioSandboxClient("http://" + endpoint);
            return client.quickHealthCheck();
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
