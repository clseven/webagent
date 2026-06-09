package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.KnowledgeBaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 知识库 Repository
 *
 * @author example
 * @date 2026/06/02
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    List<KnowledgeBaseEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);
}
