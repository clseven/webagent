package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.UserSandboxEntity;
import com.example.sandbox.web.model.entity.WorkspaceDirectoryMemoryEntity;
import com.example.sandbox.web.repository.UserSandboxRepository;
import com.example.sandbox.web.repository.WorkspaceDirectoryMemoryRepository;
import com.example.sandbox.web.service.WorkspaceDirectoryMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 基于 AIO File List API 的工作区目录记忆服务实现。
 *
 * <h3>职责</h3>
 * <p>扫描 {@code /home/gem} 下前端可见的非隐藏目录树，保存路径和大小等元数据，并为 Agent 构建短目录摘要。</p>
 *
 * <h3>边界</h3>
 * <p>本服务禁止读取文件正文；刷新只调用目录列表接口，摘要只使用已保存的路径元数据。</p>
 */
@Slf4j
@Service
public class WorkspaceDirectoryMemoryServiceImpl implements WorkspaceDirectoryMemoryService {

    /** 注入上下文时最多列出的路径数量，避免目录树过大挤占模型上下文。 */
    private static final int MAX_CONTEXT_PATHS = 80;

    /** 沙箱 AIO 客户端工厂。 */
    private final SandboxClientFactory clientFactory;

    /** 工作区目录记忆仓储。 */
    private final WorkspaceDirectoryMemoryRepository memoryRepository;

    /** 用户沙箱绑定仓储，用于记录真实沙箱 ID。 */
    private final UserSandboxRepository userSandboxRepository;

    /**
     * 创建工作区目录记忆服务。
     *
     * @param clientFactory         沙箱 AIO 客户端工厂
     * @param memoryRepository      工作区目录记忆仓储
     * @param userSandboxRepository 用户沙箱绑定仓储
     */
    public WorkspaceDirectoryMemoryServiceImpl(SandboxClientFactory clientFactory,
                                               WorkspaceDirectoryMemoryRepository memoryRepository,
                                               UserSandboxRepository userSandboxRepository) {
        this.clientFactory = clientFactory;
        this.memoryRepository = memoryRepository;
        this.userSandboxRepository = userSandboxRepository;
    }

    /**
     * 刷新 {@code /home/gem} 非隐藏目录树。
     *
     * <p>重试策略：本方法不做重试。目录列表失败通常说明沙箱不可用或 AIO 返回异常，应由调用方决定是否影响主流程；
     * 对话注入场景会在上层吞掉该失败，用户主动刷新场景则直接暴露失败。</p>
     *
     * @param sessionId 会话 ID
     */
    @Override
    @Transactional
    public void refresh(String sessionId) {
        Long userId = UserContext.getCurrentUserId();
        String sandboxId = resolveSandboxId(userId);
        AioClient client = clientFactory.getAioClient(sessionId);
        Map<String, Object> response = client.files().list(
                WorkspaceDirectoryMemoryEntity.VISIBLE_ROOT,
                true,
                false,
                null,
                true,
                "name",
                false);

        if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
            Object message = response != null ? response.get("message") : "无响应";
            throw new IllegalStateException("刷新工作区目录记忆失败: " + message);
        }

