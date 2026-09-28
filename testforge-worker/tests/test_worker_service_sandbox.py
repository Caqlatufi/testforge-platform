from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from testforge_worker.environment import SandboxSession
from testforge_worker.executor.spi import AutomationPlatform
from testforge_worker.service import ServiceConfig, WorkerService


class FakeProvider:
    def __init__(self, events: list[str]) -> None:
        self.events = events
        self.session = SandboxSession(
            environment_id="vm-a",
            platform=AutomationPlatform.WINDOWS,
            device_id="device-vm-a",
            device_uri="Windows:///managed",
            features=("WINDOWS_UI", "ISOLATED_DESKTOP"),
            resolution="1280x800",
            process_id=123,
            runtime_environment={
                "TEST_BRIDGE_ORIGIN": "http://127.0.0.1:12345",
                "TEST_BRIDGE_TOKEN": "runtime-only-token",
                "TESTFORGE_DEVICE_URI": "Windows:///managed",
            },
        )

    def start(self) -> SandboxSession:
        self.events.append("sandbox:start")
        return self.session

    def health(self, session: SandboxSession) -> bool:
        return session is self.session

    def stop(self, session: SandboxSession) -> None:
        self.events.append("sandbox:stop")


class FakeConsumerWorker:
    def __init__(self, events: list[str], **_: object) -> None:
        self.events = events

    def serve(self, stop: object) -> None:
        del stop
        self.events.append("worker:serve")


def config() -> ServiceConfig:
    return ServiceConfig(
        worker_id="worker-vm-a",
        gateway_url="http://gateway",
        redis_url="redis://redis",
        stream="tasks",
        group="workers",
        capabilities=("RUNNER_AIRTEST", "PLATFORM_WINDOWS", "ISOLATED_DESKTOP"),
        max_concurrency=1,
        device_id="configured-device",
        device_uri="Windows:///configured",
        device_platform="WINDOWS",
        device_features=("WINDOWS_UI",),
        device_resolution="1024x768",
        sandbox_command=("sandbox",),
        sandbox_cwd=None,
        sandbox_environment_id="vm-a",
        sandbox_startup_timeout_seconds=5,
        sandbox_shutdown_timeout_seconds=1,
    )


class WorkerServiceSandboxTest(unittest.TestCase):
    def test_registers_managed_device_only_after_sandbox_is_ready_and_stops_it(self) -> None:
        events: list[str] = []
        requests: list[tuple[str, str, object]] = []
        provider = FakeProvider(events)

        def request(method: str, url: str, body: object = None) -> dict[str, object]:
            self.assertIn("sandbox:start", events)
            requests.append((method, url, body))
            return {}

        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            "os.environ", {"TESTFORGE_ARTIFACT_ROOT": temporary}, clear=False
        ), patch("testforge_worker.service.redis.from_url", return_value=object()), patch(
            "testforge_worker.service.RedisStreamConsumer", return_value=object()
        ), patch(
            "testforge_worker.service.create_artifact_store", return_value=object()
        ), patch(
            "testforge_worker.service.ConsumerWorker",
            side_effect=lambda **kwargs: FakeConsumerWorker(events, **kwargs),
        ), patch("testforge_worker.service._request", side_effect=request):
            service = WorkerService(config(), sandbox_provider=provider)
            service.serve()

        self.assertEqual("sandbox:start", events[0])
        self.assertEqual("worker:serve", events[-2])
        self.assertEqual("sandbox:stop", events[-1])
        self.assertEqual(2, len(requests))
        device_body = requests[1][2]
        self.assertIsInstance(device_body, dict)
        assert isinstance(device_body, dict)
        self.assertEqual("device-vm-a", device_body["deviceId"])
        self.assertEqual("Windows:///managed", device_body["deviceUri"])
        self.assertEqual(["WINDOWS_UI", "ISOLATED_DESKTOP"], device_body["features"])
        self.assertNotIn("runtime-only-token", repr(requests))


if __name__ == "__main__":
    unittest.main()
