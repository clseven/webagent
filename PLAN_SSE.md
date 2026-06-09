# SSE 流式输出 + 可中断架构改造计划

## 一、目标

将现有的同步阻塞 HTTP 改为 SSE（Server-Sent Events）流式输出，实现：
1. **流式输出**：实时显示 LLM 思考过程、工具执行结果
2. **可中断**：用户随时可以停止，后端立刻释放资源
3. **结构化消息**：前端能区分"思考"、"工具调用"、"结果"等不同类型

---

## 二、现状分析

### 当前架构

```
前端                              后端
  │                                 │
  │── POST /chat ──────────────────→│
  │    (loading...)                  │  ReactAgent.run()
  │    (loading...)                  │  ├─ while 循环 20 轮
  │    (loading...)                  │  ├─ 调 LLM
  │    (loading...)                  │  ├─ 执行工具
  │    (loading...)                  │  └─ 返回结果
  │←── 完整响应 ────────────────────│
```

**问题**：
- 用户只能等，中途无法停止
- 看不到执行过程，体验差
- 后端资源无法及时释放

### 涉及文件

| 文件 | 当前作用 |
|------|----------|
| `AgentController.java` | HTTP 入口，返回 `ApiResponse<ChatMessage>` |
| `AgentServiceImpl.java` | 编排 Plan → Execute |
| `ReactAgent.java` | ReAct 循环，返回 `AgentResponse` |
| `PlanAgent.java` | 规划，返回 `PlanResult` |

---

## 三、目标架构

### SSE 流式架构

```
前端                              后端
  │                                 │
  │── GET /chat/stream ────────────→│
  │←── event: thinking ────────────│  LLM 思考中...
  │←── event: tool_call ───────────│  调用 read_file
  │←── event: observation ─────────│  工具返回结果
  │←── event: thinking ────────────│  LLM 继续思考...
  │←── event: tool_call ───────────│  调用 execute_command
  │←── event: observation ─────────│  命令输出...
  │                                  │
  │── 关闭连接（用户点停止）─────────→│  检测到断开
  │                                  │  循环退出
  │                                  │  资源释放
```

### 消息类型定义

| 事件类型 | 数据 | 说明 |
|----------|------|------|
| `token` | `{ content }` | LLM 生成的单个 token（实时打字效果） |
| `reasoning_token` | `{ content }` | 思考链的单个 token（推理模型的思考过程） |
| `thinking_start` | `{ stepIndex }` | 本轮思考开始 |
| `thinking_end` | `{ }` | 本轮思考结束 |
| `tool_call` | `{ tool, args, stepIndex }` | 工具调用开始（显示"xxx工具调用中..."） |
| `tool_executing` | `{ tool, elapsed }` | 工具执行中（可选，显示耗时） |
| `observation` | `{ tool, result, duration }` | 工具执行结果 |
| `plan` | `{ content }` | 规划结果 |
| `answer` | `{ content, reasoning }` | 最终答案 |
| `error` | `{ message }` | 错误信息 |
| `done` | `{ iterations, tokenUsage }` | 执行完成 |
| `interrupted` | `{ reason }` | 用户中断 |

### 渲染样式设计

**可折叠元素**（默认折叠，可展开）：
- 思考过程（LLM 思考内容 + 思考链）
- 工具调用（参数 + 结果 + 耗时）

**不折叠元素**（直接展示）：
- 最终答案
- 错误信息

**实时状态**（顶部气泡）：
- LLM 思考中（token 实时出现）
- 工具调用中（转圈动画）

---

## 四、改动清单

### Phase 1：基础设施（后端）

#### 1.1 新增 SSE 事件模型

**新建文件**：`src/main/java/.../model/sse/SseEvent.java`

