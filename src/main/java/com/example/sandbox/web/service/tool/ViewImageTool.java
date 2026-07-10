package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查看图片工具 — 加载沙箱内的图片供 LLM 分析
 *
 * <h3>用途</h3>
 * <p>当 LLM 需要分析沙箱内的图片时主动调用此工具。工具负责从沙箱下载图片字节并存入
 * {@link ImageBuffer}，随后 {@code PostToolUseHook} 将其注入对话消息，
 * 使下一轮 LLM 调用能直接"看到"该图片。</p>
 *
 * <h3>多图支持</h3>
 * <p>参数 {@code paths} 支持一次传入多个路径。同一批图片会在同一次视觉 Hook 中一起喂给
 * 视觉模型，使其能跨图推理（例如对比多张截图）。只传一个路径时行为与单图一致。</p>
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
        Map<String, Object> pathProperties = Map.of(
                "type", "string",
                "description", "沙箱内图片文件的绝对路径，例如 /home/gem/uploads/photo.png"
        );

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("paths", Map.of(
                "type", "array",
                "items", pathProperties,
                "description", "要查看的图片路径列表，支持一次传入多张以跨图推理；只传一个路径时等同单图查看",
                "minItems", 1
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("paths"),
                "additionalProperties", false
        );

        return new ToolDefinition(
                NAME,
                """
                        加载沙箱内的图片文件并让模型直接查看其内容。
                        当用户要求分析、识别或描述沙箱内图片时调用。
                        支持一次传入多个路径（paths 数组），多张图会在同一轮视觉观察中一起分析，便于跨图对照。
                        支持 PNG、JPG、JPEG、GIF、WEBP 等常见格式。
                        """,
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        List<String> paths = parsePaths(arguments.get("paths"));
        if (paths.isEmpty()) {
            return "错误：paths 不能为空";
        }

        try {
            var client = factory.getAioClient(sessionId);
            if (!client.waitForReady()) {
                return "错误：AIO 服务未就绪，请稍后重试";
            }

            List<String> loaded = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (String path : paths) {
                try {
                    byte[] bytes = client.files().download(path);
                    if (bytes == null || bytes.length == 0) {
                        failed.add(path + "（文件不存在或为空）");
                        continue;
                    }
                    String mimeType = resolveMimeType(path);
                    imageBuffer.append(sessionId, path, bytes, mimeType);
                    loaded.add(path + "（" + bytes.length + " bytes, " + mimeType + "）");
                    log.info("图片已加载到缓冲区: path={} size={} bytes mimeType={}", path, bytes.length, mimeType);
                } catch (Exception e) {
                    log.warn("加载单张图片失败: path={}", path, e);
                    failed.add(path + "（" + e.getMessage() + "）");
                }
            }

            StringBuilder result = new StringBuilder();
            if (!loaded.isEmpty()) {
                result.append("已加载 ").append(loaded.size()).append(" 张图片，正在分析：\n");
                loaded.forEach(p -> result.append("- ").append(p).append("\n"));
            }
            if (!failed.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append("\n");
                }
                result.append("加载失败的图片：\n");
                failed.forEach(p -> result.append("- ").append(p).append("\n"));
            }
            if (loaded.isEmpty()) {
                result.insert(0, "未成功加载任何图片。\n");
            } else if (loaded.size() > 1) {
                result.append("\n[共 ").append(loaded.size())
                        .append(" 张图片，视觉观察将在下一条消息一起提供]");
            }
            return result.toString().trim();
        } catch (Exception e) {
            log.error("加载图片失败: paths={}", paths, e);
            return "加载图片失败：" + e.getMessage();
        }
    }

    /**
     * 解析 paths 参数，兼容数组、单个字符串、逗号分隔。
     */
    @SuppressWarnings("unchecked")
    private List<String> parsePaths(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> paths = new ArrayList<>();
            for (Object item : list) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) {
                    paths.add(s);
                }
            }
            return paths;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return List.of();
        }
        return List.of(s);
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
