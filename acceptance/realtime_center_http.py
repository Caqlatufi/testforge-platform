#!/usr/bin/env python3
"""Verify realtime center, reconnect correction, panels and complete reports over HTTP/SSE."""

from __future__ import annotations

import argparse
import http.client
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def call(base: str, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
    request = Request(base + path, data=None if body is None else json.dumps(body).encode(), method=method,
                      headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urlopen(request, timeout=10) as response:
        payload = json.loads(response.read().decode())
    return payload.get("data", payload)


def first_sse(base: str, run_id: str, last_event_id: str | None = None) -> dict[str, Any]:
    parsed = urlparse(base)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=5)
    headers = {"Accept": "text/event-stream"}
    if last_event_id:
        headers["Last-Event-ID"] = last_event_id
    connection.request("GET", f"/api/v1/runs/{run_id}/events", headers=headers)
    response = connection.getresponse()
    if response.status != 200:
        raise AssertionError(f"SSE returned {response.status}")
    event: dict[str, Any] = {}
    while True:
        line = response.readline().decode().rstrip("\r\n")
        if not line:
            break
        key, value = line.split(":", 1)
        value = value.lstrip()
        event[key] = json.loads(value) if key == "data" else value
    connection.close()
    return event


def archived_artifact_evidence() -> dict[str, Any]:
    path = ROOT / "acceptance" / "evidence" / "mvp" / "latest.json"
    if not path.is_file():
        return {"evidence": str(path), "verified": False, "reason": "missing baseline evidence"}
    evidence = json.loads(path.read_text(encoding="utf-8"))
    runs = evidence.get("runs", [])
    keys = [key for run in runs for key in run.get("report", {}).get("artifactKeys", [])]
    objects = [item for run in runs for item in run.get("artifactObjects", [])]
    categories = {
        "screenshot": any("screenshots/" in key for key in keys),
        "log": any(key.endswith("worker.log") or key.endswith("log.txt") for key in keys),
        "structuredResult": any(key.endswith(".json") or key.endswith(".xml") for key in keys),
        "airtestHtml": any(key.endswith(".html") for key in keys),
    }
    return {"evidence": str(path.relative_to(ROOT)), "verified": all(categories.values()) and bool(objects),
            "categories": categories, "verifiedObjectCount": len(objects)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--history-evidence", type=Path, default=ROOT / "acceptance" / "evidence" / "report-history-http.json")
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance" / "evidence" / "realtime-center-http.json")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    history_seed = json.loads(args.history_evidence.read_text(encoding="utf-8"))
    run_ids = history_seed["runIds"]
    reports = [call(base, "GET", f"/api/v1/reports/runs/{run_id}") for run_id in run_ids]
    passed = next(report for report in reports if report["passed"] == 1)
    failed = next(report for report in reports if report["failed"] == 1)
    run_id = passed["runId"]
    snapshot = call(base, "GET", f"/api/v1/runs/{run_id}")
    graph = call(base, "GET", f"/api/v1/runs/{run_id}/graph")
    initial = first_sse(base, run_id)
    reconnected = first_sse(base, run_id, f"{run_id}:0")
    ai = call(base, "GET", f"/api/v1/reports/{run_id}/diagnosis/status")
    case_id = passed["results"][0]["caseId"]
    case_history = call(base, "GET", f"/api/v1/reports/runs/cases/{case_id}/history")

    worker_id = "realtime-panel-worker"
    call(base, "POST", "/api/v1/workers/register", {"workerId": worker_id, "protocolVersion": "1.0", "capabilities": ["RUNNER_AIRTEST", "WINDOWS_UI", "PLATFORM_WINDOWS"], "maxConcurrency": 2})
    device = call(base, "PUT", f"/api/v1/workers/{worker_id}/devices", {"deviceId": "panel-device", "platform": "WINDOWS", "deviceUri": "Windows:///panel-device", "features": ["WINDOWS_UI"], "resolution": "1280x800"})
    workers = call(base, "GET", "/api/v1/workers")
    devices = call(base, "GET", "/api/v1/device-slots")

    assert initial["event"] == "snapshot" and initial["data"]["id"] == run_id
    assert reconnected["event"] == "resync" and reconnected["data"]["version"] == snapshot["version"]
    assert graph["nodes"] and graph["nodes"][0]["state"] == "SUCCEEDED"
    assert passed["p50DurationMs"] > 0 and passed["p95DurationMs"] >= passed["p50DurationMs"]
    assert failed["failureCategories"] and case_history["flaky"]
    assert not ai["available"] and ai["status"] == "UNAVAILABLE"
    assert any(worker["workerId"] == worker_id and worker["maxConcurrency"] == 2 for worker in workers)
    panel_device = next(item for item in devices if item["slotId"] == device["slotId"])
    assert panel_device["status"] == "AVAILABLE" and panel_device["leaseState"] == "AVAILABLE"

    evidence = {
        "schema": "io.testforge/realtime-center-http/v1",
        "completedAt": datetime.now(timezone.utc).isoformat(),
        "runId": run_id,
        "snapshotVersion": snapshot["version"],
        "sse": {"initialEvent": initial["event"], "initialId": initial["id"],
                "reconnectEvent": reconnected["event"], "reconnectId": reconnected["id"],
                "correctedVersion": reconnected["data"]["version"]},
        "graph": {"nodeCount": len(graph["nodes"]), "edgeCount": len(graph["edges"])},
        "reports": {"passedRun": passed, "failedRun": failed, "flaky": case_history},
        "aiDiagnosis": ai,
        "worker": next(worker for worker in workers if worker["workerId"] == worker_id),
        "deviceSlot": panel_device,
        "archivedArtifacts": archived_artifact_evidence(),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASSED", "evidence": str(args.output), "runId": run_id,
                      "sseReconnect": reconnected["event"], "ai": ai["status"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
