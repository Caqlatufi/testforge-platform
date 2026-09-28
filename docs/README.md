# TestForge Platform 文档

文档按平台的十个长期主功能域组织。每个功能域固定包含 `01-需求分析.md`、`02-架构设计.md`、`03-开发方案.md`、`04-验收方案.md`。面向开源使用者的操作文档集中在 `guides/`，文档治理和发布门禁进入 `reference/`；历史私有任务档案不随公开仓库分发。

## 开源使用指南

| 顺序 | 指南 | 结果 |
| --- | --- | --- |
| 1 | [五分钟快速开始](guides/01-五分钟快速开始.md) | 启动基础设施、API 与 Web Console |
| 2 | [核心概念](guides/02-核心概念.md) | 理解当前业务对象与职责边界 |
| 3 | [端到端演示](guides/03-端到端演示.md) | 跑通 Commit、Jenkins、Worker 与报告 |
| 4 | [项目全流程视频脚本](guides/04-项目全流程视频脚本.md) | 完成 12～15 分钟项目讲解 |
| 5 | [配置与凭据](guides/05-配置与凭据.md) | 安全配置服务、Codex、对象存储、Worker 与 MCP |

文档结构和维护规则见[开源文档体系](reference/开源文档体系.md)，公开发布前必须核对[开源发布清单](reference/开源发布清单.md)。

## 功能域索引

| 顺序 | 功能域 | 负责回答 | 工程归属 | 文档 |
| --- | --- | --- | --- | --- |
| 01 | 被测项目与 Jenkins 发布 | 测什么项目、固定哪个 Commit、由哪个 Pipeline 发布 | project-catalog、cicd-gateway | [需求](01-project-delivery/01-需求分析.md) · [架构](01-project-delivery/02-架构设计.md) · [开发](01-project-delivery/03-开发方案.md) · [验收](01-project-delivery/04-验收方案.md) |
| 02 | Case 与脚本资产 | 测试原子能力、脚本版本和执行需求是什么 | case-catalog/testcase | [需求](02-test-assets/01-需求分析.md) · [架构](02-test-assets/02-架构设计.md) · [开发](02-test-assets/03-开发方案.md) · [验收](02-test-assets/04-验收方案.md) |
| 03 | 项目工作流编排 | Case/Fixture/Subflow 如何组成可发布 DAG | case-catalog/workflow | [需求](03-workflow-orchestration/01-需求分析.md) · [架构](03-workflow-orchestration/02-架构设计.md) · [开发](03-workflow-orchestration/03-开发方案.md) · [验收](03-workflow-orchestration/04-验收方案.md) |
| 04 | 测试任务管理 | 用户如何固定项目、Commit、WorkflowVersion 和目标系统 | test-job、testforge-client/jobs | [需求](04-test-job-management/01-需求分析.md) · [架构](04-test-job-management/02-架构设计.md) · [开发](04-test-job-management/03-开发方案.md) · [验收](04-test-job-management/04-验收方案.md) |
| 05 | 执行编排与可靠性 | 测试任务如何拆 DAG、可靠派发、重试、取消和收敛 | run-orchestrator、dispatcher | [需求](05-run-orchestration/01-需求分析.md) · [架构](05-run-orchestration/02-架构设计.md) · [开发](05-run-orchestration/03-开发方案.md) · [验收](05-run-orchestration/04-验收方案.md) |
| 06 | 执行资源与 Case 级调度 | 每个 Case 需要什么资源、平台如何分配 Provider/Agent/环境 | worker-gateway、testforge-worker、resource scheduling | [需求](06-execution-resources/01-需求分析.md) · [架构](06-execution-resources/02-架构设计.md) · [开发](06-execution-resources/03-开发方案.md) · [验收](06-execution-resources/04-验收方案.md) |
| 07 | 结果、事件与可观测性 | 结果如何聚合、事件如何续传、容量如何观察 | report、observability | [需求](07-results-observability/01-需求分析.md) · [架构](07-results-observability/02-架构设计.md) · [开发](07-results-observability/03-开发方案.md) · [验收](07-results-observability/04-验收方案.md) |
| 08 | AI 证据诊断 | AI 如何只基于证据给出可降级建议 | ai-diagnosis | [需求](08-ai-diagnosis/01-需求分析.md) · [架构](08-ai-diagnosis/02-架构设计.md) · [开发](08-ai-diagnosis/03-开发方案.md) · [验收](08-ai-diagnosis/04-验收方案.md) |
| 09 | 平台工程、控制台与交付 | 平台如何构建、启动、展示、持续集成和固定回归 | app、common、contracts、client、infra、acceptance | [需求](09-platform-engineering/01-需求分析.md) · [架构](09-platform-engineering/02-架构设计.md) · [开发](09-platform-engineering/03-开发方案.md) · [验收](09-platform-engineering/04-验收方案.md) |
| 10 | MCP 接入层 | AI 客户端如何读取本地项目并通过公开 API 使用 TestForge | 独立 Go module `testforge-mcp` | [需求](10-mcp-integration/01-需求分析.md) · [架构](10-mcp-integration/02-架构设计.md) · [开发](10-mcp-integration/03-开发方案.md) · [验收](10-mcp-integration/04-验收方案.md) |

