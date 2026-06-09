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
 * 读取文件工具 — 在沙箱环境中读取文件内容
 *
 * <h3>用途</h3>
 * <p>让 LLM 能查看沙箱内任意文件的内容，包括：</p>
 * <ul>
 *   <li>用户上传的文件</li>
 *   <li>执行命令生成的输出</li>
 *   <li>配置文件、日志等</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>path — 要读取的文件路径（沙箱内的绝对路径）</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <pre>
 * LLM: 我需要看看 config.yaml 的内容
 * Action: read_file
 * Action Input: {"path": "/workspace/config.yaml"}
 * </pre>
 */
@Component
public class ReadFileTool implements Tool {

    private static final String NAME = "read_file";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
                "type", "string",
                "description", "要读取的文件路径"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("path")
        );

        return new ToolDefinition(
                NAME,
                "读取沙箱环境中的文件内容",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            return "错误：文件路径不能为空";
        }

        try {
            SandboxClient client = factory.getClient(sessionId);
            return client.readFile(path);
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }
}