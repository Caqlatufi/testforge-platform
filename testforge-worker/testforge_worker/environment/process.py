from __future__ import annotations

import json
import os
import subprocess
import threading
import time
from collections import deque
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from testforge_worker.executor.spi import AutomationPlatform
from testforge_worker.runtime.process import SubprocessController

from .spi import SandboxEnvironmentProvider, SandboxSession


_READY_PREFIX = "TEST_BRIDGE_READY "


@dataclass(frozen=True)
class ProcessSandboxConfig:
    environment_id: str
    platform: AutomationPlatform
    device_id: str
    device_uri: str
    features: tuple[str, ...]
    resolution: str
    command: tuple[str, ...]
    cwd: Path | None = None
    startup_timeout_seconds: float = 30.0
    shutdown_timeout_seconds: float = 3.0
    environment: Mapping[str, str] = field(default_factory=dict, repr=False)

    def __post_init__(self) -> None:
        if not self.command or any(not item for item in self.command):
            raise ValueError("Sandbox command 必须是非空字符串数组")
        if self.startup_timeout_seconds <= 0 or self.shutdown_timeout_seconds < 0:
            raise ValueError("Sandbox 启停超时配置非法")


class SandboxStartupError(RuntimeError):
    pass


class ProcessSandboxProvider(SandboxEnvironmentProvider):
    """Own one local sandbox process inside a VM or device host."""

    def __init__(self, config: ProcessSandboxConfig) -> None:
        self._config = config
        self._lock = threading.RLock()
        self._ready = threading.Event()
        self._process: subprocess.Popen[bytes] | None = None
        self._reader: threading.Thread | None = None
        self._ready_payload: dict[str, str] | None = None
        self._startup_error: str | None = None
        self._recent_output: deque[str] = deque(maxlen=50)
        self._session: SandboxSession | None = None
        self._controller = SubprocessController(
            terminate_grace_seconds=config.shutdown_timeout_seconds
        )

    def start(self) -> SandboxSession:
        with self._lock:
            if self._process is not None and self._process.poll() is None:
                raise SandboxStartupError("Sandbox Provider 已拥有运行中进程")
            self._ready.clear()
            self._ready_payload = None
            self._startup_error = None
            self._recent_output.clear()
            environment = os.environ.copy()
            environment.update(self._config.environment)
            environment["PYTHONUNBUFFERED"] = "1"
            options: dict[str, object] = {
                "cwd": str(self._config.cwd) if self._config.cwd else None,
                "env": environment,
                "stdin": subprocess.DEVNULL,
                "stdout": subprocess.PIPE,
                "stderr": subprocess.STDOUT,
            }
            if os.name == "nt":
                options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
            else:
                options["start_new_session"] = True
            try:
                self._process = subprocess.Popen(  # type: ignore[arg-type]
                    list(self._config.command), **options
                )
            except OSError as error:
                raise SandboxStartupError(f"Sandbox 进程启动失败: {error}") from error
            self._reader = threading.Thread(
                target=self._drain_output,
                args=(self._process,),
                daemon=True,
                name=f"sandbox-{self._config.environment_id}-output",
            )
            self._reader.start()

        deadline = time.monotonic() + self._config.startup_timeout_seconds
        try:
            while not self._ready.wait(0.05):
                process = self._process
                if process is None or process.poll() is not None:
                    raise SandboxStartupError(self._failure_message("Sandbox 在就绪前退出"))
                if time.monotonic() >= deadline:
                    raise SandboxStartupError(self._failure_message("等待 Sandbox 就绪超时"))
            if self._startup_error:
                raise SandboxStartupError(self._failure_message(self._startup_error))
            payload = self._ready_payload or {}
            origin = _validated_loopback_origin(payload.get("origin"))
            token = str(payload.get("token") or "")
            if not token:
                raise SandboxStartupError("Sandbox READY 缺少 token")
            process = self._process
            if process is None:
                raise SandboxStartupError("Sandbox 进程状态丢失")
            session = SandboxSession(
                environment_id=self._config.environment_id,
                platform=self._config.platform,
                device_id=self._config.device_id,
                device_uri=self._config.device_uri,
                features=self._config.features,
                resolution=self._config.resolution,
                process_id=process.pid,
                runtime_environment={
                    "TEST_BRIDGE_ORIGIN": origin,
                    "TEST_BRIDGE_TOKEN": token,
                    "TESTFORGE_DEVICE_URI": self._config.device_uri,
                },
            )
            while time.monotonic() < deadline:
                if self.health(session):
                    with self._lock:
                        self._session = session
                    return session
                time.sleep(0.05)
            raise SandboxStartupError(self._failure_message("Sandbox 健康检查超时"))
        except BaseException:
            self._stop_process()
            raise

    def health(self, session: SandboxSession) -> bool:
        with self._lock:
            process = self._process
            if process is None or process.poll() is not None:
                return False
            if session.process_id != process.pid:
                return False
        origin = session.runtime_environment.get("TEST_BRIDGE_ORIGIN", "")
        token = session.runtime_environment.get("TEST_BRIDGE_TOKEN", "")
        if not origin or not token:
            return False
        request = Request(
            f"{origin}/test/health",
            headers={"Authorization": f"Bearer {token}"},
            method="GET",
        )
        try:
            with urlopen(request, timeout=1) as response:
                payload: Any = json.loads(response.read().decode("utf-8"))
            return response.status == 200 and isinstance(payload, dict)
        except (OSError, ValueError, json.JSONDecodeError):
            return False

    def stop(self, session: SandboxSession) -> None:
        with self._lock:
            if self._session is not None and session.process_id != self._session.process_id:
                return
        self._stop_process()

    @property
    def recent_output(self) -> tuple[str, ...]:
        with self._lock:
            token = "" if self._session is None else self._session.runtime_environment.get(
                "TEST_BRIDGE_TOKEN", ""
            )
            return tuple(line.replace(token, "<redacted>") if token else line for line in self._recent_output)

    def _drain_output(self, process: subprocess.Popen[bytes]) -> None:
        if process.stdout is None:
            return
        try:
            for raw in iter(process.stdout.readline, b""):
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                if line.startswith(_READY_PREFIX):
                    try:
                        payload = json.loads(line[len(_READY_PREFIX):])
                        if not isinstance(payload, dict):
                            raise ValueError("READY payload 不是对象")
                        self._ready_payload = {
                            "origin": str(payload.get("origin") or ""),
                            "token": str(payload.get("token") or ""),
                        }
                    except (ValueError, json.JSONDecodeError) as error:
                        self._startup_error = f"Sandbox READY 无效: {error}"
                    finally:
                        self._ready.set()
                    continue
                if line:
                    with self._lock:
                        self._recent_output.append(line)
        finally:
            process.stdout.close()

    def _failure_message(self, message: str) -> str:
        output = " | ".join(self.recent_output[-5:])
        return message if not output else f"{message}; output={output}"

    def _stop_process(self) -> None:
        with self._lock:
            process = self._process
        if process is not None:
            self._controller.terminate(process)
        reader = self._reader
        if reader is not None:
            reader.join(timeout=max(1.0, self._config.shutdown_timeout_seconds + 1.0))
        with self._lock:
            self._process = None
            self._reader = None
            self._session = None
            self._ready_payload = None
            self._startup_error = None


def _validated_loopback_origin(value: object) -> str:
    origin = str(value or "").rstrip("/")
    parsed = urlparse(origin)
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
        or parsed.port is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise SandboxStartupError("Sandbox READY origin 必须是带端口的 loopback HTTP origin")
    return origin
