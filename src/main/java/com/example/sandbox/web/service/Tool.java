package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ToolDefinition;

import java.util.Map;

/**
 * 工具接口 — Agent 可调用的能力单元
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>每个工具实现一个具体的原子能力（读文件、执行命令等）</li>
 *   <li>工具定义遵循 OpenAI function calling 规范（JSON Schema）</li>
 *   <li>执行结果统一返回字符串，方便 LLM 理解</li>
 *   <li>异常时返回错误消息而非抛异常，确保 Agent 流程不中断</li>
 * </ul>
 *
 * <h3>沙箱类型</h3>
 * <p>工具通过 getDefinition() 的 sandboxType 字段声明支持的沙箱：</p>
 * <ul>
 *   <li>"ALL" — 所有沙箱都可用</li>
 *   <li>"AIO" — 仅 AIO 沙箱可用</li>
 *   <li>"COMMON" — 仅普通沙箱可用</li>
 * </ul>
 *
 * @author example
 * @date 2026/05/15
 */
public interface Tool {

    /**
     * 获取工具定义（名称、描述、参数 schema）
     *
     * <p>这个定义会传给 LLM，让 LLM 知道有哪些工具可用以及如何调用。</p>
     *
     * @return 工具定义
     */
    ToolDefinition getDefinition();

    /**
     * 执行工具
     *
     * <p>实现要求：</p>
     * <ul>
     *   <li>参数校验失败时返回错误消息（如 "路径不能为空"）</li>
     *   <li>执行异常时捕获并返回错误消息，不抛异常</li>
     *   <li>成功时返回结果字符串，LLM 会基于这个结果继续推理</li>
     * </ul>
     *
     * @param sessionId 会话 ID（用于获取会话相关的沙箱客户端）
     * @param arguments 工具参数（从 LLM 的 tool_calls 中解析）
     * @return 执行结果（成功返回结果，失败返回错误消息）
     */
    String execute(String sessionId, Map<String, Object> arguments);
}
