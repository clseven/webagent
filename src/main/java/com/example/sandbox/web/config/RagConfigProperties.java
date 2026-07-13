package com.example.sandbox.web.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库配置
 *
 * @author example
 * @date 2026/05/31
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "rag")
public class RagConfigProperties {

    private Embedding embedding = new Embedding();
    private Chunk chunk = new Chunk();
    private Milvus milvus = new Milvus();
    private Storage storage = new Storage();
    private Enhancement enhancement = new Enhancement();
    private Preview preview = new Preview();

    @Setter
    @Getter
    public static class Embedding {
        private String apiUrl = "https://open.bigmodel.cn/api/paas/v4/embeddings";
        private String apiKey = "";
        private String model = "embedding-3";
        private int dimension = 1024;
        private int batchSize = 25;

    }

    @Setter
    @Getter
    public static class Chunk {
        private int size = 500;
        private int overlap = 50;

    }

    @Setter
    @Getter
    public static class Milvus {
        /** 是否启用 Milvus 向量库；关闭后仅保留知识库正文存储和空检索降级。 */
        private boolean enabled = true;
        private String host = "localhost";
        private int port = 19530;
        private String collection = "knowledge_chunks";

    }

    @Setter
    @Getter
    public static class Storage {
        private String path = "./uploads/knowledge";

    }

    @Setter
    @Getter
    public static class Preview {
        private Conversion conversion = new Conversion();
    }

    @Setter
    @Getter
    public static class Conversion {
        private boolean enabled;
        private int timeoutSeconds;
    }

    /**
     * 检索增强配置
     */
    @Setter
    @Getter
    public static class Enhancement {
        private boolean enabled;
        private Rewrite rewrite = new Rewrite();
        private Retrieve retrieve = new Retrieve();
        private Rerank rerank = new Rerank();

        @Setter
        @Getter
        public static class Rewrite {
            private boolean enabled;
            private int maxQueries;
            private String apiUrl;
            private String apiKey;
            private String model;
            private double temperature;
            private double topP;
            private int maxTokens;

        }

        @Setter
        @Getter
        public static class Retrieve {
            private int topN;

        }

        @Setter
        @Getter
        public static class Rerank {
            /** 是否启用外部重排；关闭后按向量分数排序。 */
            private boolean enabled = true;
            /** 重排提供方，可选 deepseek 或 bge。 */
            private String provider = "deepseek";
            /** 重排服务基础地址。 */
            private String apiUrl = "https://api.deepseek.com";
            /** 重排服务 API Key；不得写入日志。 */
            private String apiKey = "";
            /** 仅供重排使用的模型名，不继承主流程模型名。 */
            private String model = "deepseek-v4-flash";
            /** 默认最终重排条数。 */
            private int topK = 5;
            /** 默认最低相关度；仅成功重排后的低分结果不会进入最终结果。 */
            private float minScore = 0.8f;
            /** 发送给模型的最大候选数。 */
            private int maxCandidates = 12;
            /** 单个候选发送给模型的最大字符数。 */
            private int maxContentChars = 1200;
            /** 外部重排总超时秒数。 */
            private int timeoutSeconds = 10;
            /** 外部模型最大输出 Token 数。 */
            private int maxTokens = 512;

        }
    }
}
