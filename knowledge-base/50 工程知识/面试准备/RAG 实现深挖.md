---
project: webagent-clean
type: interview-guide
status: verified
area:
  - interview
  - rag
tags:
  - project/webagent-clean
  - interview
  - rag
source:
  - src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/KnowledgeDocumentProcessor.java
  - src/main/java/com/example/sandbox/web/service/impl/TextSplitterServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/EmbeddingServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/VectorStoreServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/enhance/impl/KnowledgeEnhancerImpl.java
  - src/main/java/com/example/sandbox/web/service/enhance/impl/QueryRewriteServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/enhance/impl/BgeRerankServiceImpl.java
  - src/main/java/com/example/sandbox/web/config/MilvusConfig.java
updated: 2026-07-10
---

# RAG 实现深挖

> 用途：RAG 是这个项目独立的一大块，可深挖点非常多。这份从"写入链路"和"检索链路"两条主线讲透，每个环节带上真实实现、关键参数、以及能被面试深挖的坑。
> 边界/降级的口语化应答见 [[边界与异常处理问答]] 第 5 节；这份是"实现原理 + 深挖"。

---

## 一、两条主线（先建立框架）

> RAG 就两条链路：**写入**（离线，文档上传时）和**检索**（在线，用户提问时）。写入把文档变成向量存起来，检索把问题变成向量去找最相似的片段。

**写入链路**：
```
上传 → 查重 → 存元数据(MySQL, PENDING) → 落本地文件 → 异步处理:
  解析(Tika) → 切片(带位置) → 批量 embedding(智谱) → 切片写 MySQL → 向量写 Milvus → 同步沙箱 → READY
```

**检索链路**：
```
用户问题 → Query Rewrite(多角度) → 并行向量召回(每个query×每个kb) → 融合去重 → BGE Rerank → 补正文 → 拼上下文注入 prompt
```

---

## 二、写入链路深挖

### 1. 入口与查重（KnowledgeServiceImpl.upload）

上传先查重（同 kb + user + 文件名忽略大小写，抛 DuplicateFileException），存元数据到 MySQL（状态 PENDING），落本地文件，再调 `processDocumentAsync`。

### 2. 异步处理（KnowledgeDocumentProcessor）—— 高频深挖点

处理链路**被单独拆成一个 Bean**，方法带 `@Async("knowledgeTaskExecutor")`。步骤：状态置 PROCESSING → Tika 解析 → 切片 → embedBatch 向量化 → 切片写 MySQL → 向量写 Milvus → 同步文件到沙箱（预览用，失败不阻塞）→ 状态置 READY。

**为什么拆成独立 Bean（面试必答）：**
> Spring 的 @Async 靠 AOP 代理实现。如果异步方法和调用方在**同一个类**里，内部自调用不走代理，@Async 直接失效——整个解析+向量化会在上传请求线程里**同步执行**，把接口阻塞几十秒。所以我把处理链路拆到独立的 Bean，从外部注入调用，确保走代理、真正异步。这是 Spring AOP 自调用失效的经典问题。

**其它可深挖的坑：**
- **两库不一致**：切片先全写 MySQL，再写 Milvus，方法上没有 @Transactional。如果 Milvus 写入抛异常，MySQL 里的 chunk 不回滚 → 两库数据不一致，残留脏数据。（这也是已知风险里"异步无事务"的具体化。）
- **Tika 解析上限**：用 Apache Tika 的 `parseToString`，它默认有 WriteLimit（约 100k 字符），超大文档可能被截断或抛 WriteLimitReachedException。
- avgTokensPerChunk 存的是平均值（总 token / chunk 数），不是每片真实值。

### 3. 切片算法（TextSplitterServiceImpl）—— 三层降级，能讲功底

**splitSmart（智能切片）**：按双换行 `\n\n+` 切段落 → 段落长度 ≤ 1500 直接保留（保证语义完整）→ 超过才二次切。这是核心思想：**优先按自然段落边界切，尽量不破坏语义**。

**splitCustom（自定义切片）三层降级**：
1. 按段落累积拼装，候选超 chunkSize 就落一片，用 overlap 保留重叠（`getOverlapText` 取尾部 N 字符）。
2. 单段落自身超长 → `splitLongText` 按中英文句末标点（。！？.!?）断句。
3. 单句仍超长 → `splitHard` 固定字符硬截断，滚动窗口 `step = chunkSize - overlap` 兜底。

