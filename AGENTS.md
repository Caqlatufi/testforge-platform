# TestForge Platform 协作约定

- 本目录是独立 Git 仓库，先阅读 README、scripts/README.md 和 docs/README.md。
- 文档在 docs 内维护；验收脚本与夹具放在 acceptance，启动和构建入口放在 scripts。
- 使用 scripts/check.ps1 检查、scripts/build.ps1 构建；Shell 对应 check.sh、build.sh。
- MySQL 是业务状态真相源；回调、重试、领取和状态推进必须幂等。
- AI 只给证据支持的建议，不改写确定性测试结果。
- 不提交本机 .env、Codex 登录、浏览器会话或原始运行证据。配置使用 .env.example 中的占位值。
- 不依赖外层仓库、作者工作目录或私有示例项目；Jenkinsfile 属于本项目。
- 验收记录区分单测、构建、真实 API、UI 和 Jenkins 部署；未执行项标记 NOT_RUN。
- 默认不自动提交或推送；用户明确授权时按其范围执行。
