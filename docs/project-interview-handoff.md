# Sandbox Agent 项目面试与交接速览

> 生成时间：2026-06-08  
> 适用场景：面试项目介绍、技术复盘、下一个 AI 会话快速接手。

## 1. 项目一句话

Sandbox Agent 是一个基于 Spring Boot 3 + Vue 3 的 Web Agent 系统。它把大模型、ReAct 工具调用、隔离沙箱、Skills 能力扩展、RAG 知识库和 SSE 流式输出整合在一起，让用户可以创建带知识库和工具能力的自定义 Agent 应用，并在隔离环境中执行命令、读写文件、操作浏览器和检索私有文档。

## 2. 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.2.5, Spring MVC, WebFlux, Validation |
| 前端 | Vue 3, Vue Router 4, 原生静态资源 |
| 数据库 | MySQL, Spring Data JPA |
| 向量数据库 | Milvus Java SDK 2.4.1 |
| 文档解析 | Apache Tika 2.9.1, 沙箱内 Python 解析工具 |
| 沙箱 | OpenSandbox SDK, AIO Sandbox Client |
| LLM | Planner: 智谱 GLM；Executor: DeepSeek |
| Embedding | 智谱 embedding-3 |
| 构建 | Maven |

## 3. 核心业务能力

1. 用户认证与会话隔离
   - 用户注册、登录、鉴权通过 `AuthFilter` 和 `UserContext` 串联。
   - 每个用户可拥有独立沙箱记录，业务请求按用户、会话校验权限。

2. Agent 应用管理
   - 用户可以创建 Agent App，并给应用关联知识库和 Skill。
   - 会话可以绑定某个 App，后续聊天继承应用配置。

3. 双模型 Agent 编排
   - `PlanAgent` 使用规划模型生成任务计划。
   - `ReactAgent` 使用执行模型按 ReAct 循环执行，必要时调用工具。
   - `AgentServiceImpl` 是主编排入口，负责会话、历史、工具过滤、知识增强、token 记录和消息落库。

4. 沙箱工具调用
   - 支持命令执行、文件读写、目录列举、文件搜索替换、浏览器操作、截图、下载、文档转换等。
   - 工具实现统一放在 `src/main/java/com/example/sandbox/web/service/tool/`，都实现 `Tool` 接口。

5. Skills 能力系统
   - Skill 文件位于默认目录 `.claude/skills`。
   - Agent 通过 `skill_list`、`skill_activate`、`skill_reference` 渐进式加载技能，减少 prompt 一次性膨胀。

6. RAG 知识库
   - 支持知识库 CRUD、文档上传、文档解析、切片、embedding、Milvus 检索和 Agent 内部知识检索。
   - 文档元数据、chunk 内容存 MySQL，向量存 Milvus。

7. SSE 流式聊天
   - `/api/sessions/{id}/chat/stream` 支持流式返回 plan、thinking、reasoning token、tool call、observation、answer、done、heartbeat 等事件。
   - 前端使用 `fetch + ReadableStream` 消费 SSE，支持 Authorization header。

8. Token 用量统计
   - `TokenUsageService` 记录 planner/executor 的 prompt、completion、cache hit、total token。
   - 前端有 token 统计页面。

## 4. 项目目录地图

```text
src/main/java/com/example/sandbox/
  SandboxApplication.java              # Spring Boot 启动类，开启 async/scheduling
  aio/                                 # AIO 沙箱客户端
  agent/                               # 早期/演示 Agent 代码
  web/
    controller/                        # REST API
    service/                           # 服务接口
    service/impl/                      # 核心业务实现
    service/tool/                      # Agent 可调用工具
    service/enhance/                   # RAG 检索增强：query rewrite / rerank / enhancer
    model/entity/                      # JPA 实体与领域模型
    model/request/                     # 请求 DTO
    model/response/                    # 响应 DTO
    model/llm/                         # LLM 请求、响应、工具调用结构
    model/sse/                         # SSE 事件结构
    repository/                        # Spring Data JPA Repository
    config/                            # 配置、鉴权、异常处理、Milvus

src/main/resources/
  application.yml                      # 应用、数据库、LLM、RAG、沙箱配置
  static/                              # Vue 3 前端静态文件
  tools/file_parser.py                 # 沙箱内文档解析工具

docs/                                  # 设计文档、路线图、复盘文档
uploads/                               # 本地上传文件和知识库文件
```

## 5. 关键后端模块