```java
public record SseEvent(
    String type,      // token / thinking_start / tool_call / observation / ...
    Map<String, Object> data
) {
    // Token 级流式：每个 LLM token 一个事件
    public static SseEvent token(String content) {
        return new SseEvent("token", Map.of("content", content));
    }

    // 步骤级：思考开始/结束
    public static SseEvent thinkingStart(int step) {
        return new SseEvent("thinking_start", Map.of("stepIndex", step));
    }
    public static SseEvent thinkingEnd() {
        return new SseEvent("thinking_end", Map.of());
    }

    // 工具调用
    public static SseEvent toolCall(String tool, Map<String, Object> args, int step) {
        return new SseEvent("tool_call", Map.of(
            "tool", tool, "args", args, "stepIndex", step));
    }
    public static SseEvent toolExecuting(String tool, long elapsed) {
        return new SseEvent("tool_executing", Map.of("tool", tool, "elapsed", elapsed));
    }
    public static SseEvent observation(String tool, String result, long duration) {
        return new SseEvent("observation", Map.of(
            "tool", tool, "result", result, "duration", duration));
    }

    // 规划
    public static SseEvent plan(String content) {
        return new SseEvent("plan", Map.of("content", content));
    }

    // 最终答案
    public static SseEvent answer(String content, String reasoning) {
        return new SseEvent("answer", Map.of("content", content, "reasoning", reasoning));
    }

    // 状态
    public static SseEvent done(int iterations, LlmUsage usage) {
        return new SseEvent("done", Map.of("iterations", iterations, "tokenUsage", usage));
    }
    public static SseEvent error(String message) {
        return new SseEvent("error", Map.of("message", message));
    }
    public static SseEvent interrupted(String reason) {
        return new SseEvent("interrupted", Map.of("reason", reason));
    }
}
```

#### 1.2 新增流式 LLM 调用

**修改文件**：`LlmService.java` 和 `BaseLlmServiceImpl.java`

这是 **token 级流式的核心**。

```java
// LlmService.java - 新增方法
public interface LlmService {
    // 已有方法...

    // 新增：流式聊天（返回 Flux<String>，每个元素是一个 token）
    Flux<String> chatStream(String systemPrompt, List<ChatMessage> messages);

    // 新增：流式带工具（返回 Flux<LlmStreamChunk>）
    Flux<LlmStreamChunk> chatWithToolsStream(
        String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools);
}
```

```java
// 新增：流式响应块
public record LlmStreamChunk(
    String type,      // "token" / "tool_call" / "reasoning" / "finish"
    String content,   // token 文本（type=token 时）
    String reasoning, // 思考链（type=reasoning 时）
    LlmToolCall toolCall,  // 工具调用（type=tool_call 时）
    LlmUsage usage,   // token 用量（type=finish 时）
    boolean finished
) {
    public static LlmStreamChunk token(String content) {
        return new LlmStreamChunk("token", content, null, null, null, false);
    }
    public static LlmStreamChunk reasoning(String content) {
        return new LlmStreamChunk("reasoning", null, content, null, null, false);
    }
    public static LlmStreamChunk toolCall(LlmToolCall toolCall) {
        return new LlmStreamChunk("tool_call", null, null, toolCall, null, false);
    }
    public static LlmStreamChunk finish(LlmUsage usage) {
        return new LlmStreamChunk("finish", null, null, null, usage, true);
    }
}
```

