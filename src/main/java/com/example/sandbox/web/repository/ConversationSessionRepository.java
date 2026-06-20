package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 会话 Repository
 *
 * @author example
 * @date 2026/05/14
 */
@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSessionEntity, String> {

    @Query("SELECT s FROM ConversationSessionEntity s LEFT JOIN FETCH s.enabledSkillIds WHERE s.id = :id")
    Optional<ConversationSessionEntity> findByIdWithSkills(@Param("id") String id);

    /**
     * 查找所有有沙箱 ID 的会话
     */
    @Query("SELECT s FROM ConversationSessionEntity s WHERE s.sandboxId IS NOT NULL")
    List<ConversationSessionEntity> findAllWithSandbox();

    List<ConversationSessionEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 查询指定用户拥有的目标会话。
     *
     * @param ids    待查询的会话 ID 集合
     * @param userId 当前用户 ID
     * @return 当前用户实际拥有的会话实体
     */
    List<ConversationSessionEntity> findByIdInAndUserId(Set<String> ids, Long userId);
}
