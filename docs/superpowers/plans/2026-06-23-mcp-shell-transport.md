# MCP Shell Transport 实现方案

> **Goal:** 实现 `AioShellTransport`，让用户在自己的沙箱内运行 stdio MCP Server，后端通过 AIO Shell API 与 supergateway 通信。

**架构不变：**

```
后端 Java                                           AIO 沙箱
McpClientManager
  ├─ StdioClientTransport (系统级, 宿主机进程)
  ├─ HttpClientStreamableHttpTransport (远程 HTTP)
  └─ AioShellTransport (新增)                      supergateway (streamableHttp :PORT)
       │ ① execAsync: 启动 supergateway               └─ MCP Server (stdio)
       │ ② exec: curl POST /mcp 发 JSON-RPC ──────→  curl → supergateway → MCP Server
       │ ③ 解析 exec output 中的 SSE → JSON-RPC ←── 
       │ ④ kill(sessionId): 关闭
       └─ AioShellApi ───────────────────────────→  AIO Shell REST API
```

---

## ⚠️ 实现前确认清单

### 1. McpClientTransport 接口签名 ✅ 已确认

```java
// McpTransport (父接口)
interface McpTransport {
    void close();
    Mono<Void> closeGracefully();
    Mono<Void> sendMessage(McpSchema.JSONRPCMessage);
    <T> T unmarshalFrom(Object, io.modelcontextprotocol.json.TypeRef<T>);
    List<String> protocolVersions();  // 有默认实现
}

// McpClientTransport extends McpTransport
interface McpClientTransport extends McpTransport {
    Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler);
    void setExceptionHandler(Consumer<Throwable> handler);
}
```

> 在本项目执行 `javap` 即可确认，Maven repo 路径取决于你的 `settings.xml`。

### 2. 沙箱内端到端验证

在正式沙箱中依次执行以下 curl 命令，验证全链路：

```bash
# Step 1: 启动 supergateway（使用 streamableHttp，端口 5174）
curl -X POST http://<aio-endpoint>/v1/shell/exec \
  -H "Content-Type: application/json" \
  -d '{
    "command": "npx -y supergateway --stdio \"npx -y @modelcontextprotocol/server-filesystem /home/gem\" --outputTransport streamableHttp --port 5174 --host 0.0.0.0",
    "async_mode": true
  }'
# 预期: {"success":true, "data":{"session_id":"...", "status":"running"}}
# 记录返回的 session_id = SID

# Step 2: 等 5 秒让 supergateway 启动，然后用 curl 发 MCP initialize
curl -X POST http://<aio-endpoint>/v1/shell/exec \
  -H "Content-Type: application/json" \
  -d '{
    "command": "curl -s http://localhost:5174/mcp -X POST -H \"Content-Type: application/json\" -H \"Accept: application/json, text/event-stream\" -d '\''{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}'\''"
  }'
# 预期: output 字段包含 "event: message" 和 "data: {...}" —— 即 MCP 响应

# Step 3: 发 tools/list
curl -X POST http://<aio-endpoint>/v1/shell/exec \
  -H "Content-Type: application/json" \
  -d '{
    "command": "curl -s http://localhost:5174/mcp -X POST -H \"Content-Type: application/json\" -H \"Accept: application/json, text/event-stream\" -d '\''{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}'\''"
  }'
# 预期: 返回 filesystem server 的工具列表

# Step 4: 关闭
curl -X POST http://<aio-endpoint>/v1/shell/kill \
  -H "Content-Type: application/json" \
  -d '{"id":"<SID>"}'
```

**验证通过标准：** Step 2 的 output 中出现 `"serverInfo"` 字段（MCP initialize 成功响应）。

如果 `npx` 首次下载慢（>30s），排查方法：
- curl 的 exec 超时是否够用（建议 60s+）
- 是否需要预装：`exec("npm install -g supergateway")`

---

## 前置确认

### ShellExecRequest API 能力 (已通过 OpenAPI 验证)