> 面试说法：切片不是简单按固定长度切，那样会把句子、段落切碎，破坏语义。我做了三层降级：优先按段落，段落太长按句子，句子还太长才硬截断。同时相邻片保留 overlap 重叠，避免答案正好落在切割线上被切断。

**带位置版**（splitSmartWithPosition）：复用切片结果，用 `indexOf` 反查每片在原文的 offset，用于文件预览时切片和原文联动。

**默认参数**：智能模式硬编码 SMART_CHUNK_SIZE=750 字符、overlap=75（注释：500 token × 1.5、50 token × 1.5 的字符估算）；段落阈值 1500。

**深挖坑：**
- overlap 按**字符数**从上片末尾截，不保证在词/句边界，中文可能截半个词。
- `attachPositions` 用 indexOf，若同一片文本在原文重复出现、或首尾被 trim 改过，offset 可能定位错。
- 语义完整只靠"段落优先"，对纯 PDF 抽出的连续文本、表格效果差。
- 配置里另有 `rag.chunk.size=500/overlap=50`，但 smart 模式实际用类内硬编码 750/75，**这套配置形同虚设**——被问到要诚实。

### 4. Embedding（EmbeddingServiceImpl）

调**智谱 BigModel** 的 `embedding-3` 模型。`embedBatch` 按 batchSize=25 分批循环调用，累加向量和 token（读 usage.prompt_tokens 拿真实 token）。用 WebClient，`.block()` 同步拿结果（因为本身在 @Async 线程里，无所谓）。

**深挖坑：**
- **维度冲突（经典上线事故）**：配置默认 dimension=1024，但 yml 写的是 2048，Milvus 建表用配置维度。一旦建表维度和 API 实际返回维度对不上，insert 直接报维度不匹配。
- embedBatch 任一批异常直接抛，**无重试、无单批降级**，一批失败整个文档处理失败。
- WebClient 每次调用都 new，没复用连接池。

### 5. 向量存储（VectorStoreServiceImpl + Milvus）

**Milvus 建表**（MilvusConfig，启动时 `@EventListener(ApplicationReadyEvent)`）：字段 id(autoID) / kb_id / doc_id / user_id / chunk_index / vector。索引 **HNSW + COSINE**，参数 M=16、efConstruction=256，建完 loadCollection 加载到内存。初始化失败只 log.warn 不抛，Milvus 没起来也不影响启动。

**多租户隔离靠标量过滤**：search 时 `expr = "user_id == X && kb_id == Y"`，在向量检索前先按用户和知识库过滤，实现隔离。

> 面试可讲：HNSW 是图索引，查询快、召回率高，适合在线检索；COSINE 因为文本 embedding 比的是方向相似度不是绝对距离。多租户隔离我用 Milvus 的标量字段过滤（user_id/kb_id），而不是每个用户建一个 collection，避免 collection 爆炸。

### 6. Milvus 默认关闭的降级（@ConditionalOnProperty 双 Bean）—— 能讲设计

用 `@ConditionalOnProperty` 让两个实现类**二选一**注入：
- 开启 → `VectorStoreServiceImpl`（真连 Milvus）
- 关闭 → `NoopVectorStoreServiceImpl`（insert/delete 只打日志，search 固定返回空，**不创建 MilvusClient**）

> 面试说法：我用条件化 Bean 做降级——Milvus 关闭时注入一个 Noop 实现，所有向量操作变成空操作、检索返回空。好处是**上层代码完全不用改**，也不用到处写 if。任意配置下都恰好有一个 VectorStoreService Bean 可注入。本地开发默认走 Noop，快速启动；真要 RAG 显式开 Milvus。

**深挖坑**：Milvus 关闭时，上传还是照常解析、切片、**embedding（照样烧智谱的 token）**，只是不写向量。可优化成 Milvus 关时跳过向量化。

---

## 三、检索链路深挖（KnowledgeEnhancerImpl.enhance）

六步编排：

### 1. Query Rewrite（QueryRewriteServiceImpl）—— 亮点

调 LLM（默认 DeepSeek）把用户问题改写成**多角度 query**，开 **JSON mode**（response_format=json_object）强制结构化输出。改写策略：
- 忠实版（指代消解、省略补全、口语规范化，必须第一条）
- 同义版（换说法提高召回）
- 子问题拆解（复杂问题拆成多个）

