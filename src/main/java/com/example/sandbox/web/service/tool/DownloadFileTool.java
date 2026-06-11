package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下载文件工具 — 从沙箱下载文件（AIO 沙箱）
 *
 * <h3>用途</h3>
 * <p>让 LLM 能把沙箱里的文件提供给用户：</p>
 * <ul>
 *   <li>生成的报告、代码、图片等</li>
 *   <li>处理后的数据文件</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>path — 沙箱中的文件路径</li>
 * </ul>
 *
 * <h3>返回值</h3>
 * <p>返回 Markdown 格式：</p>
 * <ul>
 *   <li>图片类型（png/jpg）— 直接在聊天窗口中显示</li>
 *   <li>其他类型 — 提供下载链接，用户可手动下载</li>
 * </ul>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class DownloadFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DownloadFileTool.class);
    private static final String NAME = "download_file";

    @Autowired
    private SandboxClientFactory factory;

    @Value("${app.base-url:http://localhost:8081}")
    private String baseUrl;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "沙箱中的文件路径"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("path")
        );

        return new ToolDefinition(
                NAME,
                "下载沙箱中的文件。返回文件的 base64 编码内容和下载链接。",
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = arguments != null ? (String) arguments.get("path") : null;
        if (path == null || path.isBlank()) {
            return "错误：文件路径不能为空";
        }

        try {
            var client = factory.getAioClient(sessionId);
            byte[] content = client.downloadFile(path);

            if (content == null || content.length == 0) {
                return "错误：文件为空或不存在: " + path;
            }

            String base64 = Base64.getEncoder().encodeToString(content);
            String encodedPath = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);

            log.info("下载文件成功: {} ({} bytes)", path, content.length);
            String downloadUrl = "/api/sessions/" + sessionId + "/files/download?path=" + encodedPath;

            // 判断是否为图片
            boolean isImage = path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif");
            StringBuilder result = new StringBuilder();
            result.append("文件下载成功！\n");
            result.append("路径: ").append(path).append("\n");
            result.append("大小: ").append(content.length).append(" bytes\n\n");

            if (isImage) {
                result.append("![").append(path).append("](").append(downloadUrl).append(")\n\n");
            }

            result.append("下载链接: ").append(downloadUrl);
            return result.toString();
        } catch (Exception e) {
            log.error("下载文件失败: {}", path, e);
            return "下载失败：" + e.getMessage();
        }
    }
}
