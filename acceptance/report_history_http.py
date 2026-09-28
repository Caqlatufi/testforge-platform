#!/usr/bin/env python3
"""Create three real HTTP runs and verify bounded Case history / Flaky evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def call(base: str, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
    request = Request(
        base + path,
        data=None if body is None else json.dumps(body).encode("utf-8"),
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urlopen(request, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} -> HTTP {error.code}: {detail}") from error
    return payload.get("data", payload)


def create_assets(
    base: str,
    *,
    runner: str = "pytest-http",
    parameter_overrides: dict[str, Any] | None = None,
) -> dict[str, Any]:
    suffix = uuid.uuid4().hex[:10]
    project = call(base, "POST", "/api/v1/projects", {"name": "History HTTP acceptance", "code": f"history-{suffix}"})
    target = call(base, "POST", f"/api/v1/projects/{project['id']}/targets", {"name": "HTTP target", "type": "HTTP_SERVICE"})
    environment = call(
        base,
        "POST",
        f"/api/v1/targets/{target['id']}/environments",
        {"name": "Local", "endpoint": "http://127.0.0.1", "config": {}, "secretRefs": {}},
    )
    case = call(
        base,
        "POST",
        f"/api/v1/projects/{project['id']}/cases",
        {
            "targetId": target["id"],
            "name": "Flaky evidence case",
            "kind": "ASSERTION",
            "parameters": {"type": "object", "additionalProperties": True},
            "tags": ["acceptance", "history"],
            "timeoutSeconds": 30,
        },
    )
    source = "acceptance/generated/history_case.py"
    script = call(
        base,
        "POST",
        f"/api/v1/cases/{case['id']}/scripts",
        {
            "runner": runner,
            "sourceRef": source,
            "checksum": "sha256:" + hashlib.sha256(source.encode()).hexdigest(),
        },
    )
    workflow = call(
        base,
        "POST",
        f"/api/v1/projects/{project['id']}/workflows",
        {"targetId": target["id"], "name": "History workflow"},
    )
    workflow = call(
        base,
        "PUT",
        f"/api/v1/workflows/{workflow['id']}/graph",
        {
            "expectedVersion": workflow["draftRevision"],
            "nodes": [{
                "id": str(uuid.uuid4()),
                "type": "CASE",
                "referenceId": case["id"],
                "referenceVersion": script["version"],
                "required": True,
                "timeoutSeconds": 30,
                "parameterOverrides": parameter_overrides or {},
                "positionX": 0.0,
                "positionY": 0.0,
            }],
            "edges": [],
        },
    )
    published = call(base, "POST", f"/api/v1/workflows/{workflow['id']}/publish", {"requestKey": str(uuid.uuid4())})
    return {
        "projectId": project["id"],
        "targetId": target["id"],
        "environmentId": environment["id"],
        "caseId": case["id"],
        "workflowId": workflow["id"],
        "workflowVersion": published["version"],
    }


def complete_run(base: str, assets: dict[str, Any], worker_id: str, status: str, index: int) -> str:
    run = call(base, "POST", "/api/v1/runs", {
        **{key: assets[key] for key in ("projectId", "targetId", "environmentId", "workflowId", "workflowVersion")},
        "priority": 5,
        "maxConcurrency": 1,
        "requestKey": str(uuid.uuid4()),
    })
    task = run["tasks"][0]
    envelope = call(base, "POST", f"/api/v1/tasks/{task['id']}/claim", {
        "runId": run["id"], "messageId": str(uuid.uuid4()), "workerId": worker_id,
    })
    attempt_id = envelope["attemptId"]
    lease_token = envelope["leaseToken"]
    call(base, "POST", f"/api/v1/attempts/{attempt_id}/start", {"workerId": worker_id, "leaseToken": lease_token})
    failure = None if status == "PASSED" else {
        "type": "PRODUCT_DEFECT", "message": f"deterministic failure {index}", "retryable": False, "details": {},
    }
    call(base, "POST", f"/api/v1/attempts/{attempt_id}/callback", {
        "schemaVersion": "1.0.0",
        "callbackKey": str(uuid.uuid5(uuid.NAMESPACE_URL, f"history:{attempt_id}")),
        "attemptId": attempt_id,
        "leaseToken": lease_token,
        "workerId": worker_id,
        "status": status,
        "completedAt": datetime.now(timezone.utc).isoformat(),
        "durationMs": 100 + index,
        "summary": f"history acceptance {status}",
        "failure": failure,
        "artifacts": [],
        "metrics": {},
    })
    return run["id"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance" / "evidence" / "report-history-http.json")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    worker_id = "history-http-worker"
    health = call(base, "GET", "/actuator/health")
    if health.get("status") != "UP":
        raise RuntimeError(f"backend is unhealthy: {health}")
    call(base, "POST", "/api/v1/workers/register", {
        "workerId": worker_id,
        "protocolVersion": "1.0",
        "capabilities": ["RUNNER_PYTEST_HTTP", "HTTP", "PLATFORM_ANY"],
        "maxConcurrency": 1,
    })
    assets = create_assets(base)
    statuses = ["PASSED", "ASSERTION_FAILED", "PASSED"]
    run_ids = [complete_run(base, assets, worker_id, status, index) for index, status in enumerate(statuses)]
    history = call(base, "GET", f"/api/v1/reports/runs/cases/{assets['caseId']}/history")
    observed = [item["finalStatus"] for item in history["recentResults"]]
    if not history["flaky"] or history["sampleSize"] != 3 or observed != list(reversed(statuses)):
        raise AssertionError(f"unexpected history response: {history}")
    evidence = {
        "schema": "io.testforge/report-history-http/v1",
        "completedAt": datetime.now(timezone.utc).isoformat(),
        "assets": assets,
        "runIds": run_ids,
        "history": history,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASSED", "evidence": str(args.output), "caseId": assets["caseId"], "runIds": run_ids}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
