package com.example.sandbox.aio.shell;

import com.example.sandbox.aio.core.AioApiException;
import com.example.sandbox.aio.core.AioHttpClient;
import com.example.sandbox.aio.shell.model.ShellExecResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 封装 AIO Shell REST API 和 Shell 会话复用行为。
 */
public class AioShellApi {

    /** Shell API 调用日志。 */
    private static final Logger log = LoggerFactory.getLogger(AioShellApi.class);

    /** 沙箱就绪检查的轮询间隔。 */
    private static final long READINESS_POLL_INTERVAL_MILLIS = 1_000L;

    /** 共享 AIO HTTP 传输客户端。 */
    private final AioHttpClient http;

    /** Shell 响应解析器。 */
    private final ObjectMapper objectMapper;

    /** 最近一次 Shell 执行返回的会话 ID。 */
    private String shellSessionId;

    /**
     * 创建 Shell API。
     *
     * @param http 共享 AIO HTTP 传输客户端
     */
    public AioShellApi(AioHttpClient http) {
        this.http = http;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 在当前复用的 Shell 会话中执行命令。
     *
     * @param command Shell 命令
     * @return 完整执行响应
     */
    public ShellExecResult exec(String command) {
        return exec(command, shellSessionId);
    }

    /**
     * 在指定 Shell 会话中执行命令。
     *
     * @param command   Shell 命令
     * @param sessionId 可选的 Shell 会话 ID
     * @return 完整执行响应
     * @throws AioApiException 当响应无法解析时抛出
     */
    public ShellExecResult exec(String command, String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("command", command);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("id", sessionId);
        }

        Map<String, Object> response = http.postMap("/v1/shell/exec", body);
        ShellExecResult result;
        try {
            result = objectMapper.convertValue(response, ShellExecResult.class);
        } catch (IllegalArgumentException e) {
            throw new AioApiException("解析 shell/exec 响应失败", e);
        }

        if (result.getData() != null && result.getData().getSessionId() != null) {
            shellSessionId = result.getData().getSessionId();
        }
        return result;
    }

    /**
     * 查看指定 Shell 会话的当前输出。
     *
     * @param sessionId Shell 会话 ID
     * @return 完整执行响应
     */
    public ShellExecResult view(String sessionId) {
        Map<String, Object> response = http.postMap("/v1/shell/view", Map.of("id", sessionId));
        return objectMapper.convertValue(response, ShellExecResult.class);
    }

    /**
     * 等待指定 Shell 会话继续执行。
     *
     * @param sessionId Shell 会话 ID
     * @param seconds   最长等待秒数
     * @return AIO 完整响应
     */
    public Map<String, Object> waitFor(String sessionId, int seconds) {
        return http.postMap("/v1/shell/wait", Map.of("id", sessionId, "seconds", seconds));
    }

    /**
     * 终止指定 Shell 会话中的进程。
     *
     * @param sessionId Shell 会话 ID
     * @return AIO 完整响应
     */
    public Map<String, Object> kill(String sessionId) {
        return http.postMap("/v1/shell/kill", Map.of("id", sessionId));
    }

    /**
     * 使用 Shell 的 test 命令判断路径是否存在。
     *
     * @param path Sandbox 内绝对路径
     * @return 路径存在返回 true；命令失败或响应异常返回 false
     */
    public boolean fileExists(String path) {
        try {
            ShellExecResult result = exec("test -e '" + path + "' && echo yes || echo no");
            return result != null && result.isSuccess() && result.getOutput().trim().contains("yes");
        } catch (Exception e) {
            log.warn("检查 Sandbox 路径失败: path={}, reason={}", path, e.getMessage());
            return false;
        }
    }

    /**
     * 单次检查 AIO Shell 服务是否健康。
     *
     * @return 健康返回 true；连接、超时或 HTTP 错误返回 false
     */
    public boolean quickHealthCheck() {
        try {
            http.getText("/v1/shell/sessions", Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.debug("AIO 快速健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 等待 AIO Shell 服务就绪。
     *
     * <p>首次检查失败后立即进入短间隔轮询，不再等待固定启动宽限期。连接失败和超时会在
     * 总超时时间内重试；中断异常不会重试，因为调用线程已要求停止。</p>
     *
     * @param timeout 总等待时间
     * @return 在超时前就绪返回 true，否则返回 false
     */
    public boolean waitUntilReady(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (true) {
            if (quickHealthCheck()) {
                return true;
            }

            long remainingMillis = deadline - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                return false;
            }

            try {
                Thread.sleep(Math.min(READINESS_POLL_INTERVAL_MILLIS, remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
