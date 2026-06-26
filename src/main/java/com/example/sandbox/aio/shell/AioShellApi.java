package com.example.sandbox.aio.shell;

import com.example.sandbox.aio.core.AioHttpClient;
import com.example.sandbox.aio.shell.model.ShellExecRequest;
import com.example.sandbox.aio.shell.model.ShellExecResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
        ShellExecRequest request = new ShellExecRequest();
        request.setCommand(command);
        if (sessionId != null && !sessionId.isBlank()) {
            request.setId(sessionId);
        }
        return execInternal(request, true);
    }

    /**
     * 在临时 Shell 会话中执行命令，并设置 AIO 命令等待上限。
     *
     * <p>本方法不会更新 {@link #shellSessionId}，适合短生命周期诊断命令或
     * MCP transport 内部 curl 调用，避免把长进程会话污染为后续默认会话。</p>
     *
     * @param command        Shell 命令
     * @param sessionId      可选的 Shell 会话 ID；为空时由 AIO 自动创建临时会话
     * @param timeoutSeconds AIO 等待命令完成的秒数
     * @return 完整执行响应
     */
    public ShellExecResult exec(String command, String sessionId, int timeoutSeconds) {
        ShellExecRequest request = new ShellExecRequest();
        request.setCommand(command);
        request.setTimeout(timeoutSeconds);
        if (sessionId != null && !sessionId.isBlank()) {
            request.setId(sessionId);
        }
        return execInternal(request, false);
    }

    /** 异步启动命令的默认超时秒数。 */
    private static final int ASYNC_DEFAULT_TIMEOUT_SECONDS = 3;

    /**
     * 异步启动 Shell 长进程。
     *
     * <p>该方法使用 AIO 的 {@code async_mode=true}，立即返回运行中的会话 ID。
     * 启动结果不会写入默认复用会话，调用方需要自行保存并在结束时 kill。</p>
     *
     * @param command Shell 长进程命令
     * @return 包含会话 ID 和 running 状态的执行响应
     */
    public ShellExecResult execAsync(String command) {
        return execAsync(command, ASYNC_DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 异步启动 Shell 长进程。
     *
     * <p>该方法使用 AIO 的 {@code async_mode=true}，立即返回运行中的会话 ID。
     * 启动结果不会写入默认复用会话，调用方需要自行保存并在结束时 kill。</p>
     *
     * @param command  Shell Shell 长进程命令
     * @param timeout  AIO 等待命令完成的秒数（超时后立即返回 running 状态）
     * @return 包含会话 ID 和 running 状态的执行响应
     */
    public ShellExecResult execAsync(String command, int timeout) {
        ShellExecRequest request = new ShellExecRequest();
        request.setCommand(command);
        request.setAsyncMode(true);
        request.setTimeout(timeout);
        return execInternal(request, false);
    }

    /**
     * 执行 Shell 请求并解析响应。
     *
     * @param request      Shell 执行请求
     * @param cacheSession 是否把返回的 session_id 记为默认复用会话
     * @return 完整执行响应；解析失败时返回 success=false 的响应对象
     */
    private ShellExecResult execInternal(ShellExecRequest request, boolean cacheSession) {
        String rawResponse = http.postText("/v1/shell/exec", request);
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

        if (cacheSession && result.getData() != null && result.getData().getSessionId() != null) {
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
