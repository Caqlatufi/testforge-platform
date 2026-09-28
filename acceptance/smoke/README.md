# Compose 冒烟验收

从平台仓库根目录执行：

```powershell
.\acceptance\smoke\compose-smoke.ps1
```

Linux/macOS：`bash acceptance/smoke/compose-smoke.sh`。

脚本构建并启动后端，检查 Health、Prometheus 和资源接口。使用 `testforge-smoke` Compose 项目及 13306、16379、19000、19001、18081 端口；结束后清理该项目临时卷。执行前确保端口空闲，避免与平台自测实例并用 18081。

证据保存在 `build/ci-smoke`。该验收不包含 Worker 执行、UI 流程或真实 Jenkins 部署。
