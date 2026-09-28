from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping, Sequence


@dataclass(frozen=True)
class CallbackUrls:
    start: str
    heartbeat: str
    complete: str
    artifact_upload: str


@dataclass(frozen=True)
class TaskEnvelope:
    """Worker 执行所需的稳定契约视图，原始 execution 留给 Runner Adapter。"""

    message_id: str
    task_id: str
    run_id: str
    attempt_id: str
    lease_token: str
    timeout_seconds: float
    execution: Mapping[str, Any]
    callbacks: CallbackUrls
    traceparent: str

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "TaskEnvelope":
        execution = _mapping(payload, "execution")
        callbacks = _mapping(payload, "callbacks")
        timeout = execution.get("timeoutSeconds")
        if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or timeout <= 0:
            raise ValueError("execution.timeoutSeconds 必须大于 0")
        runner = execution.get("runner")
        resource_mode = execution.get("resourceMode")
        expected_mode = "EXCLUSIVE_DEVICE" if runner == "airtest" else "PROCESS_POOL"
        if resource_mode is not None and resource_mode != expected_mode:
            raise ValueError("execution.resourceMode 与 runner 不匹配")
        return cls(
            message_id=_text(payload, "messageId"),
            task_id=_text(payload, "taskId"),
            run_id=_text(payload, "runId"),
            attempt_id=_text(payload, "attemptId"),
            lease_token=_text(payload, "leaseToken"),
            timeout_seconds=float(timeout),
            execution=dict(execution),
            callbacks=CallbackUrls(
                start=_text(callbacks, "startUrl"),
                heartbeat=_text(callbacks, "heartbeatUrl"),
                complete=_text(callbacks, "completeUrl"),
                artifact_upload=_text(callbacks, "artifactUploadUrl"),
            ),
            traceparent=_text(payload, "traceparent"),
        )


@dataclass(frozen=True)
class ProcessSpec:
    """Runner Adapter 只负责产生命令；进程控制由 runtime 统一负责。"""

    command: Sequence[str]
    cwd: Path | None = None
    environment: Mapping[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.command or any(not str(part) for part in self.command):
            raise ValueError("子进程命令不能为空")


@dataclass(frozen=True)
class ProcessOutcome:
    exit_code: int
    duration_ms: int
    timed_out: bool = False
    cancelled: bool = False


@dataclass(frozen=True)
class AttemptFailure:
    type: str
    message: str
    retryable: bool = False
    stack_digest: str | None = None
    details: Mapping[str, Any] = field(default_factory=dict)

    def as_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "type": self.type,
            "message": self.message[:4000],
            "retryable": self.retryable,
            "details": dict(self.details),
        }
        if self.stack_digest:
            payload["stackDigest"] = self.stack_digest
        return payload


@dataclass(frozen=True)
class AttemptResult:
    status: str
    duration_ms: int
    summary: str
    failure: AttemptFailure | None = None
    artifacts: tuple[Mapping[str, Any], ...] = ()
    metrics: Mapping[str, float] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.status == "PASSED" and self.failure is not None:
            raise ValueError("PASSED 结果不能包含 failure")
        if self.status != "PASSED" and self.failure is None:
            raise ValueError("非 PASSED 结果必须包含 failure")
        if self.duration_ms < 0:
            raise ValueError("duration_ms 不能为负数")
        if not self.summary:
            raise ValueError("summary 不能为空")


def _mapping(payload: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    value = payload.get(key)
    if not isinstance(value, Mapping):
        raise ValueError(f"{key} 必须是对象")
    return value


def _text(payload: Mapping[str, Any], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key} 必须是非空字符串")
    return value
