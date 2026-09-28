# 平台自测实例

这些脚本由根 `Jenkinsfile` 调用，用于把 TestForge 本身作为被测项目发布到本机独立实例。

| 脚本 | 调用方与作用 |
| --- | --- |
| `initialize_selftest_environment.ps1` | Jenkins 初始化阶段；准备自测数据库、账号与目录 |
| `deploy_selftest_environment.ps1` | Jenkins 部署阶段；接收 jar、dist、Commit 和环境键，发布并启动实例 |
| `run_selftest_backend.ps1` | 部署脚本生成的启动任务；启动 API 18081 |
| `run_selftest_web.ps1` / `selftest_web_server.py` | 部署脚本生成的启动任务；启动 Web 15174 |
| `jenkins_callback.py` | Jenkins 回传 BUILDING、READY、FAILED |

部署脚本从自身目录复制运行脚本，支持独立平台仓库和聚合仓库。`testforge-selftest` 仍为当前支持的环境键，目标目录由 `TESTFORGE_SELFTEST_ROOT` 指定，默认 `%ProgramData%/TestForge/platform-selftest`；执行前需具备本机 Jenkins Agent、MySQL 凭据及 Java 21。此目录不作为日常 `start.ps1` 的启动入口。
