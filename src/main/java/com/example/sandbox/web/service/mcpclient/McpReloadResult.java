package com.example.sandbox.web.service.mcpclient;

import java.util.List;
import java.util.Map;

/**
 * 用户 MCP 配置重新加载结果。
 *
 * @param added     新增并连接成功的 Server ID
 * @param updated   更新并替换成功的 Server ID
 * @param removed   已从运行时移除的 Server ID
 * @param unchanged 配置未变化的 Server ID
 * @param reused    因 endpoint 已存在而复用的“请求 ID → 实际 ID”映射
 * @param failed    加载失败的 Server ID 与结构化错误；特殊键 {@code _config} 表示配置文件级错误
 */
public record McpReloadResult(
        List<String> added,
        List<String> updated,
        List<String> removed,
        List<String> unchanged,
        Map<String, String> reused,
        Map<String, McpOperationError> failed
) {

    /**
     * 判断本次加载是否完全成功。
     *
     * @return 没有任何失败项时返回 true
     */
    public boolean successful() {
        return failed == null || failed.isEmpty();
    }
}
