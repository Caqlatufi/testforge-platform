# 可靠性验收

本目录从系统外触发 Worker 宕机、重复/迟到回调、Redis 恢复和双资源池并发场景。完整场景、入口和证据映射见 `matrix.json`；HTTP 断言同时收录在 `acceptance/bruno/reliability`。

验收脚本只能通过公开 API、消息入口和进程控制制造故障，不得直接修改 MySQL 伪造终态，也不得绕过正式状态机。

## Worker 真并发验收

启动仓库 Redis 后，通过同一 Consumer Group 启动 3 个不同 `workerId` 的真实
`ConsumerWorker`，每个 Worker 以固定并发池执行本机子进程：

```powershell
docker compose -f .\infra\compose.yaml up -d redis
python .\acceptance\reliability\worker_concurrency_100.py
```

命令执行 100 个 Task，并将任务终态、重复 Attempt、pending 消息残留、耗时、
Worker 分布和并发峰值写入
`acceptance/evidence/worker-concurrency-100.json`。
