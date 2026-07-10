---
project: webagent-clean
type: interview-guide
status: verified
area:
  - interview
  - mcp
tags:
  - project/webagent-clean
  - interview
  - mcp
source:
  - pom.xml
  - src/main/java/com/example/sandbox/web/service/mcpclient/McpClientManager.java
  - src/main/java/com/example/sandbox/web/service/mcpclient/McpClientToolProvider.java
  - src/main/java/com/example/sandbox/web/service/mcpclient/RealMcpTool.java
  - src/main/java/com/example/sandbox/web/service/mcpclient/McpConfigurationService.java
  - src/main/java/com/example/sandbox/web/service/mcpclient/McpUserConfigValidator.java
updated: 2026-07-10
---

# MCP 从协议到实现

> 用途：把 MCP 一次讲透，解决"一知半解"。顺序是从最底层的协议往上：协议长什么样 → 用的什么库 → 库怎么调 → 我在库上面做了什么。
> 
> **一句话先记住**：MCP 协议 = JSON-RPC 2.0 的一问一答，核心就三个方法（握手、列工具、调工具）；协议报文的收发解析用官方 SDK，不用自己写；我做的是上层的多租户、安全和统一封装。

---

## 一、协议本身：就是 JSON-RPC 2.0

MCP（Model Context Protocol）说白了：**基于 JSON-RPC 2.0 的一问一答协议**。每条消息是一个 JSON，通过某种通道（HTTP / stdio 管道）收发。

**核心就三个方法**，一个连接的生命周期照着走：

### 1. initialize —— 握手（连上先做）

client 发：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": { "name": "webagent", "version": "1.0" }
  }
}
```

server 回复它支持哪些能力。作用：**确认双方协议版本和能力**。

### 2. tools/list —— 问它有哪些工具

client 发：

```json
{ "jsonrpc": "2.0", "id": 2, "method": "tools/list" }
```

server 返回工具清单，**每个工具带 name、description、inputSchema**：

```json
{
  "jsonrpc": "2.0", "id": 2,
  "result": {
    "tools": [{
      "name": "get_weather",
      "description": "查询天气",
      "inputSchema": {
        "type": "object",
        "properties": { "city": { "type": "string" } },
        "required": ["city"]
      }
    }]
  }
}
```

**这个 inputSchema 就是"参数 schema"**——它原样来自 server，我拿它给大模型看，模型才知道这个工具怎么填参数。（所以参数 schema 不是我写的，是 server 提供的，我只是转发给模型。）

### 3. tools/call —— 调某个工具

client 发：

```json
{
  "jsonrpc": "2.0", "id": 3,
  "method": "tools/call",
  "params": {
    "name": "get_weather",
    "arguments": { "city": "深圳" }
  }
}
```

server 执行后返回结果（content 数组，可能是文本/图片）：

```json
{
  "jsonrpc": "2.0", "id": 3,
  "result": { "content": [{ "type": "text", "text": "深圳 28℃ 晴" }] }
}
```

### 补充：tools/list_changed —— server 主动通知工具变了

这是个"通知"（notification，不需要回复），server 主动推给 client："我的工具列表变了"。我代码里用 toolsChangeConsumer 监听它，收到就刷新缓存。

**整个协议核心就这些**：握手 → 列工具 → 调工具，外加一个工具变更通知。记住这四个就够了。

---

## 二、用的什么库：官方 Java SDK

pom.xml：

```xml
<groupId>io.modelcontextprotocol.sdk</groupId>
<artifactId>mcp-core</artifactId>   <!-- 官方 MCP Java SDK, 2.0.0 -->
```

**关键认知：协议我没有自己实现。** 上面那些 JSON-RPC 报文的拼装、收发、解析，全是这个库做的。我做的是"怎么用它 + 上层封装"。面试时这点要诚实——懂协议 ≠ 自己写协议栈。

---

## 三、库怎么调：核心就三段代码

我和这个库打交道，全部集中在 McpClientManager，就三处：

### ① 建客户端 + 握手 + 列工具（addOrReplace）

```java
McpSyncClient client = McpClient.sync(transport)   // 传入 transport（通道）
        .requestTimeout(...)
        .initializationTimeout(...)
        .toolsChangeConsumer(tools -> ...)          // 监听 tools/list_changed 通知
        .build();

client.initialize();                                 // ← 发 initialize 握手
McpSchema.ListToolsResult tools = client.listTools(); // ← 发 tools/list
```

### ② 调工具（callTool）

```java
McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
        .arguments(safeArgs)
        .build();
