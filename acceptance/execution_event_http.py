#!/usr/bin/env python3
"""Verify persisted execution-event history, exclusive cursor replay, and metrics."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def get(base: str, path: str):
    with urlopen(Request(base + path, headers={"Accept": "application/json"}), timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))["data"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance" / "evidence" / "reliability-observability-delivery" / "event-replay.json")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    history = get(base, f"/api/v1/runs/{args.run_id}/events/history?afterEventId=0&limit=100")
    events = history["events"]
    if len(events) < 2 or events[0]["type"] != "RUN_CREATED":
        raise AssertionError(f"incomplete persisted history: {history}")
    ids = [event["id"] for event in events]
    if ids != sorted(ids) or len(ids) != len(set(ids)):
        raise AssertionError(f"event ids are not strictly ordered: {ids}")
    cursor = ids[0]
    replay = get(base, f"/api/v1/runs/{args.run_id}/events/history?afterEventId={cursor}&limit=100")
    if [event["id"] for event in replay["events"]] != ids[1:]:
        raise AssertionError("afterEventId must be an exclusive cursor")
    resources = get(base, "/api/v1/monitoring/resources")
    with urlopen(base + "/actuator/prometheus", timeout=15) as response:
        prometheus = response.read().decode("utf-8")
    expected_metrics = ["testforge_tasks_queued", "testforge_resource_capacity", "testforge_dispatch_redeliveries"]
    missing = [name for name in expected_metrics if name not in prometheus]
    if missing:
        raise AssertionError(f"missing prometheus metrics: {missing}")
    evidence = {
        "schema": "io.testforge/execution-event-http/v1",
        "executedAt": datetime.now(timezone.utc).isoformat(),
        "runId": args.run_id,
        "result": "PASS",
        "eventIds": ids,
        "eventTypes": [event["type"] for event in events],
        "exclusiveCursor": cursor,
        "replayedEventIds": [event["id"] for event in replay["events"]],
        "resourceSnapshot": resources,
        "metrics": expected_metrics,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
