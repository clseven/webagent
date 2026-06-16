# AIO Client Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the monolithic AIO REST client into focused domain clients, migrate every current caller, and install the hidden Browser Agent runtime for newly created sandboxes.

**Architecture:** A shared HTTP transport handles WebClient configuration, envelopes, binary bodies, and errors. Domain clients mirror the checked-in OpenAPI contract. `AioClient` aggregates them, while lifecycle services and tools consume only the relevant domain API.

**Tech Stack:** Java 17, Spring WebClient, Jackson, Maven, AIO Sandbox REST API, Node.js 22, playwright-core.

---

### Task 1: Shared transport and aggregate client

**Files:**
- Create: `src/main/java/com/example/sandbox/aio/core/AioHttpClient.java`
- Create: `src/main/java/com/example/sandbox/aio/core/AioResponse.java`
- Create: `src/main/java/com/example/sandbox/aio/core/AioApiException.java`
- Create: `src/main/java/com/example/sandbox/aio/AioClient.java`

- [ ] Implement WebClient construction, JSON envelope decoding, raw JSON access, binary GET support, multipart upload support, and consistent HTTP errors.
- [ ] Add the aggregate client with one accessor per AIO API domain.

### Task 2: Sandbox and Shell APIs

**Files:**
- Create: `src/main/java/com/example/sandbox/aio/sandbox/AioSandboxApi.java`
- Create: `src/main/java/com/example/sandbox/aio/sandbox/model/AioSandboxContext.java`
- Create: `src/main/java/com/example/sandbox/aio/shell/AioShellApi.java`
- Create: `src/main/java/com/example/sandbox/aio/shell/model/ShellExecRequest.java`
- Create: `src/main/java/com/example/sandbox/aio/shell/model/ShellExecResult.java`

- [ ] Move readiness, context, execution, view, wait, kill, and session reuse behavior into the new domain APIs.
- [ ] Preserve current timeout and output normalization behavior.

### Task 3: File, Browser, Node, and Utility APIs

**Files:**
- Create: `src/main/java/com/example/sandbox/aio/file/AioFileApi.java`
- Create: `src/main/java/com/example/sandbox/aio/browser/AioBrowserApi.java`
- Create: `src/main/java/com/example/sandbox/aio/browser/model/BrowserInfo.java`
- Create: `src/main/java/com/example/sandbox/aio/node/AioNodeApi.java`
- Create: `src/main/java/com/example/sandbox/aio/node/model/NodeExecuteResult.java`
- Create: `src/main/java/com/example/sandbox/aio/utility/AioUtilityApi.java`

- [ ] Move every currently used file operation and binary transfer.
- [ ] Implement browser actions according to OpenAPI field names and units.
- [ ] Add typed browser info, browser config, and Node.js execution.
- [ ] Move Markdown conversion into the utility API.

### Task 4: Migrate lifecycle and business callers

**Files:**
- Modify: `src/main/java/com/example/sandbox/web/service/impl/SandboxClientFactory.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/AioSandboxStore.java`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java`
- Modify: all controllers, services, and tools importing `AioSandboxClient`

- [ ] Change factories and stores to return `AioClient`.
- [ ] Replace direct monolithic calls with the matching domain API.
- [ ] Keep current business behavior and user-facing result text unchanged where possible.

### Task 5: Install Browser Agent runtime

**Files:**
- Create: `src/main/resources/tools/browser-agent/browser-agent.mjs`
- Create: `src/main/resources/tools/browser-agent/package.json`
- Modify: `src/main/java/com/example/sandbox/web/service/impl/SandboxServiceImpl.java`

- [ ] Create `/home/gem/.runtime/browser-agent` for new sandboxes.
- [ ] Upload the fixed driver script and package manifest.
- [ ] Run `npm install --omit=dev --no-audit --no-fund` in that directory.
- [ ] Verify `playwright-core` can load without preventing sandbox creation on failure.

### Task 6: Remove the monolith and update project knowledge

**Files:**
- Delete: `src/main/java/com/example/sandbox/aio/AioSandboxClient.java`
- Modify: `docs/sandbox-api/integration-map.md`
- Modify: affected Obsidian project knowledge pages

- [ ] Confirm no production source imports or references the deleted client.
- [ ] Document the new API mapping and Browser Agent runtime boundary.
- [ ] Do not run compilation or tests at the user's request; report the work as unverified.
