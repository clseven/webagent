# MCP 架构说明

> 本文档说明 WebAgent 中 MCP（Model Context Protocol）的整体架构、三种 transport
> 类型的完整链路，以及 HTTP 与 shell 两种 transport 的对比。

---

## 1. MCP 是什么

MCP 是一个**通信协议**，让 AI Agent 能调用外部工具。底层使用 **JSON-RPC 2.0** 格式传消息。

一个 MCP Server 就是一个"工具箱"。Agent 连上去之后：
1. 发 `initialize`，握手确认能力
2. 发 `tools/list`，拿到工具列表
3. 需要时发 `tools/call`，调用具体工具

这三步全部是标准 JSON-RPC，由 MCP SDK 自动驱动，业务代码只负责把消息送出去和收回来。

---

## 2. 三种 Transport 类型

WebAgent 支持三种 transport，由 `McpServerConfig.type` 决定走哪条路。

| type | 谁能配置 | 命令在哪里跑 | 适合什么 |
|------|----------|-------------|----------|
| `stdio` | 系统管理员（application.yml） | 后端宿主机 | 本地工具，如 filesystem、git |
| `streamable-http` | 用户 | 不跑命令，直连 HTTP | 远程托管的 MCP Server |
| `shell` | 用户（不能带凭据） | 用户 AIO 沙箱内 | 需要在沙箱里跑的 stdio MCP |

用户只能配置 `streamable-http` 和 `shell`，`stdio` 对用户不可见，原因是安全——不能让用户在后端主机上执行任意命令。

---

## 3. HTTP Transport 完整链路

适用于对方已有公开 HTTP 服务的 MCP，例如 DeepWiki、Microsoft Learn API。

```
用户说："帮我安装 DeepWiki MCP"
│
▼
PlanAgent（规划）
  生成执行计划："查文档 → 确认 URL → 向用户展示 → 等确认 → 调工具"
│
▼
ReactAgent（执行）
  调用 web_search 查到官方 endpoint，向用户展示，等用户说"确认"
  生成 tool call：
  {
    "tool": "mcp_add_or_update_server",
    "arguments": {
      "server_id": "deepwiki",
      "type": "streamable-http",
      "url": "https://mcp.deepwiki.com/mcp",
      "confirmed": true
    }
  }
│
▼
McpAddOrUpdateServerTool.execute()
  检查 confirmed == true
  组装 McpServerConfig { id="deepwiki", type="streamable-http", url="..." }
│
▼
McpConfigurationService.addOrReplaceUserServer()
  1. McpUserConfigValidator 校验（不能带 headers/凭据，URL 不重复等）
  2. 读取 /home/gem/.mcp/servers.json（通过 AIO File API）
  3. 插入或替换同 ID 配置
  4. 写回 /home/gem/.mcp/servers.json
  5. 触发 reloadUserServers()
│
▼
McpClientManager.addOrReplaceUserServer(userId, config)
  createTransport() → createHttpTransport()
    解析 URL，拆成 baseUri + endpoint
    创建 HttpClientStreamableHttpTransport
│
▼
MCP SDK 接管，自动发送 JSON-RPC（后端 → 对方 HTTP Server）

  ① POST https://mcp.deepwiki.com/mcp
     {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
     响应：{"jsonrpc":"2.0","id":1,"result":{"serverInfo":{...},"capabilities":{...}}}

  ② POST https://mcp.deepwiki.com/mcp
     {"jsonrpc":"2.0","id":2,"method":"tools/list"}
     响应：{"jsonrpc":"2.0","id":2,"result":{"tools":[...]}}
│
▼
McpClientManager 缓存工具列表到 toolCache
│
▼
ReactAgent 调用 mcp_list_servers 验证，向用户报告安装结果
```

**关键细节：**
- `HttpClientStreamableHttpTransport` 是 MCP SDK 提供的标准实现，直接从后端 JVM 发 HTTP
- URL 必须是精确 endpoint，不能猜 `/mcp`，SDK 内部用 `URI.resolve()` 拼接，乱猜会 404
- 相同 URL 的 Server 会自动复用已有配置，不会重复建连
- 后端直连对方，中间没有沙箱参与

---

## 4. Shell Transport 完整链路

适用于只有 stdio 接口的 MCP，需要在用户沙箱内运行。核心问题是：后端无法直接 fork 沙箱内的进程，所以借 **supergateway** 把 stdio 转成 HTTP，再用 curl 通信。

