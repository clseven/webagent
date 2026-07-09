package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.AgentConfigProperties;
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
 * Hook 的注册顺序和细节。具体包括：日志 Hook、文件状态检查 Hook（State Checks，
 * 受 {@code agent.hook.state-check-enabled} 开关控制）、图片观察 Hook、最终 TodoState
 * 门禁、工具并发执行开关（{@code agent.hook.concurrent-tool-execution-enabled}，回滚用），
 * 以及 run_subagent 父 Agent 注入。</p>
 */
@Service
public class ReactAgentHookService {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentHookService.class);

    private final ImageBuffer imageBuffer;
    private final LlmService visionLlm;
    private final AgentTodoService todoService;
    private final SandboxClientFactory sandboxClientFactory;
    private final FileCognitionState fileCognitionState;
    private final AgentConfigProperties agentConfig;

    public ReactAgentHookService(ImageBuffer imageBuffer,
                                 @Qualifier("visionLlm") LlmService visionLlm,
                                 AgentTodoService todoService,
                                 SandboxClientFactory sandboxClientFactory,
                                 FileCognitionState fileCognitionState,
                                 AgentConfigProperties agentConfig) {
        this.imageBuffer = imageBuffer;
        this.visionLlm = visionLlm;
        this.todoService = todoService;
        this.sandboxClientFactory = sandboxClientFactory;
        this.fileCognitionState = fileCognitionState;
        this.agentConfig = agentConfig;
    }

    // ---- 同步执行器（无 TurnPolicy，默认全能力） ----

    public void configureForChat(ReactAgent reactAgent, List<Tool> filteredTools) {
        configureForChat(reactAgent, filteredTools, null, null, null, TurnPolicy.FULL);
    }

    public void configureForChat(ReactAgent reactAgent, List<Tool> filteredTools,
                                 String userMessage, String plan) {
        configureForChat(reactAgent, filteredTools, null, userMessage, plan, TurnPolicy.FULL);
    }

    public void configureForChat(ReactAgent reactAgent, List<Tool> filteredTools,
                                 String sessionId, String userMessage, String plan) {
        configureForChat(reactAgent, filteredTools, sessionId, userMessage, plan, TurnPolicy.FULL);
    }

    // ---- 同步执行器（带 TurnPolicy） ----

    /**
     * 为同步执行器注册 Hook，并注入本轮策略开关。
     *
     * @param reactAgent    执行器实例
     * @param filteredTools 当前可用工具
     * @param sessionId     当前会话 ID，可为空
     * @param userMessage   本轮用户原始请求
     * @param plan          PlanAgent 生成的计划文本
     * @param policy        本轮策略开关
     */
    public void configureForChat(ReactAgent reactAgent, List<Tool> filteredTools,
                                 String sessionId, String userMessage, String plan,
                                 TurnPolicy policy) {
        reactAgent.registerPreToolUseHook(AgentHookExamples.logHook());
        registerFileStateCheck(reactAgent);
        reactAgent.registerPostToolUseHook(viewImageHook(userMessage, plan));
        registerFinalTodoGuard(reactAgent, sessionId, policy);
        applyConcurrencyToggle(reactAgent);
        wireSubAgentParent(reactAgent, filteredTools);
    }

    // ---- 流式执行器（无 TurnPolicy，默认全能力） ----

    public void configureForStream(ReactAgent reactAgent, List<Tool> filteredTools) {
        configureForStream(reactAgent, filteredTools, null, null, null, TurnPolicy.FULL);
    }

    public void configureForStream(ReactAgent reactAgent, List<Tool> filteredTools,
                                   String userMessage, String plan) {
        configureForStream(reactAgent, filteredTools, null, userMessage, plan, TurnPolicy.FULL);
    }

    public void configureForStream(ReactAgent reactAgent, List<Tool> filteredTools,
                                   String sessionId, String userMessage, String plan) {
        configureForStream(reactAgent, filteredTools, sessionId, userMessage, plan, TurnPolicy.FULL);
    }

    // ---- 流式执行器（带 TurnPolicy） ----

    /**
     * 为流式执行器注册 Hook，并注入本轮策略开关。
     *
     * @param reactAgent    执行器实例
     * @param filteredTools 当前可用工具
     * @param sessionId     当前会话 ID，可为空
     * @param userMessage   本轮用户原始请求
     * @param plan          PlanAgent 生成的计划文本
     * @param policy        本轮策略开关
     */
    public void configureForStream(ReactAgent reactAgent, List<Tool> filteredTools,
                                   String sessionId, String userMessage, String plan,
                                   TurnPolicy policy) {
        reactAgent.registerPreToolUseHook(AgentHookExamples.logHook());
        registerFileStateCheck(reactAgent);
        reactAgent.registerPostToolUseHook(AgentHookExamples.largeOutputHook());
        reactAgent.registerPostToolUseHook(viewImageHook(userMessage, plan));
        registerFinalTodoGuard(reactAgent, sessionId, policy);
        applyConcurrencyToggle(reactAgent);
        wireSubAgentParent(reactAgent, filteredTools);
    }

    private void wireSubAgentParent(ReactAgent reactAgent, List<Tool> filteredTools) {
        for (Tool tool : filteredTools) {
            if (tool instanceof RunSubagentTool runSubagentTool) {
                runSubagentTool.setParentAgent(reactAgent);
                log.debug("RunSubagentTool 父 Agent 已注入");
                return;
            }
        }
    }

    private void registerFileStateCheck(ReactAgent reactAgent) {
        if (!agentConfig.getHook().isStateCheckEnabled()) {
            return;
        }
        FileStateCheckHook hook = new FileStateCheckHook(sandboxClientFactory, fileCognitionState);
        reactAgent.registerPreToolUseHook(hook);
        reactAgent.registerPostToolUseHook(hook);
    }

    private void applyConcurrencyToggle(ReactAgent reactAgent) {
        reactAgent.setConcurrentToolExecutionEnabled(
                agentConfig.getHook().isConcurrentToolExecutionEnabled());
    }

    private void registerFinalTodoGuard(ReactAgent reactAgent, String sessionId, TurnPolicy policy) {
        if (!policy.shouldEnableStopHook()) {
            return;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        reactAgent.registerStopHook(new FinalTodoGuardHook(todoService, sessionId));
    }

    ReactAgent.PostToolUseHook viewImageHook() {
        return viewImageHook(null, null);
    }

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

    private String visionSystemPrompt() {
        return """
                你是视觉观察器。请根据图片内容输出给主 Agent 使用的客观观察结果。
                如果用户没有提出具体问题，就概括图片主要内容、可见文字、界面状态和明显细节。
                如果用户提出了具体问题，就优先提取与问题相关的信息。
                看不清或不确定的内容要明确说明，不要猜测。
                只输出观察结果，不要寒暄。
                """;
    }

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
