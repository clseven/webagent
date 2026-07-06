# Agent Hook 治理升级 设计方案（Stop Hook 自检 / State Checks / 并发执行）

> 状态：定稿，三方案均立项。Stop Hook 已充分讨论定稿；State Checks 与并发执行为配套优化，方案已定。
> 日期：2026-07-06
> 关联：替掉 `AgentSearchPolicyHook`、升级 `FinalTodoGuardHook`、并发执行工具
>
> **已落地改动**（本轮已改代码，不在三方案范围内，记录备查）：`ReactAgent` 的 `PostToolUseHook` 触发逻辑已从"任一返回非 null 即短路"改为"全部执行、收集所有注入消息"（`triggerPostToolUseHooks` 返回 `List<ChatMessage>`，同步 `run` 与流式 `runStream` 两处调用点改为遍历追加）。原因：Post 语义为增强而非拦截，短路会静默丢弃后续 hook 的注入。

## 一、背景与动机

### 当前问题：搜索治理用硬规则，且只对搜索生效

`AgentSearchPolicyHook`（Pre Hook）用硬规则约束联网搜索：

- **方向闸**：用正则从用户请求里挑"目标词"，看搜索词包不包含它，不包含就拦。
- **预算闸**：按任务模式写死 2/5/6/12 次搜索上限，超了就拦。

两道闸的共同问题是**不看证据**：它不读搜索结果、不知道模型已知什么、不知道还缺什么，只看搜索词这几个字就决定拦不拦。后果：

- 简单任务被多搜浪费，复杂任务被强制掐断在半成品。
- "偏离目标"判断靠正则，对纯中文请求完全失效（只认英文标识符）。
- 只对搜索生效，读文件、跑命令等其他工具不受约束，治理不一致。

### 根因

"该不该继续/该不该收尾"本质是一个**需要看证据才能判断**的决策，被硬塞进了一个 Pre Hook 用正则常量干。它是个"不够好时的创可贴"，不是终态。

### 设计方向（已确认）

不为搜索单造一套编排，靠通用 `ReactAgent` 的循环能力消化搜索发散。搜索只是工具之一，不该有特殊待遇。把"该不该停"的判断从 Pre Hook 的硬规则，挪到 **Stop Hook 让模型基于证据自判**。

> 依据：harness 的本质是"模型只决定下一步，harness 控制能干什么、怎么验证、何时停"。
> 自检不做独立 Agent，做成分层触发，默认轻量。

## 二、设计目标

1. 用基于证据的模型自检，替代 `AgentSearchPolicyHook` 的方向闸。
2. 用 harness 侧的拦截计数 + 现有 `MAX_ITERATIONS`，替代预算闸。
3. 搜索不再有任何特殊逻辑，所有工具一视同仁接受证据验收。
4. 不新增 Agent、不新增循环，复用现有 Pre/Post/Stop Hook 机制。

## 三、完整方案

### 3.1 形态

- **位置**：复用现有 `Stop Hook`（`FinalTodoGuardHook` 升级）。
- **触发**：模型准备给最终答案时。
- **判定方式（B 方案）**：harness **不解析**模型输出，只注入自检提示。模型继续调工具 → 循环继续；模型给最终答案 → 放行。
- **何时强制停**：harness 侧拦截计数。

### 3.2 Stop Hook 判定逻辑

```
模型准备收尾
  ├─ 前置：看 TodoState（便宜硬信号，保留现有逻辑）
  │    有 completed 缺 evidence / 有未完成 todo → 拦下，要求继续或标 blocked
  │
  └─ TodoState 干净 → 进入证据自检：
       看拦截计数器（本会话内 Stop Hook 连续注入自检的次数）：
         第 1~2 次 → 注入完整自检提示，计数 +1
         第 3 次起 → 不再自检，直接放行（强制收敛）

       注入自检后：
         模型继续调工具 → 计数器清零（认真补查，重新给机会）
         模型又收尾     → 回到上面计数判断
```

两个关键细节：

- **TodoState 降级为前置信号**，不再是充分条件。没闭环一定拦；但闭环了不代表证据够，仍要走自检。
- **计数器在模型调工具时清零**。只有"连续多次想收尾却不补查"才触发强制放行。
  - 这道防线防**过度自信刷自检**。
  - 现有 `MAX_ITERATIONS` 防**过度谨慎无限查**。
  - 两道防线齐了，B 方案才稳。

