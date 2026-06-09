# Sandbox Agent 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 构建一个 Web 端智能对话 Agent，具备沙盒操作能力、Skill 管理能力和对话记忆能力

**架构：** 分层架构（Controller → Service → Infrastructure），遵循阿里巴巴 Java 开发手册，单一职责原则

**技术栈：** Java 17, Spring Boot 3.x, OpenSandbox SDK 1.0.10, Maven

---

## 文件结构

```
src/main/java/com/example/sandbox/
├── web/
│   ├── SandboxAgentApplication.java        # Spring Boot 启动类
│   ├── controller/
│   │   ├── AgentController.java            # 会话及对话 API
│   │   ├── SandboxController.java          # 沙盒操作 API
│   │   └── SkillController.java            # Skill 管理 API
│   ├── service/
│   │   ├── AgentService.java               # Agent 编排服务接口
│   │   ├── impl/
│   │   │   ├── AgentServiceImpl.java       # Agent 编排服务实现
│   │   │   ├── SandboxServiceImpl.java     # 沙盒操作服务实现
│   │   │   ├── SkillServiceImpl.java       # Skill 管理服务实现
│   │   │   └── ConversationServiceImpl.java# 对话记忆服务实现
│   │   ├── SandboxService.java             # 沙盒操作服务接口
│   │   ├── SkillService.java               # Skill 管理服务接口
│   │   └── ConversationService.java        # 对话记忆服务接口
│   ├── model/
│   │   ├── entity/
│   │   │   ├── ChatMessage.java            # 对话消息
│   │   │   ├── ConversationSession.java    # 会话
│   │   │   ├── Skill.java                  # 技能
│   │   │   ├── ExecutionResult.java        # 执行结果
│   │   │   └── ExecutionStatus.java        # 执行状态枚举
│   │   ├── request/
│   │   │   ├── ChatRequest.java            # 对话请求
│   │   │   ├── ExecuteRequest.java         # 命令执行请求
│   │   │   └── FileWriteRequest.java       # 文件写入请求
│   │   └── response/
│   │       ├── ApiResponse.java            # 统一响应
│   │       └── SessionResponse.java        # 会话响应
│   ├── config/
│   │   ├── AgentConfig.java                # Agent 配置属性
│   │   └── SkillLoader.java                # Skill 初始化加载器
│   └── exception/
│       ├── SessionNotFoundException.java   # 会话不存在异常
│       └── SkillNotFoundException.java     # 技能不存在异常
└── agent/
    └── SandboxAgent.java                   # 已有，沙盒操作封装

src/main/resources/
└── application.yml                         # 配置文件

src/test/java/com/example/sandbox/web/
├── service/
│   ├── AgentServiceTest.java
│   ├── SandboxServiceTest.java
│   ├── SkillServiceTest.java
│   └── ConversationServiceTest.java
└── controller/
    ├── AgentControllerTest.java
    ├── SandboxControllerTest.java
    └── SkillControllerTest.java
```

---

## 任务 1：更新 Maven 依赖

**文件：**
- 修改：`pom.xml`

- [ ] **步骤 1：添加 Spring Boot 父依赖和依赖管理**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>org.example</groupId>
    <artifactId>sandbox</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <java.version>17</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring Boot Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Jackson for JSON -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <!-- OpenSandbox SDK -->
        <dependency>
            <groupId>com.alibaba.opensandbox</groupId>
            <artifactId>sandbox</artifactId>
            <version>1.0.10</version>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
```

- [ ] **步骤 2：运行 Maven 验证依赖**

运行：`cd D:/sandbox/sandbox && mvn dependency:resolve`
预期：BUILD SUCCESS

---

## 任务 2：创建核心模型 - ExecutionStatus 枚举

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/model/entity/ExecutionStatus.java`

- [ ] **步骤 1：创建 ExecutionStatus 枚举**

```java
package com.example.sandbox.web.model.entity;

/**
 * 执行状态枚举
 *
 * @author example
 * @date 2026/05/14
 */
public enum ExecutionStatus {

    /**
     * 执行成功
     */
    SUCCESS,

    /**
     * 执行错误
     */
    ERROR,

    /**
     * 执行超时
     */
    TIMEOUT
}
```

---

## 任务 3：创建核心模型 - ExecutionResult

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/model/entity/ExecutionResult.java`

- [ ] **步骤 1：创建 ExecutionResult 类**

```java
package com.example.sandbox.web.model.entity;

import java.time.Duration;

/**
 * 执行结果
 *
 * @author example
 * @date 2026/05/14
 */
public class ExecutionResult {

    /**
     * 执行状态
     */
    private final ExecutionStatus status;

    /**
     * 执行结果内容（成功时为输出，失败时为错误信息）
     */
    private final String body;

    /**
     * 执行耗时
     */
    private final Duration duration;

    private ExecutionResult(ExecutionStatus status, String body, Duration duration) {
        this.status = status;
        this.body = body;
        this.duration = duration;
    }

