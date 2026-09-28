# 平台脚本入口

在平台仓库根目录执行。日常只需使用以下入口：

| 入口 | 职责 | 说明 |
| --- | --- | --- |
| `start.ps1` / `stop.ps1` | 启停本机控制面和基础设施 | 不启动 Jenkins、VM 或 Worker；停止保留数据卷 |
| `dev.ps1 -Component Backend / Client / Worker` | 前台运行单个组件 | 调试时使用；三个值择一 |
| `build.ps1 -Component All` / `build.sh all` | 构建后端 jar、前端 dist、Worker wheel 和 MCP 二进制 | 可单独选 Backend、Client、Worker、Mcp；不代替检查入口 |
| `check.ps1` / `check.sh` | 单测、Java 模块边界、Go vet、OpenAPI/Schema 和验收证据检查 | 默认不启动服务，不执行真实 E2E |

```powershell
.\scripts\start.ps1 -SkipInstall
.\scripts\build.ps1 -Component All -SkipInstall
.\scripts\check.ps1 -SkipInstall
.\scripts\stop.ps1
```

Shell 对应命令为 `sh scripts/build.sh all` 和 `sh scripts/check.sh --skip-install`。`SkipInstall` 仅适用于已有依赖的环境。

## 按需辅助入口

- `workers/start_playwright_worker.ps1`：启动平台 UI 自测 Worker，配置、PID 与日志仍归属平台目录。
- `selftest/`：Jenkins 使用的初始化、部署、启动与回调脚本；它部署作为被测目标的独立平台实例（API 18081、Web 15174）。详见 [selftest/README.md](selftest/README.md)。
- [acceptance/smoke](../acceptance/smoke/README.md)：Compose 冒烟验证。显式运行 `scripts/check.ps1 -ComposeSmoke` 或 `sh scripts/check.sh --compose-smoke`；会创建临时基础设施并在结束时清理。
- 业务 API、可靠性和 HTTP 夹具验收统一在 [acceptance](../acceptance/README.md)。
- 演示操作与口播统一在 [端到端演示](../docs/guides/03-端到端演示.md)和[视频脚本](../docs/guides/04-项目全流程视频脚本.md)。

## 配置加载

`start.ps1` 与 `dev.ps1 -Component Backend` 读取平台根目录 `.env.local`。其他进程需自行注入对应环境变量。受控 VM 访问宿主机时需配置可达的 API、回调和 Redis 地址；配置明细见[配置与凭据](../docs/guides/05-配置与凭据.md)。

原 `verify.*`、`ci/run-all.ps1` 已合并到 `check.*`，原 `demo` 文档入口已归入 `docs/guides`。