### 3.3 自检提示终稿（Stop Hook 注入的 user 消息）

```
[回答前自检] 在给出最终答案前，先在思考中完成以下检查，不要跳过：

1. 拆解：用户实际问了哪几个子问题？逐条列出。
2. 证据对照：对每个子问题，你是否有依据？
   - 依据类型：工具调用返回 / 用户已提供 / 公共常识 / 纯推断
   - 明确标出：哪些子问题只有"纯推断"、没有前三种依据。
3. 结论核实：你准备给出的答案里，关键结论是否都已落实（非"纯推断"）？
4. 冲突：你掌握的多条信息之间有没有矛盾？

判定与行动（二选一）：
- 若存在"纯推断"的关键结论、或子问题缺依据、或存在未解决冲突：
  不要给出最终答案。继续调用工具补查，并说明你要补什么。
- 若每个子问题都已落实（工具依据 / 用户已提供 / 公共常识，三类任一）且无未解决冲突：
  给出最终答案。在答案中标注每条关键结论的来源类型：
  [查证] 有工具依据 / [用户提供] 来自用户输入 / [常识] 公共知识 / [推断] 本轮未查证。
  其中[推断]类必须最少，否则不应判为可答。
```

设计要点：

- **"在思考中完成"** → 自检过程放 reasoning，不污染最终答案。
- **第 1 条拆解** → 锚，替掉原 hook 的方向判断。模型自己拆出子问题，自然知道有没有跑题。
- **四类依据分类** → 落地"该查才查"：常识/用户提供不算缺口，只有"纯推断"才是要拦的。
- **`[推断]` 标签** → 把"没证据的自信"显式化，模型知道这类不能多。
- **靠行为不靠解析** → 模型二选一行动（继续调工具 / 给答案），harness 不解析任何标记。

### 3.4 替换关系

| `AgentSearchPolicyHook` 原职责 | 现在被谁接管 |
|---|---|
| 方向闸（搜索词是否偏离目标） | 自检第 1 条：模型拆解子问题，自然判断是否跑题 |
| 预算闸（搜索次数硬拦） | 拦截计数 + `MAX_ITERATIONS`：harness 控制何时必须停 |
| 搜索专属逻辑 | 全部消除——读文件、跑命令、搜索一视同仁接受证据验收 |

## 四、落地改动清单

> 这是本方案的核心，落地时照此逐项检查，避免漏改。

### 4.1 改造：`FinalTodoGuardHook.java`

路径：`src/main/java/com/example/sandbox/web/service/impl/FinalTodoGuardHook.java`

- 现有 TodoState 检查逻辑**保留**，作为前置硬信号。
- 新增证据自检分支：TodoState 干净时，注入 3.3 的自检提示。
- 拦截计数器**归 `ReactAgent` 持有**（已定选项 C，见 4.5），不在 hook 实例里。Stop Hook 触发时由 `ReactAgent` 传入当前计数；模型执行工具时由 `ReactAgent` 清零。
- 阈值常量化（如 `MAX_SELF_CHECK_ROUNDS = 2`，第 3 次放行）。

### 4.2 新增：自检提示常量

- 把 3.3 的提示文本定义成常量（建议放 `FinalTodoGuardHook` 或独立的 prompt 常量类），便于后续调优。

### 4.3 删除：`AgentSearchPolicyHook.java`

路径：`src/main/java/com/example/sandbox/web/service/impl/AgentSearchPolicyHook.java`

- 整文件删除。

### 4.4 改造：`ReactAgentHookService.java`

路径：`src/main/java/com/example/sandbox/web/service/impl/ReactAgentHookService.java`

- 删除两处 `AgentSearchPolicyHook` 注册：
  - 同步路径：第 86 行 `reactAgent.registerPreToolUseHook(new AgentSearchPolicyHook(userMessage, plan));`
  - 流式路径：第 127 行同上。
- `userMessage`/`plan` 参数如果仅用于此 hook，检查是否还需要保留在 `configureForChat/Stream` 签名中（可能 `viewImageHook` 还在用，确认后再决定是否清理参数）。
- `FinalTodoGuardHook` 的注册（`registerFinalTodoGuard`）保持不变，hook 内部已升级。

### 4.5 改造：`ReactAgent.java`（拦截计数清零时机）

路径：`src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java`

这是方案里唯一需要动 `ReactAgent` 主循环的地方，需谨慎：

