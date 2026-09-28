# TestForge 跨项目验收

本目录保存从公开入口验证完整业务链路的验收材料，不归属于 Java 后端、Vue 前端或 Python Worker 任一子项目。

- `bruno/`：真实 HTTP API 验收集合。
- `reliability/`：Worker 宕机、重复消息、迟到回调和 Redis 恢复场景。
- [`smoke/`](smoke/README.md)：使用独立 Compose 项目与端口验证后端健康、指标和资源接口。
- [`fixtures/http/`](fixtures/http/README.md)：按需启动的 HTTP 被测夹具，提供成功、延迟、500 和概率失败场景。
- 单次运行日志、截图和 Trace 放入 `../tests/acceptance/runs/`，不提交凭据与大体积运行产物。
