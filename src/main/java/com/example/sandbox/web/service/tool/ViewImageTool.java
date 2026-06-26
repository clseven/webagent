package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查看图片工具 — 加载沙箱内的图片供 LLM 分析
 *
 * <h3>用途</h3>
 * <p>当 LLM 需要分析沙箱内的图片时主动调用此工具。工具负责从沙箱下载图片字节并存入
 * {@link ImageBuffer}，随后 {@code PostToolUseHook} 将其注入对话消息，
 * 使下一轮 LLM 调用能直接"看到"该图片。</p>
 *
 * <h3>适用格式</h3>
 * <p>PNG、JPG、JPEG、GIF、WEBP 等常见图片格式。</p>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class ViewImageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ViewImageTool.class);
    private static final String NAME = "view_image";

    @Autowired
    private SandboxClientFactory factory;

    @Autowired
    private ImageBuffer imageBuffer;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "沙箱内图片文件的绝对路径，例如 /home/gem/uploads/photo.png"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("path"),
                "additionalProperties", false
        );

        return new ToolDefinition(
                NAME,
                """
                        加载沙箱内的图片文件并让模型直接查看其内容。
                        当用户要求分析、识别或描述沙箱内某张图片时调用。
                        支持 PNG、JPG、JPEG、GIF、WEBP 等常见格式。
                        """,
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }

        try {
            var client = factory.getAioClient(sessionId);
            if (!client.waitForReady()) {
                return "错误：AIO 服务未就绪，请稍后重试";
            }

            byte[] bytes = client.files().download(path);
            if (bytes == null || bytes.length == 0) {
                return "错误：文件不存在或内容为空：" + path;
            }

            String mimeType = resolveMimeType(path);
            imageBuffer.put(sessionId, path, bytes, mimeType);

            log.info("图片已加载到缓冲区: path={} size={} bytes mimeType={}", path, bytes.length, mimeType);
            return "图片已加载（" + bytes.length + " bytes），正在分析：" + path;

        } catch (Exception e) {
            log.error("加载图片失败: path={}", path, e);
            return "加载图片失败：" + e.getMessage();
        }
    }

    /**
     * 根据文件扩展名推断 MIME 类型。
     */
    private String resolveMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        return "image/png"; // 默认 PNG
    }
}
