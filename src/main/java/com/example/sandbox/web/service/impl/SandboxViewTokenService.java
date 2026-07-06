package com.example.sandbox.web.service.impl;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱视图访问 token 服务。
 *
 * <p>认证接口负责签发短期 token，未认证的 iframe 代理入口只接收 token。
 * token 只绑定用户 ID，不固定 AIO endpoint；代理请求时再按用户读取当前最新 endpoint，
 * 避免沙箱重建或端口变化后继续访问旧地址。</p>
 */
@Service
public class SandboxViewTokenService {

    /** 默认 token 有效期。 */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /** token 随机数来源。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 系统时钟，测试中可替换。 */
    private final Clock clock;

    /** token 有效期。 */
    private final Duration ttl;

    /** token 到访问目标的内存映射。 */
    private final Map<String, SandboxViewTarget> targets = new ConcurrentHashMap<>();

    /**
     * 创建使用默认有效期和系统时钟的 token 服务。
     */
    public SandboxViewTokenService() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    /**
     * 创建可指定有效期和时钟的 token 服务。
     *
     * @param ttl token 有效期
     * @param clock 当前时钟
     */
    SandboxViewTokenService(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 签发沙箱视图 token。
     *
     * @param userId 用户 ID
     * @return 新 token
     */
    public String issue(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String token = newToken();
        targets.put(token, new SandboxViewTarget(userId, clock.instant().plus(ttl)));
        return token;
    }

    /**
     * 解析沙箱视图 token。
     *
     * <p>过期或不存在的 token 返回空，并会清理过期记录。</p>
     *
     * @param token 待解析 token
     * @return token 对应的访问目标
     */
    public Optional<SandboxViewTarget> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SandboxViewTarget target = targets.get(token);
        if (target == null) {
            return Optional.empty();
        }
        if (target.expiresAt().isBefore(clock.instant())) {
            targets.remove(token);
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /**
     * 生成 URL 安全的随机 token。
     *
     * @return URL 安全 token
     */
    private String newToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 沙箱视图 token 对应的访问目标。
     *
     * @param userId 用户 ID
     * @param expiresAt 过期时间
     */
    public record SandboxViewTarget(Long userId, Instant expiresAt) {
    }
}
