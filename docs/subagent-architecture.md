# 子智能体架构设计

> 状态：**设计阶段**
> 创建日期：2026-06-05
> 参考：Claude Code 逆向分析
> 配套文档：[PLAN_SSE.md](../PLAN_SSE.md)、[rag-retrieval-enhancement.md](./rag-retrieval-enhancement.md)

---

## 一、背景与目标

### 1.1 当前问题

现有 ReactAgent 是单一智能体架构，存在以下问题：

| 问题 | 说明 |
|------|------|
| **上下文污染** | 工具调用的原始结果直接进入主上下文，导致上下文快速膨胀 |
| **无法并行** | 串行执行所有任务，��率低 |
| **无角色分工** | 所有任务由同一个智能体处理，无法针对性优化 |

### 1.2 目标

借鉴 Claude Code 的设计，实现子智能体架构：

```
主智能体：规划 + 协调 + 汇总（上下文干净）
    │
    └── 子智能体：独立上下文执行任务 → 只返回结构化摘要
```

**核心收益**：
- 主上下文只包含摘要，不包含工具调用的原始数据
- 子智能体可并行执行，提升效率
- 不同子智能体可针对性优化系统提示词

---

## 二、核心设计

### 2.1 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                      MainAgent                           │
│                                                          │
│  职责：                                                   │
│  - 接收用户消息                                           │
│  - 规划：拆解任务，决定启动哪些子智能体                      │
│  - 协调：并行启动子智能体                                  │
│  - 汇总：收集子智能体的结构化摘要                          │
│  - 决策：生成最终答案或启动下一轮子智能体                   │
│                                                          │
│  上下文：                                                 │
│  - 用户消息                                               │
│  - 子智能体返回的结构化摘要                                │
│  - 最终答案                                               │
│                                                          │
│  不包含：工具调用的原始输出                                │
└─────────────────────────────────────────────────────────┘
                           │
                           │ 启动（传递任务指令）
                           ▼
┌─────────────────────────────────────────────────────────┐
│                    SubAgentFactory                       │
│                                                          │
│  - 根据类型创建子智能体实例                                │
│  - 注入独立的消息列表（sub_messages）                      │
│  - 注入受限的工具列表                                     │
│  - 注入专属系统提示词                                     │
└─────────────────────────────────────────────────────────┘
                           │
         ┌─────────┬───────┼───────┬─────────┐
         ▼         ▼       ▼       ▼         ▼
    ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
    │ Explore │ │ Search  │ │ Execute │ │  Plan   │
    │  Agent  │ │  Agent  │ │  Agent  │ │  Agent  │
    └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘
         │           │           │           │
         ▼           ▼           ▼           ▼
    ┌─────────────────────────────────────────────────────┐
    │                 独立的 sub_messages                   │
    │  - 专属系统提示词（专家角色）                          │
    │  - 任务指令（来自主智能体）                            │
    │  - 工具调用记录（不返回给主智能体）                     │
    │  - 执行过程中的中间输出（不返回给主智能体）             │
    └─────────────────────────────────────────────────────┘
                           │
                           │ 执行完成
                           ▼
                   ┌───────────────┐
                   │ 结构化摘要输出 │
                   │ (直接可用)     │
                   └───────────────┘
                           │
                           │ 返回主智能体
                           ▼
                主上下文只增加一份摘要
```

### 2.2 子智能体的上下文

**关键设计：子智能体启动时是"一张白纸"**

| 上下文组成 | 来源 |
|------------|------|
| 独立系统提示词 | 子智能体专属，定义角色和输出格式 |
| 任务指令 | 主智能体传递，描述具体任务 |
| 工具列表 | 受限的工具权限 |
| 执行记录 | 子智能体自己调用工具产生，**不返回给主智能体** |

**不继承**：
- 主会话的历史对话
- 主会话的工具调用记录
- 主会话的中间状态

### 2.3 摘要生成机制

**核心原则：在执行过程中按结构化格式输出，而不是执行完再生成摘要**

```
传统方式（不采用）：
子智能体执行 → 产生大量输出 → 再调 LLM 生成摘要 → 返回

Claude Code 方式（采用）：
子智能体执行 → 系统提示词规定输出格式 → 直接产生结构化摘要 → 返回
```

**示例系统提示词**：

```markdown
# 代码探索专家

你的任务是探索代码库，找出相关代码。

## 输出格式

执行完成后，请按以下格式输出：

## 发现摘要

### 相关文件
- `path/to/file.py`: 简要说明
- `path/to/another.py`: 简要说明

### 关键发现
1. 发现点一
2. 发现点二

### 建议
- 建议一
- 建议二

注意：不要输出完整的代码内容，只输出摘要。
```

---

## 三、子智能体类型

### 3.1 适用场景分析

本项目的核心使用场景是**执行工具 + 执行 Skill**，而非改代码库：

| 用户请求 | 需要的工具 | 是否需要子智能体 |
|----------|------------|------------------|
| 分析日志文件 | read_file + 分析 | 日志大时需要 |
| 文档转换 | read_file + write_file | 不需要，顺序执行 |
| 检索知识库 | knowledge_search | 不需要，单一工具 |
| 分析日志 + 检索文档 + 生成报告 | 多个工具组合 | **需要并行** |
| 执行一个复杂 Skill | Skill 内部调用多个工具 | **需要隔离** |

### 3.2 何时需要子智能体？

| 场景 | 是否需要 | 原因 |
|------|----------|------|
| 简单的单工具调用 | ❌ 不需要 | 直接执行即可 |
| 顺序依赖明确的任务 | ❌ 不需要 | 主智能体直接执行 |
| **任务可以并行** | ✅ 需要 | 多个子智能体同时执行 |
| **任务复杂、工具调用多** | ✅ 需要 | 子智能体独立上下文，不污染主会话 |
| **执行复杂 Skill** | ✅ 需要 | Skill 内部逻辑封装在子智能体里 |

### 3.3 内置子智能体

| 类型 | 职责 | 可用工具 | 典型场景 |
|------|------|----------|----------|
| **AnalyzerAgent** | 分析类任务（读文件 + 分析） | read_file, execute_command | 分析日志、分析数据、分析代码 |
| **SearcherAgent** | 检索知识库 | knowledge_search | 查文档、查规范 |
| **SkillExecutorAgent** | 执行 Skill | 继承主智能体工具 | 执行复杂 Skill，隔离上下文 |
| **BrowserAgent** | 浏览器操作 | browser_action, browser_screenshot | 网页抓取、自动化测试 |

### 3.4 PlanAgent 的定位（现有组件）

**PlanAgent 不是一个独立的子智能体，而是主智能体的规划能力。**

```
用户消息进入主智能体
    │
    ▼
