---
project: webagent-clean
type: module
status: verified
area:
  - app
  - session
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/controller/AgentController.java
  - src/main/java/com/example/sandbox/web/controller/AgentAppController.java
  - src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java
  - src/main/java/com/example/sandbox/web/model/entity/ConversationSessionEntity.java
  - src/main/java/com/example/sandbox/web/model/entity/AgentAppEntity.java
updated: 2026-07-09
---

# Agent 应用与会话模块

## 1. 模块概览

本文说明用户会话管理、Agent 应用配置、会话历史、启用 Skill 和关联知识库的完整接口和数据流。它是用户入口和 [[Agent 编排模块]] 之间的业务层。

当前实现里需要特别注意五点：

- 会话属于用户，删除会话不销毁用户 Sandbox，因为沙箱是用户级资源。
- 会话创建时异步创建沙箱，不阻塞 HTTP 响应。
- Agent 应用可关联多个知识库和多个 Skill，影响 `AgentTurnContext` 的工具描述、知识库增强和 Skill prompt。
- 会话也维护自己的 `enabledSkillIds`，与应用的 Skill 独立。
- 批量删除会先去空值和重复项，不属于当前用户的 ID 统一作为跳过项返回。

## 2. 会话接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/sessions` | 列出当前用户会话 |
| POST | `/api/sessions` | 创建会话，可关联应用 |
| DELETE | `/api/sessions/{id}` | 删除单个会话 |
| DELETE | `/api/sessions/batch` | 批量删除 |
| GET | `/api/sessions/{id}` | 读取会话详情 |
| GET | `/api/sessions/{id}/history` | 读取历史消息 |
| POST | `/api/sessions/{id}/chat` | 同步对话 |
| GET | `/api/sessions/{id}/chat/stream` | 流式对话 |

### 2.1 会话创建

`createSession` 和 `createSession(appId)` 的流程：

1. `ConversationServiceImpl.createSession` 创建会话记录，生成 UUID 作为 sessionId。
2. 异步创建沙箱（`CompletableFuture.runAsync`），不阻塞前端响应。
3. 如果数据库中已有沙箱记录（`hasSandbox`），跳过创建。
4. 沙箱创建失败只记 warn 日志，不影响会话创建。

### 2.2 会话删除

删除单个会话只移除会话数据（消息历史、会话记录），不销毁用户沙箱。批量删除：

1. 去空值和重复项（`LinkedHashSet`）。
2. `deleteSessionsOwnedByUser` 只删除属于当前用户的会话。
3. 不属于当前用户的 ID 统一作为跳过项返回。
4. 返回 `BatchDeleteSessionsResponse`（deleted + skipped）。

### 2.3 会话归属校验

`validateSessionOwnership` 检查会话的 `userId` 是否等于当前用户 ID。不匹配抛 `UnauthorizedException`。所有会话操作入口都会先校验归属。

## 3. Agent 应用

### 3.1 应用接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/apps` | 创建应用 |
| GET | `/api/apps` | 列出用户应用 |
| GET | `/api/apps/{appId}` | 获取应用详情 |
| PUT | `/api/apps/{appId}` | 更新应用名称和描述 |
| DELETE | `/api/apps/{appId}` | 删除应用 |
| PUT | `/api/apps/{appId}/knowledge-bases` | 更新应用关联知识库 |
| PUT | `/api/apps/{appId}/skills` | 更新应用关联 Skill |

### 3.2 应用配置影响

用户选择应用后，会话准备阶段（`AgentTurnContextService.prepare`）会加载应用配置，影响：

1. **知识库描述**：`AgentKnowledgeContextService.buildKnowledgeDescription` 把知识库描述注入 `knowledge_search` 工具描述。
2. **知识库增强**：`AgentKnowledgeContextService.enhance` 调用 `KnowledgeEnhancer` 生成增强上下文，拼到系统提示。
3. **Skill prompt**：`AgentSkillRuntimeService.buildEnabledSkillPrompt` 把已启用技能元数据拼到系统提示。
4. **工具上下文**：`AgentToolContextService.build` 用应用配置构建工具列表，为 `KnowledgeSearchTool` 注入默认知识库。

应用未关联或加载失败时返回 null，按未关联应用继续执行，不阻断对话。

### 3.3 应用与知识库关联

`AgentAppEntity` 的 `knowledgeBaseIds` 是 `Set<Long>`。关联多个知识库时：

- 检索增强会对所有关联知识库并行检索。
- `KnowledgeSearchTool` 的默认 kbId 取第一个关联知识库。
- `knowledge_search` 工具描述会拼接所有知识库的描述。

## 4. Skill 启用

### 4.1 会话级 Skill 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/sessions/{id}/skills` | 查看当前会话已启用 Skill |
| GET | `/api/sessions/{id}/skills/available` | 查看可用 Skill |
| POST | `/api/sessions/{id}/skills/{skillId}/enable` | 启用 Skill |
| POST | `/api/sessions/{id}/skills/{skillId}/disable` | 禁用 Skill |

### 4.2 Skill 启用的影响

启用 Skill 后：

1. `AgentSkillRuntimeService` 在系统提示中暴露 Skill 摘要（元数据层，约 30-50 token）。
2. 创建/恢复 Sandbox 时同步启用 Skill 文件到 `/home/gem/skills/`。
3. `todo_write` 工具运行时任务清单约束按需加载。
4. SOCIAL 轮次跳过 Skill 注入（`policy.shouldInjectSkill()` 为 false）。

### 4.3 Skill 三级渐进式披露

| 层级 | 内容 | 加载时机 |
| --- | --- | --- |
| 元数据层 | id + description | 系统提示始终暴露已启用技能 |
| 详细层 | SKILL.md 完整内容 | 按需加载，运行期走沙箱 |
| 资源层 | references/、scripts/ | 用到才取，运行期走沙箱 |

## 5. 会话历史

`getRecentHistory(sessionId, 20)` 加载最近 20 条历史消息。历史消息在 `AgentTurnContextService.prepare` 中用于：

- 判断是否首轮（`firstTurn = history.isEmpty()`）。
- 意图判断（`judgeIntent` 截断到最近 6 条）。
- 规划阶段历史隔离（过滤为"对话资料"文本）。
- 知识库增强（`KnowledgeEnhancer.enhance` 传 history 做 query rewrite）。

## 6. 前端体验

Chat 页面支持会话搜索、会话选择、批量删除、删除后恢复当前会话选择、上传文件、SSE 流式展示和右侧工具 dock。会话切换会重置沙箱视图 URL，并在需要时重新获取 view-url。

## 7. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| 会话创建后沙箱不可用 | 异步创建日志、`hasSandbox` | 沙箱异步创建，失败只记 warn |
| 越权访问会话 | `validateSessionOwnership` | 会话 userId 与当前用户不匹配抛异常 |
| 应用知识库不生效 | `AgentAppEntity.knowledgeBaseIds`、`loadApp` | 应用未关联或加载失败返回 null |
| Skill 未注入 | `enabledSkillIds`、`policy.shouldInjectSkill()` | SOCIAL 轮次跳过 Skill |
| 批量删除跳过 | `deleteSessionsOwnedByUser`、userId | 不属于当前用户的会话被跳过 |
| 标题未更新 | `scheduleGeneratedTitle`、firstTurn | 只在首轮异步生成，失败只记日志 |

## 8. 相关页面

[[创建会话并完成一次对话]] · [[Agent 编排模块]] · [[Skill 系统模块]] · [[前端模块]] · [[核心数据模型]]