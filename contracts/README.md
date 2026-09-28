# TestForge Contracts

本目录是 Java 控制面、Vue/TypeScript 客户端和 Python Worker 之间的协议单一事实源。当前稳定契约版本为 `1.0.0`，HTTP API 使用 `/api/v1`，文件按 `v1` 目录隔离。

## 目录

```text
contracts/
├─ manifest.json
├─ openapi/v1/testforge.yaml
├─ schemas/v1/
│  ├─ task-message.schema.json
│  ├─ attempt-callback.schema.json
│  ├─ domain-event.schema.json
│  └─ artifact.schema.json
├─ examples/v1/
└─ tools/validate.mjs
```

`task-message` 是 Redis Streams 的 Worker 派发信封；`attempt-callback` 是完成回调载荷；`domain-event` 是只追加审计/SSE 事件信封；`artifact` 是对象存储附件索引。消息可以重复到达，消费者必须以 `messageId`、`callbackKey` 和业务状态条件实现幂等，不能将 Redis 当作最终状态源。

## 校验

在本目录执行：

```bash
npm ci
npm run validate
```

校验命令会：

1. 校验 OpenAPI 3.1 文档及其外部 `$ref`；
2. 按 JSON Schema Draft 2020-12 校验四份 Schema；
3. 校验清单版本、文件完整性以及全部正反例；
4. 断言 OpenAPI 版本与目录清单一致。

## 跨语言使用

三端均直接从本目录生成或加载类型，不在各工程手写第二份协议：

- Java：使用 OpenAPI Generator 的 `spring`/`java` 生成 DTO 与接口，消息模型从对应 JSON Schema 生成。
- TypeScript：使用 `openapi-typescript openapi/v1/testforge.yaml` 生成 API 类型，使用支持 Draft 2020-12 的校验器加载消息 Schema。
- Python：使用 `datamodel-code-generator --input openapi/v1/testforge.yaml --input-file-type openapi` 生成模型，Worker 使用 `jsonschema` Draft 2020-12 校验消息与回调。

生成物属于消费方构建产物，不提交回本目录；版本和枚举值以这里的源文件为准。

## 演进规则

- `schemaVersion` 使用语义化版本。消费者必须拒绝未知主版本，并允许同主版本新增可选字段。
- 只增加可选字段、响应字段或新端点时提升次版本；删除字段、改变含义、收紧已有输入或修改枚举值时新建 `v2`。
- 已发布版本不原地改变字段语义。修正文案或放宽验证时提升补丁版本并保留兼容性。
- 时间统一为 RFC 3339 UTC，标识统一为 UUID，哈希统一为小写十六进制 SHA-256。
- OpenAPI 3.1 与 JSON Schema 均使用 Draft 2020-12；跨文件引用使用相对路径，Schema `$id` 使用稳定的 `https://schemas.testforge.dev/` 命名空间。
