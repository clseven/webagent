# AIO Sandbox API Integration Map

This document maps the checked-in OpenAPI snapshot to the current Java client:

`src/main/java/com/example/sandbox/aio/AioSandboxClient.java`

## Implemented Endpoints

| Client behavior | AIO endpoint | Notes |
|---|---|---|
| `downloadFile` | `GET /v1/file/download` | Query parameter `path`; binary response |
| `screenshot` | `GET /v1/browser/screenshot` | PNG binary response |
| `writeFile(byte[])` | `POST /v1/file/write` | Sends Base64 content with `encoding=base64` |
| `uploadFile` | `POST /v1/file/upload` | Multipart fields `file` and `path` |
| `navigate`, `browserAction` | `POST /v1/browser/actions` | Uses typed keyboard, mouse, and wait actions |
| `browserInfo` | `GET /v1/browser/info` | Returns browser state in the response envelope |
| sandbox context lookup | `GET /v1/sandbox` | Reads home directory and workspace context |
| readiness checks | `GET /v1/shell/sessions` | Used as an AIO health probe |
| `shellExec` | `POST /v1/shell/exec` | Required field `command`; optional session `id` |
| `shellView` | `POST /v1/shell/view` | Required field `id` |
| `shellWait` | `POST /v1/shell/wait` | Required `id`; optional `seconds` |
| `shellKill` | `POST /v1/shell/kill` | Required field `id` |
| `fileReplace` | `POST /v1/file/replace` | Required `file`, `old_str`, and `new_str` |
| `fileSearch` | `POST /v1/file/search` | Required `file` and `regex` |
| `strReplaceEditor` | `POST /v1/file/str_replace_editor` | Required `command` and `path` |
| `listFiles` | `POST /v1/file/list` | Required `path`; supports recursion and sorting |
| `convertToMarkdown` | `POST /v1/util/convert_to_markdown` | Required field `uri` |

## Compatibility Notes

- `readFile(String)` currently executes `cat` through `/v1/shell/exec` instead
  of calling `/v1/file/read`.
- `writeFile(String, String)` currently writes through a Base64 shell command.
  Only the byte-array overload calls `/v1/file/write`.
- Client responses are often deserialized into generic `Map` values. Before
  replacing these with typed models, inspect the referenced response schemas in
  `openapi.json`.
- The client has a 16 MB in-memory WebClient codec limit and a default 120
  second response timeout. These are project constraints, not API defaults.
- The AIO endpoint is associated with a user sandbox and may change when the
  sandbox is rebuilt.

## Available But Not Yet Wrapped

The checked-in specification also exposes APIs not currently wrapped directly
by `AioSandboxClient`, including:

- `/v1/file/read` and `/v1/file/find`
- `/v1/shell/write` and explicit shell session creation/cleanup
- `/v1/jupyter/*`
- `/v1/nodejs/*`
- `/v1/code/*`
- `/v1/mcp/*`
- `/v1/skills/*`
- `/v1/browser/config`

When adding one of these capabilities, use the OpenAPI operation and referenced
schema rather than inferring request fields from endpoint names.