PlanAgent 分析任务，产出执行计划（可选步骤）
    │
    ▼
主智能体根据计划决定：
    - 直接执行工具
    - 或委托子智能体
```

**原因**：
- PlanAgent 不需要独立上下文窗口
- PlanAgent 的输出（执行计划）直接指导主智能体后续执行
- PlanAgent 是轻量级的规划步骤，不是完整的执行单元

**现有 PlanAgent 的整合方式**：

```java
public class MainAgent {

    private final PlanAgent planAgent;  // 复用现有组件
    private final SubAgentFactory subAgentFactory;

    public AgentResponse process(String userMessage) {
        // 1. 规划（可选，复杂任务时启用）
        String plan = planAgent.plan(userMessage);

        // 2. 根据计划决定执行策略
        // ...
    }
}
```

### 3.5 各子智能体详细设计

#### AnalyzerAgent

```markdown
# 系统提示词

你是数据分析专家。你的任务是：
1. 读取和分析指定的文件或数据
2. 提取关键信息
3. 按规定格式返回分析摘要

## 可用工具
- read_file: 读取文件内容
- execute_command: 执行命令（如 grep、awk 等分析工具）
- list_files: 列出目录结构

## 输出格式

## 分析摘要

### 数据概况
- 文件/数据来源：xxx
- 数据规模：xxx 行/字符
- 数据类型：日志/配置/代码/...

### 关键发现
1. 发现点一（简要描述，带具体数值或位置）
2. 发现点二（简要描述）

### 问题列表（如有）
- 🔴 严重问题：xxx（位置：第 N 行）
- 🟡 警告：xxx（位置：第 M 行）

### 建议
- 后续可执行的操作建议

注意：
- 不要输出完整的原始数据
- 只输出分析结论和关键信息
- 输出保持在 500 字以内
```

#### SearchAgent

```markdown
# 系统提示词

你是知识库检索专家。你的任务是：
1. 根据用户问题，检索知识库中的相关内容
2. 筛选和整理检索结果
3. 返回与问题最相关的摘要

## 可用工具
- knowledge_search: 检索知识库

## 输出格式

## 检索摘要

### 问题相关度分析
- 问题核心：xxx
- 检索关键词：xxx

### 相关内容
1. **来源**: 文档名称
   **内容**: 相关内容摘要（不超过 200 字）
   **相关性**: 高/中/低

2. ...

### 综合答案
基于检索结果，对问题的回答摘要。

注意：
- 只返回与问题高度相关的内容
- 去除冗余信息
- 标注来源便于追溯
```

#### SkillExecutorAgent

```markdown
# 系统提示词

你是 Skill 执行专家。

## 你的任务
执行指定的 Skill，完成用户请求。

## 执行规则
1. 按 Skill 指导调用工具
2. 遵循 Skill 中定义的工作流程
3. 输出执行结果摘要

## 输出格式

## 执行摘要

### 完成内容
- 完成的操作 1
- 完成的操作 2
- ...

### 关键结果
[精炼的结果，不是原始输出]

### 产出文件（如有）
- `路径/文件名`: 简要说明

### 后续建议（如有）
- 用户可继续执行的操作

注意：
- Skill 内容已注入上下文
- 不要输出工具调用的原始数据
- 只输出用户关心的结果
```

#### BrowserAgent

```markdown
# 系统提示词

你是浏览器操作专家。你的任务是：
1. 执行浏览器自动化操作
2. 抓取网页内容或截图
3. 返回操作结果摘要

## 可用工具
- browser_action: 浏览器操作（导航、点击、输入等）
- browser_screenshot: 截图
- browser_info: 获取页面信息

## 输出格式

## 操作摘要

### 执行操作
- 访问页面：xxx
- 执行动作：点击/输入/滚动...
- 等待条件：xxx

### 页面信息
- 当前 URL：xxx
- 页面标题：xxx
- 关键元素：xxx

### 截图（如有）
[截图已保存，路径：xxx]

### 抓取内容（如有）
[关键内容摘要，不超过 500 字]

注意：
- 截图和原始 HTML 不直接输出
- 只输出关键信息摘要
```

---

## 四、触发机制

### 4.1 主智能体的两种工作模式

```
┌─────────────────────────────────────────────────────────┐
│                      主智能体                            │
│                                                          │
│  判断任务复杂度：                                         │
│  - 简单（单一工具调用）→ 直接执行                         │
│  - 复杂（多工具/并行/Skill）→ 委托子智能体                │
└─────────────────────────────────────────────────────────┘
```

### 4.2 直接执行模式

适用于：简单的单工具调用

```
用户：帮我读一下 /home/gem/workspace/app.log 的内容

主智能体判断：单一工具调用，直接执行
    │
    ▼
调用 read_file 工具
    │
    ▼
工具结果直接返回给用户
```

### 4.3 委托执行模式

适用于：
- 任务复杂，需要多次工具调用
- 任务可以并行执行
- 执行复杂的 Skill

**触发方式：主智能体输出特定标记**

```
用户：帮我分析日志找出错误，同时查一下知识库里有没有相关的解决方案

主智能体思考：
- 任务 1：分析日志（需要读文件 + 分析）
- 任务 2：检索知识库
- 两个任务可以并行

