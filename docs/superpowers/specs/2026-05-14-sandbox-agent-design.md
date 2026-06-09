# Sandbox Agent 设计规格

## 概述

构建一个 Web 端智能对话 Agent，具备沙盒操作能力、Skill 管理能力和对话记忆能力。

**设计日期**: 2026-05-14

## 架构设计

### 分层架构

```
┌─────────────────────────────────────────┐
│           Web Layer (Controller)         │  ← HTTP API
├─────────────────────────────────────────┤
│           Service Layer                  │
│  ┌─────────────┐  ┌──────────────────┐  │
│  │ AgentService│  │  SkillService    │  │
│  └─────────────┘  └──────────────────┘  │
│  ┌─────────────┐  ┌──────────────────┐  │
│  │SandboxService│ │ConversationService│ │
│  └─────────────┘  └──────────────────┘  │
├─────────────────────────────────────────┤
│         Infrastructure Layer             │
│         SandboxAgent (OpenSandbox)       │  ← 沙盒能力
└─────────────────────────────────────────┘
```

### 包结构

```
src/main/java/com/example/sandbox/
├── web/
│   ├── SandboxAgentApplication.java   # Spring Boot 启动类
│   ├── controller/
│   │   ├── AgentController.java       # 会话及对话 API
│   │   ├── SandboxController.java     # 沙盒操作 API
│   │   └── SkillController.java       # Skill 管理 API
│   ├── service/
│   │   ├── AgentService.java          # Agent 编排服务
│   │   ├── SandboxService.java        # 沙盒操作服务
│   │   ├── SkillService.java          # Skill 管理服务
│   │   └── ConversationService.java   # 对话记忆服务
│   ├── model/
│   │   ├── entity/                    # 领域模型
│   │   │   ├── ChatMessage.java
│   │   │   ├── ConversationSession.java
│   │   │   ├── Skill.java
│   │   │   └── ExecutionResult.java
│   │   ├── request/                   # 请求 DTO
│   │   │   ├── ChatRequest.java
│   │   │   ├── ExecuteRequest.java
│   │   │   └── FileWriteRequest.java
│   │   └── response/                  # 响应 DTO
│   │       ├── ApiResponse.java
│   │       └── SessionResponse.java
│   └── config/
│       ├── AgentConfig.java           # Agent 配置
│       └── SkillLoader.java           # Skill 初始化加载器
└── agent/
    └── SandboxAgent.java              # 已有，沙盒操作封装
```

## 核心模型

### ChatMessage（对话消息）

| 字段 | 类型 | 说明 |
|------|------|------|
| role | String | 角色：user / assistant / system |
| content | String | 消息内容 |
| timestamp | Long | 时间戳 |

### ConversationSession（会话）

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | String | 会话唯一标识 |
| messages | List<ChatMessage> | 消息历史 |
| sandboxId | String | 关联的沙盒实例 ID |
| enabledSkillIds | Set<String> | 启用的 Skill ID 集合 |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |

**设计决策**: `sandboxId` 只存储 ID 标识，不持有 SandboxAgent 对象，避免：
- POJO 依赖可变业务服务（违反阿里规约）
- 序列化时的问题
- 职责混乱

### Skill（技能）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | Skill 唯一标识 |
| name | String | 显示名称 |
| description | String | 描述 |
| content | String | SKILL.md 完整内容 |

### ExecutionResult（执行结果）

| 字段 | 类型 | 说明 |
|------|------|------|
| status | ExecutionStatus | 状态：SUCCESS / ERROR / TIMEOUT |
| body | String | 成功时为 output，失败时为 error |
| duration | Duration | 执行耗时 |

```java
public enum ExecutionStatus {
    SUCCESS,
    ERROR,
    TIMEOUT
}
```

## 服务接口设计

### AgentService（编排服务）

```java
public interface AgentService {
    ConversationSession createSession();
    void closeSession(String sessionId);
    ConversationSession getSession(String sessionId);
    ChatMessage chat(String sessionId, String userMessage);
}
```

