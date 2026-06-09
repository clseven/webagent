# RAG 知识库实现文档

## 一、整体架构

```
用户上传文档 → 存储到本地 → Tika 解析 → 文本切片 → 智谱 Embedding → 存储(MySQL + Milvus)
                                                                                    ↓
Agent 对话 → KnowledgeSearchTool → 问题向量化 → Milvus 检索 → 返回相关片段 → 注入上下文
```

---

## 二、技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| 文档解析 | Apache Tika 2.9.1 | 支持 700+ 格式（PDF/DOCX/PPT/Excel/图片等） |
| 文本切片 | 自实现（智能/自定义两种模式） | 智能按段落，自定义按字符数 |
| Embedding | 智谱 embedding-3 | 1024 维向量 |
| 向量存储 | Milvus 2.4 | Docker 部署 |
| 元数据存储 | MySQL | 复用现有数据库 |
| 文件存储 | 本地文件系统 | `./uploads/knowledge/{sessionId}/` |
| Token 计算 | 智谱 API 返回 | 不引入 tiktoken，用 API 返回的真实 token 数 |

---

## 三、参数配置（application.yml）

```yaml
rag:
  embedding:
    api-url: https://open.bigmodel.cn/api/paas/v4/embeddings  # 智谱 API 地址
    api-key: xxx                                                # API Key（建议用环境变量）
    model: embedding-3                                          # 模型名称
    dimension: 1024                                             # 向量维度
    batch-size: 25                                              # 每批最多处理多少条文本
  milvus:
    host: localhost
    port: 19530
    collection: knowledge_chunks    # Milvus 集合名
  storage:
    path: ./uploads/knowledge       # 本地文件存储路径
```

---

## 四、切片逻辑

### 两种切片模式

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| **智能切片**（默认） | 按段落（双换行 `\n\n`）自然切分，长段落（>2000 字符）按句子二次切分 | 大多数场景，无需配置 |
| **自定义切片** | 按指定字符数切分，支持重叠 | 需要精确控制切片大小时 |

### 智能切片流程

```
原始文本
    │
    ▼
按双换行 "\n\n" 切分为段落
    │
    ├─ 段落长度 <= 2000 字符 → 直接作为一个 chunk
    ├─ 段落长度 > 2000 字符 → 按句子二次切分
    │
    ▼
输出：List<String> chunks
```

特点：
- 尽量保留段落完整性
- 不控制切片大小，以语义为优先
- 用户无需配置任何参数

### 自定义切片流程

```
原始文本
    │
    ▼
第一层：按双换行 "\n\n" 切分为段落
    │
    ├─ 当前 chunk + 段落 <= chunkSize → 合并
    ├─ 当前 chunk + 段落 > chunkSize → 保存当前 chunk，开始新 chunk
    │
    ▼
第二层：单个段落超过 chunkSize → 按句子切分
    │
    ▼
输出：List<String> chunks（相邻 chunk 有 overlap 重叠）
```

### 自定义模式参数

| 参数 | 推荐值 | 对应 Token | 说明 |
|------|--------|-----------|------|
| chunkSize | 750 字符 | ≈ 500 tokens | 每个切片的目标大小。越大上下文越完整，检索精度下降 |
| overlap | 75 字符 | ≈ 50 tokens | 相邻切片重叠部分。防止切片边界处信息丢失，占比 10% |

换算关系：中文场景 1 字符 ≈ 1.5 tokens，所以 token 数 × 1.5 = 字符数

注意：切片大小用**字符数**控制，不是 token 数。Token 数由 Embedding API 返回后记录。

### 切片示例（自定义模式）

假设 `chunkSize=750, overlap=75`：

```
原文（3个段落）：
┌─────────────────────────────────┐
│ 段落A (300 字符)                │
│                                 │
│ 段落B (350 字符)                │
│                                 │
│ 段落C (400 字符)                │
└─────────────────────────────────┘

切片过程：
- 段落A + 段落B = 650 字符 < 750 → 合并为 chunk1
- 段落B(overlap 75字符) + 段落C = 475 字符 < 750 → 合并为 chunk2

结果：
chunk1: [段落A + 段落B]
chunk2: [段落B尾部75字符 + 段落C]
```

---

## 五、Embedding 流程

