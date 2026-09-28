#!/usr/bin/env python3
"""Observe and verify a real Airtest run across isolated Worker environments."""

from __future__ import annotations

import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}


def call(base: str, path: str) -> Any:
    request = Request(
        base.rstrip("/") + path,
        headers={"Accept": "application/json"},
        method="GET",
    )
    with urlopen(request, timeout=10) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return payload.get("data", payload) if isinstance(payload, dict) else payload


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def attempts(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    return [attempt for task in snapshot.get("tasks", []) for attempt in task.get("attempts", [])]


def maximum_cross_worker_overlap(items: list[dict[str, Any]]) -> float:
    maximum = 0.0
    for index, left in enumerate(items):
        for right in items[index + 1:]:
            if not left.get("workerId") or left.get("workerId") == right.get("workerId"):
                continue
            start = max(parse_time(left["createdAt"]), parse_time(right["createdAt"]))
            end = min(parse_time(left["updatedAt"]), parse_time(right["updatedAt"]))
            maximum = max(maximum, (end - start).total_seconds())
    return round(maximum, 3)


def wall_clock_duration_ms(
    snapshot: dict[str, Any], items: list[dict[str, Any]]
) -> int:
    starts = [item.get("createdAt") for item in items if item.get("createdAt")]
    ends = [item.get("updatedAt") for item in items if item.get("updatedAt")]
    start_value = snapshot.get("createdAt") or (min(starts) if starts else None)
    end_value = snapshot.get("finishedAt") or snapshot.get("updatedAt") or (
        max(ends) if ends else None
    )
    if not start_value or not end_value:
        return 0
    return max(0, round((parse_time(end_value) - parse_time(start_value)).total_seconds() * 1000))


def verify(
    snapshot: dict[str, Any],
    report: dict[str, Any],
    devices: list[dict[str, Any]],
    observed_concurrency: list[dict[str, Any]],
    *,
    expected_tasks: int,
    min_overlap_seconds: float,
    max_duration_ms: int,
) -> dict[str, Any]:
    all_attempts = attempts(snapshot)
    succeeded = [item for item in all_attempts if item.get("state") == "SUCCEEDED"]
    worker_ids = sorted({str(item.get("workerId")) for item in succeeded if item.get("workerId")})
    observed_workers = sorted(
        {
            worker_id
            for sample in observed_concurrency
            for worker_id in sample.get("workerIds", [])
        }
    )
    devices_by_worker = {
        str(item.get("workerId")): str(item.get("deviceId"))
        for item in devices
        if item.get("workerId") and item.get("deviceId")
    }
    isolated_workers = {
        str(item.get("workerId"))
        for item in devices
        if "ISOLATED_DESKTOP" in item.get("features", [])
    }
    device_ids = sorted({devices_by_worker[item] for item in worker_ids if item in devices_by_worker})
    overlap_seconds = maximum_cross_worker_overlap(succeeded)
    elapsed_ms = wall_clock_duration_ms(snapshot, succeeded)
    artifact_errors: list[str] = []
    for result in report.get("results", []):
        attempt_id = str(result.get("attemptId") or "")
        expected_segment = f"/attempts/{attempt_id}/"
        for key in result.get("artifactKeys", []):
            if expected_segment not in str(key):
                artifact_errors.append(str(key))

    checks = {
        "runSucceeded": snapshot.get("state") == "SUCCEEDED",
        "runAllowsParallelism": int(snapshot.get("maxConcurrency", 0)) >= 2,
        "expectedTaskCount": len(snapshot.get("tasks", [])) == expected_tasks,
        "allTasksPassed": len(succeeded) == expected_tasks and int(report.get("passed", 0)) == expected_tasks,
        "multipleWorkers": len(worker_ids) >= 2,
        "multipleDevices": len(device_ids) >= 2,
        "isolatedDesktopDeclared": set(worker_ids).issubset(isolated_workers),
        "concurrencyObservedLive": len(observed_workers) >= 2,
        "historicalOverlap": overlap_seconds >= min_overlap_seconds,
        "artifactOwnership": not artifact_errors,
        "durationWithinTarget": 0 < elapsed_ms <= max_duration_ms,
    }
    return {
        "schemaVersion": "1.0.0",
        "verifiedAt": datetime.now(timezone.utc).isoformat(),
        "status": "PASS" if all(checks.values()) else "FAIL",
        "runId": snapshot.get("id"),
        "checks": checks,
        "metrics": {
            "taskCount": len(snapshot.get("tasks", [])),
            "passed": report.get("passed"),
            "wallClockDurationMs": elapsed_ms,
            "taskDurationSumMs": report.get("durationMs"),
            "maxDurationMs": max_duration_ms,
            "workerIds": worker_ids,
            "deviceIds": device_ids,
            "observedConcurrentWorkerIds": observed_workers,
            "maximumCrossWorkerOverlapSeconds": overlap_seconds,
            "artifactOwnershipErrors": artifact_errors,
        },
        "liveConcurrencySamples": observed_concurrency,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--expected-tasks", type=int, default=8)
    parser.add_argument("--min-overlap-seconds", type=float, default=2.0)
    parser.add_argument("--serial-baseline-ms", type=int, default=95390)
    parser.add_argument("--max-duration-ratio", type=float, default=0.8)
    parser.add_argument("--poll-seconds", type=float, default=0.25)
    parser.add_argument("--timeout-seconds", type=float, default=180)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "acceptance" / "evidence" / "virtual-environment-parallel" / "latest.json",
    )
    args = parser.parse_args()

    deadline = time.monotonic() + args.timeout_seconds
    samples: list[dict[str, Any]] = []
    snapshot: dict[str, Any]
    while True:
        snapshot = call(args.base_url, f"/api/v1/runs/{args.run_id}")
        running = [item for item in attempts(snapshot) if item.get("state") == "RUNNING"]
        running_workers = sorted(
            {str(item.get("workerId")) for item in running if item.get("workerId")}
        )
        if len(running_workers) >= 2:
            samples.append(
                {
                    "observedAt": datetime.now(timezone.utc).isoformat(),
                    "workerIds": running_workers,
                    "attemptIds": sorted(str(item.get("id")) for item in running),
                }
            )
        if snapshot.get("state") in TERMINAL:
            break
        if time.monotonic() >= deadline:
            raise TimeoutError(f"run {args.run_id} 未在 {args.timeout_seconds}s 内结束")
        time.sleep(args.poll_seconds)

    report = call(args.base_url, f"/api/v1/reports/runs/{args.run_id}")
    devices = call(args.base_url, "/api/v1/device-slots")
    evidence = verify(
        snapshot,
        report,
        devices,
        samples,
        expected_tasks=args.expected_tasks,
        min_overlap_seconds=args.min_overlap_seconds,
        max_duration_ms=round(args.serial_baseline_ms * args.max_duration_ratio),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": evidence["status"], "evidence": str(args.output)}, ensure_ascii=False))
    return 0 if evidence["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
