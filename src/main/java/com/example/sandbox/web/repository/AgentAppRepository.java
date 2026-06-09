package com.example.sandbox.web.repository;

import com.example.sandbox.web.model.entity.AgentAppEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Agent 应用 Repository
 *
 * @author example
 * @date 2026/06/02
 */
@Repository
public interface AgentAppRepository extends JpaRepository<AgentAppEntity, Long> {

    List<AgentAppEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);
}