- **问题**：拦截计数器需要感知"模型调了工具"这个事件，而它发生在 `ReactAgent` 主循环里，hook 实例感知不到。
- **已定：选项 C**。状态归 `ReactAgent` 管，hook 只读不持有。
  - `ReactAgent` 维护"收尾尝试计数"：Stop Hook 注入自检时 +1，工具实际执行时归零。
  - Stop Hook 触发时由 `ReactAgent` 传入当前计数，hook 据此决定"注入自检"还是"已到阈值放行"。
  - 与 4.1 一致：计数器不在 hook 实例里。
- 注意同步路径（`run`）和流式路径（`runStream`）两处循环都要处理；一个 `ReactAgent` 实例只跑一条路径，计数器实例级即可，无需跨路径共享。

### 4.6 文档同步

- `knowledge-base/30 跨模块流程/Agent 工具调用.md` 第 50 行：删除/改写 `AgentSearchPolicyHook` 相关描述。
- `knowledge-base/10 架构/系统边界.md` 第 26 行：Hook 层清单移除 `AgentSearchPolicyHook`，补充"证据化自检"职责描述。
- 若 `docs/project-spec.md` 第八章有相关 ADR 或 hook 章节，补一条 ADR 记录本次决策。

## 五、建议的改动顺序

1. **先做 4.5 的状态机制**：在 `ReactAgent` 加"收尾尝试计数"（工具执行清零、Stop 注入自检 +1）。这是地基，没它 hook 没法判断。
2. **再做 4.1 + 4.2**：升级 `FinalTodoGuardHook`，接入计数 + 注入自检提示。
3. **验证**：跑几轮真实任务，观察自检提示是否被正确注入、计数清零是否正常、强制放行是否在阈值后生效。
4. **最后做 4.3 + 4.4 + 4.6**：确认自检稳定后，删除 `AgentSearchPolicyHook` 及其注册，同步文档。

> 顺序要点：先立新机制并验证，再删旧机制。不要同时改。

## 六、风险与兜底

| 风险 | 兜底 |
|---|---|
| 模型过度自信，反复判"够"刷自检 | 拦截计数第 3 次强制放行 |
| 模型过度谨慎，一直判"不够"无限查 | 现有 `MAX_ITERATIONS` 兜底 |
| 模型敷衍走自检流程 | 主模型为 DeepSeek V4，推理能力足够；上线后观察自检质量 |
| 自检提示每轮收尾都注入，token 成本 | 1M 上下文，当前可接受；后续可做"首次完整 / 后续精简"优化 |
| 自检被写进 content 而非 reasoning | 提示要求"在思考中完成"，但若模型把自检写进正文，最终答案会夹带自检文字。上线后观察答案是否夹带；若是，可调整提示措辞或在放行前做轻量清洗 |
| 删除 `AgentSearchPolicyHook` 后搜索失控 | 自检第 1 条覆盖方向判断；计数 + MAX_ITERATIONS 覆盖预算；上线后对比 token 消耗 |

## 七、未纳入本方案（后续）

- **EvidenceStore（结构化证据快照）**：当前自检让模型自己翻对话历史，1M 上下文扛得住。等自检质量不够再上 Post Hook 维护结构化证据快照，喂给自检提示，提升判断准确度。
- **state check 那道自检**：已独立成计划，见第八章。
- **预算运行时模块**：通用预算器（按会话/任务给所有工具配额）。当前由拦截计数 + MAX_ITERATIONS 临时承担，后续若需要更细的预算控制再做。

## 八、State Checks 计划（Pre Hook 维度，并发的配套防竞态机制）

> **定位**：State Checks 防的是"模型基于过时认知改文件"。它与并发执行绑定——单线程串行下 read 和 write 之间很少有别的东西动文件，本机制基本不触发（"睡着"状态属正常）；它的主要价值在并发落地后防 TOCTOU 竞态（read 和 write 之间另一个并发工具改了文件）。作为基础设施一并建设，单线程时低开销待命，并发时激活。
>
> 与第一~七章 Stop Hook 自检互补：Stop Hook 事后验收（证据够不够），State Checks 事前防错（认知过不过时）。正好填补删除 `AgentSearchPolicyHook` 后空出的 Pre Hook 位置。

### 8.1 要防什么

模型对文件状态有"过时认知"时仍执行写操作，具体三种：

