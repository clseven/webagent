package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.config.RagConfigProperties;
import com.example.sandbox.web.service.VectorStoreService;
import io.milvus.client.MilvusClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 向量存储服务实现
 *
 * @author example
 * @date 2026/05/31
 */
@Service
@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreServiceImpl implements VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreServiceImpl.class);

    @Autowired
    private MilvusClient milvusClient;

    @Autowired
    private RagConfigProperties config;

    @Override
    public void insert(Long userId, Long kbId, Long docId, int chunkIndex, float[] embedding) {
        insertBatch(userId, kbId, docId, List.of(Map.of("chunkIndex", chunkIndex, "embedding", (Object) embedding)));
    }

    @Override
    public void insertBatch(Long userId, Long kbId, Long docId, List<Map<String, Object>> items) {
        String collectionName = config.getMilvus().getCollection();

        List<Long> kbIds = new ArrayList<>();
        List<Long> docIds = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        List<Long> chunkIndexes = new ArrayList<>();
        List<List<Float>> embeddings = new ArrayList<>();

        for (Map<String, Object> item : items) {
            kbIds.add(kbId);
            docIds.add(docId);
            userIds.add(userId);
            chunkIndexes.add(((Number) item.get("chunkIndex")).longValue());
            // 将 float[] 转换为 List<Float>
            float[] embedding = (float[]) item.get("embedding");
            List<Float> vector = new ArrayList<>();
            for (float v : embedding) {
                vector.add(v);
            }
            embeddings.add(vector);
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("kb_id", kbIds));
        fields.add(new InsertParam.Field("doc_id", docIds));
        fields.add(new InsertParam.Field("user_id", userIds));
        fields.add(new InsertParam.Field("chunk_index", chunkIndexes));
        fields.add(new InsertParam.Field("vector", embeddings));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        milvusClient.insert(insertParam);
        log.info("向量存储成功: userId={}, docId={}, 数量={}", userId, docId, items.size());
    }

    @Override
    public List<Map<String, Object>> search(Long userId, Long kbId, float[] queryEmbedding, int topK) {
        String collectionName = config.getMilvus().getCollection();

        // 将 float[] 转换为 List<Float>
        List<Float> vector = new ArrayList<>();
        for (float v : queryEmbedding) {
            vector.add(v);
        }

        log.info("向量检索参数: collection={}, userId={}, kbId={}, topK={}, vectorSize={}", collectionName, userId, kbId, topK, vector.size());

        String expr = "user_id == " + userId;
        if (kbId != null) {
            expr += " && kb_id == " + kbId;
        }

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectors(List.of(vector))
                .withVectorFieldName("vector")
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withOutFields(List.of("doc_id", "chunk_index"))
                .withExpr(expr)
                .build();

        R<SearchResults> resp = milvusClient.search(searchParam);
        SearchResults results = resp.getData();

        log.info("Milvus 响应: results={}, fieldsDataCount={}", results != null, results != null ? results.getResults().getFieldsDataCount() : 0);

        List<Map<String, Object>> searchResults = new ArrayList<>();
        if (results != null && results.getResults() != null && results.getResults().getFieldsDataCount() > 0) {
            var fieldsDataList = results.getResults().getFieldsDataList();
            var scoresList = results.getResults().getScoresList();

            log.info("Milvus 返回数据: fieldsDataListSize={}, scoresListSize={}", fieldsDataList.size(), scoresList.size());

            List<Long> docIds = new ArrayList<>();
            List<Long> chunkIndexes = new ArrayList<>();

            for (var fieldData : fieldsDataList) {
                log.info("字段: name={}, 数据量={}", fieldData.getFieldName(), fieldData.getScalars().getLongData().getDataList().size());
                switch (fieldData.getFieldName()) {
                    case "doc_id":
                        docIds.addAll(fieldData.getScalars().getLongData().getDataList());
                        break;
                    case "chunk_index":
                        chunkIndexes.addAll(fieldData.getScalars().getLongData().getDataList());
                        break;
                }
            }

            for (int i = 0; i < docIds.size(); i++) {
                Map<String, Object> result = new HashMap<>();
                result.put("docId", docIds.get(i));
                result.put("chunkIndex", chunkIndexes.get(i));
                result.put("score", scoresList.size() > i ? scoresList.get(i) : 0.0f);
                searchResults.add(result);
            }
        }

        log.info("向量检索完成: userId={}, 结果数={}", userId, searchResults.size());
        return searchResults;
    }

    @Override
    public void deleteByDocId(Long docId) {
        String collectionName = config.getMilvus().getCollection();

        milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("doc_id == " + docId)
                .build()
        );

        log.info("向量删除成功: docId={}", docId);
    }

    @Override
    public void deleteByUserId(Long userId) {
        String collectionName = config.getMilvus().getCollection();

        milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("user_id == " + userId)
                .build()
        );

        log.info("向量删除成功: userId={}", userId);
    }

    @Override
    public void deleteByKbId(Long kbId) {
        String collectionName = config.getMilvus().getCollection();

        milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("kb_id == " + kbId)
                .build()
        );

        log.info("向量删除成功: kbId={}", kbId);
    }
}
