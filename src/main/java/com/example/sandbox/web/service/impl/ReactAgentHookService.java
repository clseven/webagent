package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.tool.ImageBuffer;
import com.example.sandbox.web.service.tool.RunSubagentTool;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /** 视觉模型，用于把图片字节转换为主 Agent 可读取的文本观察。 */
    private final LlmService visionLlm;

    /**
     * 创建 Hook 装配服务。
     *
     * @param imageBuffer 图片缓冲区
     * @param visionLlm   视觉模型服务
     */
    public ReactAgentHookService(ImageBuffer imageBuffer,
                                 @Qualifier("visionLlm") LlmService visionLlm) {
        this.imageBuffer = imageBuffer;
        this.visionLlm = visionLlm;
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
        reactAgent.registerPostToolUseHook(viewImageHook(userMessage, plan));
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
        reactAgent.registerPostToolUseHook(viewImageHook(userMessage, plan));
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
    ReactAgent.PostToolUseHook viewImageHook() {
        return viewImageHook(null, null);
    }

    /**
     * 构建 view_image 工具的图片观察 Hook。
     *
     * @param userMessage 本轮用户请求，可为空
     * @param plan        本轮规划文本，可为空
     * @return PostToolUseHook 实例
     */
    private ReactAgent.PostToolUseHook viewImageHook(String userMessage, String plan) {
        return (toolCall, result, sessionId) -> {
            if (!"view_image".equals(toolCall.name())) {
                return null;
            }
            ImageBuffer.Entry entry = imageBuffer.take(sessionId);
            if (entry == null) {
                log.warn("view_image 工具已执行，但 ImageBuffer 中没有图片数据，sessionId={}", sessionId);
                return null;
            }
            log.info("PostToolUseHook 请求视觉观察: path={} size={} bytes",
                    entry.path(), entry.bytes().length);
            return buildVisionObservationMessage(entry, userMessage, plan);
        };
    }

    /**
     * 调用视觉模型生成图片观察文本，并封装为主 Agent 可读取的上下文消息。
     *
     * @param entry       图片缓冲区条目
     * @param userMessage 本轮用户请求，可为空
     * @param plan        本轮规划文本，可为空
     * @return 文本形式的图片观察消息
     */
    private ChatMessage buildVisionObservationMessage(ImageBuffer.Entry entry, String userMessage, String plan) {
        String prompt = buildVisionUserPrompt(entry.path(), userMessage, plan);
        ChatMessage imageMessage = ChatMessage.userMessageWithImage(prompt, entry.bytes(), entry.mimeType());
        try {
            LlmResponse response = visionLlm.chatWithSystemResponse(visionSystemPrompt(), List.of(imageMessage));
            String observation = response != null ? response.getContent() : null;
            if (observation == null || observation.isBlank()) {
                observation = "视觉模型没有返回有效观察结果，主 Agent 不能依赖该图片判断。";
            }
            return ChatMessage.userMessage("""
                    图片观察结果（由视觉模型生成）
                    路径：%s
                    观察：
                    %s
                    """.formatted(entry.path(), observation.trim()));
        } catch (Exception e) {
            log.warn("视觉模型分析图片失败: path={}", entry.path(), e);
            return ChatMessage.userMessage("""
                    图片已加载，但视觉模型暂时无法分析。
                    路径：%s
                    错误：%s
                    """.formatted(entry.path(), e.getMessage()));
        }
    }

    /**
     * 构建视觉模型系统提示词。
     *
     * @return 视觉观察提示词
     */
    private String visionSystemPrompt() {
        return """
                你是视觉观察器。请根据图片内容输出给主 Agent 使用的客观观察结果。
                如果用户没有提出具体问题，就概括图片主要内容、可见文字、界面状态和明显细节。
                如果用户提出了具体问题，就优先提取与问题相关的信息。
                看不清或不确定的内容要明确说明，不要猜测。
                只输出观察结果，不要寒暄。
                """;
    }

    /**
     * 构建携带图片的用户消息文本，让视觉模型知道图片路径和任务背景。
     *
     * @param path        图片路径
     * @param userMessage 本轮用户请求，可为空
     * @param plan        本轮规划文本，可为空
     * @return 视觉模型用户消息文本
     */
    private String buildVisionUserPrompt(String path, String userMessage, String plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("图片路径：").append(path);
        if (userMessage != null && !userMessage.isBlank()) {
            builder.append("\n用户当前请求：").append(userMessage);
        }
        if (plan != null && !plan.isBlank()) {
            builder.append("\n主 Agent 当前计划：").append(plan);
        }
        return builder.toString();
    }
}
