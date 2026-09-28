from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from testforge_worker.executor.pytest_http import (
    FailureType,
    HttpCaseSpec,
    JUnitParseError,
    PytestHttpRunner,
    RunnerStatus,
    parse_junit_xml,
)
from testforge_worker.executor.pytest_http.runner import _BoundedLogCollector, _ProcessOutcome


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - stdlib handler protocol
        if self.path.startswith("/slow"):
            time.sleep(1.0)
        if self.path.startswith("/large"):
            payload = b"X" * 5000
        else:
            payload = json.dumps({"ok": True, "nested": {"value": 7}}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-TestForge", "runner")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except BrokenPipeError:
            pass

    def log_message(self, format: str, *args: object) -> None:
        del format, args


class PytestHttpRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()
        host, port = cls.server.server_address
        cls.endpoint = f"http://{host}:{port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.server_thread.join(timeout=2)

    def test_success_generates_and_executes_http_case(self) -> None:
        result = PytestHttpRunner().run(
            HttpCaseSpec(
                name="health",
                endpoint=self.endpoint,
                expected_json_subset={"nested": {"value": 7}},
                expected_headers={"X-TestForge": "runner"},
            ),
            timeout_seconds=5,
        )

        self.assertEqual(RunnerStatus.PASSED, result.status)
        self.assertIsNone(result.failure)
        self.assertIsNotNone(result.junit_xml)
        self.assertEqual(1, result.junit.tests if result.junit else 0)

    def test_run_path_executes_platform_managed_python_asset(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            test_path = Path(temporary) / "test_managed.py"
            test_path.write_text("def test_managed():\n    assert 2 + 2 == 4\n", encoding="utf-8")

            result = PytestHttpRunner().run_path(test_path, timeout_seconds=5)

        self.assertEqual(RunnerStatus.PASSED, result.status)
        self.assertEqual(1, result.junit.tests if result.junit else 0)

    def test_assertion_failure_maps_to_product_defect(self) -> None:
        result = PytestHttpRunner().run(
            HttpCaseSpec(name="wrong status", endpoint=self.endpoint, expected_status=201),
            timeout_seconds=5,
        )

        self.assertEqual(RunnerStatus.ASSERTION_FAILED, result.status)
        self.assertEqual(FailureType.PRODUCT_DEFECT, result.failure.type if result.failure else None)
        self.assertFalse(result.failure.retryable if result.failure else True)

    def test_hard_timeout_maps_to_retryable_environment_failure(self) -> None:
        result = PytestHttpRunner(terminate_grace_seconds=0.1).run(
            HttpCaseSpec(
                name="slow",
                endpoint=self.endpoint,
                path="/slow",
                request_timeout_seconds=3,
            ),
            timeout_seconds=0.2,
        )

        self.assertEqual(RunnerStatus.INFRA_FAILED, result.status)
        self.assertEqual(FailureType.ENVIRONMENT, result.failure.type if result.failure else None)
        self.assertEqual("TIMEOUT", result.failure.details.get("reason") if result.failure else None)
        self.assertTrue(result.failure.retryable if result.failure else False)

    def test_bad_junit_maps_to_environment_failure(self) -> None:
        runner = PytestHttpRunner()

        def fake_execute(
            test_path: Path,
            junit_path: Path,
            work_dir: Path,
            timeout_seconds: float,
        ) -> _ProcessOutcome:
            del test_path, work_dir, timeout_seconds
            junit_path.write_text("<testsuite>", encoding="utf-8")
            return _ProcessOutcome(1, 10, "bad xml", False)

        with patch.object(runner, "_execute_pytest", side_effect=fake_execute):
            result = runner.run(
                HttpCaseSpec(name="bad junit", endpoint=self.endpoint),
                timeout_seconds=5,
            )

        self.assertEqual(RunnerStatus.INFRA_FAILED, result.status)
        self.assertEqual("JUNIT_INVALID", result.failure.details.get("reason") if result.failure else None)
        self.assertEqual(b"<testsuite>", result.junit_xml)

    def test_junit_error_maps_to_script_error(self) -> None:
        runner = PytestHttpRunner()

        def fake_execute(
            test_path: Path,
            junit_path: Path,
            work_dir: Path,
            timeout_seconds: float,
        ) -> _ProcessOutcome:
            del test_path, work_dir, timeout_seconds
            junit_path.write_text(
                '<testsuite tests="1" errors="1">'
                '<testcase name="broken"><error type="NameError" message="missing name">trace</error>'
                '</testcase></testsuite>',
                encoding="utf-8",
            )
            return _ProcessOutcome(1, 10, "collection error", False)

        with patch.object(runner, "_execute_pytest", side_effect=fake_execute):
            result = runner.run(
                HttpCaseSpec(name="script error", endpoint=self.endpoint),
                timeout_seconds=5,
            )

        self.assertEqual(RunnerStatus.INFRA_FAILED, result.status)
        self.assertEqual(FailureType.SCRIPT_ERROR, result.failure.type if result.failure else None)
        self.assertFalse(result.failure.retryable if result.failure else True)

    def test_runner_truncates_large_pytest_log(self) -> None:
        result = PytestHttpRunner(max_log_bytes=256).run(
            HttpCaseSpec(
                name="large failure",
                endpoint=self.endpoint,
                path="/large",
                expected_status=201,
            ),
            timeout_seconds=5,
        )

        self.assertTrue(result.logs_truncated)
        self.assertLessEqual(len(result.logs.encode("utf-8")), 256)
        self.assertIn("TestForge log truncated", result.logs)

    def test_log_collector_keeps_bounded_head_and_tail(self) -> None:
        collector = _BoundedLogCollector(100)
        collector.add(b"A" * 80)
        collector.add(b"B" * 80)

        logs, truncated = collector.finish()

        self.assertTrue(truncated)
        self.assertLessEqual(len(logs.encode("utf-8")), 100)
        self.assertTrue(logs.startswith("A" * 50))
        self.assertTrue(logs.endswith("B" * 16))
        self.assertIn("TestForge log truncated", logs)

    def test_junit_parser_rejects_malformed_xml(self) -> None:
        with self.assertRaises(JUnitParseError):
            parse_junit_xml(b"<testsuites>")

    def test_junit_parser_rejects_entity_declarations(self) -> None:
        xml = (
            b'<!DOCTYPE testsuite [<!ENTITY secret "do-not-expand">]>'
            b'<testsuite tests="1"><testcase name="x">&secret;</testcase></testsuite>'
        )

        with self.assertRaisesRegex(JUnitParseError, "DOCTYPE"):
            parse_junit_xml(xml)

    def test_each_run_uses_a_cleaned_isolated_directory(self) -> None:
        runner = PytestHttpRunner()
        seen_directories: list[Path] = []

        def fake_execute(
            test_path: Path,
            junit_path: Path,
            work_dir: Path,
            timeout_seconds: float,
        ) -> _ProcessOutcome:
            del test_path, timeout_seconds
            seen_directories.append(work_dir)
            junit_path.write_text(
                '<testsuite tests="1"><testcase name="ok" time="0.01" /></testsuite>',
                encoding="utf-8",
            )
            return _ProcessOutcome(0, 10, "", False)

        with patch.object(runner, "_execute_pytest", side_effect=fake_execute):
            for name in ("first", "second"):
                result = runner.run(
                    HttpCaseSpec(name=name, endpoint=self.endpoint),
                    timeout_seconds=5,
                )
                self.assertEqual(RunnerStatus.PASSED, result.status)

        self.assertEqual(2, len(set(seen_directories)))
        self.assertTrue(all(not directory.exists() for directory in seen_directories))


if __name__ == "__main__":
    unittest.main()
