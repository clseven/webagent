---
project: webagent-clean
type: interview-guide
status: verified
area:
  - interview
  - agent
tags:
  - project/webagent-clean
  - interview
  - tool
  - mcp
  - skill
source:
  - src/main/java/com/example/sandbox/web/service/Tool.java
  - src/main/java/com/example/sandbox/web/model/entity/Skill.java
  - src/main/java/com/example/sandbox/web/service/tool/SkillActivateTool.java
  - src/main/java/com/example/sandbox/web/service/tool/SkillListTool.java
  - src/main/java/com/example/sandbox/web/service/tool/SkillReferenceTool.java
  - src/main/java/com/example/sandbox/web/service/impl/AgentSkillRuntimeService.java
  - src/main/java/com/example/sandbox/web/service/impl/ReactPromptAssembler.java
updated: 2026-07-10
---

# Agent 能力三支柱：tool_call / MCP / Skill

> 用途：把 Agent 获取能力的三种途径一次讲清，以及它们的区别、边界、为什么要三个都做。
> MCP 的协议细节见 [[MCP 从协议到实现]]，这份聚焦"三者的定位和关系"。

---

## 一、一句话区分（先建立框架）

| | 是什么 | 解决什么 | 谁来触发 |
| --- | --- | --- | --- |
| **tool_call** | 模型调用工具的**机制**（底层能力） | 让模型能"动手"而不只是"说话" | 模型主动调 |
| **MCP** | 动态接入**外部工具**的协议 | 不改代码就能扩展工具 | 模型主动调（工具来自外部 server） |
| **Skill** | 方法论 + 素材 + 脚本的**能力包** | 让模型"会做"某类任务、并带上专用脚本 | 模型主动激活 |

**最关键的一句话**：tool_call 给的是"通用能力/手"，Skill 给的是"方法论 + 专用脚本"（脑 + 随包的手）。MCP 是 tool_call 的扩展来源。三者不是完全平行的——MCP 工具和 Skill 最终**都通过 tool_call 这个机制被使用**（skill 的脚本也是靠 shell 工具跑的）。

```
tool_call（机制/底座）
   ├── 本地工具    （写死在后端的 Tool 实现）
   ├── MCP 工具    （外部 server 动态接入，包装成 Tool）
   └── skill 相关工具（skill_list / skill_activate / skill_reference 也是 Tool）
```

---

## 二、tool_call：底层机制

### 是什么

tool_call 是模型"调用工具"这个能力本身。模型不直接操作系统资源，而是输出一个"我要调 xxx 工具、参数是 yyy"的结构化请求，后端执行后把结果（observation）返回，模型据此决定下一步。

### 我的实现

- 统一的 Tool 接口：每个工具定义 name、description、参数 schema（给模型看的）、副作用类型。
- 模型在 ReAct 循环里选工具 → 后端执行 → observation 塞回 → 继续推理。
- 本地工具（read_file、web_search、browser_screenshot 等）直接实现 Tool 接口。

### 一句话

> tool_call 是让模型能动手的机制。模型只负责"选哪个工具、填什么参数"，真正的执行在后端，结果再返回给模型。工具本身不产出最终答案，是给模型提供观察。

---

## 三、MCP：tool_call 的外部扩展

### 定位

MCP 不是独立于 tool_call 的东西，它是 **tool_call 的"工具来源"之一**。区别只在于：本地工具写死在代码里，MCP 工具来自外部 server、运行时动态接入。接进来之后，它就是一个普通 Tool，走一样的 tool_call 流程。

### 一句话

> MCP 让我不改代码就能扩展工具。外部 server 暴露的工具，我用适配器（RealMcpTool）包装成统一 Tool，接进来后 ReactAgent 调它和调本地工具没区别。协议是 JSON-RPC，用官方 SDK，我做的是多租户、安全和封装。

（详见 [[MCP 从协议到实现]]）

---

## 四、Skill：自带知识、素材和工具的能力包

这是三者里最容易讲混的。**Skill 不是单纯的文档，是"方法论 + 素材 + 可执行脚本"的能力包。**

### 是什么

一个 Skill 就是一个目录，三部分组成：

