package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求沙箱工具 — 创建隔离的沙箱环境（Common 沙箱）
 *
 * <h3>用途</h3>
 * <p>在执行命令、运行代码、读写文件之前，必须先调用此工具创建沙箱。
 * 沙箱提供隔离的执行环境，确保操作不会影响宿主机。</p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>reason — 申请沙箱的原因（用于日志审计）</li>
 * </ul>
 *
 * <h3>使用时机</h3>
 * <pre>
 * LLM 推理流程：
 * 1. 用户任务需要执行操作
 * 2. 调用 request_sandbox 创建环境
 * 3. 调用 execute_command / read_file / write_file 等具体工具
 * </pre>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 Common 沙箱使用（sandboxType=COMMON）。</p>
 */
@Component
public class RequestSandboxTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RequestSandboxTool.class);
    private static final String NAME = "request_sandbox";

    @Autowired
    private SandboxService sandboxService;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reason", Map.of(
                "type", "string",
                "description", "申请沙箱的原因"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("reason")
        );

        return new ToolDefinition(
                NAME,
                "请求创建沙箱环境。当需要执行命令、运行代码、读写文件时必须先调用此工具申请沙箱。",
                parameters,
                "COMMON"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String reason = (String) arguments.get("reason");
        log.info("Sandbox requested for session {}: {}", sessionId, reason);

        try {
            sandboxService.createSandbox(sessionId);
            return "沙箱创建成功";
        } catch (Exception e) {
            log.error("Failed to create sandbox for session {}", sessionId, e);
            return "沙箱创建失败：" + e.getMessage();
        }
    }
}