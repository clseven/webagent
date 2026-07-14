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
  - src/main/java/com/example/sandbox/web/service/impl/KnowledgeDocumentProcessor.java
  - src/main/java/com/example/sandbox/web/service/impl/EmbeddingServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/VectorStoreServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/NoopVectorStoreServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/enhance/impl/KnowledgeEnhancerImpl.java
  - src/main/java/com/example/sandbox/web/service/enhance/impl/DeepSeekRerankServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/enhance/RerankResultFilter.java
  - src/main/java/com/example/sandbox/web/service/impl/AgentKnowledgeContextService.java
  - src/main/java/com/example/sandbox/web/config/RagConfigProperties.java
  - src/main/resources/application.yml
updated: 2026-07-13
---

# RAG 知识库模块

## 1. 模块概览

本文是 RAG 知识库模块的总览文档，说明知识库 CRUD、文档摄取链路、检索增强链路、Milvus 可选化和 Agent 接入方式的整体架构和边界划分。

当前实现里需要特别注意八点：

- 文档处理已拆到 `KnowledgeDocumentProcessor` 异步执行，上传接口返回时文档通常仍是 `PENDING` 或 `PROCESSING`。
- Milvus 默认关闭（`rag.milvus.enabled=false`）；关闭时文档和切片仍保存，但向量写入和检索由 [[#NoopVectorStoreServiceImpl]] 降级。
- `rag.chunk.size=500` 和 `rag.chunk.overlap=50` 是已定义配置，但上传链路默认走 `smart` 模式，不直接使用这两个值，详见 [[文档摄取与切片]]。
- 检索增强已收敛为统一流水线：Query Rewrite → 批量 Embedding → 限定知识库集合召回 → 跨查询去重 → 全局 Rerank → 结果过滤/截取 → 构建上下文。
- Rerank 默认使用独立的 DeepSeek Flash 模型配置；BGE 仍保留为可切换 provider，外部重排失败时显式降级为向量排序。
- 知识增强失败不中断主对话，降级为空上下文，对回答质量有影响但不让 Agent 主流程失败。
- Agent 应用关联知识库后，`AgentKnowledgeContextService` 负责加载应用配置、构建工具描述和生成增强上下文；是否启用由请求级 `knowledgeEnabled` 开关控制，不再由 SOCIAL/TASK 轮次策略替用户决定。
- `READY` 不等于沙箱已同步：`syncToSandbox` 失败只把 `sandboxSynced` 置 `false`，状态仍为 `READY`，但预览可能不可用。

## 2. 适用范围

### 2.1 本文覆盖

- RAG 模块的整体架构和两条主线（摄取/检索）的边界划分。
- 知识库 CRUD、文档上传/替换/删除的入口和清理范围。
- Milvus 可选化的实现机制和降级行为。
- Agent 应用关联知识库后的上下文注入方式。
- RAG 全部配置项的来源、默认值和实际影响。
- 核心数据模型和实体职责。
- 风险排查清单。

### 2.2 本文不覆盖

- 切片模式（smart/custom）、切片参数和位置标注的细节，见 [[文档摄取与切片]]。
- Query Rewrite、向量召回和 Rerank 的实现细节，见 [[查询改写与重排]]。
- Agent 如何调用 `knowledge_search` 工具，见 [[Skill 与知识检索工具]]。
- Office 预览转换链路，见 [[Office 文件预览]]。

## 3. 模块职责

RAG 知识库模块为两类入口服务：

- 前端 Knowledge 页面：管理知识库、上传/替换/删除文档、预览原文、查看切片、手动检索。
- Agent 执行链路：当应用关联知识库时，为 `knowledge_search` 工具和上下文增强提供检索能力。

两条入口共享同一套知识库数据，但走不同的服务方法。前端走 `RagController` 的 REST 接口；Agent 走 `AgentKnowledgeContextService` 和 `KnowledgeEnhancer`。

## 4. 当前配置总览

### 4.1 Embedding 与 Milvus 配置

| 配置项 | 当前值 | 环境变量 | 说明 |
| --- | --- | --- | --- |
| `rag.embedding.api-url` | `https://open.bigmodel.cn/api/paas/v4/embeddings` | `RAG_EMBEDDING_URL` | [[#Embedding]] 服务地址 |
| `rag.embedding.api-key` | 空 | `RAG_EMBEDDING_KEY` | Embedding API Key，需外部注入 |
| `rag.embedding.model` | `embedding-3` | `RAG_EMBEDDING_MODEL` | Embedding 模型名 |
| `rag.embedding.dimension` | `2048` | 无 | [[#Milvus]] 向量维度需与 Embedding 输出一致 |
| `rag.embedding.batch-size` | `25` | 无 | 批量生成 Embedding 的批大小 |
| `rag.milvus.enabled` | `false` | `RAG_MILVUS_ENABLED` | 默认关闭 Milvus |
| `rag.milvus.host` | `localhost` | `MILVUS_HOST` | Milvus 主机 |
| `rag.milvus.port` | `19530` | `MILVUS_PORT` | Milvus 端口 |
| `rag.milvus.collection` | `knowledge_chunks_v3` | 无 | 当前向量集合名 |
| `rag.storage.path` | `./uploads/knowledge` | `RAG_STORAGE_PATH` | 旧版知识库文件存储路径，当前用户级路径由 `UserWorkspaceStorageService` 管理 |
| `rag.preview.conversion.enabled` | `true` | `RAG_PREVIEW_CONVERSION_ENABLED` | 沙箱同步成功后是否异步触发 Office 预转换 |
| `rag.preview.conversion.timeout-seconds` | `120` | `RAG_PREVIEW_CONVERSION_TIMEOUT_SECONDS` | 单次 Office 预转换超时 |

### 4.2 切片配置

| 配置项 | 当前值 | 来源 | 实际是否使用 |
| --- | --- | --- | --- |
| `rag.chunk.size` | `500` | `application.yml` + 代码默认 | 当前上传默认 `smart`，不直接使用 |
| `rag.chunk.overlap` | `50` | `application.yml` + 代码默认 | 当前上传默认 `smart`，不直接使用 |

切片实际参数主要来自前端或调用方提交的 `chunkSize` 和 `overlap`，详见 [[文档摄取与切片]] 的配置表。

### 4.3 检索增强配置

| 配置项 | 当前值 | 说明 |
| --- | --- | --- |
| `rag.enhancement.enabled` | `true` | 是否启用检索增强 |
| `rag.enhancement.rewrite.enabled` | `true` | 是否启用 Query Rewrite |
| `rag.enhancement.rewrite.max-queries` | `5` | Query Rewrite 最大查询数 |
| `rag.enhancement.retrieve.top-n` | `20` | 向量召回数量 |
| `rag.enhancement.rerank.provider` | `deepseek` | Rerank provider，可切换为 `bge` |
| `rag.enhancement.rerank.model` | `deepseek-v4-flash` | 独立重排模型，不继承主流程 `DEEPSEEK_LLM_MODEL` |
| `rag.enhancement.rerank.top-k` | `5` | Rerank 后默认保留数量 |
| `rag.enhancement.rerank.min-score` | `0.8` | 成功 Rerank 后的最低相关度过滤阈值 |
| `rag.enhancement.rerank.max-candidates` | `12` | 送入外部重排模型的最大候选数 |
| `rag.enhancement.rerank.timeout-seconds` | `10` | 外部重排超时时间，超时后向量降级 |

Query Rewrite 当前默认走 DeepSeek 配置回退，不是旧文档里的 Ollama/qwen3。Rerank 与主流程模型分离：主流程可切换到 Pro，重排仍可保持 Flash 以控制延迟和成本。

## 5. 两条主线

### 5.1 文档摄取主线

入口：`RagController.upload` → `KnowledgeServiceImpl.upload` → `KnowledgeDocumentProcessor.processDocumentAsync`

同步阶段只做校验、查重和入库，完成后立即返回 `PENDING`。异步阶段负责解析、切片、Embedding、向量写入、沙箱同步和状态更新。详细规则见 [[文档摄取与切片]]。

关键边界：

- 同步阶段会做同名文件检查（忽略大小写），命中直接抛 `DuplicateFileException`，不会进入异步处理。
- 异步阶段没有事务包裹，每个 `save` 都是独立事务。如果切片已写 MySQL、向量已写 Milvus 之后、状态置 `READY` 之前抛异常，已写入的切片和向量不会被回滚，形成脏数据残留。
- `READY` 不等于沙箱已同步：`syncToSandbox` 失败只置 `sandboxSynced=false`，状态仍为 `READY`。

### 5.2 检索增强主线

入口：`KnowledgeService` 多知识库检索入口。REST 单库搜索、`knowledge_search` 工具和 Agent 自动预检索都委托同一套实现。

七步流程：

1. Query Rewrite：根据原始问题和历史生成多个 query。
2. 批量 Embedding：一次性向量化所有 query。
3. 限定知识库集合召回：只在当前 Agent 应用关联或 REST 指定的知识库 ID 集合中检索。
4. 跨查询去重：按 `docId + chunkIndex` 去重，保留原始向量分数最高的候选。
5. 全局 Rerank：由 `RerankService` 批量重排候选；成功时返回 `reranked=true`，失败时返回 `reranked=false` 的向量降级结果。
6. 结果过滤与截取：成功 Rerank 时按 `min-score` 过滤后再应用 topK；向量降级时只按向量分数排序和截取，不把 Milvus 原始分数误当作重排相关度。
7. 构建上下文：补充文档名和切片正文，格式化为 `## 知识库检索结果` 文本。

关键边界：

- 检索增强失败返回空字符串，不中断主对话。
- 无关联知识库时直接返回空字符串。
- `rag.enhancement.enabled=false` 时整体跳过。
- Milvus 关闭时向量检索返回空结果，检索增强降级为空上下文。
- 外部重排失败不重试，直接向量降级；日志不得记录查询全文、候选正文、API Key 或原始响应。

详细规则见 [[查询改写与重排]]。

## 6. Milvus 可选化

### 6.1 实现机制

通过 Spring `@ConditionalOnProperty` 实现互斥装配：

- `rag.milvus.enabled=true` 时，`VectorStoreServiceImpl` 被装配，创建 `MilvusClient`，支持向量写入和检索。
- `rag.milvus.enabled=false` 时，`NoopVectorStoreServiceImpl` 被装配，所有操作降级。

### 6.2 Noop 降级行为

[[#NoopVectorStoreServiceImpl]] 的行为：

| 方法 | 行为 |
| --- | --- |
| `insert` / `insertBatch` | 只记录跳过日志，不写入任何向量 |
| `search` | 返回空列表 |
| `deleteByDocId` / `deleteByUserId` / `deleteByKbId` | 只记录跳过日志 |

### 6.3 影响范围

Milvus 关闭不影响文档上传、解析、切片和原文预览，但会影响知识库语义检索。关闭时系统仍可启动，便于没有本地 Milvus 的开发环境。排查"切片已落库但检索不到"时需先确认 `rag.milvus.enabled`。

## 7. Agent 接入方式

### 7.1 应用关联知识库

`AgentAppEntity` 有 `knowledgeBaseIds` 字段，记录应用关联的知识库 ID 列表。当会话关联应用时，`AgentTurnContextService.prepare` 会调用 `AgentKnowledgeContextService` 做三件事：

1. `loadApp`：加载应用配置，失败不阻断主流程，按未关联应用继续。
2. `buildKnowledgeDescription`：拼接知识库描述，注入到 `knowledge_search` 工具描述里，让 Agent 知道有哪些知识库可用。
3. `enhance`：调用 `KnowledgeEnhancer.enhance` 生成增强上下文，拼接到系统提示最前面。

### 7.2 上下文注入位置

系统提示拼接顺序（在 `AgentTurnContextService.prepare` 里）：文件上下文 → 知识库增强 → 工作区记忆 → 技能提示。知识库增强排在文件上下文之后、工作区记忆之前。

### 7.3 请求级知识库开关

聊天请求带有默认开启的 `knowledgeEnabled` 开关。同步接口从 `ChatRequest.knowledgeEnabled` 读取，流式接口从 `knowledgeEnabled` 查询参数读取，二者都写入 `UserContext`。

开关语义：

- `knowledgeEnabled=true` 且当前 Agent 应用至少关联一个知识库时，每条用户消息都会对关联知识库执行自动预检索。
- `knowledgeEnabled=false` 时，不自动注入知识库上下文，也不向模型暴露 `knowledge_search` 工具。
- 即使轮次被判断为 SOCIAL，只要用户明确开启知识库且应用已关联知识库，也不再由 `TurnPolicy` 自动跳过检索。
- 没有关联知识库时，开关开启也不能检索用户其他知识库。

## 8. 知识库 CRUD

### 8.1 创建与更新

`createKnowledgeBase` 创建知识库，记录 userId、name、description。`updateKnowledgeBase` 更新名称和描述，带用户权限校验版本会先 `requireOwnedKnowledgeBase`。

### 8.2 删除知识库

`deleteKnowledgeBase` 的清理范围：

1. 逐个调用 `deleteDocument` 清理知识库下所有文档（含切片、向量、本地文件、沙箱文件）。
2. 调用 `vectorStoreService.deleteByKbId` 清理 Milvus 残余向量。
3. 删除知识库记录。

### 8.3 文档删除

`deleteDocument` 的清理范围按顺序执行：

1. 删除 Milvus 向量（`deleteByDocId`，失败仅告警不阻断）。
2. 删除 MySQL 切片（`chunkRepository.deleteByDocumentId`）。
3. 删除本地原始文件（`Files.deleteIfExists`，失败仅告警）。
4. 删除沙箱内文件（`deleteFromSandbox`，用原始文件名拼路径，失败仅告警）。
5. 删除文档记录。

注意：Milvus、本地文件、沙箱文件任一删除失败都只记日志不回滚，删除后可能残留少量孤儿数据。

### 8.4 用户级批量清理

`deleteAllByUser` 用于账号注销等场景，清理范围：Milvus 全部向量、MySQL 全部切片、`Files.walk` 反向删除本地整个用户目录、删除文档和知识库记录。注意：此方法不清理沙箱文件，会在沙箱下残留孤儿文件。

## 9. 核心数据模型

| 数据 | 含义 | 主要实体 | 存储位置 |
| --- | --- | --- | --- |
| 知识库 | 用户创建的知识集合 | `KnowledgeBaseEntity` | MySQL |
| 文档 | 上传到某个知识库的原始文件及元数据 | `KnowledgeDocumentEntity` | MySQL |
| 切片 | 文档解析后的文本片段，带序号和原文偏移 | `KnowledgeChunkEntity` | MySQL |
| 向量 | 切片 Embedding 后的检索数据 | Milvus collection | Milvus（可选） |
| 用户沙箱绑定 | 用户当前沙箱信息 | `UserSandboxEntity` | MySQL |

文档实体的关键字段：`status`（PENDING/PROCESSING/READY/FAILED）、`errorMsg`（失败原因）、`sandboxSynced`（沙箱同步状态）、`splitMode`、`chunkSize`、`overlap`、`chunkCount`、`totalTokens`。详细字段表见 [[文档摄取与切片]] 的数据落库章节。

## 10. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| 上传后一直处理中 | `knowledge_document.status`、异步线程池、Embedding API | 前端每 3s 轮询刷新；长期不变说明卡在解析、Embedding、向量写入或沙箱同步 |
| 文档 `FAILED` | `errorMsg`、Tika 解析、Embedding Key | 异步异常会写入该字段；注意失败前可能已写入切片和向量 |
| 切片已保存但检索为空 | `rag.milvus.enabled`、Milvus 写入日志、Noop 日志 | Milvus 默认关闭，检索返回空结果 |
| 检索结果质量差 | 切片参数、Query Rewrite、retrieve topN、rerank provider、rerank topK/minScore | minScore 只作用于成功重排结果；topN 过低会漏召回，provider 失败会退化为向量排序 |
| 原文无法预览 | 本地 `storagePath`、`sandboxSynced`、沙箱文件路径 | `/file` 接口的 `ensureSandboxFile` 会在沙箱缺文件时从本地补传；优先确认本地 `storagePath` 是否存在 |
| `READY` 但预览不可用 | `sandboxSynced` 是否为 false | `READY` 不等于沙箱已同步，沙箱同步失败只置 false |
| 用户注销后沙箱占用未释放 | 沙箱 `/home/gem/knowledge/...` 目录 | `deleteAllByUser` 不清理沙箱文件，会残留孤儿文件 |
| 检索增强未触发 | 应用是否关联知识库、`knowledgeEnabled`、`rag.enhancement.enabled`、Milvus 是否开启 | 请求级开关关闭时不注入上下文也不暴露工具；Milvus 默认关闭会导致无召回 |
| 检索增强中断主对话 | `KnowledgeEnhancerImpl.enhance` 是否抛异常 | 失败应返回空字符串，不应中断主流程；检查是否有未捕获异常 |

## 11. 扩展建议

1. 异步处理当前没有事务包裹，如果要避免脏数据残留，可以在 `processDocumentAsync` 里用 `TransactionSynchronizationManager` 注册 afterCommit 回调，确保状态更新在数据写入之后。
2. `deleteAllByUser` 不清理沙箱文件，如果需要完整清理，可以增加沙箱文件批量删除逻辑，但要注意沙箱可能被其他会话共享。
3. Milvus 关闭时检索完全不可用，如果需要轻量检索，可以考虑用 MySQL 全文索引做降级召回，但精度和向量检索差距很大。
4. Query Rewrite 当前走 DeepSeek 回退；如需与主流程进一步隔离，可继续沿用 `RAG_REWRITE_*` 独立配置。
5. 新增检索入口时应复用 `KnowledgeService` 的统一多知识库搜索，不要再单独拼 Query Rewrite、向量召回或 Rerank，避免页面、工具和 Agent 三条链路行为漂移。

## 12. 术语速查

本节用于 Obsidian 悬浮预览。阅读正文时，把鼠标停在带链接的术语上，可以快速看到这里的释义。

### Embedding

把文本转换成向量的过程，是向量检索的前置步骤。系统把每个 chunk 发送给 Embedding 服务，拿到向量后才能写入 Milvus 做相似度检索。当前使用智谱的 `embedding-3` 模型，维度 2048。

### Milvus

当前项目使用的向量数据库。启用时保存 chunk 向量并支持相似度召回；关闭时由 Noop 实现降级，写入和删除只记日志，检索返回空结果。默认关闭，需要 RAG 检索时必须启用。

### NoopVectorStoreServiceImpl

`NoopVectorStoreServiceImpl` 是 Milvus 关闭时的向量存储空实现，通过 `@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "false")` 装配。它让知识库上传、删除等上层流程在不需要向量检索时仍然可以完成，不创建 `MilvusClient`。

### KnowledgeDocumentProcessor

`KnowledgeDocumentProcessor` 是异步文档处理链路的独立 Bean，通过 `@Async("knowledgeTaskExecutor")` 在 `AsyncConfig` 定义的有界线程池上执行。它负责解析、切片、Embedding、向量写入、沙箱同步和状态更新。

### KnowledgeEnhancerImpl

`KnowledgeEnhancerImpl` 是 Agent 检索增强服务实现，当前主要负责把 `KnowledgeService` 返回的结构化检索结果格式化为可注入提示词的上下文。检索策略本身由统一搜索入口承载，失败返回空字符串，不中断主对话。

### AgentKnowledgeContextService

`AgentKnowledgeContextService` 是 Agent 知识库上下文服务，负责加载应用配置、构造知识库工具描述和生成增强上下文。当应用未关联知识库或请求级 `knowledgeEnabled=false` 时，相关方法返回空或跳过。

### QueryRewriteService

`QueryRewriteService` 把用户原始问题改写或扩展成多个查询，提高召回覆盖面。当前默认走 DeepSeek 配置回退，最大查询数由 `rag.enhancement.rewrite.max-queries` 控制。

### RerankService

`RerankService` 对向量召回结果二次排序，让最终返回的切片更贴近原始问题。当前默认 provider 是 DeepSeek，使用独立 `RAG_RERANK_MODEL=deepseek-v4-flash`；也可切换到 BGE。返回值是带来源标记的 `RerankResult`，用于区分成功重排分数和向量降级分数。

### RerankResultFilter

`RerankResultFilter` 是无状态结果过滤器。成功重排时先按 `min-score` 丢弃低相关候选，再截取 topK；向量降级时只按原始向量分数排序和截取，不使用重排阈值，避免把不同分数体系混在一起。

### RawChunk

`RawChunk` 是向量检索的原始候选切片，包含 docId、chunkIndex、content 和 score。content 在检索阶段为 null，后续由 `enrichWithDocName` 从 MySQL 补充。

### RankedChunk

`RankedChunk` 是 Rerank 后的排序切片，在 `RawChunk` 基础上补充了文档名。最终上下文文本由它构建，格式为 `[序号] 来源：文档名（片段序号，相关度分数）` 加切片正文。

### UserWorkspaceStorageService

`UserWorkspaceStorageService` 管理用户级文件存储路径，替代了旧版 `rag.storage.path` 全局路径。知识库原始文件按用户级路径存储，`KnowledgeFileMigrationService` 负责把历史旧路径文件迁移到当前用户级路径。

## 13. 相关页面

[[文档摄取与切片]] · [[查询改写与重排]] · [[知识库文档上传与检索]] · [[Skill 与知识检索工具]] · [[Agent 编排模块]]
