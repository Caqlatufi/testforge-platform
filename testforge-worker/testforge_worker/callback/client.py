from __future__ import annotations

import json
import random
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Mapping, Protocol
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from testforge_worker.runtime.model import AttemptResult, TaskEnvelope


class GatewayError(RuntimeError):
    pass


class NonRetryableGatewayError(GatewayError):
    pass


class LeaseRejected(GatewayError):
    """控制面拒绝当前租约；Worker 必须停止或跳过该 Attempt。"""


class RetryExhausted(GatewayError):
    pass


@dataclass(frozen=True)
class HeartbeatDecision:
    cancel_requested: bool = False


class GatewayClient(Protocol):
    def start(self, task: TaskEnvelope, worker_id: str) -> None: ...

    def heartbeat(
        self, task: TaskEnvelope, worker_id: str, progress: float | None = None
    ) -> HeartbeatDecision: ...

    def complete(
        self,
        task: TaskEnvelope,
        worker_id: str,
        callback_key: str,
        result: AttemptResult,
    ) -> Mapping[str, Any]: ...


Transport = Callable[[str, Mapping[str, Any], Mapping[str, str], float], Mapping[str, Any]]


class HttpGatewayClient:
    """小型幂等 HTTP 客户端；同一次 complete 重试始终复用原 payload。"""

    def __init__(
        self,
        *,
        transport: Transport | None = None,
        request_timeout_seconds: float = 5.0,
        max_attempts: int = 4,
        base_backoff_seconds: float = 0.2,
        sleep: Callable[[float], None] = time.sleep,
        random_value: Callable[[], float] = random.random,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts 必须至少为 1")
        self._transport = transport or _urllib_transport
        self._timeout = request_timeout_seconds
        self._max_attempts = max_attempts
        self._base_backoff = base_backoff_seconds
        self._sleep = sleep
        self._random = random_value

    def start(self, task: TaskEnvelope, worker_id: str) -> None:
        self._post_with_retry(
            task.callbacks.start,
            {"workerId": worker_id, "leaseToken": task.lease_token},
            task,
        )

    def heartbeat(
        self, task: TaskEnvelope, worker_id: str, progress: float | None = None
    ) -> HeartbeatDecision:
        body: dict[str, Any] = {
            "workerId": worker_id,
            "leaseToken": task.lease_token,
            "at": _utc_now(),
            "progress": progress if progress is not None else 0.0,
        }
        response = self._post_with_retry(task.callbacks.heartbeat, body, task)
        data = response.get("data")
        if isinstance(data, Mapping):
            return HeartbeatDecision(bool(data.get("cancelRequested", False)))
        return HeartbeatDecision(bool(response.get("cancelRequested", False)))

    def complete(
        self,
        task: TaskEnvelope,
        worker_id: str,
        callback_key: str,
        result: AttemptResult,
    ) -> Mapping[str, Any]:
        body: dict[str, Any] = {
            "schemaVersion": "1.0.0",
            "callbackKey": callback_key,
            "attemptId": task.attempt_id,
            "leaseToken": task.lease_token,
            "workerId": worker_id,
            "status": result.status,
            "completedAt": _utc_now(),
            "durationMs": result.duration_ms,
            "summary": result.summary[:2000],
            "artifacts": [dict(item) for item in result.artifacts],
        }
        if result.failure is not None:
            body["failure"] = result.failure.as_payload()
        if result.metrics:
            body["metrics"] = dict(result.metrics)
        return self._post_with_retry(task.callbacks.complete, body, task)

    def _post_with_retry(
        self, url: str, body: Mapping[str, Any], task: TaskEnvelope
    ) -> Mapping[str, Any]:
        headers = {
            "Content-Type": "application/json",
            "traceparent": task.traceparent,
        }
        last_error: Exception | None = None
        for attempt in range(1, self._max_attempts + 1):
            try:
                return self._transport(url, body, headers, self._timeout)
            except (LeaseRejected, NonRetryableGatewayError):
                raise
            except (GatewayError, OSError) as error:
                last_error = error
                if attempt < self._max_attempts:
                    delay = self._base_backoff * (2 ** (attempt - 1))
                    self._sleep(delay * (0.5 + self._random()))
        raise RetryExhausted(
            f"Gateway 请求重试 {self._max_attempts} 次仍失败: {last_error}"
        ) from last_error


def _urllib_transport(
    url: str,
    body: Mapping[str, Any],
    headers: Mapping[str, str],
    timeout_seconds: float,
) -> Mapping[str, Any]:
    request = Request(
        url,
        data=json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        headers=dict(headers),
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            raw = response.read()
    except HTTPError as error:
        if error.code in {409, 410}:
            error_code = _http_error_code(error)
            if error_code == "IDEMPOTENCY_CONFLICT":
                raise NonRetryableGatewayError(
                    "Gateway 拒绝完成回调：callbackKey 对应不同载荷"
                ) from error
            raise LeaseRejected(f"Gateway 拒绝租约，HTTP {error.code}") from error
        if error.code < 500 and error.code not in {408, 429}:
            raise NonRetryableGatewayError(
                f"Gateway 请求不可重试，HTTP {error.code}"
            ) from error
        raise GatewayError(f"Gateway 暂时不可用，HTTP {error.code}") from error
    except URLError as error:
        raise GatewayError(f"Gateway 连接失败: {error.reason}") from error
    if not raw:
        return {}
    decoded = json.loads(raw.decode("utf-8"))
    if not isinstance(decoded, Mapping):
        raise GatewayError("Gateway 响应必须是 JSON 对象")
    return decoded


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _http_error_code(error: HTTPError) -> str | None:
    try:
        decoded = json.loads(error.read().decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(decoded, Mapping):
        return None
    code = decoded.get("code")
    return code if isinstance(code, str) else None
