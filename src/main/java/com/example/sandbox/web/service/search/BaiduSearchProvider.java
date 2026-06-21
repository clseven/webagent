package com.example.sandbox.web.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 百度搜索提供者 — 通过百度千帆 AI 搜索 API 获取搜索结果。
 *
 * <p>作为 DuckDuckGo 的备用搜索源，当主搜索源被反爬拦截时自动降级。</p>
 */
@Component
public class BaiduSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BaiduSearchProvider.class);
    private static final String API_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agent.search.baidu.api-key:}")
    private String apiKey;

    @Value("${agent.search.baidu.timeout:15s}")
    private Duration timeout;

    @Value("${agent.search.baidu.max-results:5}")
    private int maxResults;

    private volatile WebClient webClient;

    private WebClient getWebClient() {
        if (webClient == null) {
            synchronized (this) {
                if (webClient == null) {
                    webClient = WebClient.builder()
                            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(512 * 1024))
                            .build();
                }
            }
        }
        return webClient;
    }

    @Override
    public String getName() {
        return "BaiduSearch";
    }

    @Override
    public String search(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            return "搜索失败：百度搜索 API Key 未配置 (agent.search.baidu.api-key)";
        }

        log.info("BaiduSearch 开始: \"{}\"", query);

        try {
            // 构建请求体
            Map<String, Object> body = Map.of(
                    "messages", List.of(Map.of("content", query, "role", "user")),
                    "search_source", "baidu_search_v2",
                    "resource_type_filter", List.of(Map.of("type", "web", "top_k", maxResults))
            );

            String requestBody = objectMapper.writeValueAsString(body);

            // 发送请求
            String responseJson = getWebClient().post()
                    .uri(API_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();

            // 解析响应
            return parseResponse(query, responseJson);

        } catch (Exception e) {
            String errStr = e.toString();
            String hint = "";
            if (errStr.contains("TimeoutException")) {
                hint = " → 请求超时";
            } else if (errStr.contains("Connection refused")) {
                hint = " → 连接被拒绝";
            }
            log.error("BaiduSearch 失败: \"{}\", error={}{}", query, e.toString(), hint, e);
            return "搜索失败：百度搜索请求异常 - " + e.getMessage();
        }
    }

    /**
     * 解析百度搜索 API 响应，提取 references 列表并格式化。
     */
    private String parseResponse(String query, String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);

            // 检查错误
            if (root.has("code") && !root.get("code").asText().isEmpty()) {
                String code = root.get("code").asText();
                String message = root.has("message") ? root.get("message").asText() : "未知错误";
                log.warn("BaiduSearch API 返回错误: code={}, message={}", code, message);
                return "搜索失败：百度搜索返回错误 - " + message;
            }

            // 提取 references
            JsonNode references = root.get("references");
            if (references == null || !references.isArray() || references.isEmpty()) {
                return "未找到与 \"" + query + "\" 相关的结果。";
            }

            // 格式化结果
            StringBuilder sb = new StringBuilder();
            sb.append("搜索 \"").append(query).append("\" 的结果：\n\n");

            int count = 0;
            for (JsonNode ref : references) {
                if (count >= maxResults) break;

                String title = ref.has("title") ? ref.get("title").asText() : "";
                String url = ref.has("url") ? ref.get("url").asText() : "";
                String content = ref.has("content") ? ref.get("content").asText() : "";

                if (title.isEmpty() && url.isEmpty()) continue;

                count++;
                sb.append(count).append(". **").append(title).append("**\n");
                if (!url.isEmpty()) {
                    sb.append("   ").append(url).append("\n");
                }
                if (!content.isEmpty()) {
                    // 截断过长摘要
                    String s = content.length() > 300
                            ? content.substring(0, 300) + "..."
                            : content;
                    sb.append("   > ").append(s).append("\n");
                }
                sb.append("\n");
            }

            if (count == 0) {
                return "未找到与 \"" + query + "\" 相关的结果。";
            }

            log.info("BaiduSearch 完成: \"{}\" → {} 条结果", query, count);
            return sb.toString().trim();

        } catch (Exception e) {
            log.error("BaiduSearch 响应解析失败", e);
            return "搜索失败：百度搜索响应解析异常";
        }
    }
}
