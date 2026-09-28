# TestForge Platform

> 本项目采用 Apache-2.0 许可，供本机自托管开发与测试。后端框架 JAR 已随仓库提供，干净源码副本启动已通过；公开版单 Case 的 UI → Jenkins → Worker → 报告主链路及重复执行已通过，复杂场景仍待验证，详见[公开准备记录](docs/reference/公开准备记录.md)。

测试任务编排与智能质量分析平台。该项目负责 Project、YAML-first Case、Workflow、Test Job、内部执行与 Attempt、Case 级资源调度、结果回调、报告、Flaky 和 AI 诊断。

## 工程组成

- `testforge-app`：Java 21 / Spring Boot Gradle多模块控制面。
- `testforge-client`：Vue 3 Web Console。
- `testforge-worker`：Python pytest-http / Playwright Web / Airtest执行器。
- `testforge-mcp`：面向 MCP Host 的独立 Go 接入层，可读取授权本地项目并调用 TestForge。
- `contracts`：OpenAPI与JSON Schema。
- `infra`：MySQL、Redis、MinIO和可观测性环境。
- `acceptance`：平台 API、可靠性验收与 HTTP 测试夹具。
- [`docs`](docs/README.md)：使用指南、功能设计、开发与验收方案，以及维护者参考。

本项目供每位使用者在自己的电脑上启动测试平台。首次使用从[五分钟快速开始](docs/guides/01-五分钟快速开始.md)进入，完整文档见[docs/README.md](docs/README.md)。本目录按独立公开仓库维护。

## 文档目录

完整索引见 [docs/README.md](docs/README.md)。首次使用先看使用指南；了解实现时，再按功能域查阅设计文档。

### 使用指南

| 文档 | 主要用途 |
| --- | --- |
| [五分钟快速开始](docs/guides/01-五分钟快速开始.md) | 准备依赖，启动基础设施、API 和 Web Console |
| [核心概念](docs/guides/02-核心概念.md) | 理解 Project、Case、Workflow、Test Job、Worker 和报告之间的关系 |
| [端到端演示](docs/guides/03-端到端演示.md) | 从页面创建测试资产，串联 Jenkins 发布、Worker 执行和报告 |
| [项目全流程视频脚本](docs/guides/04-项目全流程视频脚本.md) | 按讲解顺序准备演示画面和口播，录制项目介绍时使用 |
| [配置与凭据](docs/guides/05-配置与凭据.md) | 配置服务连接、环境变量及 AI/MCP 凭据，了解敏感信息存放方式 |

### 功能设计与开发

每个功能目录均包含四份文档：**需求分析**说明做什么、范围是什么；**架构设计**说明模块和数据如何协作；**开发方案**说明具体实现；**验收方案**说明如何验证及通过标准。各份文件的直接链接见 [功能域索引](docs/README.md#功能域索引)。

| 目录 | 主要内容 |
| --- | --- |
| [01 · 被测项目与 Jenkins 发布](docs/01-project-delivery/) | 项目登记、Git 版本选择、真实部署及部署回调 |
| [02 · Case 与脚本资产](docs/02-test-assets/) | YAML Case、脚本上传、资产版本及执行需求 |
| [03 · 工作流编排](docs/03-workflow-orchestration/) | DAG 节点与依赖、草稿编辑、发布和流程复用 |
| [04 · 测试任务管理](docs/04-test-job-management/) | 创建任务、冻结 Commit 与 Workflow 版本、确认和重复执行 |
| [05 · 执行编排与可靠性](docs/05-run-orchestration/) | 任务派发、状态推进、回调幂等、重试、取消与故障恢复 |
| [06 · 执行资源与调度](docs/06-execution-resources/) | Worker、环境和资源容量，按 Case 需求分配执行资源 |
| [07 · 结果与可观测性](docs/07-results-observability/) | 结果聚合、报告、事件推送、指标与执行证据 |
| [08 · AI 证据诊断](docs/08-ai-diagnosis/) | 基于执行证据生成诊断建议，以及失败降级和安全边界 |
| [09 · 平台工程与交付](docs/09-platform-engineering/) | 控制台、构建、启动、持续集成与平台回归 |
| [10 · MCP 接入](docs/10-mcp-integration/) | AI 客户端接入、授权工作区和 MCP 工具与平台 API 的边界 |

### 维护者参考

[`docs/reference`](docs/README.md#维护者参考) 用于跨模块的安全、文档维护和发布说明，按需阅读。

| 文档 | 主要用途 |
| --- | --- |
| [安全设计](docs/reference/安全设计.md) | 了解信任边界、凭据保护、审计结果与已知安全限制 |
| [开源发布清单](docs/reference/开源发布清单.md) | 发布前核对许可证、脱敏、依赖和验证状态 |
| [开源文档体系](docs/reference/开源文档体系.md) | 维护文档时确定内容归属、链接方式和同步更新规则 |
| [公开准备记录](docs/reference/公开准备记录.md) | 查阅仓库拆分、依赖交付和公开版本验证的过程与结论 |

组件配置另见 [后端](testforge-app/README.md)、[前端](testforge-client/README.md)、[Worker](testforge-worker/README.md)、[MCP](testforge-mcp/README.md)、[基础设施](infra/README.md)和[脚本入口](scripts/README.md)。参与开发见 [贡献指南](CONTRIBUTING.md)，安全问题反馈见 [安全策略](SECURITY.md)。

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
