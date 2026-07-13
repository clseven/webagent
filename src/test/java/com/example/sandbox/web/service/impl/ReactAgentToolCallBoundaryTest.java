package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.model.llm.LlmToolCall;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.tool.RunSubagentTool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证 ReactAgent 在压缩和裁剪上下文时不会拆开 tool calling 协议消息组。
 */
class ReactAgentToolCallBoundaryTest {

    /**
     * 验证候选切分点落在 assistant 与 tool 之间时会回退到 assistant。
     */
    @Test
    void movesSplitBeforeAssistantWhenCandidateStartsAtToolResult() {
        List<ChatMessage> messages = toolCallConversation();

        int splitAt = ReactAgent.alignSplitAtToToolCallBoundary(messages, 4);

        assertThat(splitAt).isEqualTo(3);
        assertThat(messages.get(splitAt).getRole()).isEqualTo("assistant");
        assertThat(messages.get(splitAt).getToolCalls()).isNotEmpty();
    }

    /**
     * 验证工具结果已经完整保留在左侧时，合法切分点不会被改变。
     */
    @Test
    void keepsSplitAfterCompletedToolCallGroup() {
        List<ChatMessage> messages = toolCallConversation();

        int splitAt = ReactAgent.alignSplitAtToToolCallBoundary(messages, 5);

        assertThat(splitAt).isEqualTo(5);
        assertThat(messages.get(splitAt).getRole()).isEqualTo("user");
    }

    /**
     * 验证实际压缩会保留完整的 assistant-tool 消息组，而不是留下孤立 tool 消息。
     *
     * @throws Exception 反射调用私有压缩方法失败时抛出
     */
    @Test
    void compressionKeepsToolCallGroupTogether() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyList())).thenReturn("早期消息摘要");
        ReactAgent agent = new ReactAgent(llmService, List.of());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.userMessage("甲".repeat(150_000)));
        messages.add(ChatMessage.assistantMessage("乙".repeat(150_000)));
        messages.add(ChatMessage.userMessage("丙".repeat(150_000)));
        messages.add(ChatMessage.assistantToolCallMessage(
                new LlmToolCall("call_1", "read_file", Map.of("path", "/tmp/a"))));
        messages.add(ChatMessage.toolMessage("call_1", "丁".repeat(300_000)));
        messages.add(ChatMessage.userMessage("戊".repeat(150_000)));

        Method compressIfNeeded = ReactAgent.class.getDeclaredMethod("compressIfNeeded", List.class);
        compressIfNeeded.setAccessible(true);
        compressIfNeeded.invoke(agent, messages);

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(0).getContent())
                .startsWith("[Compacted conversation summary]\n\n")
                .contains("早期消息摘要");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getToolCalls()).extracting(LlmToolCall::id).containsExactly("call_1");
        assertThat(messages.get(2).getRole()).isEqualTo("tool");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_1");
    }

    /**
     * 估算消息不足二十万 token 时不应调用摘要模型或改变原消息。
     *
     * @throws Exception 反射调用私有压缩方法失败时抛出
     */
    @Test
    void doesNotCompressBeforeTwoHundredThousandTokens() throws Exception {
        LlmService llmService = mock(LlmService.class);
        ReactAgent agent = new ReactAgent(llmService, List.of());
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.userMessage("甲".repeat(300_000)),
                ChatMessage.assistantMessage("乙".repeat(290_000))));

        Method compressIfNeeded = ReactAgent.class.getDeclaredMethod("compressIfNeeded", List.class);
        compressIfNeeded.setAccessible(true);
        compressIfNeeded.invoke(agent, messages);

        assertThat(messages).hasSize(2);
        verifyNoInteractions(llmService);
    }

    /**
     * 验证 browser 子代理继承父 Agent 的当前工具集，只排除 run_subagent 自身。
     *
     * @throws Exception 反射调用私有过滤方法失败时抛出
     */
    @Test
    void browserSubagentKeepsAllParentToolsExceptRunSubagent() throws Exception {
        RunSubagentTool runSubagentTool = new RunSubagentTool();
        List<Tool> parentTools = List.of(
                namedTool("browser_screenshot"),
                namedTool("view_image"),
                namedTool("read_file"),
                namedTool("dynamic_mcp_tool"),
                namedTool("run_subagent")
        );
        ReactAgent parentAgent = new ReactAgent(mock(LlmService.class), parentTools);
        runSubagentTool.setParentAgent(parentAgent);

        Method method = RunSubagentTool.class.getDeclaredMethod("getRestrictedTools", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Tool> restrictedTools = (List<Tool>) method.invoke(runSubagentTool, "browser");

        assertThat(restrictedTools)
                .extracting(tool -> tool.getDefinition().getName())
                .containsExactlyInAnyOrder("browser_screenshot", "view_image", "read_file", "dynamic_mcp_tool")
                .doesNotContain("run_subagent");
    }

    /**
     * 构造包含一组完整工具调用的消息序列。
     *
     * @return assistant 与 tool 相邻且 ID 匹配的消息列表
     */
    private List<ChatMessage> toolCallConversation() {
        return List.of(
                ChatMessage.userMessage("第一条"),
                ChatMessage.assistantMessage("第二条"),
                ChatMessage.userMessage("第三条"),
                ChatMessage.assistantToolCallMessage(
                        new LlmToolCall("call_1", "read_file", Map.of("path", "/tmp/a"))),
                ChatMessage.toolMessage("call_1", "文件内容"),
                ChatMessage.userMessage("继续")
        );
    }

    /**
     * 创建只用于测试过滤逻辑的轻量工具。
     *
     * @param name 工具名称
     * @return 固定返回空结果的工具实例
     */
    private Tool namedTool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return new ToolDefinition(name, "测试工具", Map.of("type", "object"), "AIO");
            }

            @Override
            public String execute(String sessionId, Map<String, Object> arguments) {
                return "";
            }
        };
    }
}