主智能体输出：
<delegate>
{"agent": "analyzer", "task": "读取 /home/gem/workspace/app.log，分析错误原因"}
{"agent": "searcher", "task": "检索知识库，找相关解决方案"}
</delegate>
    │
    ▼
框架捕获 → 并行启动两个子智能体
    │
    ▼
收集摘要 → 主智能体汇总
```

### 4.4 主智能体系统提示词

```markdown
# 主智能体

## 工作模式

你有两种工作模式：

### 模式 1：直接执行
适用于：简单的单工具调用，如读取文件、检索知识库、执行单个命令。
直接调用工具，结果返回给用户。

### 模式 2：委托执行
适用于：
- 任务复杂，需要多次工具调用
- 任务可以并行执行
- 执行复杂的 Skill

委托方式：输出特定标记
<delegate>
{"agent": "子智能体类型", "task": "任务描述", "skill": "Skill ID（可选）"}
</delegate>

## 可用子智能体

| 子智能体 | 用途 | 适用场景 |
|----------|------|----------|
| analyzer | 分析文件/数据 | 分析日志、分析代码、数据分析 |
| searcher | 检索知识库 | 查文档、查规范 |
| skill_executor | 执行 Skill | 执行复杂 Skill |
| browser | 浏览器操作 | 网页抓取、自动化测试 |

## 使用规则

1. **并行调用**：当多个子智能体任务相互独立时，可以同时委托
2. **串行调用**：当后续任务依赖前一个结果时，依次委托
3. **只返回摘要**：子智能体只返回摘要，不会返回原始数据

## 示例

用户：帮我分析日志找出错误，同时查一下知识库里有没有相关的解决方案

助手判断：需要分析日志 + 检索知识库，可以并行

助手输出：
<delegate>
{"agent": "analyzer", "task": "读取 /home/gem/workspace/app.log，分析错误原因"}
{"agent": "searcher", "task": "检索知识库，找相关解决方案"}
</delegate>
```

---

## 五、执行流程

### 5.1 整体流程

```
用户消息进入
    │
    ▼
┌─────────────────────────────────────┐
│ 主智能体处理                         │
│                                     │
│ 1. 分析任务复杂度                    │
│    - 简单 → 直接执行工具             │
│    - 复杂 → 输出 <delegate> 标记    │
│                                     │
│ 2. 框架检测 <delegate>              │
│    - 无 → 返回结果                   │
│    - 有 → 解析并启动子智能体         │
│                                     │
│ 3. 并行执行子智能体                  │
│    - 各自独立上下文                  │
│    - 只返回摘要                      │
│                                     │
│ 4. 收集摘要到主上下文                │
│                                     │
│ 5. 继续调用 LLM                      │
│    - 信息足够 → 生成最终答案         │
│    - 信息不足 → 继续委托             │
└─────────────────────────────────────┘
```

### 5.2 并行执行示意

```
用户：分析日志找出错误 + 检索知识库找解决方案

T0: 主智能体输出 <delegate> 标记
    │
    ├─────────────────────────────────────────────┐
    │ AnalyzerAgent                    SearchAgent │
    │ 独立上下文                       独立上下文  │
    │ ├─ read_file                    ├─ search   │
    │ ├─ 分析错误                     └─ 汇总     │
    │ └─ 汇总                                      │
    │                                              │
T3: ◄───────── 返回摘要 ─────────────►────────────┘
    │
T3: 主智能体收集两个摘要
T4: 主智能体生成最终答案返回用户
```

### 5.3 Skill 执行示意

```
用户：用 brainstorming skill 帮我头脑风暴一下产品设计

主智能体判断：需要执行复杂 Skill，委托
    │
    ▼
<delegate>
{"agent": "skill_executor", "task": "执行头脑风暴", "skill": "brainstorming"}
</delegate>
    │
    ▼
SkillExecutorAgent 启动
    │
    ├─ 加载 Skill 内容
    ├─ 按 Skill 指导调用工具
    ├─ 可能调用多次工具（read_file, write_file, execute_command...）
    │
    └─ 返回执行摘要（不是所有工具调用的原始数据）
    │
    ▼
主智能体收到摘要，返回用户
```

---

## 六、接口设计

### 6.1 核心接口

```java
/**
 * 子智能体接口
 */
public interface SubAgent {

    /**
     * 获取子智能体类型
     */
    SubAgentType getType();

    /**
     * 获取系统提示词
     */
    String getSystemPrompt();

    /**
     * 获取可用工具列表
     */
    List<Tool> getAvailableTools();

    /**
     * 执行任务
     * @param task 任务指令
     * @param context 执行上下文
     * @return 结构化摘要
     */
    SubAgentResult execute(String task, SubAgentContext context);
}

/**
 * 子智能体类型枚举
 */
public enum SubAgentType {
    ANALYZER,        // 分析文件/数据
    SEARCHER,        // 检索知识库
    SKILL_EXECUTOR,  // 执行 Skill
    BROWSER          // 浏览器操作
}

/**
 * 子智能体执行上下文
 */
public record SubAgentContext(
    String sessionId,              // 会话 ID
    String userOriginalQuestion,   // 用户原始问题（可选）
    String skillId,                // Skill ID（仅 SKILL_EXECUTOR 使用）
    List<String> hints             // 额外提示
) {}

/**
 * 子智能体执行结果
 */
public record SubAgentResult(
    String summary,                // 结构化摘要（直接进入主上下文）
    boolean success,               // 是否成功
    String error,                  // 错误信息（如有）
    Map<String, Object> metadata   // 元数据（文件列表、token 用量等）
) {}

/**
 * 子智能体任务定义（从 <delegate> 标记解析）
 */
public record SubAgentTask(
    SubAgentType type,             // 子智能体类型
    String instruction,            // 任务指令
    String skillId,                // Skill ID（可选）
    SubAgentContext context        // 执行上下文
) {}
```

### 6.2 委托标记解析

```java
/**
 * 委托标记解析器
 *
 * 从主智能体输出中提取 <delegate> 标记
 */
