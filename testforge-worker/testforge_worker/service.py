from __future__ import annotations

import json
import os
import socket
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

import redis

from testforge_worker.artifact import create_artifact_store
from testforge_worker.callback.client import HttpGatewayClient
from testforge_worker.consumer.claim import GatewayTaskResolver
from testforge_worker.consumer.stream import RedisStreamConsumer
from testforge_worker.consumer.worker import ConsumerWorker
from testforge_worker.executor.adapters import PytestHttpAdapter
from testforge_worker.executor.asset_resolver import ManagedAssetResolver
from testforge_worker.executor.airtest import AirtestAdapter
from testforge_worker.executor.playwright_web import PlaywrightWebAdapter
from testforge_worker.executor.spi import AutomationDriverRegistry, AutomationPlatform
from testforge_worker.environment import (
    ProcessSandboxConfig,
    ProcessSandboxProvider,
    SandboxEnvironmentProvider,
    SandboxSession,
)
from testforge_worker.runtime.process import LogSink
from testforge_worker.runtime.worker import WorkerRuntime


@dataclass(frozen=True)
class ServiceConfig:
    worker_id: str
    gateway_url: str
    redis_url: str
    stream: str
    group: str
    capabilities: tuple[str, ...]
    max_concurrency: int
    device_id: str | None
    device_uri: str | None
    device_platform: str
    device_features: tuple[str, ...]
    device_resolution: str
    sandbox_command: tuple[str, ...] | None
    sandbox_cwd: Path | None
    sandbox_environment_id: str | None
    sandbox_startup_timeout_seconds: float
    sandbox_shutdown_timeout_seconds: float

    @classmethod
    def from_environment(cls) -> "ServiceConfig":
        capabilities = tuple(_csv(os.environ.get(
            "TESTFORGE_WORKER_CAPABILITIES",
            "RUNNER_PYTEST_HTTP,HTTP,PLATFORM_ANY",
        )))
        runner = os.environ.get("TESTFORGE_RUNNER", "pytest-http").lower()
        platform = os.environ.get("TESTFORGE_PLATFORM", "windows").lower()
        default_stream = (
            f"testforge:tasks:airtest:{platform}" if runner == "airtest"
            else f"testforge:tasks:{runner}"
        )
        max_concurrency = int(os.environ.get("TESTFORGE_WORKER_CONCURRENCY", "1"))
        if max_concurrency < 1:
            raise ValueError("TESTFORGE_WORKER_CONCURRENCY 必须大于 0")
        worker_id = os.environ.get("TESTFORGE_WORKER_ID", f"worker-{socket.gethostname().lower()}")
        device_id = os.environ.get("TESTFORGE_DEVICE_ID")
        device_uri = os.environ.get("TESTFORGE_DEVICE_URI")
        sandbox_command = _json_command(os.environ.get("TESTFORGE_SANDBOX_COMMAND_JSON"))
        if sandbox_command and max_concurrency != 1:
            raise ValueError("托管 UI Sandbox 要求 TESTFORGE_WORKER_CONCURRENCY=1")
        if sandbox_command and (not device_id or not device_uri):
            raise ValueError("托管 UI Sandbox 要求 TESTFORGE_DEVICE_ID 和 TESTFORGE_DEVICE_URI")
        return cls(
            worker_id=worker_id,
            gateway_url=os.environ.get("TESTFORGE_GATEWAY_URL", "http://127.0.0.1:8081"),
            redis_url=os.environ.get("TESTFORGE_REDIS_URL", "redis://:testforge_redis@127.0.0.1:6379/0"),
            stream=os.environ.get("TESTFORGE_STREAM", default_stream),
            group=os.environ.get("TESTFORGE_CONSUMER_GROUP", "testforge-workers-v1"),
            capabilities=capabilities,
            max_concurrency=max_concurrency,
            device_id=device_id,
            device_uri=device_uri,
            device_platform=os.environ.get("TESTFORGE_DEVICE_PLATFORM", "WINDOWS"),
            device_features=tuple(_csv(os.environ.get("TESTFORGE_DEVICE_FEATURES", "WINDOWS_UI"))),
            device_resolution=os.environ.get("TESTFORGE_DEVICE_RESOLUTION", "1280x800"),
            sandbox_command=sandbox_command,
            sandbox_cwd=_optional_path(os.environ.get("TESTFORGE_SANDBOX_CWD")),
            sandbox_environment_id=(
                os.environ.get("TESTFORGE_ENVIRONMENT_ID", f"environment-{worker_id}")
                if sandbox_command else None
            ),
            sandbox_startup_timeout_seconds=float(
                os.environ.get("TESTFORGE_SANDBOX_STARTUP_TIMEOUT_SECONDS", "30")
            ),
            sandbox_shutdown_timeout_seconds=float(
                os.environ.get("TESTFORGE_SANDBOX_SHUTDOWN_TIMEOUT_SECONDS", "3")
            ),
        )


class ArtifactLogSink(LogSink):
    def __init__(self, root: Path) -> None:
        self._root = root
        self._lock = threading.Lock()

    def write(self, attempt_id: str, chunk: bytes) -> None:
        matches = list(self._root.glob(f"runs/*/attempts/{attempt_id}"))
        if not matches:
            return
        with self._lock, (matches[0] / "worker.log").open("ab") as output:
            output.write(chunk)