```
文本列表 ["切片1", "切片2", ...]
    │
    ▼
分批处理（每批 25 条）
    │
    ▼
调用智谱 API: POST /embeddings
{
  "model": "embedding-3",
  "input": ["切片1", "切片2", ...]
}
    │
    ▼
返回结果：
{
  "data": [
    { "embedding": [0.123, -0.456, ...] },
    { "embedding": [0.789, 0.012, ...] }
  ],
  "usage": {
    "prompt_tokens": 1234    ← 真实 token 数，用于记录
  }
}
```

### Token 数获取

- 从 API 响应的 `usage.prompt_tokens` 获取真实 token 数
- 总 token 数平均分摊到每个切片，记录到 MySQL
- 不引入 tiktoken，避免额外依赖

---

## 六、存储设计

### MySQL 表

**knowledge_document（文档表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| session_id | VARCHAR(36) | 会话 ID |
| file_name | VARCHAR(255) | 原始文件名 |
| file_type | VARCHAR(20) | 文件扩展名 |
| file_size | BIGINT | 文件大小（字节） |
| storage_path | VARCHAR(500) | 本地存储路径 |
| chunk_count | INT | 切片数量 |
| status | VARCHAR(20) | PENDING/PROCESSING/READY/FAILED |
| error_msg | TEXT | 失败原因 |
| split_mode | VARCHAR(20) | 切片模式：smart/custom |
| chunk_size | INT | 自定义模式下的切片字符数 |
| overlap | INT | 自定义模式下的重叠字符数 |
| total_tokens | INT | API 返回的真实 token 总数 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

**knowledge_chunk（切片表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| document_id | BIGINT FK | 关联文档 |
| session_id | VARCHAR(36) | 会话 ID（冗余） |
| chunk_index | INT | 切片序号 |
| content | TEXT | 切片文本内容 |
| token_count | INT | 该切片的 token 数（API 总 token / 切片数） |
| created_at | DATETIME | 创建时间 |

### Milvus 集合

**knowledge_chunks**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Int64 PK | 自增主键 |
| doc_id | Int64 | 文档 ID（关联 MySQL） |
| session_id | VarChar(36) | 会话 ID |
| chunk_index | Int64 | 切片序号 |
| embedding | FloatVector(1024) | 向量数据 |

索引：HNSW，余弦相似度（COSINE）

### 本地文件

```
./uploads/knowledge/
└── {sessionId}/
    └── doc_{docId}.{ext}    # 例如 doc_1.pdf, doc_2.docx
```

---

## 七、API 接口

### 1. 上传文档

```
POST /api/rag/documents/upload
Content-Type: multipart/form-data

参数：
  sessionId: 会话 ID（必填）
  file: 文件（必填）
  splitMode: 切片模式，smart 或 custom（默认 smart）
  chunkSize: 自定义模式下的切片字符数（默认 1500）
  overlap: 自定义模式下的重叠字符数（默认 200）

响应：
{
  "code": 200,
  "data": {
    "id": 1,
    "sessionId": "xxx",
    "fileName": "报告.pdf",
    "fileType": "pdf",
    "fileSize": 123456,
    "chunkCount": 0,
    "status": "PENDING",
    "splitMode": "smart",
    "createdAt": "2026-05-31T10:00:00"
  }
}
```

注意：上传后异步处理，状态从 PENDING → PROCESSING → READY（或 FAILED）。

### 2. 列出文档

```
GET /api/rag/documents/{sessionId}

响应：
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "fileName": "报告.pdf",
      "status": "READY",
      "chunkCount": 12,
      "totalTokens": 3456,
      "splitMode": "smart",
      ...
    }
  ]
}
```

### 3. 删除文档

```
DELETE /api/rag/document/{docId}

同时删除：MySQL 文档记录 + 切片记录 + Milvus 向量 + 本地文件
```

### 4. 向量检索

```
POST /api/rag/search
Content-Type: application/json

{
  "sessionId": "xxx",
  "query": "项目的技术方案是什么",
  "topK": 5
}

响应：
{
  "code": 200,
  "data": [
    {
      "docId": 1,
      "docName": "报告.pdf",
      "chunkIndex": 3,
      "content": "本项目采用微服务架构...",
      "score": 0.89
    }
  ]
}
```

---

## 八、Agent 工具

### knowledge_search

Agent 在对话中可以自主调用此工具检索知识库。

**触发场景**：用户的问题可能需要参考已上传的文档内容时。

**参数**：
```json
{
  "query": "搜索内容",
  "top_k": 5    // 可选，默认5
}
```

