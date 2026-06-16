package com.example.sandbox.aio.browser;

import com.example.sandbox.aio.browser.model.BrowserInfo;
import com.example.sandbox.aio.core.AioHttpClient;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 封装 AIO Browser REST API。
 */
public class AioBrowserApi {

    /** 共享 AIO HTTP 传输客户端。 */
    private final AioHttpClient http;

    /**
     * 创建 Browser API。
     *
     * @param http 共享 AIO HTTP 传输客户端
     */
    public AioBrowserApi(AioHttpClient http) {
        this.http = http;
    }

    /**
     * 获取当前浏览器连接信息。
     *
     * @return 浏览器信息
     */
    public BrowserInfo getInfo() {
        return http.getData("/v1/browser/info", BrowserInfo.class);
    }

    /**
     * 获取浏览器原始信息响应，供诊断工具展示。
     *
     * @return AIO 完整响应
     */
    public Map<String, Object> getInfoResponse() {
        return http.getMap("/v1/browser/info");
    }

    /**
     * 截取当前浏览器画面。
     *
     * @return PNG 字节
     */
    public byte[] screenshot() {
        return http.getBytes("/v1/browser/screenshot", MediaType.IMAGE_PNG);
    }

    /**
     * 设置浏览器分辨率。
     *
     * @param resolution OpenAPI 允许的分辨率字符串
     * @return 设置是否成功
     */
    public boolean setResolution(String resolution) {
        Map<String, Object> response = http.postMap(
                "/v1/browser/config", Map.of("resolution", resolution));
        return response != null && Boolean.TRUE.equals(response.get("success"));
    }

    /**
     * 执行一个经过字段规范化的浏览器动作。
     *
     * @param action 模型工具传入的动作参数
     * @return AIO 是否成功处理动作
     */
    @SuppressWarnings("unchecked")
    public boolean action(Map<String, Object> action) {
        String actionType = (String) action.get("action_type");
        if (actionType == null || actionType.isBlank()) {
            return false;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("action_type", actionType);
        switch (actionType) {
            case "HOTKEY" -> body.put("keys", (List<String>) action.get("keys"));
            case "PRESS" -> body.put("key", action.get("key"));
            case "TYPING" -> {
                body.put("text", action.get("text"));
                body.put("use_clipboard", action.getOrDefault("use_clipboard", false));
            }
            case "CLICK" -> {
                copyIfPresent(action, body, "x");
                copyIfPresent(action, body, "y");
                copyIfPresent(action, body, "button");
                copyIfPresent(action, body, "num_clicks");
            }
            case "DOUBLE_CLICK", "RIGHT_CLICK", "MOVE_TO" -> {
                copyIfPresent(action, body, "x");
                copyIfPresent(action, body, "y");
            }
            case "SCROLL" -> {
                body.put("dx", action.getOrDefault("dx", 0));
                body.put("dy", action.getOrDefault("dy", 0));
            }
            case "WAIT" -> body.put("duration", action.get("duration"));
            default -> {
                return false;
            }
        }
        Map<String, Object> response = http.postMap("/v1/browser/actions", body);
        return response != null && Boolean.TRUE.equals(response.get("success"));
    }

    /**
     * 使用地址栏导航到目标 URL。
     *
     * @param url 目标 URL
     * @return 三个浏览器动作均成功时返回 true
     */
    public boolean navigate(String url) {
        String normalized = url.startsWith("http://") || url.startsWith("https://")
                ? url : "https://" + url;
        return action(Map.of("action_type", "HOTKEY", "keys", List.of("ctrl", "l")))
                && action(Map.of("action_type", "TYPING", "text", normalized, "use_clipboard", false))
                && action(Map.of("action_type", "PRESS", "key", "enter"));
    }

    /**
     * 将存在的可选字段复制到请求体。
     *
     * @param source 源参数
     * @param target 目标请求体
     * @param key    字段名
     */
    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }
}
