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
import com.example.sandbox.web.model.response.SkillView;
import com.example.sandbox.web.repository.ChatMessageRepository;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.aio.AioClient;
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
import java.util.Map;
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
        addAssistantMessage(sessionId, content, reasoning, List.of());
    }

    /**
     * 保存助手消息及其过程展示事件。
     *
     * @param sessionId 会话 ID
     * @param content   助手回复正文
     * @param reasoning 思考链内容，可为 null
     * @param events    前端历史恢复所需的过程事件，可为空
     */
    @Override
    @Transactional
    public void addAssistantMessage(String sessionId, String content, String reasoning, List<Map<String, Object>> events) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        ChatMessageEntity message = EntityConverter.toChatMessageEntity(
                session, "assistant", content, reasoning, events);
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
            // 融合本地仓库与沙箱发现，覆盖 Agent 自行下载的 sandbox-only skill
            Map<String, Skill> available = collectEnabledSkills(sessionId, enabledSkillIds);

            if (!available.isEmpty()) {
                prompt.append("## 已启用技能\n\n");
                prompt.append("以下技能已为当前会话启用。所有技能都住在沙箱 ")
                      .append(Skill.SANDBOX_SKILL_ROOT).append("/<id>/。\n");
                prompt.append("调用 `skill_activate(skill_id=\"技能ID\")` 获取详细指令。\n\n");
                for (String skillId : enabledSkillIds) {
                    Skill skill = available.get(skillId);
                    if (skill != null) {
                        prompt.append(skill.toMetadataLine()).append("\n");
                    } else {
                        log.warn("技能 {} 既不在本地仓库也不在沙箱中，跳过", skillId);
                    }
                }
                prompt.append("\n");
            }
        }

        return prompt.toString();
    }

    /**
     * 收集会话已启用的技能：先查本地仓库，再用沙箱发现补齐 sandbox-only 项。
     *
     * <p>沙箱发现仅在有本地仓库缺项时执行一次，避免无谓的 shell 调用。</p>
     *
     * @param sessionId       会话 ID
     * @param enabledSkillIds 已启用的技能 ID 集合
     * @return id -> Skill 映射；任一来源都拿不到的技能不会出现在结果中
     */
    private Map<String, Skill> collectEnabledSkills(String sessionId, Set<String> enabledSkillIds) {
        Map<String, Skill> result = new java.util.LinkedHashMap<>();
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        for (String skillId : enabledSkillIds) {
            try {
                result.put(skillId, skillService.getSkill(skillId));
            } catch (SkillNotFoundException e) {
                missing.add(skillId);
            } catch (IOException e) {
                log.error("读取技能 {} 元数据失败: {}", skillId, e.getMessage());
                missing.add(skillId);
            }
        }
        if (!missing.isEmpty()) {
            for (Skill s : skillService.discoverFromSandbox(sessionId)) {
                if (missing.contains(s.getId())) {
                    result.put(s.getId(), s);
                }
            }
        }
        return result;
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
        if (skillId == null || !SAFE_SKILL_ID.matcher(skillId).matches()) {
            throw new IllegalArgumentException("Invalid skill ID: " + skillId);
        }
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        // 验证技能存在：先看本地仓库，再回退到沙箱发现；两边都没有就拒绝启用
        Skill localSkill = null;
        try {
            localSkill = skillService.getSkill(skillId);
        } catch (SkillNotFoundException ignored) {
            // 本地没有，下一步看沙箱
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill: " + skillId, e);
        }

        if (localSkill == null) {
            boolean inSandbox = skillService.discoverFromSandbox(sessionId).stream()
                    .anyMatch(s -> skillId.equals(s.getId()));
            if (!inSandbox) {
                throw new RuntimeException("Skill not found: " + skillId);
            }
        }

        session.enableSkill(skillId);
        sessionRepository.save(session);

        // 只在"本地有 + 沙箱已就绪"时推送种子文件；沙箱独有的 skill 无需推送
        if (localSkill != null && localSkill.getLocalPath() != null
                && sandboxService != null && sandboxService.hasSandbox(sessionId)) {
            log.info("同步技能文件到沙箱: {}", skillId);
            fileSyncService.syncSkill(sessionId, localSkill.getLocalPath(), localSkill.getId());
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
                AioClient client = sandboxClientFactory.getAioClient(sessionId);
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
     * 删除会话实体，级联删除消息并清理会话技能关联。
     *
     * @param sessionId 会话 ID
     */
    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        if (sessionRepository.existsById(sessionId)) {
            sessionRepository.deleteById(sessionId);
        }
    }

    /**
     * 在同一事务中删除指定用户拥有的多个会话实体。
     *
     * <p>使用实体删除以保留消息和会话技能关联的级联清理；不存在或属于其他用户的 ID 会被忽略。</p>
     *
     * @param sessionIds 待删除的会话 ID 集合
     * @param userId     当前用户 ID
     * @return 实际成功删除的会话 ID
     */
    @Override
    @Transactional
    public List<String> deleteSessionsOwnedByUser(Set<String> sessionIds, Long userId) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<ConversationSessionEntity> sessions = sessionRepository.findByIdInAndUserId(sessionIds, userId);
        List<String> deletedSessionIds = sessions.stream()
                .map(ConversationSessionEntity::getId)
                .toList();
        sessionRepository.deleteAll(sessions);
        return deletedSessionIds;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Skill> getEnabledSkills(String sessionId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        Set<String> skillIds = session.getEnabledSkillIds();
        if (skillIds.isEmpty()) {
            return List.of();
        }
        Map<String, Skill> available = collectEnabledSkills(sessionId, skillIds);
        // 保留 enabledSkillIds 的顺序输出
        return skillIds.stream().map(available::get).filter(s -> s != null).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String getSkillContent(String sessionId, String skillId) {
        if (skillId == null || !SAFE_SKILL_ID.matcher(skillId).matches()) {
            throw new IllegalArgumentException("Invalid skill ID: " + skillId);
        }
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }

        Skill skill = resolveSessionSkill(sessionId, skillId);
        try {
            AioClient client = sandboxClientFactory.getAioClient(sessionId);
            String content = skill.getContent(client);
            return decorateActivationContent(client, skill, content);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read skill content: " + skillId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String getSkillReference(String sessionId, String skillId, String path) {
        if (skillId == null || !SAFE_SKILL_ID.matcher(skillId).matches()) {
            throw new IllegalArgumentException("Invalid skill ID: " + skillId);
        }
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }

        Skill skill = resolveSessionSkill(sessionId, skillId);
        try {
            AioClient client = sandboxClientFactory.getAioClient(sessionId);
            return skill.getReferenceFile(client, path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read skill reference: " + skillId + "/" + path, e);
        }
    }

    /**
     * 解析会话上下文中的 skill：先看本地仓库（含 frontmatter），再回退到沙箱发现。
     *
     * <p>本地仓库找到的 skill 携带本地 frontmatter，但运行期 IO 仍走沙箱；沙箱独有的 skill 由
     * {@code SkillServiceImpl.discoverFromSandbox} 实时扫描得到，所有 IO 同样走沙箱。</p>
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     * @return 解析到的技能；找不到时抛 {@link RuntimeException}
     */
    private Skill resolveSessionSkill(String sessionId, String skillId) {
        try {
            return skillService.getSkill(skillId);
        } catch (SkillNotFoundException e) {
            // 退回到沙箱发现
            for (Skill s : skillService.discoverFromSandbox(sessionId)) {
                if (skillId.equals(s.getId())) {
                    return s;
                }
            }
            throw new RuntimeException("Skill not found: " + skillId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill: " + skillId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkillView> listSessionSkills(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        Set<String> enabledIds = session.getEnabledSkillIds();

        // 本地仓库 + 沙箱发现，按 id 融合
        Map<String, Skill> localById = new java.util.LinkedHashMap<>();
        for (Skill s : skillService.listSkills()) {
            localById.put(s.getId(), s);
        }
        Map<String, Skill> sandboxById = new java.util.LinkedHashMap<>();
        for (Skill s : skillService.discoverFromSandbox(sessionId)) {
            sandboxById.put(s.getId(), s);
        }

        // 合并 id 集合，按字母序输出，前端方便展示
        java.util.Set<String> allIds = new java.util.TreeSet<>();
        allIds.addAll(localById.keySet());
        allIds.addAll(sandboxById.keySet());

        List<SkillView> result = new java.util.ArrayList<>(allIds.size());
        for (String id : allIds) {
            Skill local = localById.get(id);
            Skill sandbox = sandboxById.get(id);
            Skill.Source source;
            Skill representative;
            if (local != null && sandbox != null) {
                source = Skill.Source.BOTH;
                // 优先用本地仓库的元数据（通常更新更及时）
                representative = local;
            } else if (local != null) {
                source = Skill.Source.LOCAL;
                representative = local;
            } else {
                source = Skill.Source.SANDBOX;
                representative = sandbox;
            }
            result.add(SkillView.from(representative, source, enabledIds.contains(id)));
        }
        return result;
    }

    /**
     * 给 skill_activate 返回内容拼上沙箱定位头部，告诉 LLM "我在哪、有哪些 scripts/references"。
     *
     * <p>这是渐进式披露 L2 的体验优化：一次激活就把"绝对路径根 + 可用资源清单"暴露出来，
     * 避免 LLM 再花一轮调用列目录或猜路径。</p>
     *
     * @param client 当前会话沙箱客户端
     * @param skill  目标技能
     * @param body   SKILL.md 正文
     * @return 拼接后的完整激活内容
     */
    private String decorateActivationContent(AioClient client, Skill skill, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Skill: ").append(skill.getId()).append(" ===\n");
        sb.append("Sandbox base path: ").append(skill.sandboxBasePath()).append("/\n");

        List<String> scripts;
        List<String> references;
        try {
            scripts = skill.listScripts(client);
        } catch (Exception e) {
            log.debug("列 scripts 失败 skill={}: {}", skill.getId(), e.getMessage());
            scripts = List.of();
        }
        try {
            references = skill.listReferences(client);
        } catch (Exception e) {
            log.debug("列 references 失败 skill={}: {}", skill.getId(), e.getMessage());
            references = List.of();
        }
        if (!scripts.isEmpty()) {
            sb.append("Available scripts: ").append(String.join(", ", scripts)).append("\n");
            sb.append("  Run with shell: bash ").append(skill.sandboxBasePath()).append("/<script>\n");
        }
        if (!references.isEmpty()) {
            sb.append("Available references: ").append(String.join(", ", references)).append("\n");
            sb.append("  Read with skill_reference(skill_id, path)\n");
        }
        sb.append("\n=== SKILL.md ===\n");
        sb.append(body != null ? body : "");
        return sb.toString();
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