class WorkerService:
    def __init__(
        self,
        config: ServiceConfig,
        sandbox_provider: SandboxEnvironmentProvider | None = None,
    ) -> None:
        self._config = config
        self._stop = threading.Event()
        self._sandbox_session: SandboxSession | None = None
        self._sandbox_provider = (
            sandbox_provider
            if sandbox_provider is not None
            else self._create_sandbox_provider(config)
        )
        artifact_root = Path(os.environ.get("TESTFORGE_ARTIFACT_ROOT", "build/artifacts")).resolve()
        consumer = RedisStreamConsumer(
            redis.from_url(config.redis_url),
            stream=config.stream,
            group=config.group,
            consumer=config.worker_id,
        )
        runtime = WorkerRuntime(
            worker_id=config.worker_id,
            gateway=HttpGatewayClient(),
            log_sink=ArtifactLogSink(artifact_root),
        )
        artifact_store = create_artifact_store(config.worker_id)
        asset_resolver = ManagedAssetResolver(config.gateway_url)
        automation_drivers = AutomationDriverRegistry(
            (AirtestAdapter(artifact_store, self._sandbox_runtime_environment, asset_resolver),)
        )
        self._automation_drivers = automation_drivers
        self._worker = ConsumerWorker(
            consumer=consumer,
            runtime=runtime,
            runners={
                "pytest-http": PytestHttpAdapter(artifact_store, asset_resolver),
                "playwright-web": PlaywrightWebAdapter(artifact_store, asset_resolver),
                **automation_drivers.as_runner_mapping(),
            },
            task_resolver=GatewayTaskResolver(config.gateway_url, config.worker_id),
            max_concurrency=config.max_concurrency,
        )

    def serve(self) -> None:
        heartbeats: threading.Thread | None = None
        try:
            if self._sandbox_provider is not None:
                self._sandbox_session = self._sandbox_provider.start()
            self._register()
            heartbeats = threading.Thread(
                target=self._heartbeat_loop, daemon=True, name="worker-heartbeat"
            )
            heartbeats.start()
            self._worker.serve(self._stop)
        finally:
            self._stop.set()
            if heartbeats is not None:
                heartbeats.join(timeout=2)
            if self._sandbox_provider is not None and self._sandbox_session is not None:
                self._sandbox_provider.stop(self._sandbox_session)
                self._sandbox_session = None

    def stop(self) -> None:
        self._stop.set()

    def _register(self) -> None:
        _request(
            "POST", f"{self._config.gateway_url}/api/v1/workers/register",
            {
                "workerId": self._config.worker_id,
                "protocolVersion": "1.0",
                "capabilities": list(self._config.capabilities),
                "maxConcurrency": self._config.max_concurrency,
            },
        )
        session = self._sandbox_session
        device_id = session.device_id if session else self._config.device_id
        device_uri = session.device_uri if session else self._config.device_uri
        if device_id and device_uri:
            _request(
                "PUT",
                f"{self._config.gateway_url}/api/v1/workers/{self._config.worker_id}/devices",
                {
                    "deviceId": device_id,
                    "platform": session.platform.value if session else self._config.device_platform,
                    "deviceUri": device_uri,
                    "features": list(session.features if session else self._config.device_features),
                    "resolution": session.resolution if session else self._config.device_resolution,
                },
            )

    def _heartbeat_loop(self) -> None:
        while not self._stop.wait(5):
            session = self._sandbox_session
            if (
                self._sandbox_provider is not None
                and session is not None
                and not self._sandbox_provider.health(session)
            ):
                self._stop.set()
                return
            try:
                _request("POST", f"{self._config.gateway_url}/api/v1/workers/{self._config.worker_id}/heartbeat")
                device_id = session.device_id if session else self._config.device_id
                if device_id:
                    _request(
                        "POST",
                        f"{self._config.gateway_url}/api/v1/workers/{self._config.worker_id}/devices/{device_id}/heartbeat",
                    )
            except OSError:
                continue

    def _sandbox_runtime_environment(self) -> dict[str, str]:
        session = self._sandbox_session
        return {} if session is None else dict(session.runtime_environment)

    @staticmethod
    def _create_sandbox_provider(
        config: ServiceConfig,
    ) -> SandboxEnvironmentProvider | None:
        if config.sandbox_command is None:
            return None
        if config.max_concurrency != 1:
            raise ValueError("托管 UI Sandbox 要求 max_concurrency=1")
        if not config.device_id or not config.device_uri or not config.sandbox_environment_id:
            raise ValueError("托管 Sandbox 配置缺少 environment/device 信息")
        return ProcessSandboxProvider(
            ProcessSandboxConfig(
                environment_id=config.sandbox_environment_id,
                platform=AutomationPlatform.from_value(config.device_platform),
                device_id=config.device_id,
                device_uri=config.device_uri,
                features=config.device_features,
                resolution=config.device_resolution,
                command=config.sandbox_command,
                cwd=config.sandbox_cwd,
                startup_timeout_seconds=config.sandbox_startup_timeout_seconds,
                shutdown_timeout_seconds=config.sandbox_shutdown_timeout_seconds,
            )
        )


def _request(method: str, url: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = Request(url, data=data, headers={"Content-Type": "application/json"}, method=method)
    with urlopen(request, timeout=5) as response:
        raw = response.read()
    return json.loads(raw.decode("utf-8")) if raw else {}


def _csv(value: str) -> list[str]:
    return [item.strip().upper().replace("-", "_") for item in value.split(",") if item.strip()]


def _json_command(value: str | None) -> tuple[str, ...] | None:
    if value is None or not value.strip():
        return None
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError as error:
        raise ValueError("TESTFORGE_SANDBOX_COMMAND_JSON 必须是 JSON 字符串数组") from error
    if not isinstance(parsed, list) or not parsed or any(
        not isinstance(item, str) or not item for item in parsed
    ):
        raise ValueError("TESTFORGE_SANDBOX_COMMAND_JSON 必须是非空 JSON 字符串数组")
    return tuple(parsed)


def _optional_path(value: str | None) -> Path | None:
    return None if value is None or not value.strip() else Path(value).resolve()