```json
// POST /v1/shell/exec 请求体
{
  "command": "string (required)",
  "id": "string (optional)",
  "exec_dir": "string (optional)",
  "async_mode": "boolean (default: false)",
  "timeout": "number (optional)"
}
```

`async_mode: true` 时命令在后台运行，立即返回 `status: "running"` 和 `session_id`。这正是启动 supergateway 需要的——**不需要 `nohup &` 之类的 workaround**。

### Session 管理策略

- supergateway 启动时：`execAsync(command)` 返回 sessionId → 存为 `supergatewaySessionId`
- 发 curl 请求时：`exec(command, null)` — sessionId 传 null，API 自动创建临时 session，curl 跑完即销毁
- 关闭时：`kill(supergatewaySessionId)`

curl 不传 sessionId 的原因是 `AioShellApi` 会在第 86 行自动缓存上次 exec 的 sessionId。如果 supergateway 的 sessionId 被缓存，后续 curl 就跑进同一个 session 了。传 `null` 强制创建新 session。

---

## 实现任务

### Task 1: AioShellApi 扩展

**文件：**
- 新建: `src/main/java/com/example/sandbox/aio/shell/model/ShellExecRequest.java`
- 修改: `src/main/java/com/example/sandbox/aio/shell/model/ShellExecResult.java`
- 修改: `src/main/java/com/example/sandbox/aio/shell/AioShellApi.java`

**ShellExecRequest.java** — 映射完整 API 请求体：
```java
public class ShellExecRequest {
    private String command;      // required
    private String id;           // optional — shell session ID
    private String execDir;      // optional — working directory
    private Boolean asyncMode;   // default false → true 时异步启动
    private Integer timeout;     // optional — max wait seconds
}
```

**ShellExecResult 改动：**
- 新增便捷方法 `getStatus()` → 委托给 `data.getStatus()`
- 新增便捷方法 `getSessionId()` → 委托给 `data.getSessionId()`

**AioShellApi 新增方法：**
```java
// 异步执行：async_mode=true，立即返回，用于启动 supergateway
public ShellExecResult execAsync(String command)

// 带超时的同步执行：用于 curl 请求（需要设置超时避免长时间挂起）
public ShellExecResult exec(String command, String sessionId, int timeoutSeconds)
```

实现细节：`execAsync` 构建 `ShellExecRequest`，设 `asyncMode=true`，序列化为 JSON 发送。`execInternal(ShellExecRequest request)` 统一处理序列化和响应解析。

**⚠️ 注意：** 新增的方法**不**在第 86 行缓存 `shellSessionId`。只有原有的 `exec(command)` 和 `exec(command, sessionId)` 保持缓存行为。避免污染全局 session 状态。

---

### Task 2: AioShellTransport — McpClientTransport 实现

**文件：**
- 新建: `src/main/java/com/example/sandbox/web/service/mcpclient/AioShellTransport.java`

**类结构：**
```java
public class AioShellTransport implements McpClientTransport {
    private final AioShellApi shellApi;
    private final McpServerConfig config;
    private final int port;
    private String supergatewaySessionId;
    private Function<McpSchema.JSONRPCMessage, Mono<Void>> inboundHandler;
    // ...
}
```

**构造参数：** `AioShellApi shellApi`, `McpServerConfig config`, `int port`

**五个接口方法：**

| 接口方法 | 签名 | 实现 |
|----------|------|------|
| `connect(handler)` | `Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>)` | ① 用 `execAsync` 启动 supergateway ② 存储 sessionId 和 handler ③ 返回 `Mono.empty()` |
| `sendMessage(msg)` | `Mono<Void> sendMessage(JSONRPCMessage)` | ① 序列化 msg → JSON ② `exec("curl -s ...")` 发请求 ③ 解析 output SSE → JSON-RPC response ④ `handler.apply(Mono.just(response)).subscribe()` 推入 SDK ⑤ 返回 `Mono.empty()` |
| `close()` | `void close()` | `shellApi.kill(supergatewaySessionId)` 同步终止 |
| `closeGracefully()` | `Mono<Void> closeGracefully()` | 同 close()，返回 `Mono.empty()` |
| `unmarshalFrom(data, typeRef)` | `<T> T unmarshalFrom(Object, TypeRef<T>)` | 委托给 `McpJsonDefaults.getMapper()` |
| `protocolVersions()` | `List<String> protocolVersions()` | 返回 `List.of("2024-11-05")`（默认实现已够用） |
| `setExceptionHandler(handler)` | `void setExceptionHandler(Consumer<Throwable>)` | 存储异常处理器，供内部错误回调 |

