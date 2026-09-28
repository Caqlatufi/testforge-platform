from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import tempfile
import threading
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path

from .generator import generate_http_case
from .junit import JUnitParseError, parse_junit_xml
from .model import (
    FailureType,
    HttpCaseSpec,
    JUnitCaseResult,
    JUnitSummary,
    PytestHttpFailure,
    PytestHttpResult,
    RunnerStatus,
)


@dataclass(frozen=True)
class _ProcessOutcome:
    exit_code: int
    duration_ms: int
    logs: str
    logs_truncated: bool
    timed_out: bool = False


class _BoundedLogCollector:
    """持续排空子进程输出，同时只保留定长的头尾证据。"""

    _MARKER = b"\n... TestForge log truncated ...\n"

    def __init__(self, limit_bytes: int) -> None:
        self._limit = limit_bytes
        self._head_limit = limit_bytes // 2
        self._tail_capacity = limit_bytes - self._head_limit
        self._head = bytearray()
        self._tail: deque[int] = deque(maxlen=self._tail_capacity)
        self._total = 0

    def add(self, chunk: bytes) -> None:
        self._total += len(chunk)
        head_missing = self._head_limit - len(self._head)
        if head_missing > 0:
            self._head.extend(chunk[:head_missing])
            chunk = chunk[head_missing:]
        self._tail.extend(chunk)

    def finish(self) -> tuple[str, bool]:
        truncated = self._total > self._limit
        if truncated:
            visible_tail = self._tail_capacity - len(self._MARKER)
            raw = bytes(self._head) + self._MARKER + bytes(self._tail)[-visible_tail:]
        else:
            raw = bytes(self._head) + bytes(self._tail)
        return raw.decode("utf-8", errors="ignore"), truncated


