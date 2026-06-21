package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.Tool;
import com.example.sandbox.web.service.search.SearchProvider;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired(required = false)
    private List<SearchProvider> backupProviders;

    @Value("${agent.search.max-results:5}")
    private int maxResults;

    @Value("${agent.search.timeout:10s}")
    private Duration timeout;

    @Value("${agent.search.proxy.host:}")
    private String proxyHost;

    @Value("${agent.search.proxy.port:0}")
    private int proxyPort;

    private volatile WebClient webClient;

    /** 延迟初始化 WebClient，确保 @Value 字段已注入 */
    private WebClient getWebClient() {
        if (webClient == null) {
            synchronized (this) {
                if (webClient == null) {
                    var builder = WebClient.builder()
                            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(512 * 1024));

                    if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
                        log.info("WebSearch 使用代理: {}:{}", proxyHost, proxyPort);
                        HttpClient httpClient = HttpClient.create()
                                .proxy(proxy -> proxy
                                        .type(ProxyProvider.Proxy.HTTP)
                                        .host(proxyHost)
                                        .port(proxyPort));
                        builder.clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient));
                    }

                    webClient = builder.build();
                }
            }
        }
        return webClient;
    }

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

                        重要：当用户询问"今天、最新、最近"等时间相关问题时，直接使用用户原话中的时间词，不要自行添加或猜测具体年份。除非用户明确指定了年份。
                        """,
                parameters,
                "ALL"
        );
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        String query = extractQuery(arguments);
        if (query == null) {
            log.warn("WebSearch 调用缺少 query 参数, arguments={}", arguments);
            return "错误：缺少 query 参数";
        }
        log.info("WebSearch 开始: \"{}\", sessionId={}", query, sessionId);

        // 优先尝试 SearchProvider（百度等）
        String providerResult = tryProviders(query);
        if (providerResult != null) {
            return providerResult;
        }

        // SearchProvider 全部失败，降级到 DuckDuckGo
        log.info("SearchProvider 全部失败，降级到 DuckDuckGo: \"{}\"", query);
        try {
            String html = fetchResults(query);

            // 检测反爬验证页
            String blockReason = detectChallenge(html);
            if (blockReason != null) {
                log.warn("DuckDuckGo 也被拦截: query=\"{}\", reason={}", query, blockReason);
                return """
                        搜索失败：SEARCH_SOURCE_BLOCKED
                        原因：%s
                        建议：不要继续调用 web_search，切换备用搜索源
                        """.formatted(blockReason);
            }

            List<SearchResult> results = parseResults(html);
            log.info("DuckDuckGo 完成: \"{}\" → {} 条结果", query, results.size());
            if (results.isEmpty()) {
                return "未找到与 \"" + query + "\" 相关的结果。请尝试换用更简洁或不同的关键词。";
            }
            return formatResults(query, results);
        } catch (Exception e) {
            String hint = "";
            String errStr = e.toString();
            if (errStr.contains("TimeoutException")) {
                hint = " → 可能原因: 网络不通或需要配置代理 (agent.search.proxy)";
            } else if (errStr.contains("Connection refused")) {
                hint = " → 可能原因: 代理服务未启动或地址错误";
            } else if (errStr.contains("UnknownHost")) {
                hint = " → 可能原因: DNS 解析失败";
            }
            log.error("DuckDuckGo 失败: \"{}\", error={}{}", query, e.toString(), hint, e);
            return "搜索失败：" + e.getMessage();
        }
    }

    /**
     * 尝试 SearchProvider（百度等）。按顺序尝试所有可用的提供者。
     * @return 成功时返回格式化结果，全部失败时返回 null
     */
    private String tryProviders(String query) {
        if (backupProviders == null || backupProviders.isEmpty()) {
            log.info("WebSearch 无 SearchProvider 可尝试");
            return null;
        }

        for (SearchProvider provider : backupProviders) {
            log.info("WebSearch 尝试 SearchProvider: {}", provider.getName());
            try {
                String result = provider.search(query);
                if (result != null && !result.startsWith("搜索失败：")) {
                    log.info("WebSearch SearchProvider {} 成功", provider.getName());
                    return result;
                }
                log.warn("WebSearch SearchProvider {} 返回失败: {}", provider.getName(), result);
            } catch (Exception e) {
                log.error("WebSearch SearchProvider {} 异常: {}", provider.getName(), e.getMessage(), e);
            }
        }

        log.warn("WebSearch 所有 SearchProvider 均失败");
        return null;
    }

    /**
     * 检测 DuckDuckGo 返回的是否为反爬验证页而非搜索结果。
     * @return 阻断原因字符串，若为正常搜索页则返回 null
     */
    private String detectChallenge(String html) {
        if (html == null || html.isBlank()) return null;
        String lower = html.toLowerCase();

        // Cloudflare 拦截
        if (lower.contains("cf-browser-verification") || lower.contains("cloudflare")) {
            return "DuckDuckGo 返回 Cloudflare 验证页";
        }
        // 通用反爬验证
        if (lower.contains("captcha") || lower.contains("recaptcha")) {
            return "DuckDuckGo 返回验证码页面";
        }
        // robot 检测
        if (lower.contains("robot") && lower.contains("check")) {
            return "DuckDuckGo 触发机器人检测";
        }
        // 异常检测
        if (lower.contains("anomaly") || lower.contains("challenge")) {
            return "DuckDuckGo 返回反爬验证页";
        }
        // 响应体过短（通常正常结果页 > 5KB）
        if (html.length() < 500) {
            return "DuckDuckGo 响应体异常短（" + html.length() + " 字节），可能被拒绝";
        }

        return null;
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
        log.debug("WebSearch HTTP 请求: {}", url);
        log.debug("WebSearch HTTP 超时设置: {}", timeout);

        String html = getWebClient().get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; SandboxAgent/1.0; +https://example.com)")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .block();

        log.debug("WebSearch HTTP 响应: bodyLength={}", html != null ? html.length() : "null");
        return html;
    }

    /** 用 Jsoup 解析 HTML，提取搜索结果 */
    private List<SearchResult> parseResults(String html) {
        List<SearchResult> results = new ArrayList<>();
        if (html == null || html.isBlank()) {
            log.warn("WebSearch 解析: 收到空 HTML");
            return results;
        }

        Document doc = Jsoup.parse(html);
        log.debug("WebSearch 解析: HTML长度={}, title={}", html.length(), doc.title());

        // 主选择器
        var bodies = doc.select(".result__body");
        log.debug("WebSearch 解析: .result__body 匹配 {} 个元素", bodies.size());

        for (Element body : bodies) {
            if (results.size() >= maxResults) break;

            Element titleLink = body.selectFirst(".result__a");
            Element snippet = body.selectFirst(".result__snippet");
            if (titleLink == null) {
                log.debug("WebSearch 解析: .result__body 下无 .result__a, 跳过");
                continue;
            }

            String title = titleLink.text().trim();
            if (title.isEmpty()) continue;

            String rawHref = titleLink.attr("href");
            String url = extractRealUrl(rawHref);
            String snippetText = snippet != null ? snippet.text().trim() : "";

            log.debug("WebSearch 解析结果[{}]: title=\"{}\", url=\"{}\", snippetLen={}",
                    results.size() + 1, title, url, snippetText.length());
            results.add(new SearchResult(title, url, snippetText));
        }

        // 如果主选择器无结果，降级尝试通用链接选择器
        if (results.isEmpty()) {
            log.debug("WebSearch 解析: 主选择器无结果, 尝试降级选择器 a.result__a");
            var fallbackLinks = doc.select("a.result__a");
            log.debug("WebSearch 解析: a.result__a 匹配 {} 个元素", fallbackLinks.size());
            for (Element link : fallbackLinks) {
                if (results.size() >= maxResults) break;
                String title = link.text().trim();
                if (title.isEmpty()) continue;
                String rawHref = link.attr("href");
                String url = extractRealUrl(rawHref);
                log.debug("WebSearch 降级结果[{}]: title=\"{}\", url=\"{}\"",
                        results.size() + 1, title, url);
                results.add(new SearchResult(title, url, ""));
            }
        }

        // 如果还是没结果，自动诊断页面内容
        if (results.isEmpty()) {
            diagnoseEmptyResults(html, doc);
        }

        return results;
    }

    /** 自动诊断空结果原因，在 WARN 级别输出可操作的诊断信息 */
    private void diagnoseEmptyResults(String html, Document doc) {
        String lowerHtml = html.toLowerCase();
        String title = doc.title();
        int bodyLen = html.length();

        // 检测常见问题
        boolean hasChallenge = lowerHtml.contains("challenge") || lowerHtml.contains("anomaly");
        boolean hasCaptcha = lowerHtml.contains("captcha") || lowerHtml.contains("recaptcha");
        boolean hasRobotCheck = lowerHtml.contains("robot") || lowerHtml.contains("bot check");
        boolean hasCloudflare = lowerHtml.contains("cloudflare") || lowerHtml.contains("cf-browser-verification");
        boolean isRedirectPage = lowerHtml.contains("http-equiv=\"refresh\"") || lowerHtml.contains("window.location");
        boolean hasNoIndex = lowerHtml.contains("noindex") || lowerHtml.contains("请稍后");
        boolean titleIsEmpty = title == null || title.isBlank();

        if (hasChallenge || hasCaptcha || hasRobotCheck) {
            log.warn("WebSearch 诊断: 被反爬验证拦截 [title=\"{}\", bodyLen={}, challenge={}, captcha={}, robotCheck={}]",
                    title, bodyLen, hasChallenge, hasCaptcha, hasRobotCheck);
            log.warn("WebSearch 诊断: DuckDuckGo 检测到自动化请求，返回了验证页面而非搜索结果");
        } else if (hasCloudflare) {
            log.warn("WebSearch 诊断: 被 Cloudflare 拦截 [title=\"{}\", bodyLen={}]", title, bodyLen);
        } else if (isRedirectPage) {
            log.warn("WebSearch 诊断: 收到重定向页面而非搜索结果 [title=\"{}\", bodyLen={}]", title, bodyLen);
        } else if (bodyLen < 500) {
            log.warn("WebSearch 诊断: 响应体过短，可能请求被拒绝 [title=\"{}\", bodyLen={}]", title, bodyLen);
        } else if (titleIsEmpty && bodyLen < 2000) {
            log.warn("WebSearch 诊断: 响应无标题且内容很少，可能服务不可用 [bodyLen={}]", bodyLen);
        } else {
            log.warn("WebSearch 诊断: 页面结构可能已变更 [title=\"{}\", bodyLen={}, 搜索结果选择器均未匹配]", title, bodyLen);
            // 输出页面内所有 <a> 标签的 class，帮助定位新选择器
            var allLinks = doc.select("a[class]");
            if (!allLinks.isEmpty()) {
                var classes = allLinks.stream()
                        .map(el -> el.attr("class"))
                        .distinct()
                        .limit(20)
                        .toList();
                log.warn("WebSearch 诊断: 页面中的 <a> class 列表: {}", classes);
            }
        }

        // 保存原始 HTML 到临时文件供人工检查
        try {
            java.nio.file.Path tmpFile = java.nio.file.Files.createTempFile("websearch-debug-", ".html");
            java.nio.file.Files.writeString(tmpFile, html);
            log.warn("WebSearch 诊断: 原始 HTML 已保存到 {}", tmpFile.toAbsolutePath());
        } catch (Exception e) {
            log.debug("WebSearch 诊断: 保存 HTML 失败", e);
        }
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
                String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                log.trace("WebSearch URL 提取: raw={} → decoded={}", rawHref, decoded);
                return decoded;
            }
        } catch (Exception e) {
            log.debug("WebSearch URL 解码失败: rawHref={}", rawHref, e);
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
