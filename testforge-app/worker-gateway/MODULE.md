# worker-gateway

拥有 Worker、Runner 能力、DeviceSlot 与 CallbackReceipt，负责注册、心跳、开始、幂等和迟到回调入口。

依赖 `common`、`run-orchestrator` 和 `report` 的公开 Service，不直接执行测试脚本。

## 包边界

- `port.inbound`：Worker 注册、设备登记、Attempt 开始/心跳/回调等应用端口。
- `port.outbound`：调用运行编排与确定性结果服务所需的端口。
- `adapter.inbound`：后续承载 HTTP 控制器及协议映射，不包含状态机规则。
- `adapter.outbound`：后续适配 `run-orchestrator` 与 `report` 的公开 Service，禁止访问其 Repo。

## Worker registry

`registry` 子域已经实现 Worker 节点注册与在线生命周期：

- `workerId` 是 Worker 声明的稳定身份，服务端生成不可变 `instanceId`；重复注册复用原实例记录。
- 重复注册同时刷新协议版本、能力集合、最大并发数和最近心跳，能力更新不会留下旧能力。
- 在线状态由 MySQL 中的 `lastHeartbeatAt` 与配置项
  `testforge.worker-gateway.registry.offline-timeout` 动态计算，不依赖 Redis 或易漂移的缓存状态。
- 能力匹配只返回在线节点，并校验协议小版本、runner、platform 与全部 requiredFeatures。
- 对外提供注册、心跳、按状态查询和能力匹配入口；设备占用与 Attempt 回调仍由后续集成任务实现。

## Attempt callback

- start、heartbeat 与 callback 都校验 `workerId + leaseToken`，过期或旧 Attempt 不能推进当前终态。
- `(attemptId, callbackKey)` 是持久化幂等键，`payloadHash` 不同返回冲突且不触碰状态机。
- 重复载荷返回 `DUPLICATE`，并在 `originalDisposition`、`taskId`、`resultStatus`、`reason` 中重放首次处理结果。
- 迟到回调保存 `STALE_CALLBACK` receipt；重复迟到回调仍返回原始 STALE 结果，不写报告、不覆盖终态。

## Device registry

`device.registry` 子域只负责设备登记与候选发现，不实现设备占用：

- `workerId + deviceId` 是 Worker 声明的稳定设备身份，服务端首次登记生成不可变 `slotId`；重复登记复用同一槽位。
- 重复登记会完整替换 platform、deviceUri、features 与 resolution，并刷新设备心跳，不残留旧能力标签。
- `AVAILABLE/OFFLINE` 由设备心跳超时与所属 Worker 在线状态共同计算；任一离线都不会进入候选集。
- 匹配要求 platform 与全部 requiredFeatures 满足；候选依次按额外能力更少、心跳更新、workerId、deviceId 排序，以保留更稀缺的设备并保证结果稳定。
- `RESERVED`、Attempt 绑定、释放和租约回收属于 `device.lease` 子域，不在注册服务中修改。

## Device lease

`device.lease` 子域以 MySQL 条件更新维护 DeviceSlot 独占占用，不做能力匹配：

- `AVAILABLE -> RESERVED` 同时绑定唯一 `currentAttemptId` 和服务端 `leaseToken`；并发竞争只有一条更新成功。
- 重复占用同一 Attempt 返回首次租约，其他 Attempt 收到占用冲突。
- 心跳必须匹配 DeviceSlot、Attempt、token 且租约未过期，成功后延长租期并增加版本。
- Attempt 取消、成功/失败等终态按 Attempt 条件释放；旧 Attempt 的迟到释放不会清除新占用。
- 定时 Reaper 从 MySQL 扫描到期候选，再以 Attempt、token、version 和到期时间条件回收，保证 Worker 异常退出后最终释放。
- 设备注册子域只需通过稳定 `deviceSlotId` 初始化租约记录；注册信息、离线状态和能力排序仍归 `device.registry`，由 TFP-013 集成。

端口不能依赖适配器，入站与出站适配器之间不直接耦合。
