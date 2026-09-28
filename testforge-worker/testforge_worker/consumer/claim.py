from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from testforge_worker.runtime.model import TaskEnvelope

from .stream import StreamDelivery


class ClaimDeferred(RuntimeError):
    """控制面暂时没有配额或设备，保留消息等待稍后重领。"""


class GatewayTaskResolver:
    """用 Redis TASK_READY 的稳定身份向控制面原子领取完整 Attempt。"""

    def __init__(self, gateway_base_url: str, worker_id: str, timeout_seconds: float = 5.0):
        self._base = gateway_base_url.rstrip("/")
        self._worker_id = worker_id
        self._timeout = timeout_seconds

    def __call__(self, delivery: StreamDelivery) -> TaskEnvelope:
        task_id = _text(delivery.payload, "taskId")
        body = {
            "runId": _text(delivery.payload, "runId"),
            "messageId": _text(delivery.payload, "messageId"),
            "workerId": self._worker_id,
        }
        request = Request(
            f"{self._base}/api/v1/tasks/{task_id}/claim",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=self._timeout) as response:
                decoded = json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            if error.code in {423, 429}:
                raise ClaimDeferred(f"Task 暂时不可领取，HTTP {error.code}") from error
            if error.code in {409, 410}:
                from testforge_worker.callback.client import LeaseRejected

                raise LeaseRejected(f"Task 已由其他 Worker 领取，HTTP {error.code}") from error
            raise RuntimeError(f"Task claim 失败，HTTP {error.code}") from error
        except URLError as error:
            raise RuntimeError(f"Task claim 连接失败: {error.reason}") from error
        if not isinstance(decoded, Mapping):
            raise ValueError("Task claim 响应必须是 JSON 对象")
        payload: Any = decoded.get("data", decoded)
        if not isinstance(payload, Mapping):
            raise ValueError("Task claim 响应缺少 data")
        return TaskEnvelope.from_payload(payload)


def _text(payload: Mapping[str, Any], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value:
        raise ValueError(f"TASK_READY 缺少 {key}")
    return value