| 模块 | 代表文件 | 作用 |
| --- | --- | --- |
| 启动与配置 | `SandboxApplication`, `application.yml` | 启动应用，加载 agent/rag 配置 |
| 鉴权 | `AuthController`, `AuthFilter`, `UserService` | 注册、登录、token 验证、用户上下文 |
| 会话 | `AgentController`, `ConversationServiceImpl` | 创建会话、历史消息、启用 Skill |
| Agent 编排 | `AgentServiceImpl` | 主聊天流程，同步与流式入口 |
| 规划 | `PlanAgent` | 调 planner LLM 生成执行计划 |
| 执行 | `ReactAgent` | ReAct 循环、工具调用、历史压缩、SSE |
| 工具系统 | `Tool`, `service/tool/*` | 为 LLM 暴露沙箱、文件、浏览器、RAG、Skill 工具 |
| 沙箱 | `SandboxServiceImpl`, `OpensandboxClient`, `AioSandboxStore` | 创建、复用、初始化、连接沙箱 |
| 知识库 | `RagController`, `KnowledgeServiceImpl` | 知识库和文档生命周期 |
| 向量检索 | `EmbeddingServiceImpl`, `VectorStoreServiceImpl` | embedding 调用、Milvus insert/search/delete |
| 检索增强 | `KnowledgeEnhancerImpl`, `QueryRewriteServiceImpl`, `OllamaRerankServiceImpl` | 多 query 改写、融合、重排、上下文注入 |
| 统计 | `TokenStatsController`, `TokenUsageService` | token 消耗统计 |

## 6. 核心执行链路

### 普通聊天

```text
前端 Chat 页面
  -> POST /api/sessions/{id}/chat
  -> AgentController
  -> AgentServiceImpl.chat()
  -> 校验会话归属
  -> 确保沙箱存在
  -> 加载最近 20 条历史
  -> 保存用户消息
  -> 构建 system prompt
  -> 加载 Agent App 配置
  -> 按沙箱类型过滤工具
  -> 注入知识库描述和 Skill 列表
  -> PlanAgent 生成计划
  -> KnowledgeEnhancer 可选注入增强 RAG 上下文
  -> ReactAgent 执行工具循环
  -> 保存助手消息与 reasoning
  -> 记录 planner/executor token
```

### RAG 文档入库

```text
上传文档
  -> POST /api/rag/bases/{kbId}/documents/upload
  -> 保存 KnowledgeDocument 元数据
  -> 保存文件到 uploads/knowledge
  -> 异步处理
  -> Apache Tika 解析文本
  -> TextSplitter 智能/自定义切片
  -> EmbeddingService 批量向量化
  -> MySQL 保存 chunk 内容和 offset
  -> Milvus 保存向量
  -> 同步文件到沙箱 /home/gem/knowledge 供预览
  -> 文档状态置为 READY 或 FAILED
```

### RAG 检索

```text
用户问题
  -> QueryRewriteService 可改写多路 query
  -> EmbeddingService 生成 query vector
  -> VectorStoreService 按 user_id/kb_id 在 Milvus 检索
  -> MySQL 补齐 chunk 文本和文档名
  -> RerankService 重排
  -> KnowledgeEnhancer 生成上下文
  -> 注入 Agent system prompt
```

### 流式聊天

```text
前端 fetch ReadableStream
  -> GET /api/sessions/{id}/chat/stream?message=...
  -> AgentServiceImpl.chatStream()
  -> 发送 plan 事件
  -> ReactAgent.runStream()
  -> 发送 thinking_start / token / reasoning_token
  -> 工具调用时发送 tool_call / heartbeat / observation
  -> 完成时发送 answer / done
```

## 7. 主要 API

| 领域 | 路径 |
| --- | --- |
| 认证 | `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me` |
| 会话 | `GET/POST /api/sessions`, `GET/DELETE /api/sessions/{id}` |
| 聊天 | `POST /api/sessions/{id}/chat`, `GET /api/sessions/{id}/chat/stream` |
| 会话 Skill | `GET /api/sessions/{id}/skills`, `POST /api/sessions/{id}/skills/{skillId}/enable|disable` |
| 沙箱 | `POST /api/sessions/{id}/execute`, `POST /api/sessions/{id}/files/read|write`, `GET /api/sessions/{id}/aio/endpoint` |
| 文件上传 | `POST /api/files/upload`, `GET /api/files/download/{sessionId}/{filename}` |
| Agent App | `GET/POST /api/apps`, `GET/PUT/DELETE /api/apps/{appId}` |
| App 配置 | `PUT /api/apps/{appId}/knowledge-bases`, `PUT /api/apps/{appId}/skills` |
| Skill 管理 | `GET /api/skills`, `GET /api/skills/{id}`, `POST /api/skills/set-root` |
| 知识库 | `GET/POST /api/rag/bases`, `GET/PUT/DELETE /api/rag/bases/{kbId}` |
| 知识文档 | `POST /api/rag/bases/{kbId}/documents/upload`, `GET /api/rag/bases/{kbId}/documents`, `GET/DELETE /api/rag/document/{docId}` |
| 知识检索 | `POST /api/rag/bases/{kbId}/search`, `GET /api/rag/document/{docId}/chunks`, `GET /api/rag/document/{docId}/file` |
| Token 统计 | `GET /api/token-stats/summary|daily|by-model` |

