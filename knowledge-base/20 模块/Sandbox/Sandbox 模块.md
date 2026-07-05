---
project: webagent-clean
type: module
status: verified
area:
  - sandbox
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/AioSandboxStore.java
  - src/main/java/com/example/sandbox/web/service/impl/SandboxViewTokenService.java
  - src/main/java/com/example/sandbox/web/controller/SandboxViewProxyController.java
  - src/main/java/com/example/sandbox/web/controller/SandboxViewWebSocketProxyHandler.java
updated: 2026-07-06
---

# Sandbox 模块

## 所有权模型

当前实现采用“一个用户一个长期 AIO Sandbox，多个会话共享”的模型：

- `userSandboxMap`：用户 ID 到 Sandbox ID。
- `sandboxUserMap`：Sandbox ID 到用户 ID。
- `UserSandboxEntity`：持久化用户当前 Sandbox ID 和动态 AIO endpoint。
- `AioSandboxStore`：缓存 AIO endpoint；当前关键 key 是用户级 `__user_{userId}`。

`sessionId` 只用于解析用户和当前会话，不再作为 AIO endpoint 的长期缓存 key。这是沙箱视图代理和多会话复用的基础。

## 创建与复用

`createSandbox(sessionId)` 的核心流程：

1. 从会话解析 userId。
2. 优先查找用户已有 Sandbox。
3. 如果 endpoint 存在且健康，关联当前会话上下文并复用。
4. 如果不健康，清理映射并重建。
5. 通过 OpenSandbox 创建 AIO Sandbox。
6. 初始化 `/home/gem` 目录、工具脚本和浏览器运行时。
7. 迁移旧知识库文件并恢复用户工作区。
8. 同步当前会话启用的 Skill 文件。

## 同源沙箱视图

新增沙箱视图链路：

1. 前端调用 `GET /api/sessions/{id}/aio/view-url`。
2. `SandboxViewTokenService` 签发 30 分钟短期 token，只保存 userId，不保存 endpoint。
3. 前端 iframe 访问 `/sandbox-view/{token}/...`。
4. `SandboxViewProxyController` 每次请求按 userId 获取当前最新 endpoint，再转发 HTTP。
5. `SandboxViewWebSocketProxyHandler` 处理同一路径下的 WebSocket Upgrade，桥接 code-server、noVNC、Web Terminal。
6. `SandboxViewProxySupport` 负责路径还原和 Location 头改写，避免浏览器跳到内部 `127.0.0.1:port`。

## 目录结构

Sandbox 初始化会创建：

```text
/home/gem/uploads
/home/gem/workspace
/home/gem/output
/home/gem/skills
/home/gem/temp
/home/gem/tools
/home/gem/knowledge
```

这些目录分别对应上传文件、工作目录、生成产物、Skill 文件、临时文件、工具脚本和知识库文件。

## 相关页面

[[用户 Sandbox 创建与恢复]] · [[AIO Sandbox 客户端]] · [[文件与工作区模块]] · [[前端模块]]