# DeepSeek Agnes Vision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split model roles so DeepSeek plans and executes while Agnes only performs `view_image` visual observation.

**Architecture:** Add a `visionLlm` role beside the existing executor role. `ViewImageTool` continues to load image bytes, while `ReactAgentHookService` calls `visionLlm` and injects a text observation for the DeepSeek-driven main Agent.

**Tech Stack:** Java 17, Spring Boot 3.2.5, JUnit 5, OpenAI-compatible LLM request format.

## Global Constraints

- 所有新增或修改的代码注释、Javadoc 和脚本注释必须使用中文。
- 修改前后保持 `view_image` 工具名称和参数协议不变。
- DeepSeek 专有 `thinking` 参数只能用于执行器模型，不能用于 Agnes 视觉模型。
- TodoWrite 与步骤反思机制不在本计划实现。

---

## File Structure

- Modify `src/main/java/com/example/sandbox/web/config/AgentConfigProperties.java`: add `agent.llm.vision` configuration.
- Create `src/main/java/com/example/sandbox/web/service/impl/VisionLlmServiceImpl.java`: register `visionLlm` using Agnes-compatible generic requests.
- Modify `src/main/java/com/example/sandbox/web/service/impl/DeepSeekLlmServiceImpl.java`: make DeepSeek the `executorLlm` bean again.
- Modify `src/main/java/com/example/sandbox/web/service/impl/GenericLlmServiceImpl.java`: remove the `executorLlm` bean role or convert it away from execution.
- Modify `src/main/java/com/example/sandbox/web/service/impl/ReactAgentHookService.java`: call `visionLlm` after `view_image`.
- Modify `src/main/resources/application.yml`: default executor to DeepSeek and vision to Agnes.
- Modify `docs/project-spec.md`: add ADR for DeepSeek execution plus Agnes vision.
- Test `src/test/java/com/example/sandbox/web/config/AgentConfigPropertiesVisionTest.java`: configuration defaults.
- Test `src/test/java/com/example/sandbox/web/service/impl/ReactAgentHookServiceVisionTest.java`: `view_image` hook injects Agnes text observation.

---

### Task 1: Add Vision Configuration and Bean

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/config/AgentConfigProperties.java`
- Create: `src/main/java/com/example/sandbox/web/service/impl/VisionLlmServiceImpl.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/example/sandbox/web/config/AgentConfigPropertiesVisionTest.java`

**Interfaces:**
- Produces: `AgentConfigProperties.Llm.Vision`
- Produces: Spring bean `@Service("visionLlm")`

- [ ] **Step 1: Write the failing configuration test**

Run target test before implementation:

```powershell
mvn -q -Dtest=AgentConfigPropertiesVisionTest test
```

Expected: compilation fails because `getVision()` does not exist.

- [ ] **Step 2: Implement minimal configuration and bean**

Add `Vision` nested config with `apiUrl`, `apiKey`, and `model`; add `VisionLlmServiceImpl` using those values.

- [ ] **Step 3: Verify test passes**

```powershell
mvn -q -Dtest=AgentConfigPropertiesVisionTest test
```

Expected: PASS.

---

### Task 2: Make DeepSeek the Executor and Agnes the Vision Model

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/impl/DeepSeekLlmServiceImpl.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/GenericLlmServiceImpl.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: `executorLlm`
- Produces: exactly one Spring bean named `executorLlm`, backed by DeepSeek.

- [ ] **Step 1: Write a failing context test if bean ambiguity is not already caught by existing compile tests**

Run:

```powershell
mvn -q -Dtest=AgentConfigPropertiesVisionTest test
```

Expected before implementation: either the new test passes but executor defaults are still Agnes, or context startup would have two `executorLlm` candidates once DeepSeek is enabled.

- [ ] **Step 2: Register DeepSeek as `executorLlm` and make generic implementation non-conflicting**

Enable `@Service("executorLlm")` on `DeepSeekLlmServiceImpl`. Remove `@Service("executorLlm")` from `GenericLlmServiceImpl` so it no longer conflicts.

- [ ] **Step 3: Verify focused tests pass**

```powershell
mvn -q -Dtest=AgentConfigPropertiesVisionTest test
```

Expected: PASS.

---

### Task 3: Route view_image Through visionLlm

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/impl/ReactAgentHookService.java`
- Test: `src/test/java/com/example/sandbox/web/service/impl/ReactAgentHookServiceVisionTest.java`

**Interfaces:**
- Consumes: `@Qualifier("visionLlm") LlmService`
- Produces: `ChatMessage` containing Agnes text observation after `view_image`

- [ ] **Step 1: Write failing hook test**

The test stores image bytes in `ImageBuffer`, invokes the post-tool hook for `view_image`, and asserts that the returned message contains the fake vision observation text instead of image content parts.

Run:

```powershell
mvn -q -Dtest=ReactAgentHookServiceVisionTest test
```

Expected: FAIL because current hook returns a multimodal image message and does not call `visionLlm`.

- [ ] **Step 2: Implement minimal hook change**

Inject `visionLlm`, call it with the visual observer prompt and `ChatMessage.userMessageWithImage(...)`, and return a text-only `ChatMessage.userMessage(...)` containing the image path plus observation.

- [ ] **Step 3: Verify hook test passes**

```powershell
mvn -q -Dtest=ReactAgentHookServiceVisionTest test
```

Expected: PASS.

---

### Task 4: Update ADR and Run Verification

**Files:**
- Modify: `docs/project-spec.md`

**Interfaces:**
- Produces: ADR documenting DeepSeek as planner/executor role and Agnes as `visionLlm`.

- [ ] **Step 1: Update ADR**

Revise the old Agnes executor decision or add a new ADR superseding it.

- [ ] **Step 2: Run focused verification**

```powershell
mvn -q -Dtest=AgentConfigPropertiesVisionTest,ReactAgentHookServiceVisionTest,DeepSeekLlmServiceErrorHandlingTest test
```

Expected: PASS.

- [ ] **Step 3: Run broader verification if focused tests pass**

```powershell
mvn -q test
```

Expected: PASS, unless unrelated pre-existing tests fail; document any such failure with exact output.