    /**
     * 创建成功结果
     */
    public static ExecutionResult success(String output, Duration duration) {
        return new ExecutionResult(ExecutionStatus.SUCCESS, output, duration);
    }

    /**
     * 创建错误结果
     */
    public static ExecutionResult error(String errorMessage, Duration duration) {
        return new ExecutionResult(ExecutionStatus.ERROR, errorMessage, duration);
    }

    /**
     * 创建超时结果
     */
    public static ExecutionResult timeout(Duration duration) {
        return new ExecutionResult(ExecutionStatus.TIMEOUT, "Execution timed out", duration);
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }

    public Duration getDuration() {
        return duration;
    }

    public boolean isSuccess() {
        return status == ExecutionStatus.SUCCESS;
    }

    @Override
    public String toString() {
        return "ExecutionResult{" +
                "status=" + status +
                ", body='" + body + '\'' +
                ", duration=" + duration +
                '}';
    }
}
```

---

## 任务 4：创建核心模型 - ChatMessage

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/model/entity/ChatMessage.java`

- [ ] **步骤 1：创建 ChatMessage 类**

```java
package com.example.sandbox.web.model.entity;

import java.time.Instant;

/**
 * 对话消息
 *
 * @author example
 * @date 2026/05/14
 */
public class ChatMessage {

    /**
     * 消息角色
     */
    private final String role;

    /**
     * 消息内容
     */
    private final String content;

    /**
     * 时间戳（毫秒）
     */
    private final Long timestamp;

    private ChatMessage(String role, String content, Long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    /**
     * 创建用户消息
     */
    public static ChatMessage userMessage(String content) {
        return new ChatMessage("user", content, Instant.now().toEpochMilli());
    }

    /**
     * 创建助手消息
     */
    public static ChatMessage assistantMessage(String content) {
        return new ChatMessage("assistant", content, Instant.now().toEpochMilli());
    }

    /**
     * 创建系统消息
     */
    public static ChatMessage systemMessage(String content) {
        return new ChatMessage("system", content, Instant.now().toEpochMilli());
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "role='" + role + '\'' +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
```

---

## 任务 5：创建核心模型 - Skill

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/model/entity/Skill.java`

- [ ] **步骤 1：创建 Skill 类**

```java
package com.example.sandbox.web.model.entity;

/**
 * 技能
 *
 * @author example
 * @date 2026/05/14
 */
public class Skill {

    /**
     * 技能唯一标识
     */
    private final String id;

    /**
     * 显示名称
     */
    private final String name;

    /**
     * 描述
     */
    private final String description;

    /**
     * SKILL.md 完整内容
     */
    private final String content;