**chat 方法内部流程**:
```
chat(sessionId, userMessage)
  → ConversationService.addUserMessage()    # 存储用户消息
  → ConversationService.buildPrompt()       # 构建上下文
  → SkillService.getEnabledSkills()         # 获取启用的技能
  → LLM 调用                                 # TODO: 待集成
  → ConversationService.addAssistantMessage() # 存储响应
  → return ChatMessage
```

### SandboxService（沙盒操作）

```java
public interface SandboxService {
    ExecutionResult executeCommand(String sessionId, String command);
    ExecutionResult readFile(String sessionId, String path);
    ExecutionResult writeFile(String sessionId, String path, String content);
}
```

### SkillService（技能管理）

```java
public interface SkillService {
    List<Skill> listSkills();           // 返回元数据（不含 content）
    Skill getSkill(String skillId);     // 返回完整 Skill（含 content）
    void registerSkill(Skill skill);
    void unregisterSkill(String skillId);
}
```

**内部实现**:
- `parseSkillMd()` 方法私有化
- `loadSkillsFromDirectory()` 在 `SkillLoader` 初始化时调用

### ConversationService（对话记忆）

```java
public interface ConversationService {
    // 消息管理
    void addUserMessage(String sessionId, String content);
    void addAssistantMessage(String sessionId, String content);
    List<ChatMessage> getHistory(String sessionId);
    void clearHistory(String sessionId);

    // 上下文构建
    String buildPrompt(String sessionId);

    // 会话级 Skill 开关
    void enableSkill(String sessionId, String skillId);
    void disableSkill(String sessionId, String skillId);
    Set<String> getEnabledSkillIds(String sessionId);
}
```

**设计决策**:
- 拆分 `addUserMessage` / `addAssistantMessage` 避免 role 伪造
- `buildPrompt` 明确表示构建发送给 LLM 的 prompt

## RESTful API 设计

### AgentController — 会话及对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/sessions | 创建会话 |
| DELETE | /api/sessions/{id} | 关闭会话 |
| GET | /api/sessions/{id} | 获取会话信息 |
| POST | /api/sessions/{id}/chat | 发送消息 |
| GET | /api/sessions/{id}/history | 获取历史消息 |
| GET | /api/sessions/{id}/skills | 获取启用的技能 |
| POST | /api/sessions/{id}/skills/{skillId}/enable | 启用技能 |
| POST | /api/sessions/{id}/skills/{skillId}/disable | 禁用技能 |

### SandboxController — 沙盒操作

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/sessions/{id}/execute | 执行命令 |
| POST | /api/sessions/{id}/files/read | 读取文件 |
| POST | /api/sessions/{id}/files/write | 写入文件 |

### SkillController — 全局技能管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/skills | 列出所有技能 |
| GET | /api/skills/{id} | 获取技能详情 |
| POST | /api/skills | 注册新技能 |
| DELETE | /api/skills/{id} | 注销技能 |

## 配置设计

### application.yml

```yaml
server:
  port: 8081

sandbox:
  domain: ${SANDBOX_DOMAIN:localhost:8080}
  image: ${SANDBOX_IMAGE:sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.2}
  timeout: 30m
  ready-timeout: 120s

skill:
  directory: ${SKILL_DIR:.claude/skills}

agent:
  llm-api-url: ${LLM_API_URL:}
  llm-api-key: ${LLM_API_KEY:}
```

## 待实现事项

### TODO: LLM 集成

- [ ] 定义 LLM 客户端接口
- [ ] 实现默认 HTTP 客户端
- [ ] 支持流式响应
- [ ] 支持多种模型提供商

### TODO: 增强功能

- [ ] 会话持久化
- [ ] 多用户隔离
- [ ] 沙盒实例池
- [ ] 执行日志审计

## 技术栈

- Java 17
- Spring Boot 3.x
- OpenSandbox SDK 1.0.10
- Jackson (JSON)
- Maven

## 设计原则

遵循阿里巴巴 Java 开发手册：
- 单一职责：每个服务只做一件事
- POJO 不依赖可变业务服务
- 各层各司其职，避免跨层污染
- 命名规范：类名 UpperCamelCase，方法名 lowerCamelCase，常量 UPPER_SNAKE_CASE
