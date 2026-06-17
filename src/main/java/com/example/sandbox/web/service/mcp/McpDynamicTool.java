package com.example.sandbox.web.service.mcp;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.aio.mcp.model.McpToolDescriptor;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;

import java.util.Map;

/**
 * MCP 动态工具适配器。
 *
 * <p>每个实例对应某个 MCP Server 的一个原始工具。模型看到的是安全编码后的
 * 项目工具名，执行时再转发到沙箱 AIO MCP API。</p>
 */
public class McpDynamicTool implements Tool {

    /** 模型可见的安全工具名。 */
    private final String exposedName;

    /** MCP 工具引用，用于执行时定位原始 server/tool。 */
    private final McpToolRef ref;

    /** MCP 原始工具描述。 */
    private final McpToolDescriptor descriptor;

    /** 沙箱客户端工厂，用于按会话获取当前用户 AIO 客户端。 */
    private final SandboxClientFactory factory;

    /**
     * 创建 MCP 动态工具。
     *
     * @param exposedName 模型可见的安全工具名
     * @param ref         MCP 工具引用
     * @param descriptor  MCP 原始工具描述
     * @param factory     沙箱客户端工厂
     */
    public McpDynamicTool(String exposedName, McpToolRef ref, McpToolDescriptor descriptor,
                          SandboxClientFactory factory) {
        this.exposedName = exposedName;
        this.ref = ref;
        this.descriptor = descriptor;
        this.factory = factory;
    }

    /**
     * 获取模型可见工具定义。
     *
     * @return 由 MCP 工具描述转换出的项目工具定义
     */
    @Override
    public ToolDefinition getDefinition() {
        String description = descriptor.description();
        if (description == null || description.isBlank()) {
            description = descriptor.title();
        }
        if (description == null || description.isBlank()) {
            description = "调用 MCP Server `" + ref.server() + "` 的 `" + ref.tool() + "` 工具。";
        } else {
            description = "来自 MCP Server `" + ref.server() + "`：" + description;
        }

        return new ToolDefinition(
                exposedName,
                description,
                descriptor.normalizedInputSchema(),
                "AIO"
        );
    }

    /**
     * 执行 MCP 工具调用。
     *
     * <p>本方法不做自动重试。MCP 工具可能访问外部系统或执行带副作用操作，重复调用可能造成
     * 重复写入或状态污染，因此失败时把错误返回给模型，由模型或用户决定后续动作。</p>
     *
     * @param sessionId 会话 ID
     * @param arguments LLM 生成的工具参数
     * @return MCP 调用结果 observation 文本
     */
    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            AioClient client = factory.getAioClient(sessionId);
            Map<String, Object> response = client.mcp().callTool(ref.server(), ref.tool(), arguments);
            return McpCallResultFormatter.format(response);
        } catch (Exception e) {
            return "ERROR: MCP 工具执行出错：" + e.getMessage();
        }
    }
}