| 组成 | 是什么 | 怎么用 |
| --- | --- | --- |
| **SKILL.md** | 方法论/操作指南（Markdown） | 激活后加载进 prompt，指导模型怎么做 |
| **references/** | 参考素材（模板、清单、示例） | 用 skill_reference 工具**读内容**给模型看 |
| **scripts/** | **可执行脚本**（.sh / .py 等） | 用 **shell 工具 `bash <路径>` 执行**，拿结果 |

比如一个"PDF 处理"技能：SKILL.md 写处理流程和注意事项（脑），references/ 放输出格式模板（素材），scripts/extract.py 是真正干活的提取脚本（手）。模型按 SKILL.md 指导，用 shell 跑这个脚本。

### 关键：references 和 scripts 是两种不同的东西

之前容易误以为"Skill 就是文档"，其实：
- **references 是"读"的**——参考资料，用 skill_reference 读出内容喂给模型。
- **scripts 是"跑"的**——真正的可执行工具，模型用已有的 shell 工具 `bash 路径/脚本` 执行。

**巧妙点：scripts 不需要专门的"执行脚本工具"，它复用已有的 shell 工具。** 激活技能时，系统扫描沙箱里这个技能的 scripts/ 和 references/ 目录，把清单和用法说明一起拼进返回内容，明确告诉模型"这些脚本用 bash 跑、这些参考用 skill_reference 读"。模型自己在 ReAct 循环里调 shell 去执行脚本。

（对应代码：AgentSkillRuntimeService.decorateActivationContent —— 激活时列出 scripts/references 并附上用法。）

### 所以 Skill 的准确定位

> Skill 是一个自带知识、素材和工具的能力包。SKILL.md 是方法论（脑），references 是素材，scripts 是随包携带的可执行工具（手）。它把"怎么做 + 用什么做"打包沉淀下来，激活后模型既得到指导、又拿到能直接跑的脚本。
> 和 MCP/本地 tool 的区别：那些是"通用工具"，到处能用；Skill 里的 script 是"专用工具"，跟着某个任务方法论走、随技能包一起分发。

### 核心亮点：三级渐进式披露（Progressive Disclosure）

这是 Skill 设计里最能讲的点——**为什么不一次性把所有技能内容塞给模型？因为会挤爆上下文。** 所以分三层，按需加载：

| 层级 | 内容 | 开销 | 什么时候加载 |
| --- | --- | --- | --- |
| **第 1 层：元数据** | 每个技能一行 `id: description` | 约 30-50 token/个 | 默认就在 prompt 里，让模型知道有哪些技能 |
| **第 2 层：详细** | SKILL.md 完整内容 | 大 | 模型判断某技能相关时，调 skill_activate 才加载 |
| **第 3 层：资源** | references/、scripts/ 下的文件 | 更大 | 真正用到时才调 skill_reference 取 |

> 面试说法：技能可能很多、每个 SKILL.md 可能很长，全塞进 prompt 会挤爆上下文、也浪费 token。所以我做了三级渐进式披露：默认只给模型一行技能简介（约 30-50 token），模型觉得相关了才用 skill_activate 加载完整内容，用到参考文件才用 skill_reference 取。这是典型的"上下文成本"和"能力完备"之间的取舍。

### 三个 Skill 工具（注意：Skill 也是靠 tool_call 用的）

Skill 的三层，正好对应三个工具——**Skill 本身是通过 tool_call 机制来使用的**：

- **skill_list**：列出可用技能（第 1 层元数据）
- **skill_activate**：激活某技能，把 SKILL.md 完整内容加载进来（第 1 层 → 第 2 层）
- **skill_reference**：读取该技能的 references/scripts 文件（第 3 层）

### Skill 从哪来（两个源）

- **local**：用户在前端配置的本地技能仓库，作为"种子"同步到沙箱。
- **sandbox**：会话沙箱 `/home/gem/skills/` 下扫描到的，可能是 Agent 自己下载/生成的。
- 运行期**一律以沙箱为权威数据源**读取，本地仓库只用于推送种子。

### 一句话

> Skill 是喂给模型的领域知识和方法论，不是工具。它是一个 SKILL.md 加可选的参考文件和脚本，激活后加载进 system prompt，指导模型怎么做某类任务。我用三级渐进式披露控制上下文成本——默认只给一行简介，相关了才加载完整内容。

---

## 五、三者对比（面试重点）

### tool_call vs Skill：最容易被追问

> tool_call 是"通用能力/手"——模型能实际做一个动作（读文件、搜网页、截图、跑 shell）。Skill 是"能力包"——SKILL.md 是方法论（脑），告诉模型这类任务该怎么想、按什么步骤；同时它还能随包带 scripts（专用的手）和 references（素材）。
> 区别在于：tool 是散装的通用工具，到处能用；skill 是打包的方法论，里面的脚本是跟着这套方法论的专用工具。
> 举例：搜索是 tool（一个通用动作）；"怎么做竞品调研"是 skill（一套方法论 + 可能带一个抓取/汇总脚本，指导并配合模型完成整个任务）。一个是执行单元，一个是"教程 + 素材 + 专用工具"的组合。

### MCP vs 本地 tool

> 都是 tool_call 的工具来源，区别是本地工具写死在代码里、加工具要发版，MCP 工具来自外部 server、运行时动态接入不用改代码。接进来之后走一样的调用流程。

### 为什么三个都要做？

> 因为它们解决不同层次的问题：
> - tool_call 解决"模型能不能动手"——这是底座。
> - MCP 解决"工具能不能低成本扩展"——不改代码接外部能力。
> - Skill 解决"模型会不会做"——给它领域方法论，把好的做法沉淀下来复用。
> 光有工具，模型可能不知道怎么组合使用；光有知识，模型没有手去执行。三者配合，才是既有手、又有脑、还能扩展的完整 Agent。

---

## 六、面试标准答法（串讲）

> 我把 Agent 获取能力分成三块：tool_call、MCP、Skill。
> **tool_call** 是底层机制——模型通过统一 Tool 接口发起工具调用，后端执行返回 observation，模型不直接碰系统资源。
> **MCP** 是 tool_call 的外部扩展——用官方 SDK 做 JSON-RPC 通信，把外部 server 的工具动态包装成统一 Tool，不改代码就能扩展，我在上面做了多租户隔离和安全校验。
> **Skill** 是一个能力包——SKILL.md 是方法论，references 是素材，scripts 是随包携带的可执行脚本。激活后方法论加载进 prompt 指导模型，脚本则通过 shell 工具直接跑。为了控制上下文成本，我做了三级渐进式披露：默认只给一行简介，相关了才加载完整内容，用到才取参考文件。
> 关键关系是：MCP 工具和 Skill 最终都通过 tool_call 这个机制被使用——skill_list/activate/reference 本身就是工具，skill 里的脚本也是靠 shell 工具执行的。所以 tool_call 是底座，MCP 扩展工具来源，Skill 打包领域方法论和专用脚本。

---

## 相关页面

[[MCP 从协议到实现]] · [[项目亮点与模块深挖]] · [[技术选型与场景设计问答]] · [[面试必备内容]]