三级鲁棒解析：标准 JSON 对象 → 正则抠裸数组 → 按行/逗号切。历史只取最近 6 条。

> 面试说法：用户问题经常口语化、指代不清（"刚才那个怎么弄"）。Query Rewrite 结合历史把它改写成适合检索的 query，而且生成多个角度——忠实改写保证不跑偏，同义改写提升召回，子问题拆解应对复杂问题。它不负责回答，只负责生成更好的检索 query。

### 2. 并行向量召回（parallelSearch）

先 embedBatch 批量向量化所有改写 query，再对（每个 query × 每个 kbId）用 CompletableFuture 并行调 Milvus，各取 topN。

**深挖坑**：用默认 ForkJoinPool.commonPool（supplyAsync 无自定义 executor），高并发下与其它任务争用；单个 query/kb 异常返回空 list 不影响其它（fail-safe）。

### 3. 融合去重（deduplicate）

多个 query 召回的结果按 `docId:chunkIndex` 分组，保留最高分，再按 minScore（0.5）过滤。

### 4. Rerank（BgeRerankServiceImpl）—— 降级设计

调本地部署的 **BAAI/bge-reranker-v2-m3**（http://127.0.0.1:8002/rerank），POST {query, passages, topK}，返回按相关度重排的结果。

> 面试说法：向量召回看的是语义相似度，topK 里可能有噪声（相似但不切题）。Rerank 用一个专门的重排模型，拿原始问题和每个候选片段做更精细的相关性打分，重新排序，让最终注入的上下文更准。这是"召回优先量、重排优先质"的两阶段设计。

**降级（重点）**：Rerank 未启用/返回空/调用异常 → fallbackSort 直接按**向量原始分数**排序取 topK。服务挂了也不影响主流程。

**深挖坑**：rerank 用的 query 是**原始 userMessage**，而召回用的是改写后的 query——两者不一致，可以讨论。

### 5. 补正文（enrichWithDocName）—— 重要细节

**Milvus 只存 docId/chunkIndex，不存 chunk 正文**。所以重排后要拿 docId 回 MySQL 用 `findByDocumentIdIn` 查出真正的 chunk 文本。

> 面试深挖：为什么向量库不存原文？向量库存原文会膨胀、也不是它的强项。我让 Milvus 只存向量和定位信息（docId/chunkIndex），正文放 MySQL，检索到之后回 MySQL 取。代价是多一次 DB 查询，收益是两边各司其职。风险是两库要同步，不一致时 content 为 null 的片段会被丢弃。

### 6. 拼上下文（buildContext）

拼成带来源标注的 markdown（`[i] 来源：xxx（片段 n，相关度 x.xx）`）注入 prompt，让模型能溯源、也约束它依据资料回答。

### 全链路降级兜底

整个 enhance 包在 try/catch 里，任何环节失败最终返回空串——**检索增强失败绝不影响主对话**。这是核心设计原则：RAG 是增强项，不是主链路的强依赖。

---

## 四、面试深挖点速查（按"能拉开差距"排序）

| 深挖点 | 一句话 |
| --- | --- |
| @Async 自调用失效 | 处理链路拆独立 Bean 才能真异步，否则阻塞上传请求 |
| 切片三层降级 | 段落→句子→硬截断，overlap 防切断答案 |
| @ConditionalOnProperty 双 Bean 降级 | Milvus 关闭注入 Noop，上层零改动 |
| Query Rewrite 多角度 + JSON mode | 忠实/同义/子问题，强制结构化输出 |
| Rerank 两阶段 + 降级 | 召回优先量、重排优先质，挂了退回向量分 |
| Milvus 只存向量、正文回 MySQL | 各司其职，代价是两库同步 |
| 多租户靠标量过滤 | user_id/kb_id 过滤，不给每人建 collection |
| HNSW + COSINE | 图索引快、方向相似度 |
| 两库不一致（无事务） | Milvus 写失败 MySQL 不回滚，脏数据 |
| 维度冲突 | 配置维度和 API 返回维度不一致 → insert 报错 |
| 检索失败全链路兜底空串 | RAG 是增强项不是强依赖 |

---

## 相关页面

[[边界与异常处理问答]] · [[八股结合项目]] · [[项目亮点与模块深挖]] · [[技术选型与场景设计问答]] · [[当前已知风险]]
