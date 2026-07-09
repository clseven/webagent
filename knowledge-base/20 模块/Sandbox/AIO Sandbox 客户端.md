---
project: webagent-clean
type: module
status: verified
area:
  - sandbox
  - external-contract
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/aio
  - src/main/java/com/example/sandbox/web/service/impl/SandboxClientFactory.java
  - docs/sandbox-api/openapi.json
  - docs/sandbox-api/integration-map.md
updated: 2026-07-09
---

# AIO Sandbox 客户端

## 1. 模块概览

本文说明 AIO Sandbox 客户端的职责、使用位置、endpoint 管理和契约维护要求。

AIO Sandbox 客户端封装后端到 Sandbox 的 REST 调用，向上提供 shell、文件、浏览器和健康检查能力。所有 HTTP 契约以仓库内 `docs/sandbox-api/openapi.json` 为准。

## 2. 客户端能力

| 能力 | 方法 | 说明 |
| --- | --- | --- |
| Shell 执行 | `client.shell().exec(command)` | 在沙箱内执行命令，返回 ShellExecResult |
| Shell 健康检查 | `client.shell().quickHealthCheck()` | 快速检查（5 秒超时） |
| Shell 就绪等待 | `client.shell().waitForReady()` | 轮询等待 Shell API 就绪 |
| 文件写入 | `client.files().writeText(path, content)` | 写入文本文件 |
| 文件读取 | `client.files().readText(path)` | 读取文本文件 |
| 文件删除 | `client.files().delete(path)` | 删除文件 |
| 环境信息 | `client.getContext()` | 获取工作区等环境信息 |

`AioClient` 构造时传入 `"http://" + endpoint`，所有操作通过 REST 调用 AIO Sandbox。

## 3. 使用位置

| 模块 | 用途 |
| --- | --- |
| [[Sandbox 模块]] | 创建后初始化目录、检查健康、恢复工作区和同步 Skill |
| [[文件与工作区模块]] | 上传、读取、预览和下载沙箱文件 |
| [[工具系统模块]] | Shell、文件、浏览器和文档工具通过 AIO 执行动作 |
| MCP shell transport | `AioShellTransport` 在沙箱内启动 `supergateway` 并通过 curl/SSE 转发 MCP JSON-RPC |
| RAG 知识库 | `syncToSandbox` 同步知识库文件、`ensureSandboxFile` 按需恢复 |

## 4. endpoint 管理

AIO endpoint 是动态的后端内部地址（形如 `127.0.0.1:port`）。当前规则：

- endpoint 持久化到 `UserSandboxEntity`（`aioEndpoint` 字段）。
- 内存缓存使用用户级 key `__user_{userId}`（`AioSandboxStore`）。
- `getAioClient(sessionId)` 先通过 sessionId 解析 userId，再查用户 endpoint。
- `getAioEndpoint(sessionId)` 返回后端内部 endpoint，不给浏览器 iframe 直接使用。
- `getAioEndpointForUser(userId)` 沙箱视图代理使用，每次请求动态获取最新 endpoint。
- `findEndpointForUser` 三层查找：AioSandboxStore → sandboxAgents → UserSandboxRepository。

## 5. 健康检查

当前健康探针访问 AIO shell 会话列表接口（`quickHealthCheck`，5 秒超时）。复用用户沙箱前必须确认 endpoint 可用：

- 健康则复用，注册到 `AioSandboxStore`。
- 不健康则软删除数据库记录、清理内存映射，触发重建。
- 启动恢复时只恢复健康且未删除的记录。

## 6. 契约维护要求

修改 AIO HTTP 集成前必须先读：

- `docs/sandbox-api/README.md`
- `docs/sandbox-api/integration-map.md`
- `docs/sandbox-api/openapi.json`

如果代码行为和 OpenAPI 冲突，以 OpenAPI 为权威契约，然后修正代码或文档。

## 7. SandboxClientFactory

`SandboxClientFactory` 是获取 AIO 客户端的工厂：

- `getAioClientByUserId(userId)` 按用户获取 AIO 客户端。
- `getAioClient(sessionId)` 按会话获取（内部先解析 userId）。
- 用户没有沙箱时返回 null（不抛异常），调用方需处理。

## 8. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| AIO 调用超时 | endpoint 是否健康、网络 | 健康检查 5 秒，响应超时 300 秒 |
| endpoint 找不到 | `findEndpointForUser` 三层查找 | 内存→SandboxAgent→数据库 |
| 用户没有沙箱 | `getAioClientByUserId` 返回 null | 调用方需处理 null 情况 |
| 契约不一致 | `openapi.json` 与代码行为 | 以 OpenAPI 为准 |

## 9. 相关页面

[[Sandbox 模块]] · [[用户 Sandbox 创建与恢复]] · [[AIO Sandbox REST 契约]] · [[文件与工作区模块]]