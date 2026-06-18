package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.shell.model.ShellExecResult;
import com.example.sandbox.web.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件同步服务。
 *
 * <p>负责把本地持久化的用户上传文件、知识库文件和 skill 文件恢复到 AIO Sandbox。
 * 文件写入统一走新 AIO 客户端的 `/v1/file/write` 能力，避免同步逻辑绕回旧协议。</p>
 */
@Service
public class FileSyncService {

    /** 记录文件同步过程中的单文件失败和整体恢复结果。 */
    private static final Logger log = LoggerFactory.getLogger(FileSyncService.class);

    /** 用户工作空间本地存储服务，用于定位知识库和上传目录。 */
    private final UserWorkspaceStorageService storage;

    /** 会话上传文件存储服务，用于兼容旧的按 session 组织的上传目录。 */
    @Autowired
    private FileStorageService fileStorageService;

    /** Sandbox 客户端工厂，用于根据会话获取 AIO 客户端。 */
    @Autowired
    private SandboxClientFactory sandboxClientFactory;

    /**
     * 创建文件同步服务。
     *
     * @param storage 用户工作空间本地存储服务
     */
    public FileSyncService(UserWorkspaceStorageService storage) {
        this.storage = storage;
    }

    /**
     * 文件同步结果。
     *
     * @param successCount 成功同步的文件数
     * @param failedPaths  同步失败的 Sandbox 路径
     */
    public record SyncResult(int successCount, List<String> failedPaths) {

        /**
         * 判断本次同步是否全部成功。
         *
         * @return 没有失败路径时返回 true
         */
        public boolean allSucceeded() {
            return failedPaths.isEmpty();
        }
    }

    /**
     * 同步用户级工作空间到 AIO Sandbox。
     *
     * @param userId 用户 ID
     * @param client 当前 AIO 客户端
     * @return 本次同步结果
     */
    public SyncResult syncUserWorkspace(Long userId, AioClient client) {
        List<String> failed = new ArrayList<>();
        int success = 0;
        success += syncTree(storage.knowledgeRoot(userId), "/home/gem/knowledge", client, failed);
        success += syncTree(storage.uploadRoot(userId), "/home/gem/uploads", client, failed);
        return new SyncResult(success, List.copyOf(failed));
    }

    /**
     * 同步当前会话上传文件到 Sandbox。
     *
     * @param sessionId 会话 ID
     */
    public void syncUploadFiles(String sessionId) {
        syncFromLocal(
                Path.of(fileStorageService.getStoragePath(sessionId)),
                "/home/gem/uploads",
                sessionId
        );
    }

    /**
     * 同步 skill 文件到 Sandbox。
     *
     * @param sessionId 会话 ID
     * @param skillPath skill 本地路径
     * @param skillId   skill ID
     */
    public void syncSkill(String sessionId, Path skillPath, String skillId) {
        String containerBase = "/home/gem/skills/" + skillId;
        syncFromLocal(skillPath, containerBase, sessionId);
    }

    /**
     * 从本地目录同步文件到 Sandbox。
     *
     * @param sourceDir           本地源目录
     * @param targetContainerPath Sandbox 内目标路径前缀
     * @param sessionId           会话 ID
     */
    private void syncFromLocal(Path sourceDir, String targetContainerPath, String sessionId) {
        if (!Files.exists(sourceDir)) {
            log.warn("同步源目录不存在: {}", sourceDir);
            return;
        }

        try (var stream = Files.walk(sourceDir)) {
            var files = stream.filter(Files::isRegularFile).toList();
            if (files.isEmpty()) {
                log.info("同步目录为空: {}", sourceDir);
                return;
            }

            for (Path file : files) {
                try {
                    String relativePath = sourceDir.relativize(file).toString().replace("\\", "/");
                    String containerPath = targetContainerPath + "/" + relativePath;
                    AioClient client = sandboxClientFactory.getAioClient(sessionId);
                    byte[] content = Files.readAllBytes(file);
                    if (writeSandboxFile(client, containerPath, content)) {
                        log.debug("已同步: {} -> {}", file.getFileName(), containerPath);
                    } else {
                        log.warn("同步文件失败: {} -> {}", file, containerPath);
                    }
                } catch (Exception e) {
                    log.warn("同步文件失败: {} - {}", file, e.getMessage());
                }
            }
            log.info("同步完成，共 {} 个文件", files.size());
        } catch (IOException e) {
            log.error("遍历同步目录失败: {}", sourceDir, e);
        }
    }

    /**
     * 同步一个本地目录树到 Sandbox 目录。
     *
     * @param sourceRoot  本地源根目录
     * @param sandboxRoot Sandbox 目标根目录
     * @param client      当前 AIO 客户端
     * @param failed      收集失败路径的列表
     * @return 成功同步的文件数量
     */
    private int syncTree(Path sourceRoot,
                         String sandboxRoot,
                         AioClient client,
                         List<String> failed) {
        if (!Files.exists(sourceRoot)) {
            return 0;
        }
        int success = 0;
        try (var stream = Files.walk(sourceRoot)) {
            for (Path source : stream.filter(Files::isRegularFile).toList()) {
                String relative = sourceRoot.relativize(source).toString().replace('\\', '/');
                String target = sandboxRoot + "/" + relative;
                try {
                    byte[] bytes = Files.readAllBytes(source);
                    if (writeSandboxFile(client, target, bytes)) {
                        success++;
                    } else {
                        failed.add(target);
                    }
                } catch (Exception e) {
                    failed.add(target);
                    log.warn("同步文件失败: {} -> {}", source, target, e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("遍历用户工作空间失败: " + sourceRoot, e);
        }
        return success;
    }

    /**
     * 将文件写入 AIO Sandbox。
     *
     * <p>本方法只做两件事：先用新 AIO shell 客户端确保父目录存在，再调用新 AIO file 客户端的
     * `/v1/file/write`。文件写入的瞬时失败重试在 {@link com.example.sandbox.aio.file.AioFileApi}
     * 内部完成；这里不切换到 multipart upload，也不使用旧 shell/base64 写入兜底。</p>
     *
     * @param client AIO 客户端
     * @param target Sandbox 内目标路径
     * @param bytes  文件字节
     * @return 写入成功返回 true
     */
    private boolean writeSandboxFile(AioClient client, String target, byte[] bytes) {
        ensureParentDirectory(client, target);
        return client.files().writeBytes(target, bytes);
    }

    /**
     * 确保目标文件父目录存在。
     *
     * <p>目录创建失败会抛出异常并由外层同步流程记录该文件失败；目录创建不做降级，
     * 因为这是新 AIO 客户端 shell 能力的直接调用。</p>
     *
     * @param client AIO 客户端
     * @param target Sandbox 内目标文件路径
     */
    private void ensureParentDirectory(AioClient client, String target) {
        int slash = target.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String parent = target.substring(0, slash);
        ShellExecResult result = client.shell().exec("mkdir -p " + shellQuote(parent));
        if (result == null || !result.isSuccess() || result.getExitCode() != 0) {
            throw new IllegalStateException("创建 Sandbox 父目录失败: " + parent);
        }
    }

    /**
     * 将文本包装成安全的单引号 Shell 参数。
     *
     * @param value 原始文本
     * @return Shell 单引号参数
     */
    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