```java
// BaseLlmServiceImpl.java - 新增流式实现
public Flux<String> chatStream(String systemPrompt, List<ChatMessage> messages) {
    return Flux.create(emitter -> {
        try {
            LlmRequest request = buildLlmRequest(systemPrompt, messages, null);
            // ★ 关键：设置 stream: true
            request.setStream(true);

            // 用 WebClient 流式读取
            webClient.post()
                .uri("/chat/completions")
                .bodyValue(request.toApiFormat())
                .retrieve()
                .bodyToFlux(String.class)  // ← 流式读取响应
                .subscribe(
                    chunk -> {
                        // 解析 SSE 格式：data: {...}
                        if (chunk.startsWith("data: ")) {
                            String json = chunk.substring(6);
                            if ("[DONE]".equals(json)) return;

                            // 解析 JSON 提取 token
                            String token = parseTokenFromStreamChunk(json);
                            if (token != null) {
                                emitter.next(token);
                            }
                        }
                    },
                    error -> emitter.error(error),
                    () -> emitter.complete()
                );

        } catch (Exception e) {
            emitter.error(e);
        }
    });
}

// 带工具的流式调用
public Flux<LlmStreamChunk> chatWithToolsStream(
        String systemPrompt, List<ChatMessage> messages, List<ToolDefinition> tools) {
    return Flux.create(emitter -> {
        try {
            LlmRequest request = buildLlmRequest(systemPrompt, messages, tools);
            request.setStream(true);

            // 累积 token（流式响应中 token 是逐个返回的）
            StringBuilder contentBuilder = new StringBuilder();
            StringBuilder reasoningBuilder = new StringBuilder();
            LlmToolCall toolCall = null;

            webClient.post()
                .uri("/chat/completions")
                .bodyValue(request.toApiFormat())
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> {
                        if (chunk.startsWith("data: ")) {
                            String json = chunk.substring(6);
                            if ("[DONE]".equals(json)) {
                                // 流结束，判断是工具调用还是普通结束
                                if (toolCall != null) {
                                    emitter.next(LlmStreamChunk.toolCall(toolCall));
                                }
                                emitter.next(LlmStreamChunk.finish(null));
                                emitter.complete();
                                return;
                            }

                            // 解析单个 chunk
                            LlmStreamDelta delta = parseStreamDelta(json);
                            if (delta != null) {
                                if (delta.content() != null) {
                                    contentBuilder.append(delta.content());
                                    emitter.next(LlmStreamChunk.token(delta.content()));
                                }
                                if (delta.reasoning() != null) {
                                    reasoningBuilder.append(delta.reasoning());
                                    emitter.next(LlmStreamChunk.reasoning(delta.reasoning()));
                                }
                                if (delta.toolCall() != null) {
                                    // 工具调用可能跨多个 chunk 累积
                                    toolCall = mergeToolCall(toolCall, delta.toolCall());
                                }
                            }
                        }
                    },
                    error -> emitter.error(error),
                    () -> emitter.complete()
                );

        } catch (Exception e) {
            emitter.error(e);
        }
    });
}
```

**OpenAI 流式响应格式**（DeepSeek/智谱类似）：

```
data: {"id":"...","choices":[{"delta":{"content":"我"},"index":0}]}

data: {"id":"...","choices":[{"delta":{"content":"要"},"index":0}]}

data: {"id":"...","choices":[{"delta":{"content":"读取"},"index":0}]}

data: {"id":"...","choices":[{"delta":{"tool_calls":[{"function":{"name":"read_file","arguments":"..."}}]},"index":0}]}

data: [DONE]
```

#### 1.3 新增流式 Agent 接口

**修改文件**：`AgentService.java`

```java
public interface AgentService {
    // 已有：同步版本
    ChatMessage chat(String sessionId, String userMessage);

    // 新增：流式版本
    Flux<SseEvent> chatStream(String sessionId, String userMessage);
}
```

#### 1.4 改造 ReactAgent 为双层流式

**修改文件**：`ReactAgent.java`

核心改动：
- **外层循环**：每个步骤 yield 一个事件（工具调用、结果等）
- **LLM 调用**：内部 token 级流式，每个 token yield 给前端

