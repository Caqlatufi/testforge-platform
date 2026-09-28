# ai-diagnosis

只读报告证据，负责 Provider 调用、结构校验和带证据的诊断建议。

依赖 `common` 与 `report`；模型判断不得修改 TestResult，也不得冒充确定事实。

## 包边界

- `port.inbound`：对外暴露生成诊断建议的用例边界。
- `port.outbound`：声明外部 AI Provider 边界，只接收报告模块提供的只读证据。
- `model`：诊断请求、Provider 输出、建议 DTO 与证据引用。
- `provider`：以只读、临时、无会话方式调用当前用户已登录的 Codex CLI，并执行结构化输出校验。
- `service`：负责报告证据读取、幂等、缓存复用、证据 ID 二次校验和失败降级。
- `repo`：保存诊断状态、结果、模型元数据与失败摘要，不保存 Codex 登录凭据。
- `ctrl`：暴露 Provider 状态、生成诊断和读取最近诊断接口。
- `AiDiagnosisConfig`：模块 Spring 装配入口，注册 `gpt-5.6-luna` / `high` 的 Codex CLI Provider。

默认关闭真实 Provider。设置 `TESTFORGE_AI_ENABLED=true` 并通过 `TESTFORGE_CODEX_COMMAND` 指向已登录的 Codex CLI 后启用。

本模块不得访问 `report` 的 controller、entity、repo、service、event 或出站端口，也不提供修改确定性测试结果的接口。