```
用户说："帮我安装 filesystem MCP"
│
▼
PlanAgent（规划）
  生成计划："识别为 stdio MCP → 使用 shell transport → 安装"
│
▼
ReactAgent（执行）
  生成 tool call：
  {
    "tool": "mcp_add_or_update_server",
    "arguments": {
      "server_id": "filesystem",
      "type": "shell",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home/gem/workspace"],
      "confirmed": true
    }
  }
│
▼
McpAddOrUpdateServerTool.execute()
  检查 confirmed == true
  检查 command 不为空
  校验 filesystem+npx 必须包含 @modelcontextprotocol/server-filesystem 包名
  组装 McpServerConfig { id="filesystem", type="shell", command="npx", args=[...] }
│
▼
McpConfigurationService.addOrReplaceUserServer()
  1. McpUserConfigValidator 校验（命令长度、args 数量、env 格式等）
  2. 写入 /home/gem/.mcp/servers.json
  3. 触发 reloadUserServers() → applyUserConfigs()
     发现 isShellConfig == true，获取该用户的 AioClient
│
▼
McpClientManager.addOrReplaceUserServer(userId, config, shellApi)
  createTransport() → createShellTransport()
    allocatePort(shellApi)：
      在沙箱内循环执行 "ss -tlnp | grep -q ':PORT ' && echo occupied || echo free"
      找到第一个 free 的端口（范围 8000~8099）
    创建 AioShellTransport(shellApi, config, port=8035)
│
▼
MCP SDK 调用 transport.connect()
  connectBlocking()：
    拼装 supergateway 启动命令：
    "npx -y supergateway \
       --stdio 'npx -y @modelcontextprotocol/server-filesystem /home/gem/workspace' \
       --outputTransport streamableHttp \
       --port 8035 \
       --host 0.0.0.0 2>&1"
    shellApi.execAsync(command)  →  沙箱内以长进程启动 supergateway
    记录返回的 supergatewaySessionId
    循环轮询端口（每秒一次，最长 60 秒）：
      shellApi.exec("ss -tlnp | grep -q ':8035 ' && echo occupied || echo free")
      直到返回 occupied
│
▼
MCP SDK 调用 transport.sendMessage(initialize)
  sendMessageBlocking()：
    json = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}'
    拼 curl 命令：
    "curl -sS http://localhost:8035/mcp \
      -X POST \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json, text/event-stream' \
      --data '{...}'"
    shellApi.exec(curlCommand)  →  沙箱内执行 curl

    沙箱内通信路径：
    curl → supergateway(:8035) → 转发给 stdio MCP Server（stdin）
                               ← MCP Server 返回（stdout）
           supergateway 以 SSE 格式响应：
           "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{...}}\n\n"

    parseMessages(curl输出)：
      按行扫描，提取 "data: " 前缀的行，还原为 JSONRPCMessage 对象
    inboundHandler(response)  →  交回 MCP SDK 处理
│
▼
MCP SDK 继续调用 sendMessage(tools/list)，流程同上
│
▼
McpClientManager 缓存工具列表
│
▼
ReactAgent 调用 mcp_list_servers 验证，向用户报告安装结果
```

**关键细节：**
- supergateway 运行在用户沙箱内，后端只通过 AIO Shell REST API 间接控制，**不在后端主机执行用户命令**
- 每次发 JSON-RPC 都是一次独立 `shellApi.exec(curl)`，同步等待 curl 返回
- curl 响应可能是纯 JSON，也可能是 SSE 格式（`data: {...}`），`parseMessages()` 两种都处理
- supergateway 进程的 sessionId 存在 `AioShellTransport` 里，关闭时调用 `shellApi.kill(sessionId)`
- 初始化超时最少 90 秒（比普通 HTTP 长），因为 npx 首次运行需要下载依赖

---

## 5. HTTP 与 Shell 对比

### 5.1 链路对比

```
HTTP transport：
后端 JVM ──JSON-RPC over HTTP──→ 对方 MCP Server（公网）

Shell transport：
后端 JVM
  └─ AIO Shell REST API
       └─ 用户沙箱进程
            └─ supergateway（HTTP :PORT）
                 └─ stdio MCP Server（子进程）
  后端 JVM 发 curl（通过 Shell API）──JSON-RPC──→ supergateway ──stdin/stdout──→ MCP Server
```

### 5.2 关键细节对比

