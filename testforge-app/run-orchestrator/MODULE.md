# run-orchestrator

拥有 Run、Task 与 Attempt 状态机，负责 DAG 释放、运行聚合和并发配额。

依赖 `common`、`project-catalog`、`case-catalog`；派发需求以公开 Port 表达，不反向依赖 `dispatcher`。

## 包边界

| 包 | 职责 |
| --- | --- |
| `ctrl` | Run 查询、创建和取消入口及模块异常映射；只调用本模块 Service |
| `entity` | TestRun、TestTask、TaskAttempt 持久化实体 |
| `model` | 运行编排 DTO、状态枚举、值对象与查询模型 |
| `repo` | 仅管理本模块实体；不得被其他模块直接访问 |
| `service` | 显式状态机、version 条件迁移、DAG 释放、聚合和并发配额规则 |
| `event` | 本模块发布或消费的运行领域事件 |
| `port` | 对外公开的跨模块协作端口，尤其是派发登记需求 |

`RunOrchestratorConfig` 是 app 显式导入的模块装配入口，限定扫描本模块 Controller、实体和仓储，并显式声明业务 Service。Run 查询以 Task 为子项、Attempt 为时间线返回聚合；leaseToken 不通过查询 API 泄露。

DAG 释放以持久化 Task 为真相源：根节点创建即进入 `QUEUED`，后继节点在前置终态后按 `ON_SUCCESS` / `ON_COMPLETION` 收敛；状态与 `persistenceVersion` 的 CAS 保证后继只释放一次。配额服务锁定 Run 后核算 `DISPATCHED` / `RUNNING` 数量，Task 终态释放配额时在同一事务中推进后继并重新聚合 Run。

`service.reliability.ExecutionReliabilityService` 是 dispatcher 的公开状态真相源边界：它创建有效 Attempt、条件续租、提供 LOST/超时候选，并以 Task/Attempt 双版本事务 CAS 提交完成、取消、超时和重排。重排时通过派发 Port 同事务登记下一 Attempt 的延迟 Outbox，dispatcher 不直接访问本模块 Repo。

## 验证

```powershell
.\gradlew.bat :run-orchestrator:test
.\gradlew.bat :run-orchestrator:jar
```

架构测试限制源码只能依赖 `common`、`project-catalog`、`case-catalog` 和基础框架，防止形成对 `dispatcher` 等下游模块的反向依赖。
