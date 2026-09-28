# observability

负责只追加审计事件、SSE、Metrics 与 Trace 输出。

仅依赖 `common` 的事件信封；可以订阅各模块事件，但不能反向修改业务状态。