| 维度 | streamable-http | shell |
|------|----------------|-------|
| **JSON-RPC 发送方式** | JVM 直接发 HTTP 请求 | JVM 通过 Shell API 在沙箱执行 curl |
| **响应接收方式** | JVM 直接读 HTTP 响应体 | 读 curl 的 stdout，解析 SSE 格式 |
| **连接建立** | 直接 initialize | 先启动 supergateway，等端口就绪，再 initialize |
| **进程管理** | 无，SDK 管理 HTTP 连接 | 后端持有 supergatewaySessionId，关闭时 kill |
| **端口占用** | 无 | 占用沙箱内 8000~8099 中一个端口 |
| **初始化超时** | 全局配置（默认较短） | 至少 90 秒 |
| **配置写入位置** | `/home/gem/.mcp/servers.json` | 同左 |
| **凭据限制** | 不支持 headers/Token | 不支持 env 中写凭据 |
| **断线恢复** | 重新 addOrReplace 即可 | 需要重新启动 supergateway 进程 |
| **调试入口** | 直接 curl 对方 URL | 查 supergateway 进程日志（`shellApi.view(sessionId)`） |

### 5.3 整体对比

**相同的部分：**
- 配置都写入 `/home/gem/.mcp/servers.json`，通过 AIO File API 读写
- 都由 `McpClientManager` 统一管理生命周期，工具列表都缓存在 `toolCache`
- 都经过 `McpUserConfigValidator` 安全校验
- MCP 协议层完全一致，都是 JSON-RPC 2.0，SDK 驱动 `initialize` → `tools/list` 两步握手
- 大模型视角完全一致：看到的工具名、调用方式、返回格式没有任何区别

**不同的部分：**
- HTTP transport 是后端 JVM 直连对方服务器，中间没有沙箱参与
- Shell transport 中，JSON-RPC 消息**绕了两圈**：JVM → Shell API → 沙箱 curl → supergateway → stdio MCP Server
- Shell transport 多了"启动进程 + 等待端口"这个阶段，HTTP transport 没有
- Shell transport 的 MCP Server 生命周期和用户沙箱绑定，后端重启后需要重新建连

---

## 6. 配置文件

用户 MCP 配置持久化在沙箱内：`/home/gem/.mcp/servers.json`

```json
{
  "version": 1,
  "servers": [
    {
      "id": "deepwiki",
      "type": "streamable-http",
      "url": "https://mcp.deepwiki.com/mcp",
      "enabled": true
    },
    {
      "id": "filesystem",
      "type": "shell",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home/gem/workspace"],
      "enabled": true
    }
  ]
}
```

- 后端重启后，用户首次发消息时会懒加载这份配置，自动恢复连接
- 手动编辑文件后需调用 `mcp_reload` 工具使变更生效

---

## 7. 错误码

| 错误码 | 含义 | 常见原因 |
|--------|------|----------|
| `MCP_DISABLED` | MCP 功能总开关关闭 | `agent.mcp.enabled=false`，需管理员重启 |
| `CONFIG_INVALID` | 配置格式错误 | 必填字段缺失、字段超长、ID 重复等 |
| `PORT_ALLOCATION_FAILED` | 沙箱端口耗尽 | 8000~8099 全部被占用 |
| `SUPERGATEWAY_START_FAILED` | supergateway 未能启动 | shell 命令错误、npx 下载失败、端口监听超时 |
| `SHELL_EXEC_FAILED` | curl 执行失败 | 沙箱网络问题、supergateway 进程已退出 |
| `SSE_PARSE_ERROR` | 响应解析失败 | supergateway 返回非标准格式 |
| `PROTOCOL_ERROR` | MCP 握手失败 | 对方不是兼容的 MCP endpoint |
| `AUTH_REQUIRED` | 需要认证 | 对方要求 Token，当前不支持 headers |
| `HTTP_404` / `HTTP_405` | HTTP 请求失败 | URL endpoint 不正确 |

---

## 8. 涉及的主要类

| 类 | 职责 |
|----|------|
| `McpAddOrUpdateServerTool` | 大模型调用的工具入口，从 tool call arguments 组装配置 |
| `McpConfigurationService` | 配置编排：读写 servers.json，触发 reload，管理懒加载 |
| `McpUserConfigValidator` | 安全校验：字段长度、凭据禁止、重复检查 |
| `McpClientManager` | Client 生命周期：创建 transport，初始化，缓存工具 |
| `McpServerConfig` | 单个 MCP Server 的配置 Bean |
| `McpClientKey` | Client 唯一键（scope + userId + serverId） |
| `AioShellTransport` | Shell transport 实现：启动 supergateway，用 curl 收发 JSON-RPC |
| `AioShellApi` | 沙箱 Shell REST API 客户端 |
