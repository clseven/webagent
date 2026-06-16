package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.model.entity.UserSandboxEntity;
import com.example.sandbox.web.repository.UserSandboxRepository;
import com.example.sandbox.web.service.SandboxClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 沙箱客户端工厂
 *
 * <p>统一返回 AIO 沙箱客户端。</p>
 *
 * @author example
 * @date 2026/05/20
 */
@Component
public class SandboxClientFactory {

    private static final Logger log = LoggerFactory.getLogger(SandboxClientFactory.class);

    @Autowired
    private SandboxServiceImpl sandboxService;

    @Autowired
    private UserSandboxRepository userSandboxRepository;

    /**
     * 获取会话对应的沙箱客户端（统一返回 AIO 客户端）
     *
     * @param sessionId 会话 ID
     * @return AIO 沙箱客户端
     */
    public SandboxClient getClient(String sessionId) {
        return getAioClient(sessionId);
    }

    /**
     * 获取 AIO 沙箱客户端
     */
    public AioClient getAioClient(String sessionId) {
        return sandboxService.getAioClient(sessionId);
    }

    /**
     * 根据用户 ID 获取 AIO 沙箱客户端
     * <p>用于知识库等只有 userId 没有 sessionId 的场景</p>
     *
     * @param userId 用户 ID
     * @return AIO 沙箱客户端，若用户无沙箱则返回 null
     */
    public AioClient getAioClientByUserId(Long userId) {
        if (userId == null) return null;
        try {
            UserSandboxEntity entity = userSandboxRepository.findByUserIdAndDeletedFalse(userId).orElse(null);
            if (entity == null) {
                log.warn("用户 {} 尚未创建沙箱", userId);
                return null;
            }
            String endpoint = entity.getAioEndpoint();
            if (endpoint == null || endpoint.isBlank()) {
                log.warn("用户 {} 沙箱 endpoint 为空", userId);
                return null;
            }
            return new AioClient("http://" + endpoint);
        } catch (Exception e) {
            log.error("根据 userId 获取 AIO 客户端失败: userId={}", userId, e);
            return null;
        }
    }
}
