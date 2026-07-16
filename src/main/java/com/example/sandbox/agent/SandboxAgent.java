package com.example.sandbox.agent;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxException;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.Volume;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.Host;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * SandboxAgent - 基于 OpenSandbox 的沙箱操作 Agent
 *
 * <p>提供沙箱环境下的命令执行、文件操作、代码运行等能力，
 * 可与 LLM 结合实现 AI Agent 自动化操作。</p>
 *
 * <p>核心设计原则：</p>
 * <ul>
 *   <li>单一职责：每个方法只做一件事</li>
 *   <li>防御式编程：所有参数均做校验</li>
 *   <li>资源安全：使用 try-with-resources 确保沙箱释放</li>
 * </ul>
 *
 * @author example
 * @date 2026-05-11
 */
public class SandboxAgent implements AutoCloseable {

    /** 默认沙箱镜像 */
    private static final String DEFAULT_IMAGE = "sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/chrome:latest";

    /** 默认沙箱超时时间 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    /** 默认就绪等待时间 */
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(60);

    /** 工作目录 */
    private static final String WORK_DIR = "/home/gem";

    /** 沙箱实例 */
    private final Sandbox sandbox;

    /** 连接配置 */
    private final ConnectionConfig connectionConfig;

    /**
     * 私有构造函数，通过 Builder 创建
     */
    private SandboxAgent(Sandbox sandbox, ConnectionConfig connectionConfig) {
        this.sandbox = sandbox;
        this.connectionConfig = connectionConfig;
    }

    /**
     * 获取沙箱 ID
     *
     * @return 沙箱 ID
     */
    public String getSandboxId() {
        return sandbox.getId();
    }

    /**
     * 获取沙箱信息
     *
     * @return 沙箱信息
     */
    public SandboxInfo getSandboxInfo() {
        return sandbox.getInfo();
    }

    /**
     * 获取 AIO 服务的外部访问地址
     *
     * <p>AIO 容器内部监听 8080 端口，通过此方法获取映射到宿主机的地址，
     * 用于连接 AIO 的 REST API（截图、环境信息等）。</p>
     *
     * @return 外部访问地址，如 "localhost:34567"
     */
    public String getAioEndpoint() {
        var endpoint = sandbox.getEndpoint(8080);
        return endpoint.getEndpoint();
    }

    // ==================== 命令执行 ====================

    /**
     * 执行 Shell 命令
     *
     * @param command 要执行的命令
     * @return 命令执行结果
     * @throws IllegalArgumentException 命令为空时抛出
     */
    public CommandResult executeCommand(String command) {
        validateNotEmpty(command, "命令");
        Execution execution = sandbox.commands().run(command);
        return CommandResult.from(execution);
    }

    // ==================== 文件操作 ====================

    /**
     * 读取文件内容
     *
     * @param filePath 文件路径
     * @return 文件内容
     * @throws IllegalArgumentException 文件路径为空时抛出
     */
    public String readFile(String filePath) {
        validateNotEmpty(filePath, "文件路径");
        return sandbox.files().readFile(filePath, "UTF-8", null);
    }

    /**
     * 写入文件
     *
     * @param filePath 文件路径
     * @param content  文件内容
     * @param mode     文件权限（如 644、755）
     */
    public void writeFile(String filePath, String content, int mode) {
        validateNotEmpty(filePath, "文件路径");
        sandbox.files().write(List.of(
                WriteEntry.builder()
                        .path(filePath)
                        .data(content)
                        .mode(mode)
                        .build()
        ));
    }

    /**
     * 写入文件（默认权限 644）
     *
     * @param filePath 文件路径
     * @param content  文件内容
     */
    public void writeFile(String filePath, String content) {
        writeFile(filePath, content, 644);
    }

