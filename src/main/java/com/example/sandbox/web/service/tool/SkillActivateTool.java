package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 激活技能工具 — 加载技能的完整指令内容
 *
 * <h3>渐进式披露第二步</h3>
 * <p>当 LLM 判断某个技能与当前任务相关时，调用此工具获取详细指导。
 * 技能指令会被加载到 system prompt 中，指导后续的工具调用和推理。</p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>skill_id — 要激活的技能 ID（从 skill_list 获取）</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>
 * LLM: 用户让我做头脑风暴，这需要 brainstorming 技能
 * Action: skill_activate
 * Action Input: {"skill_id": "brainstorming"}
 * </pre>
 */
@Component
public class SkillActivateTool implements Tool {

    private static final String NAME = "skill_activate";

    @Autowired
    private ConversationService conversationService;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skill_id", Map.of(
                "type", "string",
                "description", "要激活的技能 ID"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("skill_id")
        );

        return new ToolDefinition(
                NAME,
                "激活一个技能：加载该技能的完整指令内容（来自沙箱 /home/gem/skills/<id>/SKILL.md）。"
                        + "返回内容会附带沙箱绝对路径、可用 scripts 与 references 清单，方便后续直接调用。"
                        + "先使用 skill_list 查看有哪些可用技能，再决定激活哪个。"
                        + "当你判断某个技能与当前任务相关时，调用此工具获取详细指导。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String skillId = (String) arguments.get("skill_id");
        if (skillId == null || skillId.isBlank()) {
            return "错误：技能 ID 不能为空";
        }

        try {
            String content = conversationService.getSkillContent(sessionId, skillId);
            return content;
        } catch (Exception e) {
            return "激活技能失败：" + e.getMessage();
        }
    }
}