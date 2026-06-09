package com.example.sandbox.web.model.response;

import com.example.sandbox.web.model.entity.AgentAppEntity;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Agent 应用响应
 *
 * @author example
 * @date 2026/06/02
 */
public class AgentAppResponse {

    private Long id;
    private Long userId;
    private String name;
    private String description;
    private Set<Long> knowledgeBaseIds;
    private Set<String> skillIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AgentAppResponse from(AgentAppEntity entity) {
        AgentAppResponse response = new AgentAppResponse();
        response.setId(entity.getId());
        response.setUserId(entity.getUserId());
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        response.setKnowledgeBaseIds(entity.getKnowledgeBaseIds());
        response.setSkillIds(entity.getSkillIds());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Set<Long> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(Set<Long> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }

    public Set<String> getSkillIds() { return skillIds; }
    public void setSkillIds(Set<String> skillIds) { this.skillIds = skillIds; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
