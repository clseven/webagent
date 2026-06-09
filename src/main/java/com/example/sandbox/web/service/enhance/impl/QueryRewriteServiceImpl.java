package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.enhance.QueryRewriteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Rewrite 服务实现
 *
 * <p>使用 LLM（GLM-4-Flash）改写查询，消解指代、补全省略。</p>
 *
 * @author example
 * @date 2026/06/05
 */
@Service
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteServiceImpl.class);

    /** 用于从 LLM 输出中提取 JSON 数组 */
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

    /** 改写 prompt 模板 */
    private static final String REWRITE_SYSTEM_PROMPT = """
            你是搜索查询改写助手。基于以下对话历史，把用户的最新问题改写成 1~3 个独立、可搜索的查询。

            要求：
            1. 消除代词（它/这/那/他/她）→ 替换为具体对象
            2. 补全省略的主语、对比对象、上下文
            3. 保留专有名词、型号、错误码、版本号
            4. 如包含"对比 A 和 B"语义，输出两个独立 query
            5. 如问题已足够清晰，输出原 query 一个即可
            6. 用 JSON 数组返回，例如：["query1", "query2"]

            只输出 JSON 数组，不要输出其他内容。
            """;

    @Autowired
    @Qualifier("plannerLlm")
    private LlmService llmService;

    @Autowired
    private RagConfigProperties config;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public List<String> rewrite(String userMessage, List<ChatMessage> history) {
        RagConfigProperties.Enhancement.Rewrite rewriteConfig = config.getEnhancement().getRewrite();
        if (!rewriteConfig.isEnabled()) {
            return List.of(userMessage);
        }

        try {
            // 构建历史对话摘要
            String historyText = buildHistoryText(history);

            String userPrompt = """
                    对话历史：
                    %s

                    当前问题：
                    %s

                    改写结果（JSON 数组）：
                    """.formatted(historyText, userMessage);

            // 构建消息并调用 LLM
            List<ChatMessage> messages = List.of(ChatMessage.userMessage(userPrompt));
            String response = llmService.chatWithSystem(REWRITE_SYSTEM_PROMPT, messages);

            List<String> queries = parseQueries(response, rewriteConfig.getMaxQueries());

            if (queries.isEmpty()) {
                log.warn("Query Rewrite 返回空，降级为原始 query");
                return List.of(userMessage);
            }

            log.info("Query Rewrite: {} -> {}", userMessage, queries);
            return queries;

        } catch (Exception e) {
            log.warn("Query Rewrite 失败，降级为原始 query: {}", e.getMessage());
            return List.of(userMessage);
        }
    }

    /**
     * 构建历史对话文本（取最近 N 条）
     */
    private String buildHistoryText(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史对话）";
        }

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, history.size() - 6); // 最近 6 条
        for (int i = start; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 数组
     */
    private List<String> parseQueries(String response, int maxQueries) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        // 尝试匹配 JSON 数组
        Matcher m = JSON_ARRAY_PATTERN.matcher(response);
        if (m.find()) {
            String json = m.group();
            try {
                List<String> queries = objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                // 过滤空字符串，限制数量
                return queries.stream()
                        .filter(q -> q != null && !q.isBlank())
                        .limit(maxQueries)
                        .toList();
            } catch (Exception e) {
                log.debug("解析 JSON 数组失败: {}", e.getMessage());
            }
        }

        // 降级：尝试按行/逗号分割
        String[] parts = response.split("[\n,]");
        List<String> queries = new ArrayList<>();
        for (String part : parts) {
            String q = part.trim().replaceAll("^[\"'\\[\\]]+|[\"'\\[\\]]+$", "");
            if (!q.isEmpty() && queries.size() < maxQueries) {
                queries.add(q);
            }
        }

        return queries;
    }
}
