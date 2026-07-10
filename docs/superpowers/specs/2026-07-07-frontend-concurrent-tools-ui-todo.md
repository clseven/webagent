# 前端改造备忘：并发工具的分层折叠 UI（已实现）

> 状态：**已实现（2026-07-07）**。后端并发（见 `2026-07-06-stop-hook-evidence-self-check-design.md` 第九章）已落地且默认开启：`ToolScheduler` 让一轮多个 tool_call 并发执行。前端配套改造已随同后端 `SseEvent.toolCallId` 透传一并落地，见末尾「六、实现记录」。
> 目的：把需求钉下来，防止后端改完忘记改前端。具体实现步骤见末尾「六、实现记录」。
> 关联文件：`src/main/resources/static/js/pages/Chat.js`、`src/main/resources/static/js/app.js`、SSE 事件定义 `src/main/java/com/example/sandbox/web/model/sse/SseEvent.java`、事件发射 `src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java`。

## 一、为什么要改

后端并发落地后，模型一轮可以同时调用多个工具（如 3-5 个并发搜索）。当前前端假设"一轮一个工具、事件串行到达"（tool_call → observation 顺序配对）。并发后：

- 一轮内多个 `tool_call` 事件几乎同时到达；
- 多个工具的"执行中→完成"状态各自独立、乱序更新；
- 现有按到达顺序配对的渲染会互相覆盖或错位。

所以前端要从"线性事件流"改成"按轮分组、组内按工具 id 索引"的结构。

## 二、目标形态（用户描述）

分层折叠，两层结构：

1. **外层：一个大的"思考步骤"卡片**
   - 折叠态显示一个总状态：`思考中…` / `搜索中…` / `执行中…`（根据本轮工具类型给个概括词）。
   - 这是"这一轮"的整体容器，用户默认看到的是这一行概览，不被一堆工具刷屏。

2. **内层：点开后展开本轮的工具列表 + 思考过程**
   - 展开后显示：
     - 本轮的**思考过程**（reasoning / thinking 文本）。
     - 本轮**并发调用的多个工具**，每个一行，各自显示自己的实时状态，例如：
       - `xxx 命令运行中…` / `xxx 命令已完成`
       - `正在抓取网页 xxx…` / `已抓取 xxx`
       - `搜索 "xxx" 中…` / `搜索完成`
   - 每个工具行的状态**独立更新**（并发下 A 还在跑、B 已完成，互不影响）。

## 三、关键约束（给实现时把关用）

- **按 id 索引，不按到达顺序**：每个工具行要能被其 `tool_call_id`（或 stepIndex + 序号）唯一定位，observation 到达时靠这个 key 找到对应行更新，不能用"当前工具"这种单一变量。
- **一轮 = 一个分组容器**：`thinking_start` 开一个新的外层卡片，本轮所有 `tool_call` / `observation` 都挂在这个容器下的工具列表里。
- **状态三态**：每个工具行至少要能表达 待执行 / 执行中 / 已完成（或失败）。并发下同一容器内多行可同时处于不同态。
- **概览词**：外层折叠态的"搜索中/命令运行中/执行中"应能从本轮工具集合概括得出（比如全是 web_search 就显示"搜索中"，混合就显示"执行中"）。
- **向后兼容**：一轮只有一个工具时，展开体验不应比现在更差（单工具也走同一套分组结构，只是列表里只有一行）。

## 四、需要后端配合确认的点（实现前对齐）

- **后端现状（2026-07-07 已落地）**：`ToolScheduler` 并发执行多个 tool_call，**SSE 事件在并发线程池里各自发射、按完成顺序交错上线**（`executeOneStreamToolWithHooks` 在 `ToolScheduler` 线程池中被并发调用，`tool_call` 与 `observation` 各自往同一 sink 推）。第九章 9.2 所述「按模型顺序对齐」只作用于 `results` 列表/消息历史，**不作用于 SSE 事件**。因此前端会收到交错事件，单工具的「按到达顺序配对」逻辑在并发下必然错配——这正是本备忘所述改造要解决的问题。
- 要做到备忘第二节所述"一个大思考步骤内并列多个工具行、各自独立状态"，前端需按 `stepIndex` 把同一轮的多个 tool_call 归组进同一折叠容器，并按 `toolCallId`（而非到达顺序）把 observation 配对到对应工具行。
- **后端已加 `toolCallId` 字段（2026-07-07）**：`SseEvent.toolCall/observation/toolExecuting` 均带 `toolCallId` 重载；`ReactAgent` 在调用 `executeOneStreamToolWithHooks` 前先 `ensureToolCallId`，让 tool_call/observation/history 三处同 id。这是前端"按工具独立更新状态"的稳定 key。
- 后端 `agent.hook.concurrent-tool-execution-enabled` 开关可临时关闭并发回退串行，前端改造期间可用于对比验证。

## 五、不在本次范围

- 具体 DOM 结构、CSS、事件处理函数改法——后续单独讨论。
- 是否加动画、进度条等体验细节——后续再定。

## 六、实现记录（2026-07-07）

**后端小改（前端硬依赖）**：
- `SseEvent.java`：`toolCall`/`observation`/`toolExecuting` 各加带 `toolCallId` 重载，旧签名调新签名传 `null` 兼容。
- `ReactAgent.java`：单工具/并发/消化三路径调用 `executeOneStreamToolWithHooks` 前先 `ensureToolCallId`，`tool_call`（1041/1044/1422）/`observation`（1059/1428）发射带上 `toolCall.id()`；并发路径 `historyCalls` 整体 ensure 后再交 `ToolScheduler`。

**前端改造（一次性）**：
- 事件处理层（`Chat.js`）：`appendToolCallEvent`/`completeToolCallEvent`/`tool_executing` 改按 `toolCallId` 配对，去掉单一 `currentToolCall` 的配对职责（保留作 id 缺失时的回退）。`thinking_start` 记 `currentStepIndex`，`thinking_end` 让 thinking/reasoning 事件用本轮 stepIndex，使同轮思考与工具行可归组。
- 渲染层（`Chat.js`）：新增 `groupedSteps`（按 stepIndex 聚合）+ `stepOverview`/`stepAllDone`；流式模板改为两层折叠——外层每轮一个卡片（概览词 + 工具数，当前轮默认展开），内层 thinking/reasoning + 工具行各自三态独立。`streamingStatus` 从当前轮工具集合派生概览词。
- `app.js`：`createLiveStream` 的 stream 对象加 `currentStepIndex: 0` 初始字段。
- **历史渲染保持扁平**（二期）：`AgentEventMapper.toolResultEvent` 不带 stepIndex/toolCallId、`AgentStep` 是「一轮一工具」模型，并发轮 thinking 会重复 N 次；历史分组前置 `AgentEventMapper` 加字段 + `addStreamToolStep` 并发路径去重 thinking。历史是已完成态、无实时配对压力，扁平不比现在更差。

**验证**：`mvn -q compile`（JDK17）通过、`node --check` 前端语法通过。端到端（并发搜索触发多工具、SSE 抓 id 配对、单工具不回归、回滚开关串行）需在带 `DEEPSEEK_API_KEY` 的环境跑。
