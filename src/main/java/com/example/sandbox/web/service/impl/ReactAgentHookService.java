package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.tool.ImageBuffer;
import com.example.sandbox.web.service.tool.RunSubagentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ReactAgent Hook 装配服务。
 *
 * <p>集中注册执行器 Hook 和需要父 Agent 引用的工具，让编排层不需要关心
 * Hook 的注册顺序和细节。</p>
 */
@Service
public class ReactAgentHookService {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentHookService.class);

    /** 图片缓冲区，用于 view_image 工具和 PostToolUseHook 之间传递图片字节。 */
    private final ImageBuffer imageBuffer;

    /**
     * 创建 Hook 装配服务。
     *
     * @param imageBuffer 图片缓冲区
     */
    public ReactAgentHookService(ImageBuffer imageBuffer) {
        this.imageBuffer = imageBuffer;
    }

    /**
     * 为同步执行器注册 Hook。
     *
     * @param reactAgent 执行器实例
     * @param filteredTools 当前可用工具
     */
    public void configureForChat(ReactAgent reactAgent, List<Tool> filteredTools) {
        configureForChat(reactAgent, filteredTools, null, null);
    }

    /**
     * 为同步执行器注册 Hook，并注入本轮任务边界。
     *
     * @param reactAgent 执行器实例
     * @param filteredTools 当前可用工具
     * @param userMessage 本轮用户原始请求
     * @param plan PlanAgent 生成的计划文本
     */
    public void configureForChat(ReactAgent reactAgent, List<Tool> filteredTools,
                                 String userMessage, String plan) {
        reactAgent.registerPreToolUseHook(AgentHookExamples.logHook());
        reactAgent.registerPreToolUseHook(new AgentSearchPolicyHook(userMessage, plan));
        reactAgent.registerPostToolUseHook(viewImageHook());
        wireSubAgentParent(reactAgent, filteredTools);
    }

    /**
     * 为流式执行器注册 Hook。
     *
     * @param reactAgent 执行器实例
     * @param filteredTools 当前可用工具
     */
    public void configureForStream(ReactAgent reactAgent, List<Tool> filteredTools) {
        configureForStream(reactAgent, filteredTools, null, null);
    }

    /**
     * 为流式执行器注册 Hook，并注入本轮任务边界。
     *
     * @param reactAgent 执行器实例
     * @param filteredTools 当前可用工具
     * @param userMessage 本轮用户原始请求
     * @param plan PlanAgent 生成的计划文本
     */
    public void configureForStream(ReactAgent reactAgent, List<Tool> filteredTools,
                                   String userMessage, String plan) {
        reactAgent.registerPreToolUseHook(AgentHookExamples.logHook());
        reactAgent.registerPreToolUseHook(new AgentSearchPolicyHook(userMessage, plan));
        reactAgent.registerPostToolUseHook(AgentHookExamples.largeOutputHook());
        reactAgent.registerPostToolUseHook(viewImageHook());
        wireSubAgentParent(reactAgent, filteredTools);
    }

    /**
     * 将父 Agent 引用注入 run_subagent 工具。
     *
     * @param reactAgent 父执行器
     * @param filteredTools 当前可用工具
     */
    private void wireSubAgentParent(ReactAgent reactAgent, List<Tool> filteredTools) {
        for (Tool tool : filteredTools) {
            if (tool instanceof RunSubagentTool runSubagentTool) {
                runSubagentTool.setParentAgent(reactAgent);
                log.debug("RunSubagentTool 父 Agent 已注入");
                return;
            }
        }
    }

    /**
     * 构建 view_image 工具的图片注入 Hook。
     *
     * @return PostToolUseHook 实例
     */
    private ReactAgent.PostToolUseHook viewImageHook() {
        return (toolCall, result, sessionId) -> {
            if (!"view_image".equals(toolCall.name())) {
                return null;
            }
            ImageBuffer.Entry entry = imageBuffer.take(sessionId);
            if (entry == null) {
                log.warn("view_image 工具已执行，但 ImageBuffer 中没有图片数据，sessionId={}", sessionId);
                return null;
            }
            log.info("PostToolUseHook 注入图片: path={} size={} bytes",
                    entry.path(), entry.bytes().length);
            return ChatMessage.userMessageWithImage(
                    "(图片：" + entry.path() + ")",
                    entry.bytes(),
                    entry.mimeType()
            );
        };
    }
}
