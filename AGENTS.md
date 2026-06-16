## AIO Sandbox API

Before changing sandbox HTTP integrations, read:

- `docs/sandbox-api/README.md`
- `docs/sandbox-api/integration-map.md`
- `docs/sandbox-api/openapi.json` for exact request and response schemas

Treat the checked-in OpenAPI document as the authoritative AIO REST contract.

## Code Documentation

- 所有新增或修改的代码注释、Javadoc 和脚本注释必须使用中文；类名、方法名、参数名、API 名称和其他技术标识符可保留原文。
- Add clear comments or Javadoc for every newly created class, field, and method.
- Class comments must explain the class responsibility and its role in the system.
- Method comments must explain purpose, important parameters, return values, and exceptional behavior.
- Error handling and retry code must document which failures are retried, which are not retried, and why.
- Comments should explain intent and constraints instead of restating the code line by line.