public class DelegateParser {

    private static final Pattern DELEGATE_PATTERN =
        Pattern.compile("<delegate>([\\s\\S]*?)</delegate>");

    /**
     * 解析委托标记
     *
     * <p><strong>降级策略</strong>：如果解析失败（JSON 格式错误、未知的 agent 类型），
     * 不抛异常，返回空列表，让主智能体按"无委托"路径继续执行。</p>
     */
    public List<SubAgentTask> parse(String llmOutput) {
        if (llmOutput == null || !llmOutput.contains("<delegate>")) {
            return List.of();
        }

        List<SubAgentTask> tasks = new ArrayList<>();
        Matcher matcher = DELEGATE_PATTERN.matcher(llmOutput);

        while (matcher.find()) {
            String block = matcher.group(1).trim();

            // 每个 <delegate> 块可能包含多个 JSON 对象（每行一个）
            for (String line : block.split("\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    DelegateRequest request = objectMapper.readValue(line, DelegateRequest.class);
                    SubAgentType type = safeParseType(request.agent());
                    if (type == null) {
                        log.warn("未知的子智能体类型: {}，跳过该任务", request.agent());
                        continue;
                    }
                    tasks.add(new SubAgentTask(
                        type,
                        request.task(),
                        request.skill(),
                        new SubAgentContext(null, null, request.skill(), null)
                    ));
                } catch (JsonProcessingException e) {
                    log.warn("解析委托标记失败: {}，降级为无委托", line, e);
                    return List.of();  // ← 整块解析失败时降级，不影响后续流程
                }
            }
        }

        return tasks;
    }

