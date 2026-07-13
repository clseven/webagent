# Agent Time Awareness and Process Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一前端过程文案，并让所有 Agent 执行层获得可信、可测试、带时区的当前时间。

**Architecture:** 前端保留现有事件分组结构，只把状态文字改为稳定的动作记录。后端在单轮上下文准备阶段创建 `AgentTimeContext` 快照，规划器和各类执行器共享该快照；`CurrentTimeTool` 通过同一服务按需读取新时间。

**Tech Stack:** Java 17、Spring Boot 3.2.5、JUnit 5、AssertJ、Vue 3 静态前端、Node.js `vm` 测试。

## Global Constraints

- 所有新增或修改的代码注释、Javadoc 和脚本注释必须使用中文。
- 新增类、字段和方法必须说明职责、参数、返回值与异常行为。
- 不修改 AIO Sandbox HTTP 契约。
- 不暂存、不提交、不推送。
- 保留现有未跟踪文件和用户改动。

---

### Task 1: 前端稳定状态文案

**Files:**
- Modify: `src/test/js/api-path.test.js`
- Modify: `src/main/resources/static/js/pages/Chat.js`
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `processTitle(event)`、`ChatStepGrouper.overview(group)`、历史和流式共用的 Vue 模板。
- Produces: 搜索工具固定标题 `已搜索网页（查询词）`，过程总标题和分组标题固定为 `已运行`。

- [ ] **Step 1: 写失败测试**

在现有 Node 测试中加载 `Chat.js` 的纯函数前缀，断言运行中/完成态搜索事件标题一致，`toolResult` 无 `status` 时仍视为完成，模板包含“已运行”。

- [ ] **Step 2: 运行失败测试**

Run: `node src/test/js/api-path.test.js`

Expected: FAIL，因为当前运行中搜索仍显示“正在…”，分组仍生成“搜索中…”和工具计数。

- [ ] **Step 3: 实现最小文案映射**

修改 `processTitle`、`ChatStepGrouper`、流式/历史 summary 和实时推理标题；保留断线异常提示，删除正常路径的状态时态切换和数量文案。

- [ ] **Step 4: 更新静态资源缓存版本并验证**

Run: `node src/test/js/api-path.test.js`

Expected: PASS。

### Task 2: 可测试的时间快照与工具

**Files:**
- Create: `src/main/java/com/example/sandbox/web/service/impl/AgentTimeContext.java`
- Create: `src/main/java/com/example/sandbox/web/service/impl/AgentTimeContextService.java`
- Create: `src/main/java/com/example/sandbox/web/service/tool/CurrentTimeTool.java`
- Modify: `src/test/java/com/example/sandbox/web/service/impl/ReactPromptAssemblerTest.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `AgentTimeContextService.snapshot()`、`snapshot(String zoneId)`、`AgentTimeContext.toPromptSection()` 和 Spring 工具 `current_time`。
- Consumes: Java `Clock`、IANA `ZoneId`、现有 `Tool`/`ToolDefinition` 协议。

- [ ] **Step 1: 写固定 Clock 的失败测试**

测试北京时间快照、UTC 转换、提示词 section、`current_time` schema 和非法时区错误字符串。

- [ ] **Step 2: 运行失败测试**

Run: `mvn "-Dtest=ReactPromptAssemblerTest" "-Dmaven.compiler.testIncludes=**/ReactPromptAssemblerTest.java" test`

Expected: FAIL，因为时间类型和工具尚不存在。

- [ ] **Step 3: 实现快照、服务和工具**

使用可注入 `Clock`，默认读取 `agent.time-zone`；工具标记为 `ALL` 只读能力，非法时区返回 `错误：无效的时区 - <value>`。

- [ ] **Step 4: 运行定向测试**

Run: `mvn "-Dtest=ReactPromptAssemblerTest" "-Dmaven.compiler.testIncludes=**/ReactPromptAssemblerTest.java" test`

Expected: PASS；若本地被既有忽略测试阻断，至少运行 `mvn "-Dmaven.test.skip=true" package` 验证主代码，并记录阻断证据。

### Task 3: 把同一时间快照传到所有 Agent 层

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/impl/AgentTurnContext.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/AgentTurnContextService.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/ReactPromptAssembler.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/ReactAgent.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/ReactAgentFactory.java`
- Modify: `src/test/java/com/example/sandbox/web/service/impl/ReactPromptAssemblerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `AgentTimeContext.toPromptSection()`。
- Produces: `AgentTurnContext.runtimeTimeContext()`；`ReactPromptAssembler.assemble(..., runtimeContext, dynamicContext, plan)`；子代理继承父 Agent 的 `runtimeContext`。

- [ ] **Step 1: 写提示词传播失败测试**

断言运行时上下文位于动态边界之后、业务动态上下文之前；社交轮包含时间但不包含工作空间；规划器 session context 可包含同一 section。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn "-Dtest=ReactPromptAssemblerTest" "-Dmaven.compiler.testIncludes=**/ReactPromptAssemblerTest.java" test`

Expected: FAIL，因为组装器尚无 runtime context 参数。

- [ ] **Step 3: 实现单轮传播**

在 `prepare` 中只取一次快照，写入 `AgentTurnContext`；规划器 session context、普通执行器、社交轮和 `ReactAgent.fork` 复用相同字符串，避免同一轮内时间漂移。

- [ ] **Step 4: 定向验证**

Run: `mvn "-Dtest=ReactPromptAssemblerTest" "-Dmaven.compiler.testIncludes=**/ReactPromptAssemblerTest.java" test`

Expected: PASS。

### Task 4: ADR 与回归验证

**Files:**
- Modify: `docs/project-spec.md`

**Interfaces:**
- Produces: ADR-016，记录“单轮时间快照 + 按需时间工具 + 动态边界”的决策。

- [ ] **Step 1: 补 ADR**

记录决策、排除方案、理由和约束，明确时区来源、缓存边界与最新事实仍需搜索。

- [ ] **Step 2: 运行前端回归**

Run: `node src/test/js/api-path.test.js`

Run: `node src/test/js/file-previewer-office.test.js`

Expected: 全部 PASS。

- [ ] **Step 3: 运行 Java 定向和主构建验证**

Run: `mvn "-Dtest=ReactPromptAssemblerTest" "-Dmaven.compiler.testIncludes=**/ReactPromptAssemblerTest.java" test`

Run: `mvn "-Dmaven.test.skip=true" package`

Expected: 定向测试和主构建 PASS；任何既有测试源阻断需单独报告，不伪装成此次回归。

- [ ] **Step 4: 检查变更边界**

Run: `git status --short`

Run: `git diff --check`

Expected: 仅出现本计划文件，不包含既有未跟踪目录的修改；`git diff --check` 无空白错误。
