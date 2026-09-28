#!/usr/bin/env python3
"""Expire a real Attempt lease, recover it, and reject its late callback."""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "acceptance"))
from report_history_http import call, create_assets  # noqa: E402


def wait_for_queued(base: str, run_id: str, timeout: int) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        run = call(base, "GET", f"/api/v1/runs/{run_id}")
        task = run["tasks"][0]
        if task["state"] == "QUEUED" and task.get("attempts") and task["attempts"][-1]["state"] in {"LOST", "ABANDONED", "TIMED_OUT"}:
            return run
        time.sleep(1)
    raise TimeoutError(f"run {run_id} did not recover to QUEUED")


def complete(base: str, attempt: dict, worker: str) -> dict:
    call(base, "POST", f"/api/v1/attempts/{attempt['attemptId']}/start", {"workerId": worker, "leaseToken": attempt["leaseToken"]})
    return call(base, "POST", f"/api/v1/attempts/{attempt['attemptId']}/callback", {"schemaVersion": "1.0.0", "callbackKey": str(uuid.uuid4()), "attemptId": attempt["attemptId"], "leaseToken": attempt["leaseToken"], "workerId": worker, "status": "PASSED", "completedAt": datetime.now(timezone.utc).isoformat(), "durationMs": 100, "summary": "recovered by replacement worker", "failure": None, "artifacts": [], "metrics": {}})


def claim_when_available(base: str, run_id: str, task_id: str, worker: str, timeout: int) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        call(base, "POST", f"/api/v1/workers/{worker}/heartbeat")
        try:
            return call(base, "POST", f"/api/v1/tasks/{task_id}/claim", {"runId": run_id, "messageId": str(uuid.uuid4()), "workerId": worker})
        except RuntimeError as error:
            if "HTTP 409" not in str(error) and "HTTP 423" not in str(error):
                raise
            time.sleep(1)
    raise TimeoutError(f"task {task_id} was not claimable by {worker}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--cycles", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=45)
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance" / "evidence" / "reliability-observability-delivery" / "worker-loss.json")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    assets = create_assets(base)
    dead_worker = f"dead-worker-{uuid.uuid4().hex[:8]}"
    replacement = f"replacement-worker-{uuid.uuid4().hex[:8]}"
    for worker in (dead_worker, replacement):
        call(base, "POST", "/api/v1/workers/register", {"workerId": worker, "protocolVersion": "1.0", "capabilities": ["RUNNER_PYTEST_HTTP", "HTTP", "PLATFORM_ANY"], "maxConcurrency": 1})
    cycles = []
    for index in range(args.cycles):
        call(base, "POST", f"/api/v1/workers/{dead_worker}/heartbeat")
        run = call(base, "POST", "/api/v1/runs", {**{key: assets[key] for key in ("projectId", "targetId", "environmentId", "workflowId", "workflowVersion")}, "priority": 5, "maxConcurrency": 1, "requestKey": str(uuid.uuid4())})
        task = run["tasks"][0]
        first = claim_when_available(base, run["id"], task["id"], dead_worker, args.timeout)
        call(base, "POST", f"/api/v1/attempts/{first['attemptId']}/start", {"workerId": dead_worker, "leaseToken": first["leaseToken"]})
        recovered = wait_for_queued(base, run["id"], args.timeout)
        second = claim_when_available(base, run["id"], task["id"], replacement, args.timeout)
        applied = complete(base, second, replacement)
        late = call(base, "POST", f"/api/v1/attempts/{first['attemptId']}/callback", {"schemaVersion": "1.0.0", "callbackKey": str(uuid.uuid4()), "attemptId": first["attemptId"], "leaseToken": first["leaseToken"], "workerId": dead_worker, "status": "PASSED", "completedAt": datetime.now(timezone.utc).isoformat(), "durationMs": 999, "summary": "late callback", "failure": None, "artifacts": [], "metrics": {}})
        final_run = call(base, "GET", f"/api/v1/runs/{run['id']}")
        if applied["disposition"] != "ACCEPTED" or late["disposition"] != "STALE" or final_run["state"] != "SUCCEEDED":
            raise AssertionError({"applied": applied, "late": late, "run": final_run})
        cycles.append({"cycle": index + 1, "runId": run["id"], "taskId": task["id"], "expiredAttemptId": first["attemptId"], "replacementAttemptId": second["attemptId"], "attemptStatesAfterRecovery": [attempt["state"] for attempt in recovered["tasks"][0]["attempts"]], "lateDisposition": late["disposition"], "finalState": final_run["state"]})
    evidence = {"schema": "io.testforge/worker-loss/v1", "executedAt": datetime.now(timezone.utc).isoformat(), "result": "PASS", "cycles": cycles}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