    public Skill(String id, String name, String description, String content) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.content = content;
    }

    /**
     * 创建元数据（不含完整内容）
     */
    public Skill toMetadata() {
        return new Skill(id, name, description, null);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return "Skill{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
```

---

## 任务 6：创建核心模型 - ConversationSession

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/model/entity/ConversationSession.java`

- [ ] **步骤 1：创建 ConversationSession 类**

```java
package com.example.sandbox.web.model.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 对话会话
 *
 * @author example
 * @date 2026/05/14
 */
public class ConversationSession {

    /**
     * 会话唯一标识
     */
    private final String sessionId;

    /**
     * 消息历史
     */
    private final List<ChatMessage> messages;

    /**
     * 关联的沙盒实例 ID
     */
    private String sandboxId;

    /**
     * 启用的技能 ID 集合
     */
    private final Set<String> enabledSkillIds;

    /**
     * 创建时间
     */
    private final LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    public ConversationSession() {
        this.sessionId = UUID.randomUUID().toString();
        this.messages = new ArrayList<>();
        this.enabledSkillIds = new HashSet<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
        this.updatedAt = LocalDateTime.now();
    }

    public Set<String> getEnabledSkillIds() {
        return enabledSkillIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 添加消息并更新时间
     */
    public void addMessage(ChatMessage message) {
        messages.add(message);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 启用技能
     */
    public void enableSkill(String skillId) {
        enabledSkillIds.add(skillId);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 禁用技能
     */
    public void disableSkill(String skillId) {
        enabledSkillIds.remove(skillId);
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "ConversationSession{" +
                "sessionId='" + sessionId + '\'' +
                ", sandboxId='" + sandboxId + '\'' +
                ", messageCount=" + messages.size() +
                ", enabledSkillIds=" + enabledSkillIds +
                '}';
    }
}
```

---

## 任务 7：创建请求 DTO

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/model/request/ChatRequest.java`
- 创建：`src/main/java/com/example/sandbox/web/model/request/ExecuteRequest.java`
- 创建：`src/main/java/com/example/sandbox/web/model/request/FileWriteRequest.java`

- [ ] **步骤 1：创建 ChatRequest**

```java
package com.example.sandbox.web.model.request;

/**
 * 对话请求
 *
 * @author example
 * @date 2026/05/14
 */
public class ChatRequest {

    /**
     * 用户消息内容
     */
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
```

- [ ] **步骤 2：创建 ExecuteRequest**

```java
package com.example.sandbox.web.model.request;

/**
 * 命令执行请求
 *
 * @author example
 * @date 2026/05/14
 */
public class ExecuteRequest {

    /**
     * 要执行的命令
     */
    private String command;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }
}
```

- [ ] **步骤 3：创建 FileWriteRequest**

```java
package com.example.sandbox.web.model.request;

/**
 * 文件写入请求
 *
 * @author example
 * @date 2026/05/14
 */
public class FileWriteRequest {

    /**
     * 文件路径
     */
    private String path;

    /**
     * 文件内容
     */
    private String content;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
```

---

## 任务 8：创建响应 DTO

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/model/response/ApiResponse.java`
- 创建：`src/main/java/com/example/sandbox/web/model/response/SessionResponse.java`

- [ ] **步骤 1：创建 ApiResponse（统一响应）**

```java
package com.example.sandbox.web.model.response;

/**
 * 统一 API 响应
 *
 * @author example
 * @date 2026/05/14
 */
public class ApiResponse<T> {

    /**
     * 响应码
     */
    private final int code;

    /**
     * 响应消息
     */
    private final String message;

    /**
     * 响应数据
     */
    private final T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 成功响应（无数据）
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "success", null);
    }

    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 未找到响应
     */
    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(404, message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
```

- [ ] **步骤 2：创建 SessionResponse**

```java
package com.example.sandbox.web.model.response;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 会话响应
 *
 * @author example
 * @date 2026/05/14
 */
public class SessionResponse {

    private String sessionId;
    private String sandboxId;
    private Set<String> enabledSkillIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public Set<String> getEnabledSkillIds() {
        return enabledSkillIds;
    }

    public void setEnabledSkillIds(Set<String> enabledSkillIds) {
        this.enabledSkillIds = enabledSkillIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

---

## 任务 9：创建自定义异常

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/exception/SessionNotFoundException.java`
- 创建：`src/main/java/com/example/sandbox/web/exception/SkillNotFoundException.java`

- [ ] **步骤 1：创建 SessionNotFoundException**

```java
package com.example.sandbox.web.exception;

/**
 * 会话不存在异常
 *
 * @author example
 * @date 2026/05/14
 */
public class SessionNotFoundException extends RuntimeException {

    private final String sessionId;

    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
```

- [ ] **步骤 2：创建 SkillNotFoundException**

```java
package com.example.sandbox.web.exception;

/**
 * 技能不存在异常
 *
 * @author example
 * @date 2026/05/14
 */
public class SkillNotFoundException extends RuntimeException {

    private final String skillId;

    public SkillNotFoundException(String skillId) {
        super("Skill not found: " + skillId);
        this.skillId = skillId;
    }

    public String getSkillId() {
        return skillId;
    }
}
```

---

## 任务 10：创建服务接口

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/service/AgentService.java`
- 创建：`src/main/java/com/example/sandbox/web/service/SandboxService.java`
- 创建：`src/main/java/com/example/sandbox/web/service/SkillService.java`
- 创建：`src/main/java/com/example/sandbox/web/service/ConversationService.java`

- [ ] **步骤 1：创建 AgentService 接口**

```java
package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;

/**
 * Agent 编排服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface AgentService {

    /**
     * 创建新会话
     *
     * @return 新创建的会话
     */
    ConversationSession createSession();

    /**
     * 关闭会话
     *
     * @param sessionId 会话 ID
     */
    void closeSession(String sessionId);

    /**
     * 获取会话
     *
     * @param sessionId 会话 ID
     * @return 会话信息
     */
    ConversationSession getSession(String sessionId);

    /**
     * 对话
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return 助手响应
     */
    ChatMessage chat(String sessionId, String userMessage);
}
```

- [ ] **步骤 2：创建 SandboxService 接口**

```java
package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ExecutionResult;

/**
 * 沙盒操作服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface SandboxService {

    /**
     * 执行命令
     *
     * @param sessionId 会话 ID
     * @param command   命令
     * @return 执行结果
     */
    ExecutionResult executeCommand(String sessionId, String command);

    /**
     * 读取文件
     *
     * @param sessionId 会话 ID
     * @param path      文件路径
     * @return 执行结果
     */
    ExecutionResult readFile(String sessionId, String path);

    /**
     * 写入文件
     *
     * @param sessionId 会话 ID
     * @param path      文件路径
     * @param content   文件内容
     * @return 执行结果
     */
    ExecutionResult writeFile(String sessionId, String path, String content);
}
```

- [ ] **步骤 3：创建 SkillService 接口**

```java
package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.Skill;

import java.util.List;