1. **没读就改**：模型直接 `file_replace`，`old_str` 凭记忆填 → 匹配失败或误替换。
2. **读过但中间被改了**：模型 read 文件 A 后，中间发生了任何事（自己又改、shell 改、并发工具改、外部改），再 `file_replace` → 基于旧内容。
3. **盲写覆盖**：模型 `write_file` 直接覆盖，冲掉它没看过、或已被改过的内容。

当前 `FileReplaceTool` / `WriteFileTool` **没有任何防护**。`FileReplaceTool` 注释自己写了"先 read_file 查看上下文"，但这只是建议，代码不强制。

### 8.2 走法 C：写前现场验证（不追踪中间事件）

核心设计——**不追踪 read 和 write 之间到底谁改了文件**（shell 是黑盒、模型自述不可全信），而是在"写"的那一刻，针对要写的那个文件现场验证：

- read 时：本地算内容 hash 存起来（0 额外 API）。
- 写前：re-read 一次算当前 hash，跟存的 hash 比。
  - 一致 → 没人动过，放行。
  - 不一致 → 被改过了，拦下要求重读。

这样 shell 难题消失（不管中间谁改的，写前 hash 一比就知道），不波及其他文件（只验证要写的那个），不依赖模型自述（harness 自己算）。

**read-before-edit 与 staleness 合并**：写前那次 re-read 本身就同时干了两件事——既确认"读过"（有 hash 记录才算读过），又确认"没过时"（hash 一致）。一次 re-read 两件事一起办。

**写后失效**：写完清除该文件的 hash 记录。模型若接着再改同一个文件，必须重读——因为写完内容变了，旧认知作废。

### 8.3 机制

新增 per-session 文件认知状态服务，形态类似 `AgentTodoService`：

```
FileCognitionState:
  sessionId → { 文件路径 → 上次 read 时的内容 hash }
```

**生命周期**：随会话存在，会话结束清理；map 设上限（如 256 条）淘汰最旧记录，避免长会话无限增长。

挂载方式（复用现有 Hook，不新增循环）：

- **Post Hook on `read_file`**：读完本地算 hash，存入 `FileCognitionState`。
- **Pre Hook on `file_replace` / `write_file`**：
  - 先判"新建 vs 覆盖"（见 8.4）：新建文件直接放行，不要求先读。
  - 覆盖已有文件：
    - 没记录（没读过）→ 拦，返回"请先 read_file 确认当前内容，再修改"
    - re-read 算当前 hash，与记录值不符 → 拦，返回"该文件自你上次读取后已变化，请重新 read_file"
    - 一致 → 放行，并把记录刷新为最新（写前 re-read 的内容即最新认知）
  - 大文件降级：文件超过阈值（如 1MB）跳过 hash 校验，只查"读过没"，避免 re-read 大文件开销。
- **Post Hook on `file_replace` / `write_file`**：写后从 `FileCognitionState` 清除该路径（invalidate）。

拦截方式沿用 Pre Hook 既有语义：返回的字符串作为 observation 回传模型，工具不实际执行。

### 8.4 落地改动清单

1. **新增 `FileCognitionState` 服务**：per-session 状态（参考 `AgentTodoService` 存取模式），存 `{路径 → hash}`。
2. **新增 `FileStateCheckHook`（Pre Hook）**：实现 8.3 的写前检查逻辑。
3. **新增配套 Post Hook 逻辑**：read 后存 hash、write 后清 hash。可合并进同一个 hook 类。
4. **`ReactAgentHookService` 注册**：在 `configureForChat` / `configureForStream` 注册上述 Pre/Post Hook。
5. **staleness 判据已定：re-read hash**（已查证沙箱 API，见 8.5）。
6. **新建 vs 覆盖判定**：写前用 `/v1/file/list` 列父目录，判断目标文件是否已存在（沙箱无单独 stat 端点，见 8.5）。新建放行，覆盖才走 hash 校验。建议给 `SandboxClient` 补一个 `fileExists(path)` 便利方法封装此判断。

### 8.5 staleness 判据查证结论（re-read hash）

已查 `docs/sandbox-api/openapi.json` 与 `SandboxClient` / `AioFileApi`：

- `/v1/file/list` 返回的 `FileInfo` **含 `modified_time` 和 `size`**——沙箱有 mtime 能力，但只能通过列父目录间接拿。
- `/v1/file/read` 返回的 `FileReadResult` **只有 content 和 file，不返回 mtime**。
- 没有单独的 stat 端点。

