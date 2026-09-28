from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from testforge_worker.executor.playwright_web import PlaywrightWebAdapter
from testforge_worker.runtime.model import CallbackUrls, ProcessOutcome, TaskEnvelope


def task(script: Path) -> TaskEnvelope:
    return TaskEnvelope(
        message_id="message-1",
        task_id="task-1",
        run_id="run-1",
        attempt_id="attempt-1",
        lease_token="lease-1",
        timeout_seconds=30,
        execution={"runner": "playwright-web", "resourceMode": "PROCESS_POOL", "script": {"sourceRef": str(script)}, "parameters": {"baseUrl": "http://127.0.0.1:15174"}},
        callbacks=CallbackUrls("start", "heartbeat", "complete", "artifacts"),
        traceparent="trace",
    )


class PlaywrightWebAdapterTest(unittest.TestCase):
    def test_prepare_materializes_task_and_uses_dedicated_entry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"TESTFORGE_ARTIFACT_ROOT": temporary}, clear=False):
            script = Path(temporary) / "selftest.py"
            script.write_text("def run(page, parameters): return 'ok'", encoding="utf-8")
            spec = PlaywrightWebAdapter().prepare(task(script))
            self.assertIn("testforge_worker.executor.playwright_web.entry", spec.command)
            self.assertTrue(Path(spec.command[spec.command.index("--task") + 1]).is_file())

    def test_result_uploads_screenshot_trace_and_log(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {"TESTFORGE_ARTIFACT_ROOT": temporary}, clear=False):
            script = Path(temporary) / "selftest.py"
            script.write_text("def run(page, parameters): return 'ok'", encoding="utf-8")
            envelope = task(script)
            adapter = PlaywrightWebAdapter()
            adapter.prepare(envelope)
            directory = Path(temporary) / "runs" / "run-1" / "attempts" / "attempt-1"
            (directory / "final.png").write_bytes(b"png")
            (directory / "trace.zip").write_bytes(b"zip")
            (directory / "browser.log").write_text("console", encoding="utf-8")
            (directory / "playwright-result.json").write_text(json.dumps({"status": "PASSED", "summary": "UI ok"}), encoding="utf-8")
            result = adapter.result(envelope, ProcessOutcome(0, 123))
            self.assertEqual("PASSED", result.status)
            self.assertEqual({"SCREENSHOT", "TRACE", "LOG", "OTHER"}, {item["type"] for item in result.artifacts})


if __name__ == "__main__":
    unittest.main()
