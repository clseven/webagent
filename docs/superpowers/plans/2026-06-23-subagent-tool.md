# 通用后台任务系统

> **一期（s06）：** 串行子代理 — `ReactAgent.fork()` 上下文隔离 ✅ 已实现
> **二期（s13）：** 通用后台任务 — 慢操作丢后台线程，主 Agent 不阻塞

---

## 一期回顾

子代理通过标准 `run_subagent` 工具触发，`ReactAgent.fork()` 创建子实例，独立 ReAct 循环，完成后只回传结构化摘要。主 Agent 同步等待子代理完成。

核心文件：`RunSubagentTool.java`、`ReactAgent.fork()`、`SubAgentConfigProperties.java`、`AgentServiceImpl.java`

已知小问题：`SubAgentConfigProperties.maxIterations` 目前未生效，子代理仍走 `ReactAgent.MAX_ITERATIONS = 25`。按需修，不影响二期。

---

## 二期：通用后台任务系统

### 问题

一期所有工具调用都是串行阻塞的。`npm install` 跑 10 分钟，Agent 干等 10 分钟。`run_subagent` 分析大文件要 15 秒，Agent 干等 15 秒。慢操作阻塞主循环，白白消耗 LLM token。

### 方案

慢操作丢后台线程，Agent 循环继续，完成后通知注入对话。

**设计原则：** Agent 循环决定"怎么执行"，工具只管"做什么"。后台执行是调度策略，不塞进具体工具。

### 架构

```
BackgroundTaskManager（Spring Bean）
  ├── start(sessionId, Supplier<String> task) → bg_id
  │     线程池提交闭包，完成后结果塞入通知队列
  │     闭包 = executeTool() 或其包装（超时、异常已在调用方包好）
  ├── collect(sessionId) → String|null
  │     poll 通知队列，返回拼接好的通知文本（或 null）
  ├── cancelAll(sessionId) → void
  │     真取消：遍历 Future 调 cancel(true) + interrupt
  │     清理通知队列
  ├── awaitPending(sessionId, timeout) → String
  │     阻塞等待所有未完成任务，返回剩余通知
  └── shouldRunBackground(toolCall) → boolean
        1. 模型显式 run_in_background: true（主路径）
        2. 关键词启发式兜底（install/build/test/compile...）

ReactAgent 循环
  while 每轮迭代：
    ① collect() → 有通知 → 注入 messages（user 消息）
    ② LLM 调用
    ③ 如果 tool_call：
       ├── shouldRunBackground()? 是 → manager.start(闭包) → 占位 tool_result
       └── 否 → 同步 executeTool()
    ④ 如果 finished → Stop Hook
  // 出循环后：
  ⑤ awaitPending() → 有遗留 → 注入 → 消化循环（最多 6 轮）
```

### 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| Manager 入参 | `Supplier<String>` 闭包（不是 Tool） | 闭包在调用方已包好超时+异常；消除 RunSubagentTool singleton parentAgent 风险 |
| 线程模型 | `newFixedThreadPool(5)` + daemon | 复用线程；daemon 随 JVM 退出；Semaphore 做准入限流 |
| 限流策略 | `Semaphore.tryAcquire()`，超限回退同步 | 不排队，不拒绝，静默降级 |
| 取消 | `Future.cancel(true)` + interrupt | 真取消，不只删引用 |
| 通知闭环 | `awaitPending()` + 消化循环（最多 6 轮） | LLM 看到通知后可能又调工具，不能只给一次 LLM 调用 |
| `compressIfNeeded` | 跳过含 `<task_notification>` 的消息 | 通知才几百字，压缩没意义 |
| 流式路径 | `chatStream()` 不启用后台 | 传给 `BackgroundTaskManager` 的引用为 null，自动走回串行；三期再做 |
| 子代理流式 | 不需要 | 子代理内部用 `run()`，前端只看主 Agent SSE |
| 沙箱异步 | 不用 | Java 线程够用，Common 沙箱也生效 |

### 文件变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `BackgroundTaskManager.java` | 新增 ~130 行 | 统一后台任务调度 |
| `ReactAgent.java` | 修改 +55 行 | 循环中集成 collect + 后台分叉 |
| `ExecuteCommandTool.java` | 修改 +5 行 | 加 `run_in_background` 参数 |
| `RunSubagentTool.java` | 修改 +5 行 | 加 `run_in_background` 参数 |
| `AgentServiceImpl.java` | 修改 +20 行 | 注入 BackgroundTaskManager |
| `application.yml` | 修改 +3 行 | `agent.background` 配置段 |

总增量：~220 行。

### 并发安全性

| 场景 | 安全 | 原因 |
|---|---|---|
| `npm install` + `run_subagent` 同时后台 | ✅ | 独立线程 + 独立沙箱操作 |
| 两个慢命令同时后台 | ⚠️ | 可能写冲突，LLM 不应发起冲突操作 |
| 通知注入 + LLM 调用同一轮 | ✅ | collect() 在 LLM 调用前，串行 |
| 并发超限 | ✅ | Semaphore.tryAcquire() → 回退同步 |
| 最终回答前 pending 任务 | ✅ | awaitPending() 阻塞等待 |
| SSE 中断 | ✅ | cancelAll() → Future.cancel(true) |
| RunSubagentTool 跨会话串用 | ✅ | 闭包捕获，不依赖可变字段 |

### 不做的

- ❌ `runStream()` 后台支持（三期，独立设计）
- ❌ 子代理 SSE 流式输出
- ❌ AIO shell session 异步（Java 线程足够）
- ❌ 后台任务暂停/恢复/看门狗（按需）
- ❌ 多 tool_call 扇出（每轮仍是单 tool_call）

### 验证步骤

1. **后台 bash：** `"后台执行 npm install"` → 日志显示任务已启动 + 占位 tool_result
2. **通知注入：** 安装完成后下一轮 `collect()` 返回通知 → LLM 看到结果
3. **后台子代理：** `"后台分析 app.log"` → 子代理后台跑 → 通知注入摘要
4. **并发上限：** 第 6 个后台任务回退同步，日志 `"并发已满"`
5. **最终闭环：** LLM 直接回答时后台任务未完成 → `awaitPending()` 等待 → 再调 LLM 综合
6. **中断清理：** SSE 断开 → `cancelAll()` → Future 被 cancel，无残留

### 已知问题

1. **LLM 不理解后台工作方式。** 启动后台子代理后，LLM 偶尔会自己重复做同样的搜索（调 `web_search`、`browser_screenshot` 等），或用 `shell_wait` 尝试等待后台任务。原因是提示词没明确告诉它"启动后台后不要管，通知会自动来"。

2. **多 tool_call 时第二个被丢弃。** 当 LLM 一次返回两个 tool_call（如同时启动两个后台子代理），当前代码只处理第一个。第二个子代理不会被启动。这是已有的单 tool_call per turn 限制。

3. **`runStream()` 未支持后台。** 当前只在同步路径 `chat()` → `run()` 实现了后台任务。流式路径 `chatStream()` → `runStream()` 不传 `BackgroundTaskManager`，后台功能不生效。