```java
public Flux<SseEvent> runStream(String sessionId, String userMessage,
                                 List<ChatMessage> history) {
    return Flux.create(emitter -> {
        try {
            int iteration = 0;
            StringBuilder currentThinking = new StringBuilder();
            StringBuilder currentReasoning = new StringBuilder();

            while (!emitter.isCancelled() && iteration < MAX_ITERATIONS) {
                iteration++;

                // ★ 通知前端：开始新一轮思考
                emitter.next(SseEvent.thinkingStart(iteration));

                currentThinking.setLength(0);
                currentReasoning.setLength(0);

                // ★ 关键：流式调用 LLM
                final int currentStep = iteration;
                llmService.chatWithToolsStream(prompt, messages, toolDefinitions)
                    .subscribe(
                        chunk -> {
                            switch (chunk.type()) {
                                case "token":
                                    // ★ 每个 token 发给前端
                                    currentThinking.append(chunk.content());
                                    emitter.next(SseEvent.token(chunk.content()));
                                    break;
                                case "reasoning":
                                    currentReasoning.append(chunk.reasoning());
                                    emitter.next(SseEvent.thinking_token_reasoning(chunk.reasoning()));
                                    break;
                                case "tool_call":
                                    // LLM 决定调工具
                                    LlmToolCall toolCall = chunk.toolCall();
                                    emitter.next(SseEvent.toolCall(
                                        toolCall.name(),
                                        toolCall.arguments(),
                                        currentStep
                                    ));

                                    // 立即执行工具
                                    emitter.next(SseEvent.thinkingEnd());

                                    // ★ 前端显示"read_file 工具调用中..."
                                    emitter.next(SseEvent.toolExecuting(
                                        toolCall.name(), 0));

                                    long startTime = System.currentTimeMillis();
                                    String result = executeTool(sessionId,
                                        toolCall.name(), toolCall.arguments());
                                    long duration = System.currentTimeMillis() - startTime;

                                    // ★ 工具结果发给前端
                                    emitter.next(SseEvent.observation(
                                        toolCall.name(), result, duration));

                                    // 累积到消息历史
                                    messages.add(ChatMessage.assistantMessage(...));
                                    messages.add(ChatMessage.userMessage(
                                        "Observation: " + result));
                                    break;
                                case "finish":
                                    // LLM 输出完成
                                    emitter.next(SseEvent.thinkingEnd());

                                    if (chunk.toolCall() == null) {
                                        // LLM 直接给答案（没调工具）
                                        emitter.next(SseEvent.answer(
                                            currentThinking.toString(),
                                            currentReasoning.toString()
                                        ));
                                        emitter.next(SseEvent.done(iteration, null));
                                    }
                                    break;
                            }
                        },
                        error -> {
                            emitter.next(SseEvent.error(error.getMessage()));
                        },
                        () -> {
                            // LLM 流结束
                        }
                    );

                // 检查是否完成
                if (isFinished) {
                    break;
                }
            }

            // 中断处理
            if (emitter.isCancelled()) {
                emitter.next(SseEvent.interrupted("用户手动暂停"));
            }

        } finally {
            emitter.complete();
        }
    });
}
```

**完整的事件流**：

```
1. thinking_start
2. token: "我"
3. token: "要"
4. token: "读"
5. token: "取"
6. token: "文件"
7. thinking_end
8. tool_call: { tool: "read_file", args: {...} }
9. tool_executing: { tool: "read_file", elapsed: 0 }
10. observation: { tool: "read_file", result: "...", duration: 200 }
11. thinking_start
12. token: "文"
13. token: "件"
14. token: "内容"
15. ...
```

#### 1.4 改造 AgentServiceImpl

**修改文件**：`AgentServiceImpl.java`

```java
public Flux<SseEvent> chatStream(String sessionId, String userMessage) {
    return Flux.create(emitter -> {
        try {
            // 1. 前置处理（和同步版本一样）
            ConversationSession session = getSession(sessionId);
            List<ChatMessage> history = loadHistory(sessionId);
            List<Tool> tools = filterTools(sessionId);

            // 2. 规划（可以 yield plan 事件）
            emitter.next(SseEvent.plan("开始规划..."));
            PlanResult plan = planAgent.plan(userMessage);
            emitter.next(SseEvent.plan(plan.getPlan()));

            // 3. 流式执行
            ReactAgent agent = new ReactAgent(executorLlm, tools, systemPrompt, plan.getPlan());

            agent.runStream(sessionId, userMessage, history)
                .doOnNext(emitter::next)
                .doOnError(e -> emitter.next(SseEvent.error(e.getMessage())))
                .doOnComplete(() -> {
                    // 后置处理：保存消息
                    saveMessages(sessionId, ...);
                })
                .subscribe();

        } catch (Exception e) {
            emitter.next(SseEvent.error(e.getMessage()));
            emitter.complete();
        }
    });
}
```

#### 1.5 新增 SSE Controller 端点

**修改文件**：`AgentController.java`

