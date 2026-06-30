package com.example.sandbox.web.service.tool;

import com.example.sandbox.web.context.UserContext;
import com.example.sandbox.web.model.entity.ToolDefinition;
import com.example.sandbox.web.service.KnowledgeService;
import com.example.sandbox.web.service.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 知识库检索工具 — 从向量数据库中检索相关内容（RAG）
 *
 * <h3>用途</h3>
 * <p>让 LLM 能基于用户上传的文档回答问题（Retrieval-Augmented Generation）：</p>
 * <ol>
 *   <li>用户上传文档 → 系统切片、向量化、入库</li>
 *   <li>用户提问 → 工具检索最相关的文档片段</li>
 *   <li>LLM 基于检索结果生成回答</li>
 * </ol>
 *
 * <h3>动态描述注入</h3>
 * <p>不同应用可能关联不同的知识库，此工具支持动态修改描述。
 * AgentServiceImpl 在处理请求时，会根据应用配置的知识库动态设置描述，
 * 让 LLM 知道"有哪些知识库可以用"。</p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>query — 搜索查询内容</li>
 *   <li>top_k — 返回结果数量（默认 5）</li>
 * </ul>
 *
 * <h3>返回值</h3>
 * <p>返回格式化的检索结果列表，包含来源文档、相似度、内容片段：</p>
 * <pre>
 * [1] 来源: 用户手册.pdf (片段3, 相似度: 0.89)
 *     在沙箱中创建文件的方法是...
 * </pre>
 *
 * @author example
 * @date 2026/05/31
 */
@Component
public class KnowledgeSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);
    private static final String NAME = "knowledge_search";

    @Autowired
    private KnowledgeService knowledgeService;

    /** 当前会话关联的知识库 ID（由 AgentServiceImpl 在处理前设置） */
    private final ThreadLocal<Long> currentKbId = new ThreadLocal<>();

    /** 动态工具描述（由 AgentServiceImpl 根据知识库描述注入） */
    private final ThreadLocal<String> dynamicDescription = new ThreadLocal<>();

    @Override
    public ToolDefinition getDefinition() {
        String desc = dynamicDescription.get();
        if (desc == null) {
            desc = "从知识库检索更多信息。注意：系统已自动预检索最相关的内容到上下文，" +
                   "仅在需要更深入或不同角度的检索时调用此工具。";
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "用于向量检索的关键词或问题。请使用自包含表述，不依赖对话代词。");
        properties.put("query", queryProp);

        Map<String, Object> topKProp = new LinkedHashMap<>();
        topKProp.put("type", "integer");
        topKProp.put("description", "返回结果数量，默认5");
        properties.put("top_k", topKProp);

        parameters.put("properties", properties);
        parameters.put("required", List.of("query"));

        return new ToolDefinition(NAME, desc, parameters);
    }

    /**
     * 获取带自定义描述的工具定义
     */
    public ToolDefinition getDefinitionWithDescription(String description) {
        String oldDesc = dynamicDescription.get();
        dynamicDescription.set(description);
        ToolDefinition def = getDefinition();
        dynamicDescription.set(oldDesc);
        return def;
    }

    /**
     * 设置当前会话关联的知识库 ID
     */
    public void setCurrentKbId(Long kbId) {
        currentKbId.set(kbId);
    }

    /**
     * 清除当前会话的知识库 ID
     */
    public void clearCurrentKbId() {
        currentKbId.remove();
    }

    /**
     * 清除动态描述
     */
    public void clearDynamicDescription() {
        dynamicDescription.remove();
    }

    @Override
    public String execute(String sessionId, Map<String, Object> arguments) {
        try {
            String query = (String) arguments.get("query");
            int topK = arguments.containsKey("top_k")
                    ? ((Number) arguments.get("top_k")).intValue()
                    : 5;

            Long userId = UserContext.getCurrentUserId();
            Long kbId = currentKbId.get();
            log.info("知识库检索: userId={}, kbId={}, query={}, topK={}", userId, kbId, query, topK);

            List<Map<String, Object>> results = knowledgeService.search(userId, kbId, query, topK);

            if (results.isEmpty()) {
                return "未找到相关知识库内容。请确认已上传相关文档。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("以下是从知识库中检索到的相关内容：\n\n");

            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> r = results.get(i);
                float score = ((Number) r.get("score")).floatValue();
                String docName = (String) r.get("docName");
                String content = (String) r.get("content");
                int chunkIndex = ((Number) r.get("chunkIndex")).intValue();

                sb.append(String.format("[%d] 来源: %s (片段%d, 相似度: %.2f)\n%s\n\n",
                        i + 1, docName, chunkIndex, score, content));
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("知识库检索失败", e);
            return "知识库检索失败: " + e.getMessage();
        }
    }
}