**SSE 响应解析逻辑（静态工具方法）：**

输入（exec output 字段）：
```
event: message
data: {"result":{...},"jsonrpc":"2.0","id":1}
```

处理：
1. 按 `\n` 分割
2. 找 `data: ` 开头的行
3. 去掉 `data: ` 前缀 → 纯 JSON
4. 反序列化为 `McpSchema.JSONRPCMessage`

**端口分配逻辑：**

```java
private static int allocatePort(AioShellApi shellApi) {
    int port = 8000;
    while (port < 8100) {
        // 用 exec 检查端口是否被占用
        ShellExecResult result = shellApi.exec(
            "ss -tlnp | grep -q ':" + port + " ' && echo occupied || echo free", 
            null);
        if (result.getOutput().trim().contains("free")) {
            return port;
        }
        port++;
    }
    throw new McpConnectionException(/* PORT_ALLOCATION_FAILED */);
}
```

**supergateway 启动命令构建：**
```java
private String buildSupergatewayCommand() {
    // npx -y supergateway
    //   --stdio "npx -y @modelcontextprotocol/server-filesystem /home/gem"
    //   --outputTransport streamableHttp
    //   --port PORT --host 0.0.0.0
    String stdioCmd = config.getCommand();
    if (!config.getArgs().isEmpty()) {
        stdioCmd += " " + String.join(" ", config.getArgs());
    }
    return String.format(
        "npx -y supergateway --stdio \"%s\" --outputTransport streamableHttp --port %d --host 0.0.0.0",
        escapeDoubleQuotes(stdioCmd), port);
}
```

**环境变量传递：** `ShellExecRequest` 没有 `env` 字段，需要通过 shell 语法在命令前注入：
```java
private String buildSupergatewayCommand() {
    StringBuilder cmd = new StringBuilder();
    // 注入环境变量: KEY1=val1 KEY2=val2 npx ...
    if (!config.getEnv().isEmpty()) {
        for (Map.Entry<String, String> e : config.getEnv().entrySet()) {
            cmd.append(e.getKey()).append("='")
               .append(e.getValue().replace("'", "'\\''")).append("' ");
        }
    }
    cmd.append("npx -y supergateway --stdio \"");
    // ... 后续
}
```

---

### Task 3: McpClientManager 适配

**文件：**
- 修改: `src/main/java/com/example/sandbox/web/service/mcpclient/McpClientManager.java`

**约束：现有 `stdio` 和 `streamable-http` 路径一行不改。**
- `createStdioTransport()` 不动
- `createHttpTransport()` 不动
- 现有的 `createTransport(McpServerConfig)` 不动
- 现有的 `addOrReplaceUserServer(Long, McpServerConfig)` 不动

**为什么需要新重载：** `createTransport(McpServerConfig)` 只接收 config，但 `shell` 类型的 Transport 需要 `AioShellApi`（每个用户的沙箱 endpoint 不同）。必须新增一个重载来接收这个参数。

**新增内容：**

1. 新增公开方法（由 `McpConfigurationService` 调用）：
```java
public void addOrReplaceUserServer(Long userId, McpServerConfig config, AioShellApi shellApi)
```

2. 新增私有重载 `addOrReplace(key, config, shellApi)` — 和现有 `addOrReplace(key, config)` 并列，仅在其内部调用 `createTransport(config, shellApi)` 而非 `createTransport(config)`。

