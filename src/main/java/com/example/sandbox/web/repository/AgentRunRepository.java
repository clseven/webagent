package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.AgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Agent 运行记录 Repository。
 */
@Repository
public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {

    /**
     * 删除指定会话的全部运行账本。
     *
     * @param sessionId 会话 ID
     */
    void deleteBySession_Id(String sessionId);

    /**
     * 按创建时间读取会话运行记录。
     *
     * @param sessionId 会话 ID
     * @return 从旧到新的运行记录
     */
    List<AgentRunEntity> findBySession_IdOrderByCreatedAtAsc(String sessionId);

    /**
     * 读取指定运行之后尚未应用到会话上下文的记录。
     *
     * @param sessionId 会话 ID
     * @param runId 已应用的最后运行 ID
     * @return 从旧到新的遗漏运行记录
     */
    List<AgentRunEntity> findBySession_IdAndIdGreaterThanOrderByIdAsc(String sessionId, Long runId);

    /**
     * 分页读取会话最早的运行记录。
     *
     * @param sessionId 会话 ID
     * @param pageable 分页参数
     * @return 从旧到新的运行记录
     */
    List<AgentRunEntity> findBySession_IdOrderByIdAsc(String sessionId, Pageable pageable);

    /**
     * 分页读取指定运行之后的记录。
     *
     * @param sessionId 会话 ID
     * @param runId 已应用的最后运行 ID
     * @param pageable 分页参数
     * @return 从旧到新的遗漏运行记录
     */
    List<AgentRunEntity> findBySession_IdAndIdGreaterThanOrderByIdAsc(
            String sessionId, Long runId, Pageable pageable);
}
