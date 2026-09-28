# 参与 TestForge

感谢参与 TestForge。安全漏洞请按 [`SECURITY.md`](SECURITY.md) 私下报告。

## 工程边界

```text
testforge-app       Java 控制面与公开 API
testforge-client    Vue Web Console
testforge-worker    Python 执行器
testforge-mcp       Go MCP 接入层
contracts           跨语言契约
infra               本地基础设施与 Jenkins 示例
acceptance          验收工具与可公开证据
```

MySQL 是状态真相源；Redis 只承担可恢复派发与短期协调。Worker 和 MCP 不直连数据库。Jenkins 负责实际发布，TestForge 只选择版本、触发和追溯。AI 诊断只提供带证据建议，不修改确定性结果。

## 本地开发

要求 Java 21、Node.js 22.12+、Python 3.10+、Go 1.25+ 和 Docker Compose v2。快速开始见 [`docs/guides/01-五分钟快速开始.md`](docs/guides/01-五分钟快速开始.md)。

提交前按改动范围运行：

```powershell
./scripts/check.ps1

cd testforge-app
./gradlew.bat test verifyModuleBoundaries

cd ../testforge-client
npm test -- --run
npm run build

cd ../testforge-worker
python -m pytest -q

cd ../testforge-mcp
go test ./...
go vet ./...
```

## Pull Request

PR 应说明用户可见变化、关键取舍、验证结果、未验证项和剩余风险。页面变化附截图；契约变化同步修改 `contracts`、调用方、示例和验收。禁止提交真实凭据、本机绝对路径、浏览器 Profile、数据库导出或未脱敏 Evidence。
