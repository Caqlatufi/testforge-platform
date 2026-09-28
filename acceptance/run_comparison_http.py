#!/usr/bin/env python3
"""Verify PASS->FAIL and FAIL->PASS run comparison over the public HTTP API."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


def compare(base: str, left: str, right: str) -> dict:
    query = urlencode({"baselineRunId": left, "candidateRunId": right})
    with urlopen(f"{base}/api/v1/reports/comparisons?{query}", timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))["data"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--baseline-run-id", required=True)
    parser.add_argument("--candidate-run-id", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance" / "evidence" / "target-revision-deployment" / "tfp-036-comparison.json")
    args = parser.parse_args()
    regression = compare(args.base_url.rstrip("/"), args.baseline_run_id, args.candidate_run_id)
    fixed = compare(args.base_url.rstrip("/"), args.candidate_run_id, args.baseline_run_id)
    if regression["summary"]["regressions"] != 1 or fixed["summary"]["fixed"] != 1:
        raise AssertionError({"regression": regression, "fixed": fixed})
    evidence = {"schema": "io.testforge/run-comparison-http/v1", "executedAt": datetime.now(timezone.utc).isoformat(), "result": "PASS", "regression": regression, "fixed": fixed}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": "PASS", "evidence": str(args.output)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
