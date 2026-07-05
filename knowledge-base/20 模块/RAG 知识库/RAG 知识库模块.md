---
project: webagent-clean
type: module
status: verified
area:
  - rag
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/controller/RagController.java
  - src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/EmbeddingServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/VectorStoreServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/NoopVectorStoreServiceImpl.java
  - src/main/resources/application.yml
updated: 2026-07-06
---

# RAG 知识库模块

## 职责

RAG 知识库模块负责用户知识库、文档上传、文本抽取、切片、Embedding、向量写入、检索、改写和重排。它为 Agent 提供 `knowledge_search` 工具，也为前端 Knowledge 页面提供管理和预览接口。

## 核心数据

- 知识库：用户创建的知识集合。
- 文档：上传到某个知识库的原始文件及其元数据。
- 切片：文档解析后的文本片段，带 chunk index、偏移和元数据。
- 向量：切片 Embedding 后写入 Milvus；Milvus 关闭时跳过写入。

## 默认配置

- Embedding：智谱 `embedding-3`，维度 2048，batch size 25。
- Chunk：size 500，overlap 50。
- Milvus：`RAG_MILVUS_ENABLED=false` 默认关闭；启用后集合 `knowledge_chunks_v3`。
- Query Rewrite：默认启用，Ollama `qwen3:1.7b`，最多 3 个查询。
- Retrieve：每个 query topN 20，minScore 0.5。
- Rerank：默认启用，BGE rerank topK 8。

## Milvus 可选化

`MilvusConfig` 和 `VectorStoreServiceImpl` 只有在 `rag.milvus.enabled=true` 时启用。关闭时，`NoopVectorStoreServiceImpl` 接住插入、删除和搜索：

- 写入和删除只记录跳过日志。
- 检索返回空结果。
- 系统仍可启动，便于没有本地 Milvus 的开发环境。

## 与 Agent 的关系

当 Agent 应用关联知识库时，`AgentTurnContextService` 会准备知识库上下文，并动态调整 `knowledge_search` 的描述和默认范围。知识增强失败不应中断主对话，而应降级为空上下文。

## 相关页面

[[文档摄取与切片]] · [[查询改写与重排]] · [[知识库文档上传与检索]] · [[Skill 与知识检索工具]]