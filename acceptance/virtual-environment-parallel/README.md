# 多虚拟环境并行验收

本验收只接受至少两个独立桌面环境。每台 Windows VM 内运行一组 Skill Sandbox + Worker；不要在同一个 Windows 桌面启动多个 Sandbox，因为 Airtest Windows 输入最终会竞争同一套系统鼠标和键盘。

## 宿主网络基线

后端、VM Worker 必须连接同一个 Redis，且 Attempt 回调地址必须能从来宾机访问：

```powershell
$env:TESTFORGE_BIND_ADDRESS = '<HOST_ADDRESS>'
docker compose -f infra/compose.yaml up -d redis minio minio-init

$env:REDIS_HOST = '<HOST_ADDRESS>'
$env:TESTFORGE_PUBLIC_BASE_URL = 'http://<HOST_ADDRESS>:8081'
.\scripts\dev.ps1 Backend
```

不要让后端连接 `127.0.0.1` 的开发 Redis、VM Worker 连接 Default Switch Redis；两套队列不会互通。`TESTFORGE_PUBLIC_BASE_URL` 也不能使用回环地址，否则来宾机会把 Attempt 回调发送给自己。

## 每台 VM 的 Worker 配置

以下标识在每台 VM 上必须唯一；bridge token 由 Sandbox 启动时生成，不写入配置：

```powershell
$env:TESTFORGE_WORKER_ID = 'airtest-win-vm-a'
$env:TESTFORGE_WORKER_CONCURRENCY = '1'
$env:TESTFORGE_WORKER_CAPABILITIES = 'RUNNER_AIRTEST,PLATFORM_WINDOWS,WINDOWS_UI,ISOLATED_DESKTOP'
$env:TESTFORGE_RUNNER = 'airtest'
$env:TESTFORGE_PLATFORM = 'windows'
$env:TESTFORGE_DEVICE_ID = 'skill-sandbox-win-vm-a'
$env:TESTFORGE_DEVICE_URI = 'Windows:///?title_re=TestForge%20Skill%20Sandbox'
$env:TESTFORGE_DEVICE_PLATFORM = 'WINDOWS'
$env:TESTFORGE_DEVICE_FEATURES = 'WINDOWS_UI,ISOLATED_DESKTOP'
$env:TESTFORGE_ENVIRONMENT_ID = 'win-vm-a'
$env:TESTFORGE_SANDBOX_CWD = 'C:\testforge\skill-sandbox'
$env:TESTFORGE_SANDBOX_COMMAND_JSON = '["npm.cmd","run","start"]'
python -m testforge_worker --serve
```

VM B 将所有 `vm-a` 标识替换为 `vm-b`。两台 Worker 使用同一个 Gateway、Redis 和对象存储地址，但各自的 Sandbox、设备标识和本地工作目录独立。

## 验收

创建 `maxConcurrency >= 2` 的八场景 Run 后立即启动观察器：

```powershell
python acceptance/virtual-environment-parallel/verify.py --run-id <RUN_ID>
```

观察器必须在 Run 执行期间启动，因为它会采样至少两个不同 Worker 同时处于 `RUNNING` 的时刻。最终同时校验：8/8 通过、不同 Worker/DeviceSlot、历史时间区间重叠、附件 objectKey 归属正确，以及 Run 墙钟时间（`createdAt` 到 `finishedAt`）不超过最近串行基线的 80%。报告中的 Task duration 总和只作为诊断指标，不能用于判定并行加速。证据写入 `acceptance/evidence/virtual-environment-parallel/latest.json`。