return client.callTool(request);                     // ← 发 tools/call
```

就这些。McpSyncClient 是同步客户端（调用阻塞等返回），McpSchema.* 是库提供的协议数据结构。我全程没碰任何 JSON 拼装。

---

## 四、transport：唯一需要额外理解的抽象

transport = **消息走什么通道**。同样的三个 JSON-RPC 报文，transport 只决定它们怎么传输。我的 createTransport 根据配置的 type 选通道：

| transport | 通道 | 在哪跑 | 谁能配 |
| --- | --- | --- | --- |
| **streamable-http** | HTTP 请求 | 远程 server | 用户可配（带 SSRF 校验） |
| **stdio** | 子进程的标准输入输出管道 | 主应用**主机** | 只有管理员（有风险） |
| **shell** | 用户沙箱内起进程 | 用户**沙箱内** | 用户可配（隔离在沙箱） |

理解：HTTP transport 把报文当 HTTP body 发出去；stdio transport 把报文写进子进程的 stdin、从 stdout 读回复。协议内容一模一样，只是搬运方式不同。

---

## 五、我在库上面做了什么（真正的工程价值）

库负责协议，我负责"怎么安全地、多租户地把它用起来"。这才是面试要突出的部分：

| 事情 | 谁做 |
| --- | --- |
| JSON-RPC 报文拼装/收发/解析、三个协议方法、transport 底层实现 | **官方 SDK** |
| 选哪种 transport、超时配置、连接生命周期与缓存、并发管理 | 我（McpClientManager） |
| 多用户隔离：按 sessionId 解析 userId，只暴露该用户的 server | 我（McpClientToolProvider） |
| 把 MCP 工具包装成系统统一 Tool 接口 | 我（RealMcpTool，适配器模式） |
| 命名冲突处理：对模型暴露唯一名、对调用保留原名 | 我（McpToolNameCodec / uniqueName） |
| SSRF 安全校验：禁 HTTP/私网、解析 IP 拦截 | 我（McpUserConfigValidator） |
| transport 分级授权：主机 stdio 只给管理员，用户只能用沙箱 shell | 我 |
| 工具数量上限（50），防挤爆模型上下文 | 我（McpClientToolProvider） |
| 配置读写（沙箱内 servers.json，按用户隔离）+ Agent 自管配置工具 | 我（McpConfigurationService + add/list/remove 工具） |

### 最核心的封装思路：适配器模式

```
本地工具  → 实现 Tool 接口 ┐
                          ├→ ReactAgent 统一按 Tool 调用，不区分来源
MCP 工具  → RealMcpTool  ┘   （RealMcpTool 是适配器）
```

RealMcpTool 把一个 MCP 工具适配成系统的 Tool 接口。模型选中它 → RealMcpTool.execute → 转回 McpClientManager.callTool → 库发 tools/call → 结果格式化成 observation。**ReactAgent 完全不知道这个工具是本地的还是 MCP 的。**

---

## 六、面试标准答法

### Q：MCP 协议长什么样？你自己实现协议了吗？

> 协议本身是 JSON-RPC 2.0，核心就三个方法：initialize 握手、tools/list 列出工具、tools/call 调用工具，外加一个 tools/list_changed 的变更通知。这部分我用的是官方 Java SDK（mcp-core），报文的收发和解析不用自己写。
> 我做的是上层工程：transport 选型（HTTP/stdio/shell）、连接生命周期管理、多用户隔离、安全校验（SSRF、transport 分级授权）、命名冲突处理，以及用适配器模式把 MCP 工具包装成系统统一的 Tool 接口，让 ReactAgent 调 MCP 工具和调本地工具没区别。
> 一句话：SDK 负责协议，我负责怎么安全地、多租户地把它用起来。

### Q：那 tools/list 拿到的参数 schema 是你写的吗？

> 不是。inputSchema 是每个 MCP server 在 tools/list 里返回的，原样来自 server。我做的是把它转发给大模型——模型靠这个 schema 知道工具有哪些参数、怎么填。本地工具的 schema 才是我自己写的。

### Q：一次 MCP 工具调用，从模型到 server 的完整链路？

> 模型在 ReAct 循环里根据 schema 决定调某个 MCP 工具、生成参数 → RealMcpTool 收到（它实现了统一 Tool 接口）→ 转到 McpClientManager.callTool → 官方 SDK 把它拼成 tools/call 的 JSON-RPC 报文，通过对应 transport（HTTP 或 stdio 管道）发给真实 server → server 执行返回 content → 格式化成 observation 塞回对话，模型看到结果决定下一步。

---

## 相关页面

[[技术选型与场景设计问答]] · [[项目亮点与模块深挖]] · [[面试必备内容]] · [[当前已知风险]]
