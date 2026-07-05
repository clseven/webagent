---
project: webagent-clean
type: module
status: verified
area:
  - tools
  - sandbox
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/tool/ExecuteCommandTool.java
  - src/main/java/com/example/sandbox/web/service/tool/ShellWaitTool.java
  - src/main/java/com/example/sandbox/web/service/tool/ShellKillTool.java
  - src/main/java/com/example/sandbox/web/service/tool/RequestSandboxTool.java
updated: 2026-07-06
---

# Shell 与生命周期工具

## Shell 工具

Shell 工具面向 AIO Sandbox：

- `execute_command` 执行命令。
- `shell_wait` 等待长任务输出。
- `shell_kill` 终止长任务。

前端工作区树也复用命令执行能力，通过 `find /home/gem` 获取目录结构。

## 生命周期工具

`request_sandbox` 用于显式请求或确认沙箱资源。当前系统大部分会话会在创建或首次执行时自动确保用户级 Sandbox 可用，因此该工具更多是 Agent 可见的能力入口。

## 与后台任务

`run_subagent` 可把耗时子任务交给后台任务管理器，主 Agent 之后收集结果。Shell 工具本身仍是单次动作，不直接创建 Agent 后台任务。

## 相关页面

[[Sandbox 模块]] · [[工具目录]] · [[Agent 工具调用]]