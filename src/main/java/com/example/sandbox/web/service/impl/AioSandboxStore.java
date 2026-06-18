package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.entity.UserSandboxEntity;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.repository.UserSandboxRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AIO 沙箱映射存储。
 *
 * <p>负责维护 sessionId/userId 与 AIO endpoint 的内存映射，并在应用启动时从数据库恢复。
 * 启动恢复阶段只恢复健康的未删除记录，避免不可达旧 endpoint 阻止后续重新创建沙箱。</p>
 */
@Component
public class AioSandboxStore {

    /** 记录沙箱映射恢复、注册和移除行为。 */
    private static final Logger log = LoggerFactory.getLogger(AioSandboxStore.class);

    /** 内存映射：sessionId 或用户级 key → aioEndpoint。 */
    private final Map<String, String> sessionEndpoints = new ConcurrentHashMap<>();

    /** 内存映射：sandboxId → sessionId 或用户级 key，用于反向查找。 */
    private final Map<String, String> sandboxToSession = new ConcurrentHashMap<>();

    /** 会话仓库，用于清理会话上的沙箱绑定。 */
    @Autowired
    private ConversationSessionRepository sessionRepository;

    /** 用户沙箱仓库，用于恢复用户级永久沙箱绑定。 */
    @Autowired
    private UserSandboxRepository userSandboxRepository;

    /**
     * 应用启动时恢复 AIO 沙箱映射。
     *
     * <p>这里不重新激活 deleted=true 的记录，也不恢复健康检查失败的 endpoint。
     * 这样旧容器已停止或端口不可达时，创建流程可以正常进入重建路径。</p>
     */
    @PostConstruct
    public void restore() {
        log.info("开始恢复 AIO 沙箱映射...");
        try {
            var userSandboxes = userSandboxRepository.findByDeletedFalse();
            int restored = 0;
            int unhealthy = 0;
            int skipped = 0;

            for (UserSandboxEntity userSandbox : userSandboxes) {
                Long userId = userSandbox.getUserId();
                String sandboxId = userSandbox.getSandboxId();
                String endpoint = userSandbox.getAioEndpoint();

                if (userId == null || endpoint == null || endpoint.isBlank()) {
                    skipped++;
                    log.debug("用户沙箱记录缺少 userId 或 endpoint，跳过: userId={}, sandboxId={}", userId, sandboxId);
                    continue;
                }

                if (!checkHealth(endpoint)) {
                    unhealthy++;
                    log.warn("AIO 沙箱启动恢复时暂不可达，跳过内存映射，后续请求将重建: userId={}, sandboxId={}, endpoint={}",
                            userId, sandboxId, endpoint);
                    continue;
                }

                String key = "__user_" + userId;
                sessionEndpoints.put(key, endpoint);
                if (sandboxId != null && !sandboxId.isBlank()) {
                    sandboxToSession.put(sandboxId, key);
                }

                restored++;
                log.debug("恢复沙箱映射: userId={}, sandboxId={}, endpoint={}", userId, sandboxId, endpoint);
            }

            log.info("AIO 沙箱恢复完成: 恢复 {} 个，暂不可达 {} 个，跳过 {} 个，当前活跃 {} 个",
                    restored, unhealthy, skipped, sessionEndpoints.size());
        } catch (Exception e) {
            log.error("恢复 AIO 沙箱映射失败", e);
        }
    }

    /**
     * 注册沙箱映射。
     *
     * @param sessionId 会话 ID 或用户级 key
     * @param sandboxId 沙箱 ID
     * @param endpoint  AIO endpoint
     */
    public void register(String sessionId, String sandboxId, String endpoint) {
        sessionEndpoints.put(sessionId, endpoint);
        if (sandboxId != null) {
            sandboxToSession.put(sandboxId, sessionId);
        }
        log.info("注册沙箱: sessionId={}, sandboxId={}, endpoint={}", sessionId, sandboxId, endpoint);
    }

    /**
     * 移除指定会话的沙箱映射。
     *
     * @param sessionId 会话 ID
     */
    public void remove(String sessionId) {
        String endpoint = sessionEndpoints.remove(sessionId);
        if (endpoint != null) {
            sandboxToSession.values().removeIf(sid -> sessionId.equals(sid));
        }
        log.info("移除沙箱: sessionId={}", sessionId);
    }

    /**
     * 检查会话是否已有沙箱映射。
     *
     * @param sessionId 会话 ID 或用户级 key
     * @return 存在映射返回 true
     */
    public boolean hasSandbox(String sessionId) {
        return sessionEndpoints.containsKey(sessionId);
    }

    /**
     * 获取沙箱 endpoint。
     *
     * @param sessionId 会话 ID 或用户级 key
     * @return AIO endpoint，不存在时返回 null
     */
    public String getEndpoint(String sessionId) {
        return sessionEndpoints.get(sessionId);
    }

    /**
     * 获取所有 session/key 到 endpoint 的映射。
     *
     * @return 当前内存映射
     */
    public Map<String, String> getAllEndpoints() {
        return sessionEndpoints;
    }

    /**
     * 获取 AIO 客户端。
     *
     * @param sessionId 会话 ID 或用户级 key
     * @return AIO 客户端
     * @throws RuntimeException 当没有映射时抛出
     */
    public AioClient getClient(String sessionId) {
        String endpoint = sessionEndpoints.get(sessionId);
        if (endpoint == null) {
            throw new RuntimeException("No AIO sandbox for session: " + sessionId);
        }
        return new AioClient("http://" + endpoint);
    }

    /**
     * 快速检查沙箱健康状态。
     *
     * <p>该检查只用于启动恢复日志，不会触发删除。连接拒绝、超时或 HTTP 错误都返回 false，
     * 因为这些失败在后端刚启动时可能只是 AIO 端口尚未恢复。</p>
     *
     * @param endpoint AIO endpoint
     * @return 单次检查成功返回 true
     */
    private boolean checkHealth(String endpoint) {
        try {
            AioClient client = new AioClient("http://" + endpoint);
            return client.shell().quickHealthCheck();
        } catch (Exception e) {
            log.debug("沙箱健康检查失败: endpoint={}, error={}", endpoint, e.getMessage());
            return false;
        }
    }

    /**
     * 清除数据库中的会话沙箱记录。
     *
     * @param sessionId 会话 ID
     */
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
}
