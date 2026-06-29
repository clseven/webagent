# 工具 description 重构设计

> 配套 `docs/lessons/prompt-redesign.md`。System prompt 瘦身后，部分"工作流、调用次序、
> 跨工具选型"会迁移到 system prompt；同时工具 description 自身也要瘦身，回归本职：
> **"我是什么 + 我的参数怎么填"**。

---

## 设计原则

1. **description 只回答"是什么 + 参数怎么填"，不回答"什么时候用"** —— when-to-use 是 system prompt 的事
2. **不在 desc 里劝退到其他工具** —— 跨工具选型由 system prompt 或模型自己判断
3. **不在 desc 里堆否定列表** —— "不读取 DOM、不返回 X、不能 Y" 是防御性写作，污染描述
4. **不在 desc 里硬编码补丁** —— 错误码、产品名、具体包名、对话用词、踩坑场景应该：
   - 安全/参数校验 → 在 `execute()` 里做，错误返回时告诉模型
   - 调用次序 → 在调用次序错误时通过错误返回纠正
   - 业务策略（不可逆操作确认等）→ 放 system prompt
5. **风格中性，与其他工具一致** —— 避免营销文案（"一步拿到""纯净可读"）和强调标记（"【首先使用】""务必"）

---

## 严重补丁（必须重构）

### 1. `mcp_add_or_update_server`

**问题**：description 把对话流程、产品名（VS Code/Claude Desktop）、用户确认词（"确认""可以""安装吧"）、HTTP/重试规则、headers 免责声明全堆进来。参数 `args` description 硬编码 npm 包名 `@modelcontextprotocol/server-filesystem`。

**改成**：

```
description:
添加或更新当前用户的私有 MCP Server。支持 streamable-http（远程 endpoint）
和 shell（在用户沙箱内 stdio 启动）。需要 confirmed=true 才会执行。

参数:
- server_id: 小写 Server ID；重试时复用，不另建
- type: streamable-http | shell（默认 streamable-http）
- url: streamable-http 的精确 endpoint
- command: shell 类型的 stdio 启动命令（如 npx、python、node）
- args: shell 类型的命令参数列表，需完整填写不留空
- enabled: 是否启用（默认 true）
- confirmed: 用户已确认安装方案后才能设为 true
```

**搬到 system prompt 的内容**（已在 `prompt-redesign.md` 的 mcp_management 段处理）：
- 目标客户端固定为 WebAgent，不询问外部客户端
- 安装流程（搜索 → 确认 → 安装 → 验证）
- 用户确认词识别
- 连接失败处理

**搬到 execute() 校验的内容**（已有，无需改动）：
- filesystem MCP 缺包名 → 返回错误并提示正确 args
- shell args 空白项校验

---

### 2. `browser_execute`

**问题**：description 里塞了**和本工具完全无关的**"搜索兜底限制"——讲 `web_search` 返回 `SEARCH_SOURCE_BLOCKED` 时怎么用浏览器兜底，硬编码了百度/Bing/Google 等搜索引擎名。"不可逆操作不要做"是业务策略。

**改成**：

```
description:
在浏览器中执行 Playwright JavaScript 代码。代码运行在 page 全局上下文中，
可以使用 page.goto、page.evaluate、page.locator 等 Playwright API。

参数:
- code: 要执行的 JavaScript 代码。可用全局变量：page（当前页面）。
        不要 require/import、不要新建或关闭 page、不要访问 process。
- timeout: 执行超时秒数（默认 30）
```

**搬到 system prompt 的内容**：
- 不可逆操作（支付、发布、删除、发送消息）需用户确认 → 放在 identity 段或单独的"安全边界"小段
- 搜索兜底策略 → 写在 `web_search` 失败时的返回文本里（execute 已经返回了 `SEARCH_SOURCE_BLOCKED`，可以在返回文本里多加一句提示）

---

### 3. `browser_action`

**问题**：5 条"使用规则"是工作流文档；反复劝退到 `browser_inspect + browser_execute`；导航场景给了两种方案还自己说"更推荐另一个"。

**改成**：

```
description:
在浏览器中执行一个原子动作（点击、输入、按键、滚动、悬停等）。每次只能执行一个。

参数:
- action_type: CLICK | TYPING | PRESS | HOTKEY | SCROLL | HOVER | MOVE_TO
- x, y: 坐标（CLICK/HOVER/MOVE_TO 使用）
- text: 输入文本（TYPING 使用）
- key: 按键名（PRESS 使用）
- keys: 组合键，如 ctrl+l（HOTKEY 使用）
- dx, dy: 滚动量，dy>0 向下，dy<0 向上（SCROLL 使用）
```

**搬到 system prompt 的内容**（可放在 screenshot 段或新增 browser 段）：
- 坐标操作前先截图，页面变化后重新截图（这是浏览器工具组的共同约束）
- 需要按文本定位或多步连续操作时，优先用 browser_inspect + browser_execute

---

### 4. `browser_screenshot`

**问题**：否定列表（"不能理解图片内容…不能返回 DOM"）+ 跨工具劝退 + 截图坐标的工作流（是 browser_action 的事）+ 二维码/验证码场景列举 + "务必"。

**改成**：

```
description:
截取当前浏览器视口，返回可在聊天窗口展示的图片链接。
当用户需要亲眼看到页面视觉内容时使用。

参数:
- url: 可选。提供时先导航到该 URL 再截图，省略则截当前页面
```

**搬到 system prompt 的内容**（已在 prompt-redesign 的 screenshot 段处理）：
- "用户需要亲自看到页面视觉内容时使用截图"——screenshot 段已经讲了

