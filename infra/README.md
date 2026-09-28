# TestForge 基础设施

本目录提供 MySQL、Redis、MinIO、OpenTelemetry Collector、Prometheus 和 Grafana 的可复现本地环境。MySQL 是业务状态真相源；Redis 仅保存可由 MySQL Outbox 恢复的派发、租约辅助和短期实时数据；完整日志与附件进入 MinIO。

## 环境

| 环境 | Compose 覆盖文件 | 数据策略 | 默认用途 |
| --- | --- | --- | --- |
| `dev` | `compose.dev.yaml` | 命名卷持久化 | 日常开发 |
| `test` | `compose.test.yaml` | tmpfs，停止后丢弃 | 自动化与集成测试 |
| `demo` | `compose.demo.yaml` | 命名卷持久化 | 面试演示，Grafana 默认打开总览面板 |

`env/*.env` 只包含本机开发默认值，不能直接作为公网或生产密钥。需要覆盖时复制并通过 Docker Compose 的 `--env-file` 传入私有文件，不要提交真实凭据。

## 使用

Windows PowerShell：

```powershell
.\bin\compose.ps1 -Environment dev -Action validate
.\bin\compose.ps1 -Environment dev -Action up
.\bin\compose.ps1 -Environment dev -Action status
.\bin\compose.ps1 -Environment dev -Action down
```

Linux/macOS：

```sh
./bin/compose.sh dev validate
./bin/compose.sh dev up
./bin/compose.sh dev status
./bin/compose.sh dev down
```

`up` 先执行配置校验，再使用 `docker compose up --wait` 等待全部长驻服务健康，最后运行 `init` profile 中的一次性 `minio-init` 任务，幂等创建 `MINIO_ARTIFACT_BUCKET`。`down` 保留命名卷，不会删除数据库或附件。

## 默认入口

| 服务 | dev/demo | test |
| --- | ---: | ---: |
| MySQL | `localhost:3306` | `localhost:13306` |
| Redis | `localhost:6379` | `localhost:16379` |
| MinIO API / Console | `localhost:9000` / `localhost:9001` | `localhost:19000` / `localhost:19001` |
| OTLP gRPC / HTTP | `localhost:4317` / `localhost:4318` | `localhost:14317` / `localhost:14318` |
| Prometheus | `http://localhost:9090` | `http://localhost:19090` |
| Grafana | `http://localhost:3000` | `http://localhost:13000` |

Prometheus 预配置抓取自身、OTel Collector 和宿主机 `8081` 端口的 `/actuator/prometheus`。应用未启动时 `testforge-app` target 显示为 down 是预期现象，不影响基础设施容器自身健康。

## Jenkins CI/CD 接入

Jenkins 是项目级 CI/CD 执行器，不并入基础中间件 Compose：

```powershell
docker compose -f .\compose.jenkins.yaml up -d --build --wait
```

入口为 `http://127.0.0.1:8082`。自定义 Jenkins 镜像只安装 Pipeline、Git、JUnit、Java 21、Node 22 与 Python，不挂载业务仓库，也不替项目自动创建 Job。

开发或运维需要在 Jenkins 中为每个被测项目创建 Multibranch Pipeline，并把 SCM 指向该项目的 Git 仓库。Jenkins 扫描分支并从对应分支读取项目根目录的 `Jenkinsfile`；TestForge 只绑定这个 Multibranch 项目，在测试任务启动时选择分支子 Job，传入 `DEPLOY_ENVIRONMENT`、`COMMIT_SHA` 和 `CALLBACK_URL`。实际 checkout、构建、测试、制品归档、部署与回调都由 Jenkins 执行。

可部署Pipeline还必须声明`INITIALIZE_ENVIRONMENT`布尔参数。Skill Sandbox在该参数为真时调用项目初始化钩子，并要求Jenkins配置`SKILL_SANDBOX_DEPLOY_ROOT`作为目标环境根目录；初始化失败必须使Build失败，不能继续回调READY。

Jenkins 地址和凭据属于平台级 Connection；被测项目的 Pipeline 实现与 Job 生命周期归开发/运维。不能把该无认证配置用于共享、测试或生产网络。

`TESTFORGE_CICD_CALLBACK_BASE_URL` 必须配置为所有 Jenkins Agent 都能访问的 TestForge 地址，不在仓库中固化某台宿主机的私网 IP。Jenkins 容器访问宿主控制面时可使用 `http://host.docker.internal:8081`；VM Agent 应填写其实际可达地址。需要启用 Jenkins 认证时，在
DeploymentProfile 里只保存 `credentialRef`，再通过 Spring 配置
`testforge.cicd.credentials.<ref>.username/token` 注入真实凭据；Token 不进入数据库或 Git。

`compose.jenkins.yaml` 默认仅绑定 `127.0.0.1:8082`，并通过环境变量配置 Jenkins 公共 URL、SCM、网关和 Agent 工作目录。该镜像跳过首次设置向导，只用于本地开发；共享环境必须另行配置身份认证、最小权限授权、TLS、备份和 Credential 轮换。

## 数据卷与清理

dev/demo 分别使用带 Compose 项目前缀的 MySQL、Redis、MinIO、Prometheus 和 Grafana 命名卷，三个环境互不共享数据。本目录脚本不提供自动删卷动作；需要清空数据时应先确认环境，再显式运行对应 Compose 命令的 `down --volumes`。
