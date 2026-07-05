---
project: webagent-clean
type: flow
status: verified
area:
  - sandbox
  - files
  - skills
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java
  - src/main/java/com/example/sandbox/web/service/impl/AioSandboxStore.java
  - src/main/java/com/example/sandbox/web/service/impl/FileSyncService.java
  - src/main/java/com/example/sandbox/web/service/impl/SandboxViewTokenService.java
updated: 2026-07-06
---

# 用户 Sandbox 创建与恢复

## 新建或复用

1. 会话创建或对话执行时，系统调用 `createSandbox(sessionId)`。
2. `SandboxServiceImpl` 通过会话找到 userId。
3. 如果 `userSandboxMap` 或数据库中有该用户 Sandbox，优先获取 endpoint 并做健康检查。
4. 健康则复用，并把 endpoint 注册到用户级 `AioSandboxStore` key。
5. 不健康则清理用户映射和 store，重新创建 Sandbox。
6. 新建后持久化 `UserSandboxEntity`，初始化 AIO 目录和工具脚本。
7. 迁移旧知识库文件，恢复用户工作区，同步启用 Skill。

## 应用重启恢复

应用启动时从 `user_sandbox` 恢复用户和 Sandbox 绑定。恢复时会检查 endpoint 是否健康；健康记录重新注册到用户级 key，不健康记录会跳过或等待后续重建。

## endpoint 查询

- `getAioClient(sessionId)`：后端工具使用，先通过 sessionId 解析 userId，再查用户 endpoint。
- `getAioEndpoint(sessionId)`：返回后端内部 endpoint，不给浏览器 iframe 直接使用。
- `getAioEndpointForUser(userId)`：沙箱视图代理使用，每次请求动态获取最新 endpoint。

## 沙箱视图 token

`SandboxViewTokenService` 签发短期 token，token 只绑定 userId。这样沙箱重建或端口变化后，旧 token 仍会代理到当前用户最新 endpoint，而不是固定旧地址。

## 关键约束

- 会话关闭不销毁 Sandbox。
- endpoint 是动态内部地址，前端必须走同源代理。
- 文件恢复失败会影响工作区完整性，应通过日志和测试排查。
- 当前沙箱视图 token 存在内存中，服务重启后需要重新签发。

## 相关页面

[[Sandbox 模块]] · [[AIO Sandbox 客户端]] · [[文件与工作区模块]]