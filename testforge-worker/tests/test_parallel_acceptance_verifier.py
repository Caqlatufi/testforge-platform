from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


VERIFY_PATH = (
    Path(__file__).resolve().parents[2]
    / "acceptance"
    / "virtual-environment-parallel"
    / "verify.py"
)
SPEC = importlib.util.spec_from_file_location("virtual_environment_verify", VERIFY_PATH)
assert SPEC is not None and SPEC.loader is not None
verify_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_module)


class ParallelAcceptanceVerifierTest(unittest.TestCase):
    def test_accepts_two_observed_workers_with_isolated_artifacts(self) -> None:
        snapshot = {
            "id": "run-1",
            "state": "SUCCEEDED",
            "maxConcurrency": 2,
            "tasks": [
                {
                    "attempts": [
                        {
                            "id": "attempt-a",
                            "workerId": "worker-a",
                            "state": "SUCCEEDED",
                            "createdAt": "2026-09-21T00:00:00Z",
                            "updatedAt": "2026-09-21T00:00:10Z",
                        }
                    ]
                },
                {
                    "attempts": [
                        {
                            "id": "attempt-b",
                            "workerId": "worker-b",
                            "state": "SUCCEEDED",
                            "createdAt": "2026-09-21T00:00:05Z",
                            "updatedAt": "2026-09-21T00:00:15Z",
                        }
                    ]
                },
            ],
        }
        report = {
            "passed": 2,
            "durationMs": 15000,
            "results": [
                {
                    "attemptId": "attempt-a",
                    "artifactKeys": ["bucket/runs/run-1/attempts/attempt-a/result.json"],
                },
                {
                    "attemptId": "attempt-b",
                    "artifactKeys": ["bucket/runs/run-1/attempts/attempt-b/result.json"],
                },
            ],
        }
        devices = [
            {"workerId": "worker-a", "deviceId": "device-a", "features": ["ISOLATED_DESKTOP"]},
            {"workerId": "worker-b", "deviceId": "device-b", "features": ["ISOLATED_DESKTOP"]},
        ]

        evidence = verify_module.verify(
            snapshot,
            report,
            devices,
            [{"workerIds": ["worker-a", "worker-b"]}],
            expected_tasks=2,
            min_overlap_seconds=2,
            max_duration_ms=20000,
        )

        self.assertEqual("PASS", evidence["status"])
        self.assertTrue(all(evidence["checks"].values()))
        self.assertEqual(15000, evidence["metrics"]["wallClockDurationMs"])
        self.assertEqual(15000, evidence["metrics"]["taskDurationSumMs"])


if __name__ == "__main__":
    unittest.main()