/**
 * 技能管理服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface SkillService {

    /**
     * 列出所有技能（仅元数据）
     *
     * @return 技能列表
     */
    List<Skill> listSkills();

    /**
     * 获取技能详情
     *
     * @param skillId 技能 ID
     * @return 技能详情（含完整内容）
     */
    Skill getSkill(String skillId);

    /**
     * 注册技能
     *
     * @param skill 技能
     */
    void registerSkill(Skill skill);

    /**
     * 注销技能
     *
     * @param skillId 技能 ID
     */
    void unregisterSkill(String skillId);

    /**
     * 从目录加载技能
     *
     * @param directory 目录路径
     */
    void loadSkillsFromDirectory(String directory);
}
```

- [ ] **步骤 4：创建 ConversationService 接口**

```java
package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;

import java.util.List;
import java.util.Set;

/**
 * 对话记忆服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface ConversationService {

    /**
     * 添加用户消息
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     */
    void addUserMessage(String sessionId, String content);

    /**
     * 添加助手消息
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     */
    void addAssistantMessage(String sessionId, String content);

    /**
     * 获取消息历史
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<ChatMessage> getHistory(String sessionId);

    /**
     * 清空消息历史
     *
     * @param sessionId 会话 ID
     */
    void clearHistory(String sessionId);

    /**
     * 构建发送给 LLM 的 prompt
     *
     * @param sessionId 会话 ID
     * @return prompt 字符串
     */
    String buildPrompt(String sessionId);

    /**
     * 启用技能
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    void enableSkill(String sessionId, String skillId);

    /**
     * 禁用技能
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    void disableSkill(String sessionId, String skillId);

    /**
     * 获取启用的技能 ID
     *
     * @param sessionId 会话 ID
     * @return 技能 ID 集合
     */
    Set<String> getEnabledSkillIds(String sessionId);
}
```

---

## 任务 11：创建服务实现

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/service/impl/AgentServiceImpl.java`
- 创建：`src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java`
- 创建：`src/main/java/com/example/sandbox/web/service/impl/SkillServiceImpl.java`
- 创建：`src/main/java/com/example/sandbox/web/service/impl/ConversationServiceImpl.java`

- [ ] **步骤 1：创建 ConversationServiceImpl**

```java
package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.service.ConversationService;
import com.example.sandbox.web.service.SkillService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话记忆服务实现
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    /**
     * 会话存储（内存存储，后续可替换为持久化存储）
     */
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    private final SkillService skillService;

    public ConversationServiceImpl(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 获取或创建会话
     */
    public ConversationSession getOrCreateSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new ConversationSession());
    }

    /**
     * 创建新会话
     */
    public ConversationSession createSession() {
        ConversationSession session = new ConversationSession();
        sessions.put(session.getSessionId(), session);
        return session;
    }

    /**
     * 删除会话
     */
    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 获取会话（不存在则抛异常）
     */
    public ConversationSession getSession(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException(sessionId);
        }
        return session;
    }

    @Override
    public void addUserMessage(String sessionId, String content) {
        ConversationSession session = getSession(sessionId);
        session.addMessage(ChatMessage.userMessage(content));
    }

    @Override
    public void addAssistantMessage(String sessionId, String content) {
        ConversationSession session = getSession(sessionId);
        session.addMessage(ChatMessage.assistantMessage(content));
    }

    @Override
    public List<ChatMessage> getHistory(String sessionId) {
        ConversationSession session = getSession(sessionId);
        return session.getMessages();
    }

    @Override
    public void clearHistory(String sessionId) {
        ConversationSession session = getSession(sessionId);
        session.getMessages().clear();
    }

    @Override
    public String buildPrompt(String sessionId) {
        ConversationSession session = getSession(sessionId);
        StringBuilder prompt = new StringBuilder();

        // 添加启用的技能内容
        Set<String> enabledSkills = session.getEnabledSkillIds();
        for (String skillId : enabledSkills) {
            try {
                var skill = skillService.getSkill(skillId);
                if (skill != null && skill.getContent() != null) {
                    prompt.append("# Skill: ").append(skill.getName()).append("\n\n");
                    prompt.append(skill.getContent()).append("\n\n");
                }
            } catch (Exception e) {
                // 技能不存在时忽略
            }
        }

        // 添加消息历史
        for (ChatMessage message : session.getMessages()) {
            prompt.append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }

        return prompt.toString();
    }

    @Override
    public void enableSkill(String sessionId, String skillId) {
        ConversationSession session = getSession(sessionId);
        session.enableSkill(skillId);
    }

    @Override
    public void disableSkill(String sessionId, String skillId) {
        ConversationSession session = getSession(sessionId);
        session.disableSkill(skillId);
    }

    @Override
    public Set<String> getEnabledSkillIds(String sessionId) {
        ConversationSession session = getSession(sessionId);
        return session.getEnabledSkillIds();
    }
}
```

- [ ] **步骤 2：创建 SkillServiceImpl**

