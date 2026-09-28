# 安全策略

## 支持范围

TestForge 当前处于 `0.x` 工程验证阶段。尚未发布稳定 Release 时，仅主分支接受安全修复。

## 私下报告漏洞

公开仓库启用 GitHub Private Vulnerability Reporting 后，请使用 **Security → Report a vulnerability**。不要创建包含利用细节、真实 Token、密码、私有仓库地址或用户数据的公开 Issue。

报告应包含受影响 Commit、前置条件、最小复现、实际影响和建议修复（可选）。

## 当前运行边界

- 本项目当前交付目标是“每位使用者在自己的电脑上启动一套 TestForge”，不是公网服务器或 SaaS。控制面没有内建用户认证、RBAC 和租户隔离，默认只监听 `127.0.0.1`。
- `TESTFORGE_TOKEN` 只供 MCP 对接外部认证代理或未来平台认证使用；当前 TestForge API 不会自行校验该 Token。
- `infra/compose.jenkins.yaml` 是回环地址上的本地开发 Jenkins，不是生产安全模板。共享 Jenkins 必须自行启用认证、授权、TLS、CSRF 防护和 Credential 管理。
- Worker、Jenkins 回调和 Environment Agent 尚未形成完整双向身份认证体系；本机 VM 场景只允许受控虚拟交换网络。
- `infra/env/*.env` 包含可公开的本地演示默认值，不能作为公网或生产密码。

## 凭据规则

- 不提交 `.env`、`.env.local`、`auth.json`、Codex Home、浏览器 Profile、Jenkins Home、私钥或云厂商密钥。
- AI 诊断必须使用独立 `TESTFORGE_AI_CODEX_HOME`。不得复制个人 `~/.codex/auth.json` 到仓库、镜像或共享目录。
- 对象存储、数据库、Redis 和 Jenkins 凭据由部署环境注入；仓库只保留 `.env.example` 占位值。
- Evidence 可能包含截图、日志、URL、路径和业务数据；公开前必须再次脱敏。

完整威胁模型、审计结论和发布阻断项见 [`docs/reference/安全设计.md`](docs/reference/安全设计.md)。