3. 新增私有方法 `createTransport(McpServerConfig config, AioShellApi shellApi)`：
```java
private McpClientTransport createTransport(McpServerConfig config, AioShellApi shellApi) {
    String type = config.getType() != null ? config.getType().trim().toLowerCase() : "";
    return switch (type) {
        // 复用现有逻辑，不加 shellApi 参数
        case "stdio" -> createStdioTransport(config);
        case "streamable-http", "http" -> createHttpTransport(config);
        // shell 使用新逻辑
        case "shell" -> createShellTransport(config, shellApi);
        default -> throw new IllegalArgumentException("...");
    };
}
```

注意 `stdio` 和 `streamable-http` 的 case 仍是现有的 `createStdioTransport(config)` 和 `createHttpTransport(config)`，不加 `shellApi` 参数，行为完全不变。

4. 新增：
```java
private McpClientTransport createShellTransport(McpServerConfig config, AioShellApi shellApi) {
    if (config.getCommand() == null || config.getCommand().isBlank()) {
        throw new IllegalArgumentException("shell MCP server 必须配置 command: " + config.getId());
    }
    int port = allocatePort(shellApi);
    return new AioShellTransport(shellApi, config, port);
}
```

---

### Task 4: McpConfigurationService 适配

**文件：**
- 修改: `src/main/java/com/example/sandbox/web/service/mcpclient/McpConfigurationService.java`

**改动：** `applyUserConfigs` 中需要获取 `AioClient` 并传给 `addOrReplaceUserServer`。

当前签名：
```java
private McpReloadResult applyUserConfigs(Long userId, List<McpServerConfig> configs)
```

问题：`applyUserConfigs` 只有 `userId`，但 `SandboxClientFactory.getAioClient()` 需要 `sessionId`。不过 `SandboxClientFactory` 有 `getAioClientByUserId(Long userId)` 方法。

**改动：**

1. `McpConfigurationService` 注入 `SandboxClientFactory`（实际上已经有 `sandboxClientFactory`）

2. 在 `applyUserConfigs` 中：
```java
AioClient aio = null;
// 只在有 shell 类型配置时才获取 AioClient
boolean hasShellType = configs.stream().anyMatch(c -> "shell".equals(c.getType()));
if (hasShellType) {
    aio = sandboxClientFactory.getAioClientByUserId(userId);
    if (aio == null) {
        // 用户没有沙箱，shell 类型 server 全部标记失败
        // ...
    }
}

// 连接时判断类型
if ("shell".equals(config.getType())) {
    manager.addOrReplaceUserServer(userId, config, aio.shell());
} else {
    manager.addOrReplaceUserServer(userId, config);
}
```

---

### Task 5: McpUserConfigValidator 更新

**文件：**
- 修改: `src/main/java/com/example/sandbox/web/service/mcpclient/McpUserConfigValidator.java`

**改动点 1：** `validateServer()` 第 125 行，允许 `"shell"` 类型：
```java
boolean isHttp = "streamable-http".equals(type) || "http".equals(type);
boolean isShell = "shell".equals(type);
if (!isHttp && !isShell) {
    throw new IllegalArgumentException("用户动态 MCP 只允许 streamable-http 或 shell");
}
```

**改动点 2：** `shell` 类型的校验规则：
```java
if (isShell) {
    // shell 类型：必须 command，可选 args/env，禁止 url/headers
    if (server.getCommand() == null || server.getCommand().isBlank()) {
        throw new IllegalArgumentException("shell MCP 必须配置 command");
    }
    if (server.getUrl() != null && !server.getUrl().isBlank()) {
        throw new IllegalArgumentException("shell MCP 不允许配置 url");
    }
    if (!server.getHeaders().isEmpty()) {
        throw new IllegalArgumentException("shell MCP 不允许配置 headers");
    }
}
```

**改动点 3：** `validateDocument()` 中对 shell 类型不检查 URL 去重（shell 没 url）。

---

### Task 6: McpServerView 适配

**文件：**
- 修改: `src/main/java/com/example/sandbox/web/service/mcpclient/McpServerView.java`
- 修改: `src/main/java/com/example/sandbox/web/service/mcpclient/McpConfigurationService.java` (`toView` 方法)