```java
package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能管理服务实现
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class SkillServiceImpl implements SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillServiceImpl.class);

    /**
     * 技能存储
     */
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    @Override
    public List<Skill> listSkills() {
        return new ArrayList<>(skills.values().stream()
                .map(Skill::toMetadata)
                .toList());
    }

    @Override
    public Skill getSkill(String skillId) {
        Skill skill = skills.get(skillId);
        if (skill == null) {
            throw new SkillNotFoundException(skillId);
        }
        return skill;
    }

    @Override
    public void registerSkill(Skill skill) {
        skills.put(skill.getId(), skill);
        log.info("Registered skill: {}", skill.getId());
    }

    @Override
    public void unregisterSkill(String skillId) {
        Skill removed = skills.remove(skillId);
        if (removed != null) {
            log.info("Unregistered skill: {}", skillId);
        }
    }

    @Override
    public void loadSkillsFromDirectory(String directory) {
        try {
            Path skillDir = Path.of(directory);
            if (!Files.exists(skillDir)) {
                log.warn("Skill directory not found: {}", directory);
                return;
            }

            Files.list(skillDir)
                    .filter(Files::isDirectory)
                    .forEach(this::loadSkillFromPath);

            log.info("Loaded {} skills from {}", skills.size(), directory);
        } catch (IOException e) {
            log.error("Failed to load skills from directory: {}", directory, e);
        }
    }

    /**
     * 从路径加载单个技能
     */
    private void loadSkillFromPath(Path skillPath) {
        Path skillFile = skillPath.resolve("SKILL.md");
        if (!Files.exists(skillFile)) {
            return;
        }

        try {
            String content = Files.readString(skillFile);
            String skillId = skillPath.getFileName().toString();
            Skill skill = parseSkillMd(skillId, content);
            if (skill != null) {
                skills.put(skill.getId(), skill);
                log.debug("Loaded skill: {} from {}", skill.getId(), skillPath);
            }
        } catch (IOException e) {
            log.error("Failed to load skill from: {}", skillPath, e);
        }
    }

    /**
     * 解析 SKILL.md 内容
     */
    private Skill parseSkillMd(String skillId, String content) {
        // 提取标题作为 name（第一行的 # 标题）
        String name = skillId;
        Pattern titlePattern = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
        Matcher titleMatcher = titlePattern.matcher(content);
        if (titleMatcher.find()) {
            name = titleMatcher.group(1).trim();
        }

        // 提取描述（标题后的第一段非空内容）
        String description = "";
        Pattern descPattern = Pattern.compile("^#\\s+.+\\n+([^#]+)", Pattern.MULTILINE);
        Matcher descMatcher = descPattern.matcher(content);
        if (descMatcher.find()) {
            description = descMatcher.group(1).trim().split("\n")[0];
        }

        return new Skill(skillId, name, description, content);
    }
}
```

- [ ] **步骤 3：创建 SandboxServiceImpl**

```java
package com.example.sandbox.web.service.impl;

import com.example.sandbox.agent.SandboxAgent;
import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.model.entity.ExecutionResult;
import com.example.sandbox.web.service.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙盒操作服务实现
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class SandboxServiceImpl implements SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxServiceImpl.class);

    /**
     * 沙盒实例存储（sessionId -> SandboxAgent）
     */
    private final Map<String, SandboxAgent> sandboxAgents = new ConcurrentHashMap<>();

    /**
     * 会话到沙盒 ID 的映射
     */
    private final Map<String, String> sessionSandboxMap = new ConcurrentHashMap<>();

    /**
     * 注册沙盒实例到会话
     */
    public void registerSandbox(String sessionId, SandboxAgent agent) {
        sandboxAgents.put(agent.getSandboxId(), agent);
        sessionSandboxMap.put(sessionId, agent.getSandboxId());
        log.info("Registered sandbox {} for session {}", agent.getSandboxId(), sessionId);
    }

    /**
     * 获取会话关联的沙盒
     */
    public SandboxAgent getSandbox(String sessionId) {
        String sandboxId = sessionSandboxMap.get(sessionId);
        if (sandboxId == null) {
            throw new SessionNotFoundException("No sandbox for session: " + sessionId);
        }
        return sandboxAgents.get(sandboxId);
    }

    /**
     * 移除会话的沙盒
     */
    public void removeSandbox(String sessionId) {
        String sandboxId = sessionSandboxMap.remove(sessionId);
        if (sandboxId != null) {
            SandboxAgent agent = sandboxAgents.remove(sandboxId);
            if (agent != null) {
                try {
                    agent.close();
                    log.info("Closed sandbox {} for session {}", sandboxId, sessionId);
                } catch (Exception e) {
                    log.error("Failed to close sandbox for session: {}", sessionId, e);
                }
            }
        }
    }

    @Override
    public ExecutionResult executeCommand(String sessionId, String command) {
        SandboxAgent agent = getSandbox(sessionId);
        Instant start = Instant.now();

        try {
            SandboxAgent.CommandResult result = agent.executeCommand(command);
            Duration duration = Duration.between(start, Instant.now());

            if (result.isSuccess()) {
                return ExecutionResult.success(result.getStdout(), duration);
            } else {
                return ExecutionResult.error("Exit code: " + result.getExitCode(), duration);
            }
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.error(e.getMessage(), duration);
        }
    }

    @Override
    public ExecutionResult readFile(String sessionId, String path) {
        SandboxAgent agent = getSandbox(sessionId);
        Instant start = Instant.now();

        try {
            String content = agent.readFile(path);
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.success(content, duration);
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.error(e.getMessage(), duration);
        }
    }

    @Override
    public ExecutionResult writeFile(String sessionId, String path, String content) {
        SandboxAgent agent = getSandbox(sessionId);
        Instant start = Instant.now();

        try {
            agent.writeFile(path, content);
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.success("File written: " + path, duration);
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            return ExecutionResult.error(e.getMessage(), duration);
        }
    }
}
```