据此选 **re-read hash**，不用 mtime：

- read 时本地算 hash，**0 额外 API**；mtime 方案 read 时需额外调 list 取 mtime，更重。
- hash 是内容真实状态，**mtime 可被 `touch`/`cp -p` 重置**，不如 hash 可靠。
- 写前 re-read 在逻辑上本就合理（写前确认内容），那次 read 不算浪费。
- 大文件 re-read 开销用 8.3 的"大文件降级"兜底。

### 8.6 与其他方案的关系

| 维度 | Stop Hook 自检（第一~七章） | State Checks（本章） | 并发执行（第九章） |
|------|----------------------------|----------------------|--------------------|
| 时点 | 收尾前 | 写文件前 | 一轮多工具执行 |
| 防什么 | 没做完 / 没证据 | 做错 / 过时认知 | （性能，非防错） |
| Hook 类型 | Stop Hook | Pre + Post Hook | 改主循环 + 调度器 |
| 位置 | `FinalTodoGuardHook` 升级 | 新增 `FileStateCheckHook` | `ReactAgent` + `ToolScheduler` |

**与并发的强绑定**：并发落地后，state checks 的 **re-read + verify + write 必须整体在 per-session 写锁临界区内**，不能只锁 verify。若 re-read 在锁外、verify 在锁内、write 又在锁外，则 re-read 拿到 hash=X 通过校验后、write 之前仍会被并发 write 插入修改（TOCTOU）。正确做法：获取写锁 → re-read 算 hash → 对比 → write → 释放写锁，整段原子。即 state checks 的"写前校验 + 写入"就是并发放过 WRITE 类工具后加锁的临界区。

### 8.7 风险与边界

| 风险/边界 | 处理 |
|-----------|------|
| 大文件 hash/re-read 成本 | 超阈值（如 1MB）降级为只查"读过没"，跳过 hash。**代价：大文件放弃 staleness 防护**（仍保留 read-before-edit） |
| `write_file` 不区分"新建"vs"覆盖" | 写前判文件是否存在：新建放行，覆盖才校验 |
| `file_replace` 的 `old_str` 精确匹配是否已隐含 read | 部分隐含（匹配失败报错），但那是工具内部、改一半才发现；state checks 执行前拦，更早，且防"恰好匹配但内容已变"的误替换 |
| shell 改文件 | **不追踪 shell**。走法 C 写前现场 re-read，不管中间是 shell 还是别的改的，hash 一比就知道。无需解析 shell 命令、无需全清记录 |
| 连续改同一文件 | 走法 C 写后清记录，模型再改同一文件需重读（哪怕它刚写过）。**代价：连续改同一文件每次多一次 read**。可接受——写后内容确已变，重读是安全的 |
| 单线程下基本不触发 | 正常现象。本机制为并发防竞态而建，单线程低开销待命。日志可记录触发次数，长期为 0 不代表坏了 |
| 并发下 TOCTOU | re-read + verify + write 整体放 per-session 写锁临界区内（见 8.6），不能只锁 verify |

### 8.8 配套软约束（零成本，顺手做）

在 system prompt 加一条规范，从源头减少黑盒文件操作：

> 修改文件优先使用 `file_replace` / `write_file` 工具，不要用 `sed`/`echo >`/`cp` 等 shell 命令改文件。工具调用是结构化的，便于追踪；shell 改文件是黑盒。

这是软约束（模型大部分时候遵守），不替代 State Checks 的硬约束，但能让文件操作更可观测，并发落地后副作用判断也更容易。**不管 State Checks 是否落地，这条都建议加。**

### 8.9 改动顺序

1. 新增 `FileCognitionState` 服务。
2. 实现 `FileStateCheckHook`（Pre）+ 配套 Post Hook。
3. 注册并跑真实任务验证（重点测：没读就改、读后外部改、连续改同一文件、新建 vs 覆盖）。
4. 与并发执行联调（并发落地后，hash 校验纳入写锁临界区）。

## 九、并发执行工具计划（独立立项，最大改动）

> 这是对照业界 harness 的**最大缺口**：Claude Code 的 StreamingToolExecutor 让一轮多个 tool_use 并发执行（读类并发、写类独占），而本项目严格串行。联网调研场景模型一轮抛 3-5 个搜索时，串行 = 3-5 倍延迟，并发 ≈ 1 倍。收益确定，但动核心循环、风险高，**单独立项，不与 Stop Hook / State Checks 混做**。