当前 `McpServerView` 的 `url` 字段对 stdio 传空字符串。shell 类型同理。但 `McpConfigurationService.toView()` 只传 `config.getUrl()`：
```java
new McpServerView(key.scope(), config.getId(), config.getType(), 
    config.getUrl(), ...);  // shell 类型的 url 为 null
```

**方案：** shell 类型展示 `command + args`。在 `McpServerView` 的 `url` 字段上不传，新增一个 `connectionInfo` 字段（可选）：
```java
// shell: "npx -y @modelcontextprotocol/server-filesystem /home/gem"
// streamable-http: "https://example.com/mcp"
// stdio: "npx -y @modelcontextprotocol/server-filesystem"
```

或者保持简单：`url` 字段对 shell 传入 `command` 首行（50 字符截断），不做 record 结构变更。**推荐后者**，改动最小。

---

### Task 7: 错误处理增强

**文件：**
- 修改: `src/main/java/com/example/sandbox/web/service/mcpclient/McpErrorCode.java`
- 修改: `src/main/java/com/example/sandbox/web/service/mcpclient/McpConnectionErrorClassifier.java`

**新增 McpErrorCode：**
```java
/** 沙箱内 shell 命令执行失败 */
SHELL_EXEC_FAILED,
/** supergateway 启动失败或意外退出 */
SUPERGATEWAY_START_FAILED,
/** SSE/JSON-RPC 响应解析失败 */
SSE_PARSE_ERROR,
/** 端口分配失败（8000-8099 全被占用） */
PORT_ALLOCATION_FAILED
```

**McpConnectionErrorClassifier 改动：** 新增对 `AioApiException` 的分类逻辑（AioShellApi 的网络异常）。或者让 `AioShellTransport` 在 catch 到异常时直接包装为 `McpConnectionException`，不经过 classifier。

**建议：** `AioShellTransport` 内部直接抛出 `McpConnectionException`，预填好 structured error。这样 `classifier` 不需要改。

---

### Task 8: 前置依赖与配置

**文件：**
- 修改: `src/main/java/com/example/sandbox/web/service/mcpclient/McpServerConfig.java` (仅 Javadoc 更新)

不需要修改 `McpServerConfig` 的字段结构——`shell` 类型复用 `command`/`args`/`env`。但需要更新 class 级 Javadoc 说明新增的 `shell` 类型。

`sameConnectionConfig()` 已经比较 `command`/`args`/`env`，不需要改。

---

## 不在此次实现范围

| 项目 | 原因 |
|------|------|
| 通知轮询（notifications/tools/list_changed） | POST 响应中夹带的通知由 SDK 的 `toolsChangeConsumer` 处理，不需要额外轮询 |
| 健康检查 / 自动重连 | supergateway 崩溃后的恢复逻辑留到后续迭代 |
| supergateway 版本固定 | 当前用 `npx -y supergateway`（最新版），版本管理后续统一做 |
| `McpServerConfig` 的多态化 | 当前 flat POJO 已够用，重构成本高于收益 |

---

## 文件变更汇总

| 文件 | 操作 | 说明 |
|------|------|------|
| `aio/shell/model/ShellExecRequest.java` | 新建 | 完整请求模型 |
| `aio/shell/model/ShellExecResult.java` | 修改 | +getStatus(), +getSessionId() |
| `aio/shell/AioShellApi.java` | 修改 | +execAsync(), +exec(cmd, sid, timeout) |
| `mcpclient/AioShellTransport.java` | 新建 | McpClientTransport 实现 |
| `mcpclient/McpClientManager.java` | 修改 | +shell case, +重载 |
| `mcpclient/McpConfigurationService.java` | 修改 | 传 AioClient 给 Manager |
| `mcpclient/McpUserConfigValidator.java` | 修改 | 允许 shell 类型 |
| `mcpclient/McpErrorCode.java` | 修改 | +4 个错误码 |
| `mcpclient/McpServerConfig.java` | 修改 | Javadoc 更新 |
| `mcpclient/McpServerView.java` | (不改) | url 字段暂用 |
| `mcpclient/McpConfigurationService.java` (toView) | 修改 | shell 类型 url 展示 |

共 **3 新建, 8 修改**。