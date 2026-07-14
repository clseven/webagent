---
project: webagent-clean
type: api
status: verified
area:
  - api
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/controller
updated: 2026-07-06
---

# 后端 HTTP API

## 认证

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/auth/me`

## 会话与对话

- `GET /api/sessions`
- `POST /api/sessions`
- `DELETE /api/sessions/{id}`
- `DELETE /api/sessions/batch`
- `GET /api/sessions/{id}`
- `POST /api/sessions/{id}/chat`，同步对话。请求体含 `message`（必填）、`searchEnabled`（默认 false）、`planningEnabled`（默认 true）、`knowledgeEnabled`（默认 true）。
- `GET /api/sessions/{id}/chat/stream`，SSE 流式对话。query 参数：`message`（必填）、`searchEnabled=false`、`planningEnabled=true`、`knowledgeEnabled=true`。
- `GET /api/sessions/{id}/history`
- `GET /api/sessions/{id}/skills`
- `GET /api/sessions/{id}/skills/available`
- `POST /api/sessions/{id}/skills/{skillId}/enable`
- `POST /api/sessions/{id}/skills/{skillId}/disable`

## 沙箱与工作区

- `POST /api/sessions/{id}/execute`
- `POST /api/sessions/{id}/files/read`
- `POST /api/sessions/{id}/files/write`
- `GET /api/sessions/{id}/aio/endpoint`，后端内部调试/兼容用途，不建议给 iframe 直连。
- `GET /api/sessions/{id}/aio/view-url`，返回 `/sandbox-view/{token}/` 同源代理路径。
- `POST /api/sessions/{id}/workspace/refresh`
- `GET /api/sessions/{id}/files/preview?path=...`
- `GET /api/sessions/{id}/files/download?path=...`

## 沙箱视图代理

- `/sandbox-view/{token}` 和 `/sandbox-view/{token}/**`：支持 GET、POST、PUT、DELETE、PATCH、HEAD、OPTIONS。
- 同一路径也注册 WebSocket Handler，用于 code-server、noVNC、Web Terminal 的 Upgrade 请求。

该代理不属于 `/api`，面向浏览器 iframe 使用。

## 普通上传文件

- `POST /api/files/upload`
- `PUT /api/files/upload/replace`
- `GET /api/files/download/{sessionId}/{filename}`
- `DELETE /api/files/{sessionId}/{filename}`
- `GET /api/files/path/{sessionId}`

当前这些接口按会话所属 userId 访问用户级 uploads 目录。

## Agent 应用

- `POST /api/apps`
- `GET /api/apps`
- `GET /api/apps/{appId}`
- `PUT /api/apps/{appId}`
- `DELETE /api/apps/{appId}`
- `PUT /api/apps/{appId}/knowledge-bases`
- `PUT /api/apps/{appId}/skills`

## Skill

- `GET /api/skills`
- `GET /api/skills/{id}`
- `POST /api/skills/set-root`

## RAG

- `POST /api/rag/bases`
- `GET /api/rag/bases`
- `GET /api/rag/bases/{kbId}`
- `PUT /api/rag/bases/{kbId}`
- `DELETE /api/rag/bases/{kbId}`
- `POST /api/rag/bases/{kbId}/documents/upload`
- `GET /api/rag/bases/{kbId}/documents`
- `GET /api/rag/document/{docId}`
- `GET /api/rag/document/{docId}/chunks`
- `GET /api/rag/document/{docId}/file`
- `PUT /api/rag/document/{docId}/replace`
- `DELETE /api/rag/document/{docId}`
- `POST /api/rag/bases/{kbId}/search`

## Token 统计

- `GET /api/token-stats/summary`
- `GET /api/token-stats/daily`
- `GET /api/token-stats/by-model`

## 相关页面

[[系统运行方式]] · [[前端模块]] · [[核心数据模型]] · [[运行配置]]