```java
@GetMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<SseEvent>> chatStream(
        @PathVariable String id,
        @RequestParam String message) {

    return agentService.chatStream(id, message)
        .map(event -> ServerSentEvent.<SseEvent>builder()
            .event(event.type())
            .data(event)
            .build());
}
```

---

### Phase 2：依赖调整

#### 2.1 pom.xml

确认已有 `spring-boot-starter-webflux`（已存在）。

#### 2.2 消息存储

流式执行过程中需要**延迟存储**消息，等全部完成后再存：

```java
// 执行过程中
List<ChatMessage> pendingMessages = new ArrayList<>();

// 每轮追加到 pending
pendingMessages.add(ChatMessage.userMessage("Observation: ..."));

// 完成后一次性存 DB
conversationService.saveMessages(sessionId, pendingMessages);
```

#### 2.3 Token 用量统计

流式过程中累积统计：

```java
AtomicInteger totalPromptTokens = new AtomicInteger(0);
AtomicInteger totalCompletionTokens = new AtomicInteger(0);

// 每轮 LLM 调用后累加
totalPromptTokens.addAndGet(usage.promptTokens());
```

---

### Phase 3：前端改造

#### 3.1 API 调用

```javascript
async function chatStream(sessionId, message, onEvent, onStop) {
    const eventSource = new EventSource(
        `/api/sessions/${sessionId}/chat/stream?message=${encodeURIComponent(message)}`
    );

    // Token 级：实时显示 LLM 输出（打字效果）
    eventSource.addEventListener('token', (e) => {
        const data = JSON.parse(e.data);
        onEvent({ type: 'token', content: data.content });
    });

    // 思考开始
    eventSource.addEventListener('thinking_start', (e) => {
        onEvent({ type: 'thinkingStart' });
    });

    // 工具调用（显示"xxx 工具调用中..."）
    eventSource.addEventListener('tool_call', (e) => {
        const data = JSON.parse(e.data);
        onEvent({ type: 'toolCall', tool: data.tool, args: data.args });
    });

    // 工具执行中（可选，显示耗时）
    eventSource.addEventListener('tool_executing', (e) => {
        const data = JSON.parse(e.data);
        onEvent({ type: 'toolExecuting', tool: data.tool, elapsed: data.elapsed });
    });

    // 工具结果
    eventSource.addEventListener('observation', (e) => {
        const data = JSON.parse(e.data);
        onEvent({ type: 'observation', result: data.result, duration: data.duration });
    });

    // 思考结束
    eventSource.addEventListener('thinking_end', (e) => {
        onEvent({ type: 'thinkingEnd' });
    });

    // 规划结果
    eventSource.addEventListener('plan', (e) => {
        const data = JSON.parse(e.data);
        onEvent({ type: 'plan', content: data.content });
    });

    // 最终答案
    eventSource.addEventListener('answer', (e) => {
        const data = JSON.parse(e.data);
        onEvent({ type: 'answer', content: data.content });
    });

    // 执行完成
    eventSource.addEventListener('done', (e) => {
        eventSource.close();
        onEvent({ type: 'done' });
    });

    // 中断
    eventSource.addEventListener('interrupted', (e) => {
        onEvent({ type: 'interrupted' });
    });

    // 错误
    eventSource.addEventListener('error', (e) => {
        eventSource.close();
        onEvent({ type: 'error', message: e.data });
    });

    // 返回停止函数
    return () => {
        eventSource.close();  // ★ 关闭连接 = 中断后端
        onStop?.();
    };
}
```

#### 3.2 UI 组件（完整体验）

