from __future__ import annotations

import argparse
import json
import os
import subprocess
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]


def request(
        base_url: str,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
) -> Any:
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        data=payload,
        method=method,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            envelope = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} failed: HTTP {error.code} {detail}") from error
    return envelope["data"]


def git(*arguments: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(ROOT), *arguments],
        text=True,
        encoding="utf-8",
    ).strip()


def main() -> int:
    parser = argparse.ArgumentParser(description="TestForge Target Git revision HTTP acceptance")
    parser.add_argument("--base-url", default="http://127.0.0.1:8081/api/v1")
    args = parser.parse_args()

    projects = request(args.base_url, "GET", "/projects")
    existing = next((item for item in projects if item["code"] == "revision-acceptance"), None)
    project = request(args.base_url, "GET", f"/projects/{existing['id']}") if existing else request(
        args.base_url,
        "POST",
        "/projects",
        {"name": "版本解析验收", "code": "revision-acceptance"},
    )
    target_body = {
        "name": "TestForge Repository",
        "type": "DESKTOP",
        "repositoryUrl": str(ROOT),
        "defaultBranch": git("branch", "--show-current"),
    }
    target = request(
        args.base_url,
        "POST",
        f"/projects/{project['id']}/targets",
        target_body,
    )

    expected = git("rev-parse", "HEAD")
    resolved: dict[str, str] = {}
    selectors = {
        "default": {"type": "DEFAULT_BRANCH"},
        "branch": {"type": "BRANCH", "value": target_body["defaultBranch"]},
        "commit": {"type": "COMMIT", "value": expected},
    }
    for label, selector in selectors.items():
        result = request(
            args.base_url,
            "POST",
            f"/targets/{target['id']}/revisions/resolve",
            selector,
        )
        resolved[label] = result["commitSha"]

    tag = f"tfp033-http-acceptance-{os.getpid()}"
    git("tag", tag, expected)
    try:
        result = request(
            args.base_url,
            "POST",
            f"/targets/{target['id']}/revisions/resolve",
            {"type": "TAG", "value": tag},
        )
        resolved["tag"] = result["commitSha"]
    finally:
        git("tag", "-d", tag)

    if any(commit != expected for commit in resolved.values()):
        raise AssertionError(f"revision mismatch: expected={expected}, actual={resolved}")

    print(json.dumps({
        "status": "PASS",
        "projectId": project["id"],
        "targetId": target["id"],
        "defaultBranch": target_body["defaultBranch"],
        "expectedCommit": expected,
        "resolved": resolved,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