        List<WorkspaceDirectoryMemoryEntity> existing =
                memoryRepository.findByUserIdAndSessionIdOrderByPathAsc(userId, sessionId);
        Map<String, WorkspaceDirectoryMemoryEntity> existingByPath = existing.stream()
                .collect(Collectors.toMap(
                        WorkspaceDirectoryMemoryEntity::getPath,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<WorkspaceDirectoryMemoryEntity> toSave = new ArrayList<>();
        for (Map<String, Object> item : extractFiles(response)) {
            String path = asString(item.get("path"));
            if (!isVisibleWorkspacePath(path)) {
                continue;
            }
            boolean directory = Boolean.TRUE.equals(item.get("is_directory"));
            Long sizeBytes = directory ? null : asLong(item.get("size"));
            WorkspaceDirectoryMemoryEntity entity = existingByPath.remove(path);
            if (entity == null) {
                entity = new WorkspaceDirectoryMemoryEntity();
            }
            entity.refresh(userId, sessionId, sandboxId, path, directory, sizeBytes);
            toSave.add(entity);
        }

        for (WorkspaceDirectoryMemoryEntity stale : existingByPath.values()) {
            if (!stale.isDeleted()) {
                stale.markDeleted();
                toSave.add(stale);
            }
        }

        if (!toSave.isEmpty()) {
            memoryRepository.saveAll(toSave);
        }
        log.info("工作区目录记忆刷新完成: session={}, visiblePaths={}", sessionId, toSave.size());
    }

    /**
     * 构建给模型看的短目录摘要。
     *
     * @param sessionId 会话 ID
     * @return 目录摘要；没有记忆时返回空字符串
     */
    @Override
    @Transactional(readOnly = true)
    public String buildContext(String sessionId) {
        Long userId = UserContext.getCurrentUserId();
        List<WorkspaceDirectoryMemoryEntity> entries =
                memoryRepository.findByUserIdAndSessionIdAndDeletedFalseOrderByPathAsc(userId, sessionId);
        if (entries.isEmpty()) {
            return "";
        }

        long directoryCount = entries.stream().filter(WorkspaceDirectoryMemoryEntity::isDirectory).count();
        long fileCount = entries.size() - directoryCount;
        List<String> topLevelDirectories = entries.stream()
                .filter(WorkspaceDirectoryMemoryEntity::isDirectory)
                .filter(item -> item.getDepth() == 1)
                .map(item -> item.getName() + "/")
                .sorted()
                .toList();

        StringBuilder context = new StringBuilder();
        context.append("## 工作区目录记忆\n\n");
        context.append("可见根目录: `").append(WorkspaceDirectoryMemoryEntity.VISIBLE_ROOT).append("`\n");
        context.append("范围: 与工作区面板一致，仅包含非隐藏路径；这里只记录路径与大小元数据。\n");
        context.append("统计: ").append(directoryCount).append(" 个目录，")
                .append(fileCount).append(" 个文件。\n");
        if (!topLevelDirectories.isEmpty()) {
            context.append("顶层目录: ").append(String.join("、", topLevelDirectories)).append("\n");
        }
        context.append("路径列表（最多 ").append(MAX_CONTEXT_PATHS).append(" 项）:\n");

        entries.stream()
                .sorted(Comparator.comparing(WorkspaceDirectoryMemoryEntity::getPath))
                .limit(MAX_CONTEXT_PATHS)
                .map(this::formatEntry)
                .forEach(line -> context.append("- ").append(line).append("\n"));
        if (entries.size() > MAX_CONTEXT_PATHS) {
            context.append("- ... 还有 ").append(entries.size() - MAX_CONTEXT_PATHS).append(" 项未列出\n");
        }

        return context.toString();
    }

    /**
     * 解析 AIO 列表响应中的文件项。
     *
     * @param response AIO 响应 Map
     * @return 文件项列表；缺失时返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractFiles(Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        Object files = dataMap.get("files");
        if (!(files instanceof List<?> fileList)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : fileList) {
            if (item instanceof Map<?, ?> itemMap) {
                result.add((Map<String, Object>) itemMap);
            }
        }
        return result;
    }

    /**
     * 判断路径是否属于前端工作区面板展示范围。
     *
     * @param path 待检查路径
     * @return true 表示路径位于 {@code /home/gem} 下且没有隐藏路径段
     */
    private boolean isVisibleWorkspacePath(String path) {
        if (path == null || !path.startsWith(WorkspaceDirectoryMemoryEntity.VISIBLE_ROOT + "/")) {
            return false;
        }
        String relative = path.substring((WorkspaceDirectoryMemoryEntity.VISIBLE_ROOT + "/").length());
        for (String segment : relative.split("/")) {
            if (segment.isBlank() || segment.startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 格式化目录记忆项为模型可读的一行。
     *
     * @param entity 目录记忆实体
     * @return 相对路径摘要
     */
    private String formatEntry(WorkspaceDirectoryMemoryEntity entity) {
        String relative = entity.getPath().substring((WorkspaceDirectoryMemoryEntity.VISIBLE_ROOT + "/").length());
        if (entity.isDirectory()) {
            return relative + "/";
        }
        return relative + " (" + formatSize(entity.getSizeBytes()) + ")";
    }

    /**
     * 格式化文件大小。
     *
     * @param sizeBytes 文件大小，可为 null
     * @return 可读大小
     */
    private String formatSize(Long sizeBytes) {
        if (sizeBytes == null || sizeBytes < 0) {
            return "未知大小";
        }
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        double kb = sizeBytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    /**
     * 将对象转成字符串。
     *
     * @param value 原始值
     * @return 字符串或 null
     */
    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * 将数字对象转成 Long。
     *
     * @param value 原始值
     * @return Long 值；无法转换时返回 null
     */
    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取当前用户绑定的沙箱 ID。
     *
     * @param userId 用户 ID
     * @return 沙箱 ID；未找到记录时返回 null
     */
    private String resolveSandboxId(Long userId) {
        Optional<UserSandboxEntity> sandbox = userSandboxRepository.findByUserIdAndDeletedFalse(userId);
        return sandbox.map(UserSandboxEntity::getSandboxId).orElse(null);
    }
}
