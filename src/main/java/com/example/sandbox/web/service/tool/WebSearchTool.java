package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具 — 通过 DuckDuckGo HTML 端点搜索网页并返回格式化结果。
 *
 * <h3>实现</h3>
 * <ul>
 *   <li>用 {@code WebClient} 调用 {@code html.duckduckgo.com/html} 无 JS 端点</li>
 *   <li>用 Jsoup 解析静态 HTML，提取标题、链接、摘要</li>
 *   <li>无需 API Key，无需注册</li>
 * </ul>
 *
 * <h3>返回</h3>
 * <p>每个结果包含：序号、标题（可点击链接）、摘要文本</p>
 */
@Component
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String NAME = "web_search";
    private static final String DDG_URL = "https://html.duckduckgo.com/html/";

    @Value("${agent.search.max-results:5}")
    private int maxResults;

    @Value("${agent.search.timeout:10s}")
    private Duration timeout;

    private final WebClient webClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(512 * 1024))
            .build();

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "搜索关键词，用简洁的自然语言描述你想查什么"
        ));

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("query"),
                "additionalProperties", false
        );

        return new ToolDefinition(
                NAME,
                """
                        通过网络搜索获取最新信息。适用场景：
                        - 查询实时数据（新闻、股价、天气等）
                        - 查询最新技术文档、API 用法
                        - 验证事实或获取超出模型知识的资料
                        返回搜索结果列表，含标题、链接和摘要，可用于后续分析或引用。
                        """,
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String query = extractQuery(arguments);
        if (query == null) {
            return "错误：缺少 query 参数";
        }
        log.info("WebSearch 开始: \"{}\"", query);

        try {
            String html = fetchResults(query);
            List<SearchResult> results = parseResults(html);
            if (results.isEmpty()) {
                return "未找到与 \"" + query + "\" 相关的结果。请尝试换用更简洁或不同的关键词。";
            }
            return formatResults(query, results);
        } catch (Exception e) {
            log.error("WebSearch 失败: \"{}\"", query, e);
            return "搜索失败：" + e.getMessage();
        }
    }

    /** 从参数中提取 query 字符串 */
    private String extractQuery(Map<String, Object> arguments) {
        if (arguments == null) return null;
        Object q = arguments.get("query");
        if (q instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    /** 调用 DuckDuckGo HTML 端点 */
    private String fetchResults(String query) {
        String url = DDG_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        return webClient.get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; SandboxAgent/1.0; +https://example.com)")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .block();
    }

    /** 用 Jsoup 解析 HTML，提取搜索结果 */
    private List<SearchResult> parseResults(String html) {
        List<SearchResult> results = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        for (Element body : doc.select(".result__body")) {
            if (results.size() >= maxResults) break;

            Element titleLink = body.selectFirst(".result__a");
            Element snippet = body.selectFirst(".result__snippet");
            if (titleLink == null) continue;

            String title = titleLink.text().trim();
            if (title.isEmpty()) continue;

            String rawHref = titleLink.attr("href");
            String url = extractRealUrl(rawHref);
            String snippetText = snippet != null ? snippet.text().trim() : "";

            results.add(new SearchResult(title, url, snippetText));
        }

        // 如果主选择器无结果，降级尝试通用链接选择器
        if (results.isEmpty()) {
            for (Element link : doc.select("a.result__a")) {
                if (results.size() >= maxResults) break;
                String title = link.text().trim();
                if (title.isEmpty()) continue;
                String rawHref = link.attr("href");
                String url = extractRealUrl(rawHref);
                results.add(new SearchResult(title, url, ""));
            }
        }

        return results;
    }

    /**
     * 从 DuckDuckGo 重定向 URL 中提取真实目标地址。
     * <p>格式: {@code https://duckduckgo.com/l/?uddg=ENCODED_URL&rut=...}</p>
     */
    private String extractRealUrl(String rawHref) {
        if (rawHref == null || rawHref.isBlank()) return "";
        try {
            if (rawHref.contains("uddg=")) {
                int start = rawHref.indexOf("uddg=") + 5;
                int end = rawHref.indexOf("&", start);
                String encoded = end > 0 ? rawHref.substring(start, end) : rawHref.substring(start);
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 解析失败就用原始 href
        }
        return rawHref;
    }

    /** 格式化为 LLM 友好的文本 */
    private String formatResults(String query, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索 \"").append(query).append("\" 的结果：\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". **").append(r.title).append("**\n");
            if (!r.url.isEmpty()) {
                sb.append("   ").append(r.url).append("\n");
            }
            if (!r.snippet.isEmpty()) {
                // 截断过长摘要
                String s = r.snippet.length() > 300
                        ? r.snippet.substring(0, 300) + "..."
                        : r.snippet;
                sb.append("   > ").append(s).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** 搜索结果记录 */
    private record SearchResult(String title, String url, String snippet) {
    }
}
