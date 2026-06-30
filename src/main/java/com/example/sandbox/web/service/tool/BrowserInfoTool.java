package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 浏览器状态查询工具 — 查看浏览器当前状态
 *
 * <h3>用途</h3>
 * <p>查询浏览器实例的状态信息，包括：</p>
 * <ul>
 *   <li>浏览器是否提供可用的 CDP 连接</li>
 *   <li>User-Agent</li>
 *   <li>窗口大小</li>
 * </ul>
 *
 * <h3>何时使用</h3>
 * <p>在浏览器工具持续返回连接错误时调用，用于判断浏览器运行时是否就绪。
 * 普通页面操作直接从 browser_inspect 开始即可。</p>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class BrowserInfoTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BrowserInfoTool.class);
    private static final String NAME = "browser_info";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false
        );

        return new ToolDefinition(
                NAME,
                """
                        查询浏览器连接状态、User-Agent 和视口大小。
                        """,
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            var client = factory.getAioClient(sessionId);
            var info = client.browser().getInfo();

            if (info == null) {
                return "错误：无法获取浏览器信息，浏览器可能未启动";
            }

            var viewport = info.getViewport();
            String viewportText = viewport != null
                    ? viewport.getWidth() + "x" + viewport.getHeight()
                    : "未知";
            boolean cdpReady = info.getCdpUrl() != null && !info.getCdpUrl().isBlank();
            log.info("浏览器信息查询成功: cdpReady={}, viewport={}", cdpReady, viewportText);
            return "浏览器状态: {"
                    + "\"ready\":" + cdpReady
                    + ",\"userAgent\":\"" + escape(info.getUserAgent())
                    + "\",\"viewport\":\"" + viewportText + "\"}";
        } catch (Exception e) {
            log.error("浏览器信息查询失败", e);
            return "错误：浏览器信息查询失败 - " + e.getMessage();
        }
    }

    /**
     * 转义用于紧凑类 JSON 诊断响应的文本。
     *
     * @param value 原始值，允许为 null
     * @return 不包含外围引号的转义后文本
     */
    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