class PytestHttpRunner:
    """在每次调用的临时目录内生成并执行一个 pytest HTTP 用例。"""

    def __init__(
        self,
        *,
        python_executable: str | None = None,
        max_log_bytes: int = 64 * 1024,
        max_junit_bytes: int = 5 * 1024 * 1024,
        terminate_grace_seconds: float = 1.0,
    ) -> None:
        if max_log_bytes <= len(_BoundedLogCollector._MARKER) * 2:
            raise ValueError("max_log_bytes 太小，无法容纳截断标记")
        if max_junit_bytes <= 0:
            raise ValueError("max_junit_bytes 必须大于 0")
        if terminate_grace_seconds < 0:
            raise ValueError("terminate_grace_seconds 不能为负数")
        self._python = python_executable or sys.executable
        self._max_log_bytes = max_log_bytes
        self._max_junit_bytes = max_junit_bytes
        self._terminate_grace_seconds = terminate_grace_seconds

    def run(self, case: HttpCaseSpec, *, timeout_seconds: float) -> PytestHttpResult:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds 必须大于 0")
        with tempfile.TemporaryDirectory(prefix="testforge-pytest-http-") as temp_dir:
            work_dir = Path(temp_dir)
            test_path = generate_http_case(case, work_dir)
            return self._run_path(test_path, work_dir, timeout_seconds)

    def run_path(self, test_path: Path, *, timeout_seconds: float) -> PytestHttpResult:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds 必须大于 0")
        path = test_path.resolve()
        if not path.is_file():
            raise ValueError(f"pytest 脚本不存在: {path}")
        return self._run_path(path, path.parent, timeout_seconds)

    def _run_path(self, test_path: Path, work_dir: Path, timeout_seconds: float) -> PytestHttpResult:
        junit_path = work_dir / "junit.xml"
        started = time.monotonic()
        try:
            outcome = self._execute_pytest(test_path, junit_path, work_dir, timeout_seconds)
        except OSError as error:
            duration_ms = round((time.monotonic() - started) * 1000)
            return self._infra_result(
                    duration_ms=duration_ms,
                    message=f"无法启动 pytest: {error}",
                    details={"reason": "PROCESS_START_FAILED"},
                    retryable=True,
            )

        if outcome.timed_out:
            return self._infra_result(
                    duration_ms=outcome.duration_ms,
                    message=f"pytest 执行超过 {timeout_seconds:g} 秒硬超时",
                    details={"reason": "TIMEOUT", "timeoutSeconds": timeout_seconds},
                    retryable=True,
                    exit_code=outcome.exit_code,
                    logs=outcome.logs,
                    logs_truncated=outcome.logs_truncated,
            )

        try:
            with junit_path.open("rb") as junit_file:
                junit_xml = junit_file.read(self._max_junit_bytes + 1)
        except OSError as error:
            return self._infra_result(
                    duration_ms=outcome.duration_ms,
                    message=f"pytest 未生成可读 JUnit XML: {error}",
                    details={"reason": "JUNIT_MISSING"},
                    retryable=True,
                    exit_code=outcome.exit_code,
                    logs=outcome.logs,
                    logs_truncated=outcome.logs_truncated,
            )
        try:
            junit = parse_junit_xml(junit_xml, max_bytes=self._max_junit_bytes)
        except JUnitParseError as error:
            return self._infra_result(
                    duration_ms=outcome.duration_ms,
                    message=str(error),
                    details={"reason": "JUNIT_INVALID"},
                    retryable=True,
                    exit_code=outcome.exit_code,
                    junit_xml=junit_xml,
                    logs=outcome.logs,
                    logs_truncated=outcome.logs_truncated,
            )
        return self._map_result(outcome, junit_xml, junit)

    def _execute_pytest(
        self,
        test_path: Path,
        junit_path: Path,
        work_dir: Path,
        timeout_seconds: float,
    ) -> _ProcessOutcome:
        command = [
            self._python,
            "-m",
            "pytest",
            "-q",
            "--disable-warnings",
            "--junitxml",
            str(junit_path),
            str(test_path),
        ]
        process_options: dict[str, object] = {
            "cwd": str(work_dir),
            "stdin": subprocess.DEVNULL,
            "stdout": subprocess.PIPE,
            "stderr": subprocess.STDOUT,
            "env": self._subprocess_environment(),
        }
        if os.name == "nt":
            process_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            process_options["start_new_session"] = True
        started = time.monotonic()
        process = subprocess.Popen(command, **process_options)  # type: ignore[arg-type]
        collector = _BoundedLogCollector(self._max_log_bytes)
        reader = threading.Thread(
            target=self._drain_output,
            args=(process, collector),
            name="pytest-http-log-reader",
            daemon=True,
        )
        reader.start()
        timed_out = False
        try:
            process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            self._terminate(process)
        reader.join(timeout=max(1.0, self._terminate_grace_seconds + 1.0))
        logs, logs_truncated = collector.finish()
        return _ProcessOutcome(
            exit_code=process.returncode if process.returncode is not None else -1,
            duration_ms=round((time.monotonic() - started) * 1000),
            logs=logs,
            logs_truncated=logs_truncated,
            timed_out=timed_out,
        )

    @staticmethod
    def _drain_output(process: subprocess.Popen[bytes], collector: _BoundedLogCollector) -> None:
        if process.stdout is None:
            return
        try:
            while True:
                chunk = process.stdout.read(8192)
                if not chunk:
                    return
                collector.add(chunk)
        finally:
            process.stdout.close()

    @staticmethod
    def _subprocess_environment() -> dict[str, str]:
        environment = os.environ.copy()
        environment["PYTHONUNBUFFERED"] = "1"
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        environment.pop("PYTEST_ADDOPTS", None)
        return environment

    def _terminate(self, process: subprocess.Popen[bytes]) -> None:
        process.terminate()
        try:
            process.wait(timeout=self._terminate_grace_seconds)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

    def _map_result(
        self,
        outcome: _ProcessOutcome,
        junit_xml: bytes,
        junit: JUnitSummary,
    ) -> PytestHttpResult:
        failing_cases = tuple(
            case for case in junit.cases if case.outcome in {"failure", "error"}
        )
        if outcome.exit_code == 0 and not failing_cases:
            return PytestHttpResult(
                status=RunnerStatus.PASSED,
                duration_ms=outcome.duration_ms,
                summary=self._summary(junit),
                exit_code=outcome.exit_code,
                junit=junit,
                junit_xml=junit_xml,
                logs=outcome.logs,
                logs_truncated=outcome.logs_truncated,
            )

        failure_type = self._classify(outcome.exit_code, failing_cases)
        status = (
            RunnerStatus.ASSERTION_FAILED
            if failure_type is FailureType.PRODUCT_DEFECT
            else RunnerStatus.INFRA_FAILED
        )
        evidence = next(iter(failing_cases), None)
        message = self._failure_message(evidence, outcome.exit_code)
        stack_digest = None
        if evidence and evidence.details:
            stack_digest = hashlib.sha256(evidence.details.encode("utf-8")).hexdigest()
        return PytestHttpResult(
            status=status,
            duration_ms=outcome.duration_ms,
            summary=self._summary(junit),
            exit_code=outcome.exit_code,
            junit=junit,
            junit_xml=junit_xml,
            logs=outcome.logs,
            logs_truncated=outcome.logs_truncated,
            failure=PytestHttpFailure(
                type=failure_type,
                message=message,
                stack_digest=stack_digest,
                retryable=failure_type is FailureType.ENVIRONMENT,
                details={
                    "reason": "PYTEST_FAILURE",
                    "case": evidence.name if evidence else None,
                    "exceptionType": evidence.exception_type if evidence else None,
                },
            ),
        )

    @staticmethod
    def _classify(exit_code: int, failures: tuple[JUnitCaseResult, ...]) -> FailureType:
        combined = " ".join(
            filter(
                None,
                (
                    f"{case.exception_type or ''} {case.message or ''} {case.details or ''}"
                    for case in failures
                ),
            )
        ).lower()
        environment_markers = (
            "urlerror",
            "connectionerror",
            "connectionrefusederror",
            "connecttimeout",
            "readtimeout",
            "socket.timeout",
            "timed out",
            "temporary failure in name resolution",
            "name or service not known",
        )
        if any(marker in combined for marker in environment_markers):
            return FailureType.ENVIRONMENT
        if failures and all(
            case.outcome == "failure"
            and "assertionerror"
            in f"{case.exception_type or ''} {case.message or ''} {case.details or ''}".lower()
            for case in failures
        ):
            return FailureType.PRODUCT_DEFECT
        if failures or exit_code in {2, 4, 5}:
            return FailureType.SCRIPT_ERROR
        return FailureType.ENVIRONMENT

    @staticmethod
    def _failure_message(case: JUnitCaseResult | None, exit_code: int) -> str:
        if case is None:
            return f"pytest 异常退出，exitCode={exit_code}"
        message = case.message or case.exception_type or f"用例 {case.name} 执行失败"
        return message[:4000]

    @staticmethod
    def _summary(junit: JUnitSummary) -> str:
        passed = junit.tests - junit.failures - junit.errors - junit.skipped
        return (
            f"pytest: {junit.tests} tests, {passed} passed, "
            f"{junit.failures} failed, {junit.errors} errors, {junit.skipped} skipped"
        )

    @staticmethod
    def _infra_result(
        *,
        duration_ms: int,
        message: str,
        details: dict[str, object],
        retryable: bool,
        exit_code: int | None = None,
        junit_xml: bytes | None = None,
        logs: str = "",
        logs_truncated: bool = False,
    ) -> PytestHttpResult:
        return PytestHttpResult(
            status=RunnerStatus.INFRA_FAILED,
            duration_ms=duration_ms,
            summary=message[:2000],
            exit_code=exit_code,
            junit=None,
            junit_xml=junit_xml,
            logs=logs,
            logs_truncated=logs_truncated,
            failure=PytestHttpFailure(
                type=FailureType.ENVIRONMENT,
                message=message[:4000],
                retryable=retryable,
                details=details,
            ),
        )
