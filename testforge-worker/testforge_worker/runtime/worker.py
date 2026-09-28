from __future__ import annotations

import threading
import uuid
from typing import Protocol

from testforge_worker.callback.client import GatewayClient, LeaseRejected

from .model import AttemptFailure, AttemptResult, ProcessOutcome, ProcessSpec, TaskEnvelope
from .process import LogSink, NullLogSink, SubprocessController


class RunnerAdapter(Protocol):
    """TFP-014 只需把具体 Runner 适配到这两个方法。"""

    def prepare(self, task: TaskEnvelope) -> ProcessSpec: ...

    def result(self, task: TaskEnvelope, outcome: ProcessOutcome) -> AttemptResult: ...


class DuplicateInFlight(RuntimeError):
    pass


class WorkerRuntime:
    def __init__(
        self,
        *,
        worker_id: str,
        gateway: GatewayClient,
        process_controller: SubprocessController | None = None,
        log_sink: LogSink | None = None,
        heartbeat_interval_seconds: float = 5.0,
    ) -> None:
        if not worker_id:
            raise ValueError("worker_id 不能为空")
        if heartbeat_interval_seconds <= 0:
            raise ValueError("heartbeat_interval_seconds 必须大于 0")
        self._worker_id = worker_id
        self._gateway = gateway
        self._processes = process_controller or SubprocessController()
        self._logs = log_sink or NullLogSink()
        self._heartbeat_interval = heartbeat_interval_seconds
        self._active: set[str] = set()
        self._active_lock = threading.Lock()

    def execute(self, task: TaskEnvelope, runner: RunnerAdapter) -> AttemptResult:
        self._enter(task.attempt_id)
        try:
            self._gateway.start(task, self._worker_id)
            try:
                spec = runner.prepare(task)
                outcome = self._processes.run(
                    task.attempt_id,
                    spec,
                    timeout_seconds=task.timeout_seconds,
                    heartbeat_interval_seconds=self._heartbeat_interval,
                    heartbeat=lambda: self._heartbeat(task),
                    log_sink=self._logs,
                )
                result = self._to_result(task, runner, outcome)
            except LeaseRejected:
                raise
            except OSError as error:
                result = AttemptResult(
                    status="INFRA_FAILED",
                    duration_ms=0,
                    summary=f"Runner 子进程启动失败: {error}"[:2000],
                    failure=AttemptFailure(
                        type="ENVIRONMENT",
                        message=f"Runner 子进程启动失败: {error}",
                        retryable=True,
                        details={"reason": "PROCESS_START_FAILED"},
                    ),
                )
            callback_key = str(
                uuid.uuid5(uuid.NAMESPACE_URL, f"testforge:attempt:{task.attempt_id}:complete")
            )
            self._gateway.complete(task, self._worker_id, callback_key, result)
            return result
        finally:
            self._leave(task.attempt_id)

    def _heartbeat(self, task: TaskEnvelope) -> bool:
        decision = self._gateway.heartbeat(task, self._worker_id)
        return decision.cancel_requested

    @staticmethod
    def _to_result(
        task: TaskEnvelope, runner: RunnerAdapter, outcome: ProcessOutcome
    ) -> AttemptResult:
        if outcome.cancelled:
            return AttemptResult(
                status="CANCELLED",
                duration_ms=outcome.duration_ms,
                summary="控制面请求取消，Runner 子进程已终止",
                failure=AttemptFailure(
                    type="CANCELLED",
                    message="Attempt 已取消",
                    retryable=False,
                    details={"reason": "CANCEL_REQUESTED"},
                ),
            )
        if outcome.timed_out:
            return AttemptResult(
                status="INFRA_FAILED",
                duration_ms=outcome.duration_ms,
                summary=f"Runner 执行超过 {task.timeout_seconds:g} 秒硬超时",
                failure=AttemptFailure(
                    type="ENVIRONMENT",
                    message="Runner 子进程硬超时",
                    retryable=True,
                    details={
                        "reason": "TIMEOUT",
                        "timeoutSeconds": task.timeout_seconds,
                        "exitCode": outcome.exit_code,
                    },
                ),
            )
        try:
            mapped = runner.result(task, outcome)
            if outcome.exit_code != 0 and mapped.status == "PASSED":
                return AttemptResult(
                    status="INFRA_FAILED",
                    duration_ms=outcome.duration_ms,
                    summary=f"Runner 进程异常退出，exitCode={outcome.exit_code}",
                    failure=AttemptFailure(
                        type="SCRIPT_ERROR",
                        message=f"Runner 进程异常退出，exitCode={outcome.exit_code}",
                        retryable=False,
                        details={
                            "reason": "PROCESS_EXIT",
                            "exitCode": outcome.exit_code,
                        },
                    ),
                )
            return mapped
        except Exception as error:
            return AttemptResult(
                status="INFRA_FAILED",
                duration_ms=outcome.duration_ms,
                summary=f"Runner 结果解析失败: {error}"[:2000],
                failure=AttemptFailure(
                    type="SCRIPT_ERROR",
                    message=f"Runner 结果解析失败: {error}",
                    retryable=False,
                    details={
                        "reason": "RESULT_MAPPING_FAILED",
                        "exitCode": outcome.exit_code,
                    },
                ),
            )

    def _enter(self, attempt_id: str) -> None:
        with self._active_lock:
            if attempt_id in self._active:
                raise DuplicateInFlight(f"Attempt {attempt_id} 已在当前 Worker 执行")
            self._active.add(attempt_id)

    def _leave(self, attempt_id: str) -> None:
        with self._active_lock:
            self._active.discard(attempt_id)
