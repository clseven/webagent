package com.example.sandbox.web.service.impl;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话内文件认知状态服务（State Checks 的存储层）。
 *
 * <h3>职责</h3>
 * <p>按会话维护"模型上次读取每个文件时的内容 hash"。read 后由 Post Hook 存入，
 * write 前由 Pre Hook 比对（re-read 现场算 hash 与此对比判断是否过时），write 后清除。
 * 不追踪中间是谁改的文件（shell / 并发工具 / 外部），只在写的那一刻现场验证。</p>
 *
 * <h3>生命周期与边界</h3>
 * <ul>
 *   <li>随会话存在于内存，按 {@code sessionId} 隔离，会话结束可整体清理。</li>
 *   <li>每会话记录数设上限 {@link #MAX_ENTRIES_PER_SESSION}，按 LRU 淘汰最旧，避免长会话无限增长。</li>
 *   <li>hash 仅用于"内容是否变化"的相等判断，不做安全用途；SHA-256 足够且稳定。</li>
 * </ul>
 */
@Service
public class FileCognitionState {

    /** 每会话最多记录的文件数，超出按 LRU 淘汰最旧记录。 */
    static final int MAX_ENTRIES_PER_SESSION = 256;

    /** 会话 ID → { 文件路径 → 上次 read 时的内容 hash }。 */
    private final Map<String, Map<String, String>> states = new ConcurrentHashMap<>();

    /**
     * 记录某文件在本会话的最新内容 hash（read 后或写前 re-read 通过后调用）。
     *
     * @param sessionId 会话 ID
     * @param path      文件路径
     * @param content   文件内容
     */
    public void remember(String sessionId, String path, String content) {
        if (isBlank(sessionId) || isBlank(path) || content == null) {
            return;
        }
        Map<String, String> perSession = states.computeIfAbsent(sessionId, k -> newLruMap());
        synchronized (perSession) {
            perSession.put(path, hash(content));
        }
    }

    /**
     * 获取某文件在本会话记录的 hash。
     *
     * @param sessionId 会话 ID
     * @param path      文件路径
     * @return 记录的 hash；未读过时为空
     */
    public Optional<String> getHash(String sessionId, String path) {
        if (isBlank(sessionId) || isBlank(path)) {
            return Optional.empty();
        }
        Map<String, String> perSession = states.get(sessionId);
        if (perSession == null) {
            return Optional.empty();
        }
        synchronized (perSession) {
            return Optional.ofNullable(perSession.get(path));
        }
    }

    /**
     * 判断某文件在本会话是否读过（有 hash 记录）。
     *
     * @param sessionId 会话 ID
     * @param path      文件路径
     * @return true 表示读过
     */
    public boolean hasRead(String sessionId, String path) {
        return getHash(sessionId, path).isPresent();
    }

    /**
     * 清除某文件的记录（write 后调用，使旧认知作废，下次改需重读）。
     *
     * @param sessionId 会话 ID
     * @param path      文件路径
     */
    public void invalidate(String sessionId, String path) {
        if (isBlank(sessionId) || isBlank(path)) {
            return;
        }
        Map<String, String> perSession = states.get(sessionId);
        if (perSession == null) {
            return;
        }
        synchronized (perSession) {
            perSession.remove(path);
        }
    }

    /**
     * 清理整个会话的文件认知（会话结束时调用）。
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        if (!isBlank(sessionId)) {
            states.remove(sessionId);
        }
    }

    /**
     * 计算内容 hash。
     *
     * @param content 文件内容
     * @return 十六进制 SHA-256；算法缺失时退化为长度+hashCode，保证仍能相等比较
     */
    static String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return content.length() + ":" + content.hashCode();
        }
    }

    /**
     * 创建带 LRU 淘汰的每会话 map（access-order，超上限移除最旧）。
     *
     * @return 线程安全包装的 LRU map（外部访问仍需在 perSession 上同步）
     */
    private Map<String, String> newLruMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MAX_ENTRIES_PER_SESSION;
            }
        });
    }

    /**
     * 判断字符串是否为空白。
     *
     * @param value 字符串
     * @return true 表示 null 或全空白
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
