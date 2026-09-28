from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from testforge_worker.executor.airtest import AirtestAdapter
from testforge_worker.executor.spi import UnsupportedAutomationPlatform
from testforge_worker.runtime.model import CallbackUrls, ProcessOutcome, TaskEnvelope


def task(execution: dict | None = None) -> TaskEnvelope:
    return TaskEnvelope(
        message_id="message-1",
        task_id="task-1",
        run_id="run-1",
        attempt_id="attempt-1",
        lease_token="lease-1",
        timeout_seconds=30,
        execution=execution or {"sourceRef": "case-1", "platform": "WINDOWS", "parameters": {}},
        callbacks=CallbackUrls("start", "heartbeat", "complete", "artifacts"),
        traceparent="trace",
    )


class AirtestAdapterTest(unittest.TestCase):
    def test_prepare_passes_scenario_id_to_airtest_process(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"TESTFORGE_ARTIFACT_ROOT": temporary}, clear=False
        ):
            script = Path(temporary) / "scenario.air"
            script.mkdir()
            envelope = task(
                {
                    "platform": "WINDOWS",
                    "script": {"sourceRef": str(script)},
                    "parameters": {
                        "deviceUri": "Windows:///?title_re=Skill%20Sandbox",
                        "scenarioId": "core-obstacle-blocked-v1",
                        "bridgeOrigin": "http://127.0.0.1:12345",
                        "bridgeToken": "process-token",
                    },
                }
            )

            spec = AirtestAdapter().prepare(envelope)

            self.assertEqual(
                "core-obstacle-blocked-v1",
                spec.environment["TESTFORGE_SCENARIO_ID"],
            )
            self.assertEqual("http://127.0.0.1:12345", spec.environment["TEST_BRIDGE_ORIGIN"])
            self.assertEqual("WINDOWS", spec.environment["TESTFORGE_AUTOMATION_PLATFORM"])

    def test_prepare_supports_windows_android_and_ios(self) -> None:
        devices = {
            "WINDOWS": "Windows:///?title_re=Skill%20Sandbox",
            "ANDROID": "Android:///emulator-5554",
            "IOS": "iOS:///00008110-device",
        }
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"TESTFORGE_ARTIFACT_ROOT": temporary}, clear=False
        ):
            script = Path(temporary) / "scenario.air"
            script.mkdir()
            for platform, device_uri in devices.items():
                with self.subTest(platform=platform):
                    envelope = task(
                        {
                            "platform": platform,
                            "script": {"sourceRef": str(script)},
                            "parameters": {"deviceUri": device_uri},
                        }
                    )

                    spec = AirtestAdapter().prepare(envelope)

                    self.assertEqual(platform, spec.environment["TESTFORGE_AUTOMATION_PLATFORM"])
                    self.assertIn(device_uri, spec.command)

    def test_managed_environment_is_authoritative_and_injected_at_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"TESTFORGE_ARTIFACT_ROOT": temporary}, clear=False
        ):
            script = Path(temporary) / "scenario.air"
            script.mkdir()
            managed = {
                "TESTFORGE_DEVICE_URI": "Windows:///managed-sandbox",
                "TEST_BRIDGE_ORIGIN": "http://127.0.0.1:23456",
                "TEST_BRIDGE_TOKEN": "ephemeral-managed-token",
            }
            envelope = task(
                {
                    "platform": "WINDOWS",
                    "script": {"sourceRef": str(script)},
                    "parameters": {
                        "deviceUri": "Windows:///task-device",
                        "bridgeOrigin": "http://127.0.0.1:11111",
                        "bridgeToken": "persisted-task-token",
                    },
                }
            )

            spec = AirtestAdapter(runtime_environment=lambda: managed).prepare(envelope)

            self.assertIn("Windows:///managed-sandbox", spec.command)
            self.assertEqual("http://127.0.0.1:23456", spec.environment["TEST_BRIDGE_ORIGIN"])
            self.assertEqual("ephemeral-managed-token", spec.environment["TEST_BRIDGE_TOKEN"])

    def test_prepare_rejects_platform_outside_driver_descriptor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            script = Path(temporary) / "scenario.air"
            script.mkdir()
            envelope = task(
                {
                    "platform": "MACOS",
                    "script": {"sourceRef": str(script)},
                    "parameters": {"deviceUri": "Mac:///SkillSandbox"},
                }
            )

            with self.assertRaisesRegex(UnsupportedAutomationPlatform, "MACOS"):
                AirtestAdapter().prepare(envelope)

    def test_prepare_rejects_task_and_device_platform_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            script = Path(temporary) / "scenario.air"
            script.mkdir()
            envelope = task(
                {
                    "platform": "ANDROID",
                    "script": {"sourceRef": str(script)},
                    "parameters": {"deviceUri": "Windows:///SkillSandbox"},
                }
            )

            with self.assertRaisesRegex(OSError, "平台不一致"):
                AirtestAdapter().prepare(envelope)

    def test_maps_sandbox_types_and_adds_late_runtime_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"TESTFORGE_ARTIFACT_ROOT": temporary}
        ):
            envelope = task()
            adapter = AirtestAdapter()
            directory = Path(temporary) / "runs" / "run-1" / "attempts" / "attempt-1"
            directory.mkdir(parents=True)
            adapter._directories[envelope.attempt_id] = directory  # noqa: SLF001
            (directory / "screenshots").mkdir()
            (directory / "screenshots" / "01-ready.png").write_bytes(b"png")
            (directory / "airtest-result.json").write_text(
                json.dumps({"status": "PASSED", "summary": "ok"}), encoding="utf-8"
            )
            (directory / "evidence-manifest.json").write_text(
                json.dumps(
                    {
                        "artifacts": [
                            {
                                "type": "STEP_SCREENSHOT",
                                "relativePath": "screenshots/01-ready.png",
                            },
                            {"type": "CASE_RESULT", "relativePath": "airtest-result.json"},
                        ]
                    }
                ),
                encoding="utf-8",
            )
            (directory / "worker.log").write_text("worker", encoding="utf-8")
            (directory / "airtest-report").mkdir()
            (directory / "airtest-report" / "index.html").write_text(
                "<html></html>", encoding="utf-8"
            )

            result = adapter.result(envelope, ProcessOutcome(exit_code=0, duration_ms=123))

            self.assertEqual("PASSED", result.status)
            self.assertEqual(
                {"SCREENSHOT", "OTHER", "LOG", "AIRTEST_HTML"},
                {artifact["type"] for artifact in result.artifacts},
            )

    def test_reads_nested_failure_message(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"TESTFORGE_ARTIFACT_ROOT": temporary}
        ):
            envelope = task()
            adapter = AirtestAdapter()
            directory = Path(temporary) / "attempt"
            directory.mkdir()
            adapter._directories[envelope.attempt_id] = directory  # noqa: SLF001
            (directory / "airtest-result.json").write_text(
                json.dumps(
                    {
                        "status": "FAILED",
                        "failure": {"type": "ASSERTION_FAILED", "message": "bad hit"},
                    }
                ),
                encoding="utf-8",
            )

            result = adapter.result(envelope, ProcessOutcome(exit_code=1, duration_ms=12))

            self.assertEqual("ASSERTION_FAILED", result.status)
            self.assertEqual("bad hit", result.failure.message)


if __name__ == "__main__":
    unittest.main()
