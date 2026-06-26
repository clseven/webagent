package com.example.sandbox.web.service.mcpclient;

import com.example.sandbox.aio.shell.AioShellApi;
import com.example.sandbox.aio.shell.model.ShellExecResult;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 基于 AIO Shell 的 MCP Client transport。
 *
 * <p>该 transport 不在 WebAgent 后端主机启动用户命令，而是在用户自己的 AIO Sandbox
 * 中启动 {@code supergateway}。supergateway 再把沙箱内 stdio MCP Server 转换成
 * Streamable HTTP endpoint，后端通过 AIO Shell 执行 {@code curl} 与其通信。</p>
 */
public class AioShellTransport implements McpClientTransport {

    /** 组件日志。 */
    private static final Logger log = LoggerFactory.getLogger(AioShellTransport.class);

    /** supergateway 监听地址只暴露在用户 Sandbox 内部。 */
    private static final String LOOPBACK_HOST = "localhost";

    /** 端口探测起始值，避开常见系统服务端口。 */
    private static final int MIN_PORT = 8000;

    /** 端口探测结束值。 */
    private static final int MAX_PORT = 8099;

    /** 等待 supergateway 监听端口的最长时间。 */
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    /** 端口就绪检查间隔。 */
    private static final Duration STARTUP_POLL_INTERVAL = Duration.ofSeconds(1);

    /** 单次 curl 请求等待 MCP 响应的时间。 */
    private static final int REQUEST_TIMEOUT_SECONDS = 60;

    /** execAsync 超时：npx 首次下载 supergateway 可能需要较长时间。 */
    private static final int SUPERGW_ASYNC_TIMEOUT = 40;

    /** 当前用户 Sandbox 的 Shell API。 */
    private final AioShellApi shellApi;

    /** MCP Server 配置。 */
    private final McpServerConfig config;

    /** supergateway 在 Sandbox 内监听的端口。 */
    private final int port;

    /** supergateway 长进程 Shell 会话 ID。 */
    private volatile String supergatewaySessionId;

    /** supergateway 端口是否已确认可接收 MCP 请求。 */
    private volatile boolean supergatewayReady;

    /** SDK 注册的入站消息处理器。 */
    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inboundHandler;

    /** SDK 注册的异常处理器。 */
    private volatile Consumer<Throwable> exceptionHandler;

    /**
     * 创建 AIO Shell transport。
     *
     * @param shellApi 当前用户 Sandbox 的 Shell API
     * @param config   MCP Server 配置
     * @param port     supergateway 监听端口
     */
    public AioShellTransport(AioShellApi shellApi, McpServerConfig config, int port) {
        this.shellApi = shellApi;
        this.config = config;
        this.port = port;
    }

    /**
     * 在 Sandbox 内分配一个空闲端口。
     *
     * @param shellApi 当前用户 Sandbox 的 Shell API
     * @return 可用于 supergateway 的空闲端口
     * @throws McpConnectionException 端口范围内没有空闲端口时抛出
     */
    public static int allocatePort(AioShellApi shellApi) {
        for (int port = MIN_PORT; port <= MAX_PORT; port++) {
            ShellExecResult result = shellApi.exec(portCheckCommand(port), null, 10);
            if (result.isSuccess() && result.getOutput().trim().contains("free")) {
                return port;
            }
        }
        throw connectionException(
                McpErrorCode.PORT_ALLOCATION_FAILED,
                "无法为沙箱内 MCP shell transport 分配端口",
                "端口范围 " + MIN_PORT + "-" + MAX_PORT + " 均不可用",
                null);
    }

