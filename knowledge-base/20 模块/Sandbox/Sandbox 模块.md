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
  - src/main/java/com/example/sandbox/web/config/AgentConfigProperties.java
  - src/main/resources/application.yml
updated: 2026-07-09
---

# Sandbox 模块

## 1. 模块概览

本文说明用户级 AIO Sandbox 的创建、复用、恢复、重建和同源视图代理的完整链路。

当前实现里需要特别注意七点：

- 沙箱是用户级资源：一个用户永久持有一个 AIO Sandbox，所有会话共享。`sessionId` 只用于解析 `userId`，AIO endpoint 的缓存 key 是用户级 `__user_{userId}`。
- 新会话优先复用健康沙箱；不健康时软删除数据库记录并重建。
- 启动恢复只恢复健康且未删除的记录，不可达的 endpoint 会被跳过，让后续请求正常进入重建路径。
- [[#AioSandboxStore]] 是纯内存缓存，重启即失效，数据库 `UserSandboxEntity` 才是持久来源。
- 同源视图代理不暴露 AIO endpoint，token 只保存 `userId`，每次请求按用户取最新 endpoint，适配沙箱重建和端口变化。
- 代理强制使用 HTTP/1.1，因为默认 HTTP/2 会发起 h2c 升级，沙箱容器内的 nginx 不支持，转发给 code-server 后端会触发 502。
- 创建沙箱会初始化目录、上传工具脚本、安装 Browser Agent 运行时、迁移旧知识库文件、恢复用户工作区和同步已启用技能。

## 2. 适用范围

### 2.1 本文覆盖

- `SandboxServiceImpl` 的创建、复用、重置、销毁和启动恢复。
- `AioSandboxStore` 的内存映射注册、查找和恢复。
- `SandboxViewTokenService` 的 token 签发和解析。
- `SandboxViewProxyController` 的同源 HTTP 代理。
- `SandboxViewWebSocketProxyHandler` 的 WebSocket 代理。
- AIO 沙箱目录结构、工具脚本和 Browser Agent 运行时初始化。
- 用户工作区恢复和技能同步。
- 沙箱相关配置的来源和默认值。

### 2.2 本文不覆盖

- AIO Sandbox REST 契约细节，见 [[AIO Sandbox REST 契约]]。
- AIO 客户端实现，见 [[AIO Sandbox 客户端]]。
- 知识库文件迁移逻辑，见 [[文档摄取与切片]]。
- 前端如何使用视图 URL，见 [[前端模块]]。

## 3. 当前配置总览

| 配置项 | 当前值 | 环境变量 | 说明 |
| --- | --- | --- | --- |
| `agent.sandbox.domain` | `localhost:8080` | `SANDBOX_DOMAIN` | OpenSandbox 平台地址 |
| `agent.sandbox.image` | `agent-infra/sandbox-office:latest` | `SANDBOX_IMAGE` | 默认沙箱镜像；代码默认值不同，以 yml 为准 |
| `agent.sandbox.timeout` | `PT30M` | 无 | 创建请求中的默认超时配置 |
| `agent.sandbox.ready-timeout` | `PT180S` | 无 | 等待沙箱就绪超时；yml 180s，代码默认 120s |
| `agent.sandbox.sandbox-timeout` | `P1D` | `SANDBOX_TIMEOUT` | 沙箱存活时间；yml P1D，代码默认 P7D |

注意：代码里 `AgentConfigProperties.Sandbox` 的默认值和 `application.yml` 的覆盖值不完全一致（image、ready-timeout、sandbox-timeout 都有差异），以 yml 为准。`isCurrentImageAio` 通过镜像名是否包含 `agent-infra/sandbox` 或 `all-in-one-sandbox` 判断是否走 AIO 路径。

### 3.1 内存映射与持久化

| 数据 | 含义 | 存储位置 | 重启是否保留 |
| --- | --- | --- | --- |
| `userSandboxMap` | userId → sandboxId | 内存 | 否 |
| `sandboxUserMap` | sandboxId → userId | 内存 | 否 |
| `AioSandboxStore.sessionEndpoints` | 用户级 key `__user_{userId}` → endpoint | 内存 | 否 |
| `UserSandboxEntity` | 用户当前 sandboxId 和 endpoint | MySQL | 是 |
| `creationLocks` | userId → 锁对象 | 内存 | 否 |

## 4. 创建与复用流程

入口方法：`SandboxServiceImpl.createSandbox`

### 4.1 主流程

1. 从会话解析 `userId`，解析失败直接跳过创建。
2. 查找用户已有沙箱绑定 `userSandboxMap.get(userId)`。
3. 如果已有沙箱，走复用检查（见 4.2）。
4. 如果没有或复用失败，进入创建锁 `creationLocks`，双重检查后创建新沙箱。
5. 创建 `SandboxAgent`，设置镜像、超时和 entrypoint（AIO 用 `/opt/gem/run.sh`）。
6. 等待 AIO 服务就绪（`initAioContext`），超时则抛 `IllegalStateException`。
7. 初始化 AIO 目录结构（`initAioDirectories`）。
8. 迁移旧知识库文件并恢复用户工作区（`restoreUserWorkspace`）。
9. 同步当前会话启用的技能（`syncAllEnabledSkills`）。
10. 持久化用户沙箱信息到 `UserSandboxEntity`。

### 4.2 复用检查

当用户已有沙箱时：

1. 通过 `findEndpointForUser` 三层查找 endpoint（见 4.3）。
2. 如果 endpoint 存在，用 `isSandboxHealthy` 做快速健康检查（5 秒超时）。
3. 健康则关联会话到沙箱、初始化目录、直接返回。
4. 不健康则调用 `cleanupUnhealthySandbox` 软删除数据库记录、清理内存映射、清理会话表中的 sandboxId。
5. endpoint 找不到时，沙箱可能不健康，进入重建路径。

### 4.3 三层 endpoint 查找

`findEndpointForUser(userId, sandboxId)` 的查找顺序：

1. `AioSandboxStore` 内存查找 `__user_{userId}`。
2. `sandboxAgents` 内存查找 `SandboxAgent`（沙箱刚创建时在这里）。
3. `UserSandboxRepository` 数据库查找（回退路径，应用重启后内存为空时使用）。

任何一层找到 endpoint 都会回填到上层缓存，避免后续重复查找。三层都找不到返回 `null`，触发重建。

### 4.4 双重检查锁

创建沙箱使用 `creationLocks.computeIfAbsent("user:" + userId)` 加 `synchronized` 锁，防止同一用户并发创建多个沙箱。锁内再次检查 `userSandboxMap`，避免等待期间其他线程已创建。锁在 `finally` 中移除，避免内存泄漏。

## 5. 启动恢复

入口方法：`SandboxServiceImpl.restoreUserSandboxMap`（`@PostConstruct`）和 `AioSandboxStore.restore`（`@PostConstruct`）

两个恢复方法在应用启动时各自执行：

1. 查询 `userSandboxRepository.findByDeletedFalse()`。
2. 对每条记录做健康检查 `isSandboxHealthy` / `checkHealth`。
3. 健康的记录恢复到内存映射，注册到 `AioSandboxStore`。
4. 不健康的记录跳过，不写入内存映射，让后续请求正常进入重建路径。

关键约束：

- 不恢复 `deleted=true` 的记录，也不恢复健康检查失败的 endpoint。
- 后端刚启动时 AIO 端口可能尚未恢复，健康检查失败是预期行为，不视为错误。
- 恢复失败只记日志，不阻断应用启动。

## 6. 重置与销毁

### 6.1 重置沙箱

入口方法：`SandboxServiceImpl.resetSandbox`

1. 解析用户 ID，失败抛 `SessionNotFoundException`。
2. 获取旧 sandboxId（内存优先，回退数据库）。
3. 断开用户与旧沙箱的运行时绑定（`detachUserSandbox`）：关闭旧 `SandboxAgent`，失败不阻断 reset。
4. 软删除用户沙箱记录（`softDeleteUserSandboxRecord`）。
5. 清理会话表中的 sandboxId（`clearUserSessionSandboxRecords`）。
6. 调用 `createSandbox` 重建。

### 6.2 销毁沙箱

`destroyUserSandbox(userId)` 关闭旧 `SandboxAgent` 并移除内存映射。`removeSandbox(sessionId)` 清理会话级映射和数据库记录，但不动用户级沙箱绑定。删除单个会话时只移除会话数据，不销毁用户沙箱。

## 7. AIO 目录初始化

入口方法：`SandboxServiceImpl.initAioDirectories`

### 7.1 目录结构

```text
/home/gem/uploads
/home/gem/workspace
/home/gem/output
/home/gem/skills
/home/gem/temp
/home/gem/tools
/home/gem/knowledge
/home/gem/.runtime/browser-agent
```

前七个目录由 `AIO_WORKSPACE_DIRS_COMMAND` 一次创建，是工作空间的硬依赖。`.runtime/browser-agent` 是隐藏运行时目录，不属于用户工作空间，不写入 README，不参与用户文件恢复。

| 目录 | 用途 | 创建失败影响 |
| --- | --- | --- |
| `/home/gem/uploads` | 用户上传文件 | 后续文件恢复依赖此目录 |
| `/home/gem/workspace` | Agent 工作目录 | Agent 无法工作 |
| `/home/gem/output` | 生成产物 | 结果无处存放 |
| `/home/gem/skills` | Skill 文件 | 技能同步失败 |
| `/home/gem/temp` | 临时文件 | 临时操作失败 |
| `/home/gem/tools` | 工具脚本 | `file_parser.py` 无法上传 |
| `/home/gem/knowledge` | 知识库原始文件和预览 | 文件预览不可用 |
| `/home/gem/.runtime/browser-agent` | Browser Agent 运行时 | 仅禁用语义浏览器能力，不阻断 |

### 7.2 工具脚本与运行时

- `file_parser.py` 从 classpath `/tools/file_parser.py` 读取并写入 `/home/gem/tools/`，写入失败只记日志。
- README.md 写入 `/home/gem/`，失败只记日志，不阻断。
- Browser Agent 运行时安装在 `/home/gem/.runtime/browser-agent`，包含 `browser-agent.mjs`、`package.json` 和 `npm install playwright-core`。安装或验证失败只禁用浏览器能力，不阻断其他能力。

### 7.3 AIO 初始化重试

`execShellWithRetry` 对 AIO Shell 命令最多重试 3 次（`AIO_INIT_RETRY_ATTEMPTS`），重试间隔 `500ms * attempt`。仅重试连接中止、响应过早关闭等瞬时调用失败；命令返回非零退出码也会重试，但耗尽后抛出异常，让关键初始化失败可见。

## 8. 同源沙箱视图

### 8.1 Token 签发

入口方法：`SandboxViewTokenService.issue`

1. 前端调用 `GET /api/sessions/{id}/aio/view-url`。
2. `SandboxViewTokenService` 用 `SecureRandom` 生成 24 字节随机 token，Base64URL 编码。
3. token 绑定 `userId` 和 30 分钟过期时间（`DEFAULT_TTL`）。
4. 存入内存映射 `targets`。
5. 前端拿到 `/sandbox-view/{token}/...` 路径，iframe 直接访问。

token 只保存 `userId`，不保存 endpoint。解析时检查过期，过期的 token 会被清理并返回空。

### 8.2 HTTP 代理

入口方法：`SandboxViewProxyController.proxy`

```text
/sandbox-view/{token}  和  /sandbox-view/{token}/**
```

支持 GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS。流程：

1. 解析 token，过期或不存在返回 404。
2. 调用 `getAioEndpointForUser(userId)` 获取当前最新 endpoint，用户无沙箱返回 404。
3. `SandboxViewProxySupport.upstreamPath` 还原真实上游路径。
4. 构造上游请求，跳过 hop-by-hop 请求头（host、connection、content-length、transfer-encoding、upgrade、forwarded、x-forwarded-*）。
5. 注入 `X-Forwarded-Host`、`X-Forwarded-Proto`、`X-Forwarded-Prefix`。
6. 用 `HttpClient` 发送上游请求，强制 HTTP/1.1，不跟随重定向（`NEVER`）。
7. 写回响应，跳过 hop-by-hop 响应头，`Location` 头由 `SandboxViewProxySupport.rewriteLocation` 改写回同源路径。
8. 默认加 `Cache-Control: no-store`。

### 8.3 关键设计约束

- 强制 HTTP/1.1：默认 HTTP/2 在明文连接上发起 h2c 升级，附带 `Upgrade: h2c` 头。沙箱容器内 nginx 不支持 h2c，转发给 code-server 后端会触发 502。
- 不跟随重定向：上游 302 的 Location 如果指向 `127.0.0.1:port`，浏览器会直接跳过去，绕过代理。`rewriteLocation` 把它改写回 `/sandbox-view/{token}/...` 同源路径。
- WebSocket 请求（带 `Sec-WebSocket-Key` 头）由 `SandboxViewWebSocketProxyHandler` 处理，不走 HTTP 代理。
- `forwardedHost` 优先使用边界反向代理写入的 `X-Forwarded-Host`，回退到浏览器请求的 Host。云端 TLS 终止在 Nginx/负载均衡层，后端看到的 scheme 可能仍是 http。

## 9. 用户工作区恢复与技能同步

### 9.1 工作区恢复

入口方法：`SandboxServiceImpl.restoreUserWorkspace`

1. `KnowledgeFileMigrationService.migrateUser(userId)` 把历史旧路径知识库文件规范化到当前用户级存储路径。
2. `FileSyncService.syncUserWorkspace(userId, client)` 把用户工作区文件同步到新沙箱。
3. 部分失败只记日志，不阻断沙箱创建。

### 9.2 技能同步

入口方法：`SandboxServiceImpl.syncAllEnabledSkills`

1. 从会话读取已启用技能 ID 集合。
2. 逐个获取技能，只推送有 `localPath` 的技能（本地有副本的）。
3. `FileSyncService.syncSkill` 推送技能文件到 `/home/gem/skills/`。
4. 本地无副本的技能跳过，不视为失败。

### 9.3 续期任务

`renewAllSandboxes` 是 `@Scheduled(fixedRate = 20 * 60 * 1000)` 的定时任务。AIO 模式下沙箱由平台管理，不需要续期，直接返回。非 AIO 模式遍历所有用户沙箱续期，不健康的沙箱会被移除映射。

## 10. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| 新会话创建很慢 | 镜像拉取、AIO 健康探针、ready-timeout、Browser Agent npm install | npm install 可能很慢，网络问题会降级跳过 |
| 复用到了坏沙箱 | `UserSandboxEntity` 记录、endpoint 健康检查、`cleanupUnhealthySandbox` 日志 | 健康检查 5 秒超时，端口刚恢复可能误判 |
| 视图 502 | token 是否过期、endpoint 是否最新、HTTP/1.1 是否被改 | h2c 升级会导致 502，检查 HttpClient 版本配置 |
| code-server 502 | nginx 是否支持 h2c、HttpClient 是否强制 HTTP/1.1 | 强制 HTTP/1.1 是为了规避此问题 |
| Web Terminal 断连 | WebSocket 代理、Upgrade 路径、token 有效期 | WebSocket 走独立 Handler，不走 HTTP 代理 |
| 知识库文件预览缺失 | `/home/gem/knowledge` 是否同步、RAG 文档 `sandboxSynced`、`restoreUserWorkspace` 日志 | 工作区恢复部分失败只记日志 |
| 技能未同步 | 技能是否有 `localPath`、`syncAllEnabledSkills` 日志、会话启用技能集合 | 本地无副本的技能会被跳过 |
| 重启后沙箱丢失 | `UserSandboxEntity` 是否 deleted=false、启动恢复日志的 unhealthy 数 | 不健康的不恢复，需要等下次请求触发重建 |
| 重置后旧沙箱未释放 | `detachUserSandbox` 日志、`agent.close()` 是否成功 | 旧沙箱关闭失败不阻断 reset，但可能残留 |

## 11. 扩展建议

1. 健康检查当前是单次 5 秒超时，如果 AIO 端口恢复较慢可能误判为不健康并触发重建。可以增加少量重试或延长超时，但要注意不能让创建链路变得太慢。
2. `creationLocks` 是 `ConcurrentHashMap` + `synchronized`，如果同一用户高频创建会串行等待。当前场景下可接受，如果出现性能问题可以考虑改用 `StampedLock`。
3. Browser Agent 运行时安装失败只记日志，如果需要更可靠的浏览器能力，可以加重试或预装到镜像。
4. token 纯内存存储，重启即失效，重启后打开的 iframe 会 404。如果需要持久化，可以改用 Redis，但当前场景下 30 分钟过期足够。
5. `findEndpointForUser` 的三层查找有回填逻辑，排查 endpoint 不一致时要按内存→内存→数据库顺序核对。

## 12. 术语速查

本节用于 Obsidian 悬浮预览。阅读正文时，把鼠标停在带链接的术语上，可以快速看到这里的释义。

### AIO Sandbox

当前项目使用的 All-in-One 沙箱运行环境，提供 shell、文件、浏览器、noVNC、code-server 等能力。是否走 AIO 路径由镜像名是否包含 `agent-infra/sandbox` 或 `all-in-one-sandbox` 判断。

### endpoint

AIO Sandbox 暴露给后端调用的地址（形如 `127.0.0.1:port`）。它可能随沙箱重建而变化，所以视图代理每次按用户取最新值，而不是在 token 里固定。

### AioSandboxStore

`AioSandboxStore` 是 AIO endpoint 的纯内存缓存，维护用户级 key `__user_{userId}` 到 endpoint 的映射。应用启动时从 `UserSandboxEntity` 恢复健康记录，重启即失效。三层 endpoint 查找的第一层。

### UserSandboxEntity

`UserSandboxEntity` 是用户级沙箱绑定的持久化实体，记录用户当前 sandboxId 和 AIO endpoint。它是重启后恢复的来源，不健康记录会被软删除（`deleted=true`）。

### 同源代理

浏览器访问的是 WebAgent 后端路径 `/sandbox-view/{token}/...`，后端再转发到 AIO endpoint。这样避免暴露内部地址，也能统一处理 WebSocket 和 Location 改写。

### SandboxViewTokenService

`SandboxViewTokenService` 负责签发和解析沙箱视图 token。token 用 SecureRandom 生成，只绑定 userId 和 30 分钟过期时间，不固定 endpoint。过期或不存在返回空，代理返回 404。

### SandboxViewProxyController

`SandboxViewProxyController` 是同源 HTTP 代理控制器。它解析 token、按用户取最新 endpoint、构造上游请求、跳过 hop-by-hop 头、强制 HTTP/1.1、不跟随重定向、改写 Location 头回同源路径。

### SandboxViewProxySupport

`SandboxViewProxySupport` 是代理工具类，负责上游路径还原（`upstreamPath`）和 Location 头改写（`rewriteLocation`），避免浏览器跳到内部 `127.0.0.1:port`。

### SandboxAgent

`SandboxAgent` 是沙箱代理对象，封装 OpenSandbox 的创建、健康检查、续期和关闭。非 AIO 模式下由 `SandboxServiceImpl` 管理；AIO 模式下主要用于创建阶段，endpoint 交给 `AioSandboxStore` 缓存。

### creationLocks

`creationLocks` 是用户级创建锁映射（`Map<String, Object>`），用 `computeIfAbsent` + `synchronized` 防止同一用户并发创建多个沙箱。锁在 `finally` 中移除，避免内存泄漏。

### restoreUserWorkspace

`restoreUserWorkspace` 在新沙箱创建时恢复用户工作区，先迁移旧知识库文件到规范路径，再通过 `FileSyncService` 把用户文件同步到沙箱。部分失败只记日志。

### syncAllEnabledSkills

`syncAllEnabledSkills` 在沙箱创建时把所有已启用且有本地副本的技能推送到 `/home/gem/skills/`。本地无副本的技能跳过，不视为失败。

### isCurrentImageAio

`isCurrentImageAio` 通过沙箱镜像名是否包含 `agent-infra/sandbox` 或 `all-in-one-sandbox` 判断是否走 AIO 路径。当前项目统一使用 AIO 镜像，`isAioSandbox` 已废弃且恒返回 true。

## 13. 相关页面

[[用户 Sandbox 创建与恢复]] · [[AIO Sandbox 客户端]] · [[AIO Sandbox REST 契约]] · [[文件与工作区模块]] · [[前端模块]]