package com.example.sandbox.web.service.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级图片字节缓冲区。
 *
 * <p>作为图片类工具（{@code ViewImageTool}、MCP 图片工具）与 {@code PostToolUseHook} 之间的
 * 数据桥梁：工具执行时将下载好的图片字节存入，Hook 执行后立即取走并构造多模态消息。</p>
 *
 * <p>设计原则：每个 sessionId 维护一个图片列表，支持单次工具返回多张图片（例如 MCP 工具
 * 一次返回多张 {@code ImageContent}）。{@link #drain} 是"消费型"读取，取走全部并清空，
 * 避免同一张图被重复注入，也避免上一轮残留图片串到下一轮工具。</p>
 */
@Component
public class ImageBuffer {

    private final ConcurrentHashMap<String, List<Entry>> buffer = new ConcurrentHashMap<>();

    /**
     * 追加一张图片到会话缓冲区。支持同一会话多次 {@code append}（多图场景）。
     *
     * @param sessionId 会话 ID
     * @param path      沙箱内文件路径或来源标签（用于日志和消息文字）
     * @param bytes     图片原始字节
     * @param mimeType  MIME 类型，如 "image/png"
     */
    public void append(String sessionId, String path, byte[] bytes, String mimeType) {
        buffer.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Entry(path, bytes, mimeType));
    }

    /**
     * 取出并清空该会话的全部图片（消费型读取）。无数据时返回空列表。
     *
     * @param sessionId 会话 ID
     * @return 图片条目列表（可能为空，不会为 null）
     */
    public List<Entry> drain(String sessionId) {
        List<Entry> entries = buffer.remove(sessionId);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * 图片条目。
     *
     * @param path     沙箱内文件路径或来源标签
     * @param bytes    图片原始字节
     * @param mimeType MIME 类型
     */
    public record Entry(String path, byte[] bytes, String mimeType) {
    }
}