## 8. 数据模型速览

| 实体 | 表 | 含义 |
| --- | --- | --- |
| `UserEntity` | `users` | 用户账号 |
| `ConversationSessionEntity` | `conversation_session` | 会话，关联用户和可选 App |
| `ChatMessageEntity` | `chat_message` | 对话历史，支持 reasoning |
| `UserSandboxEntity` | `user_sandbox` | 用户沙箱信息 |
| `AgentAppEntity` | `agent_app` | 自定义 Agent 应用，关联知识库和 Skill |
| `KnowledgeBaseEntity` | `knowledge_base` | 知识库 |
| `KnowledgeDocumentEntity` | `knowledge_document` | 文档元数据、处理状态、token、同步状态 |
| `KnowledgeChunkEntity` | `knowledge_chunk` | 文档切片文本、offset、token |
| `TokenUsageEntity` | `token_usage` | LLM token 统计 |

Milvus collection 当前配置为 `knowledge_chunks_v3`，字段核心包括 `user_id`、`kb_id`、`doc_id`、`chunk_index`、`vector`，检索指标使用 cosine。

## 9. Agent 工具清单

常见工具包括：

- `request_sandbox`：创建/获取沙箱。
- `execute_command`：执行命令。
- `shell_wait` / `shell_kill`：长命令等待和终止。
- `read_file` / `write_file` / `list_files`：文件读写和目录浏览。
- `file_search` / `file_replace` / `str_replace_editor`：文件搜索和编辑。
- `download_file`：下载文件到沙箱。
- `browser_action` / `browser_screenshot` / `browser_info`：浏览器操作。
- `parse_document` / `convert_to_markdown`：文档解析和 Markdown 转换。
- `skill_list` / `skill_activate` / `skill_reference`：Skill 渐进式加载。
- `knowledge_search`：知识库检索。

工具带有 `sandboxType`，`AgentServiceImpl` 会按 COMMON/AIO/ALL 过滤可用工具。

## 10. 前端页面

| 页面 | 文件 | 功能 |
| --- | --- | --- |
| 登录 | `static/js/pages/Login.js` | 登录、注册 |
| 聊天 | `static/js/pages/Chat.js` | 会话列表、发送消息、SSE 流式展示、文件上传 |
| Agent 应用 | `static/js/pages/AgentApps.js` | 创建应用、关联知识库和 Skill |
| Skills | `static/js/pages/Skills.js` | 查看 Skill、设置根目录 |
| 知识库 | `static/js/pages/Knowledge.js` | 知识库 CRUD、文档上传、检索测试、切片/文件预览 |
| MCP | `static/js/pages/Mcp.js` | MCP 相关页面 |
| Token 统计 | `static/js/pages/TokenStats.js` | token 消耗看板 |
| 文件/工作区组件 | `WorkspaceBrowser.js`, `FilePreviewer.js` | 文件浏览与预览 |

前端没有单独打包流程，Spring Boot 直接托管 `src/main/resources/static`。

## 11. 面试讲法

### 推荐 1 分钟版本

这个项目是一个 Web Agent 平台，核心是把 LLM 的推理能力和真实可执行环境打通。用户登录后可以创建 Agent 应用，给应用绑定知识库和技能，然后在聊天里让 Agent 自动规划任务、调用沙箱工具、读取文件、执行命令、操作浏览器，必要时还会检索私有知识库。后端用 Spring Boot 做编排，MySQL 存用户、会话和文档元数据，Milvus 做向量检索，前端用 Vue 3 展示聊天、知识库和 token 统计。比较有价值的点是双模型规划执行、工具系统抽象、RAG 入库检索链路、SSE 流式事件和用户/沙箱隔离。

### 可以重点展开的技术亮点

1. 双模型分工：规划模型负责拆任务，执行模型负责 ReAct 工具循环，降低单模型又规划又执行的混乱度。
2. 工具抽象：所有工具统一实现 `Tool`，向 LLM 暴露 `ToolDefinition`，并按沙箱类型动态过滤。
3. RAG 全链路：文档上传后异步解析、切片、embedding，文本存 MySQL，向量存 Milvus，检索时补齐文档来源。
4. 检索增强：已有 `KnowledgeEnhancer`、`QueryRewriteService`、`RerankService` 结构，用于多轮问题改写和候选重排。
5. SSE 事件化：不是只流文本，而是把 plan、thinking、tool_call、observation、answer、done 等作为结构化事件返回。
6. 上下文治理：聊天历史最多取最近 20 条，ReAct 内部超过阈值后压缩旧消息摘要，控制 token 膨胀。
7. 权限隔离：实体普遍带 userId，检索和沙箱访问按当前用户过滤，防止跨用户访问。

