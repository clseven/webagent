package com.example.sandbox.web.controller;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.request.McpServerRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.mcpclient.McpConfigurationService;
import com.example.sandbox.web.service.mcpclient.McpReloadResult;
import com.example.sandbox.web.service.mcpclient.McpServerConfig;
import com.example.sandbox.web.service.mcpclient.McpServerView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前登录用户的私有 MCP Server 管理接口。
 *
 * <p>接口直接管理该用户 Sandbox 中的 {@code /home/gem/.mcp/servers.json}，
 * 不读取或修改系统管理员在 application.yml 中配置的 MCP Server。</p>
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    /** 用户 MCP 配置编排服务。 */
    private final McpConfigurationService configurationService;

    /**
     * 创建用户 MCP 管理控制器。
     *
     * @param configurationService 用户 MCP 配置编排服务
     */
    public McpController(McpConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * 列出当前用户的私有 MCP Server，凭据值不会返回给浏览器。
     *
     * @return 用户私有 Server 状态列表
     */
    @GetMapping("/servers")
    public ApiResponse<List<McpServerView>> listServers() {
        return ApiResponse.success(
                configurationService.listUserServers(UserContext.getCurrentUserId()));
    }

    /**
     * 新建一个用户私有 Streamable HTTP MCP Server，并立即尝试连接。
     *
     * @param request Server 配置请求
     * @return 配置写入与连接结果
     */
    @PostMapping("/servers")
    public ApiResponse<McpReloadResult> createServer(@RequestBody McpServerRequest request) {
        Long userId = UserContext.getCurrentUserId();
        return ApiResponse.success(configurationService.addOrReplaceUserServer(
                userId, toConfig(request.id(), request, null)));
    }

    /**
     * 更新指定用户私有 MCP Server，并立即尝试重新连接。
     *
     * <p>浏览器无法读取旧凭据值，因此同名请求头传空字符串时保留旧值；
     * 未出现在请求中的旧请求头视为用户主动删除。</p>
     *
     * @param serverId 路径中的 Server ID
     * @param request  更新后的 Server 配置
     * @return 配置写入与连接结果
     */
    @PutMapping("/servers/{serverId}")
    public ApiResponse<McpReloadResult> updateServer(
            @PathVariable String serverId, @RequestBody McpServerRequest request) {
        Long userId = UserContext.getCurrentUserId();
        McpServerConfig existing = configurationService.getUserServerConfig(userId, serverId);
        if (existing == null) {
            return ApiResponse.notFound("MCP Server 不存在: " + serverId);
        }
        return ApiResponse.success(configurationService.addOrReplaceUserServer(
                userId, toConfig(serverId, request, existing)));
    }

    /**
     * 删除指定用户私有 MCP Server，并关闭运行时连接。
     *
     * @param serverId 待删除 Server ID
     * @return 重新加载结果
     */
    @DeleteMapping("/servers/{serverId}")
    public ApiResponse<McpReloadResult> deleteServer(@PathVariable String serverId) {
        return ApiResponse.success(configurationService.removeUserServer(
                UserContext.getCurrentUserId(), serverId));
    }

    /**
     * 从用户 Sandbox 重新读取配置并重建发生变化的连接。
     *
     * @return 重新加载结果
     */
    @PostMapping("/reload")
    public ApiResponse<McpReloadResult> reloadServers() {
        return ApiResponse.success(configurationService.reloadUserServers(
                UserContext.getCurrentUserId()));
    }

    /**
     * 将页面请求转换为内部 Server 配置，并安全合并未回显的旧请求头值。
     *
     * @param serverId Server ID
     * @param request  页面请求
     * @param existing 已有配置；新建时为空
     * @return 可交给校验器的 Streamable HTTP 配置
     */
    private McpServerConfig toConfig(String serverId, McpServerRequest request,
                                     McpServerConfig existing) {
        McpServerConfig config = new McpServerConfig();
        config.setId(serverId);
        config.setType("streamable-http");
        config.setUrl(request.url());
        config.setEnabled(!Boolean.FALSE.equals(request.enabled()));
        config.setRequestTimeoutSeconds(request.requestTimeoutSeconds());
        config.setHeaders(mergeHeaders(request.headers(), existing));
        return config;
    }

    /**
     * 合并请求头；更新时空值复用同名旧凭据，新建时空值交由校验器拒绝。
     *
     * @param requested 浏览器提交的请求头
     * @param existing  已有配置；新建时为空
     * @return 合并后的请求头
     */
    private Map<String, String> mergeHeaders(Map<String, String> requested,
                                              McpServerConfig existing) {
        Map<String, String> source = requested != null ? requested : Map.of();
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String value = entry.getValue();
            if ((value == null || value.isBlank()) && existing != null) {
                value = existing.getHeaders().get(entry.getKey());
            }
            merged.put(entry.getKey(), value);
        }
        return merged;
    }
}
