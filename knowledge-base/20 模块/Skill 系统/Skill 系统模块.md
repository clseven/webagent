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
  - src/main/java/com/example/sandbox/web/model/entity/Skill.java
updated: 2026-07-09
---

# Skill 系统模块

## 1. 模块概览

本文说明 Skill 系统的发现、同步、渐进式披露和运行时接入方式。

当前实现里需要特别注意五点：

- Skill 来源分 local（本地仓库）和 sandbox（沙箱内扫描）两种，`localPath` 区分。
- 三级渐进式披露：元数据层（摘要进系统提示）→ 详细层（SKILL.md 按需读取）→ 资源层（references/scripts 用到才取）。
- 会话维护自己的 `enabledSkillIds`，与应用的 Skill 独立。
- SOCIAL 轮次跳过 Skill 注入（`policy.shouldInjectSkill()` 为 false）。
- 沙箱创建时只同步有 `localPath` 的技能，本地无副本的跳过。

## 2. Skill 来源

| 来源 | localPath | 说明 |
| --- | --- | --- |
| local | 非 null | 用户在前端配置的本地仓库（`SkillService.setSkillRootPath`），是会话沙箱的种子来源 |
| sandbox | null | 当前会话沙箱 `/home/gem/skills/` 下扫描到的技能，可能是 Agent 自行下载或生成 |
| both | 非 null | 本地仓库和沙箱中都存在（已启用并已同步） |

`Skill.Source` 枚举：LOCAL、SANDBOX、BOTH。沙箱内技能包根目录 `SANDBOX_SKILL_ROOT = "/home/gem/skills"`。

## 3. 会话启用

会话保存 `enabledSkillIds`（`Set<String>`）。前端接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/sessions/{id}/skills` | 查看已启用 Skill |
| GET | `/api/sessions/{id}/skills/available` | 查看可用 Skill |
| POST | `/api/sessions/{id}/skills/{skillId}/enable` | 启用 Skill |
| POST | `/api/sessions/{id}/skills/{skillId}/disable` | 禁用 Skill |

启用后，`AgentSkillRuntimeService` 把 Skill 摘要加入动态上下文（系统提示）。

## 4. 渐进式披露

| 层级 | 内容 | 加载方式 | token 成本 |
| --- | --- | --- | --- |
| 元数据层 | id + description | 系统提示始终暴露已启用技能 | 约 30-50 token/技能 |
| 详细层 | SKILL.md 完整内容 | `skill_activate` 工具按需读取 | 按需 |
| 资源层 | references/、scripts/ | `skill_reference` 工具用到才取 | 按需 |

执行器看到 Skill 摘要后，需要时调用：

- `skill_list`：查看可用 Skill。
- `skill_activate`：读取主指令（SKILL.md）。
- `skill_reference`：读取附加引用文件。

这样避免每轮对话都注入大量 Skill 内容。

## 5. 规划技能元数据

`AgentSkillRuntimeService.findPlanningSkills` 为 PlanAgent 提供规划技能元数据。`buildSessionContext` 为规划器构建会话上下文。这些让规划器知道有哪些技能可用，在策略建议中可以提及。

## 6. 与 Sandbox 的关系

用户 Sandbox 创建或恢复后，`SandboxServiceImpl.syncAllEnabledSkills`：

1. 从会话读取已启用技能 ID 集合。
2. 逐个获取技能，只推送有 `localPath` 的（本地有副本的）。
3. `FileSyncService.syncSkill` 推送技能文件到 `/home/gem/skills/`。
4. 本地无副本的技能跳过，不视为失败。

## 7. 与 TurnPolicy 的关系

SOCIAL 轮次（`policy.shouldInjectSkill()` 为 false）跳过 Skill 注入：

- `skillPrompt` 为空串。
- 工具上下文用 `AgentToolContext.empty()`，不注入技能工具。
- 只有 TASK 和 AMBIGUOUS 轮次才会注入 Skill。

## 8. 风险与排查

| 现象 | 优先检查 | 说明 |
| --- | --- | --- |
| Skill 未注入 | `enabledSkillIds`、`policy.shouldInjectSkill()` | SOCIAL 轮次跳过 |
| Skill 文件未同步 | `localPath` 是否为 null、`syncAllEnabledSkills` 日志 | 本地无副本的技能跳过 |
| skill_activate 读不到内容 | 沙箱 `/home/gem/skills/` 目录 | 运行期走沙箱，检查文件是否同步 |
| Skill 摘要过长 | 元数据层 token 数 | 摘要应控制在 30-50 token/技能 |

## 9. 相关页面

[[Skill 与知识检索工具]] · [[Agent 应用与会话模块]] · [[计划与 ReAct 执行]] · [[Sandbox 模块]]