---

### 5. `browser_inspect`

**问题**：三步"推荐流程"编号是 system prompt 的内容；"ref 只是阅读编号，不能直接作为 locator"是模型用错后打的补丁；否定三联"不点击、不输入、不导航"是防御。

**改成**：

```
description:
检查当前浏览器页面，返回 URL、标题、可见文本和带 selector 的交互元素清单。

参数:
- focus: 可选。聚焦的元素或区域描述，缩小返回范围
```

**搬到 system prompt 的内容**：
- "ref 是阅读编号，需要操作时用 selector"——可以放在浏览器工具组的统一约束里

**搬到 execute() 错误返回的内容**：
- 模型把 ref 当 locator 传给 browser_execute 时，让 browser_execute 在错误返回里提示

---

## 中度问题（建议重构）

### 6. `web_search`

**问题**：末尾"重要：用户问'今天/最新'时不要补年份"——典型的查询构造补丁，和 web_search 工具无关。

**改成**：去掉那段。原描述前半部分保留即可。

**搬到 system prompt 的内容**：
- 搜索 query 构造策略可以放在一个"搜索使用约定"小段，或者交给模型自己判断（GLM/DeepSeek 等主流模型一般不会犯这种错）

---

### 7. `skill_list`

**问题**：开头【首先使用此工具】强调标记 + "在调用 skill_activate 之前必须先调用此工具"——典型的"模型不调 list 直接 activate"后加的强制次序。

**改成**：

```
description:
列出当前可用的技能（已启用 + 沙箱中发现）。返回每个技能的 ID 和一行描述。
```

**搬到 skill_activate 的 execute() 错误返回**：
- skill_activate 找不到 skill_id 时，返回 "未找到该 skill_id，可调用 skill_list 查看可用技能"

---

### 8. `knowledge_search`

**问题**：参数 `query` description 举具体改写例子（"它怎么用"→"Spring Bean 的使用方法"），是代词检索失败后的补丁。

**改成**：

```
参数:
- query: 用于向量检索的关键词或问题。请使用自包含表述，不依赖对话代词。
```

去掉具体改写例子。

---

### 9. `skill_reference`

**问题**：暴露内部路径模板 `/home/gem/skills/<id>/<path>`；"禁止 ../ 或绝对路径"是安全补丁（应在 execute 里校验）；"当技能指令中提到某个参考文档…时使用此工具" 是 when-to-use。

**改成**：

```
description:
读取已激活技能的附属引用文件（模板、示例等）。

参数:
- skill_id: 技能 ID
- path: 相对于该 skill 目录的路径
```

**搬到 execute() 校验**：路径穿越校验代码里做，返回 "路径不能包含 .. 或绝对路径"。

---

### 10. `convert_to_markdown`

**问题**：营销文案风格——"把 URL 给它，一步拿到干净的正文""无需浏览器、无需下载""去除广告、导航、侧栏等噪音""纯净可读"。和其他工具的中性技术描述风格冲突。

**改成**：

```
description:
将网页 URL 或本地文档（HTML/PDF/Word 等）转换为 Markdown 文本。

参数:
- source: URL 或沙箱内文件路径
```

---

### 11. `browser_info`

**问题**：否定列表（"不读取网页 DOM、不返回 URL、不返回 CDP 地址"）+ "通常不必在每次操作前调用"+ 跨工具引导。

**改成**：

```
description:
查询浏览器连接状态、User-Agent 和视口大小。
```

---

### 12. `skill_activate`

**问题**：包含"先使用 skill_list 查看…当你判断某个技能与当前任务相关时…"——工作流和 when-to-use。

**改成**：

```
description:
激活指定技能，返回完整指令以及该技能的 scripts 和 references 清单。

参数:
- skill_id: 技能 ID
```

---

## 描述清晰、无需改动的工具

`read_file`、`write_file`、`execute_command`、`list_files`、`file_search`、`file_replace`、
`parse_document`、`download_file`、`shell_kill`、`view_image`、`mcp_list_servers`、
`mcp_remove_server`、`request_sandbox`、`str_replace_editor`、`shell_wait`、`mcp_reload`、
`run_subagent`、`document_parser`

---

## 共性结论

**补丁式写作的两个重灾区**：
- 浏览器工具组（5 个工具全部有补丁）
- MCP/Skill 元工具组

反映出这两块在迭代中模型出错最多，最后通过"加 desc"而不是"加 system prompt / 加 execute 校验 / 加错误反馈"来兜底。

**统一处理方案**：
- 工作流、调用次序、跨工具选型 → system prompt
- 用户对话策略（确认词识别、不可逆操作）→ system prompt
- 参数校验、安全约束（路径穿越、空参数、缺包名）→ execute() 校验 + 错误返回
- 让每个工具的 description 回归到 **"我是什么 + 我的参数怎么填"**

---

## 与 prompt-redesign.md 的关系

System prompt 重构（`prompt-redesign.md`）已经处理了一些迁移目的地：

- 浏览器相关共同约束 → 可在 prompt-redesign 的 `screenshot` 段后增加一个 `browser` 段（按需加载）
- MCP 安装流程、确认词识别 → 已在 `mcp_management` 段
- skill_activate / skill_list / skill_reference 的使用约定 → 已在 `skill_system` 段
- 不可逆操作需确认 → 建议加到 identity 段或单独的"安全边界"小段

实施顺序建议：
1. 先按 `prompt-redesign.md` 重构 system prompt
2. 再按本文重构工具 description
3. 第二步要确认：从工具 desc 删掉的内容，已经在 system prompt 或 execute 错误返回中有对应承接
