# TestForge Platform

> 本项目采用 Apache-2.0 许可，供本机自托管开发与测试。后端框架 JAR 已随仓库提供，干净源码副本启动已通过；完整 API/UI/Jenkins/Worker 全链路验收尚未完成，详见[公开准备记录](docs/reference/公开准备记录.md)。

测试任务编排与智能质量分析平台。该项目负责 Project、YAML-first Case、Workflow、Test Job、内部执行与 Attempt、Case 级资源调度、结果回调、报告、Flaky 和 AI 诊断。

## 工程组成

- `testforge-app`：Java 21 / Spring Boot Gradle多模块控制面。
- `testforge-client`：Vue 3 Web Console。
- `testforge-worker`：Python pytest-http / Playwright Web / Airtest执行器。
- `testforge-mcp`：面向 MCP Host 的独立 Go 接入层，可读取授权本地项目并调用 TestForge。
- `contracts`：OpenAPI与JSON Schema。
- `infra`：MySQL、Redis、MinIO和可观测性环境。
- `acceptance`：平台 API、可靠性验收与 HTTP 测试夹具。

本项目供每位使用者在自己的电脑上启动测试平台。首次使用从[五分钟快速开始](docs/guides/01-五分钟快速开始.md)进入，完整文档见[docs/README.md](docs/README.md)。本目录按独立公开仓库维护。

## 使用路径

| 目标 | 入口 |
| --- | --- |
| 启动 API 与 Web Console | [五分钟快速开始](docs/guides/01-五分钟快速开始.md) |
| 理解当前领域模型 | [核心概念](docs/guides/02-核心概念.md) |
| 跑通 Jenkins、Worker 与报告 | [端到端演示](docs/guides/03-端到端演示.md) |
| 配置 AI 客户端 | [TestForge MCP](testforge-mcp/README.md) |
| 配置服务、Codex 与凭据 | [配置与凭据](docs/guides/05-配置与凭据.md) |
| 了解安全边界 | [安全策略](SECURITY.md) · [安全设计与审计](docs/reference/安全设计.md) |
| 录制项目介绍 | [全流程视频脚本](docs/guides/04-项目全流程视频脚本.md) |

## 最小构建

环境要求：Java 21、Node.js 22.12+、Python 3.10+、Go 1.25+。各子工程可以独立验证：

```powershell
# Java 控制面
cd testforge-app
.\gradlew.bat clean build

# Vue Console
cd ..\testforge-client
npm ci
npm run build

# Python Worker
cd ..\testforge-worker
$env:PYTHONPYCACHEPREFIX = "$PWD/build/pycache"
python -m pip install -e .
python -m unittest discover -s tests
python -m pip wheel . --no-deps --wheel-dir build/wheels

# TestForge MCP
cd ..\testforge-mcp
go test ./...
go build -trimpath -o build/testforge-mcp.exe ./cmd/testforge-mcp
.\build\testforge-mcp.exe version

# 公开入口验收骨架
cd ..
python acceptance/validate.py
```

在 `testforge-platform` 目录可通过统一入口执行全部构建：

```powershell
.\scripts\build.ps1 -Component All
```

构建只生成制品；测试与契约检查使用 `scripts/check.ps1`。Linux/macOS 对应入口为 `sh scripts/build.sh all` 和 `sh scripts/check.sh`。`contracts` 和 `infra` 各自保留独立校验命令，由对应工程任务维护。

## 一键运行与项目 Pipeline

```powershell
# 启动 MySQL、Redis、MinIO、Java API 和 Vue Console
.\scripts\start.ps1

# 执行 Java/Python/Node/Go 测试与契约检查
.\scripts\check.ps1

# 停止进程但保留本地数据卷
.\scripts\stop.ps1
```

平台自身 CI 与“TestForge 作为被测项目”的独立测试实例发布均定义在 [`Jenkinsfile`](Jenkinsfile)：普通分支 Build 执行 Java/Python/Node/契约门禁；TestForge 传入发布参数时，Jenkins 精确检出冻结 Commit，将后端发布到 `18081`、Web Console 发布到 `15174`，并通过 BUILDING/READY/FAILED 回调释放测试任务。其他被测项目必须在各自仓库维护 Jenkinsfile；本仓库不打包私有或同级示例项目。本地检查入口为 `scripts/check.ps1` 执行。

平台 UI 自测使用 `playwright-web` Worker。它只消费 `testforge:tasks:playwright-web`，以 `RUNNER_PLAYWRIGHT_WEB` 注册，脚本必须通过 Playwright 页面定位器完成操作，不能用 REST 代替业务步骤；每次 Attempt 都归档截图、Trace、浏览器日志和结构化结果。

MCP Host 接入方式见 [`testforge-mcp/README.md`](testforge-mcp/README.md)，完整边界见 [`docs/10-mcp-integration`](docs/10-mcp-integration/01-需求分析.md)。

## 安全提示

AI 诊断默认关闭；启用时必须使用 TestForge 专用 `TESTFORGE_AI_CODEX_HOME`，不要复用或提交个人 Codex Home。控制面当前没有内建用户认证/RBAC，默认仅监听 `127.0.0.1`，不能直接暴露公网。复制 [`.env.example`](.env.example) 到未跟踪的 `.env.local` 后填写自己的数据库、Redis、Jenkins、对象存储和 MCP 配置；完整限制见 [`SECURITY.md`](SECURITY.md)。

## 许可证

平台原创内容及随附的 `commons`、`simple-migration` 自有框架 JAR 采用 [Apache-2.0](LICENSE) 许可。第三方依赖遵循各自许可证；框架 JAR 的说明与校验值见 [testforge-app/libs](testforge-app/libs/README.md)。
