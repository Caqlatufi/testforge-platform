#!/usr/bin/env python3
"""Reserve a real DeviceSlot through task claim, abandon it, and verify lease reaping."""
from __future__ import annotations

import argparse, json, sys, time, uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "acceptance"))
from report_history_http import call, create_assets  # noqa: E402

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--timeout", type=int, default=45)
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance/evidence/reliability-observability-delivery/device-slot-recovery.json")
    args = parser.parse_args(); base = args.base_url.rstrip("/")
    assets = create_assets(base, runner="airtest", parameter_overrides={"platform":"WINDOWS","requiredFeatures":["UI_AUTOMATION"]})
    worker = f"device-recovery-{uuid.uuid4().hex[:8]}"; device = f"sandbox-{uuid.uuid4().hex[:8]}"
    call(base, "POST", "/api/v1/workers/register", {"workerId":worker,"protocolVersion":"1.0","capabilities":["RUNNER_AIRTEST","PLATFORM_WINDOWS","UI_AUTOMATION"],"maxConcurrency":1})
    slot = call(base, "PUT", f"/api/v1/workers/{worker}/devices", {"deviceId":device,"platform":"WINDOWS","deviceUri":f"Windows:///{device}","features":["UI_AUTOMATION"],"resolution":"1280x720"})
    run = call(base, "POST", "/api/v1/runs", {**{key:assets[key] for key in ("projectId","targetId","environmentId","workflowId","workflowVersion")},"priority":5,"maxConcurrency":1,"processConcurrency":1,"deviceConcurrency":1,"requestKey":str(uuid.uuid4())})
    task = run["tasks"][0]
    envelope = call(base, "POST", f"/api/v1/tasks/{task['id']}/claim", {"runId":run["id"],"messageId":str(uuid.uuid4()),"workerId":worker})
    call(base, "POST", f"/api/v1/attempts/{envelope['attemptId']}/start", {"workerId":worker,"leaseToken":envelope["leaseToken"]})
    deadline = time.monotonic() + args.timeout; released = None
    while time.monotonic() < deadline:
        slots = call(base, "GET", "/api/v1/device-slots")
        released = next(item for item in slots if item["slotId"] == slot["slotId"])
        if released["leaseState"] == "AVAILABLE" and released.get("currentAttemptId") is None: break
        time.sleep(1)
    else: raise TimeoutError(f"DeviceSlot lease not released: {released}")
    evidence = {"schema":"io.testforge/device-slot-recovery/v1","executedAt":datetime.now(timezone.utc).isoformat(),"result":"PASS","runId":run["id"],"taskId":task["id"],"attemptId":envelope["attemptId"],"slotId":slot["slotId"],"leaseState":released["leaseState"],"currentAttemptId":released.get("currentAttemptId")}
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False)); return 0

if __name__ == "__main__": raise SystemExit(main())
