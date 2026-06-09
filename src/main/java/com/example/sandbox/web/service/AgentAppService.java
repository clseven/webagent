package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.AgentAppEntity;

import java.util.List;
import java.util.Set;

/**
 * Agent 应用服务接口
 *
 * @author example
 * @date 2026/06/02
 */
public interface AgentAppService {

    /**
     * 创建应用
     */
    AgentAppEntity createApp(Long userId, String name, String description);

    /**
     * 列出用户的应用
     */
    List<AgentAppEntity> listApps(Long userId);

    /**
     * 获取应用详情
     */
    AgentAppEntity getApp(Long appId);

    /**
     * 更新应用
     */
    AgentAppEntity updateApp(Long appId, String name, String description);

    /**
     * 删除应用
     */
    void deleteApp(Long appId);

    /**
     * 更新应用关联的知识库
     */
    void updateKnowledgeBases(Long appId, Set<Long> kbIds);

    /**
     * 更新应用关联的 Skill
     */
    void updateSkills(Long appId, Set<String> skillIds);
}
