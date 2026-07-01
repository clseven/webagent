package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.AgentSkillRuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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

    private static final Logger log = LoggerFactory.getLogger(SkillListTool.class);

    private static final String NAME = "skill_list";

    /** 技能运行时服务，负责格式化当前会话可见技能列表。 */
    @Autowired
    private AgentSkillRuntimeService skillRuntimeService;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", new LinkedHashMap<>(),
                "required", java.util.List.of()
        );

        return new ToolDefinition(
                NAME,
                "列出当前可用的技能（已启用 + 沙箱中发现）。返回每个技能的 ID 和一行描述。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            return skillRuntimeService.formatSkillList(sessionId);
        } catch (Exception e) {
            log.error("获取技能列表失败: sessionId={}", sessionId, e);
            return "错误：获取技能列表失败 - " + e.getMessage();
        }
    }
}