- [ ] **步骤 4：创建 AgentServiceImpl**

```java
package com.example.sandbox.web.service.impl;

import com.example.sandbox.agent.SandboxAgent;
import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Agent 编排服务实现
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private final ConversationServiceImpl conversationService;
    private final SandboxServiceImpl sandboxService;
    private final SkillService skillService;
    private final AgentConfigProperties configProperties;

    public AgentServiceImpl(ConversationServiceImpl conversationService,
                           SandboxServiceImpl sandboxService,
                           SkillService skillService,
                           AgentConfigProperties configProperties) {
        this.conversationService = conversationService;
        this.sandboxService = sandboxService;
        this.skillService = skillService;
        this.configProperties = configProperties;
    }

    @Override
    public ConversationSession createSession() {
        // 创建会话
        ConversationSession session = conversationService.createSession();

        // 创建沙盒实例
        try {
            SandboxAgent agent = SandboxAgent.builder()
                    .domain(configProperties.getSandbox().getDomain())
                    .image(configProperties.getSandbox().getImage())
                    .timeout(Duration.parse(configProperties.getSandbox().getTimeout()))
                    .readyTimeout(Duration.parse(configProperties.getSandbox().getReadyTimeout()))
                    .debug(false)
                    .build();

            // 注册沙盒到会话
            sandboxService.registerSandbox(session.getSessionId(), agent);
            session.setSandboxId(agent.getSandboxId());

            log.info("Created session {} with sandbox {}", session.getSessionId(), agent.getSandboxId());
        } catch (Exception e) {
            log.error("Failed to create sandbox for session: {}", session.getSessionId(), e);
            // 沙盒创建失败时仍然返回会话，但 sandboxId 为空
        }

        return session;
    }

    @Override
    public void closeSession(String sessionId) {
        // 移除沙盒
        sandboxService.removeSandbox(sessionId);

        // 删除会话
        conversationService.deleteSession(sessionId);

        log.info("Closed session {}", sessionId);
    }

    @Override
    public ConversationSession getSession(String sessionId) {
        return conversationService.getSession(sessionId);
    }

    @Override
    public ChatMessage chat(String sessionId, String userMessage) {
        // 1. 存储用户消息
        conversationService.addUserMessage(sessionId, userMessage);

        // 2. 构建上下文（包含技能和消息历史）
        String prompt = conversationService.buildPrompt(sessionId);

        // 3. 获取启用的技能
        List<String> enabledSkills = conversationService.getEnabledSkillIds(sessionId).stream().toList();
        log.debug("Session {} enabled skills: {}", sessionId, enabledSkills);

        // 4. 调用 LLM（TODO: 待集成）
        String assistantResponse = callLlm(prompt);

        // 5. 存储助手响应
        conversationService.addAssistantMessage(sessionId, assistantResponse);

        return ChatMessage.assistantMessage(assistantResponse);
    }

    /**
     * 调用 LLM（TODO: 待实现）
     */
    private String callLlm(String prompt) {
        // TODO: 集成 LLM API
        // 当前返回占位响应
        return "[TODO] LLM integration pending. Prompt length: " + prompt.length();
    }
}
```

---

