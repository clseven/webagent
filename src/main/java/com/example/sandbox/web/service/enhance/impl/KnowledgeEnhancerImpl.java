package com.example.sandbox.web.service.enhance.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.service.KnowledgeService;
import com.example.sandbox.web.service.enhance.KnowledgeEnhancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 知识库上下文适配器。
 *
 * <h3>用途</h3>
 * <p>复用 {@link KnowledgeService} 的统一多知识库检索流水线，并把结构化结果
 * 格式化为可注入规划器和执行器的上下文文本。</p>
 *
 * <h3>故障策略</h3>
 * <p>检索异常只影响本轮知识增强，不中断 Agent 主流程；异常会记录完整堆栈并降级为空上下文。</p>
 */
@Service
public class KnowledgeEnhancerImpl implements KnowledgeEnhancer {

    /** 检索增强运行日志。 */
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEnhancerImpl.class);

    /** RAG 配置，用于读取自动预检索的开关、topK 和最低重排相关度。 */
    @Autowired
    private RagConfigProperties config;

    /** 统一知识库服务，供页面、工具和自动预检索共享同一流水线。 */
    @Autowired
    private KnowledgeService knowledgeService;

    /**
     * 检索应用关联的全部知识库并构建 Agent 上下文。
     *
     * @param userId 当前用户 ID
     * @param kbIds 当前 Agent 应用允许使用的完整知识库 ID 集合
     * @param userMessage 原始用户消息
     * @param history 本轮消息入库前的对话历史，用于查询改写
     * @return 格式化后的知识库上下文；关闭、无结果或检索失败时返回空字符串
     */
    @Override
    public String enhance(Long userId, List<Long> kbIds, String userMessage, List<ChatMessage> history) {
        RagConfigProperties.Enhancement enhancement = config.getEnhancement();
        if (!enhancement.isEnabled()) {
            return "";
        }
        if (kbIds == null || kbIds.isEmpty()) {
            log.debug("未指定知识库，跳过检索增强");
            return "";
        }

        long started = System.currentTimeMillis();
        try {
            List<Map<String, Object>> results = knowledgeService.search(
                    userId,
                    kbIds,
                    userMessage,
                    history,
                    enhancement.getRerank().getTopK(),
                    enhancement.getRerank().getMinScore());
            String context = buildContext(results);
            log.info("检索增强完成: 知识库={} 个, 耗时={}ms, 最终={} 个片段",
                    kbIds.size(), System.currentTimeMillis() - started, results.size());
            return context;
        } catch (Exception e) {
            log.error("检索增强失败", e);
            return "";
        }
    }

    /**
     * 把统一检索结果格式化为提示词片段。
     *
     * @param results 已按全局相关度排序的结构化检索结果
     * @return 可拼接到本轮模型输入的上下文；无结果时返回空字符串
     */
    private String buildContext(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("## 知识库检索结果（来自用户上传的文档）\n\n");
        context.append("以下内容可能与用户问题相关，已按相关性排序：\n\n");
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> result = results.get(i);
            String docName = String.valueOf(result.getOrDefault("docName", "未知文档"));
            int chunkIndex = ((Number) result.get("chunkIndex")).intValue();
            float score = ((Number) result.get("score")).floatValue();
            String content = String.valueOf(result.getOrDefault("content", ""));
            context.append(String.format(Locale.ROOT,
                    "[%d] 来源：%s（片段 %d，相关度 %.2f）\n%s\n\n",
                    i + 1, docName, chunkIndex, score, content));
        }
        return context.toString();
    }
}
