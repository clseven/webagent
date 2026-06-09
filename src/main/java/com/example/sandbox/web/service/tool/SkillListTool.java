package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能列表工具 — 列出当前会话可用的技能
 *
 * <h3>技能系统（渐进式披露）</h3>
 * <p>项目使用三层渐进式披露来节省 token：</p>
 * <ol>
 *   <li>skill_list — 简历模式，只显示 ID 和一句话描述（这个工具）</li>
 *   <li>skill_activate — 激活技能，加载完整指令</li>
 *   <li>skill_reference — 读取技能的引用文件（模板、示例）</li>
 * </ol>
 *
 * <h3>使用时机</h3>
 * <p>LLM 在推理开始时调用此工具，了解有哪些技能可用，
 * 但不相关的技能不要激活，避免浪费 token。</p>
 */
@Component
public class SkillListTool implements Tool {

    private static final String NAME = "skill_list";

    @Autowired
    private ConversationService conversationService;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", new LinkedHashMap<>(),
                "required", java.util.List.of()
        );

        return new ToolDefinition(
                NAME,
                "列出当前会话已启用的技能。返回每个技能的 ID 和一句话描述。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            List<Skill> skills = conversationService.getEnabledSkills(sessionId);
            if (skills == null || skills.isEmpty()) {
                return "当前会话未启用任何技能";
            }

            StringBuilder sb = new StringBuilder();
            for (Skill skill : skills) {
                sb.append("- ").append(skill.getId());
                if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                    sb.append(": ").append(skill.getDescription());
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "获取技能列表失败：" + e.getMessage();
        }
    }
}