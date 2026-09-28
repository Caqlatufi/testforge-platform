# HTTP 验收夹具

本目录为 HTTP 执行、超时、失败和 Flaky 验收提供可控的被测接口。它归属 `acceptance`，按需启动，不参与平台模块构建、打包或日常启动。

从平台仓库根目录执行：

```powershell
cd acceptance/fixtures/http
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests
.\.venv\Scripts\python.exe -m uvicorn http_fixture.app:app --host 127.0.0.1 --port 8090
```

Linux/macOS 使用 `.venv/bin/python` 替换上述 Python 路径。

| 接口 | 验收用途 |
| --- | --- |
| `GET /health` | 就绪检查 |
| `GET /api/v1/scenarios/success` | 正常响应与结果回传 |
| `GET /api/v1/scenarios/slow?delay_ms=250` | 延迟响应，范围 0–5000 ms |
| `GET /api/v1/scenarios/error` | 固定返回结构化 500 |
| `GET /api/v1/scenarios/flaky?failure_rate=0.5&seed=42` | 可复现的概率结果；不传 seed 时随机 |
| `GET /api/v1/state` | 当前进程请求和成功/失败计数 |

Worker 位于同一主机时使用 `http://127.0.0.1:8090`；VM 验收时显式选择宿主机可达地址并限制在受控网络。重启夹具会清空内存计数。

夹具不创建平台 Project、Case 或 Workflow。验收数据通过平台当前公开 API 准备；旧 Suite/Run 演示导入器已移除。这里的单元测试仅证明夹具行为，真实平台验收仍需执行任务、回调和报告断言。
