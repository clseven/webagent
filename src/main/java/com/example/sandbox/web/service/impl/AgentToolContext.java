package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.tool.KnowledgeSearchTool;

import java.util.List;

/**
 * Agent 单轮执行可用工具上下文。
 *
 * <p>该对象把工具实例、发送给 LLM 的工具定义以及需要清理的动态工具状态绑定在一起，
 * 让编排层只消费结果，不关心工具过滤和动态描述注入细节。</p>
 *
 * @param filteredTools       当前会话真正可执行的工具实例
 * @param toolDefinitions     发送给规划器和执行器的工具定义
 * @param knowledgeSearchTool 被动态注入知识库上下文的工具；没有关联知识库时为 null
 * @param targetType          当前沙箱目标类型，用于日志观测
 */
public record AgentToolContext(List<Tool> filteredTools,
                               List<ToolDefinition> toolDefinitions,
                               KnowledgeSearchTool knowledgeSearchTool,
                               String targetType) {

    /** 空工具上下文，用于本轮无需注入工具的轮次。 */
    public static AgentToolContext empty() {
        return new AgentToolContext(List.of(), List.of(), null, "none");
    }
}
