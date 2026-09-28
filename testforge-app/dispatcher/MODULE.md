# dispatcher

拥有 Outbox、Redis 派发记录、租约回收和重试调度。

依赖 `common` 与 `run-orchestrator` 的公开 Port/Service，不访问其他模块 Repo；Redis 不保存唯一业务真相。

## 包边界

- `port.inbound`：模块对内暴露的派发、恢复和重试用例端口；只表达调用意图。
- `port.outbound`：模块访问消息设施、时间等外部能力的端口；不泄漏具体中间件 API。
- `adapter.inbound`：实现 `run-orchestrator` 公开端口或承接定时触发器等入口。
- `adapter.outbound` / `stream`：承载 MySQL Outbox 与 Redis Streams 等基础设施适配器。

## Outbox 与 Relay

- `outbox`：MySQL 持久化事件、幂等 `eventKey`、带令牌的 claim/lease，以及过期租约恢复。
- `relay`：调用消息发布端口，成功后按 claim token 标记；失败按指数退避重新开放领取。
- `TransactionalOutboxDispatchRegistrar`：实现 run-orchestrator 的登记端口，使根任务与 Outbox 在同一事务提交或回滚。
- `RedisOutboxMessagePublisher`：把 `TASK_READY` Outbox 事件按 runner/platform 写入 Redis Streams，稳定复用 eventId 作为 messageId。
- `OutboxRelayScheduler`：按 fixed-delay 扫描可领取事件；多实例依赖数据库行锁、claim token 和租约恢复仲裁。

Relay 使用至少一次语义：消息系统已接收但数据库尚未标记时发生崩溃，租约到期后会再次投递。下游必须使用稳定的 `eventId` 或 `eventKey` 幂等消费。端口不能依赖适配器，且模块不能跨边界访问其他业务模块的 `repo`。

Stream 中的 `TASK_READY` 是“可领取任务”通知，不在 Redis 内创建或判定 Attempt。重复记录复用相同 `messageId` 与 `taskId`，Worker 后续仍须通过 worker-gateway / run-orchestrator 的 `state + version` 条件领取生成唯一有效 Attempt；Redis 记录本身不能授予执行权。

Redis 不可用时发布器进入短暂停顿，Relay 将事件重新置为 `PENDING` 并按指数退避更新 `availableAt`；Redis 恢复后的单探针成功会重新打开派发。开关和批量参数位于 `testforge.dispatcher.*`，应用默认开启，模块测试默认不启动后台 Relay。

## Attempt 租约与恢复

- Worker 领取后由 `AttemptLeaseService` 签发不可猜测的 `leaseToken`；服务端固定返回 5 秒心跳周期，默认租约窗口 15 秒。
- 心跳必须同时匹配 Attempt、Worker、token、RUNNING 状态和未过期租约，成功后以服务端时间延期。
- `ReliabilityReaperScheduler` 默认每秒扫描 MySQL。Reaper 用 token、version 与到期时间 CAS 标记 `LOST`，补偿扫描可在进程崩溃后继续收敛已落库的 LOST Attempt。
- `RetryCoordinator` 仅重试已知基础设施故障，默认最多 3 次总 Attempt；退避为 5 秒起步、2 倍增长、1 分钟封顶。
- 重排 Task 与登记下一 Attempt 的 Outbox 在同一事务完成；事件键包含 attempt 序号，`availableAt` 保证退避前不可领取。
- 取消、完成与硬超时通过 Task/Attempt 双版本 CAS 竞争，先落库的终态不被迟到事件覆盖；终态继续触发配额释放、DAG 与 Run 聚合。

相关配置位于 `testforge.dispatcher.reliability.*`。Redis 只承载派发通知，租约、LOST、重试时刻和最终状态仍以 MySQL 为准。