**返回**：
```
以下是从知识库中检索到的相关内容：

[1] 来源: 报告.pdf (片段3, 相似度: 0.89)
本项目采用微服务架构，基于Spring Cloud...

[2] 来源: 方案.docx (片段1, 相似度: 0.82)
技术选型包括...
```

---

## 九、前端功能

### 知识库页面

- **会话选择器**：选择当前会话，知识库按会话隔离
- **统计卡片**：文档总数、切片总数、已就绪数
- **拖拽上传**：支持拖拽或点击选择文件
- **切片设置**：
  - 默认显示「智能切片」
  - 可切换到「自定义切片」
  - 点击「切片设置」展开高级参数
  - 参数旁显示推荐值
- **检索测试**：输入问题测试知识库检索效果，显示相似度和来源
- **文档列表**：显示所有文档、状态标识、token 数、支持删除

---

## 十、处理状态流转

```
上传文件
    │
    ▼
PENDING（已保存元数据，等待处理）
    │
    ▼
PROCESSING（正在解析/切片/向量化）
    │
    ├─ 成功 → READY（就绪，可检索）
    └─ 失败 → FAILED（记录错误信息）
```

前端页面会显示状态，READY 的文档才可被检索。

---

## 十一、已知限制和待优化

| 项目 | 说明 | 建议 |
|------|------|------|
| 切片精度 | 用字符数控制，不是 token 数 | 中文场景足够，如需精确可引入 tiktoken |
| 检索方式 | 纯向量检索（余弦相似度） | 可加入关键词检索（混合检索） |
| 向量存储 | Milvus 单集合，按 session_id 过滤 | 数据量大时考虑分区 |
| 文档解析 | Tika 统一处理，对复杂 PDF 效果一般 | 可针对 PDF 用 Marker 增强 |
| 文件格式检测 | 用文件扩展名判断 | 可用 Tika 的 MIME 检测 |
| 异步处理 | 无重试机制 | 失败后可支持手动重试 |

---

## 十二、Docker Compose（Milvus）

```yaml
version: '3.5'

services:
  etcd:
    image: quay.io/coreos/etcd:v3.5.16
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
    command: etcd -advertise-client-urls=http://127.0.0.1:2379 -listen-client-urls http://0.0.0.0:2379

  minio:
    image: minio/minio:latest
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    command: minio server /minio_data

  milvus:
    image: milvusdb/milvus:v2.4-latest
    command: ["milvus", "run", "standalone"]
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
    ports:
      - "19530:19530"
      - "9091:9091"
    depends_on:
      - etcd
      - minio
```

---

## 十三、文件清单

### 新增文件（20个）

| 路径 | 说明 |
|------|------|
| `config/RagConfigProperties.java` | RAG 配置属性 |
| `config/MilvusConfig.java` | Milvus 连接 + 集合初始化 |
| `model/entity/KnowledgeDocumentEntity.java` | 文档实体 |
| `model/entity/KnowledgeChunkEntity.java` | 切片实体 |
| `repository/KnowledgeDocumentRepository.java` | 文档 Repository |
| `repository/KnowledgeChunkRepository.java` | 切片 Repository |
| `service/DocumentParserService.java` | 文档解析接口 |
| `service/impl/DocumentParserServiceImpl.java` | Tika 实现 |
| `service/TextSplitterService.java` | 切片接口（智能/自定义） |
| `service/impl/TextSplitterServiceImpl.java` | 切片实现 |
| `service/EmbeddingService.java` | Embedding 接口（返回 token 数） |
| `service/impl/EmbeddingServiceImpl.java` | 智谱 API 实现 |
| `service/VectorStoreService.java` | 向量存储接口 |
| `service/impl/VectorStoreServiceImpl.java` | Milvus 实现 |
| `service/KnowledgeService.java` | 知识库编排接口 |
| `service/impl/KnowledgeServiceImpl.java` | 核心编排实现 |
| `controller/RagController.java` | REST API |
| `model/request/SearchRequest.java` | 检索请求 DTO |
| `model/response/DocumentResponse.java` | 文档响应 DTO |
| `service/tool/KnowledgeSearchTool.java` | Agent 工具 |

### 修改文件（5个）

| 路径 | 改动 |
|------|------|
| `pom.xml` | 新增 Tika + Milvus SDK 依赖 |
| `application.yml` | 新增 `rag.*` 配置段 |
| `js/api.js` | 新增知识库 API 方法（支持切片参数） |
| `js/pages/Knowledge.js` | 知识库页面完整实现（含切片设置 UI） |
| `css/style.css` | 新增知识库样式 |
