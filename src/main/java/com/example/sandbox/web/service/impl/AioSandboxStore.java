package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioSandboxClient;
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
 * AIO 沙箱存储
 *
 * <p>管理 sessionId → endpoint 的映射，支持持久化和启动恢复。</p>
 *
 * @author example
 * @date 2026/05/21
 */
@Component
public class AioSandboxStore {

    private static final Logger log = LoggerFactory.getLogger(AioSandboxStore.class);

    /**
     * 内存映射：sessionId → aioEndpoint
     */
    private final Map<String, String> sessionEndpoints = new ConcurrentHashMap<>();

    /**
     * 内存映射：sandboxId → sessionId（用于反向查找）
     */
    private final Map<String, String> sandboxToSession = new ConcurrentHashMap<>();

    @Autowired
    private ConversationSessionRepository sessionRepository;

    @Autowired
    private UserSandboxRepository userSandboxRepository;

    /**
     * 应用启动时从 UserSandboxEntity 恢复映射
     * <p>
     * 同时清理数据库中不健康的沙箱记录
     */
    @PostConstruct
    public void restore() {
        log.info("开始恢复 AIO 沙箱映射...");
        try {
            var userSandboxes = userSandboxRepository.findAll();
            int restored = 0;
            int cleaned = 0;

            for (UserSandboxEntity userSandbox : userSandboxes) {
                String sandboxId = userSandbox.getSandboxId();
                String endpoint = userSandbox.getAioEndpoint();

                if (endpoint == null || endpoint.isBlank()) {
                    log.debug("用户 {} 缺少 endpoint，跳过", userSandbox.getUserId());
                    continue;
                }

                // 检查沙箱是否健康
                if (checkHealth(endpoint)) {
                    // 使用 "__user_" + userId 作为 key，供 SandboxServiceImpl 使用
                    String key = "__user_" + userSandbox.getUserId();
                    sessionEndpoints.put(key, endpoint);
                    if (sandboxId != null) {
                        sandboxToSession.put(sandboxId, key);
                    }
                    restored++;
                    log.debug("恢复沙箱: userId={}, sandboxId={}, endpoint={}", userSandbox.getUserId(), sandboxId, endpoint);
                } else {
                    // 沙箱不健康，清理数据库记录
                    log.warn("沙箱不健康，清理记录: userId={}, sandboxId={}, endpoint={}", userSandbox.getUserId(), sandboxId, endpoint);
                    try {
                        userSandboxRepository.delete(userSandbox);
                        cleaned++;
                    } catch (Exception e) {
                        log.error("清理不健康沙箱记录失败: userId={}", userSandbox.getUserId(), e);
                    }
                }
            }

            log.info("AIO 沙箱恢复完成: 恢复 {} 个，清理 {} 个不健康记录，当前活跃 {} 个", restored, cleaned, sessionEndpoints.size());
        } catch (Exception e) {
            log.error("恢复 AIO 沙箱映射失败", e);
        }
    }

    /**
     * 注册沙箱
     */
    public void register(String sessionId, String sandboxId, String endpoint) {
        sessionEndpoints.put(sessionId, endpoint);
        if (sandboxId != null) {
            sandboxToSession.put(sandboxId, sessionId);
        }
        log.info("注册沙箱: sessionId={}, sandboxId={}, endpoint={}", sessionId, sandboxId, endpoint);
    }

    /**
     * 移除沙箱
     */
    public void remove(String sessionId) {
        String endpoint = sessionEndpoints.remove(sessionId);
        if (endpoint != null) {
            sandboxToSession.values().removeIf(sid -> sessionId.equals(sid));
        }
        log.info("移除沙箱: sessionId={}", sessionId);
    }

    /**
     * 检查会话是否有沙箱
     */
    public boolean hasSandbox(String sessionId) {
        return sessionEndpoints.containsKey(sessionId);
    }

    /**
     * 获取沙箱 endpoint
     */
    public String getEndpoint(String sessionId) {
        return sessionEndpoints.get(sessionId);
    }

    /**
     * 获取所有 session → endpoint 映射
     */
    public Map<String, String> getAllEndpoints() {
        return sessionEndpoints;
    }

    /**
     * 获取 AIO 客户端
     */
    public AioSandboxClient getClient(String sessionId) {
        String endpoint = sessionEndpoints.get(sessionId);
        if (endpoint == null) {
            throw new RuntimeException("No AIO sandbox for session: " + sessionId);
        }
        return new AioSandboxClient("http://" + endpoint);
    }

    /**
     * 检查沙箱健康
     */
    private boolean checkHealth(String endpoint) {
        try {
            AioSandboxClient client = new AioSandboxClient("http://" + endpoint);
            return client.isReady();
        } catch (Exception e) {
            log.debug("沙箱健康检查失败: endpoint={}, error={}", endpoint, e.getMessage());
            return false;
        }
    }

    /**
     * 清除数据库中的沙箱记录
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