```javascript
function ChatBox() {
    const [currentStep, setCurrentStep] = useState(null);
    const [thinkingText, setThinkingText] = useState('');
    const [events, setEvents] = useState([]);
    const [stopFn, setStopFn] = useState(null);

    const handleChat = async (message) => {
        const stop = await chatStream(sessionId, message, (event) => {
            switch (event.type) {
                case 'thinkingStart':
                    setCurrentStep({ type: 'thinking' });
                    setThinkingText('');
                    break;
                case 'token':
                    // ★ 实时追加 token（打字效果）
                    setThinkingText(prev => prev + event.content);
                    break;
                case 'thinkingEnd':
                    setCurrentStep(null);
                    // 保存这一步的思考内容
                    setEvents(prev => [...prev, {
                        type: 'thinking',
                        content: thinkingText
                    }]);
                    break;
                case 'toolCall':
                    setCurrentStep({
                        type: 'toolCalling',
                        tool: event.tool,
                        args: event.args
                    });
                    break;
                case 'toolExecuting':
                    // 显示"read_file 工具调用中..."
                    setCurrentStep({
                        type: 'toolExecuting',
                        tool: event.tool,
                        elapsed: event.elapsed
                    });
                    break;
                case 'observation':
                    setCurrentStep(null);
                    setEvents(prev => [...prev, {
                        type: 'toolResult',
                        tool: event.tool,
                        result: event.result
                    }]);
                    break;
                case 'answer':
                    setEvents(prev => [...prev, {
                        type: 'answer',
                        content: event.content
                    }]);
                    break;
                case 'done':
                    setStopFn(null);
                    break;
            }
        });
        setStopFn(() => stop);
    };

    const handleStop = () => {
        stopFn?.();  // 关闭 SSE 连接
        setStopFn(null);
    };

    return (
        <div>
            <MessageList events={events} />

            {/* 实时状态显示 */}
            {currentStep?.type === 'thinking' && (
                <ThinkingBubble text={thinkingText} />
            )}
            {currentStep?.type === 'toolExecuting' && (
                <ToolExecutingCard
                    tool={currentStep.tool}
                    elapsed={currentStep.elapsed}
                />
            )}

            {/* 停止按钮 */}
            {stopFn && <Button onClick={handleStop}>停止</Button>}
        </div>
    );
}
```

#### 3.3 消息渲染

```javascript
function MessageList({ events }) {
    return events.map((event, i) => {
        switch (event.type) {
            case 'thinking':
                // 一轮完整的 LLM 思考
                return <ThinkingCard key={i} content={event.content} />;
            case 'toolResult':
                return <ToolResultCard key={i}
                    tool={event.tool}
                    result={event.result} />;
            case 'answer':
                return <AnswerCard key={i} content={event.content} />;
            default:
                return null;
        }
    });
}

// 工具执行中卡片（前端显示"xxx 工具调用中..."）
function ToolExecutingCard({ tool, elapsed }) {
    return (
        <Card>
            <Spinner /> 正在调用工具: <strong>{tool}</strong>
            {elapsed > 0 && <span> ({elapsed}ms)</span>}
        </Card>
    );
}

// 思考卡片（完整显示一轮 token 累积的内容）
function ThinkingCard({ content }) {
    return (
        <Card>
            <Icon>💭</Icon> AI 思考:
            <Content>{content}</Content>
        </Card>
    );
}
```

#### 3.4 完整体验时序

```
用户发消息"帮我分析日志"
    ↓
┌────────────────────────────────────────────┐
│ 规划阶段                                    │
│   [规划中...]                               │
│   [规划结果: 1.读取日志 2.分析错误 3.总结]   │
└────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────┐
│ 第 1 轮（可折叠，默认折叠）                   │
│ ▼ 💭 思考过程: 我要读取日志...Action:read...  │
│   （点击展开看详情）                          │
└────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────┐
│ 工具调用（可折叠）                           │
│ ▼ 🔧 read_file 调用完成 (200ms)             │
│   参数: { path: "/logs/app.log" }            │
│   结果: 日志内容...                           │
└────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────┐
│ 第 2 轮（可折叠）                            │
│ ▼ 💭 思考过程: 我发现了 3 个错误...           │
└────────────────────────────────────────────┘
    ↓
... 继续循环 ...
    ↓
┌────────────────────────────────────────────┐
│ 最终答案（不折叠，直接展示）                  │
│ 📋 分析结果:                                │
│   系统存在 3 个错误：                        │
│   1. NullPointerException...              │
│   2. OutOfMemoryError...                   │
│   3. ConnectionTimeout...                  │
└────────────────────────────────────────────┘
```

