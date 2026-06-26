package com.example.sandbox.web.service.tool;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级图片字节缓冲区。
 *
 * <p>作为 {@code ViewImageTool} 与 {@code PostToolUseHook} 之间的数据桥梁：
 * 工具执行时将下载好的图片字节存入，Hook 执行后立即取走并构造多模态消息。</p>
 *
 * <p>设计原则：每个 sessionId 只保留最近一次 {@code put} 的图片；
 * {@link #take} 是"消费型"读取，取走后自动清除，避免同一张图被重复注入。</p>
 */
@Component
public class ImageBuffer {

    private final ConcurrentHashMap<String, Entry> buffer = new ConcurrentHashMap<>();

    /**
     * 存入图片字节。若同一 session 已有未消费的图片，直接覆盖。
     *
     * @param sessionId 会话 ID
     * @param path      沙箱内文件路径（用于日志和消息文字）
     * @param bytes     图片原始字节
     * @param mimeType  MIME 类型，如 "image/png"
     */
    public void put(String sessionId, String path, byte[] bytes, String mimeType) {
        buffer.put(sessionId, new Entry(path, bytes, mimeType));
    }

    /**
     * 取出并移除图片（消费型读取）。无数据时返回 null。
     *
     * @param sessionId 会话 ID
     * @return 图片条目，或 null
     */
    public Entry take(String sessionId) {
        return buffer.remove(sessionId);
    }

    /**
     * 图片条目。
     *
     * @param path     沙箱内文件路径
     * @param bytes    图片原始字节
     * @param mimeType MIME 类型
     */
    public record Entry(String path, byte[] bytes, String mimeType) {
    }
}
