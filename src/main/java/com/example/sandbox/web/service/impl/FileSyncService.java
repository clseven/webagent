package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
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
 * 文件同步服务
 *
 * <p>将本地存储的文件同步到沙盒中。
 * 统一处理 skill 文件和用户上传文件的同步逻辑。</p>
 *
 * @author example
 * @date 2026/05/20
 */
@Service
public class FileSyncService {

    private static final Logger log = LoggerFactory.getLogger(FileSyncService.class);

    private final UserWorkspaceStorageService storage;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private SandboxClientFactory sandboxClientFactory;

    public FileSyncService(UserWorkspaceStorageService storage) {
        this.storage = storage;
    }

    public record SyncResult(int successCount, List<String> failedPaths) {
        public boolean allSucceeded() {
            return failedPaths.isEmpty();
        }
    }

    public SyncResult syncUserWorkspace(Long userId, AioClient client) {
        List<String> failed = new ArrayList<>();
        int success = 0;
        success += syncTree(storage.knowledgeRoot(userId), "/home/gem/knowledge", client, failed);
        success += syncTree(storage.uploadRoot(userId), "/home/gem/uploads", client, failed);
        return new SyncResult(success, List.copyOf(failed));
    }

    /**
     * 同步用户上传的文件到沙盒
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
     * 同步 skill 文件到沙盒
     *
     * @param sessionId 会话 ID
     * @param skillPath skill 本地路径
     * @param skillId skill ID
     */
    public void syncSkill(String sessionId, Path skillPath, String skillId) {
        String containerBase = "/home/gem/skills/" + skillId;
        syncFromLocal(skillPath, containerBase, sessionId);
    }

    /**
     * 从本地目录同步文件到沙盒
     *
     * @param sourceDir 本地源目录
     * @param targetContainerPath 沙盒内目标路径前缀
     * @param sessionId 会话 ID
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
                    if (client.files().writeBytes(containerPath, content)) {
                        log.debug("已同步: {} -> {}", file.getFileName(), containerPath);
                    } else {
                        log.warn("同步文件失败: {} -> {}", file, containerPath);
                    }
                } catch (IOException e) {
                    log.warn("同步文件失败: {} - {}", file, e.getMessage());
                }
            }
            log.info("同步完成，共 {} 个文件", files.size());
        } catch (IOException e) {
            log.error("遍历同步目录失败: {}", sourceDir, e);
        }
    }

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
                    if (client.files().writeBytes(target, bytes)) {
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
}
