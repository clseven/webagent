# WebAgent Clean Obsidian 项目知识库首次建库实施计划

> **执行目标：** 将当前代码仓库整理为一套适合 AI 新会话快速理解、同时便于人类浏览的中文 Obsidian 项目知识库。

**源仓库：** `D:\javademo\webagent-clean`

**目标目录：** `D:\obsidian\llm-wiki\Projects\WebAgent Clean`

**约束：**

- 只在本地生成，不提交 Git。
- 知识库正文使用简体中文，代码标识符、接口路径和配置键保持原样。
- 以代码、配置、测试和仓库内契约文档为主要事实来源。
- 目录只负责物理归类；跨模块关系使用 Obsidian 内部链接、反向链接和原生关系图表达。
- 不维护独立的人工关系图文件。
- 使用稳定的英文 Properties 键和值，方便 Obsidian Bases、Dataview 和后续 Skill 查询。

---

## 任务一：确认知识来源和当前状态

**读取内容：**

- 仓库目录结构、Git 当前状态和近期提交
- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- 核心 Java 源码、前端源码和测试
- `docs/sandbox-api/README.md`
- `docs/sandbox-api/integration-map.md`
- `docs/sandbox-api/openapi.json`
- Obsidian vault 的 `.obsidian` 配置和已启用插件

**步骤：**

1. 记录当前提交和未提交变更，只描述，不修改。
2. 划分主要业务模块、基础设施模块和外部依赖。
3. 以 OpenAPI 文件核对 AIO Sandbox HTTP 请求、响应和错误契约。
4. 标记代码事实、合理推断和待确认内容，避免把推断写成既定事实。

## 任务二：提炼架构、模块和跨模块流程

**重点领域：**

- Agent 编排、计划执行与 ReAct
- LLM 接入与模型分工
- Agent 应用、会话和消息
- RAG 文档摄取、切片、向量检索、查询改写与重排
- Sandbox 生命周期、AIO 客户端和工具执行
- Skill 加载与工具暴露
- 文件存储、用户工作区、同步和 Office 预览
- 认证、用户上下文和前端页面
- 数据模型、运行配置、外部服务和测试现状

**步骤：**

1. 为每个模块确认职责、入口、核心类、依赖和被依赖关系。
2. 识别真正跨越多个模块的用户流程。
3. 将关键类、接口、配置和流程相互链接。
4. 将尚未从实现中确认的内容收敛到“开放问题”或标记为 `inferred`。

## 任务三：生成 Obsidian 知识库

**生成目录：**

```text
WebAgent Clean/
├─ 00 项目入口.md
├─ 01 当前上下文.md
├─ 10 架构/
├─ 20 模块/
├─ 30 跨模块流程/
├─ 40 接口与数据/
├─ 50 工程知识/
├─ 80 变更记录/
├─ 90 待整理/
└─ 视图/
```

**步骤：**

1. 创建项目入口，提供 AI 阅读路径、人类阅读路径、核心模块和关键流程索引。
2. 创建当前上下文，记录仓库状态、近期变化、风险和推荐阅读顺序。
3. 为架构、模块、流程、接口、数据模型和工程知识创建独立中文页面。
4. 每页添加统一 Properties：

```yaml
project: webagent-clean
type: module
status: verified
area:
  - rag
tags:
  - project/webagent-clean
source:
  - src/main/...
updated: 2026-06-13
```

5. 使用 `[[内部链接]]` 建立模块、流程、接口、配置和风险之间的关系。
6. 创建 Obsidian Bases 视图，至少覆盖：
   - 全部项目知识
   - 按类型和领域浏览
   - 待确认或可能过期的内容

## 任务四：质量校验

**校验项：**

- 所有 Markdown 和 Base 文件可按 UTF-8 读取
- YAML Properties 格式一致
- 内部链接不存在明显断链
- 文件名不存在会造成链接歧义的重复项
- 入口页可以在少量跳转内定位到模块、流程和接口
- 主要模块至少同时连接到一个流程或相关模块
- `status: verified` 的内容能追溯到明确源码或契约
- `.base` 文件语法可被 YAML 解析

**步骤：**

1. 自动扫描 Markdown、Properties 和内部链接。
2. 检查孤立页面和连接过少的页面。
3. 抽查核心页面中的源码路径、接口路径和配置键。
4. 修正校验发现的问题后重新执行检查。

## 任务五：写入本地 Vault

**步骤：**

1. 在仓库内暂存并完成全部校验。
2. 确认目标 vault 根目录和目标项目目录。
3. 将校验后的目录复制到 `D:\obsidian\llm-wiki\Projects\WebAgent Clean`。
4. 再次检查目标目录中的文件数量、编码和关键入口文件。
5. 保留仓库内的实施计划，但不执行 Git 暂存或提交。

