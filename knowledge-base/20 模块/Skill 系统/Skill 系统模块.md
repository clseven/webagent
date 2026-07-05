---
project: webagent-clean
type: module
status: verified
area:
  - skills
tags:
  - project/webagent-clean
source:
  - src/main/java/com/example/sandbox/web/service/impl/AgentSkillRuntimeService.java
  - src/main/java/com/example/sandbox/web/controller/SkillController.java
  - src/main/java/com/example/sandbox/web/service/tool/SkillListTool.java
  - src/main/java/com/example/sandbox/web/service/tool/SkillActivateTool.java
  - src/main/java/com/example/sandbox/web/service/tool/SkillReferenceTool.java
updated: 2026-07-06
---

# Skill 系统模块

## 职责

Skill 系统让用户把可复用工作流、工具说明和领域指令挂到会话中。它分为三层：

- 本地 Skill 发现：从配置的 Skill 根目录读取。
- Sandbox Skill 同步：把启用 Skill 同步到 `/home/gem/skills`。
- Agent 运行时披露：摘要进系统提示，完整内容通过工具按需读取。

## 会话启用

会话保存 `enabledSkillIds`。前端可以查看当前会话可用 Skill，并对单个 Skill 启用或禁用。启用后，`AgentSkillRuntimeService` 会把 Skill 摘要加入动态上下文。

## 渐进式披露

执行器看到 Skill 摘要后，需要时调用：

- `skill_list` 查看可用 Skill。
- `skill_activate` 读取主指令。
- `skill_reference` 读取附加引用。

这样可以避免每轮对话都注入大量 Skill 内容。

## 与 Sandbox 的关系

用户 Sandbox 创建或恢复后，`SandboxServiceImpl` 会同步当前会话启用的 Skill 文件。这样工具或 shell 能在沙箱内访问 Skill 相关资源。

## 面试可讲点

Skill 系统体现了“提示词不是越多越好”的工程取舍：先用摘要指导模型选择，再按需读取完整指令，兼顾上下文成本和可扩展性。

## 相关页面

[[Skill 与知识检索工具]] · [[Agent 应用与会话模块]] · [[计划与 ReAct 执行]]