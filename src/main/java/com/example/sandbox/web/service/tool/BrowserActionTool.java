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
 * 浏览器操作工具 — 通过键鼠模拟操作浏览器
 *
 * <h3>用途</h3>
 * <p>让 LLM 能在 AIO 沙箱中控制真实浏览器，可用于：</p>
 * <ul>
 *   <li>网页数据抓取（导航到目标网站 + 提取内容）</li>
 *   <li>自动化测试</li>
 *   <li>填写表单、点击按钮</li>
 * </ul>
 *
 * <h3>支持的操作类型</h3>
 * <table>
 *   <tr><th>类型</th><th>说明</th><th>必填参数</th></tr>
 *   <tr><td>HOTKEY</td><td>组合键</td><td>keys (如 ["ctrl", "l"])</td></tr>
 *   <tr><td>TYPING</td><td>输入文本</td><td>text</td></tr>
 *   <tr><td>PRESS</td><td>按单个键</td><td>key (如 "enter")</td></tr>
 *   <tr><td>CLICK</td><td>点击坐标</td><td>x, y</td></tr>
 *   <tr><td>MOVE_TO</td><td>移动鼠标</td><td>x, y</td></tr>
 *   <tr><td>SCROLL</td><td>滚动页面</td><td>scroll_x, scroll_y</td></tr>
 *   <tr><td>WAIT</td><td>等待</td><td>wait (毫秒)</td></tr>
 * </table>
 *
 * <h3>导航示例</h3>
 * <pre>
 * 1. HOTKEY ["ctrl", "l"]  → 选中地址栏
 * 2. TYPING "https://..."   → 输入 URL
 * 3. PRESS "enter"         → 回车访问
 * </pre>
 *
 * <h3>沙箱限制</h3>
 * <p>此工具仅 AIO 沙箱可用（sandboxType=AIO）。</p>
 */
@Component
public class BrowserActionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BrowserActionTool.class);
    private static final String NAME = "browser_action";

    @Autowired
    private SandboxClientFactory factory;

    @Override
    public ToolDefinition getDefinition() {
        String description = """
                执行浏览器操作（键鼠模拟）。支持的操作类型：
                - HOTKEY: 组合键，如 ["ctrl", "l"] 选中地址栏
                - TYPING: 输入文本
                - PRESS: 按单个键，如 "enter"
                - CLICK: 点击坐标 (x, y)
                - MOVE_TO: 移动鼠标到坐标
                - SCROLL: 滚动页面
                - WAIT: 等待毫秒数

                导航示例：HOTKEY(ctrl+l) → TYPING(url) → PRESS(enter)
                """;

        Map<String, Object> properties = Map.of(
                "action_type", Map.of(
                        "type", "string",
                        "description", "操作类型：HOTKEY, TYPING, PRESS, CLICK, MOVE_TO, SCROLL, WAIT"
                ),
                "keys", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "HOTKEY 的按键列表，如 [\"ctrl\", \"l\"]"
                ),
                "key", Map.of(
                        "type", "string",
                        "description", "PRESS 的单个按键，如 \"enter\""
                ),
                "text", Map.of(
                        "type", "string",
                        "description", "TYPING 的文本内容"
                ),
                "x", Map.of(
                        "type", "integer",
                        "description", "X 坐标（CLICK, MOVE_TO, SCROLL）"
                ),
                "y", Map.of(
                        "type", "integer",
                        "description", "Y 坐标（CLICK, MOVE_TO, SCROLL）"
                ),
                "scroll_x", Map.of(
                        "type", "integer",
                        "description", "X 方向滚动量（SCROLL）"
                ),
                "scroll_y", Map.of(
                        "type", "integer",
                        "description", "Y 方向滚动量（SCROLL）"
                ),
                "wait", Map.of(
                        "type", "integer",
                        "description", "等待毫秒数（WAIT）"
                ),
                "use_clipboard", Map.of(
                        "type", "boolean",
                        "description", "TYPING 是否使用剪贴板（Linux 必须为 false）",
                        "default", false
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("action_type")
        );

        return new ToolDefinition(NAME, description, parameters, "AIO");
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String actionType = (String) arguments.get("action_type");
        if (actionType == null || actionType.isBlank()) {
            return "错误：action_type 不能为空";
        }

        try {
            var client = factory.getAioClient(sessionId);
            boolean success = client.browserAction(arguments);

            if (success) {
                log.info("浏览器操作成功: {}", actionType);
                return "操作成功: " + actionType;
            } else {
                return "操作失败: " + actionType;
            }
        } catch (Exception e) {
            log.error("浏览器操作失败: {}", actionType, e);
            return "操作失败: " + e.getMessage();
        }
    }
}