## 任务 12：创建配置类

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/config/AgentConfigProperties.java`
- 创建：`src/main/java/com/example/sandbox/web/config/SkillLoader.java`
- 创建：`src/main/java/com/example/sandbox/web/config/GlobalExceptionHandler.java`

- [ ] **步骤 1：创建 AgentConfigProperties**

```java
package com.example.sandbox.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 配置属性
 *
 * @author example
 * @date 2026/05/14
 */
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentConfigProperties {

    private Sandbox sandbox = new Sandbox();
    private Skill skill = new Skill();
    private Llm llm = new Llm();

    public Sandbox getSandbox() {
        return sandbox;
    }

    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public static class Sandbox {
        private String domain = "localhost:8080";
        private String image = "sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.2";
        private String timeout = "PT30M";
        private String readyTimeout = "PT120S";

        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public String getTimeout() { return timeout; }
        public void setTimeout(String timeout) { this.timeout = timeout; }
        public String getReadyTimeout() { return readyTimeout; }
        public void setReadyTimeout(String readyTimeout) { this.readyTimeout = readyTimeout; }
    }

    public static class Skill {
        private String directory = ".claude/skills";

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
    }

    public static class Llm {
        private String apiUrl = "";
        private String apiKey = "";

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}
```

- [ ] **步骤 2：创建 SkillLoader**

```java
package com.example.sandbox.web.config;

import com.example.sandbox.web.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 技能初始化加载器
 *
 * @author example
 * @date 2026/05/14
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final SkillService skillService;
    private final AgentConfigProperties configProperties;

    public SkillLoader(SkillService skillService, AgentConfigProperties configProperties) {
        this.skillService = skillService;
        this.configProperties = configProperties;
    }

    /**
     * 应用启动完成后加载技能
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String skillDirectory = configProperties.getSkill().getDirectory();
        log.info("Loading skills from: {}", skillDirectory);
        skillService.loadSkillsFromDirectory(skillDirectory);
    }
}
```

- [ ] **步骤 3：创建 GlobalExceptionHandler**

```java
package com.example.sandbox.web.config;

import com.example.sandbox.web.exception.SessionNotFoundException;
import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.model.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 *
 * @author example
 * @date 2026/05/14
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleSessionNotFound(SessionNotFoundException e) {
        log.warn("Session not found: {}", e.getSessionId());
        return ApiResponse.notFound(e.getMessage());
    }

    @ExceptionHandler(SkillNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleSkillNotFound(SkillNotFoundException e) {
        log.warn("Skill not found: {}", e.getSkillId());
        return ApiResponse.notFound(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneric(Exception e) {
        log.error("Unexpected error", e);
        return ApiResponse.error(500, "Internal server error: " + e.getMessage());
    }
}
```

---

## 任务 13：创建 Controller

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/controller/AgentController.java`
- 创建：`src/main/java/com/example/sandbox/web/controller/SandboxController.java`
- 创建：`src/main/java/com/example/sandbox/web/controller/SkillController.java`

- [ ] **步骤 1：创建 AgentController**

```java
package com.example.sandbox.web.controller;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.ConversationSession;
import com.example.sandbox.web.model.request.ChatRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.model.response.SessionResponse;
import com.example.sandbox.web.service.AgentService;
import com.example.sandbox.web.service.ConversationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 会话及对话 API
 *
 * @author example
 * @date 2026/05/14
 */
@RestController
@RequestMapping("/api/sessions")
public class AgentController {

    private final AgentService agentService;
    private final ConversationService conversationService;

    public AgentController(AgentService agentService, ConversationService conversationService) {
        this.agentService = agentService;
        this.conversationService = conversationService;
    }

    /**
     * 创建会话
     */
    @PostMapping
    public ApiResponse<SessionResponse> createSession() {
        ConversationSession session = agentService.createSession();
        return ApiResponse.success(toSessionResponse(session));
    }

    /**
     * 关闭会话
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> closeSession(@PathVariable String id) {
        agentService.closeSession(id);
        return ApiResponse.success();
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/{id}")
    public ApiResponse<SessionResponse> getSession(@PathVariable String id) {
        ConversationSession session = agentService.getSession(id);
        return ApiResponse.success(toSessionResponse(session));
    }

    /**
     * 发送消息
     */
    @PostMapping("/{id}/chat")
    public ApiResponse<ChatMessage> chat(@PathVariable String id, @RequestBody ChatRequest request) {
        ChatMessage response = agentService.chat(id, request.getMessage());
        return ApiResponse.success(response);
    }

    /**
     * 获取历史消息
     */
    @GetMapping("/{id}/history")
    public ApiResponse<List<ChatMessage>> getHistory(@PathVariable String id) {
        List<ChatMessage> history = conversationService.getHistory(id);
        return ApiResponse.success(history);
    }

    /**
     * 获取启用的技能
     */
    @GetMapping("/{id}/skills")
    public ApiResponse<Set<String>> getEnabledSkills(@PathVariable String id) {
        Set<String> skills = conversationService.getEnabledSkillIds(id);
        return ApiResponse.success(skills);
    }

    /**
     * 启用技能
     */
    @PostMapping("/{id}/skills/{skillId}/enable")
    public ApiResponse<Void> enableSkill(@PathVariable String id, @PathVariable String skillId) {
        conversationService.enableSkill(id, skillId);
        return ApiResponse.success();
    }

    /**
     * 禁用技能
     */
    @PostMapping("/{id}/skills/{skillId}/disable")
    public ApiResponse<Void> disableSkill(@PathVariable String id, @PathVariable String skillId) {
        conversationService.disableSkill(id, skillId);
        return ApiResponse.success();
    }

    private SessionResponse toSessionResponse(ConversationSession session) {
        SessionResponse response = new SessionResponse();
        response.setSessionId(session.getSessionId());
        response.setSandboxId(session.getSandboxId());
        response.setEnabledSkillIds(session.getEnabledSkillIds());
        response.setCreatedAt(session.getCreatedAt());
        response.setUpdatedAt(session.getUpdatedAt());
        return response;
    }
}
```

- [ ] **步骤 2：创建 SandboxController**

```java
package com.example.sandbox.web.controller;

import com.example.sandbox.web.model.entity.ExecutionResult;
import com.example.sandbox.web.model.request.ExecuteRequest;
import com.example.sandbox.web.model.request.FileWriteRequest;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.SandboxService;
import org.springframework.web.bind.annotation.*;

/**
 * 沙盒操作 API
 *
 * @author example
 * @date 2026/05/14
 */
@RestController
@RequestMapping("/api/sessions")
public class SandboxController {

    private final SandboxService sandboxService;

    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    /**
     * 执行命令
     */
    @PostMapping("/{id}/execute")
    public ApiResponse<ExecutionResult> executeCommand(
            @PathVariable String id,
            @RequestBody ExecuteRequest request) {
        ExecutionResult result = sandboxService.executeCommand(id, request.getCommand());
        return ApiResponse.success(result);
    }

    /**
     * 读取文件
     */
    @PostMapping("/{id}/files/read")
    public ApiResponse<ExecutionResult> readFile(
            @PathVariable String id,
            @RequestBody FileWriteRequest request) {
        ExecutionResult result = sandboxService.readFile(id, request.getPath());
        return ApiResponse.success(result);
    }

    /**
     * 写入文件
     */
    @PostMapping("/{id}/files/write")
    public ApiResponse<ExecutionResult> writeFile(
            @PathVariable String id,
            @RequestBody FileWriteRequest request) {
        ExecutionResult result = sandboxService.writeFile(id, request.getPath(), request.getContent());
        return ApiResponse.success(result);
    }
}
```

- [ ] **步骤 3：创建 SkillController**

```java
package com.example.sandbox.web.controller;

import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.response.ApiResponse;
import com.example.sandbox.web.service.SkillService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 技能管理 API
 *
 * @author example
 * @date 2026/05/14
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 列出所有技能
     */
    @GetMapping
    public ApiResponse<List<Skill>> listSkills() {
        List<Skill> skills = skillService.listSkills();
        return ApiResponse.success(skills);
    }

    /**
     * 获取技能详情
     */
    @GetMapping("/{id}")
    public ApiResponse<Skill> getSkill(@PathVariable String id) {
        Skill skill = skillService.getSkill(id);
        return ApiResponse.success(skill);
    }

    /**
     * 注册新技能
     */
    @PostMapping
    public ApiResponse<Void> registerSkill(@RequestBody Skill skill) {
        skillService.registerSkill(skill);
        return ApiResponse.success();
    }

    /**
     * 注销技能
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> unregisterSkill(@PathVariable String id) {
        skillService.unregisterSkill(id);
        return ApiResponse.success();
    }
}
```

---

## 任务 14：创建配置文件

**文件：**
- 创建：`src/main/resources/application.yml`

- [ ] **步骤 1：创建 application.yml**

```yaml
server:
  port: 8081

spring:
  application:
    name: sandbox-agent

agent:
  sandbox:
    domain: ${SANDBOX_DOMAIN:localhost:8080}
    image: ${SANDBOX_IMAGE:sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.2}
    timeout: PT30M
    ready-timeout: PT120S
  skill:
    directory: ${SKILL_DIR:.claude/skills}
  llm:
    api-url: ${LLM_API_URL:}
    api-key: ${LLM_API_KEY:}

logging:
  level:
    com.example.sandbox: DEBUG
```

---

## 任务 15：创建 Spring Boot 启动类

**文件：**
- 创建：`src/main/java/com/example/sandbox/web/SandboxAgentApplication.java`

- [ ] **步骤 1：创建启动类**

```java
package com.example.sandbox.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sandbox Agent 应用启动类
 *
 * @author example
 * @date 2026/05/14
 */
@SpringBootApplication
public class SandboxAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxAgentApplication.class, args);
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`cd D:/sandbox/sandbox && mvn clean compile`
预期：BUILD SUCCESS

---

## 任务 16：验证启动

- [ ] **步骤 1：启动应用**

运行：`cd D:/sandbox/sandbox && mvn spring-boot:run`
预期：应用启动成功，端口 8081

- [ ] **步骤 2：测试创建会话 API**

运行：`curl -X POST http://localhost:8081/api/sessions`
预期：返回 sessionId 和 sandboxId

- [ ] **步骤 3：测试列出技能 API**

运行：`curl http://localhost:8081/api/skills`
预期：返回已加载的技能列表

---

## 自检结果

**1. 规格覆盖度：** ✅
- 核心模型：ChatMessage, ConversationSession, Skill, ExecutionResult ✅
- 服务接口：AgentService, SandboxService, SkillService, ConversationService ✅
- Controller：AgentController, SandboxController, SkillController ✅
- 配置：application.yml, AgentConfigProperties, SkillLoader ✅

**2. 占位符扫描：** ✅
- 所有代码步骤都有完整代码
- `callLlm` 方法标记 TODO 但有占位实现

**3. 类型一致性：** ✅
- 所有服务实现与接口定义一致
- Controller 使用正确的服务接口
