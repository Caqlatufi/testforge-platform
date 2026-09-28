from __future__ import annotations

import argparse
import json
from pathlib import Path

from .model import HttpCaseSpec
from .runner import PytestHttpRunner


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    task = json.loads(Path(args.task).read_text(encoding="utf-8"))
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    execution = task["execution"]
    parameters = execution.get("parameters", {})
    environment = execution["environment"]
    resolved_script = execution.get("resolvedScriptPath")
    spec = HttpCaseSpec(
        name=str(parameters.get("name", execution.get("sourceRef", "http-case"))),
        endpoint=environment["endpoint"],
        path=str(parameters.get("path", "/")),
        method=str(parameters.get("method", "GET")),
        expected_status=int(parameters.get("expectedStatus", 200)),
        headers=parameters.get("headers", {}),
        query=parameters.get("query", {}),
        json_body=parameters.get("jsonBody"),
        expected_json_subset=parameters.get("expectedJsonSubset"),
        expected_body_contains=parameters.get("expectedBodyContains"),
        expected_headers=parameters.get("expectedHeaders", {}),
        request_timeout_seconds=float(parameters.get("requestTimeoutSeconds", 10)),
    )
    runner = PytestHttpRunner()
    result = (
        runner.run_path(Path(resolved_script), timeout_seconds=float(execution["timeoutSeconds"]))
        if resolved_script else runner.run(spec, timeout_seconds=float(execution["timeoutSeconds"]))
    )
    (output / "pytest.log").write_text(result.logs, encoding="utf-8")
    if result.junit_xml is not None:
        (output / "junit.xml").write_bytes(result.junit_xml)
    payload = {
        "status": result.status.value,
        "durationMs": result.duration_ms,
        "summary": result.summary,
        "failure": None if result.failure is None else {
            "type": result.failure.type.value,
            "message": result.failure.message,
            "retryable": result.failure.retryable,
            "stackDigest": result.failure.stack_digest,
            "details": dict(result.failure.details),
        },
    }
    (output / "result.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
