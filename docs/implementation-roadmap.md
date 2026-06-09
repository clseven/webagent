# 实施规划总表

> 最后更新：2026-06-05
>
> 本文档汇总所有待实施的规划，按优先级排序，方便 Agent 快速定位和执行。

---

## 一、优先级总览

> **AI 实施理念**：让 AI 写代码本身很快（**时间不是瓶颈**），**瓶颈在设计清晰度**。本表用"AI 实施关键决策"代替"工作量估算"，标出每个规划实施时需要重点确认的设计点。

| 优先级 | 规划 | 收益 | 风险 | 实际状态 | AI 实施关键决策 | 参考文档 |
|--------|------|------|------|----------|------------------|----------|
| **P0-1** | 代码重构 | 消除技术债务，为后续开发扫清障碍 | 中（可能引入回归 bug） | 🟡 文件已迁移，待修 import | 1) 用 IDE 批量改 import 2) 是否引入 Lombok | [REFACTOR_PLAN.md](./REFACTOR_PLAN.md) |
| **P0-2a** | SSE 流式 - 修 bug 跑通 | 验证已有代码可用，闭环最小可用链路 | 低 | 🟡 代码已写 70%，**有 1 个编译错误** | 1) 修 `AgentServiceImpl:564` 重复 `userId` 2) 删 `ReactAgent:629` 无效 `takeUntilOther` | [PLAN_SSE.md](../PLAN_SSE.md) |
| **P0-2b** | SSE 流式 - 二期完善 | 完整体验：心跳、token 展示、thinking/reasoning 分类渲染 | 低 | ⚪ 待 P0-2a | 1) 前后端 `done.tokenUsage` 协议对齐 2) heartbeat 前端处理 3) reasoning 分类渲染 | [PLAN_SSE.md](../PLAN_SSE.md) |
| **P1-1** | RAG 检索增强 | 解决多轮指代、query 口语化、专有名词召回差（**当前用户最大痛点**） | 中（新增组件 + LLM 成本↑） | ⚪ 待开始（设计已评审） | 1) Query Rewrite 用哪种模型 2) Rerank 阈值 3) 三层颗粒度（知识库/文档/会话）开关的 UI 设计 | [rag-retrieval-enhancement.md](./rag-retrieval-enhancement.md) |
| **P1-1b** | 知识库切片可视化 | 调试 RAG 召回结果，让用户看到"AI 用的这条来自原文哪里" | 低 | ⚪ 待开始（晚点做） | 1) 后端切片列表接口 2) 前端 ChunkViewer 组件 3) 关键词高亮 4) P1-1 召回结果联动 | 待建文档 |
| **P1-2** | 子智能体架构 | 根本性架构升级，解决上下文污染，支持并行 | 高（架构级变更） | ⚪ 待开始 | 1) flag 渐进式迁移 2) 子智能体同步 vs 流式 3) `<delegate>` 降级策略 4) 递归深度限制 5) 子智能体超时 | [subagent-architecture.md](./subagent-architecture.md) |
| **P2-1** | 前端文件预览 | 用户体验优化，预览知识库/工作空间文件 | 低 | ⚪ 待开始（设计已评审） | 1) Office 转 PDF 用 LibreOffice 还是前端 2) 一期支持格式范围 3) 预览组件复用 vs 独立 | [frontend-file-preview.md](./frontend-file-preview.md) |
| **P3-1** | 代码缺陷修复 | 解决已知问题（资源泄漏、并发问题等） | 低 | ⚪ 待开始 | 沙箱泄漏 + 异步竞态优先独立 PR | [code-review-report.md](./code-review-report.md) |
| **P3-2** | `.doc` 格式解析 | 沙箱镜像支持旧 `.doc` 格式（`.docx` 已支持） | 低 | 🟡 代码已写，待沙箱启动验证 | 1) 启动时 `apt-get install catdoc` 2) `file_parser.py` 加 `parse_doc` 3) **升级版**见下方（Dockerfile 方式） | [REFACTOR_PLAN.md](./REFACTOR_PLAN.md#九当前进度2024-06-04) |

### P1 优先级调整说明

原 roadmap 建议"子智能体优先于 RAG"，理由是"解决根本性上下文污染"。**已调整**为"RAG 增强优先"，理由：

1. **痛点导向**：用户当前最大痛点是 RAG 检索质量差（多轮对话指代丢失、专有名词召回差），RAG 增强是"今天就能见效"的功能
2. **风险梯度**：RAG 增强是"加新组件"，子智能体是"改架构"，后者风险远高于前者
3. **价值验证**：RAG 增强可在小流量上 AB 验证，子智能体改动大、试错成本高

子智能体仍是"未来 6 个月最有价值的升级"，但**不是当前最高优先级**。

### P1-1 和 P1-2 的协同关系

**不是两个独立工作**：
- P1-1 RAG 增强要新增 `KnowledgeEnhancer`（Query Rewrite + Rerank）
- P1-2 子智能体包含 `SearcherAgent`，**内部调用 `KnowledgeEnhancer`**
- **推荐顺序**：先 P1-1（稳定 KnowledgeEnhancer）→ 再 P1-2（SearcherAgent 内置 KnowledgeEnhancer）

详见 [subagent-architecture.md 第九节](./subagent-architecture.md#九与-p1-1-rag-检索增强的协同)。

---

## 二、依赖关系图

```
代码重构(P0-1)  ─┬─→ SSE-修bug(P0-2a) ─→ SSE-二期(P0-2b)
                  │
                  ├─→ RAG检索增强(P1-1)   ← 当前最高价值 P1
                  │
                  ├─→ 子智能体架构(P1-2)  ← 未来高价值 P1
                  │
                  ├─→ 前端文件预览(P2-1)  ← 可与 P1 并行
                  │
                  └─→ 代码缺陷修复(P3-1)  ← 持续性，应穿插进行
```

### 依赖关系说明

**硬依赖**（必须等前置完成）：
- P0-2a / P0-2b → P0-1
- P1-1 / P1-2 → P0-1

**软依赖**（建议但不强制）：
- P2-1 → 实际不依赖 P0-1，主要是前端 + 一点后端
- P3-1 → 持续性工作，可以从今天就开始

**可并行**：
- P1-1 RAG 增强和 P1-2 子智能体：建议**优先 P1-1**，P1-2 等有团队人手再做
- P2-1 文件预览：可与 P1-1 并行
- P3-1 缺陷修复：任何阶段都可穿插

---

## 三、详细规划

### P0-1: 代码重构

**参考文档**: [REFACTOR_PLAN.md](./REFACTOR_PLAN.md)

**状态**: 🟡 文件迁移已完成，待修复 import 引用

**AI 实施关键决策**:
1. **修 import 引用**：用 IDEA 的 Refactor → Move 功能批量修复，或手动改 `com.example.sandbox.*` → `com.example.sandbox.web.*`
2. **删旧目录残留**：git status 显示大量 `D` 状态文件（`web/`, `agent/`, `aio/`, `playwright/`，参考 REFACTOR_PLAN.md 第 682-685 行的命令）
3. **是否引入 Lombok**：先看 `pom.xml` 是否已加依赖；引入会减少 200+ 行样板代码，但需要全文件加 `@Data` `@Builder` 注解
4. **Skill 读取改走沙箱**：当前 `Skill.getContent()` 直接读宿主机文件，应改为走 `SandboxClient.readFile()` 读沙箱内已同步文件（路径 `/home/gem/skills/{skillId}/...`）

**风险**: 中。每次改完跑一遍冒烟测试，避免回归

---

### P0-2a: SSE 流式 - 修 bug 跑通（**今天就能做**）

**参考文档**: [PLAN_SSE.md](../PLAN_SSE.md)

**状态**: 🟡 代码已写 70%，**有 1 个编译错误待修**

**已完成（无需再做）**:
- ✅ `SseEvent` 事件模型（含 token / reasoning_token / thinking_start/end / tool_call / observation / plan / answer / done / error / interrupted / heartbeat）
- ✅ `LlmStreamChunk` 流式响应块
- ✅ `LlmService` 接口新增 `chatStream` / `chatWithToolsStream`
- ✅ `BaseLlmServiceImpl` 流式实现（WebClient + bodyToFlux + SSE 解析 + tool_call 流式累积）
- ✅ `ReactAgent.runStream` 双层流式（外层步骤 + 内层 token）
- ✅ `AgentService.chatStream` 流式编排（含规划 → 执行 → 消息保存）
- ✅ `Controller /api/sessions/{id}/chat/stream` SSE 端点
- ✅ 前端 `Chat.js` 消费 SSE 全部事件类型
- ✅ `pom.xml` `spring-boot-starter-webflux` 依赖

**AI 实施关键决策**:
1. **修编译错误**（必须先做）
   - `AgentServiceImpl.java:524` 和 `:564` 都声明了 `Long userId = UserContext.getCurrentUserId();`
   - 第 564 行是重复声明，会编译失败
   - **修法**：删掉 564 行的重复声明（变量已在 524 行声明）
2. **清理无效代码**
   - `ReactAgent.java:629` 的 `.takeUntilOther(Flux.never())` 是空操作（`Flux.never()` 永不结束）
   - 删掉这行
3. **编译 + 跑通最小链路**
   - `mvn compile` 通过
   - 启动应用，发一条消息，前端能看到 token 流式出现
   - 测试"用户点停止" → 后端能在 1-2 秒内释放资源

**风险**: 低。核心逻辑已实现，只是清理

---

### P0-2b: SSE 流式 - 二期完善

**参考文档**: [PLAN_SSE.md](../PLAN_SSE.md)

**状态**: ⚪ 待 P0-2a 完成后开始

**AI 实施关键决策**:
1. **前后端 `done.tokenUsage` 协议对齐** — 后端 `AgentServiceImpl` 已记录 token 用量，前端应展示给用户（`Chat.js` 当前未读 `done` 的 `data.tokenUsage`）
2. **前端 `heartbeat` 事件处理** — 后端长工具执行期间会发心跳，前端应监听以防代理超时断开（当前 `Chat.js` 未处理 `heartbeat`）
3. **`reasoning_token` 分类渲染** — 当前前端把 reasoning 和 token 混在一起追加到 `currentThinking`（`Chat.js:351`），应分开显示（"思考过程" vs "思考链"）
4. **PlanAgent 流式化** — 规划阶段可 yield 中间进度，当前只发一次 `plan` 事件
5. **流式响应测试** — 单元测试 + 集成测试（SseEvent 序列化 / ReactAgent.runStream 流式产出 / 中断检测）
6. **消息存储统一** — 当前每个分支单独 `saveAssistantMessage` / `saveInterruptedMessages`，应改为统一 `pendingMessages` 队列
7. **SSE 连接稳定性** — 心跳事件 + 前端重连机制
8. **工具超时调整** — 当前 120 秒（原计划 30 秒），需确认合理值

**风险**: 低

---

### P1-1: RAG 检索增强 ⭐ **当前最高价值**

**参考文档**: [rag-retrieval-enhancement.md](./rag-retrieval-enhancement.md)

**状态**: ⚪ 待开始（设计已评审）

**AI 实施关键决策**:
1. **Query Rewrite 用哪种模型** — 用智谱 GLM（成本低）还是 DeepSeek（质量高）？
2. **Rerank 阈值怎么定** — top20 → top5 的取舍标准
3. **三层颗粒度（知识库/文档/会话）开关的 UI 设计** — 在知识库页面加开关 vs 在会话页面加
4. **`RerankService` 是独立服务还是工具内嵌** — 影响后续 P1-2 SearcherAgent 的调用方式
5. **AB 测试方案** — 如何在小流量上验证效果

**方案核心**:
```
用户消息 + 历史
    ↓
Step 1: Query Rewrite（LLM 改写 1~3 个独立 query）
    ↓
Step 2: 向量检索（并行多路）
    ↓
Step 3: 融合去重（按 docId+chunkIndex 合并）
    ↓
Step 4: Rerank（LLM 重排 top20 → top5）
    ↓
Step 5: 注入到 system context
    ↓
Step 6: LLM 拿到的是"已经重排好的相关片段"
```

**待完成**:
1. 新增 `RerankService`（LLM 调用，传入 query + candidates，返回相关性分数）
2. 新增 `KnowledgeEnhancer`（Step 1-6 编排）
3. `ChatRequest` 新增 `enableKnowledge` 字段
4. 前端 toggle UI（会话级开关）
5. `AgentApp` 默认配置（应用级默认）
6. 知识库级 + 文档级开关（在 KnowledgeService 加 enable/disable API）
7. AB 测试方案（小流量验证效果）

**成本**：每次对话多 1 次 LLM 调用（Rewrite）+ N 次 Embedding + Rerank

**风险**: 中
- LLM 调用成本上升
- Query Rewrite 质量依赖模型能力
- Rerank 准确度需要验证

**依赖**: 可与 P0-2b 并行；P1-2 子智能体的 SearchAgent 可复用本组件（**必须先做 P1-1 再做 P1-2 的 SearchAgent**）

---

### P1-1b: 知识库切片可视化（P1-1 的配套调试 UI）

**状态**: ⚪ 待开始（**晚点做**，跟 P1-1 一起规划）

**问题**: 当前 `Knowledge.js` 文档列表只显示"X 个切片"数字，看不到内容。P1-1 增强检索后，用户最关心"AI 用的这条是从哪个文档哪个切片来的"。

**与 P2-1（文件预览）的关系**:
| 维度 | 文件预览（P2-1） | 切片展示（P1-1b） |
|------|----------------|------------------|
| 展示什么 | 原始文档 | 切片后的文本块 |
| 给谁看 | 确认"文件长啥样" | 调试"RAG 召回效果" |
| 价值 | 体验优化 | **RAG 调试必备** |

**待完成**:
1. 后端新增切片列表接口（`GET /api/rag/documents/{docId}/chunks`，分页）
2. 复用 `KnowledgeChunkRepository.findByDocumentIdOrderByChunkIndex`（已存在）
3. 前端 `Knowledge.js` 文档行加 "🔍 查看切片" 按钮
4. 新增 `ChunkViewer` 组件（弹窗）：
   - 左侧切片列表（可滚动）
   - 右侧选中切片完整内容
   - 支持搜索高亮（输入关键词 → 高亮匹配 chunk）
   - 分页/虚拟滚动（1000+ 切片不卡）
5. P1-1 召回结果联动：在 RAG 召回结果里点"查看原文"跳到 ChunkViewer 对应切片

**AI 实施关键决策**:
1. **是否做关键词高亮** — P1-1 检索时把 query 关键词传给前端高亮
2. **是否做相似度展示** — 切片列表里显示与 query 的相似度分数
3. **分页还是虚拟滚动** — 切片量大时虚拟滚动（react-window）vs 服务端分页
4. **是否支持切片编辑** — 让用户能合并/拆分/删除某个切片（高级功能，二期）

**风险**: 低

**依赖**: 强烈建议跟 P1-1 一起做（P1-1 后端跑通后立即补前端 UI，否则 RAG 增强是黑盒）

---

### P1-2: 子智能体架构

**参考文档**: [subagent-architecture.md](./subagent-architecture.md)（已优化为 AI 实施友好版）

**状态**: ⚪ 待开始

**AI 实施关键决策**:
1. **flag 渐进式迁移**：第一阶段用 `agent.sub-agent.enabled` flag 控制，旧 ReactAgent 逻辑保留作后备，可秒回滚
2. **子智能体同步 vs 流式**：先实现方案 A（子智能体同步执行，不透传流式进度），方案 B（流式透传）作为 P0-2b 之后的后续优化
3. **`<delegate>` 降级策略**：解析失败时返回空列表，按"无委托"继续
4. **递归深度限制**：`MAX_DELEGATION_DEPTH = 3`，防止 LLM 一直输出 `<delegate>` 死循环
5. **子智能体超时**：`SUB_AGENT_TIMEOUT_MS = 60_000`，单点超时不影响其他
6. **sessionId 传递**：`GenericSubAgent` 必须从 `context.sessionId()` 拿 sessionId 传 ReactAgent，但 `conversationService` 传 null（不写主会话）
7. **`<P1-1 已完成>`** 才能做 P1-2 的 SearcherAgent 阶段（详见 subagent-architecture.md 第九节）

**实施阶段**（详见 subagent-architecture.md 第十节）:
- 阶段 A: 基础框架（接口、类型、解析器、工厂、GenericSubAgent）
- 阶段 B: 第一个子智能体（AnalyzerAgent 端到端验证）
- 阶段 C: MainAgent 核心
- **阶段 A→B→C 完成后暂停，让用户决定是否启用方案 B（流式透传）**
- 阶段 D: 其他 3 个子智能体
- 阶段 E: 集成到 AgentServiceImpl
- 阶段 F: 风险验证（4 个对抗测试）

**依赖**:
- **硬依赖 P0-2a SSE 跑通**：流式 + 并行 + 多上下文三重复杂度叠加会失控
- **强依赖 P1-1 RAG 增强**（针对 SearcherAgent 阶段）：SearcherAgent 内部调用 KnowledgeEnhancer
- 软依赖 P0-1 代码重构

---

### P2-1: 前端文件预览

**参考文档**: [frontend-file-preview.md](./frontend-file-preview.md)

**状态**: ⚪ 待开始（设计已评审）

**AI 实施关键决策**:
1. **Office 转 PDF 用 LibreOffice 还是前端** — LibreOffice 部署在沙箱镜像里 vs 前端用 sheetjs 解析
2. **一期支持格式范围** — 建议 PDF / 图片 / 纯文本 / Markdown / 代码（不包含 Office，避免一开始就陷入转换难题）
3. **预览组件复用 vs 独立** — 知识库和工作空间两处用同一组件还是各一个
4. **统一预览接口设计** — `/api/preview?path=xxx` 的权限校验（防止越权访问）

**待完成**:
1. 后端统一预览接口（`/api/preview?path=xxx`，根据后缀返回对应 Content-Type）
2. Content-Type 动态映射
3. 前端 `FilePreview` 组件
4. 知识库页面集成
5. 工作空间页面集成
6. （二期）Office 文档转换（Word/Excel/PPT → PDF，需 LibreOffice）

**一期支持**: PDF / 图片 / 纯文本 / Markdown / 代码
**二期支持**: Word / Excel / PPT

**风险**: 低

**依赖**: 不强依赖 P0-1，可与 P1-1 并行

---

### P3-1: 代码缺陷修复（持续性）

**参考文档**: [code-review-report.md](./code-review-report.md)

**状态**: ⚪ 待开始（可从今天穿插做）

**AI 实施关键决策**:
1. **沙箱泄漏修复的颗粒度** — 是独立 PR 还是跟随其他规划一起
2. **异步竞态的修复策略** — 改用 `CompletableFuture` 存引用 + `chat()` 中等待，还是用分布式锁
3. **ThreadLocal 改 TransmittableThreadLocal** — 是否升级 TTL 库

**关键问题**:
| 问题 | 优先级 | 紧急度 |
|------|--------|--------|
| 沙箱资源泄漏 | P1 | 高（生产环境可能 OOM） |
| 异步沙箱创建竞态条件 | P1 | 高（用户立即遇到） |
| ThreadLocal 内存泄漏风险 | P2 | 中 |
| ReAct 消息历史无限增长 | P3 | 低（已有压缩机制，可观察） |

**风险**: 低

**建议做法**:
- **不要攒到最后做**：每修一个 P0/P1 时顺手把相关缺陷也修了
- **沙箱资源泄漏 + 异步沙箱竞态** 这两个 P1 问题可以**优先单独提一个 PR 修**（独立于其他规划）

---

### P3-2: `.doc` 格式解析

**参考文档**: [REFACTOR_PLAN.md](./REFACTOR_PLAN.md)（第九节"当前进度"第 6 条）

**状态**: 🟡 代码已写，待沙箱启动验证 catdoc 装包是否成功

**问题**: 当前 `/v1/util/convert_to_markdown` 只支持 `.docx`，不支持旧 `.doc` 格式

**AI 实施关键决策**:
1. **镜像装 antiword 还是 catdoc** — 两种工具各有特点
2. **接口层 vs Tool 层改造** — 修改 `/v1/util/convert_to_markdown` 接口（侵入性大） vs 在 `DocumentParserTool` 代码层加 `.doc` 处理（侵入性小）
3. **pandoc 方案** — 备选：`pandoc -f doc -t markdown`（功能强但镜像体积大）

**当前实施**（**方式 A：启动时自动装包**）:
- **Java 端**：`SandboxServiceImpl.initAioDirectories` 启动时执行 `apt-get install -y catdoc || apk add --no-cache catdoc`，失败不抛错（try-catch 兜底）
- **Python 端**：`file_parser.py` 加 `parse_doc()` 函数 + `parse_file()` 加 `.doc` 分支，调 `subprocess.run(['catdoc', '-s', 'cp936', '-d', 'utf-8', file_path])`
- **README**：沙箱 `/home/gem/README.md` 同步加 `.doc` 格式说明
- **验证方法**（用户启动沙箱后）:
  ```bash
  which catdoc              # 应返回 /usr/bin/catdoc
  catdoc -v                 # 看版本
  python3 /home/gem/tools/file_parser.py parse /path/to/test.doc
  ```
- **优点**：零沙箱镜像改动，通用性强
- **缺点**：每次沙箱启动多 2-3 秒（且依赖网络）

---

**升级版（**方式 B：Dockerfile 方式，推荐**）**:

> **适用场景**：你能控制沙箱镜像的 Dockerfile（比如从网上下载了镜像，自己改 Dockerfile 重新打）

**Dockerfile 片段**（在合适位置加一行）:

```dockerfile
# 安装 .doc 解析依赖（catdoc，体积 ~1MB，中文支持好）
RUN apt-get update && apt-get install -y --no-install-recommends catdoc \
    && rm -rf /var/lib/apt/lists/*
```

**完整 Dockerfile 模板**（基于 Debian/Ubuntu 基础镜像）:
```dockerfile
FROM ubuntu:22.04

# ... 现有系统依赖 ...

# .doc 解析支持
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        catdoc \
    && rm -rf /var/lib/apt/lists/*

# ... 现有 Python 依赖 ...
# pip install python-docx docx2txt PyMuPDF openpyxl xlrd python-pptx
```

**Alpine 基础镜像**（如果用 Alpine）:
```dockerfile
FROM alpine:3.18

RUN apk add --no-cache catdoc
```

**Java 端对应改动**（如果完全用 Dockerfile 方式，可以删掉启动时装包逻辑）:
- 删除 `SandboxServiceImpl.initAioDirectories` 里的 apt-get install 代码
- `file_parser.py` 不变

**优点 vs 启动时装包**:
| 维度 | 启动时装包（当前） | Dockerfile 装（升级版） |
|------|------------------|----------------------|
| 启动开销 | 多 2-3 秒 | 0 |
| 网络依赖 | 启动时需联网 | 镜像构建时联网（一次性） |
| 失败概率 | 偶尔（网络/源问题） | 0（构建时已验证） |
| 镜像体积 | 不变 | +1MB（catdoc） |
| 部署灵活性 | 任何镜像都能用 | 需要自己能打镜像 |

**双保险策略**（推荐）:
- **首选 Dockerfile 方式**（生产环境）
- **保留启动时装包代码**作为兜底（开发环境 / 用别人镜像时）
- 两者并存：Dockerfile 装好后，启动时装包会自动跳过（`apt-get install` 检测到已装会直接返回 0）

**风险**: 低

**依赖**: 不依赖其他规划

---

## 四、推荐实施顺序（合并原第三节 + 第六节）

> **AI 实施理念**：让 AI 写代码本身很快，**时间不是瓶颈**。以下时间标注仅作参考节奏，**实际由 AI 一次性写完**。

```
┌─────────────────────────────────────────────────────┐
│ 今天就能做（最小可执行任务）                          │
│  P0-2a: SSE 修 bug 跑通                            │
│       修 AgentServiceImpl.java:564 重复 userId      │
│       + 删 ReactAgent.java:629 无效 takeUntilOther   │
│       + 跑通最小链路                                 │
│  关键决策：3 个（见第一节）                           │
└─────────────────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────────┐
│ 短期（本周）                                         │
│  P0-1: 代码重构（可与 P3-1 并行穿插）                │
│       修 import + 删旧目录 + Lombok                  │
│  P3-2: .doc 格式解析（独立小任务，可穿插）           │
│  关键决策：P0-1 有 4 个，P3-2 有 3 个                 │
└─────────────────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────────┐
│ 中期（本月内）                                       │
│  P0-2b: SSE 二期完善（可与 P1-1 并行）              │
│  P1-1: RAG 检索增强 ⭐ 当前最高价值                  │
│  P3-1: 沙箱资源泄漏修复（独立 PR 优先）              │
│  关键决策：P0-2b 有 8 个，P1-1 有 5 个                 │
└─────────────────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────────┐
│ 长期（持续）                                         │
│  P1-2: 子智能体架构                                 │
│       硬依赖 P0-2a 跑通，强依赖 P1-1（SearcherAgent）│
│       实施分 6 个阶段（A→B→C→暂停→D→E→F）           │
│  P2-1: 前端文件预览（可与 P1-1 / P1-2 并行）         │
│  P3-1: 剩余缺陷修复（穿插）                          │
│  关键决策：P1-2 有 7 个，P2-1 有 4 个                 │
└─────────────────────────────────────────────────────┘
```

---

## 五、快速参考

### Agent 执行指南

当需要实施某个规划时，按以下步骤操作：

```
1. 读取对应文档
   - 使用 Read 工具读取参考文档
   - 理解设计目标和实现细节

2. 确认当前状态
   - 检查"待完成"列表
   - 确认依赖关系是否满足

3. 按步骤实施
   - 按文档中的步骤逐步实施
   - 每完成一步，更新文档中的进度标记

4. 完成后验证
   - 编译测试
   - 功能验证
   - 更新文档状态
```

### 文档索引

| 文档 | 路径 | 用途 |
|------|------|------|
| 实施规划总表 | `docs/implementation-roadmap.md` | 本文档，规划总览 |
| 代码重构 | `docs/REFACTOR_PLAN.md` | 目录重组、Lombok 改造 |
| SSE 流式输出 | `PLAN_SSE.md` | 流式改造详细方案 |
| 子智能体架构 | `docs/subagent-architecture.md` | 子智能体设计方案 |
| RAG 检索增强 | `docs/rag-retrieval-enhancement.md` | Query Rewrite + Rerank |
| 前端文件预览 | `docs/frontend-file-preview.md` | 文件预览功能设计 |
| 代码缺陷报告 | `docs/code-review-report.md` | 已知问题清单 |
| RAG 基础设计 | `docs/rag-design.md` | RAG 已实现部分（参考） |
| 配置参考 | `docs/project-config-reference.md` | 配置参数说明（参考） |

---

## 六、进度追踪

| 规划 | 状态 | 开始日期 | 完成日期 | 备注 |
|------|------|----------|----------|------|
| P0-1 代码重构 | 🟡 进行中 | 2026-06-05 | - | 文件已迁移，待修 import |
| P0-2a SSE 修 bug 跑通 | ⚪ 待开始 | - | - | 阻塞：1 个编译错误 |
| P0-2b SSE 二期完善 | ⚪ 待 P0-2a | - | - | |
| P1-1 RAG 检索增强 | ⚪ 待开始 | - | - | 当前最高价值 P1 |
| P1-1b 知识库切片可视化 | ⚪ 待开始 | - | - | P1-1 配套 UI，**晚点做** |
| P1-2 子智能体架构 | ⚪ 待开始 | - | - | 硬依赖 P0-2a，强依赖 P1-1（SearcherAgent） |
| P2-1 前端文件预览 | ⚪ 待开始 | - | - | 可与 P1-1 并行 |
| P3-1 代码缺陷修复 | 🟡 部分进行 | - | - | 沙箱泄漏 + 异步竞态 可优先 |
| P3-2 .doc 格式解析 | 🟡 进行中 | 2026-06-05 | - | 代码已写：启动时 `apt-get install catdoc` + `file_parser.py` 加 `parse_doc`。**待沙箱启动验证**。Dockerfile 升级版已记入文档。 |

状态说明：⚪ 待开始 | 🟡 进行中 | ✅ 已完成 | ❌ 已取消

---

## 七、注意事项

1. **优先完成 P0**：P0 是基础，不完成会影响后续开发
2. **P1 优先级已调整**：RAG 增强（当前痛点） > 子智能体（未来价值）
3. **P1-2 硬依赖 P0-2a SSE 跑通**：流式 + 并行 + 多上下文三重复杂度叠加会失控，**必须先 P0-2a**
4. **P1-2 SearcherAgent 强依赖 P1-1**：SearcherAgent 内部调用 KnowledgeEnhancer，**必须先 P1-1**
5. **P1 可并行**：P1-1 RAG 增强 和 P0-2b SSE 二期 可并行开发
6. **P1-2 实施分阶段 + 暂停点**：A→B→C 完成后**暂停**，让用户决定是否启用方案 B（流式透传），避免做了一大堆又回滚
7. **及时更新文档**：每完成一个步骤，更新第六节"进度追踪"
8. **遇到问题先讨论**：参照 CLAUDE.md 的规则，先讨论问题和方案，获得确认后再修改代码
9. **代码缺陷穿插修**：不要攒到最后，每做一个 P0/P1 时顺手修相关缺陷
