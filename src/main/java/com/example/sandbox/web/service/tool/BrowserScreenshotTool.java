package com.example.sandbox.web.service.tool;

import com.example.sandbox.aio.AioSandboxClient;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 浏览器截图工具 — 截取网页内容并返回图片链接
 *
 * <h3>用途</h3>
 * <p>让 LLM 能"看"到网页内容，可用于：</p>
 * <ul>
 *   <li>验证网页加载是否正常</li>
 *   <li>截取动态渲染的内容（JS 生成的数据）</li>
 *   <li>保存网页快照作为证据</li>
 * </ul>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>url — 可选，截图前先导航到该 URL；不传则截取当前页面</li>
 * </ul>
 *
 * <h3>返回值</h3>
 * <p>返回 Markdown 格式的图片链接，可直接在聊天窗口中显示：</p>
 * <pre>
 * ![截图](http://...)
 * </pre>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class BrowserScreenshotTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BrowserScreenshotTool.class);
    private static final String NAME = "browser_screenshot";

    @Autowired
    private SandboxClientFactory factory;

    @Value("${app.base-url:http://localhost:8081}")
    private String baseUrl;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of(
                "type", "string",
                "description", "可选。要截图的网页 URL。如果提供，工具会先导航到该 URL 再截图；如果不提供，则截取浏览器当前页面。"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", java.util.List.of()
        );

        return new ToolDefinition(
                NAME,
                "截取网页截图。可指定 URL 导航后截图，或截取浏览器当前页面。截图保存到沙箱临时目录并返回查看链接。",
                parameters,
                "AIO"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            var client = factory.getAioClient(sessionId);

            // 等待 AIO 服务就绪
            if (!client.waitForReady()) {
                return "错误：AIO 服务未就绪（等待 30 秒后仍无法连接），请稍后重试";
            }

            // 获取 URL 参数
            String url = arguments != null ? (String) arguments.get("url") : null;

            // 如果提供了 URL，先导航
            if (url != null && !url.isBlank()) {
                log.info("浏览器导航到: {}", url);

                // 使用 browserAction 执行导航
                String normalizedUrl = url;
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    normalizedUrl = "https://" + url;
                }

                boolean navSuccess = client.browserAction(Map.of("action_type", "HOTKEY", "keys", List.of("ctrl", "l")))
                        && client.browserAction(Map.of("action_type", "TYPING", "text", normalizedUrl, "use_clipboard", false))
                        && client.browserAction(Map.of("action_type", "PRESS", "key", "enter"));

                if (!navSuccess) {
                    return "错误：导航到 " + url + " 失败";
                }

                // 等待页面加载（内部轮询，无详细日志）
                client.waitForPageLoad(10);
            }

            // 截图
            byte[] screenshot = client.screenshot();
            if (screenshot == null || screenshot.length == 0) {
                return "错误：截图为空";
            }

            // 保存截图到沙箱文件
            String filePath = "/tmp/screenshot_" + Instant.now().toEpochMilli() + ".png";
            if (!client.writeFile(filePath, screenshot)) {
                return "错误：截图保存失败";
            }

            log.info("截图成功，大小: {} bytes，路径: {}", screenshot.length, filePath);
            String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
            String downloadUrl = baseUrl + "/api/sessions/" + sessionId + "/files/download?path=" + encodedPath;
            return "截图成功！文件路径: " + filePath + "，大小: " + screenshot.length + " bytes\n\n" +
                   "![截图](" + downloadUrl + ")\n\n" +
                   "下载链接: " + downloadUrl;
        } catch (Exception e) {
            log.error("截图失败", e);
            return "截图失败：" + e.getMessage();
        }
    }
}