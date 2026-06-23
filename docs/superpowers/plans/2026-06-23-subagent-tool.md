# 子代理实现方案（两期）

> **一期（s06）：** 串行子代理 — 上下文隔离，只回传摘要
> **二期（s13）：** 后台执行 — 子代理异步跑，主 Agent 不阻塞

---

## 一期：串行子代理（已实现，待验证）

### 改动清单

| 文件 | 变更 | 说明 |
|---|---|---|
| `web/service/tool/RunSubagentTool.java` | **新增** ~295 行 | 4 种类型，上下文隔离，同步执行 |
| `web/service/impl/ReactAgent.java` | **修改** +56 行 | `fork()` 创建子实例 + `getTools()` 暴露工具列表 |
| `web/config/SubAgentConfigProperties.java` | **新增** ~45 行 | `agent.sub-agent` 配置绑定 |
| `web/service/impl/AgentServiceImpl.java` | **修改** +42 行 | 注入、feature flag 过滤、parentAgent 绑定 |
| `application.yml` | **修改** +15 行 | `agent.sub-agent` 配置段 |

### 数据流

```
用户消息 → AgentServiceImpl.chat()
  ├── PlanAgent.plan()
  ├── ReactAgent.run()
  │     ├── [迭代1] LLM → tool_call: run_subagent(type=analyzer, task=...)
  │     │     └── RunSubagentTool.execute()
  │     │           ├── getRestrictedTools("analyzer") → [read_file, list_files, ...]
  │     │           ├── parentAgent.fork(受限工具, analyzer系统提示词)
  │     │           │     └── child.messages = [user("分析app.log中的错误")]
  │     │           │     └── child.hooks = [logHook]  ← 继承 PreToolUse/PostToolUse
  │     │           │     └── child.conversationService = null  ← 不写 DB
  │     │           ├── child.run(sessionId, task, [])  ← 独立 ReAct 循环
  │     │           │     ├── [子迭代1] tool_call: read_file → 文件内容
  │     │           │     └── [子迭代2] "## 分析摘要\n..."
  │     │           └── return 摘要 → 作为 tool_result 给主 Agent
  │     ├── observation: "## 分析摘要\n..."  ← 只有摘要，无原始输出
  │     └── [迭代2] LLM 基于摘要给出最终回答
  └── chat_message 表：只有 run_subagent 记录，无子代理内部 read_file 等
```

### 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| 触发方式 | `run_subagent` 工具（标准 tool_call） | ReactAgent 循环零改动 |
| 上下文隔离 | `child.run(sessionId, task, List.of())` — 空历史 | 子代理不继承主会话 |
| 递归防护 | 子代理工具列表不含 `run_subagent` | 简单可靠 |
| Hook 继承 | PreToolUse + PostToolUse 继承，Stop 不继承 | 安全检查不跳过 |
| 不回写 DB | `conversationService=null` | 子代理消息不入主会话 |
| 超时保护 | `CompletableFuture.orTimeout(120, SECONDS)` | 防止子代理跑飞 |

### 验证步骤

> 环境 JDK 不可用，需在宿主机 IDEA 中运行。

**Step 1: 编译**
```
mvn compile → 确认所有新文件通过编译
```

**Step 2: 启动**
```
启动应用，检查日志：
  enabled=true  → "子代理已启用，run_subagent 工具可用"
  enabled=false → "子代理未启用，run_subagent 工具已移除"
```

**Step 3: 端到端**
```
1. 沙箱 /home/gem/workspace/ 创建 test.log
2. 发送消息："分析 /home/gem/workspace/test.log 中的内容"
3. 验证日志链路：
   "[Hook] 工具调用: run_subagent"
   → "启动子代理: type=analyzer" + "Fork 子 Agent"
   → "子代理完成: type=analyzer, 迭代=N"
4. 验证主回答只含摘要，不含 read_file 原始输出
5. 验证 chat_message 表无子代理内部的 read_file 等记录
```

**Step 4: Feature Flag**
```
agent.sub-agent.enabled: false → 日志显示 "子代理未启用，run_subagent 工具已移除"
改回 true → 工具恢复
```

---

## 二期：后台并行执行（设计阶段）

> 一期验证通过后执行。

### 问题

一期串行模式下，主 Agent 调 `run_subagent` 后会阻塞等待结果。如果用户请求涉及两个独立子任务（如"分析 app.log 中的错误 + 搜索知识库中的解决方案"），LLM 只能：

```
串行（一期）：
  第1轮: run_subagent(analyzer) → 等 15s → 拿到摘要
  第2轮: run_subagent(searcher) → 等 5s → 拿到摘要
  第3轮: 综合回答
  总耗时: ~22s（全在主循环里干等）
```

