#!/usr/bin/env python3
"""Exercise callback idempotency through 20 concurrent real HTTP requests."""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "acceptance"))
from report_history_http import call, create_assets  # noqa: E402


def raw(base: str, path: str, body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
    request = Request(base + path, data=json.dumps(body).encode(), method="POST", headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=20) as response:
            return response.status, json.loads(response.read().decode())
    except HTTPError as error:
        return error.code, json.loads(error.read().decode())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance" / "evidence" / "reliability-observability-delivery" / "callback-race.json")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    assets = create_assets(base)
    worker = f"callback-race-{uuid.uuid4().hex[:8]}"
    call(base, "POST", "/api/v1/workers/register", {"workerId": worker, "protocolVersion": "1.0", "capabilities": ["RUNNER_PYTEST_HTTP", "HTTP", "PLATFORM_ANY"], "maxConcurrency": 1})
    run = call(base, "POST", "/api/v1/runs", {**{key: assets[key] for key in ("projectId", "targetId", "environmentId", "workflowId", "workflowVersion")}, "priority": 5, "maxConcurrency": 1, "requestKey": str(uuid.uuid4())})
    task = run["tasks"][0]
    envelope = call(base, "POST", f"/api/v1/tasks/{task['id']}/claim", {"runId": run["id"], "messageId": str(uuid.uuid4()), "workerId": worker})
    attempt = envelope["attemptId"]
    call(base, "POST", f"/api/v1/attempts/{attempt}/start", {"workerId": worker, "leaseToken": envelope["leaseToken"]})
    callback_key = str(uuid.uuid4())
    payload = {"schemaVersion": "1.0.0", "callbackKey": callback_key, "attemptId": attempt, "leaseToken": envelope["leaseToken"], "workerId": worker, "status": "PASSED", "completedAt": datetime.now(timezone.utc).isoformat(), "durationMs": 123, "summary": "20-way callback race", "failure": None, "artifacts": [], "metrics": {}}
    with ThreadPoolExecutor(max_workers=20) as pool:
        responses = list(pool.map(lambda _: raw(base, f"/api/v1/attempts/{attempt}/callback", payload), range(20)))
    dispositions = [response[1].get("data", {}).get("disposition") for response in responses]
    if dispositions.count("ACCEPTED") != 1 or dispositions.count("DUPLICATE") != 19:
        raise AssertionError(f"unexpected callback race dispositions: {dispositions}")
    conflicting = dict(payload, summary="conflicting payload")
    conflict_status, conflict_body = raw(base, f"/api/v1/attempts/{attempt}/callback", conflicting)
    if conflict_status != 409 or conflict_body.get("code") != "IDEMPOTENCY_CONFLICT":
        raise AssertionError((conflict_status, conflict_body))
    final_run = call(base, "GET", f"/api/v1/runs/{run['id']}")
    evidence = {"schema": "io.testforge/callback-race/v1", "executedAt": datetime.now(timezone.utc).isoformat(), "result": "PASS", "runId": run["id"], "taskId": task["id"], "attemptId": attempt, "requests": 20, "dispositions": {"ACCEPTED": 1, "DUPLICATE": 19}, "conflictStatus": conflict_status, "finalRunState": final_run["state"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
