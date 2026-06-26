# AIO Sandbox API Integration Map

This document maps the checked-in OpenAPI snapshot to the domain-oriented
Java clients under:

`src/main/java/com/example/sandbox/aio`

`AioClient` is the aggregate entry point. HTTP transport is centralized in
`core/AioHttpClient`; endpoint wrappers are split by AIO API group.

## Implemented Endpoints

| Java API | AIO endpoint | Notes |
|---|---|---|
| `AioSandboxApi.getContext` | `GET /v1/sandbox` | Reads the top-level sandbox context |
| `AioSandboxApi.getNodePackages` | `GET /v1/sandbox/packages/nodejs` | Returns the response envelope |
| `AioShellApi.exec` | `POST /v1/shell/exec` | Required `command`; optional reusable session `id` |
| `AioShellApi.exec(command, id, timeout)` | `POST /v1/shell/exec` | Sends `timeout` and does not update the reusable shell session |
| `AioShellApi.execAsync` | `POST /v1/shell/exec` | Sends `async_mode=true` for long-running processes such as MCP supergateway |
| `AioShellApi.view` | `POST /v1/shell/view` | Required `id` |
| `AioShellApi.waitFor` | `POST /v1/shell/wait` | Required `id`; optional `seconds` |
| `AioShellApi.kill` | `POST /v1/shell/kill` | Required `id` |
| `AioShellApi.quickHealthCheck` | `GET /v1/shell/sessions` | Lightweight readiness probe |
| `AioFileApi.readText` | `POST /v1/file/read` | Required `file` |
| `AioFileApi.writeText`, `writeBytes` | `POST /v1/file/write` | Binary content uses Base64 encoding |
| `AioFileApi.upload` | `POST /v1/file/upload` | Multipart fields `file` and `path` |
| `AioFileApi.download` | `GET /v1/file/download` | Query parameter `path`; binary response |
| `AioFileApi.replace` | `POST /v1/file/replace` | Required `file`, `old_str`, and `new_str` |
| `AioFileApi.search` | `POST /v1/file/search` | Required `file` and `regex` |
| `AioFileApi.edit` | `POST /v1/file/str_replace_editor` | Required `command` and `path` |
| `AioFileApi.list` | `POST /v1/file/list` | Required `path`; supports recursion and sorting |
| `AioBrowserApi.getInfo` | `GET /v1/browser/info` | Typed access to `cdp_url`, VNC, and viewport data |
| `AioBrowserApi.screenshot` | `GET /v1/browser/screenshot` | PNG binary response |
| `AioBrowserApi.action`, `navigate` | `POST /v1/browser/actions` | Uses OpenAPI action fields such as `dx`, `dy`, and `duration` |
| `AioBrowserApi.setResolution` | `POST /v1/browser/config` | Required `resolution` |
| `AioNodeApi.execute` | `POST /v1/nodejs/execute` | Executes JavaScript in the AIO Node.js runtime |
| `AioNodeApi.getInfo` | `GET /v1/nodejs/info` | Returns runtime information |
| `AioUtilityApi.convertToMarkdown` | `POST /v1/util/convert_to_markdown` | Required `uri` |
| `AioMcpApi.listServers` | `GET /v1/mcp/servers` | Returns configured MCP Server names |
| `AioMcpApi.listTools` | `GET /v1/mcp/{server_name}/tools` | Reads MCP tool metadata and input JSON Schema |
| `AioMcpApi.callTool` | `POST /v1/mcp/{server_name}/tools/{tool_name}` | Sends raw MCP tool arguments as the JSON request body |

## Browser Agent Tools

| Tool | Responsibility |
|---|---|
| `browser_info` | Reports browser readiness, User-Agent, and viewport without exposing CDP |
| `browser_inspect` | Runs the fixed semantic page snapshot script |
| `browser_execute` | Runs a guarded Playwright async function body with a prebound `page` |
| `browser_screenshot` | Captures the current viewport for visual verification |
| `browser_action` | Executes one coordinate, keyboard, scroll, or wait fallback action |

The intended model workflow is
`browser_inspect -> browser_execute -> browser_inspect/browser_screenshot`.
`BrowserAgentRuntimeService` invokes the fixed Node.js wrapper without injecting
the host-mapped `browser/info.data.cdp_url`. The sandbox runtime reads
`http://127.0.0.1:9222/json/version` and uses its `webSocketDebuggerUrl`, which is
reachable from the Node.js process running inside the sandbox.

## Compatibility Notes

- `AioClient` implements the small generic `SandboxClient` contract for legacy
  call sites, but new AIO integrations must call a domain API explicitly.
- Some diagnostic and flexible tool responses remain generic `Map` values.
  Before introducing typed models, inspect the referenced response schemas in
  `openapi.json`.
- `AioHttpClient` has a 16 MB in-memory WebClient codec limit and a default 120
  second response timeout. These are project constraints, not API defaults.
- The AIO endpoint is associated with a user sandbox and may change when the
  sandbox is rebuilt.
- Browser Agent resources and `playwright-core` are installed only during new
  sandbox initialization under `/home/gem/.runtime/browser-agent`. This hidden
  runtime directory is not part of the user workspace or workspace restore.

## Available But Not Yet Wrapped

The checked-in specification also exposes APIs not currently wrapped directly
by the domain clients, including:

- `/v1/file/find`
- `/v1/shell/write` and explicit shell session creation/cleanup
- `/v1/jupyter/*`
- `/v1/code/*`
- `/v1/skills/*`

When adding one of these capabilities, use the OpenAPI operation and referenced
schema rather than inferring request fields from endpoint names.
