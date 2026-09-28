from __future__ import annotations

import sys
import unittest
from collections.abc import Mapping
from typing import Any

from testforge_worker.callback.client import HeartbeatDecision
from testforge_worker.runtime.model import (
    AttemptFailure,
    AttemptResult,
    CallbackUrls,
    ProcessOutcome,
    ProcessSpec,
    TaskEnvelope,
)
from testforge_worker.runtime.process import SubprocessController
from testforge_worker.runtime.worker import WorkerRuntime


def task(timeout_seconds: float = 2.0) -> TaskEnvelope:
    return TaskEnvelope(
        message_id="40000000-0000-4000-8000-000000000001",
        task_id="50000000-0000-4000-8000-000000000001",
        run_id="30000000-0000-4000-8000-000000000001",
        attempt_id="20000000-0000-4000-8000-000000000001",
        lease_token="60000000-0000-4000-8000-000000000001",
        timeout_seconds=timeout_seconds,
        execution={"runner": "stub"},
        callbacks=CallbackUrls("start", "heartbeat", "complete", "artifacts"),
        traceparent="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    )


class FakeGateway:
    def __init__(self, *, cancel_after: int | None = None) -> None:
        self.starts = 0
        self.heartbeats = 0
        self.completions: list[tuple[str, AttemptResult]] = []
        self.cancel_after = cancel_after

    def start(self, task: TaskEnvelope, worker_id: str) -> None:
        del task, worker_id
        self.starts += 1

    def heartbeat(
        self, task: TaskEnvelope, worker_id: str, progress: float | None = None
    ) -> HeartbeatDecision:
        del task, worker_id, progress
        self.heartbeats += 1
        return HeartbeatDecision(
            self.cancel_after is not None and self.heartbeats >= self.cancel_after
        )

    def complete(
        self,
        task: TaskEnvelope,
        worker_id: str,
        callback_key: str,
        result: AttemptResult,
    ) -> Mapping[str, Any]:
        del task, worker_id
        self.completions.append((callback_key, result))
        return {"accepted": True}


class CapturingLogs:
    def __init__(self) -> None:
        self.value = bytearray()

    def write(self, attempt_id: str, chunk: bytes) -> None:
        del attempt_id
        self.value.extend(chunk)


class CommandAdapter:
    def __init__(self, code: str) -> None:
        self.code = code

    def prepare(self, task: TaskEnvelope) -> ProcessSpec:
        del task
        return ProcessSpec((sys.executable, "-c", self.code))

    def result(self, task: TaskEnvelope, outcome: ProcessOutcome) -> AttemptResult:
        del task
        if outcome.exit_code == 0:
            return AttemptResult("PASSED", outcome.duration_ms, "ok")
        return AttemptResult(
            "INFRA_FAILED",
            outcome.duration_ms,
            f"进程异常退出: {outcome.exit_code}",
            AttemptFailure(
                "SCRIPT_ERROR",
                f"exitCode={outcome.exit_code}",
                details={"reason": "PROCESS_EXIT", "exitCode": outcome.exit_code},
            ),
        )


class WorkerRuntimeTest(unittest.TestCase):
    def runtime(self, gateway: FakeGateway, logs: CapturingLogs | None = None) -> WorkerRuntime:
        return WorkerRuntime(
            worker_id="worker-test-1",
            gateway=gateway,
            process_controller=SubprocessController(
                terminate_grace_seconds=0.2, poll_interval_seconds=0.01
            ),
            log_sink=logs,
            heartbeat_interval_seconds=0.03,
        )

    def test_streams_logs_and_completes_successfully(self) -> None:
        gateway = FakeGateway()
        logs = CapturingLogs()
        result = self.runtime(gateway, logs).execute(
            task(), CommandAdapter("print('runtime-log', flush=True)")
        )

        self.assertEqual("PASSED", result.status)
        self.assertIn(b"runtime-log", logs.value)
        self.assertEqual(1, gateway.starts)
        self.assertEqual(1, len(gateway.completions))

    def test_cancel_terminates_process_and_callbacks_cancelled(self) -> None:
        gateway = FakeGateway(cancel_after=1)
        result = self.runtime(gateway).execute(
            task(), CommandAdapter("import time; time.sleep(10)")
        )

        self.assertEqual("CANCELLED", result.status)
        self.assertEqual("CANCELLED", result.failure.type)
        self.assertLess(result.duration_ms, 3000)

    def test_timeout_terminates_process_and_reports_retryable_failure(self) -> None:
        gateway = FakeGateway()
        result = self.runtime(gateway).execute(
            task(timeout_seconds=0.08), CommandAdapter("import time; time.sleep(10)")
        )

        self.assertEqual("INFRA_FAILED", result.status)
        self.assertEqual("TIMEOUT", result.failure.details["reason"])
        self.assertTrue(result.failure.retryable)

    def test_abnormal_exit_is_mapped_by_runner_contract(self) -> None:
        gateway = FakeGateway()
        result = self.runtime(gateway).execute(task(), CommandAdapter("raise SystemExit(7)"))

        self.assertEqual("INFRA_FAILED", result.status)
        self.assertEqual(7, result.failure.details["exitCode"])

    def test_process_start_failure_is_reported_and_completed(self) -> None:
        class MissingCommandAdapter(CommandAdapter):
            def prepare(self, task: TaskEnvelope) -> ProcessSpec:
                del task
                return ProcessSpec(("definitely-not-a-testforge-command",))

        gateway = FakeGateway()
        result = self.runtime(gateway).execute(task(), MissingCommandAdapter(""))

        self.assertEqual("INFRA_FAILED", result.status)
        self.assertEqual("PROCESS_START_FAILED", result.failure.details["reason"])
        self.assertEqual(1, len(gateway.completions))

    def test_callback_key_is_stable_for_attempt(self) -> None:
        first_gateway = FakeGateway()
        second_gateway = FakeGateway()
        self.runtime(first_gateway).execute(task(), CommandAdapter("pass"))
        self.runtime(second_gateway).execute(task(), CommandAdapter("pass"))

        self.assertEqual(first_gateway.completions[0][0], second_gateway.completions[0][0])


if __name__ == "__main__":
    unittest.main()
