package com.example.sandbox.web.model.converter;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.llm.AgentRunCheckpoint;
import com.example.sandbox.web.model.llm.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Agent 运行检查点能无损保存模型工具调用协议消息。
 */
class AgentRunCheckpointCodecTest {

    /**
     * 并发工具调用必须在序列化后保留同组调用及各自的结果关联 ID。
     */
    @Test
    void 检查点应无损恢复并发工具调用和对应结果() {
        List<ChatMessage> messages = List.of(
                ChatMessage.userMessage("继续任务"),
                ChatMessage.assistantToolCallsMessage("准备并行检查文件和资料", "需要同时读取本地文件并搜索外部资料", List.of(
                        new LlmToolCall("call_1", "read_file", Map.of("path", "/a")),
                        new LlmToolCall("call_2", "web_search", Map.of("query", "x")))),
                ChatMessage.toolMessage("call_1", "A"),
                ChatMessage.toolMessage("call_2", "B"));

        String json = EntityConverter.serializeCheckpoint(AgentRunCheckpoint.fromMessages(messages));
        List<ChatMessage> restored = EntityConverter.parseCheckpoint(json).toMessages();

        assertThat(restored).hasSize(4);
        assertThat(restored.get(1).getContent()).isEqualTo("准备并行检查文件和资料");
        assertThat(restored.get(1).getReasoning()).isEqualTo("需要同时读取本地文件并搜索外部资料");
        assertThat(restored.get(1).getToolCalls())
                .extracting(LlmToolCall::id)
                .containsExactly("call_1", "call_2");
        assertThat(restored.get(2).getToolCallId()).isEqualTo("call_1");
        assertThat(restored.get(3).getToolCallId()).isEqualTo("call_2");
    }

    /**
     * 损坏检查点不能阻断会话加载，应降级为空检查点。
     */
    @Test
    void 损坏检查点应降级为空消息列表() {
        AgentRunCheckpoint checkpoint = EntityConverter.parseCheckpoint("{broken");

        assertThat(checkpoint.toMessages()).isEmpty();
    }
}
