package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.shell.model.ShellExecResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 控制会话沙箱中的 code-server 工作台打开文件并定位光标。
 *
 * <p>本服务负责路径规范化、普通文件与符号链接边界检查、Shell 参数转义，
 * 以及 code-server 客户端尚未连接时的短暂重试。</p>
 */
@Slf4j
@Service
public class VsCodeNavigationService {

    /** code-server 客户端连接尚未就绪时的最大重试次数。 */
    private static final int OPEN_MAX_ATTEMPTS = 5;

    /** code-server 客户端连接尚未就绪时的重试间隔毫秒数。 */
    private static final long OPEN_RETRY_DELAY_MILLIS = 500L;

    /** code-server 当前工作台使用的用户数据目录。 */
    private static final String USER_DATA_DIRECTORY = "/home/gem/.config/code-server/vscode";

    /** 沙箱客户端工厂，用于获取当前会话绑定的 AIO 客户端。 */
    private final SandboxClientFactory sandboxClientFactory;

    /**
     * 创建 VSCode 文件定位服务。
     *
     * @param sandboxClientFactory 沙箱客户端工厂
     */
    public VsCodeNavigationService(SandboxClientFactory sandboxClientFactory) {
        this.sandboxClientFactory = sandboxClientFactory;
    }

    /**
     * 在当前会话的 VSCode 工作台中打开文件并定位到指定行列。
     *
     * @param sessionId 会话 ID
     * @param path      沙箱绝对路径或相对于 {@code /home/gem} 的路径
     * @param line      可选目标行号，从 1 开始
     * @param column    可选目标列号，从 1 开始
     * @return 实际打开的规范沙箱绝对路径
     * @throws IllegalArgumentException 路径或行列不合法、文件不存在时抛出
     * @throws IllegalStateException code-server 工作台未就绪或命令执行失败时抛出
     */
    public String openFile(String sessionId, String path, Integer line, Integer column) {
        int targetLine = requirePositivePosition(line, "line");
        int targetColumn = requirePositivePosition(column, "column");
        AioClient client = sandboxClientFactory.getAioClient(sessionId);
        String resolvedPath = resolveFilePath(client, path);
        openLocation(client, resolvedPath, targetLine, targetColumn);
        log.info("VSCode 文件定位完成: session={}, path={}, line={}, column={}",
                sessionId, resolvedPath, targetLine, targetColumn);
        return resolvedPath;
    }

    /**
     * 校验行列位置并应用默认值。
     *
     * @param value 可选位置
     * @param name  参数名称
     * @return 大于等于 1 的位置；空值返回 1
     * @throws IllegalArgumentException 位置小于 1 时抛出
     */
    private int requirePositivePosition(Integer value, String name) {
        if (value == null) {
            return 1;
        }
        if (value < 1) {
            throw new IllegalArgumentException(name + " 必须大于等于 1");
        }
        return value;
    }

    /**
     * 把请求路径解析为 {@code /home/gem} 内存在的普通文件。
     *
     * <p>相对路径优先按 {@code /home/gem} 解析；若不存在，再尝试常见的
     * {@code /home/gem/workspace} 工作目录。</p>
     *
     * @param client      当前会话 AIO 客户端
     * @param requestPath 请求中的绝对或相对路径
     * @return 已确认存在的沙箱绝对文件路径
     * @throws IllegalArgumentException 路径越界、格式非法或文件不存在时抛出
     */
    private String resolveFilePath(AioClient client, String requestPath) {
        String normalized = normalizeRequestPath(requestPath);
        if (normalized.startsWith("/home/gem/")) {
            String canonicalPath = resolveRegularFile(client, normalized);
            if (canonicalPath == null) {
                throw new IllegalArgumentException("文件不存在或不是普通文件: " + normalized);
            }
            return canonicalPath;
        }

        String directPath = "/home/gem/" + normalized;
        requireHomeGemPath(directPath);
        String canonicalPath = resolveRegularFile(client, directPath);
        if (canonicalPath != null) {
            return canonicalPath;
        }

        String workspacePath = "/home/gem/workspace/" + normalized;
        requireHomeGemPath(workspacePath);
        canonicalPath = resolveRegularFile(client, workspacePath);
        if (canonicalPath != null) {
            return canonicalPath;
        }
        throw new IllegalArgumentException("文件不存在或不是普通文件: " + normalized);
    }