### 方案：`run_in_background` 参数（s13 模式）

参考教学材料 s13 Background Tasks：慢操作扔后台线程，Agent 继续跑循环，完成后通过 `<task_notification>` 注入。

```
流水线并行（二期）：
  第1轮: run_subagent(analyzer, run_in_background=true) → 立即返回 "bg_0001 已启动"
         ├─ [后台] analyzer 子代理开始跑...
  第2轮: run_subagent(searcher, run_in_background=true) → 立即返回 "bg_0002 已启动"  
         ├─ [后台] searcher 子代理开始跑...
         ├─ [后台] analyzer 完成 → 通知入队
  第3轮: 收到 <task_notification> analyzer 摘要
         ├─ [后台] searcher 完成 → 通知入队
  第4轮: 收到 <task_notification> searcher 摘要
         LLM 综合两个摘要 → 最终回答
  总耗时: ~17s（主循环不阻塞，两个子代理在后台重叠执行）
```

### 为什么不需要多 tool_call

一期和二期都是 **单 tool_call per turn**，不存在并发安全问题：

- LLM 每轮只调一个 `run_subagent`（现有约束不变）
- 后台模式让这个调用**不阻塞**，Agent 立刻进入下一轮
- 下一轮可以继续调另一个 `run_subagent(background=true)`
- 两个子代理在后台线程中**真正并行**执行
- 完成后通知按序注入，LLM 看到时两个摘要都已就绪

这避开了你指出的"不同工具能否安全并发"的严谨性问题——根本不需要改多 tool_call。

### 子代理不对前端暴露

子代理完全是后端行为——主 Agent 调它就像调 `read_file`。前端只看到：

```
SSE 事件流（二期）：
  thinking_start → token → token → ...
  tool_call: run_subagent(type=analyzer, ...)  ← 前端看到这里
  observation: "[后台子代理 bg_0001 已启动]..."  ← 不阻塞
  thinking_start → token → ...
  tool_call: run_subagent(type=searcher, ...)   ← 前端看到这里
  observation: "[后台子代理 bg_0002 已启动]..."  ← 不阻塞
  thinking_start → token → ...
  observation: "<task_notification> analyzer 摘要"  ← 后台完成，前端看到通知
  observation: "<task_notification> searcher 摘要"  ← 后台完成，前端看到通知
  answer → done
```

前端不需要理解子代理——它看到的仍然是 thinking / tool_call / observation / answer 事件流。只是 observation 里可能出现 `task_notification` 格式的文本。

### 增量改动（二期合计 ~55 行）

#### 1. RunSubagentTool（+35 行）

```java
// === 新增字段 ===
/** 后台通知队列：sessionId → Queue<通知文本> */
private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>
        backgroundNotifications = new ConcurrentHashMap<>();

// === 工具定义增量 ===
properties.put("run_in_background", Map.of(
    "type", "boolean",
    "description", "设为 true 时子代理在后台执行，主 Agent 不等待。"
        + "多个后台子代理可以并行。完成后以 <task_notification> 格式通知主 Agent。"
        + "适用：多个独立子任务需要并行执行时。"
));

// === execute() 分叉 ===
boolean isBackground = Boolean.TRUE.equals(arguments.get("run_in_background"));

if (isBackground) {
    String bgId = "bg_" + UUID.randomUUID().toString().substring(0, 8);
    String typeForLog = type;
    String taskForLog = task.length() > 100 ? task.substring(0, 100) + "..." : task;

    CompletableFuture.runAsync(() -> {
        try {
            AgentResponse response = child.run(sessionId, task, List.of());
            String summary = response.getFinalAnswer();
            String notification = String.format("""
                <task_notification>
                  <task_id>%s</task_id>
                  <type>%s</type>
                  <status>completed</status>
                  <summary>%s</summary>
                </task_notification>""", bgId, typeForLog, summary);
            enqueueNotification(sessionId, notification);
            log.info("后台子代理完成: bgId={}, type={}", bgId, typeForLog);
        } catch (Exception e) {
            String errorNote = String.format("""
                <task_notification>
                  <task_id>%s</task_id>
                  <type>%s</type>
                  <status>failed</status>
                  <summary>%s</summary>
                </task_notification>""", bgId, typeForLog, e.getMessage());
            enqueueNotification(sessionId, errorNote);
        }
    });

    log.info("后台子代理已启动: bgId={}, type={}, task={}", bgId, type, taskForLog);
    return String.format("[后台子代理 %s 已启动] 类型=%s，任务=%s。完成后将通知结果。",
            bgId, type, taskForLog);
}
// else: 现有同步逻辑不变

// === 新增静态方法 ===
/** 将通知入队到指定会话 */
private static void enqueueNotification(String sessionId, String notification) {
    backgroundNotifications
        .computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>())
        .add(notification);
}

/** 取出并清空指定会话的所有待处理通知，用双换行拼接 */
public static String drainNotifications(String sessionId) {
    ConcurrentLinkedQueue<String> queue = backgroundNotifications.get(sessionId);
    if (queue == null || queue.isEmpty()) {
        return null;
    }
    StringBuilder sb = new StringBuilder();
    String notification;
    while ((notification = queue.poll()) != null) {
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(notification);
    }
    return sb.toString();
}
```

