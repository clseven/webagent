package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.impl.SandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 *   <tr><td>CLICK</td><td>点击坐标或当前鼠标位置</td><td>x/y 必须同时提供或同时省略</td></tr>
 *   <tr><td>DOUBLE_CLICK</td><td>双击坐标</td><td>x, y</td></tr>
 *   <tr><td>RIGHT_CLICK</td><td>右击坐标</td><td>x, y</td></tr>
 *   <tr><td>MOVE_TO</td><td>移动鼠标</td><td>x, y</td></tr>
 *   <tr><td>SCROLL</td><td>滚动页面</td><td>dx, dy</td></tr>
 *   <tr><td>WAIT</td><td>等待</td><td>duration (秒)</td></tr>
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
                执行一个原子的键盘或鼠标动作。适合坐标点击、滚动、快捷键等简单操作，
                也是 browser_execute 无法使用 DOM 定位时的视觉兜底。

                使用规则：
                - 每次调用只执行一个动作；
                - 坐标以当前浏览器视口左上角为 (0,0)，单位为 CSS 像素；
                - 坐标操作前先调用 browser_screenshot，页面变化后重新截图；
                - SCROLL 中 dy > 0 向下滚动，dy < 0 向上滚动；
                - WAIT 的 duration 单位是秒且必须大于 0；
                - 需要理解页面结构、按文本定位或连续完成多步操作时，优先使用
                  browser_inspect + browser_execute。

                导航可使用 HOTKEY(ctrl+l) → TYPING(url) → PRESS(enter)，
                但更推荐 browser_execute 中的 page.goto(url)。
                """;

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action_type", Map.of(
                "type", "string",
                "enum", List.of("HOTKEY", "TYPING", "PRESS", "CLICK", "DOUBLE_CLICK",
                        "RIGHT_CLICK", "MOVE_TO", "SCROLL", "WAIT"),
                "description", "要执行的唯一动作类型。"
        ));
        properties.put("keys", Map.of(
                "type", "array",
                "minItems", 1,
                "items", Map.of("type", "string"),
                "description", "仅 HOTKEY 使用。按键列表，例如 [\"ctrl\", \"l\"]。"
        ));
        properties.put("key", Map.of(
                "type", "string",
                "minLength", 1,
                "description", "仅 PRESS 使用。单个按键，例如 \"enter\"、\"escape\"、\"tab\"。"
        ));
        properties.put("text", Map.of(
                "type", "string",
                "minLength", 1,
                "description", "仅 TYPING 使用。输入到当前焦点元素的文本。"
        ));
        properties.put("x", Map.of(
                "type", "number",
                "description", "CLICK、DOUBLE_CLICK、RIGHT_CLICK、MOVE_TO 的视口 X 坐标。"
        ));
        properties.put("y", Map.of(
                "type", "number",
                "description", "CLICK、DOUBLE_CLICK、RIGHT_CLICK、MOVE_TO 的视口 Y 坐标。"
        ));
        properties.put("button", Map.of(
                "type", "string",
                "enum", List.of("left", "right", "middle"),
                "default", "left",
                "description", "仅 CLICK 使用。鼠标按钮，默认 left。"
        ));
        properties.put("num_clicks", Map.of(
                "type", "integer",
                "enum", List.of(1, 2, 3),
                "default", 1,
                "description", "仅 CLICK 使用。点击次数，默认 1。"
        ));
        properties.put("dx", Map.of(
                "type", "integer",
                "default", 0,
                "description", "仅 SCROLL 使用。水平滚动量；正数向右，负数向左。"
        ));
        properties.put("dy", Map.of(
                "type", "integer",
                "default", 0,
                "description", "仅 SCROLL 使用。垂直滚动量；正数向下，负数向上。"
        ));
        properties.put("duration", Map.of(
                "type", "number",
                "exclusiveMinimum", 0,
                "description", "仅 WAIT 使用。等待秒数，例如 1.5。"
        ));
        properties.put("use_clipboard", Map.of(
                "type", "boolean",
                "default", false,
                "description", "仅 TYPING 使用。AIO Linux 环境默认 false。"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("action_type"),
                "additionalProperties", false
        );

        return new ToolDefinition(NAME, description, parameters, "AIO");
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        Object actionTypeValue = arguments != null ? arguments.get("action_type") : null;
        if (!(actionTypeValue instanceof String actionType) || actionType.isBlank()) {
            return "错误：action_type 不能为空";
        }
        actionType = actionType.toUpperCase(Locale.ROOT);
        String validationError = validateArguments(actionType, arguments);
        if (validationError != null) {
            return "错误：" + validationError;
        }

        Map<String, Object> normalizedArguments = new LinkedHashMap<>(arguments);
        normalizedArguments.put("action_type", actionType);

        try {
            var client = factory.getAioClient(sessionId);
            boolean success = client.browser().action(normalizedArguments);

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

    /**
     * 在调用 AIO 浏览器 API 前校验各动作专用参数。
     *
     * @param actionType 已规范为大写的动作类型
     * @param arguments 模型提供的工具参数
     * @return 参数有效时返回 null，否则返回简洁的校验错误信息
     */
    private String validateArguments(String actionType, Map<String, Object> arguments) {
        return switch (actionType) {
            case "HOTKEY" -> arguments.get("keys") instanceof List<?> keys && !keys.isEmpty()
                    ? null : "HOTKEY 必须提供非空 keys";
            case "TYPING" -> validateTyping(arguments);
            case "PRESS" -> hasText(arguments, "key")
                    ? null : "PRESS 必须提供非空 key";
            case "CLICK" -> validateClick(arguments);
            case "DOUBLE_CLICK", "RIGHT_CLICK", "MOVE_TO" ->
                    hasCoordinates(arguments) ? null : actionType + " 必须同时提供数字 x 和 y";
            case "SCROLL" -> validateScroll(arguments);
            case "WAIT" -> arguments.get("duration") instanceof Number duration
                    && duration.doubleValue() > 0 ? null : "WAIT 必须提供大于 0 的 duration（秒）";
            default -> "不支持的 action_type: " + actionType;
        };
    }

    /**
     * 校验 CLICK 的坐标以及可选的鼠标按钮和点击次数。
     *
     * @param arguments 模型提供的工具参数
     * @return 参数有效时返回 null，否则返回校验错误信息
     */
    private String validateClick(Map<String, Object> arguments) {
        boolean hasX = arguments.get("x") instanceof Number;
        boolean hasY = arguments.get("y") instanceof Number;
        if (hasX != hasY) {
            return "CLICK 的 x 和 y 必须同时提供或同时省略";
        }
        Object button = arguments.get("button");
        if (button != null && !Set.of("left", "right", "middle").contains(button.toString())) {
            return "CLICK 的 button 只能是 left、right 或 middle";
        }
        Object count = arguments.get("num_clicks");
        if (count != null && !(count instanceof Number)) {
            return "CLICK 的 num_clicks 必须是整数";
        }
        if (count instanceof Number number) {
            if (number.doubleValue() != number.intValue()
                    || !Set.of(1, 2, 3).contains(number.intValue())) {
                return "CLICK 的 num_clicks 只能是整数 1、2 或 3";
            }
        }
        return null;
    }

    /**
     * 校验 TYPING 的输入文本和可选剪贴板行为。
     *
     * @param arguments 模型提供的工具参数
     * @return 参数有效时返回 null，否则返回校验错误信息
     */
    private String validateTyping(Map<String, Object> arguments) {
        if (!hasText(arguments, "text")) {
            return "TYPING 必须提供非空 text";
        }
        Object clipboard = arguments.get("use_clipboard");
        return clipboard == null || clipboard instanceof Boolean
                ? null : "TYPING 的 use_clipboard 必须是布尔值";
    }

    /**
     * 校验 SCROLL 使用整数滚动量，并确保该动作不会无效执行。
     *
     * @param arguments 模型提供的工具参数
     * @return 参数有效时返回 null，否则返回校验错误信息
     */
    private String validateScroll(Map<String, Object> arguments) {
        Object dxValue = arguments.getOrDefault("dx", 0);
        Object dyValue = arguments.getOrDefault("dy", 0);
        if (!(dxValue instanceof Number dx) || !(dyValue instanceof Number dy)) {
            return "SCROLL 的 dx 和 dy 必须是整数";
        }
        if (dx.doubleValue() != dx.intValue() || dy.doubleValue() != dy.intValue()) {
            return "SCROLL 的 dx 和 dy 必须是整数";
        }
        return dx.doubleValue() != 0 || dy.doubleValue() != 0
                ? null : "SCROLL 的 dx 和 dy 不能同时为 0";
    }

    /**
     * 检查指定参数是否包含非空白文本。
     *
     * @param arguments 模型提供的工具参数
     * @param key 参数名称
     * @return 参数值是否为非空白文本
     */
    private boolean hasText(Map<String, Object> arguments, String key) {
        return arguments.get(key) instanceof String value && !value.isBlank();
    }

    /**
     * 检查两个视口坐标是否都存在且为数值。
     *
     * @param arguments 模型提供的工具参数
     * @return x 和 y 是否都存在且为数值
     */
    private boolean hasCoordinates(Map<String, Object> arguments) {
        return arguments.get("x") instanceof Number && arguments.get("y") instanceof Number;
    }
}
