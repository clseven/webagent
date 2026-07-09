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
updated: 2026-07-09
---

# Shell 与生命周期工具

## 1. 模块概览

本文说明 Shell 命令执行和沙箱生命周期相关的工具实现。

Shell 工具面向 AIO Sandbox，通过 `AioClient.shell().exec()` 执行命令。生命周期工具用于显式请求或确认沙箱资源。

## 2. Shell 工具

### 2.1 execute_command

| 属性 | 值 |
| --- | --- |
| 工具名 | `execute_command` |
| sandboxType | `AIO` |
| sideEffect | `EXCLUSIVE`（完全串行） |
| 功能 | 在沙箱内执行 shell 命令，返回 stdout/stderr 和退出码 |

执行流程：

1. 从参数获取命令字符串和可选工作目录。
2. 通过 `AioClient.shell().exec(command)` 在沙箱内执行。
3. 返回 `ShellExecResult`，包含 exitCode、output 和 success 状态。
4. 前端工作区树也复用此能力，通过 `find /home/gem` 获取目录结构。

异常处理：执行异常返回错误消息字符串，不抛异常，确保 Agent 循环不中断。

### 2.2 shell_wait

| 属性 | 值 |
| --- | --- |
| 工具名 | `shell_wait` |
| sandboxType | `AIO` |
| sideEffect | `EXCLUSIVE` |
| 功能 | 等待长任务输出，轮询检查是否有新输出 |

用于等待异步命令完成或获取增量输出。

### 2.3 shell_kill

| 属性 | 值 |
| --- | --- |
| 工具名 | `shell_kill` |
| sandboxType | `AIO` |
| sideEffect | `EXCLUSIVE` |
| 功能 | 终止长任务 |

用于终止正在运行的长任务，释放沙箱资源。

## 3. 生命周期工具

### request_sandbox

| 属性 | 值 |
| --- | --- |
| 工具名 | `request_sandbox` |
| sandboxType | `ALL` |
| sideEffect | `EXCLUSIVE` |
| 功能 | 显式请求或确认沙箱资源 |

当前系统大部分会话在创建或首次执行时自动确保用户级 Sandbox 可用（`AgentTurnContextService.ensureSandboxReady`），因此该工具更多是 Agent 可见的能力入口，让 Agent 在需要时显式确认沙箱状态。

## 4. 并发调度

Shell 工具的 `sideEffect` 都是 `EXCLUSIVE`（完全串行），因为命令执行会改变环境状态。即使开启并发执行（`concurrent-tool-execution-enabled=true`），Shell 工具也会串行执行，不会与其他工具并发。

## 5. 与后台任务的关系

`run_subagent` 可把耗时子任务交给 `BackgroundTaskManager` 后台运行，主 Agent 之后收集结果。Shell 工具本身仍是单次动作，不直接创建 Agent 后台任务。如果需要长时间运行命令，应配合 `shell_wait` 轮询或 `run_subagent` 后台执行。

## 6. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| 命令执行超时 | AIO 响应超时、命令本身耗时 | 响应超时 300 秒，长任务用 shell_wait |
| 命令返回非零退出码 | exitCode、output | execShellWithRetry 会重试 3 次，但工具执行不重试 |
| 沙箱不可用 | `ensureSandboxReady`、endpoint 健康检查 | 首次访问时同步创建 |
| 工作区树为空 | `find /home/gem` 命令、沙箱目录初始化 | 检查 `initAioDirectories` 是否成功 |

## 7. 相关页面

[[Sandbox 模块]] · [[工具目录]] · [[Agent 工具调用]] · [[工具系统模块]]