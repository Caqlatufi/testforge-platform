from __future__ import annotations

import os
import signal
import subprocess
import threading
import time
from collections.abc import Callable
from typing import Protocol

from .model import ProcessOutcome, ProcessSpec


class LogSink(Protocol):
    def write(self, attempt_id: str, chunk: bytes) -> None: ...


class NullLogSink:
    def write(self, attempt_id: str, chunk: bytes) -> None:
        del attempt_id, chunk


class SubprocessController:
    """跨平台管理单个 Runner 子进程及其进程组。"""

    def __init__(
        self,
        *,
        terminate_grace_seconds: float = 1.0,
        poll_interval_seconds: float = 0.05,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        if terminate_grace_seconds < 0 or poll_interval_seconds <= 0:
            raise ValueError("进程轮询参数非法")
        self._terminate_grace = terminate_grace_seconds
        self._poll_interval = poll_interval_seconds
        self._monotonic = monotonic

    def run(
        self,
        attempt_id: str,
        spec: ProcessSpec,
        *,
        timeout_seconds: float,
        heartbeat_interval_seconds: float,
        heartbeat: Callable[[], bool],
        log_sink: LogSink,
    ) -> ProcessOutcome:
        environment = os.environ.copy()
        environment.update(spec.environment)
        environment["PYTHONUNBUFFERED"] = "1"
        options: dict[str, object] = {
            "cwd": str(spec.cwd) if spec.cwd else None,
            "env": environment,
            "stdin": subprocess.DEVNULL,
            "stdout": subprocess.PIPE,
            "stderr": subprocess.STDOUT,
        }
        if os.name == "nt":
            options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            options["start_new_session"] = True

        started = self._monotonic()
        process = subprocess.Popen(list(spec.command), **options)  # type: ignore[arg-type]
        reader = threading.Thread(
            target=self._drain,
            args=(attempt_id, process, log_sink),
            name=f"attempt-{attempt_id}-log",
            daemon=True,
        )
        reader.start()
        next_heartbeat = started + heartbeat_interval_seconds
        timed_out = False
        cancelled = False
        try:
            while process.poll() is None:
                now = self._monotonic()
                if now - started >= timeout_seconds:
                    timed_out = True
                    self.terminate(process)
                    break
                if now >= next_heartbeat:
                    if heartbeat():
                        cancelled = True
                        self.terminate(process)
                        break
                    next_heartbeat = now + heartbeat_interval_seconds
                time.sleep(self._poll_interval)
            if process.poll() is None:
                process.wait()
        except BaseException:
            self.terminate(process)
            raise
        finally:
            reader.join(timeout=max(1.0, self._terminate_grace + 1.0))
        return ProcessOutcome(
            exit_code=process.returncode if process.returncode is not None else -1,
            duration_ms=round((self._monotonic() - started) * 1000),
            timed_out=timed_out,
            cancelled=cancelled,
        )

    def terminate(self, process: subprocess.Popen[bytes]) -> None:
        if process.poll() is not None:
            return
        try:
            if os.name == "nt":
                process.send_signal(signal.CTRL_BREAK_EVENT)
            else:
                os.killpg(process.pid, signal.SIGTERM)
        except (OSError, ValueError):
            process.terminate()
        try:
            process.wait(timeout=self._terminate_grace)
            return
        except subprocess.TimeoutExpired:
            pass
        try:
            if os.name == "nt":
                # terminate()/kill() 在 Windows 只作用于直接子进程；/T 保证 Runner
                # 拉起的 pytest/Airtest 辅助进程不会在取消或超时后泄漏。
                killed = subprocess.run(
                    ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                    check=False,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                if killed.returncode != 0 and process.poll() is None:
                    process.kill()
            else:
                os.killpg(process.pid, signal.SIGKILL)
        except OSError:
            process.kill()
        process.wait()

    @staticmethod
    def _drain(
        attempt_id: str,
        process: subprocess.Popen[bytes],
        log_sink: LogSink,
    ) -> None:
        if process.stdout is None:
            return
        try:
            while True:
                chunk = process.stdout.read(8192)
                if not chunk:
                    return
                log_sink.write(attempt_id, chunk)
        finally:
            process.stdout.close()
