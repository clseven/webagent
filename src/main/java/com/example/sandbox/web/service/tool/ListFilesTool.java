package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出文件工具 — 查看沙箱中目录的内容（AIO 沙箱）
 *
 * <h3>用途</h3>
 * <p>让 LLM 能浏览沙箱文件系统，通常作为"找文件"的第一步：</p>
 * <ol>
 *   <li>先 list_files 查看目录有什么</li>
 *   <li>找到目标文件后 read_file 读取</li>
 * </ol>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>path — 要查看的目录路径（默认 /workspace）</li>
 *   <li>recursive — 是否递归列出子目录（默认 false）</li>
 * </ul>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class ListFilesTool implements Tool {

    private static final String NAME = "list_files";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "要查看的目录路径，如 /workspace 或 /tmp"
        ));
        properties.put("recursive", Map.of(
                "type", "boolean",
                "description", "是否递归列出子目录（默认 false）"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of()
        );

        return new ToolDefinition(
                NAME,
                "列出沙盒中指定目录下的所有文件和子目录。",
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            path = "/workspace";
        }

        Boolean recursive = (Boolean) arguments.get("recursive");
        boolean isRecursive = recursive != null && recursive;

        try {
            var client = factory.getAioClient(sessionId);
            Map<String, Object> result = client.files().list(
                    path, isRecursive, false,
                    null, true, "name", false
            );

            if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
                String msg = result != null ? (String) result.get("message") : "未知错误";
                return "列出文件失败：" + msg;
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) {
                return "目录为空";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("目录: ").append(data.get("path")).append("\n");
            sb.append("总计: ").append(data.get("total_count")).append(" 项\n\n");

            List<Map<String, Object>> files = (List<Map<String, Object>>) data.get("files");
            if (files != null) {
                for (Map<String, Object> file : files) {
                    String name = (String) file.get("name");
                    Boolean isDir = (Boolean) file.get("is_directory");
                    Long size = file.get("size") != null ? ((Number) file.get("size")).longValue() : null;

                    if (Boolean.TRUE.equals(isDir)) {
                        sb.append("  ").append(name).append("/\n");
                    } else {
                        sb.append("  ").append(name);
                        if (size != null) {
                            sb.append(" (").append(formatSize(size)).append(")");
                        }
                        sb.append("\n");
                    }
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "列出文件失败：" + e.getMessage();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
