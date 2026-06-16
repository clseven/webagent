package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 等待进程完成工具 — 等待 shell 会话中的进程执行完毕（AIO 沙箱）
 *
 * <h3>用途</h3>
 * <p>配合异步命令使用。execute_command 启动长时间运行的命令后，
 * 返回 shell_session_id，调用此工具等待进程结束。</p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>session_id — Shell 会话 ID（由 execute_command 返回）</li>
 *   <li>seconds — 最大等待秒数（默认 30）</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <pre>
 * 1. 启动编译：execute_command("make -j8") → 返回 shell_session_id
 * 2. 等待完成：shell_wait(session_id="xxx", seconds=300)
 * 3. 查看结果
 * </pre>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class ShellWaitTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ShellWaitTool.class);
    private static final String NAME = "shell_wait";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "session_id", Map.of(
                        "type", "string",
                        "description", "Shell 会话 ID（由 execute_command 返回）"
                ),
                "seconds", Map.of(
                        "type", "integer",
                        "description", "最大等待秒数",
                        "default", 30
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("session_id")
        );

        return new ToolDefinition(
                NAME,
                "等待沙箱中指定 shell 会话的进程执行完成。适用于长时间运行的命令，需要等待其结束并获取结果。",
                parameters,
                "AIO"
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String sessionId, Map<String, Object> arguments) {
        String shellSessionId = (String) arguments.get("session_id");
        if (shellSessionId == null || shellSessionId.isBlank()) {
            return "错误：session_id 不能为空";
        }

        Number secondsNum = (Number) arguments.get("seconds");
        int seconds = secondsNum != null ? secondsNum.intValue() : 30;

        try {
            var client = factory.getAioClient(sessionId);
            Map<String, Object> result = client.shell().waitFor(shellSessionId, seconds);

            if (result == null) {
                return "错误：等待进程失败，无响应";
            }

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                String status = data != null ? (String) data.get("status") : "unknown";
                log.info("进程等待完成: session={} status={}", shellSessionId, status);
                return "进程状态: " + status;
            } else {
                return "错误：等待进程失败 - " + result.get("message");
            }
        } catch (Exception e) {
            log.error("等待进程失败: session={}", shellSessionId, e);
            return "错误：等待进程失败 - " + e.getMessage();
        }
    }
}