    /**
     * 规范化 VSCode 定位请求中的路径，并拒绝越界片段和非沙箱绝对路径。
     *
     * @param requestPath 请求路径
     * @return {@code /home/gem} 绝对路径或安全相对路径
     * @throws IllegalArgumentException 路径为空、包含越界片段或指向其他绝对目录时抛出
     */
    private String normalizeRequestPath(String requestPath) {
        if (requestPath == null || requestPath.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (requestPath.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("path 不能包含控制字符");
        }

        String normalized = requestPath.trim().replace('\\', '/');
        if (normalized.length() >= 2) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
        }
        if (normalized.startsWith("file://")) {
            normalized = normalized.substring("file://".length());
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (normalized.startsWith("/home/gem/")) {
            requireHomeGemPath(normalized);
            return normalized;
        }
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("只允许打开 /home/gem 范围内的文件");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("不允许访问该路径");
            }
        }
        return normalized;
    }

    /**
     * 使用 Shell 解析普通文件的规范路径，并阻止符号链接逃逸出 {@code /home/gem}。
     *
     * @param client 当前会话 AIO 客户端
     * @param path   已通过范围校验的沙箱绝对路径
     * @return 文件存在时返回规范绝对路径；不存在或不是普通文件时返回 null
     * @throws IllegalArgumentException 规范路径逃逸出 {@code /home/gem} 时抛出
     */
    private String resolveRegularFile(AioClient client, String path) {
        String quotedPath = shellQuote(path);
        ShellExecResult result = client.shell().exec(
                "test -f " + quotedPath + " && realpath -- " + quotedPath,
                null,
                10
        );
        if (result == null || !result.isSuccess() || result.getExitCode() != 0) {
            return null;
        }

        String canonicalPath = result.getOutput().trim();
        requireHomeGemPath(canonicalPath);
        return canonicalPath;
    }

    /**
     * 调用 code-server CLI，把目标文件发送给已经加载的 VSCode 工作台。
     *
     * <p>仅当 code-server 明确提示当前没有已连接实例时重试；文件不存在、命令缺失等
     * 其他失败不会重试，因为重复执行不能改变结果。</p>
     *
     * @param client 当前会话 AIO 客户端
     * @param path   已确认存在的沙箱绝对文件路径
     * @param line   目标行号
     * @param column 目标列号
     * @throws IllegalStateException 工作台持续未就绪、命令失败或等待被中断时抛出
     */
    private void openLocation(AioClient client, String path, int line, int column) {
        String target = path + ":" + line + ":" + column;
        String command = "code-server --user-data-dir=" + shellQuote(USER_DATA_DIRECTORY)
                + " --reuse-window " + shellQuote(target);

        for (int attempt = 1; attempt <= OPEN_MAX_ATTEMPTS; attempt++) {
            ShellExecResult result = client.shell().exec(command, null, 10);
            if (result != null && result.isSuccess() && result.getExitCode() == 0) {
                return;
            }

            String output = result == null ? "" : result.getOutput().trim();
            String responseMessage = result == null ? "" : result.getMessage();
            boolean clientNotReady = output.contains("No opened code-server instances found")
                    || (responseMessage != null
                    && responseMessage.contains("No opened code-server instances found"));
            if (!clientNotReady || attempt == OPEN_MAX_ATTEMPTS) {
                String reason = !output.isBlank()
                        ? output
                        : (responseMessage == null || responseMessage.isBlank()
                                ? "code-server 命令执行失败"
                                : responseMessage);
                reason = reason.replaceAll("\\s+", " ").trim();
                if (reason.length() > 300) {
                    reason = reason.substring(0, 300) + "...";
                }
                throw new IllegalStateException("无法在 VSCode 中打开文件: " + reason);
            }

            try {
                Thread.sleep(OPEN_RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 VSCode 工作台就绪时被中断", e);
            }
        }
    }

    /**
     * 校验绝对路径位于 {@code /home/gem} 内，且不包含空白或越界片段。
     *
     * @param path 待校验的沙箱绝对路径
     * @throws IllegalArgumentException 路径越界或包含非法片段时抛出
     */
    private void requireHomeGemPath(String path) {
        if (path == null || !path.startsWith("/home/gem/") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("不允许访问该路径");
        }
        for (String segment : path.substring("/home/gem/".length()).split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("不允许访问该路径");
            }
        }
    }

    /**
     * 把字符串转换为安全的 POSIX Shell 单引号参数。
     *
     * @param value 原始参数
     * @return 可直接拼接到 Shell 命令中的单个安全参数
     */
    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
