package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.repository.AgentAppRepository;
import com.example.sandbox.web.service.AgentAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Agent 应用服务实现
 *
 * @author example
 * @date 2026/06/02
 */
@Service
public class AgentAppServiceImpl implements AgentAppService {

    private static final Logger log = LoggerFactory.getLogger(AgentAppServiceImpl.class);

    @Autowired
    private AgentAppRepository agentAppRepository;

    @Override
    @Transactional
    public AgentAppEntity createApp(Long userId, String name, String description) {
        AgentAppEntity app = new AgentAppEntity();
        app.setUserId(userId);
        app.setName(name);
        app.setDescription(description);
        app = agentAppRepository.save(app);
        log.info("创建 Agent 应用: userId={}, appId={}, name={}", userId, app.getId(), name);
        return app;
    }

    @Override
    public List<AgentAppEntity> listApps(Long userId) {
        return agentAppRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public AgentAppEntity getApp(Long appId) {
        return agentAppRepository.findById(appId)
                .orElseThrow(() -> new RuntimeException("Agent 应用不存在: " + appId));
    }

    @Override
    @Transactional
    public AgentAppEntity updateApp(Long appId, String name, String description) {
        AgentAppEntity app = getApp(appId);
        if (name != null) app.setName(name);
        if (description != null) app.setDescription(description);
        app.setUpdatedAt(LocalDateTime.now());
        return agentAppRepository.save(app);
    }

    @Override
    @Transactional
    public void deleteApp(Long appId) {
        AgentAppEntity app = getApp(appId);
        agentAppRepository.delete(app);
        log.info("删除 Agent 应用: appId={}", appId);
    }

    @Override
    @Transactional
    public void updateKnowledgeBases(Long appId, Set<Long> kbIds) {
        AgentAppEntity app = getApp(appId);
        app.setKnowledgeBaseIds(kbIds);
        app.setUpdatedAt(LocalDateTime.now());
        agentAppRepository.save(app);
        log.info("更新应用知识库关联: appId={}, kbIds={}", appId, kbIds);
    }

    @Override
    @Transactional
    public void updateSkills(Long appId, Set<String> skillIds) {
        AgentAppEntity app = getApp(appId);
        app.setSkillIds(skillIds);
        app.setUpdatedAt(LocalDateTime.now());
        agentAppRepository.save(app);
        log.info("更新应用 Skill 关联: appId={}, skillIds={}", appId, skillIds);
    }
}
