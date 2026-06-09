package com.example.sandbox.web.service.tool;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多功能文件编辑器工具 — 集查看、创建、替换、插入于一体（AIO 沙箱）
 *
 * <h3>支持的命令</h3>
 * <table>
 *   <tr><th>command</th><th>说明</th><th>必填参数</th></tr>
 *   <tr><td>view</td><td>查看文件（可指定行范围）</td><td>path, [view_range]</td></tr>
 *   <tr><td>create</td><td>创建新文件</td><td>path, file_text</td></tr>
 *   <tr><td>str_replace</td><td>精确替换文本</td><td>path, old_str, new_str</td></tr>
 *   <tr><td>insert</td><td>在指定行插入内容</td><td>path, insert_line, new_str</td></tr>
 * </table>
 *
 * <h3>优势</h3>
 * <p>一个工具覆盖所有文件操作场景，比 read_file + write_file + file_replace 组合更高效，
 * 减少工具调用次数和 token 消耗。</p>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class StrReplaceEditorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(StrReplaceEditorTool.class);
    private static final String NAME = "str_replace_editor";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of(
                "type", "string",
                "description", "操作类型：view（查看文件）、create（创建文件）、str_replace（替换文本）、insert（插入行）"
        ));
        properties.put("path", Map.of(
                "type", "string",
                "description", "文件路径"
        ));
        properties.put("file_text", Map.of(
                "type", "string",
                "description", "文件内容（create 时必填）"
        ));
        properties.put("old_str", Map.of(
                "type", "string",
                "description", "要替换的原始文本（str_replace 时必填，必须精确匹配）"
        ));
        properties.put("new_str", Map.of(
                "type", "string",
                "description", "替换后的新文本（str_replace 时必填）"
        ));
        properties.put("insert_line", Map.of(
                "type", "integer",
                "description", "插入行号（insert 时必填，0 表示文件开头）"
        ));
        properties.put("view_range", Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "查看行范围 [start, end]（view 时可选，-1 表示到文件末尾）"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("command", "path")
        );

        return new ToolDefinition(
                NAME,
                "多功能文件编辑器。支持：view（查看文件/指定行范围）、create（创建新文件）、str_replace（精确替换文本）、insert（在指定行插入内容）。比 read_file + write_file 组合更高效，推荐用于代码编辑。",
                parameters,
                "AIO"
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String sessionId, Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        String path = (String) arguments.get("path");

        if (command == null || command.isBlank()) return "错误：command 不能为空";
        if (path == null || path.isBlank()) return "错误：path 不能为空";

        try {
            AioSandboxClient client = factory.getAioClient(sessionId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("command", command);
            body.put("path", path);

            if (arguments.get("file_text") != null) body.put("file_text", arguments.get("file_text"));
            if (arguments.get("old_str") != null) body.put("old_str", arguments.get("old_str"));
            if (arguments.get("new_str") != null) body.put("new_str", arguments.get("new_str"));
            if (arguments.get("insert_line") != null) body.put("insert_line", arguments.get("insert_line"));
            if (arguments.get("view_range") != null) body.put("view_range", arguments.get("view_range"));

            Map<String, Object> result = client.strReplaceEditor(body);

            if (result == null) {
                return "错误：操作失败，无响应";
            }

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) return "操作成功";

                String output = (String) data.get("output");
                String error = (String) data.get("error");

                if (error != null && !error.isBlank()) {
                    return "错误：" + error;
                }

                log.info("编辑器操作成功: {} path={}", command, path);
                return output != null && !output.isBlank() ? output : "操作成功";
            } else {
                return "错误：操作失败 - " + result.get("message");
            }
        } catch (Exception e) {
            log.error("编辑器操作失败: {} path={}", command, path, e);
            return "错误：操作失败 - " + e.getMessage();
        }
    }
}