### 9.1 现状（已读代码确认）

- `LlmResponse.getToolCall()` 返回**单个** `LlmToolCall`，主循环（`run` 第 494 行、`runStream` 第 1098 行）一次只处理一个 tool_call。
- 即便底层模型一轮返回多个 tool_use，当前 LLM 接入层也没暴露列表形态。
- `executeTool` / `executeToolWithHeartbeat` 串行执行，无并发调度。

**结论**：并发的真正起点不是"加调度器"，而是更前置——先让 LLM 接入层能拿到"一轮多个 tool_call"。

### 9.2 目标形态

工具声明副作用类型，调度器分类执行：

```
ToolSideEffect:
  READ       — 纯读，不碰共享状态（web_search、web_fetch、grep、read_file）
  WRITE      — 改文件/沙箱（write_file、file_replace）
  EXCLUSIVE  — 独占（bash、git checkout、改环境）

调度规则：
  一批 tool_calls 进来：
    READ 类   → 并发执行，限并发数（如 3）
    WRITE/EXCLUSIVE → 串行，且与 READ 之间按"读写锁"互斥
  结果按模型给的 tool_use 顺序对齐回灌（不管实际完成顺序）
```

### 9.3 前置工作（阶段 0）

1. **DeepSeek parallel tool calling 已确认支持**（go/no-go 门槛已过，用户确认）。一轮可返回多个 tool_use，并发这条路通。
2. **`LlmResponse` 增加 `getToolCalls()` 列表**（保留 `getToolCall()` 兼容旧逻辑）。当前 `getToolCall()` 只返回单个，即便模型一轮返回多个也只取一个——这一步是把多 tool_call 接出来。
3. **主循环改为遍历列表**：拿到 `List<LlmToolCall>` 后交给调度器，而不是只取一个。

> 门槛已过，但第 2、3 步仍是落地起点——先让 LLM 接入层能拿到并处理"一轮多个 tool_call"（此时仍串行执行），再加调度器做并发。

### 9.4 落地改动清单

1. **`Tool` 接口加副作用声明**：新增 `ToolSideEffect getSideEffect()`（或加到 `ToolDefinition`）。所有现存工具实现类都要标。漏标一个就有数据竞争风险。
2. **新增 `ToolScheduler`**：接收 `List<LlmToolCall>`，按副作用分组，READ 并发（限流）、WRITE/EXCLUSIVE 串行，结果按原顺序收集。处理：单工具超时/异常隔离、并发数上限。
3. **改造 `ReactAgent` 主循环**：同步 `run` 和流式 `runStream` 两处，把"取单个 toolCall 执行"换成"取列表交调度器"。
4. **流式 SSE 事件改造**：串行时事件顺序天然清晰（A 开始→A 结束→B 开始），并发后事件交错，前端要支持"多个工具同时进行中"。需同步改前端。
5. **梳理隐式共享状态**（项目特有，难点）：`ImageBuffer`（view_image 存图 / Post Hook 取图，按 sessionId 关联）、`AgentTodoService`、per-session 沙箱。这些即使标 READ 也可能撞，要单独标记或加 per-session 锁。

### 9.5 最小可行起点（强烈建议先只做这个）

不要一上来做通用调度器。先只对**纯网络读类**（web_search、web_fetch）开并发，其他全串行：

- 收益：联网调研场景的主要延迟拿掉，模型一轮 3-5 个搜索并发跑。
- 风险骤降：不碰文件/沙箱/buffer，难点 5（隐式共享状态）基本不触发。
- 验证稳定后，再逐步把 read_file/grep 纳入并发（需解决沙箱并发访问）。

### 9.6 与其他方案的关系

| 关联点 | 说明 |
|--------|------|
| State Checks（第八章） | 并发落地后，state checks 的 hash 校验必须在 per-session 锁内做，避免 TOCTOU（查时没变、写时被并发改了）。8.6 已记，落地时呼应 |
| ImageBuffer side-channel | view_image 的"工具存图/Post Hook 取图"在并发下会撞 sessionId，需改成按 tool_call_id 关联，或对 view_image 标 EXCLUSIVE |
| Stop Hook 自检 | 无直接冲突，但并发后单轮工具产出更多，自检的"证据对照"会更密集，观察 token 消耗 |

