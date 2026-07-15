package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.ConversationContextEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 会话级模型上下文快照 Repository。
 */
@Repository
public interface ConversationContextRepository extends JpaRepository<ConversationContextEntity, String> {
}
