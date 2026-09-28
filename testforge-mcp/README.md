# TestForge MCP

`testforge-mcp` 是 TestForge 的独立 Go MCP Server。它运行在用户的 MCP Host 环境中，读取用户显式授权的本地 Git 工作区，并且只通过 TestForge 公开 REST API 管理 Case、Workflow、Test Job、执行和报告。

它不是被测项目依赖，不直连 TestForge 数据库、Redis、Jenkins 或 Worker，也不在本地复制平台状态。

## 构建

要求 Go 1.25+：

```powershell
cd testforge-mcp
go test ./...
go build -o build/testforge-mcp.exe ./cmd/testforge-mcp
```

查看构建信息：

```powershell
./build/testforge-mcp.exe version
```

## MCP Host 配置

环境变量：

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `TESTFORGE_URL` | 否 | `http://127.0.0.1:8081` | TestForge API 地址 |
| `TESTFORGE_TOKEN` | 使用认证代理时 | 空 | 发给 API 的 Bearer Token；不会写入绑定文件或 Tool 结果。当前平台本身尚不校验它 |
| `TESTFORGE_WORKSPACE` | 是 | 当前目录 | MCP 可以读取的唯一工作区根目录 |
| `TESTFORGE_BINDINGS` | 否 | OS 用户配置目录 | Workspace 与 Project 的本地绑定文件 |
| `TESTFORGE_TIMEOUT` | 否 | `30s` | 单次 HTTP 超时 |

通用 MCP Host 配置示例：

```json
{
  "mcpServers": {
    "testforge": {
      "command": "C:/tools/testforge-mcp.exe",
      "args": [
        "--url", "http://127.0.0.1:8081",
        "--workspace", "D:/work/my-project"
      ],
      "env": {
        "TESTFORGE_TOKEN": "replace-with-token-when-required"
      }
    }
  }
}
```

MCP 模式下 stdout 只承载协议消息，诊断信息只写 stderr。

## 能力

Tools：

- `testforge_inspect_workspace`
- `testforge_bind_project`
- `testforge_validate_case`
- `testforge_apply_case`
- `testforge_apply_workflow`
- `testforge_prepare_job`
- `testforge_execute_job`
- `testforge_get_execution`
- `testforge_get_report`

Resources：

- `testforge://workspace/context`
- `testforge://projects/{projectId}/context`
- `testforge://runs/{runId}/report`
- `testforge://schemas/test-case`

Prompts：

- `test_current_project`
- `add_regression_case`
- `analyze_test_failure`

## 安全与版本边界

1. 所有文件路径先规范化并解析符号链接，结果必须仍位于 `TESTFORGE_WORKSPACE` 内。
2. Test Job 默认固定本地已提交 HEAD；工作区 Dirty 时必须先提交，或显式声明仅测试已提交 HEAD。
3. 发布和执行的 `requestKey` 会在结果中返回；结果不确定时必须复用同一个 key。
4. TestForge Report 是确定性结论；AI Diagnosis 只是带证据建议。
5. MCP SDK 与协议版本由 `go.mod` 和 `version` 输出固定，升级前必须重新执行 Host 兼容性与 Conformance 验收。
6. 当前 TestForge 控制面没有内建认证/RBAC；`TESTFORGE_TOKEN` 只有在前置可信认证代理或后续认证实现存在时才形成访问控制。否则 API 和 MCP 只能运行在本机或可信实验网络。

完整设计见 [`../docs/10-mcp-integration`](../docs/10-mcp-integration/01-需求分析.md)。
