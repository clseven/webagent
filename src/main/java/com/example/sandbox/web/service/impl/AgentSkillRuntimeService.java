package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.model.entity.AgentAppEntity;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.response.SkillView;
import com.example.sandbox.web.repository.ConversationSessionRepository;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Agent 技能运行时服务。
 *
 * <p>集中承接会话内技能发现、启用禁用、激活、引用读取和规划元数据过滤。
 * 本服务只把本地仓库当上传源，运行期读取统一来自当前会话沙箱发现到的真实 skill 路径。</p>
 */
@Service
public class AgentSkillRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillRuntimeService.class);

    /** 前端和工具允许传入的技能 ID 格式。 */
    private static final Pattern SAFE_SKILL_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.\\-]{0,63}$");

    /** 会话仓库，用于读取和更新会话启用技能集合。 */
    private final ConversationSessionRepository sessionRepository;

    /** 技能仓库服务，用于本地上传源定位和沙箱发现。 */
    private final SkillService skillService;

    /** 沙箱服务，用于判断会话沙箱状态。 */
    private final SandboxService sandboxService;

    /** 沙箱客户端工厂，用于读取技能内容和清理技能目录。 */
    private final SandboxClientFactory sandboxClientFactory;

    /** 文件同步服务，用于把本地上传源推送到沙箱。 */
    private final FileSyncService fileSyncService;

    /**
     * 创建技能运行时服务。
     *
     * @param sessionRepository 会话仓库
     * @param skillService      技能仓库服务
     * @param sandboxService    沙箱服务
     * @param sandboxClientFactory 沙箱客户端工厂
     * @param fileSyncService   文件同步服务
     */
    public AgentSkillRuntimeService(ConversationSessionRepository sessionRepository,
                                    SkillService skillService,
                                    SandboxService sandboxService,
                                    SandboxClientFactory sandboxClientFactory,
                                    @Lazy FileSyncService fileSyncService) {
        this.sessionRepository = sessionRepository;
        this.skillService = skillService;
        this.sandboxService = sandboxService;
        this.sandboxClientFactory = sandboxClientFactory;
        this.fileSyncService = fileSyncService;
    }

    /**
     * 构建当前会话已启用技能的系统提示片段。
     *
     * @param sessionId 会话 ID
     * @return 技能提示片段；没有启用技能或沙箱中找不到时返回空字符串
     */
    @Transactional(readOnly = true)
    public String buildEnabledSkillPrompt(String sessionId) {
        ConversationSessionEntity session = getSessionEntity(sessionId);
        return buildEnabledSkillPrompt(sessionId, session.getEnabledSkillIds());
    }

    /**
     * 启用指定技能。
     *
     * <p>如果技能来自本地上传源且沙箱已就绪，会同步本地目录到沙箱；沙箱已存在的技能不会重复上传。</p>
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    @Transactional
    public void enableSkill(String sessionId, String skillId) {
        validateSkillId(skillId);
        ConversationSessionEntity session = getSessionEntity(sessionId);
        Skill localSkill = findLocalSkillOrNull(skillId);
        if (localSkill == null && !existsInSandbox(sessionId, skillId)) {
            throw new RuntimeException("Skill not found: " + skillId);
        }

        session.enableSkill(skillId);
        sessionRepository.save(session);

        if (localSkill != null && localSkill.getLocalPath() != null
                && sandboxService != null && sandboxService.hasSandbox(sessionId)) {
            log.info("同步技能文件到沙箱: {}", skillId);
            fileSyncService.syncSkill(sessionId, localSkill.getLocalPath(), localSkill.getId());
        }
    }

    /**
     * 禁用指定技能并清理当前沙箱中的技能目录。
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    @Transactional
    public void disableSkill(String sessionId, String skillId) {
        validateSkillId(skillId);
        ConversationSessionEntity session = getSessionEntity(sessionId);
        session.disableSkill(skillId);
        sessionRepository.save(session);
        removeSkillFromSandbox(sessionId, skillId);
    }

    /**
     * 获取当前会话启用技能列表。
     *
     * @param sessionId 会话 ID
     * @return 已启用技能列表，按会话启用顺序输出
     */
    @Transactional(readOnly = true)
    public List<Skill> getEnabledSkills(String sessionId) {
        ConversationSessionEntity session = getSessionEntity(sessionId);
        Set<String> skillIds = session.getEnabledSkillIds();
        if (skillIds.isEmpty()) {
            return List.of();
        }
        Map<String, Skill> available = collectEnabledSkills(sessionId, skillIds);
        return skillIds.stream().map(available::get).filter(s -> s != null).toList();
    }

    /**
     * 读取当前会话中指定技能的完整激活内容。
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     * @return 带沙箱定位信息的技能内容
     */
    @Transactional(readOnly = true)
    public String getSkillContent(String sessionId, String skillId) {
        validateSkillId(skillId);
        ensureSessionExists(sessionId);
        Skill skill = resolveSessionSkill(sessionId, skillId);
        try {
            AioClient client = sandboxClientFactory.getAioClient(sessionId);
            String content = skill.getContent(client);
            return decorateActivationContent(client, skill, content);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read skill content: " + skillId, e);
        }
    }

    /**
     * 读取技能引用文件。
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     * @param path      相对 skill 根目录的引用文件路径
     * @return 引用文件内容
     */
    @Transactional(readOnly = true)
    public String getSkillReference(String sessionId, String skillId, String path) {
        validateSkillId(skillId);
        ensureSessionExists(sessionId);
        Skill skill = resolveSessionSkill(sessionId, skillId);
        try {
            AioClient client = sandboxClientFactory.getAioClient(sessionId);
            return skill.getReferenceFile(client, path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read skill reference: " + skillId + "/" + path, e);
        }
    }

    /**
     * 列出当前会话可见的所有技能。
     *
     * @param sessionId 会话 ID
     * @return 前端技能页面视图列表
     */
    @Transactional(readOnly = true)
    public List<SkillView> listSessionSkills(String sessionId) {
        ConversationSessionEntity session = getSessionEntity(sessionId);
        Set<String> enabledIds = session.getEnabledSkillIds();

        Map<String, Skill> localById = new LinkedHashMap<>();
        for (Skill s : skillService.listSkills()) {
            localById.put(s.getId(), s);
        }
        Map<String, Skill> sandboxById = new LinkedHashMap<>();
        for (Skill s : skillService.discoverFromSandbox(sessionId)) {
            sandboxById.put(s.getId(), s);
        }

        Set<String> allIds = new TreeSet<>();
        allIds.addAll(localById.keySet());
        allIds.addAll(sandboxById.keySet());

        return allIds.stream()
                .map(id -> toSkillView(id, localById.get(id), sandboxById.get(id), enabledIds.contains(id)))
                .toList();
    }

    /**
     * 构建 skill_list 工具返回内容。
     *
     * @param sessionId 会话 ID
     * @return 技能列表文本
     */
    @Transactional(readOnly = true)
    public String formatSkillList(String sessionId) {
        List<Skill> enabled = getEnabledSkills(sessionId);
        List<Skill> sandboxAll = skillService.discoverFromSandbox(sessionId);
        Set<String> enabledIds = new HashSet<>();
        for (Skill skill : enabled) {
            enabledIds.add(skill.getId());
        }
        List<Skill> sandboxOnlyUnenabled = sandboxAll.stream()
                .filter(s -> !enabledIds.contains(s.getId()))
                .toList();

        if (enabled.isEmpty() && sandboxOnlyUnenabled.isEmpty()) {
            return "当前会话未启用任何技能；沙箱 " + Skill.SANDBOX_SKILL_ROOT + " 目录下也没有可发现的技能。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 已启用技能\n");
        if (enabled.isEmpty()) {
            sb.append("（无）\n");
        } else {
            appendSkillLines(sb, enabled);
        }

        if (!sandboxOnlyUnenabled.isEmpty()) {
            sb.append("\n## 沙箱中发现但未启用\n");
            sb.append("以下技能存在于 ").append(Skill.SANDBOX_SKILL_ROOT)
                    .append("/ 但未在本会话启用，提示用户在前端 Skill 页面启用后才能调用：\n");
            appendSkillLines(sb, sandboxOnlyUnenabled);
        }
        return sb.toString();
    }

    /**
     * 获取规划阶段可见的技能元数据。
     *
     * @param sessionId 会话 ID
     * @param app       当前 Agent 应用；可为 null
     * @return 过滤后的技能列表
     */
    public List<Skill> findPlanningSkills(String sessionId, AgentAppEntity app) {
        List<Skill> skills = skillService.discoverFromSandbox(sessionId);
        if (app != null && !app.getSkillIds().isEmpty()) {
            Set<String> appSkillIds = app.getSkillIds();
            skills = skills.stream()
                    .filter(s -> appSkillIds.contains(s.getId()))
                    .toList();
            log.info("【Skill 过滤】应用: {}, 可用技能: {}",
                    app.getId(), skills.stream().map(Skill::getId).toList());
        }
        return skills;
    }

    /**
     * 构建规划器使用的会话上下文。
     *
     * @param session         当前会话
     * @param enhancedContext 已组合好的增强上下文
     * @return 会话上下文文本
     */
    public String buildSessionContext(ConversationSession session, String enhancedContext) {
        StringBuilder sb = new StringBuilder();
        if (session.getEnabledSkillIds() != null && !session.getEnabledSkillIds().isEmpty()) {
            Map<String, Skill> sandboxSkills = new LinkedHashMap<>();
            for (Skill s : skillService.discoverFromSandbox(session.getSessionId())) {
                sandboxSkills.put(s.getId(), s);
            }
            sb.append("## 已启用技能\n\n");
            for (String skillId : session.getEnabledSkillIds()) {
                Skill skill = sandboxSkills.get(skillId);
                if (skill != null) {
                    sb.append(skill.toMetadataLine()).append("\n");
                } else {
                    log.warn("构建会话上下文时技能 {} 在沙箱中未找到", skillId);
                    sb.append("- ").append(skillId).append("\n");
                }
            }
            sb.append("\n");
        }
        if (enhancedContext != null && !enhancedContext.isEmpty()) {
            sb.append(enhancedContext).append("\n");
        }
        return sb.toString();
    }

    /**
     * 按指定技能 ID 集合构建系统提示片段。
     *
     * @param sessionId       会话 ID
     * @param enabledSkillIds 已启用技能 ID
     * @return 系统提示片段
     */
    private String buildEnabledSkillPrompt(String sessionId, Set<String> enabledSkillIds) {
        log.info("构建系统提示，会话 {} 启用技能数: {}", sessionId, enabledSkillIds.size());
        if (enabledSkillIds.isEmpty()) {
            return "";
        }
        Map<String, Skill> available = collectEnabledSkills(sessionId, enabledSkillIds);
        if (available.isEmpty()) {
            return "";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("## 已启用技能\n\n");
        prompt.append("以下技能已为当前会话启用。技能文件来自当前会话沙箱 ")
                .append(Skill.SANDBOX_SKILL_ROOT).append(" 下发现到的真实技能目录。\n");
        prompt.append("调用 `skill_activate(skill_id=\"技能ID\")` 获取详细指令。\n\n");
        for (String skillId : enabledSkillIds) {
            Skill skill = available.get(skillId);
            if (skill != null) {
                prompt.append(skill.toMetadataLine()).append("\n");
            } else {
                log.warn("技能 {} 不在当前会话沙箱中，跳过", skillId);
            }
        }
        prompt.append("\n");
        return prompt.toString();
    }

    /**
     * 收集当前会话已启用技能。
     *
     * @param sessionId       会话 ID
     * @param enabledSkillIds 已启用技能 ID
     * @return 技能 ID 到技能元数据的映射
     */
    private Map<String, Skill> collectEnabledSkills(String sessionId, Set<String> enabledSkillIds) {
        Map<String, Skill> result = new LinkedHashMap<>();
        Map<String, Skill> sandboxById = new LinkedHashMap<>();
        for (Skill skill : skillService.discoverFromSandbox(sessionId)) {
            sandboxById.put(skill.getId(), skill);
        }
        for (String skillId : enabledSkillIds) {
            Skill found = sandboxById.get(skillId);
            if (found != null) {
                result.put(skillId, found);
            }
        }
        return result;
    }

    /**
     * 将本地/沙箱技能合并成前端视图项。
     *
     * @param id       技能 ID
     * @param local    本地上传源中的技能；可为 null
     * @param sandbox  沙箱中的技能；可为 null
     * @param enabled  是否已启用
     * @return 前端视图项
     */
    private SkillView toSkillView(String id, Skill local, Skill sandbox, boolean enabled) {
        Skill.Source source;
        Skill representative;
        if (local != null && sandbox != null) {
            source = Skill.Source.BOTH;
            representative = local;
        } else if (local != null) {
            source = Skill.Source.LOCAL;
            representative = local;
        } else {
            source = Skill.Source.SANDBOX;
            representative = sandbox;
        }
        return SkillView.from(representative, source, enabled);
    }

    /**
     * 向文本缓冲区追加技能摘要行。
     *
     * @param sb     文本缓冲区
     * @param skills 技能列表
     */
    private void appendSkillLines(StringBuilder sb, List<Skill> skills) {
        for (Skill skill : skills) {
            sb.append("- ").append(skill.getId());
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append(": ").append(skill.getDescription());
            }
            sb.append("\n");
        }
    }

    /**
     * 从本地上传源查找技能，找不到时返回 null。
     *
     * @param skillId 技能 ID
     * @return 本地技能上传源；找不到时返回 null
     */
    private Skill findLocalSkillOrNull(String skillId) {
        try {
            return skillService.getSkill(skillId);
        } catch (SkillNotFoundException ignored) {
            return null;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill: " + skillId, e);
        }
    }

    /**
     * 判断当前会话沙箱中是否存在指定技能。
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     * @return 存在时返回 true
     */
    private boolean existsInSandbox(String sessionId, String skillId) {
        return skillService.discoverFromSandbox(sessionId).stream()
                .anyMatch(s -> skillId.equals(s.getId()));
    }

    /**
     * 解析当前会话沙箱中的技能。
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     * @return 技能元数据
     */
    private Skill resolveSessionSkill(String sessionId, String skillId) {
        for (Skill skill : skillService.discoverFromSandbox(sessionId)) {
            if (skillId.equals(skill.getId())) {
                return skill;
            }
        }
        throw new RuntimeException("Skill not found: " + skillId);
    }

    /**
     * 从沙箱清理指定技能目录。
     *
     * <p>优先删除沙箱发现到的真实根目录；未发现时回退到旧版标准路径。</p>
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    private void removeSkillFromSandbox(String sessionId, String skillId) {
        if (sandboxService == null || !sandboxService.hasSandbox(sessionId)) {
            return;
        }
        try {
            AioClient client = sandboxClientFactory.getAioClient(sessionId);
            Skill sandboxSkill = null;
            for (Skill s : skillService.discoverFromSandbox(sessionId)) {
                if (skillId.equals(s.getId())) {
                    sandboxSkill = s;
                    break;
                }
            }
            String skillPath = sandboxSkill != null
                    ? sandboxSkill.sandboxBasePath()
                    : Skill.SANDBOX_SKILL_ROOT + "/" + skillId;
            client.execCommand("rm -rf " + shellQuote(skillPath));
            log.info("已从沙箱移除技能文件: {}", skillId);
        } catch (Exception e) {
            log.warn("清理沙箱技能文件失败: {}", skillId, e);
        }
    }

    /**
     * 给 skill_activate 返回内容拼上沙箱定位头部。
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
     * 获取会话实体。
     *
     * @param sessionId 会话 ID
     * @return 会话实体
     */
    private ConversationSessionEntity getSessionEntity(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    /**
     * 校验会话存在。
     *
     * @param sessionId 会话 ID
     */
    private void ensureSessionExists(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    /**
     * 校验技能 ID。
     *
     * @param skillId 技能 ID
     */
    private void validateSkillId(String skillId) {
        if (skillId == null || !SAFE_SKILL_ID.matcher(skillId).matches()) {
            throw new IllegalArgumentException("Invalid skill ID: " + skillId);
        }
    }

    /**
     * 把字符串包装成单引号 shell 参数。
     *
     * @param value 原始路径
     * @return 转义后的 shell 参数
     */
    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