    private SubAgentType safeParseType(String name) {
        if (name == null) return null;
        try {
            return SubAgentType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 委托请求 DTO
     */
    record DelegateRequest(
        String agent,   // 子智能体类型：analyzer / searcher / skill_executor / browser
        String task,    // 任务指令
        String skill    // Skill ID（可选）
    ) {}
}
```

### 6.3 子智能体工厂

```java
/**
 * 子智能体工厂
 */
@Component
public class SubAgentFactory {

    private final LlmService llmService;
    private final SubAgentConfigProperties configs;
    private final Map<String, Tool> allTools;
    private final SkillService skillService;  // 用于 SKILL_EXECUTOR 注入 Skill 内容

    /**
     * 创建子智能体实例
     */
    public SubAgent create(SubAgentType type, String skillId) {
        SubAgentConfig config = configs.getAgents().get(type.name().toLowerCase());
        if (config == null || !config.isEnabled()) {
            throw new IllegalStateException("子智能体未启用: " + type);
        }

        // 加载系统提示词（从配置文件指定的资源文件）
        String systemPrompt = loadPrompt(config.getSystemPromptFile());

        // SKILL_EXECUTOR 特殊处理：追加 Skill 内容
        if (type == SubAgentType.SKILL_EXECUTOR && skillId != null) {
            String skillContent = skillService.getSkill(skillId).getContent();
            systemPrompt = systemPrompt + "\n\n## Skill 内容\n\n" + skillContent;
        }

        // 过滤工具（SKILL_EXECUTOR 继承主智能体工具）
        List<Tool> tools = "inherit".equals(config.getAllowedTools())
            ? new ArrayList<>(allTools.values())  // 继承全部
            : filterTools(config.getAllowedTools());

        return new GenericSubAgent(
            type,
            systemPrompt,
            tools,
            llmService,
            config.getMaxIterations()
        );
    }

    private List<Tool> filterTools(List<String> allowedToolNames) {
        return allowedToolNames.stream()
            .map(allTools::get)
            .filter(Objects::nonNull)
            .toList();
    }

    private String loadPrompt(String path) {
        try {
            Resource resource = new ClassPathResource(path);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载子智能体提示词: " + path, e);
        }
    }
}
```

### 6.4 通用子智能体实现

```java
/**
 * 通用子智能体实现
 *
 * 复用 ReactAgent 的循环机制，区别在于：
 * 1. 独立的消息列表（sub_messages）
 * 2. 受限的工具列表
 * 3. 专属的系统提示词
 * 4. 不写入主会话历史（避免污染）
 */
public class GenericSubAgent implements SubAgent {

    private final SubAgentType type;
    private final String systemPrompt;
    private final List<Tool> tools;
    private final LlmService llmService;
    private final int maxIterations;

    @Override
    public SubAgentResult execute(String task, SubAgentContext context) {
        // 1. 构造任务消息（任务指令作为唯一上下文）
        List<ChatMessage> subMessages = List.of(ChatMessage.userMessage(task));

        // 2. 创建临时 ReactAgent
        //    - 不传 conversationService 和 sessionId（子智能体不写主会话）
        //    - 工具列表已受限
        ReactAgent agent = new ReactAgent(
            llmService,
            tools,
            systemPrompt,
            null,  // 无执行计划
            null,  // 不保存到主会话（关键：避免污染）
            null   // 无会话 ID
        );

        try {
            // 3. 执行（同步版本，TODO: 流式版本见第八节）
            AgentResponse response = agent.run(null, task, List.of());

            return new SubAgentResult(
                response.getFinalAnswer(),
                true,
                null,
                Map.of(
                    "iterations", response.getIterations(),
                    "type", type.name()
                )
            );
        } catch (Exception e) {
            log.error("子智能体执行失败: {}", type, e);
            return new SubAgentResult(
                "子智能体 " + type + " 执行失败：" + e.getMessage(),
                false,
                e.getMessage(),
                Map.of("type", type.name())
            );
        }
    }
}
```

> ⚠️ **重要**：当前 `ReactAgent.run()` 接收 `sessionId` 用于工具执行时获取沙箱客户端。
> 子智能体需要 sessionId 才能执行工具（read_file 需要 sessionId 找到沙箱）。
> **修正方案**：subMessages 仍传 `List.of()`，但 sessionId 应从 context 传入：
> ```java
> String sessionId = context != null ? context.sessionId() : null;
> AgentResponse response = agent.run(sessionId, task, List.of());
> ```
> 这样工具能正常调用，但不写主会话历史。

### 6.5 主智能体实现

```java
/**
 * 主智能体
 *
 * 替代现有的 AgentServiceImpl 中"调用 ReactAgent"的循环部分。
 * 不破坏现有 chat() / chatStream() 入口（迁移策略见 8.4）。
 */
public class MainAgent {

    /** 最大委托层数（防止无限递归） */
    private static final int MAX_DELEGATION_DEPTH = 3;

    /** 单个子智能体的超时时间（毫秒） */
    private static final long SUB_AGENT_TIMEOUT_MS = 60_000;

    private final LlmService llmService;
    private final SubAgentFactory subAgentFactory;
    private final DelegateParser delegateParser;
    private final PlanAgent planAgent;
    private final List<ChatMessage> mainMessages;
    private final List<ToolDefinition> toolDefinitions;

    public AgentResponse process(String userMessage) {
        return processRecursive(userMessage, 0);
    }

    /**
     * 递归处理（带深度限制）
     */
    private AgentResponse processRecursive(String userMessage, int depth) {
        if (depth >= MAX_DELEGATION_DEPTH) {
            log.warn("达到最大委托深度 {}，停止委托", MAX_DELEGATION_DEPTH);
            return generateFinalAnswer();
        }

        // 1. 添加用户消息到主上下文
        mainMessages.add(ChatMessage.userMessage(userMessage));

        // 2. 调用 LLM
        String llmOutput = llmService.chatWithTools(
            buildSystemPrompt(),
            mainMessages,
            toolDefinitions
        ).getContent();

        // 3. 检测是否有委托标记
        List<SubAgentTask> tasks = delegateParser.parse(llmOutput);

        if (!tasks.isEmpty()) {
            // 4. 有委托 → 并行执行子智能体
            return executeDelegatedTasks(userMessage, tasks, depth);
        }

        // 5. 无委托 → 直接返回（可能是工具调用或最终答案）
        if (hasToolCall(llmOutput)) {
            return handleToolCall(llmOutput);
        }

        // 6. 最终答案
        mainMessages.add(ChatMessage.assistantMessage(llmOutput));
        return new AgentResponse(llmOutput, null, null, null, depth);
    }

    /**
     * 执行委托的子智能体任务
     *
     * <p>关键设计：</p>
     * <ul>
     *   <li>使用 CompletableFuture 实现真正并行 + 超时控制</li>
     *   <li>单点失败不影响其他任务（每个子智能体独立 try-catch）</li>
     *   <li>收集到的摘要作为 user 消息追加到主上下文</li>
     * </ul>
     */
    private AgentResponse executeDelegatedTasks(String userMessage, List<SubAgentTask> tasks, int depth) {
        // 1. 并行执行（带超时）
        List<CompletableFuture<SubAgentResult>> futures = tasks.stream()
            .map(task -> CompletableFuture.supplyAsync(() -> {
                try {
                    SubAgent agent = subAgentFactory.create(task.type(), task.skillId());
                    return agent.execute(task.instruction(), task.context());
                } catch (Exception e) {
                    log.error("子智能体执行异常: {}", task.type(), e);
                    return new SubAgentResult(
                        "子智能体 " + task.type() + " 执行异常：" + e.getMessage(),
                        false,
                        e.getMessage(),
                        Map.of()
                    );
                }
            }))
            .toList();

        // 2. 等待所有完成（带超时）
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
        try {
            allDone.get(SUB_AGENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("子智能体执行超时（{}ms），取消未完成的任务", SUB_AGENT_TIMEOUT_MS);
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            log.error("子智能体执行协调失败", e);
        }

        // 3. 收集摘要到主上下文（只追加成功的）
        for (int i = 0; i < futures.size(); i++) {
            SubAgentResult result = futures.get(i).getNow(
                new SubAgentResult("子智能体未返回结果", false, "timeout", Map.of())
            );
            SubAgentTask task = tasks.get(i);

            String report = String.format(
                "[子智能体 %s 报告]\n%s",
                task.type().name().toLowerCase(),
                result.success() ? result.summary() : "❌ " + result.error()
            );
            mainMessages.add(ChatMessage.userMessage(report));
        }

        // 4. 继续调用 LLM（深度+1）
        return processRecursive(userMessage, depth + 1);
    }
}
```

### 6.6 和 SSE 流式的协同（待解决问题）

> ⚠️ **本节是设计草案，需要和 P0-2a SSE 实施时协同验证。**

子智能体的流式输出问题：
- 当前 `ReactAgent.runStream()` 是 SSE 的核心
- 子智能体如果用 `runStream`，**流式事件怎么传给主智能体？**
- 主智能体怎么把子智能体的进度**透传给前端**？

**方案 A（推荐）**：子智能体用同步 `run()`，不流式
- 主智能体的 SSE 流是顶层 ReactAgent 的流
- 子智能体作为"工具调用"嵌入主流程，前端看不到子智能体的 token 进度，只看到最终摘要
- 优点：实现简单，不破坏现有 SSE 架构
- 缺点：长任务时前端无反馈

**方案 B**：子智能体也流式，转发到主 SSE
- 子智能体的 token ���事件经过包装，**作为嵌套事件**转发给主 emitter
- 前端能感知"现在 AnalyzerAgent 在思考"
- 优点：完整体验
- 缺点：实现复杂，需要修改 SSE 事件结构（增加 `subAgentType` 字段）

**当前决策**：先实现**方案 A**，方案 B 作为 P0-2b 之后的后续优化。

---

## 七、配置设计

### 7.1 子智能体配置文件

```yaml
# application.yml
agent:
  sub-agents:
    analyzer:
      enabled: true
      system-prompt-file: classpath:prompts/analyzer-agent.md
      allowed-tools:
        - read_file
        - list_files
        - file_search
        - execute_command
      max-iterations: 10

    searcher:
      enabled: true
      system-prompt-file: classpath:prompts/searcher-agent.md
      allowed-tools:
        - knowledge_search
      max-iterations: 5

    skill_executor:
      enabled: true
      system-prompt-file: classpath:prompts/skill-executor-agent.md
      # 工具列表继承自主智能体
      allowed-tools: inherit
      max-iterations: 15

    browser:
      enabled: true
      system-prompt-file: classpath:prompts/browser-agent.md
      allowed-tools:
        - browser_action
        - browser_screenshot
        - browser_info
      max-iterations: 10
```

### 7.2 配置属性类

```java
@ConfigurationProperties(prefix = "agent.sub-agents")
public class SubAgentConfigProperties {

    private Map<String, SubAgentConfig> agents = new HashMap<>();

    public static class SubAgentConfig {
        private boolean enabled = true;
        private String systemPromptFile;
        private List<String> allowedTools = new ArrayList<>();
        private int maxIterations = 10;
        // getters, setters
    }
}
```

---

## 八、与现有代码的关系

### 8.1 代码复用

| 现有组件 | 改造方式 |
|----------|----------|
| `ReactAgent` | 保留，作为子智能体的执行引擎 |
| `ReactAgent.runStream` | 暂不使用（方案 A 决策），但保留供 P0-2b 后续优化 |
| `PlanAgent` | 保留，作为主智能体的规划步骤（不改为子智能体） |
| `AgentServiceImpl` | 改造为 `MainAgent` 的协调逻辑（**保留旧 `chat()` 方法作为后备**） |
| `SkillService` | 复用，为 `SkillExecutorAgent` 提供 Skill 内容 |
| 工具类 | 不变，由子智能体工厂按配置分配 |

### 8.2 架构整合示意

```
┌─────────────────────────────────────────────────────────┐
│                    AgentServiceImpl                      │
│                       (入口层)                           │
│   保留 chat() / chatStream() 方法，内部委托 MainAgent    │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                      MainAgent                           │
│                     (协调层)                             │
│                                                          │
│  ┌─────────────┐                                        │
│  │  PlanAgent  │  ← 现有组件，作为规划步骤               │
│  └─────────────┘                                        │
│         │                                                │
│         ▼                                                │
│  ┌─────────────────────────────────────────┐            │
│  │ 检测 <delegate> 标记                     │            │
│  │ - 无 → 直接执行                          │            │
│  │ - 有 → 启动子智能体                       │            │
│  └─────────────────────────────────────────┘            │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                   SubAgentFactory                        │
│                    (工厂层)                              │
└────────────────────────┬────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
   AnalyzerAgent    SearcherAgent   SkillExecutorAgent
         │               │               │
         └───────────────┴───────────────┘
                         │
                         ▼
               ┌─────────────────────┐
               │    ReactAgent       │  ← 复用，独立上下文
               │  (子智能体执行引擎)  │
               └─────────────────────┘
```

### 8.3 新增文件

```
src/main/java/com/example/sandbox/
├── agent/
│   ├── MainAgent.java              # 主智能体
│   ├── SubAgent.java               # 子智能体接口
│   ├── SubAgentType.java           # 子智能体类型枚举
│   ├── SubAgentResult.java         # 子智能体结果
│   ├── SubAgentContext.java        # 子智能体上下文
│   ├── SubAgentFactory.java        # 子智能体工厂
│   ├── GenericSubAgent.java        # 通用子智能体实现
│   ├── SubAgentTask.java           # 子智能体任务定义
│   └── DelegateParser.java         # 委托标记解析器
├── config/
│   └── SubAgentConfigProperties.java  # 配置属性
└── resources/
    └── prompts/
        ├── analyzer-agent.md       # AnalyzerAgent 系统提示词
        ├── searcher-agent.md       # SearchAgent 系统提示词
        ├── skill-executor-agent.md # SkillExecutorAgent 系统提示词
        └── browser-agent.md        # BrowserAgent 系统提示词
```

### 8.4 迁移策略（重要）

**渐进式迁移，避免一次性切换**：

1. **第一阶段**：实现 MainAgent 和 SubAgentFactory，但**通过 `agent.sub-agent.enabled` flag 控制**
   - flag = false：走旧的 ReactAgent 直连逻辑（保持现状）
   - flag = true：走 MainAgent 协调逻辑
2. **第二阶段**：小流量灰度（A/B 测试），对比 token 消耗和执行效果
3. **第三阶段**：全量切换，删除旧逻辑

**回滚方案**：如果 MainAgent 出问题，**修改 flag 即可秒回旧版本**。

### 8.5 修改文件

```
src/main/java/com/example/sandbox/
├── service/impl/
│   └── AgentServiceImpl.java       # chat() 和 chatStream() 内部分支调用 MainAgent 或旧 ReactAgent
└── resources/
    └── application.yml             # 新增子智能体配置
```

---

## 九、与 P1-1 RAG 检索增强的协同

### 9.1 关系说明

`SearcherAgent` 和 `KnowledgeEnhancer`（来自 `rag-retrieval-enhancement.md`）**不是两个独立工作**：

- **KnowledgeEnhancer** 是**检索能力增强**（Query Rewrite + Rerank）
- **SearcherAgent** 是**检索的执行者**（调用 knowledge_search 工具）

**推荐架构**：SearcherAgent 内部自动启用 KnowledgeEnhancer

```java
public class SearcherAgent implements SubAgent {

    private final KnowledgeEnhancer knowledgeEnhancer;  // ← 内置

    public SubAgentResult execute(String task, SubAgentContext context) {
        // 1. 增强查询（Rewrite + Rerank）
        EnhancedQuery enhanced = knowledgeEnhancer.enhance(task, context.userOriginalQuestion());

        // 2. 用增强后的 query 调用 knowledge_search
        // 3. 整理结果为摘要
        // ...
    }
}
```

### 9.2 实施顺序

1. **先做 P1-1 RAG 增强**（KnowledgeEnhancer + RerankService）
2. **再做 P1-2 子智能体**（SearcherAgent 调用 KnowledgeEnhancer）

这样 SearcherAgent 实施时，KnowledgeEnhancer 已经是稳定组件。

---

## 十、实施步骤

> **说明**：本节是**实施清单**，每步有明确的"验证标准"。AI 实施时按顺序执行，每步验证后再进入下一步。

### 阶段 A: 基础框架

- [ ] **A1. 定义核心接口和类型**
  - 新建 `SubAgent` 接口
  - 新建 `SubAgentType` 枚举（ANALYZER, SEARCHER, SKILL_EXECUTOR, BROWSER）
  - 新建 `SubAgentContext`, `SubAgentResult`, `SubAgentTask` record
  - **验证标准**：`mvn compile` 通过，所有类型在 IDE 中可见

- [ ] **A2. 实现 DelegateParser**
  - 解析 `<delegate>...</delegate>` 标记
  - **降级策略**：解析失败 → 返回空列表（不抛异常）
  - 支持多任务（一个 `<delegate>` 块内多个 JSON 对象，每行一个）
  - **验证标准**：单元测试覆盖正常解析、JSON 格式错误、未知 agent 类型、缺失 `<delegate>` 标签 4 种情况

- [ ] **A3. 实现 SubAgentFactory**
  - 从 `application.yml` 读取配置
  - 根据 type 创建对应 SubAgent
  - SKILL_EXECUTOR 特殊处理：注入 Skill 内容
  - 加载系统提示词（从 `systemPromptFile` 路径）
  - **验证标准**：单元测试覆盖 4 种 type 的创建逻辑

- [ ] **A4. 实现 GenericSubAgent**
  - 复用 ReactAgent 执行
  - **关键**：从 context.sessionId() 传入，但 conversationService 传 null（不写主会话）
  - 捕获异常，返回 SubAgentResult.failure(...)
  - **验证标准**：单元测试验证"不写入主会话历史"（可用 mock ConversationService 验证 addUserMessage 未被调用）

### 阶段 B: 第一个子智能体（端到端验证）

- [ ] **B1. 编写 AnalyzerAgent 系统提示词**
  - 新建 `resources/prompts/analyzer-agent.md`
  - 内容参考 3.5 节"AnalyzerAgent"

- [ ] **B2. application.yml 添加 analyzer 配置**
  - 复制 7.1 节的 analyzer 配置

- [ ] **B3. 端到端测试**
  - 临时在 MainAgent 中硬编码启用 analyzer
  - 发送一个分析日志的用户消息
  - **验证标准**：
    - 主智能体输出 `<delegate>` 标记
    - AnalyzerAgent 启动并执行
    - 返回结构化摘要
    - **主会话历史只包含摘要，不包含原始 read_file 输出**

### 阶段 C: MainAgent 核心

- [ ] **C1. 实现 MainAgent.processRecursive**
  - 实现 6.5 节的递归逻辑
  - **MAX_DELEGATION_DEPTH = 3**（防止无限递归）
  - **验证标准**：单元测试覆盖"达到最大深度时返回最终答案"

- [ ] **C2. 实现 executeDelegatedTasks**
  - 使用 `CompletableFuture.allOf(...).get(SUB_AGENT_TIMEOUT_MS)` 实现并行 + 超时
  - **SUB_AGENT_TIMEOUT_MS = 60_000**
  - 单点失败不影响其他（每个子智能体独立 try-catch）
  - **验证标准**：
    - 单元测试：1 个子智能体抛异常，其他正常完成
    - 单元测试：1 个子智能体超时，其他正常完成

- [ ] **C3. MainAgent 系统提示词**
  - 新建 MainAgent 系统提示词（参考 4.4 节）
  - 注入到 LLM 调用

- [ ] **C4. 整合 PlanAgent**
  - MainAgent 处理流程开头调用 `planAgent.plan(userMessage)`
  - 把 plan 注入到系统提示词

### 阶段 D: 其他 3 个子智能体

- [ ] **D1. SearcherAgent**
  - 提示词参考 3.5 节
  - **关联 P1-1**：先确认 KnowledgeEnhancer 已实现（见 9.2 节）
  - **验证标准**：能正确检索知识库并返回摘要

- [ ] **D2. SkillExecutorAgent**
  - 提示词参考 3.5 节
  - 验证 Skill 内容注入
  - **验证标准**：能执行 Skill 流程并返回摘要

- [ ] **D3. BrowserAgent**
  - 提示词参考 3.5 节
  - **验证标准**：能执行浏览器操作并返回摘要

### 阶段 E: 集成到 AgentServiceImpl

- [ ] **E1. 修改 AgentServiceImpl**
  - 在 `chat()` 和 `chatStream()` 内部增加分支：
    - `agent.sub-agent.enabled = false` → 走旧 ReactAgent 逻辑（保持现状）
    - `agent.sub-agent.enabled = true` → 走 MainAgent
  - **验证标准**：flag 切换时旧功能不受影响

- [ ] **E2. 端到端测试**
  - 简单任务：直接执行（不委托）
  - 复杂任务：委托子智能体
  - 并行执行：多个子智能体同时跑
  - Skill 执行：上下文隔离验证
  - **验证标准**：4 种场景全部通过

- [ ] **E3. 性能对比**
  - 对比改造前后的 token 消耗和响应时间
  - **验证标准**：有 baseline 数据 + 改造后数据，能看到明确差异

### 阶段 F: 风险缓解

- [ ] **F1. `<delegate>` 解析失败的降级**
  - 测试：LLM 输出 `<delegate>{"agent":"unknown","task":"x"}</delegate>`
  - 预期：log.warn + 跳过该任务，继续执行

- [ ] **F2. 递归保护**
  - 测试：让 LLM 一直输出 `<delegate>` 标记
  - 预期：第 3 次后停止委托，返回最终答案

- [ ] **F3. 子智能体超时**
  - 测试：AnalyzerAgent 工具调用卡住 70 秒
  - 预期：60 秒后取消，主智能体收到"超时"摘要继续

- [ ] **F4. Flag 切换回滚**
  - 测试：先用 flag=true 跑通，然后改 flag=false
  - 预期：行为立即回到旧版本

---

## 十一、预期收益

| 指标 | 改造前 | 改造后 |
|------|--------|--------|
| **主上下文大小** | 包含所有工具输出，快速膨胀 | 只有摘要，增长缓慢 |
| **执行效率** | 串行执行 | 可并行执行 |
| **Token 成本** | 高（重复内容多） | 低（摘要精简） |
| **可扩展性** | 难以添加新能力 | 新增子智能体即可 |

---

## 十二、风险与缓解

| 风险 | 严重度 | 缓解措施 |
|------|--------|----------|
| 架构改动大 | 高 | 渐进式迁移（flag 控制），先实现 AnalyzerAgent 验证效果 |
| 摘要丢失关键信息 | 中 | 优化系统提示词，明确输出格式要求 |
| 并行执行的协调复杂 | 中 | 使用 CompletableFuture.allOf + 超时控制 |
| 子智能体执行时间不可控 | 中 | 添加 60 秒超时，支持中断 |
| 无限递归（LLM 一直输出 delegate） | 中 | MAX_DELEGATION_DEPTH = 3 |
| `<delegate>` 解析失败 | 中 | 降级策略：解析失败 → 返回空列表，按无委托继续 |
| 单个子智能体失败 | 低 | 独立 try-catch，其他结果正常收集 |
| 和 P0-2 SSE 协同 | 待验证 | 先实现方案 A（同步执行子智能体），方案 B 作为后续优化 |
| 复用 ReactAgent 的隐性耦合 | 中 | sessionId 从 context 传入，conversationService 传 null |

---

## 十三、后续优化

完成基础架构后，可考虑：

1. **方案 B 流式透传**
   - 子智能体也用 runStream，转发到主 SSE
   - 前端能感知"现在 AnalyzerAgent 在思考"

2. **前缀缓存优化**
   - 确保子智能体系统提示词结构一致
   - 利用 API 的 Prompt Caching 降低成本

3. **子智能体嵌套**
   - 允许子智能体启动子智能体
   - 处理更复杂的任务链

4. **摘要质量监控**
   - 记录摘要的 token 数量
   - 对比子智能体上下文大小和摘要大小

5. **动态工具分配**
   - 根据任务动态调整子智能体的工具列表
   - 更精细的权限控制

---

## 附录 A: AI 实施指南

### A.1 实施前必读

AI 接到"实施子智能体架构"任务时，**第一步不是写代码**，而是确认：

- [ ] 读完了本文档全部章节
- [ ] 读了 [PLAN_SSE.md](../PLAN_SSE.md) 了解 SSE 现状
- [ ] 读了 [rag-retrieval-enhancement.md](./rag-retrieval-enhancement.md) 了解 RAG 增强（影响 P1-1 和 P1-2 的顺序）
- [ ] 读完了现有 `ReactAgent.java` 和 `AgentServiceImpl.java` 的全部方法
- [ ] 确认**未做 P1-1 RAG 增强**：如果先做本规划，`SearcherAgent` 应使用现有的 `knowledge_search` 工具（不带 Query Rewrite/Rerank），后续再升级

### A.2 实施顺序（推荐）

```
阶段 A（基础） → B（端到端验证 1 个子智能体） → C（MainAgent 核心）
                ↓
                停下来，让用户验证方案 A 决策（同步 vs 流式透传）
                ↓
阶段 D（其他 3 个） → E（集成到 AgentServiceImpl） → F（风险验证）
```

**为什么先 A → B → C → 暂停**：
- 阶段 A 写 5 个新类，独立可测
- 阶段 B 用 AnalyzerAgent 端到端验证（验证"独立上下文"、"摘要进入主上下文"两个核心机制）
- 阶段 C 写 MainAgent（这是核心逻辑）
- **此时暂停**：让用户决定"是否启用方案 B（流式透传）"，**避免做了一大堆又回滚**

### A.3 验证标准汇总

每个阶段完成后，AI 应运行验证：

| 阶段 | 验证方式 |
|------|----------|
| A 基础 | `mvn compile` + 单元测试 |
| B 端到端 | 启动应用，发一个分析日志的消息，手动验证主会话历史 |
| C MainAgent | 单元测试覆盖"递归深度"、"超时"、"异常隔离" |
| D 其他 3 个 | 单元测试 + 端到端 |
| E 集成 | flag 切换测试，4 种场景 |
| F 风险 | 4 个 F 子项的对抗测试 |

### A.4 实施时容易踩的坑

1. **sessionId 传递**：第 6.4 节有警告，`GenericSubAgent` 必须从 context 拿 sessionId 传给 ReactAgent.run()
2. **conversationService 必须传 null**：传非 null 会污染主会话历史
3. **`<delegate>` 解析的 JSON 格式**：每行一个对象，不是数组形式（参考第 4.3 节的示例）
4. **降级策略**：解析失败时**不要抛异常**，返回 `List.of()` 即可
5. **超时控制**：必须用 `CompletableFuture.allOf(...).get(timeout)`，不能用 `parallelStream().toList()`（没超时）
6. **flag 开关**：第一阶段必须保留旧 `chat()` 逻辑作为后备，flag 切换不能破坏现有功能

### A.5 验收清单（给用户/PM 看的）

实施完成后，用户应能验证：

- [ ] 发送"分析日志 + 查知识库"类消息，看到主智能体输出 `<delegate>` 标记
- [ ] 主会话历史中只有摘要，没有原始工具输出
- [ ] flag 关闭时行为和旧版本完全一致
- [ ] flag 开启时，递归委托最多 3 层
- [ ] 1 个子智能体抛异常不影响其他
- [ ] 1 个子智能体卡住 60 秒后被取消
- [ ] token 消耗比改造前明显降低（有数据对比）
