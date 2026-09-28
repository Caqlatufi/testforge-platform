# report

拥有确定性 TestResult、RunReport、失败分类、P50/P95、运行对比和 Flaky 证据。

依赖 `common`、`case-catalog` 与 `run-orchestrator` 的只读/结果边界，不接受 AI 修改测试结论。

## 包边界

- `port.inbound`：供 `worker-gateway` 写入确定性结果、供 `ai-diagnosis` 只读报告证据。
- `port.outbound`：声明报告聚合所需的用例元数据与运行历史查询能力；适配器在后续业务任务实现。
- `model`：跨模块可见的不可变命令与证据 DTO，不包含报告聚合规则。
- `service`：聚合确定性报告、Case 历史与供 AI 使用的有界只读证据快照。
- `ReportConfig`：模块 Spring 装配入口，注册报告、历史和证据查询实现。

本模块不得访问其他模块的 `repo`，也不得依赖 `ai-diagnosis`。AI 只能读取报告证据，不能通过任何端口回写 `TestResult`。