#### 2. ReactAgent.run() 增量（+10 行）

```java
// 在 while 循环开头，compressIfNeeded() 之后、LLM 调用之前：
while (iteration < MAX_ITERATIONS) {
    iteration++;
    compressIfNeeded(messages);

    // 🔔 收集后台子代理的完成通知
    String bgNotification = RunSubagentTool.drainNotifications(sessionId);
    if (bgNotification != null) {
        log.info("注入后台子代理通知: {} 字符", bgNotification.length());
        messages.add(ChatMessage.userMessage(bgNotification));
    }

    String prompt = effectiveSystemPrompt();
    LlmResponse response = llmService.chatWithTools(prompt, messages, toolDefinitions);
    // ... 后续不变
```

#### 3. ReactAgent.runStream() 增量（+10 行）

```java
// 流式版本中同样位置，在每次 LLM 调用前 drain 并注入：
// SSE 版本的心跳/中断检查逻辑之前，添加同样的 drain 逻辑
String bgNotification = RunSubagentTool.drainNotifications(sessionId);
if (bgNotification != null) {
    log.info("注入后台子代理通知: {} 字符", bgNotification.length());
    messages.add(ChatMessage.userMessage(bgNotification));
    // 可选：通过 sink.next(SseEvent.message(...)) 告知前端有后台任务完成
}
```

### 并发安全性分析

| 场景 | 安全？ | 原因 |
|---|---|---|
| 两个 `run_subagent(bg=true)` 同时执行 | ✅ | 各自独立上下文 + 独立沙箱操作 |
| `run_subagent(bg=true)` + `read_file` 同一轮 | ✅ | 单 tool_call per turn，不会同轮出现 |
| 子代理写文件 + 主 Agent 读文件 | ⚠️ 时序依赖 | 和任何工具的时序问题一样，LLM 判断 |
| 两个子代理写同一文件 | ⚠️ 竞态 | LLM 不应分配冲突任务（和分配两个 `write_file` 一样） |
| 通知注入时机 | ✅ | 在 LLM 调用前 drain，不会和 tool_result 混淆 |
| 通知队列并发 | ✅ | `ConcurrentLinkedQueue` 线程安全 |

核心结论：**不需要 `isParallelSafe()` 声明**。因为每个 turn 仍然只有一个 tool_call，并发只发生在子代理的后台线程之间，而子代理之间天然隔离。主循环始终保持单线程语义。

### 配置增量

```yaml
agent:
  sub-agent:
    enabled: ${SUB_AGENT_ENABLED:false}
    timeout-seconds: ${SUB_AGENT_TIMEOUT:120}
    background:
      # 同一会话最多同时运行的后台子代理数
      max-concurrent: ${SUB_AGENT_MAX_CONCURRENT:5}
    types:
      # ... 不变
```

---

## 不做的

- ❌ 不实现多 tool_call 扇出（一期和二期都是单 tool_call per turn，不需改 `LlmResponse` + LLM 适配层）
- ❌ 不实现子代理 SSE 流式（子代理内部用 `run()`，前端只看主 Agent 的 SSE）
- ❌ 不实现 Fork 模式 Prompt Cache 共享（API 层优化，按需）
- ❌ 不实现后台任务的看门狗/暂停/取消（一期没有这个需求，按需）

---

## 提交计划

一期代码已就绪：

```
git add src/main/java/com/example/sandbox/web/service/tool/RunSubagentTool.java
git add src/main/java/com/example/sandbox/web/config/SubAgentConfigProperties.java
git add src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java
git add src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java
git add src/main/resources/application.yml
git add docs/superpowers/plans/2026-06-23-subagent-tool.md
git commit -m "引入 run_subagent 工具：子代理作为标准工具，上下文隔离

一期（s06 Subagent）：
- 新增 RunSubagentTool：4 种类型 (analyzer/searcher/browser/general)
- ReactAgent.fork()：创建子实例，继承 Hook，独立 messages[]
- SubAgentConfigProperties & application.yml 配置支持
- AgentServiceImpl：feature flag 控制 + parentAgent 注入
- 子代理消息不入主会话 DB，只返回结构化摘要

二期（s13 Background）设计已完成，待一期验证通过后实施。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
