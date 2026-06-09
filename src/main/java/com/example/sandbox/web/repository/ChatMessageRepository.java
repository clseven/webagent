package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 消息 Repository
 *
 * @author example
 * @date 2026/05/14
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.session.id = :sessionId ORDER BY m.timestamp ASC")
    List<ChatMessageEntity> findBySessionIdOrderByTimestampAsc(@Param("sessionId") String sessionId);

    void deleteBySessionId(String sessionId);
}
