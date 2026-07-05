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
updated: 2026-07-06
---

# Agent 应用与会话模块

## 职责

该模块管理用户会话、Agent 应用配置、会话历史、启用 Skill、关联知识库和前端会话列表。它是用户入口和 [[Agent 编排模块]] 之间的业务层。

## 会话

- `GET /api/sessions`：列出当前用户会话。
- `POST /api/sessions`：创建会话，可关联应用。
- `DELETE /api/sessions/{id}`：删除单个会话。
- `DELETE /api/sessions/batch`：批量删除。
- `GET /api/sessions/{id}`：读取会话详情。
- `GET /api/sessions/{id}/history`：读取历史消息。
- `POST /api/sessions/{id}/chat`：同步对话。
- `GET /api/sessions/{id}/chat/stream`：流式对话。

会话属于用户。删除会话不销毁用户 Sandbox，因为当前资源模型是“一个用户一个长期 Sandbox，多个会话共享”。

## Agent 应用

`AgentAppController` 提供应用 CRUD，并允许应用关联知识库和 Skill：

- `PUT /api/apps/{appId}/knowledge-bases`：更新应用关联知识库。
- `PUT /api/apps/{appId}/skills`：更新应用关联 Skill。

用户选择应用后，会话准备阶段会加载应用配置，影响知识库增强、工具描述和 Skill prompt。

## Skill 启用

会话也维护自己的 `enabledSkillIds`。前端可查看当前会话可用 Skill，并启用或禁用：

- `GET /api/sessions/{id}/skills`。
- `GET /api/sessions/{id}/skills/available`。
- `POST /api/sessions/{id}/skills/{skillId}/enable`。
- `POST /api/sessions/{id}/skills/{skillId}/disable`。

启用 Skill 后，`AgentSkillRuntimeService` 会在系统提示中暴露 Skill 摘要，并在创建/恢复 Sandbox 时同步启用 Skill 文件。

## 前端体验

Chat 页面支持会话搜索、会话选择、批量删除、删除后恢复当前会话选择、上传文件、SSE 流式展示和右侧工具 dock。会话切换会重置沙箱视图 URL，并在需要时重新获取 view-url。

## 相关页面

[[创建会话并完成一次对话]] · [[Agent 编排模块]] · [[Skill 系统模块]] · [[前端模块]]