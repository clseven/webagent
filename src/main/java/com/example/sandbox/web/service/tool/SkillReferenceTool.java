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
 * 技能引用工具 — 读取技能关联的引用文件
 *
 * <h3>渐进式披露第三步</h3>
 * <p>技能通常会引用一些模板、示例或参考文档。
 * 当技能指令中提到需要这些引用文件时，调用此工具按需加载。</p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>skill_id — 技能 ID</li>
 *   <li>path — 引用文件的路径</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <p>比如"代码审查"技能引用了一份审查清单模板，
 * 激活技能后，LLM 调用此工具加载模板内容来使用。</p>
 */
@Component
public class SkillReferenceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillReferenceTool.class);

    private static final String NAME = "skill_reference";

    /** 技能运行时服务，负责从当前会话沙箱读取引用文件。 */
    @Autowired
    private AgentSkillRuntimeService skillRuntimeService;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skill_id", Map.of(
                "type", "string",
                "description", "要读取引用文件的技能 ID"
        ));
        properties.put("path", Map.of(
                "type", "string",
                "description", "相对于该 skill 目录的路径"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of("skill_id", "path")
        );

        return new ToolDefinition(
                NAME,
                "读取已激活技能的附属引用文件（模板、示例等）。",
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        Object skillIdValue = arguments.get("skill_id");
        Object pathValue = arguments.get("path");
        String skillId = skillIdValue instanceof String value ? value : null;
        String path = pathValue instanceof String value ? value : null;

        if (skillId == null || skillId.isBlank()) {
            return "错误：技能 ID 不能为空";
        }
        if (path == null || path.isBlank()) {
            return "错误：引用文件路径不能为空";
        }

        try {
            return skillRuntimeService.getSkillReference(sessionId, skillId, path);
        } catch (Exception e) {
            log.error("读取技能引用文件失败: sessionId={}, skillId={}, path={}", sessionId, skillId, path, e);
            return "错误：读取引用文件失败 - " + e.getMessage();
        }
    }
}
