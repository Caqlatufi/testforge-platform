# testforge-worker

独立 Python Worker 工程。它只通过 Redis、HTTP 和对象存储公开契约接入平台，不导入 Java 后端源码，也不直连 MySQL。

包边界：

- `consumer`：至少一次消息消费与领取协调。
- `runtime`：并发、取消、租约心跳和子进程生命周期。
- `executor`：通用 Runner、Automation Driver SPI 与驱动实现；Playwright Web 负责可并发的浏览器 UI 测试，Airtest Adapter 支持 Windows、Android、iOS 交互目标。
- `environment`：Worker 独占 Sandbox 的启动、探活与进程树回收 SPI。
- `device`：Worker 本地设备发现与能力上报。
- `callback`：开始、心跳和幂等结果回调客户端。
- `artifact`：日志、JUnit、截图和报告上传。

最小验证：

```powershell
$env:PYTHONPYCACHEPREFIX = "$PWD/build/pycache"
python -m pip install -e .
python -m unittest discover -s tests
python -m pip wheel . --no-deps --wheel-dir build/wheels
python -m testforge_worker
```

## Worker runtime

`RedisStreamConsumer` 创建 Consumer Group，优先使用 `XAUTOCLAIM` 接管超过
空闲阈值的 pending 消息，再以 `XREADGROUP` 读取新消息。`ConsumerWorker` 只有在
Gateway 已接受完成回调、确认消息租约过期，或识别出本机重复执行时才 ACK；网络
失败会保留 pending 消息，等待同组 Worker 再次 claim。

`WorkerRuntime` 通过 `RunnerAdapter.prepare()` 获取子进程命令，通过
`RunnerAdapter.result()` 映射 Runner 结果。UI 自动化工具先注册到
`AutomationDriverRegistry`，并通过 `AutomationDriver` SPI 复用同一 Runtime。
当前 `AirtestAdapter` 声明 Windows、Android、iOS 三个平台；新增自动化工具只需实现
SPI 并注册，不修改 Consumer 或 Runtime。Runtime 统一负责 Attempt start、5 秒心跳、
取消传播、硬超时、进程组终止和 stdout/stderr 日志流。具体 pytest-http/playwright-web/Airtest
适配由上层集成，不进入通用 runtime。

## Playwright Web Worker

```powershell
python -m pip install -e .
$env:TESTFORGE_RUNNER = 'playwright-web'
$env:TESTFORGE_STREAM = 'testforge:tasks:playwright-web'
$env:TESTFORGE_WORKER_ID = 'playwright-web-local'
$env:TESTFORGE_WORKER_CAPABILITIES = 'RUNNER_PLAYWRIGHT_WEB,WEB_UI,PLATFORM_WINDOWS'
$env:TESTFORGE_WORKER_CONCURRENCY = '2'
python -m testforge_worker
```

Case 主脚本必须导出 `run(page, parameters)`。Worker 默认使用本机 Chrome 通道（可通过 `parameters.browserChannel` 改为已安装的其他 Playwright 浏览器），并把最终/失败截图、`trace.zip`、浏览器 Console/PageError 日志和结果 JSON 作为 Attempt Evidence 回传，因此本机 Worker 不依赖额外下载浏览器二进制。

## Worker-owned Sandbox

UI Worker 可通过 `TESTFORGE_SANDBOX_COMMAND_JSON` 托管一个 Sandbox 进程。命令必须是 JSON 字符串数组，不能使用 shell 拼接。Provider 收到一次性 `TEST_BRIDGE_READY` 后执行认证健康检查，随后才注册 Worker 和唯一 DeviceSlot；bridge token 只保存在 Worker 进程内并注入 Airtest 子进程。托管模式强制 `TESTFORGE_WORKER_CONCURRENCY=1`，真实并行通过在独立 VM/桌面 Host 中增加 Worker + Sandbox 实例获得。

相关变量：`TESTFORGE_ENVIRONMENT_ID`、`TESTFORGE_SANDBOX_CWD`、`TESTFORGE_SANDBOX_COMMAND_JSON`、`TESTFORGE_SANDBOX_STARTUP_TIMEOUT_SECONDS` 和 `TESTFORGE_SANDBOX_SHUTDOWN_TIMEOUT_SECONDS`。完整 Windows VM 示例和验收命令见 `acceptance/virtual-environment-parallel/README.md`。

完成回调使用 `attemptId` 派生稳定 `callbackKey`。HTTP 重试复用同一 JSON 载荷，
避免因重试生成新幂等事件；409/410 租约拒绝不会继续执行旧 Attempt。

默认的 `gateway` 后端由 Java Gateway 校验 Attempt 租约，并签发只允许
指定 objectKey、指定文件大小和 SHA-256 元数据的短期 OSS POST 策略。
Python Worker 直传 OSS，但不读取或保存 OSS AccessKey Secret。

本地兼容验收可设置 `TESTFORGE_ARTIFACT_BACKEND=minio`。Worker 会在首次上传时
确保 bucket 存在，并把回调中的 `objectKey` 指向已成功写入的对象；可通过
`TESTFORGE_MINIO_ENDPOINT`、`TESTFORGE_MINIO_ACCESS_KEY`、
`TESTFORGE_MINIO_SECRET_KEY` 和 `TESTFORGE_MINIO_BUCKET` 覆盖连接配置。

OSS 永久密钥只配置给 Java Gateway：`TESTFORGE_OSS_ENDPOINT`、
`TESTFORGE_OSS_BUCKET`、`TESTFORGE_OSS_PREFIX`、`TESTFORGE_OSS_ACCESS_KEY_ID` 和
`TESTFORGE_OSS_ACCESS_KEY_SECRET`。`scripts/dev.ps1 Backend` 会在启动 Gateway 时读取
平台根目录中被 Git 忽略的 `.env.local`；Worker 启动路径不再加载该文件。