#### 3.5 实时状态显示

```
┌────────────────────────────────────────────┐
│ 正在思考中...                               │
│ 💭 我要读取日志 (token 实时出现)              │
└────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────┐
│ ⏳ read_file 工具调用中... (转圈动画)        │
└────────────────────────────────────────────┘
    ↓
（执行完成，转圈消失）
```

---

### Phase 4：中断处理

#### 4.1 中断时的消息补全

当 SSE 连接断开时，需要补全一条"中断"消息：

```java
// ReactAgent.java - Flux.create 的 finally 块
return Flux.create(emitter -> {
    try {
        while (!emitter.isCancelled() && ...) {
            // 正常执行
        }
    } finally {
        if (emitter.isCancelled()) {
            // ★ 用户中断了，补全消息
            String interruptionNote = "【用户手动暂停】任务被中断。";
            conversationService.addUserMessage(sessionId, interruptionNote);
            conversationService.addAssistantMessage(sessionId, "任务已暂停。");
        }
        emitter.complete();
    }
});
```

#### 4.2 工具执行中途中断

工具执行是阻塞的，无法中途中断。但可以：

1. **接受现实**：工具跑完才算真正停止（可能 1-2 秒延迟）
2. **加超时**：工具执行加短超时（如 30 秒），超时自动失败

```java
private String executeTool(...) {
    return CompletableFuture.supplyAsync(() -> tool.execute(...))
        .orTimeout(30, TimeUnit.SECONDS)  // ★ 超时控制
        .exceptionally(e -> "工具执行超时或被中断")
        .join();
}
```

---

## 五、保留兼容性

### 双端点并存

```
POST /api/sessions/{id}/chat        → 同步版本（保留）
GET  /api/sessions/{id}/chat/stream → 流式版本（新增）
```

前端可以逐步迁移，先改一个页面试用流式，稳定后再全部切换。

---

## 六、测试计划

### 6.1 单元测试

- `SseEvent` 序列化/反序列化
- `ReactAgent.runStream()` 流式产出
- 中断检测（`emitter.isCancelled()`）

### 6.2 集成测试

- SSE 连接建立
- 流式接收事件
- 中断后端是否停止
- 中断后消息是否补全

### 6.3 手动测试

| 场景 | 预期 |
|------|------|
| 正常执行完成 | 收到完整事件流，done 事件最后到达 |
| 执行中途停止 | SSE 断开，后端循环退出，消息补全 |
| 网络断开 | 后端检测到连接断开，自动清理 |
| 并发多会话 | 各会话独立，互不影响 |

---

## 七、风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| SSE 连接不稳定 | 加心跳事件，前端重连机制 |
| 工具执行无法中断 | 接受 1-2 秒延迟，或加超时 |
| 消息丢失 | 执行过程中缓存，完成后再存 DB |
| 并发压力 | WebFlux 天然支持异步，压力比同步更小 |

---

## 八、实施顺序

| 阶段 | 任务 | 预估时间 |
|------|------|----------|
| **Phase 1** | 后端 SSE 事件模型 | 0.5 天 |
| **Phase 2** | LLM 流式调用（token 级） | 1.5 天 |
| **Phase 3** | ReactAgent 双层流式改造 | 1.5 天 |
| **Phase 4** | AgentServiceImpl 流式编排 | 0.5 天 |
| **Phase 5** | Controller SSE 端点 | 0.5 天 |
| **Phase 6** | 中断处理 + 消息补全 | 0.5 天 |
| **Phase 7** | 前端 token 级渲染 + 工具调用提示 | 1.5 天 |
| **Phase 8** | 测试 + 修复 | 1 天 |

**总计：约 7-8 天**

---

## 九、开始前的确认

1. **是否保留同步接口**：保留 `/chat` 作为备用？
2. **前端框架**：你用的是什么前端框架？（React/Vue/其他）
3. **消息渲染**：是否需要渲染"思考链"（reasoning content）？
4. **工具超时**：默认 30 秒还是其他值？

---

确认后开始实施。
