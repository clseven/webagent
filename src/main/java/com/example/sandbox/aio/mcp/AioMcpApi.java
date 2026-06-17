package com.example.sandbox.aio.mcp;

import com.example.sandbox.aio.core.AioApiException;
import com.example.sandbox.aio.core.AioHttpClient;
import com.example.sandbox.aio.mcp.model.McpToolDescriptor;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 封装 AIO MCP REST API。
 *
 * <p>AIO Sandbox 负责连接第三方 MCP Server，本类只调用 AIO 暴露的
 * REST 代理接口，不直接实现 stdio、SSE 或 JSON-RPC 协议。</p>
 */
public class AioMcpApi {

    /** MCP 发现接口的单次调用超时。 */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(10);

    /** MCP 工具执行接口的单次调用超时。 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    /** 共享 AIO HTTP 传输客户端。 */
    private final AioHttpClient http;

    /**
     * 创建 MCP API。
     *
     * @param http 共享 AIO HTTP 传输客户端
     */
    public AioMcpApi(AioHttpClient http) {
        this.http = http;
    }

    /**
     * 列出当前沙箱已配置的 MCP Server。
     *
     * @return MCP Server 名称列表
     * @throws AioApiException 当 AIO 返回失败响应或数据结构不符合契约时抛出
     */
    public List<String> listServers() {
        Map<String, Object> response = http.getMap("/v1/mcp/servers", DISCOVERY_TIMEOUT);
        Object data = requireSuccess(response, "列出 MCP Server 失败");
        if (data == null) {
            return List.of();
        }
        if (!(data instanceof List<?> values)) {
            throw new AioApiException("MCP Server 列表响应格式错误");
        }

        List<String> servers = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                servers.add(value.toString());
            }
        }
        return servers;
    }

    /**
     * 列出指定 MCP Server 暴露的工具。
     *
     * @param serverName MCP Server 名称
     * @return MCP 工具描述列表
     * @throws AioApiException 当 AIO 返回失败响应或数据结构不符合契约时抛出
     */
    public List<McpToolDescriptor> listTools(String serverName) {
        Map<String, Object> response = http.getMap(mcpPath(serverName, "tools"), DISCOVERY_TIMEOUT);
        Object data = requireSuccess(response, "列出 MCP 工具失败: " + serverName);
        if (data == null) {
            return List.of();
        }
        if (!(data instanceof Map<?, ?> dataMap)) {
            throw new AioApiException("MCP 工具列表响应格式错误: " + serverName);
        }
        Object toolsValue = dataMap.get("tools");
        if (!(toolsValue instanceof List<?> rawTools)) {
            return List.of();
        }

        List<McpToolDescriptor> tools = new ArrayList<>();
        for (Object rawTool : rawTools) {
            if (rawTool instanceof Map<?, ?> map) {
                tools.add(McpToolDescriptor.fromMap(map));
            }
        }
        return tools;
    }

    /**
     * 调用指定 MCP Server 的工具。
     *
     * <p>调用失败不会在本层重试。MCP 工具可能访问外部系统或执行带副作用操作，
     * 盲目重试可能导致重复写入、重复下单等问题，因此由上层根据业务语义决定是否重试。</p>
     *
     * @param serverName MCP Server 名称
     * @param toolName   MCP Server 内的原始工具名称
     * @param arguments  工具参数；为空时发送空对象
     * @return AIO 返回的完整响应信封
     */
    public Map<String, Object> callTool(String serverName, String toolName, Map<String, Object> arguments) {
        Map<String, Object> body = arguments != null ? arguments : Map.of();
        return http.postMap(mcpPath(serverName, "tools", toolName), body, CALL_TIMEOUT);
    }

    /**
     * 校验 AIO 通用响应信封。
     *
     * @param response 响应信封
     * @param message  失败时使用的错误前缀
     * @return 响应 data 字段
     * @throws AioApiException 当响应为空或 success=false 时抛出
     */
    private Object requireSuccess(Map<String, Object> response, String message) {
        if (response == null) {
            throw new AioApiException(message + ": AIO 返回空响应");
        }
        if (Boolean.FALSE.equals(response.get("success"))) {
            throw new AioApiException(message + ": " + response.get("message"));
        }
        return response.get("data");
    }

    /**
     * 构造 MCP REST 路径并编码路径片段。
     *
     * @param segments MCP 路径片段
     * @return 编码后的 REST 路径
     */
    private String mcpPath(String... segments) {
        StringBuilder path = new StringBuilder("/v1/mcp");
        for (String segment : segments) {
            path.append("/")
                    .append(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
        }
        return path.toString();
    }
}
