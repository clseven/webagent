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
 *   <li>CDP URL — Chrome DevTools Protocol 地址（用于调试）</li>
 *   <li>VNC URL — 远程桌面地址（可视化观察浏览器）</li>
 *   <li>窗口大小、当前 URL 等</li>
 * </ul>
 *
 * <h3>何时使用</h3>
 * <p>在执行浏览器操作前，先调用此工具确认浏览器已就绪。
 * 如果返回错误，说明浏览器未启动，需要先初始化。</p>
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
                "required", List.of()
        );

        return new ToolDefinition(
                NAME,
                "查询浏览器当前状态信息，包括 CDP URL、VNC URL、窗口大小等。不需要截图即可了解浏览器状态。",
                parameters,
                "AIO"
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            var client = factory.getAioClient(sessionId);
            Map<String, Object> info = client.browserInfo();

            if (info == null) {
                return "错误：无法获取浏览器信息，浏览器可能未启动";
            }

            log.info("浏览器信息查询成功: {}", info);
            return "浏览器信息: " + info;
        } catch (Exception e) {
            log.error("浏览器信息查询失败", e);
            return "错误：浏览器信息查询失败 - " + e.getMessage();
        }
    }
}
