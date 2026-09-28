from __future__ import annotations

import sys
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from testforge_worker.environment import (
    ProcessSandboxConfig,
    ProcessSandboxProvider,
    SandboxStartupError,
)
from testforge_worker.executor.spi import AutomationPlatform


FIXTURE = Path(__file__).parent / "fixtures" / "fake_sandbox.py"


def provider(environment_id: str) -> ProcessSandboxProvider:
    return ProcessSandboxProvider(
        ProcessSandboxConfig(
            environment_id=environment_id,
            platform=AutomationPlatform.WINDOWS,
            device_id=f"device-{environment_id}",
            device_uri="Windows:///?title_re=Skill%20Sandbox",
            features=("WINDOWS_UI", "ISOLATED_DESKTOP"),
            resolution="1280x800",
            command=(sys.executable, "-u", str(FIXTURE)),
            startup_timeout_seconds=5,
            shutdown_timeout_seconds=1,
        )
    )


class ProcessSandboxProviderTest(unittest.TestCase):
    def test_two_providers_own_independent_healthy_sessions(self) -> None:
        first = provider("vm-a")
        second = provider("vm-b")
        sessions = []
        try:
            with ThreadPoolExecutor(max_workers=2) as pool:
                sessions = list(pool.map(lambda item: item.start(), (first, second)))

            self.assertNotEqual(sessions[0].process_id, sessions[1].process_id)
            self.assertNotEqual(
                sessions[0].runtime_environment["TEST_BRIDGE_ORIGIN"],
                sessions[1].runtime_environment["TEST_BRIDGE_ORIGIN"],
            )
            self.assertNotEqual(
                sessions[0].runtime_environment["TEST_BRIDGE_TOKEN"],
                sessions[1].runtime_environment["TEST_BRIDGE_TOKEN"],
            )
            self.assertTrue(first.health(sessions[0]))
            self.assertTrue(second.health(sessions[1]))
            self.assertNotIn("TEST_BRIDGE_TOKEN", repr(sessions[0]))
            self.assertNotIn(
                sessions[0].runtime_environment["TEST_BRIDGE_TOKEN"],
                "\n".join(first.recent_output),
            )
        finally:
            if sessions:
                first.stop(sessions[0])
                second.stop(sessions[1])

        self.assertFalse(first.health(sessions[0]))
        self.assertFalse(second.health(sessions[1]))

    def test_start_timeout_reaps_process(self) -> None:
        sandbox = ProcessSandboxProvider(
            ProcessSandboxConfig(
                environment_id="never-ready",
                platform=AutomationPlatform.WINDOWS,
                device_id="device-never-ready",
                device_uri="Windows:///never-ready",
                features=("WINDOWS_UI",),
                resolution="1280x800",
                command=(sys.executable, "-u", "-c", "import time; time.sleep(30)"),
                startup_timeout_seconds=0.2,
                shutdown_timeout_seconds=0.2,
            )
        )

        with self.assertRaisesRegex(SandboxStartupError, "超时"):
            sandbox.start()


if __name__ == "__main__":
    unittest.main()
