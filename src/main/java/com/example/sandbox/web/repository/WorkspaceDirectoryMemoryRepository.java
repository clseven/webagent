package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.WorkspaceDirectoryMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 工作区目录记忆 Repository。
 *
 * <p>只查询和保存目录树元数据，不涉及文件正文。</p>
 */
@Repository
public interface WorkspaceDirectoryMemoryRepository extends JpaRepository<WorkspaceDirectoryMemoryEntity, Long> {

    /**
     * 查询指定用户和会话的全部目录记忆，包含已删除记录。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 按路径排序的目录记忆列表
     */
    List<WorkspaceDirectoryMemoryEntity> findByUserIdAndSessionIdOrderByPathAsc(Long userId, String sessionId);

    /**
     * 查询指定用户和会话当前仍可见的目录记忆。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 按路径排序的可见目录记忆列表
     */
    List<WorkspaceDirectoryMemoryEntity> findByUserIdAndSessionIdAndDeletedFalseOrderByPathAsc(
            Long userId, String sessionId);
}