### 9.7 风险

| 风险 | 严重度 | 处理 |
|------|--------|------|
| 工具副作用标错，写工具被当读并发 → 数据损坏 | 高 | 阶段 0 梳理时逐个核对；默认标 EXCLUSIVE，只把确认无副作用的标 READ |
| ImageBuffer 等隐式共享状态并发冲突 | 高 | 最小可行起点只开纯网络读，规避此风险；扩大并发前先改 buffer 关联键 |
| SSE 事件交错，前端状态错乱 | 中 | 前端要支持并发进行中状态 |
| 并发 bug 间歇性，难复现 | 中 | 需系统测试，不靠单次跑确认 |
| DeepSeek 不支持 parallel tool calling | — | **已解决**：已确认支持，门槛过 |

### 9.8 改动顺序

```
阶段 0（门槛已过，接出多 tool_call）：
  DeepSeek parallel tool calling 已确认支持。
  LlmResponse 加 getToolCalls()，主循环改遍历列表（此时仍串行执行）。
  → 这一步只把多 tool_call 接出来，不加并发。

阶段 1（最小可行并发）：
  Tool 加副作用声明（先只标 web_search/web_fetch 为 READ，其余 EXCLUSIVE）。
  ToolScheduler 只对 READ 并发，限并发 2-3。
  其余维持串行。
  → 拿到联网调研场景主要收益。

阶段 2（扩大并发）：
  read_file/grep 纳入 READ 并发，解决沙箱并发访问（per-session 读写锁）。
  ImageBuffer 关联键改造。

阶段 3（激进优化，可选）：
  流式 JSON 解析，tool_use 完整即启动（不等整段返回）。
  复杂度高，收益边际，可不做。
```

### 9.9 立项建议

- 三方案均立项（用户决策：都是优化，都做）。
- **建议落地顺序**：Stop Hook（已定稿、独立、收益面广）→ State Checks（基础设施，单线程待命、并发时激活）→ 并发执行（风险最高、动主循环）。State Checks 的 hash 校验在并发落地时纳入写锁临界区。
- 工程上仍建议**分批上线、逐个验证**，避免多变量同时引入导致问题难定位。但立项与设计不阻塞，可并行推进。

## 十、测试、回滚与验收

### 10.1 Stop Hook 自检

- **测试用例**：①纯推断答题（无工具调用）应被拦；②搜索发散后收尾应被拦要求补查；③连续 3 次收尾应强制放行；④模型补查后计数应清零；⑤TodoState 未闭环时走前置拦截、不消耗自检计数。
- **回滚**：`AgentSearchPolicyHook` 文件先注释注册、不立即删；自检不稳可恢复旧 hook 注册。稳定后再删文件。
- **验收**：对比上线前后 token 消耗、任务完成质量（人工抽检）、自检拦截日志是否合理。

### 10.2 State Checks

- **测试用例**：①没读就 file_replace 应被拦；②read 后外部改文件再 file_replace 应被拦；③连续改同一文件每次需重读；④新建文件不拦；⑤大文件降级只查读过没；⑥并发下 re-read+write 原子性（压测）。
- **回滚**：`FileStateCheckHook` 注册开关化（配置项），出问题关掉即恢复无校验。
- **验收**：单线程下触发次数应近 0（待命正常）；并发联调后验证防竞态有效。

### 10.3 并发执行

- **测试用例**：①一轮多 web_search 并发完成、结果按模型顺序对齐；②写工具不与读工具并发；③单工具异常不影响其他并发工具；④并发数限流生效；⑤SSE 事件交错时前端状态正确。
- **回滚**：`ToolScheduler` 加开关，退化为串行（遍历列表逐个执行）。`LlmResponse.getToolCalls()` 保留 `getToolCall()` 兼容，回滚不影响旧逻辑。
- **验收**：联网调研场景延迟下降；无数据竞争（重点压测写文件场景）；SSE 事件前端正常。

## 十一、决策脉络

1. 不为搜索单造编排，靠通用 `ReactAgent` 消化（方向判断）
2. 不要反思 Agent，做分层自检（自检形态）
3. harness 控边界、模型判证据（依据来源：Claude Code / Codex 拆解）
4. 落地首选 Stop Hook 证据化验收（落地位置）
5. B 方案 + 拦截计数 + 该查才查自检提示（本方案定稿）
