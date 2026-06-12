package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.exception.UnauthorizedException;
import com.example.sandbox.web.model.converter.EntityConverter;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ChatMessageEntity;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.repository.ChatMessageRepository;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.SkillService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 对话记忆服务实现
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private static final Pattern SAFE_SKILL_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.\\-]{0,63}$");

    private final ConversationSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SkillService skillService;
    private final SandboxService sandboxService;

    @Autowired
    private SandboxClientFactory sandboxClientFactory;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private FileSyncService fileSyncService;

    public ConversationServiceImpl(ConversationSessionRepository sessionRepository,
                                  ChatMessageRepository messageRepository,
                                  SkillService skillService,
                                  SandboxService sandboxService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.skillService = skillService;
        this.sandboxService = sandboxService;
    }

    @Override
    @Transactional
    public void addUserMessage(String sessionId, String content) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        ChatMessageEntity message = EntityConverter.toChatMessageEntity(session, "user", content);
        messageRepository.save(message);
    }

    @Override
    @Transactional
    public void addAssistantMessage(String sessionId, String content) {
        addAssistantMessage(sessionId, content, null);
    }

    @Override
    @Transactional
    public void addAssistantMessage(String sessionId, String content, String reasoning) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        ChatMessageEntity message = EntityConverter.toChatMessageEntity(session, "assistant", content);
        message.setReasoning(reasoning);
        messageRepository.save(message);
    }

    @Override
    public List<ChatMessage> getHistory(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        List<ChatMessageEntity> messages = messageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
        return EntityConverter.toChatMessageList(messages);
    }

    @Override
    @Transactional
    public void clearHistory(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        messageRepository.deleteBySessionId(sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public String buildSystemPrompt(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }

        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        StringBuilder prompt = new StringBuilder();

        Set<String> enabledSkillIds = session.getEnabledSkillIds();
        log.info("构建系统提示，会话 {} 启用技能数: {}", sessionId, enabledSkillIds.size());

        if (!enabledSkillIds.isEmpty()) {
            prompt.append("## 已启用技能\n\n");
            prompt.append("以下技能已为当前会话启用。调用 `skill_activate(skill_id=\"技能ID\")` 获取详细指令。\n\n");
            for (String skillId : enabledSkillIds) {
                try {
                    Skill skill = skillService.getSkill(skillId);
                    prompt.append(skill.toMetadataLine()).append("\n");
                } catch (SkillNotFoundException e) {
                    log.warn("技能 {} 不存在，跳过", skillId);
                } catch (IOException e) {
                    log.error("读取技能 {} 元数据失败: {}", skillId, e.getMessage());
                }
            }
            prompt.append("\n");
        }

        return prompt.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getRecentHistory(String sessionId, int limit) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        List<ChatMessageEntity> allMessages = messageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
        if (allMessages.size() <= limit) {
            return EntityConverter.toChatMessageList(allMessages);
        }
        var recent = allMessages.subList(allMessages.size() - limit, allMessages.size());
        return EntityConverter.toChatMessageList(recent);
    }

    @Override
    @Transactional
    public void enableSkill(String sessionId, String skillId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        // 验证技能存在
        Skill skill;
        try {
            skill = skillService.getSkill(skillId);
        } catch (SkillNotFoundException e) {
            throw new RuntimeException("Skill not found: " + skillId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill: " + skillId, e);
        }

        session.enableSkill(skillId);
        sessionRepository.save(session);

        // 如果沙箱已存在，同步技能文件到沙箱
        if (sandboxService != null && sandboxService.hasSandbox(sessionId)) {
            log.info("同步技能文件到沙箱: {}", skillId);
            fileSyncService.syncSkill(sessionId, skill.getLocalPath(), skill.getId());
        }
    }

    @Override
    @Transactional
    public void disableSkill(String sessionId, String skillId) {
        if (skillId == null || !SAFE_SKILL_ID.matcher(skillId).matches()) {
            throw new IllegalArgumentException("Invalid skill ID: " + skillId);
        }

        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        session.disableSkill(skillId);
        sessionRepository.save(session);

        if (sandboxService != null && sandboxService.hasSandbox(sessionId)) {
            try {
                AioSandboxClient client = sandboxClientFactory.getAioClient(sessionId);
                client.execCommand("rm -rf /home/gem/skills/" + skillId);
                log.info("已从沙箱移除技能文件: {}", skillId);
            } catch (Exception e) {
                log.warn("清理沙箱技能文件失败: {}", skillId, e);
            }
        }
    }

    @Override
    public Set<String> getEnabledSkillIds(String sessionId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        return session.getEnabledSkillIds();
    }

    // ========== 供 AgentService 调用的方法 ==========

    /**
     * 创建新会话（数据库）
     */
    @Transactional
    public ConversationSession createSession() {
        Long userId = UserContext.getCurrentUserId();
        ConversationSessionEntity entity = new ConversationSessionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity = sessionRepository.save(entity);
        return EntityConverter.toConversationSession(entity);
    }

    /**
     * 创建新会话（关联 Agent 应用）
     */
    @Transactional
    public ConversationSession createSession(Long appId) {
        Long userId = UserContext.getCurrentUserId();
        ConversationSessionEntity entity = new ConversationSessionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setAppId(appId);
        entity = sessionRepository.save(entity);
        return EntityConverter.toConversationSession(entity);
    }

    /**
     * 删除会话（数据库）
     */
    @Transactional
    public void deleteSession(String sessionId) {
        if (sessionRepository.existsById(sessionId)) {
            sessionRepository.deleteById(sessionId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Skill> getEnabledSkills(String sessionId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        Set<String> skillIds = session.getEnabledSkillIds();
        return skillIds.stream()
                .map(sid -> {
                    try {
                        return skillService.getSkill(sid);
                    } catch (SkillNotFoundException e) {
                        log.warn("技能 {} 不存在，跳过", sid);
                        return null;
                    } catch (IOException e) {
                        log.error("读取技能 {} 失败: {}", sid, e.getMessage());
                        return null;
                    }
                })
                .filter(s -> s != null)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String getSkillContent(String sessionId, String skillId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }

        try {
            Skill skill = skillService.getSkill(skillId);
            return skill.getContent();
        } catch (SkillNotFoundException e) {
            throw new RuntimeException("Skill not found: " + skillId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill content: " + skillId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String getSkillReference(String sessionId, String skillId, String path) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }

        try {
            Skill skill = skillService.getSkill(skillId);
            return skill.getReferenceFile(path);
        } catch (SkillNotFoundException e) {
            throw new RuntimeException("Skill not found: " + skillId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill reference: " + skillId + "/" + path, e);
        }
    }

    /**
     * 获取会话（数据库）
     */
    @Transactional(readOnly = true)
    public ConversationSession getSession(String sessionId) {
        ConversationSessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        return EntityConverter.toConversationSession(entity);
    }

    /**
     * 获取用户的所有会话
     */
    @Transactional(readOnly = true)
    public List<ConversationSession> listUserSessions(Long userId) {
        List<ConversationSessionEntity> entities = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return entities.stream()
                .map(EntityConverter::toConversationSession)
                .toList();
    }
}
