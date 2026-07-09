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
updated: 2026-07-09
---

# 用户 Sandbox 创建与恢复

## 1. 新建或复用

入口方法：`SandboxServiceImpl.createSandbox(sessionId)`

### 1.1 复用检查

1. 通过会话解析 `userId`，解析失败跳过创建。
2. 查找 `userSandboxMap.get(userId)`。
3. 如果已有沙箱，通过 `findEndpointForUser` 三层查找 endpoint（AioSandboxStore → sandboxAgents → 数据库）。
4. endpoint 存在则做健康检查（`isSandboxHealthy`，5 秒超时）。
5. 健康则关联会话、初始化目录、直接返回。
6. 不健康则软删除数据库记录、清理内存映射，进入重建。

### 1.2 创建新沙箱

1. 进入 `creationLocks` 双重检查锁（`synchronized`），防止同一用户并发创建。
2. 构造 `SandboxAgent`，设置镜像、超时和 entrypoint（AIO 用 `/opt/gem/run.sh`）。
3. 等待 AIO 服务就绪（`initAioContext`，`waitForReady` 轮询）。
4. 初始化 AIO 目录（`initAioDirectories`）：创建 7 个工作目录 + README + 工具脚本 + Browser Agent 运行时。
5. 迁移旧知识库文件（`KnowledgeFileMigrationService.migrateUser`）。
6. 恢复用户工作区（`FileSyncService.syncUserWorkspace`）。
7. 同步已启用技能（`syncAllEnabledSkills`）。
8. 持久化 `UserSandboxEntity`（sandboxId + endpoint）。
9. 注册到 `AioSandboxStore`（用户级 key `__user_{userId}`）。

### 1.3 AIO 初始化重试

`execShellWithRetry` 对关键命令最多重试 3 次（间隔 `500ms * attempt`），仅重试连接中止等瞬时失败。目录创建失败抛异常，README 和 Browser Agent 失败只记日志。

## 2. 应用重启恢复

入口方法：`SandboxServiceImpl.restoreUserSandboxMap` 和 `AioSandboxStore.restore`（`@PostConstruct`）

1. 查询 `userSandboxRepository.findByDeletedFalse()`。
2. 对每条记录做健康检查。
3. 健康的记录恢复到内存映射，注册到 `AioSandboxStore`。
4. 不健康的记录跳过，不写入内存映射，让后续请求进入重建路径。

关键约束：不恢复 `deleted=true` 的记录，也不恢复健康检查失败的 endpoint。后端刚启动时 AIO 端口可能尚未恢复，健康检查失败是预期行为。

## 3. endpoint 查询

| 方法 | 入参 | 用途 | 返回 |
| --- | --- | --- | --- |
| `getAioClient(sessionId)` | sessionId | 后端工具使用 | `AioClient`，无沙箱抛异常 |
| `getAioEndpoint(sessionId)` | sessionId | 后端内部使用 | endpoint 字符串 |
| `getAioEndpointForUser(userId)` | userId | 沙箱视图代理使用 | 当前最新 endpoint |
| `findEndpointForUser(userId, sandboxId)` | userId + sandboxId | 内部查找 | endpoint 或 null |

`findEndpointForUser` 三层查找带回填：找到后回填到上层缓存，避免后续重复查找。

## 4. 沙箱视图 token

`SandboxViewTokenService` 签发短期 token：

1. `SecureRandom` 生成 24 字节随机 token，Base64URL 编码。
2. token 绑定 `userId` 和 30 分钟过期时间。
3. 存入内存映射 `targets`。
4. 解析时检查过期，过期的 token 清理并返回空。

token 只保存 `userId`，不保存 endpoint。沙箱重建或端口变化后，旧 token 仍会代理到当前用户最新 endpoint，而不是固定旧地址。

## 5. 重置

`resetSandbox(sessionId)` 的流程：

1. 解析 userId，获取旧 sandboxId。
2. 断开用户与旧沙箱的运行时绑定（`detachUserSandbox`）。
3. 软删除用户沙箱记录（`softDeleteUserSandboxRecord`）。
4. 清理会话表中的 sandboxId（`clearUserSessionSandboxRecords`）。
5. 调用 `createSandbox` 重建。

旧沙箱关闭失败不阻断 reset。

## 6. 关键约束

- 会话关闭不销毁 Sandbox（沙箱是用户级资源）。
- endpoint 是动态内部地址，前端必须走同源代理 `/sandbox-view/{token}/...`。
- 文件恢复失败会影响工作区完整性，部分失败只记日志不阻断。
- 沙箱视图 token 存在内存中，服务重启后需要重新签发。
- 双重检查锁在 `finally` 中移除，避免内存泄漏。

## 7. 相关页面

[[Sandbox 模块]] · [[AIO Sandbox 客户端]] · [[文件与工作区模块]] · [[文件上传与预览]]