    /**
     * 删除文件
     *
     * @param filePaths 要删除的文件路径列表
     */
    public void deleteFiles(List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            throw new IllegalArgumentException("文件路径列表不能为空");
        }
        sandbox.files().deleteFiles(filePaths);
    }

    // ==================== 代码执行 ====================

    /**
     * 执行 Python 代码
     *
     * @param code Python 代码
     * @return 执行结果
     */
    public CommandResult runPython(String code) {
        validateNotEmpty(code, "Python 代码");
        String command = String.format("python3 -c %s", shellEscape(code));
        return executeCommand(command);
    }

    /**
     * 执行 Node.js 代码
     *
     * @param code JavaScript 代码
     * @return 执行结果
     */
    public CommandResult runJavaScript(String code) {
        validateNotEmpty(code, "JavaScript 代码");
        String command = String.format("node -e %s", shellEscape(code));
        return executeCommand(command);
    }

    /**
     * 执行 Python 脚本文件
     *
     * <p>先将代码写入文件，再执行，适合多行代码场景</p>
     *
     * @param scriptName 脚本文件名
     * @param code       Python 代码
     * @return 执行结果
     */
    public CommandResult runPythonScript(String scriptName, String code) {
        validateNotEmpty(scriptName, "脚本名");
        validateNotEmpty(code, "Python 代码");

        String scriptPath = WORK_DIR + "/" + scriptName;
        writeFile(scriptPath, code, 755);
        return executeCommand("python3 " + scriptPath);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 续期沙箱
     *
     * @param duration 续期时长
     * @return 续期后的到期时间
     */
    public OffsetDateTime renew(Duration duration) {
        return sandbox.renew(duration).getExpiresAt();
    }

    /**
     * 暂停沙箱
     */
    public void pause() {
        sandbox.pause();
    }

    /**
     * 终止沙箱（不可逆）
     */
    public void kill() {
        sandbox.kill();
    }

    /**
     * 检查沙箱是否健康
     *
     * @return 是否健康
     */
    public boolean isHealthy() {
        return sandbox.isHealthy();
    }

    @Override
    public void close() {
        try {
            sandbox.kill();
        } catch (Exception e) {
            // 终止失败时静默处理
        }
        sandbox.close();

    }
    // ==================== 工具方法 ====================

    /**
     * 参数非空校验
     */
    private void validateNotEmpty(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }

    /**
     * Shell 转义
     */
    private String shellEscape(String str) {
        return "'" + str.replace("'", "'\\''") + "'";
    }

    // ==================== Builder ====================

    /**
     * 创建 Agent Builder
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * SandboxAgent 构建器
     */
    public static class Builder {
        private String domain = "localhost:8080";
        private String apiKey;
        private String image = DEFAULT_IMAGE;
        /** 需要重新连接的已有沙箱 ID；为空时创建新沙箱。 */
        private String sandboxId;
        private List<String> entrance;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration readyTimeout = DEFAULT_READY_TIMEOUT;
        private Duration requestTimeout = Duration.ofMinutes(5);  // HTTP 请求超时，首次拉镜像需要较长时间
        private boolean debug = false;
        private List<Volume> volumes = List.of();  // Volume 挂载列表

        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder image(String image) {
            this.image = image;
            return this;
        }

        /**
         * 指定需要重新连接的已有沙箱。
         *
         * @param sandboxId 已有沙箱 ID
         * @return 当前 Builder
         */
        public Builder sandboxId(String sandboxId) {
            this.sandboxId = sandboxId;
            return this;
        }

        public Builder entrypoint(String... commands) {
            this.entrance = List.of(commands);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder readyTimeout(Duration readyTimeout) {
            this.readyTimeout = readyTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /**
         * 添加 Volume 挂载
         *
         * @param volume Volume 对象
         * @return Builder
         */
        public Builder addVolume(Volume volume) {
            this.volumes = new java.util.ArrayList<>(this.volumes);
            this.volumes.add(volume);
            return this;
        }

        /**
         * 挂载宿主机目录到容器
         *
         * @param hostPath 宿主机路径
         * @param containerPath 容器内路径
         * @return Builder
         */
        public Builder mountHostPath(String hostPath, String containerPath) {
            Volume volume = Volume.builder()
                    .name("volume-" + System.currentTimeMillis())
                    .host(Host.builder().path(hostPath).build())
                    .mountPath(containerPath)
                    .build();
            return addVolume(volume);
        }

        /**
         * 构建 SandboxAgent 实例。
         *
         * <p>设置 sandboxId 时连接已有沙箱，否则按当前配置创建新沙箱。连接失败或沙箱
         * 已不存在时由 OpenSandbox SDK 抛出异常，调用方决定重试或重建。</p>
         *
         * @return SandboxAgent 实例
         */
        public SandboxAgent build() {
            ConnectionConfig config = ConnectionConfig.builder()
                    .domain(domain)
                    .debug(debug)
                    .requestTimeout(requestTimeout)
                    .build();

            if (sandboxId != null && !sandboxId.isBlank()) {
                Sandbox sandbox = Sandbox.connector()
                        .sandboxId(sandboxId)
                        .connectionConfig(config)
                        .connectTimeout(readyTimeout)
                        .connect();
                return new SandboxAgent(sandbox, config);
            }

            Sandbox.Builder sandboxBuilder = Sandbox.builder()
                    .connectionConfig(config)
                    .image(image)
                    .timeout(timeout)
                    .readyTimeout(readyTimeout);

            // 如果设置了 entrance，使用自定义启动命令
            if (entrance != null) {
                sandboxBuilder.entrypoint(entrance);
            }

            // 如果有 Volume 配置，添加到沙箱
            if (!volumes.isEmpty()) {
                sandboxBuilder.volumes(volumes);
            }

            Sandbox sandbox = sandboxBuilder.build();

            return new SandboxAgent(sandbox, config);
        }
    }

    // ==================== 命令执行结果 ====================

    /**
     * 命令执行结果封装
     */
    public static class CommandResult {

        /** 标准输出 */
        private final String stdout;

        /** 退出码 */
        private final int exitCode;

        /** 是否成功 */
        private final boolean success;

        private CommandResult(String stdout, int exitCode, boolean success) {
            this.stdout = stdout;
            this.exitCode = exitCode;
            this.success = success;
        }

        /**
         * 从 Execution 转换
         */
        public static CommandResult from(Execution execution) {
            String stdout = "";
            if (execution.getLogs() != null && execution.getLogs().getStdout() != null
                    && !execution.getLogs().getStdout().isEmpty()) {
                // 拼接所有行，而不是只取第一行
                StringBuilder sb = new StringBuilder();
                for (var line : execution.getLogs().getStdout()) {
                    sb.append(line.getText()).append("\n");
                }
                stdout = sb.toString();
            }

            boolean success = execution.getExitCode() != null && execution.getExitCode() == 0;

            return new CommandResult(stdout, execution.getExitCode() != null ? execution.getExitCode() : -1, success);
        }

        public String getStdout() {
            return stdout;
        }

        public int getExitCode() {
            return exitCode;
        }

        public boolean isSuccess() {
            return success;
        }

        @Override
        public String toString() {
            return "CommandResult{" +
                    "success=" + success +
                    ", exitCode=" + exitCode +
                    ", stdout='" + stdout.trim() + '\'' +
                    '}';
        }
    }
}