### 面试官可能追问

| 问题 | 答法要点 |
| --- | --- |
| 为什么要 PlanAgent + ReactAgent？ | 规划和执行解耦；PlanAgent 产出高层步骤，ReactAgent 根据观察结果动态调整。 |
| RAG 为什么 MySQL + Milvus 都要存？ | MySQL 存可读文本、文档元数据和状态；Milvus 只负责高效向量召回。 |
| 如何避免 Agent 死循环？ | `ReactAgent` 有最大迭代次数 20、工具超时 120 秒、工具错误作为 observation 反馈给模型。 |
| SSE 如何设计？ | 事件类型结构化，前端按事件类型渲染，长工具执行期间发 heartbeat。 |
| 如何做权限隔离？ | 认证后写入 `UserContext`，会话、知识库、向量检索都按 userId 过滤。 |
| 目前最大风险？ | 配置里存在硬编码敏感信息；沙箱生命周期和异步创建存在并发/资源释放风险；SSE 和检索增强仍需要完整回归。 |

## 12. 当前风险与改进点

1. 敏感配置风险
   - `application.yml` 当前存在硬编码数据库密码、LLM API Key、Embedding Key、OCR Key。
   - 建议全部改为环境变量，并删除默认真实值，避免提交和泄漏。

2. 文档编码问题
   - 部分现有中文 Markdown 和 Java 注释在当前终端显示为乱码。
   - 建议统一以 UTF-8 保存，并检查 IDE、Git 和终端编码配置。

3. 沙箱资源生命周期
   - 文档中已有风险记录：沙箱资源释放、异步创建竞态、ThreadLocal 清理需要重点检查。

4. SSE 完整性
   - 流式链路已经成型，但需要重点验证前后端 `done.tokenUsage`、heartbeat、reasoning 展示、中断保存。

5. RAG 检索质量
   - 已有 Query Rewrite + Rerank 结构，但需要继续做效果评估、阈值配置、前端调试可视化。

6. 测试覆盖
   - 当前项目更像功能快速迭代态，建议补充 Agent 编排、RAG 入库检索、权限隔离、SSE 事件序列的自动化测试。

## 13. 下次会话接手建议

下一个 AI 会话建议优先读取这些文件：

1. `docs/project-interview-handoff.md`：本文件，先建立总览。
2. `README.md`：项目功能介绍。
3. `src/main/resources/application.yml`：运行端口、数据库、LLM、RAG、沙箱配置。注意不要泄漏密钥。
4. `src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java`：主编排入口。
5. `src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java`：工具循环和 SSE 核心。
6. `src/main/java/com/example/sandbox/web/service/impl/KnowledgeServiceImpl.java`：RAG 入库与检索主流程。
7. `src/main/java/com/example/sandbox/web/service/enhance/impl/KnowledgeEnhancerImpl.java`：RAG 检索增强。
8. `src/main/resources/static/js/pages/Chat.js`：聊天页和 SSE 前端消费。
9. `docs/implementation-roadmap.md`、`PLAN_SSE.md`、`docs/rag-retrieval-enhancement.md`：后续计划。

建议接手时先运行：

```bash
mvn compile
```

如果要启动项目，需要本地具备 MySQL、Milvus、OpenSandbox 服务，并提供有效 LLM/Embedding 配置：

```bash
mvn spring-boot:run
```

默认访问地址：

```text
http://localhost:8081
```

## 14. 可放到简历的项目描述

Sandbox Agent：基于 Spring Boot 3、Vue 3、MySQL、Milvus 和 OpenSandbox 的 Web Agent 平台。负责/实现了 Agent 会话编排、ReAct 工具调用、隔离沙箱文件与命令执行、Skill 渐进式加载、RAG 知识库上传检索、SSE 流式输出和 token 用量统计。系统采用 Planner/Executor 双模型架构，Planner 生成执行计划，Executor 按 ReAct 循环调用工具并根据 observation 自我修正；RAG 链路通过 Apache Tika 解析文档，MySQL 存储文档和切片元数据，Milvus 存储向量并按用户和知识库过滤检索。

## 15. 面试亮点关键词

- LLM Agent orchestration
- ReAct tool calling
- Planner / Executor 双模型架构
- 沙箱隔离执行
- RAG 文档解析、切片、向量化、Milvus 检索
- Query Rewrite + Rerank
- SSE 流式事件协议
- 用户权限隔离
- Token 用量统计
- Vue 3 单页应用

