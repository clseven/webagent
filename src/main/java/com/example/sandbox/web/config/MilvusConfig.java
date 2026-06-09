package com.example.sandbox.web.config;

import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;

/**
 * Milvus 向量数据库配置
 *
 * @author example
 * @date 2026/05/31
 */
@Configuration
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);

    @Autowired
    private RagConfigProperties config;

    @Lazy
    @Autowired
    private MilvusClient milvusClient;

    @Bean(destroyMethod = "close")
    public MilvusClient milvusClient() {
        RagConfigProperties.Milvus milvusConfig = config.getMilvus();
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(milvusConfig.getHost())
                .withPort(milvusConfig.getPort())
                .build();
        log.info("连接 Milvus: {}:{}", milvusConfig.getHost(), milvusConfig.getPort());
        return new MilvusServiceClient(connectParam);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initCollection() {
        try {
            String collectionName = config.getMilvus().getCollection();
            int dimension = config.getEmbedding().getDimension();

            R<Boolean> hasResp = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build()
            );

            if (!hasResp.getData()) {
                log.info("创建 Milvus 集合: {}", collectionName);

                FieldType idField = FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.Int64)
                        .withPrimaryKey(true)
                        .withAutoID(true)
                        .build();

                FieldType kbIdField = FieldType.newBuilder()
                        .withName("kb_id")
                        .withDataType(DataType.Int64)
                        .build();

                FieldType docIdField = FieldType.newBuilder()
                        .withName("doc_id")
                        .withDataType(DataType.Int64)
                        .build();

                FieldType userIdField = FieldType.newBuilder()
                        .withName("user_id")
                        .withDataType(DataType.Int64)
                        .build();

                FieldType chunkIndexField = FieldType.newBuilder()
                        .withName("chunk_index")
                        .withDataType(DataType.Int64)
                        .build();

                FieldType vectorField = FieldType.newBuilder()
                        .withName("vector")
                        .withDataType(DataType.FloatVector)
                        .withDimension(dimension)
                        .build();

                CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .addFieldType(idField)
                        .addFieldType(kbIdField)
                        .addFieldType(docIdField)
                        .addFieldType(userIdField)
                        .addFieldType(chunkIndexField)
                        .addFieldType(vectorField)
                        .build();

                milvusClient.createCollection(createParam);

                CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName("vector")
                        .withIndexType(IndexType.HNSW)
                        .withMetricType(MetricType.COSINE)
                        .withExtraParam("{\"M\":16,\"efConstruction\":256}")
                        .build();

                milvusClient.createIndex(indexParam);

                log.info("Milvus 集合创建成功: {}", collectionName);
            } else {
                log.info("Milvus 集合已存在: {}", collectionName);
            }

            // 加载集合到内存
            log.info("加载 Milvus 集合到内存: {}", collectionName);
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            log.info("Milvus 集合加载完成: {}", collectionName);

        } catch (Exception e) {
            log.warn("Milvus 初始化失败（可能未启动）: {}", e.getMessage());
        }
    }
}
