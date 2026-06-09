package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.LlmResponse;
import com.example.sandbox.web.model.llm.LlmStreamChunk;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 服务接口 — 抽象不同 LLM 厂商的调用差异
 *
 * <p>项目中使用两套 LLM 实例：</p>
 * <ul>
 *   <li>plannerLlm（智谱 GLM）— 负责规划，不需要工具调用能力</li>
 *   <li>executorLlm（DeepSeek）— 负责执行，需要工具调用能力</li>
 * </ul>
 *
 * @author example
 * @date 2026/05/14
 */
public interface LlmService {

    /**
     * 简单聊天补全（无系统提示，无工具）
     *
     * @param messages 消息列表
     * @return 助手回复内容
     */
    String chat(List<ChatMessage> messages);

    /**
     * 带系统提示的聊天补全
     *
     * @param systemPrompt 系统提示词
     * @param messages     消息列表
     * @return 助手回复内容
     */
    String chatWithSystem(String systemPrompt, List<ChatMessage> messages);

    /**
     * 带系统提示的聊天补全（返回完整响应，包含 token 用量）
     *
     * <p>与 chatWithSystem 的区别是返回 LlmResponse，可以获取：
     * <ul>
     *   <li>思考链（reasoning_content）— 部分模型支持</li>
     *   <li>Token 用量 — 用于计费和成本分析</li>
     * </ul>
     *
     * @param systemPrompt 系统提示词
     * @param messages     消息列表
     * @return LLM 响应对象
     */
    LlmResponse chatWithSystemResponse(String systemPrompt, List<ChatMessage> messages);

    /**
     * 带工具的聊天补全（ReAct 模式核心调用）
     *
     * <p>LLM 收到请求后可以：</p>
     * <ul>
     *   <li>直接返回答案（finished=true）</li>
     *   <li>请求调用工具（hasToolCall=true）— 返回工具名称和参数</li>
     * </ul>
     *
     * <p>工具调用结果（Observation）由 Agent 层执行后追加到消息历史，
     * 然后再次调用本方法让 LLM 继续推理。</p>
     *
     * @param systemPrompt 系统提示词
     * @param messages     消息列表（可包含历史 Observation）
     * @param tools        可用工具定义列表
     * @return LLM 响应（可能包含工具调用）
     */
    LlmResponse chatWithTools(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools);

    // ==================== 流式调用方法 ====================

    /**
     * 流式聊天（不带工具）
     *
     * <p>实时返回 LLM 生成的 token，前端可以实现打字效果。</p>
     *
     * @param systemPrompt 系统提示词
     * @param messages     消息列表
     * @return Token 流（每个元素是一个 token）
     */
    Flux<String> chatStream(String systemPrompt, List<ChatMessage> messages);

    /**
     * 流式聊天（带工具）— ReAct 流式模式核心调用
     *
     * <p>返回多种类型的块：</p>
     * <ul>
     *   <li>token — LLM 输出的 token</li>
     *   <li>reasoning — 思考链 token（推理模型）</li>
     *   <li>tool_call — 工具调用（流式累积完成后发出）</li>
     *   <li>finish — 流结束</li>
     * </ul>
     *
     * @param systemPrompt 系统提示词
     * @param messages     消息列表
     * @param tools        可用工具定义列表
     * @return 流式响应块
     */
    Flux<LlmStreamChunk> chatWithToolsStream(String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools);
}
