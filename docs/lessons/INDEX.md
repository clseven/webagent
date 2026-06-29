# Learn Claude Code — 完整课程索引

> 从 0 到 1 构建 nano Claude Code-like agent，每次只加一个机制

---

### 🔵 工具与执行

| 编号 | 课程 | 文件 |
|------|------|------|
| s01 | [Agent Loop — 一个循环就够了](s01.md) | `s01_agent_loop/` |
| s02 | [Tool Use — 多加一个工具，只加一行](s02.md) | `s02_tool_use/` |
| s03 | [Permission — 执行前做权限判断](s03.md) | `s03_permission/` |
| s04 | [Hooks — 挂在循环上，不写进循环里](s04.md) | `s04_hooks/` |
### 🟢 规划与协调

| 编号 | 课程 | 文件 |
|------|------|------|
| s05 | [TodoWrite — 没有计划的 Agent，做着做着就偏了](s05.md) | `s05_todo_write/` |
| s06 | [Subagent — 大任务拆小，每个拿到的都是干净上下文](s06.md) | `s06_subagent/` |
| s07 | [Skill Loading — 用到的时候才加载](s07.md) | `s07_skill_loading/` |
### 🟣 记忆管理

| 编号 | 课程 | 文件 |
|------|------|------|
| s08 | [Context Compact — 上下文总会满，要有办法腾地方](s08.md) | `s08_context_compact/` |
| s09 | [Memory — 压缩会丢细节，要有一层不丢的](s09.md) | `s09_memory/` |
### 🟢 规划与协调

| 编号 | 课程 | 文件 |
|------|------|------|
| s10 | [System Prompt — 运行时组装，不硬编码](s10.md) | `s10_system_prompt/` |
| s11 | [Error Recovery — 错误不是结束，是重试的开始](s11.md) | `s11_error_recovery/` |
### 🔴 多Agent平台

| 编号 | 课程 | 文件 |
|------|------|------|
| s12 | [Task System — 目标太大，拆成小任务](s12.md) | `s12_task_system/` |
### 🟠 并发

| 编号 | 课程 | 文件 |
|------|------|------|
| s13 | [Background Tasks — 慢操作放后台](s13.md) | `s13_background_tasks/` |
| s14 | [Cron Scheduler — 按时间表生产工作](s14.md) | `s14_cron_scheduler/` |
### 🔴 多Agent平台

| 编号 | 课程 | 文件 |
|------|------|------|
| s15 | [Agent Teams — 一个搞不定，组队来](s15.md) | `s15_agent_teams/` |
| s16 | [Team Protocols — 队友之间要有约定](s16.md) | `s16_team_protocols/` |
| s17 | [Autonomous Agents — 自己看板，自己认领](s17.md) | `s17_autonomous_agents/` |
| s18 | [Worktree Isolation — 各干各的，互不干扰](s18.md) | `s18_worktree_isolation/` |
| s19 | [MCP Tools — 外接工具，标准协议](s19.md) | `s19_mcp_plugin/` |
| s20 | [Comprehensive Agent — 全部机制，归到一个循环](s20.md) | `s20_comprehensive/` |

---

## 学习路径

```
s01 Agent Loop     ─┐
s02 Tool Use       ─┤ 工具与执行
s03 Permission     ─┤
s04 Hooks          ─┘

s05 TodoWrite      ─┐
s06 Subagent       ─┤
s07 Skill Loading  ─┤ 规划与协调
s10 System Prompt  ─┤
s11 Error Recovery ─┘

s08 Context Compact─┐ 记忆管理
s09 Memory         ─┘

s13 Background      ─┐ 并发
s14 Cron Scheduler ─┘

s12 Task System     ─┐
s15 Agent Teams     ─┤
s16 Team Protocols  ─┤
s17 Autonomous      ─┤ 多Agent平台
s18 Worktree        ─┤
s19 MCP Tools       ─┤
s20 Comprehensive   ─┘
```
