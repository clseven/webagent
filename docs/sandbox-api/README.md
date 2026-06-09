# AIO Sandbox API Reference

This directory records the REST API exposed by the AIO sandbox image.

## Files

- `openapi.json`: authoritative OpenAPI 3.1 specification exported by the
  running sandbox. Do not edit it manually.
- `integration-map.md`: mapping between the API and this project's
  `AioSandboxClient`.

## Snapshot

- Sandbox API version: `1.0.0.152`
- OpenAPI version: `3.1.0`
- Captured: `2026-06-10`
- Paths: `44`
- Schemas: `133`
- SHA-256:
  `88EBC1169D7359F391A30240DA7EE948144C0CE6A7C690759C4729FF60668006`

The snapshot was captured from a temporary AIO endpoint at
`http://localhost:41862/v1/openapi.json`. Sandbox ports are dynamic, so code
must use the endpoint returned by the OpenSandbox service instead of assuming
this port.

## API Groups

| Group | Prefix | Purpose |
|---|---|---|
| Sandbox | `/v1/sandbox` | Context and installed package information |
| Shell | `/v1/shell` | Commands, processes, and shell sessions |
| File | `/v1/file` | Read, write, upload, download, search, and edit files |
| Browser | `/v1/browser` | Browser state, screenshots, actions, and config |
| Jupyter | `/v1/jupyter` | Python notebook execution and sessions |
| Node.js | `/v1/nodejs` | Node.js execution and runtime information |
| Code | `/v1/code` | Generic code execution |
| MCP | `/v1/mcp` | MCP servers, tools, and tool execution |
| Skills | `/v1/skills` | Sandbox-local skill registration and content |
| Utility | `/v1/util` | Conversion helpers such as Markdown conversion |

## How AI Assistants Should Use This

1. Read `integration-map.md` before changing `AioSandboxClient`.
2. Search `openapi.json` for the exact path, such as `/v1/file/list`.
3. Follow the operation's `requestBody` or `parameters` reference.
4. Resolve `$ref` values under `components.schemas`.
5. Check required fields, enum values, response media types, and validation
   responses before writing request or response models.

The OpenAPI document is the source of truth when prose documentation and code
disagree. Existing code behavior must still be checked because the client may
contain compatibility handling for older sandbox versions.

## Important Conventions

- Most JSON endpoints return an envelope containing `success`, `message`, and
  `data`.
- `/v1/file/download` and `/v1/browser/screenshot` return binary content rather
  than the normal JSON envelope.
- `/v1/file/upload` uses `multipart/form-data`.
- Shell process identifiers are passed as `id` in shell request bodies.
- The sandbox base URL is dynamic and is stored by the project as the AIO
  endpoint.

## Refreshing The Snapshot

Open the current sandbox endpoint and download:

```powershell
Invoke-WebRequest `
  -UseBasicParsing `
  'http://<current-aio-endpoint>/v1/openapi.json' `
  -OutFile 'docs/sandbox-api/openapi.json'
```

After updating the JSON, update the version, date, counts, hash, and
`integration-map.md` if endpoints or schemas changed.
