package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.SandboxClient;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写入文件工具 — 在沙箱环境中创建或覆盖文件
 *
 * <h3>用途</h3>
 * <p>让 LLM 能在沙箱里写入文件，可用于：</p>
 * <ul>
 *   <li>保存处理结果（如生成的报告、转换后的文件）</li>
 *   <li>创建脚本（写完用 execute_command 执行）</li>
 *   <li>修改配置文件</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>path — 文件路径</li>
 *   <li>content — 要写入的内容</li>
 * </ul>
 *
 * <h3>注意</h3>
 * <p>写入操作会覆盖已有文件，不会询问确认。</p>
 */
@Component
public class WriteFileTool implements Tool {

    private static final String NAME = "write_file";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "要写入的文件路径"
        ));
        properties.put("content", Map.of(
                "type", "string",
                "description", "要写入的文件内容"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("path", "content")
        );

        return new ToolDefinition(
                NAME,
                "在沙箱环境中写入或创建文件",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        String content = (String) arguments.get("content");

        if (path == null || path.isBlank()) {
            return "错误：文件路径不能为空";
        }
        if (content == null) {
            return "错误：文件内容不能为空";
        }

        try {
            SandboxClient client = factory.getClient(sessionId);
            client.writeFile(path, content);
            return "文件写入成功：" + path;
        } catch (Exception e) {
            return "写入失败：" + e.getMessage();
        }
    }
}