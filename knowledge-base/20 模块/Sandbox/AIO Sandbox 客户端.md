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
updated: 2026-07-06
---

# AIO Sandbox 客户端

## 职责

AIO Sandbox 客户端封装后端到 Sandbox 的 REST 调用，向上提供 shell、文件、浏览器和健康检查能力。所有 HTTP 契约以仓库内 `docs/sandbox-api/openapi.json` 为准。

## 使用位置

- [[Sandbox 模块]]：创建后初始化目录、检查健康、恢复工作区和同步 Skill。
- [[文件与工作区模块]]：上传、读取、预览和下载沙箱文件。
- [[工具系统模块]]：Shell、文件、浏览器和文档工具通过 AIO 执行动作。
- MCP shell transport：`AioShellTransport` 在沙箱内启动 `supergateway` 并通过 curl/SSE 转发 MCP JSON-RPC。

## endpoint 管理

AIO endpoint 是动态的后端内部地址。当前规则：

- endpoint 持久化到 `UserSandboxEntity`。
- 内存缓存使用用户级 key。
- `getAioEndpoint(sessionId)` 只服务后端内部使用。
- 前端 iframe 不直接使用 endpoint，而是使用 [[Sandbox 模块]] 的同源代理路径。

## 健康检查

当前健康探针会访问 AIO shell 会话列表接口。复用用户沙箱前必须确认 endpoint 可用，不健康时触发重建和映射清理。

## 契约维护要求

修改 AIO HTTP 集成前必须先读：

- `docs/sandbox-api/README.md`
- `docs/sandbox-api/integration-map.md`
- `docs/sandbox-api/openapi.json`

如果代码行为和 OpenAPI 冲突，以 OpenAPI 为权威契约，然后修正代码或文档。

## 相关页面

[[Sandbox 模块]] · [[用户 Sandbox 创建与恢复]] · [[AIO Sandbox REST 契约]]