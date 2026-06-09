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
 * 执行命令工具 — 在沙箱环境中执行 shell 命令
 *
 * <h3>用途</h3>
 * <p>让 LLM 能在沙箱里执行任意 shell 命令，可用于：</p>
 * <ul>
 *   <li>运行脚本（python、node、bash）</li>
 *   <li>查看文件信息（ls、cat、head）</li>
 *   <li>安装依赖（pip install、npm install）</li>
 *   <li>处理数据（grep、awk、sort）</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>command — 要执行的 shell 命令</li>
 * </ul>
 *
 * <h3>安全说明</h3>
 * <p>所有命令都在隔离沙箱中执行，不会影响宿主机。</p>
 */
@Component
public class ExecuteCommandTool implements Tool {

    private static final String NAME = "execute_command";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of(
                "type", "string",
                "description", "要执行的 shell 命令"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("command")
        );

        return new ToolDefinition(
                NAME,
                "在沙箱环境中执行 shell 命令。可用于运行脚本、查看文件、安装依赖等。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return "错误：命令不能为空";
        }

        try {
            SandboxClient client = factory.getClient(sessionId);
            return client.execCommand(command);
        } catch (Exception e) {
            return "执行出错：" + e.getMessage();
        }
    }
}