    /**
     * 连接 MCP transport，并启动 supergateway 长进程。
     *
     * @param handler SDK 入站消息处理器
     * @return 启动完成信号
     */
    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.fromRunnable(() -> connectBlocking(handler))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 注册异常处理器。
     *
     * @param handler SDK 异常处理器
     */
    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        this.exceptionHandler = handler;
    }

    /**
     * 发送 JSON-RPC 消息到沙箱内 supergateway。
     *
     * @param message JSON-RPC 请求或通知
     * @return 发送完成信号
     */
    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> sendMessageBlocking(message))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 关闭 supergateway 长进程。
     */
    @Override
    public void close() {
        String sessionId = supergatewaySessionId;
        if (sessionId == null || sessionId.isBlank()) {
            supergatewayReady = false;
            return;
        }
        try {
            shellApi.kill(sessionId);
        } catch (Exception e) {
            log.warn("关闭沙箱 MCP supergateway 失败: server={}, session={}, 原因={}",
                    config.getId(), sessionId, e.getMessage());
        } finally {
            supergatewaySessionId = null;
            supergatewayReady = false;
        }
    }

    /**
     * 优雅关闭 supergateway 长进程。
     *
     * @return 关闭完成信号
     */
    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::close)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 将 SDK 原始对象转换为目标类型。
     *
     * @param data    原始对象
     * @param typeRef 目标类型引用
     * @param <T>     目标类型
     * @return 转换后的对象
     */
    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return McpJsonDefaults.getMapper().convertValue(data, typeRef);
    }

    /**
     * 启动 supergateway 并等待端口就绪。
     *
     * @param handler SDK 入站消息处理器
     */
    private void connectBlocking(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        this.inboundHandler = handler;
        try {
            supergatewayReady = false;
            ShellExecResult result = shellApi.execAsync(buildSupergatewayCommand(), SUPERGW_ASYNC_TIMEOUT);
            if (!result.isSuccess() || result.getSessionId().isBlank()) {
                throw connectionException(
                        McpErrorCode.SUPERGATEWAY_START_FAILED,
                        "沙箱内 supergateway 启动失败",
                        result.getMessage(),
                        null);
            }
            supergatewaySessionId = result.getSessionId();
            ensureSupergatewayReady();
            log.info("沙箱 MCP supergateway 已启动: server={}, port={}, session={}",
                    config.getId(), port, supergatewaySessionId);
        } catch (RuntimeException e) {
            notifyException(e);
            close();
            throw e;
        }
    }

    /**
     * 同步发送单条 JSON-RPC 消息。
     *
     * @param message JSON-RPC 消息
     */
    private void sendMessageBlocking(McpSchema.JSONRPCMessage message) {
        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = inboundHandler;
        if (handler == null) {
            throw connectionException(
                    McpErrorCode.PROTOCOL_ERROR,
                    "MCP transport 尚未连接",
                    "sendMessage 在 connect 之前被调用",
                    null);
        }
        try {
            ensureSupergatewayReady();
            String json = McpJsonDefaults.getMapper().writeValueAsString(message);
            ShellExecResult result = shellApi.exec(buildCurlCommand(json), null, REQUEST_TIMEOUT_SECONDS);
            if (!result.isSuccess() || result.getExitCode() != 0) {
                throw connectionException(
                        McpErrorCode.SHELL_EXEC_FAILED,
                        "沙箱内 MCP curl 请求执行失败",
                        shellFailureDetail(result),
                        null);
            }

            List<McpSchema.JSONRPCMessage> responses = parseMessages(result.getOutput());
            if (responses.isEmpty() && !(message instanceof McpSchema.JSONRPCNotification)) {
                throw connectionException(
                        McpErrorCode.SSE_PARSE_ERROR,
                        "MCP 响应为空或未包含 JSON-RPC data",
                        result.getOutput(),
                        null);
            }
            for (McpSchema.JSONRPCMessage response : responses) {
                handler.apply(Mono.just(response)).subscribe();
            }
        } catch (IOException e) {
            McpConnectionException wrapped = connectionException(
                    McpErrorCode.SSE_PARSE_ERROR,
                    "MCP JSON-RPC 序列化或解析失败",
                    e.getMessage(),
                    e);
            notifyException(wrapped);
            throw wrapped;
        } catch (RuntimeException e) {
            notifyException(e);
            throw e;
        }
    }

    /**
     * 确认 supergateway 已监听端口。
     *
     * <p>部分 MCP SDK 会在 connect 的 Mono 尚未完全结束前触发 initialize 发送。
     * 这里在每次 curl 前再做一次门闩式确认，避免 initialize 打到尚未监听的端口。</p>
     */
    private void ensureSupergatewayReady() {
        if (supergatewayReady) {
            return;
        }
        synchronized (this) {
            if (supergatewayReady) {
                return;
            }
            waitUntilPortReady();
            supergatewayReady = true;
        }
    }

    /**
     * 等待 supergateway 端口监听。
     */
    private void waitUntilPortReady() {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() <= deadline) {
            ShellExecResult result = shellApi.exec(portCheckCommand(port), null, 10);
            if (result.isSuccess() && result.getOutput().trim().contains("occupied")) {
                return;
            }
            sleepQuietly(STARTUP_POLL_INTERVAL);
        }

        String detail = "";
        if (supergatewaySessionId != null) {
            try {
                detail = shellApi.view(supergatewaySessionId).getOutput();
            } catch (Exception e) {
                detail = e.getMessage();
            }
        }
        throw connectionException(
                McpErrorCode.SUPERGATEWAY_START_FAILED,
                "沙箱内 supergateway 未在超时时间内监听端口",
                detail,
                null);
    }

    /**
     * 构造 supergateway 启动命令。
     *
     * @return 可交给 AIO Shell 执行的命令
     */
    private String buildSupergatewayCommand() {
        StringBuilder command = new StringBuilder();
        for (Map.Entry<String, String> entry : config.getEnv().entrySet()) {
            command.append(entry.getKey())
                    .append('=')
                    .append(shellQuote(entry.getValue()))
                    .append(' ');
        }
        command.append("npx -y supergateway --stdio ")
                .append(shellQuote(buildStdioCommand()))
                .append(" --outputTransport streamableHttp --port ")
                .append(port)
                .append(" --host 0.0.0.0")
                .append(" 2>&1");
        return command.toString();
    }

    /**
     * 构造传给 supergateway 的 stdio 命令。
     *
     * @return 带参数的 stdio 命令字符串
     */
    private String buildStdioCommand() {
        List<String> parts = new ArrayList<>();
        parts.add(config.getCommand());
        parts.addAll(config.getArgs());
        return parts.stream()
                .map(AioShellTransport::shellQuote)
                .reduce((left, right) -> left + " " + right)
                .orElse(config.getCommand());
    }

    /**
     * 构造发送 JSON-RPC 的 curl 命令。
     *
     * @param json JSON-RPC 文本
     * @return 可交给 AIO Shell 执行的 curl 命令
     */
    private String buildCurlCommand(String json) {
        return "curl -sS http://" + LOOPBACK_HOST + ":" + port + "/mcp"
                + " -X POST"
                + " -H " + shellQuote("Content-Type: application/json")
                + " -H " + shellQuote("Accept: application/json, text/event-stream")
                + " --data " + shellQuote(json);
    }

    /**
     * 解析 curl 输出中的 SSE data 行。
     *
     * @param output curl 输出
     * @return JSON-RPC 消息列表
     * @throws IOException 输出中的 JSON 无法解析时抛出
     */
    private List<McpSchema.JSONRPCMessage> parseMessages(String output) throws IOException {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        String trimmed = output.trim();
        if (trimmed.startsWith("{")) {
            return List.of(McpSchema.deserializeJsonRpcMessage(McpJsonDefaults.getMapper(), trimmed));
        }

        List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : output.replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            } else if (line.isBlank() && data.length() > 0) {
                addParsedData(messages, data);
                data.setLength(0);
            }
        }
        if (data.length() > 0) {
            addParsedData(messages, data);
        }
        return List.copyOf(messages);
    }

    /**
     * 解析并追加单条 SSE data。
     *
     * @param messages 已解析消息列表
     * @param data data 字段内容
     * @throws IOException JSON 无法解析时抛出
     */
    private void addParsedData(List<McpSchema.JSONRPCMessage> messages, StringBuilder data) throws IOException {
        String json = data.toString();
        if ("[DONE]".equals(json)) {
            return;
        }
        messages.add(McpSchema.deserializeJsonRpcMessage(McpJsonDefaults.getMapper(), json));
    }

    /**
     * 构造端口占用检查命令。
     *
     * @param port 待检查端口
     * @return Shell 命令
     */
    private static String portCheckCommand(int port) {
        return "ss -tlnp | grep -q ':" + port + " ' && echo occupied || echo free";
    }

    /**
     * 生成 POSIX shell 单引号安全字符串。
     *
     * @param value 原始字符串
     * @return 可安全嵌入 shell 命令的字符串
     */
    private static String shellQuote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\\''") + "'";
    }

    /**
     * 安静等待一段时间。
     *
     * @param duration 等待时长
     */
    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw connectionException(
                    McpErrorCode.SUPERGATEWAY_START_FAILED,
                    "等待 supergateway 启动时线程被中断",
                    e.getMessage(),
                    e);
        }
    }

    /**
     * 通知 SDK 异常处理器。
     *
     * @param error 异常
     */
    private void notifyException(Throwable error) {
        Consumer<Throwable> handler = exceptionHandler;
        if (handler != null) {
            handler.accept(error);
        }
    }

    /**
     * 格式化 Shell 执行失败详情。
     *
     * @param result Shell 执行结果
     * @return 错误详情
     */
    private String shellFailureDetail(ShellExecResult result) {
        if (result == null) {
            return "AIO Shell 返回空结果";
        }
        return "message=" + result.getMessage()
                + ", status=" + result.getStatus()
                + ", exitCode=" + result.getExitCode()
                + ", output=" + result.getOutput();
    }

    /**
     * 创建结构化连接异常。
     *
     * @param code    错误码
     * @param message 用户可读说明
     * @param detail  诊断详情
     * @param cause   原始异常
     * @return 携带结构化错误的异常
     */
    private static McpConnectionException connectionException(McpErrorCode code, String message,
                                                              String detail, Throwable cause) {
        return new McpConnectionException(new McpOperationError(code, message, detail), cause);
    }
}
