package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.SkillService;
import com.example.sandbox.web.service.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能列表工具 — 列出当前会话可用的技能。
 *
 * <h3>渐进式披露第一步</h3>
 * <ol>
 *   <li><b>skill_list</b>（本工具） — 简历模式，只显示 ID 和一句话描述</li>
 *   <li><b>skill_activate</b> — 激活技能，加载完整指令并附带沙箱定位信息</li>
 *   <li><b>skill_reference</b> — 读取技能的引用文件（模板、示例）</li>
 * </ol>
 *
 * <h3>返回内容</h3>
 * <p>包含两部分：</p>
 * <ul>
 *   <li><b>已启用技能</b>：用户在前端为该会话开启过的技能</li>
 *   <li><b>沙箱新发现</b>：当前会话沙箱 {@code /home/gem/skills/} 下存在但尚未启用的技能
 *   （通常是 Agent 自行下载或生成的），需要用户在前端开关启用后才能 activate。</li>
 * </ul>
 *
 * <p>LLM 在推理开始时调用此工具了解可用能力；不相关的技能不要 activate，避免浪费 token。</p>
 */
@Component
public class SkillListTool implements Tool {

    private static final String NAME = "skill_list";

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private SkillService skillService;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", new LinkedHashMap<>(),
                "required", java.util.List.of()
        );

        return new ToolDefinition(
                NAME,
                "【首先使用此工具】列出当前会话已启用的技能；同时报告沙箱 /home/gem/skills/ 下存在但尚未启用的技能"
                        + "（通常是后下载的，需要用户在前端启用）。返回每个技能的 ID 和一句话描述。"
                        + "在调用 skill_activate 之前必须先调用此工具，了解有哪些可用技能。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            List<Skill> enabled = conversationService.getEnabledSkills(sessionId);
            List<Skill> sandboxAll = skillService.discoverFromSandbox(sessionId);

            Set<String> enabledIds = new HashSet<>();
            if (enabled != null) {
                for (Skill s : enabled) enabledIds.add(s.getId());
            }
            List<Skill> sandboxOnlyUnenabled = sandboxAll.stream()
                    .filter(s -> !enabledIds.contains(s.getId()))
                    .toList();

            if ((enabled == null || enabled.isEmpty()) && sandboxOnlyUnenabled.isEmpty()) {
                return "当前会话未启用任何技能；沙箱 " + Skill.SANDBOX_SKILL_ROOT + " 目录下也没有可发现的技能。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 已启用技能\n");
            if (enabled == null || enabled.isEmpty()) {
                sb.append("（无）\n");
            } else {
                for (Skill skill : enabled) {
                    sb.append("- ").append(skill.getId());
                    if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                        sb.append(": ").append(skill.getDescription());
                    }
                    sb.append("\n");
                }
            }

            if (!sandboxOnlyUnenabled.isEmpty()) {
                sb.append("\n## 沙箱中发现但未启用\n");
                sb.append("以下技能存在于 ").append(Skill.SANDBOX_SKILL_ROOT)
                        .append("/ 但未在本会话启用，提示用户在前端 Skill 页面启用后才能调用：\n");
                for (Skill skill : sandboxOnlyUnenabled) {
                    sb.append("- ").append(skill.getId());
                    if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                        sb.append(": ").append(skill.getDescription());
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取技能列表失败：" + e.getMessage();
        }
    }
}