## 主业务链

```text
被测项目 + Jenkins Pipeline
  -> YAML Case / Fixture / Managed Asset / ExecutionRequirement
  -> Project WorkflowVersion（只固定 DAG 拓扑与 Case 引用）
  -> Test Job（固定 Project + Commit + WorkflowTopologyVersion + TargetSystem）
  -> 内部执行 Attempt（创建时固化 Case YAML/脚本/资源校验和）/ DAG Task
  -> ResourceClaim / Provider / Environment Agent / Lease
  -> TestResult / ExecutionEvent / Report
  -> AI Evidence Diagnosis
```

## 关键边界

- Project 是被测 Git 项目，不是测试任务容器，也不拥有环境。
- Jenkins 负责 Jenkinsfile、SCM、Agent、构建和真实发布；TestForge 负责选择、触发、门禁和追溯。
- Test Job 是用户可见的执行对象；内部执行 Attempt 只保存重跑历史，不提供独立 Runs 主栏。
- Workflow 强归属 Project；新节点只使用 Case、Fixture、Subflow；多个 Case 不再通过 Suite 组合。发布版本冻结 DAG 拓扑，不冻结 Case 内容。
- Case 保存后，所有引用它的 Workflow 在下一次执行时自动读取最新有效定义；已经创建的 QUEUED/RUNNING/终态执行只读取自身不可变快照。
- Case 声明 ExecutionRequirement；Test Job 不填写资源池、具体机器或并发数。
- Dispatcher Worker 是固定控制面消费者；Environment Agent 是环境内可替换进程；真实容量来自 Provider 和 AllocatableResource。
- MySQL 是状态、事件和派发意图真相源；Redis 只承担可重复派发和短期协调。
- AI 诊断只读取确定性报告和证据，不回写结果。
- MCP 是独立 Go 接入层，只通过公开 HTTP 契约连接 TestForge；不修改 TestForge 核心、不直连数据库、Redis、Jenkins 或 Worker，也不在 MCP 内复制平台状态。

## 示例、任务与参考

- [MCP 接入层](10-mcp-integration/01-需求分析.md)：面向 MCP Host 的本地项目检查、TestForge 资产编排与测试执行入口。
- [TestForge MCP 使用说明](../testforge-mcp/README.md)：安装、MCP Host 配置、能力列表和安全边界。
- [开源使用指南](guides/README.md)：快速开始、核心概念、端到端演示与视频脚本。
- [开源发布清单](reference/开源发布清单.md)：许可证、脱敏、干净安装、Release 与安全门禁。
- [安全设计与开源审计](reference/安全设计.md)：威胁模型、Codex 凭据边界、扫描结论与公网阻断项。

## 维护规则

1. 新需求先判断归属上述十个功能域，回填该域四份文档；不得按任务编号或某次重构另建并列功能目录。
2. 同一事实只在最合适的功能域完整描述；其他文档以稳定相对链接引用。
3. 长期设计回填对应功能域；验收脚本放入 `acceptance`，运行产物在本机生成。
4. 需求分析只写目标、核心流程、关键规则、异常分支、本期包含和成功标准。
5. 架构设计维护模块边界、模型、链路和真实目录；接口字段进入开发方案。
6. 开发方案按功能组织，每个功能至少一张流程图；存在竞争、重试、租约或并行时必须写并发实现方式。
7. 验收只认真实流程和证据；状态仅使用 `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`。
8. 目录重命名或模型收敛后同步更新任务、README、AGENTS 和代码注释中的稳定链接，不保留第二套“历史设计目录”。
