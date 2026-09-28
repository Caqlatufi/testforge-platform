#!/usr/bin/env python3
"""Exercise Project -> Target/Environment -> Case/Suite/Script through real HTTP APIs."""

from __future__ import annotations

import argparse
import hashlib
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def request(base: str, method: str, path: str, body: dict[str, Any] | None = None) -> tuple[int, Any]:
    req = Request(base + path, data=None if body is None else json.dumps(body).encode(), method=method,
                  headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urlopen(req, timeout=10) as response:
            return response.status, json.loads(response.read().decode())
    except HTTPError as error:
        return error.code, json.loads(error.read().decode())


def ok(base: str, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
    status, payload = request(base, method, path, body)
    if status >= 400:
        raise AssertionError(f"{method} {path} -> {status}: {payload}")
    return payload.get("data", payload)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance" / "evidence" / "asset-management-http.json")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    suffix = uuid.uuid4().hex[:10]

    before = ok(base, "GET", "/api/v1/projects")
    project = ok(base, "POST", "/api/v1/projects", {"name": "Asset acceptance", "code": f"asset-{suffix}"})
    project = ok(base, "PUT", f"/api/v1/projects/{project['id']}", {"name": "Asset acceptance edited", "code": f"asset-edit-{suffix}"})
    target = ok(base, "POST", f"/api/v1/projects/{project['id']}/targets", {"name": "Web target", "type": "WEB"})
    target = ok(base, "PUT", f"/api/v1/targets/{target['id']}", {"name": "HTTP target edited", "type": "HTTP_SERVICE"})
    environment = ok(base, "POST", f"/api/v1/targets/{target['id']}/environments", {
        "name": "Local", "endpoint": "http://127.0.0.1:8090", "config": {"locale": "zh-CN"}, "secretRefs": {},
    })
    environment = ok(base, "PUT", f"/api/v1/environments/{environment['id']}", {
        "name": "Local edited", "endpoint": "http://127.0.0.1:8091", "config": {"locale": "en-US"}, "secretRefs": {},
    })
    case = ok(base, "POST", f"/api/v1/projects/{project['id']}/cases", {
        "targetId": target["id"], "name": "Health case", "kind": "ASSERTION",
        "parameters": {"type": "object", "additionalProperties": True}, "tags": ["smoke"], "timeoutSeconds": 20,
    })
    case = ok(base, "PUT", f"/api/v1/cases/{case['id']}", {
        "targetId": target["id"], "name": "Health case edited", "kind": "ASSERTION",
        "parameters": {"type": "object", "additionalProperties": True}, "tags": ["P0", "smoke"], "timeoutSeconds": 30,
    })
    scripts = []
    for version in (1, 2):
        source = f"acceptance/generated/health_v{version}.py"
        scripts.append(ok(base, "POST", f"/api/v1/cases/{case['id']}/scripts", {
            "runner": "pytest-http", "sourceRef": source,
            "checksum": "sha256:" + hashlib.sha256(source.encode()).hexdigest(),
        }))
    suite = ok(base, "POST", f"/api/v1/projects/{project['id']}/suites", {
        "targetId": target["id"], "name": "Smoke suite", "caseIds": [case["id"]], "tags": ["smoke"], "parameterBindings": {},
    })
    suite = ok(base, "PUT", f"/api/v1/suites/{suite['id']}", {
        "expectedVersion": suite["version"], "name": "Smoke suite edited", "caseIds": [case["id"]],
        "tags": ["P0", "smoke"], "parameterBindings": {"locale": "en-US"},
    })

    aggregate = ok(base, "GET", f"/api/v1/projects/{project['id']}")
    cases = ok(base, "GET", f"/api/v1/projects/{project['id']}/cases")
    stored_scripts = ok(base, "GET", f"/api/v1/cases/{case['id']}/scripts")
    suites = ok(base, "GET", f"/api/v1/projects/{project['id']}/suites?{urlencode({'targetId': target['id']})}")
    after = ok(base, "GET", "/api/v1/projects")
    invalid_status, invalid = request(base, "POST", "/api/v1/projects", {"name": "Invalid", "code": "INVALID"})
    missing_status, missing = request(base, "GET", f"/api/v1/projects/{uuid.uuid4()}")

    assert len(after) == len(before) + 1
    assert aggregate["name"] == "Asset acceptance edited"
    assert aggregate["targets"][0]["name"] == "HTTP target edited"
    assert aggregate["targets"][0]["environments"][0]["name"] == "Local edited"
    assert cases[0]["name"] == "Health case edited" and cases[0]["scriptVersionId"] == scripts[-1]["id"]
    assert [item["version"] for item in stored_scripts] == [1, 2]
    assert suites[0]["caseIds"] == [case["id"]] and suites[0]["name"] == "Smoke suite edited"
    assert invalid_status == 400 and invalid.get("code") == "VALIDATION_ERROR"
    assert missing_status == 404 and missing.get("code") == "RESOURCE_NOT_FOUND"

    evidence = {
        "schema": "io.testforge/asset-management-http/v1",
        "completedAt": datetime.now(timezone.utc).isoformat(),
        "projectCountBefore": len(before), "projectCountAfter": len(after),
        "project": aggregate, "cases": cases, "scripts": stored_scripts, "suites": suites,
        "errorResponses": {"invalid": invalid, "missing": missing},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASSED", "evidence": str(args.output), "projectId": project["id"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
