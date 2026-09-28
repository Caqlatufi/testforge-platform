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

## 构建与执行入口

在仓库根目录使用 `scripts/build.ps1 -Component All` 构建，使用 `scripts/check.ps1` 检查。Linux/macOS 对应 `sh scripts/build.sh all` 和 `sh scripts/check.sh`；一键启动以 Windows 指南为准。

根 `Jenkinsfile` 同时负责普通分支 CI，以及平台作为被测项目时的独立实例部署。部署模式精确检出 Test Job 冻结的 Commit，构建后发布到 API `18081`、Web `15174`，发送 BUILDING/READY/FAILED 回调。其他被测项目在各自仓库维护 Jenkinsfile。

平台 UI 自测使用 `playwright-web` Worker，业务操作通过页面定位器完成；原始截图、Trace 和运行日志留在本机，公开证据需先脱敏。

## 文档维护与发布参考

[`docs/reference`](docs/README.md#维护者参考) 用于跨模块的安全、文档维护和发布说明，按需阅读。

| 文档 | 主要用途 |
| --- | --- |
| [安全设计](docs/reference/安全设计.md) | 了解信任边界、凭据保护、审计结果与已知安全限制 |
| [开源发布清单](docs/reference/开源发布清单.md) | 发布前核对许可证、脱敏、依赖和验证状态 |
| [开源文档体系](docs/reference/开源文档体系.md) | 维护文档时确定内容归属、链接方式和同步更新规则 |
| [公开准备记录](docs/reference/公开准备记录.md) | 查阅仓库拆分、依赖交付和公开版本验证的过程与结论 |

组件配置另见 [后端](testforge-app/README.md)、[前端](testforge-client/README.md)、[Worker](testforge-worker/README.md)、[MCP](testforge-mcp/README.md)、[基础设施](infra/README.md)和[脚本入口](scripts/README.md)。安全问题反馈见 [安全策略](SECURITY.md)。

## Pull Request

PR 应说明用户可见变化、关键取舍、验证结果、未验证项和剩余风险。页面变化附截图；契约变化同步修改 `contracts`、调用方、示例和验收。禁止提交真实凭据、本机绝对路径、浏览器 Profile、数据库导出或未脱敏 Evidence。
