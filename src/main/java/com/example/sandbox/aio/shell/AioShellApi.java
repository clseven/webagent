package com.example.sandbox.aio.shell;

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
     */
    public ShellExecResult exec(String command, String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("command", command);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("id", sessionId);
        }

        String rawResponse = http.postText("/v1/shell/exec", body);
        if (rawResponse != null && containsBinary(rawResponse)) {
            log.debug("AIO shell/exec 原始响应（二进制，已省略）: 长度={}", rawResponse.length());
        } else {
            log.debug("AIO shell/exec 原始响应: {}", rawResponse);
        }

        ShellExecResult result;
        try {
            result = objectMapper.readValue(rawResponse, ShellExecResult.class);
        } catch (Exception e) {
            log.error("解析 shell/exec 响应失败: {}", e.getMessage());
            result = new ShellExecResult();
            result.setSuccess(false);
            result.setMessage("解析响应失败: " + truncate(rawResponse, 500));
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
     * <p>连接失败是启动轮询和旧 endpoint 检测中的常见结果，本方法不逐次输出日志；
     * 等待流程会在最终成功或超时时输出汇总，避免启动阶段刷屏。</p>
     *
     * @return 健康返回 true；连接、超时或 HTTP 错误返回 false
     */
    public boolean quickHealthCheck() {
        try {
            http.getTextQuietly("/v1/shell/sessions", Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等待 AIO Shell 服务就绪。
     *
     * <p>连接拒绝、连接过早关闭、超时和 HTTP 错误会在总超时时间内继续轮询，
     * 因为这些失败常见于 AIO 服务刚启动的短暂阶段；线程中断不会重试，
     * 因为调用方已经要求停止等待。</p>
     *
     * @param timeout 总等待时间
     * @return 在超时前就绪返回 true，否则返回 false
     */
    public boolean waitUntilReady(Duration timeout) {
        long startTime = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        long deadline = startTime + timeoutNanos;
        int attempts = 0;

        while (System.nanoTime() <= deadline) {
            attempts++;
            if (quickHealthCheck()) {
                log.info("AIO 服务就绪！耗时 {} 秒，共尝试 {} 次",
                        Duration.ofNanos(System.nanoTime() - startTime).toSeconds(), attempts);
                return true;
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(READINESS_POLL_INTERVAL_MILLIS, Duration.ofNanos(remainingNanos).toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("AIO 服务未就绪，超时 {} 秒，共尝试 {} 次", timeout.toSeconds(), attempts);
        return false;
    }

    /**
     * 检测字符串是否包含大量二进制或不可打印字符。
     *
     * @param value 待检测文本
     * @return 包含大量不可打印字符时返回 true
     */
    private boolean containsBinary(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int sample = Math.min(value.length(), 1024);
        int nonPrintable = 0;
        for (int i = 0; i < sample; i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                nonPrintable++;
            } else if (c == 0x7F) {
                nonPrintable++;
            }
        }
        return nonPrintable * 10 > sample;
    }

    /**
     * 截断日志文本，避免解析失败时输出过长响应。
     *
     * @param value 原始文本
     * @param max   最大长度
     * @return 截断后的文本
     */
    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...[truncated, total=" + value.length() + "]";
    }
}
