#!/usr/bin/env python3
"""TFP-034 real HTTP/MySQL acceptance for immutable per-task target revisions."""

from __future__ import annotations

import argparse
import json
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def request(base_url: str, method: str, path: str, payload: dict | None = None):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = Request(
        f"{base_url.rstrip('/')}{path}",
        data=body,
        method=method,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urlopen(req, timeout=30) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        response_body = error.read().decode("utf-8")
        try:
            parsed = json.loads(response_body)
        except json.JSONDecodeError:
            parsed = {"raw": response_body}
        return error.code, parsed


def api_data(base_url: str, method: str, path: str, payload: dict | None = None, expected=200):
    status, response = request(base_url, method, path, payload)
    if status != expected:
        raise AssertionError(f"{method} {path}: expected {expected}, got {status}: {response}")
    return response["data"]


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(repo), *args], text=True, encoding="utf-8"
    ).strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument(
        "--evidence",
        type=Path,
        default=Path(__file__).resolve().parent
        / "evidence"
        / "target-revision-deployment"
        / "tfp-034.json",
    )
    args = parser.parse_args()
    repo = args.repo.resolve()
    head = git(repo, "rev-parse", "HEAD")
    previous = git(repo, "rev-parse", "HEAD~1")
    branch = f"tfp-034-acceptance-{uuid.uuid4().hex[:8]}"
    suffix = uuid.uuid4().hex[:8]
    git(repo, "branch", branch, head)

    evidence: dict = {
        "task": "TFP-034",
        "executedAt": datetime.now(timezone.utc).isoformat(),
        "repository": str(repo),
        "initialHead": head,
        "previousCommit": previous,
        "temporaryBranch": branch,
    }

    try:
        project = api_data(
            args.base_url,
            "POST",
            "/api/v1/projects",
            {"name": f"TFP-034 acceptance {suffix}", "code": f"tfp034-{suffix}"},
            201,
        )
        target = api_data(
            args.base_url,
            "POST",
            f"/api/v1/projects/{project['id']}/targets",
            {
                "name": "local-git-target",
                "type": "HTTP_SERVICE",
                "repositoryUrl": str(repo),
                "defaultBranch": branch,
            },
            201,
        )
        environment = api_data(
            args.base_url,
            "POST",
            f"/api/v1/targets/{target['id']}/environments",
            {
                "name": "acceptance-local",
                "endpoint": "http://127.0.0.1:8081",
                "config": {},
                "secretRefs": {},
            },
            201,
        )

        cases = []
        for index in (1, 2):
            case = api_data(
                args.base_url,
                "POST",
                f"/api/v1/projects/{project['id']}/cases",
                {
                    "targetId": target["id"],
                    "name": f"revision-case-{index}",
                    "kind": "ASSERTION",
                    "parameters": {"type": "object", "additionalProperties": True},
                    "tags": ["acceptance", "tfp-034"],
                    "timeoutSeconds": 30,
                },
                201,
            )
            script = api_data(
                args.base_url,
                "POST",
                f"/api/v1/cases/{case['id']}/scripts",
                {
                    "runner": "pytest-http",
                    "sourceRef": f"acceptance/tfp034_case_{index}.py",
                    "checksum": f"sha256:{str(index) * 64}",
                },
                201,
            )
            cases.append((case, script))

        workflow = api_data(
            args.base_url,
            "POST",
            f"/api/v1/projects/{project['id']}/workflows",
            {"targetId": target["id"], "name": "two-revision-workflow"},
            201,
        )
        node_ids = [str(uuid.uuid4()), str(uuid.uuid4())]
        nodes = []
        for index, ((case, script), node_id) in enumerate(zip(cases, node_ids), start=1):
            nodes.append(
                {
                    "id": node_id,
                    "type": "CASE",
                    "referenceId": case["id"],
                    "referenceVersion": script["version"],
                    "required": True,
                    "timeoutSeconds": 30,
                    "parameterOverrides": {},
                    "positionX": float(index * 100),
                    "positionY": 100.0,
                }
            )
        api_data(
            args.base_url,
            "PUT",
            f"/api/v1/workflows/{workflow['id']}/graph",
            {"expectedVersion": 0, "nodes": nodes, "edges": []},
        )
        published = api_data(
            args.base_url,
            "POST",
            f"/api/v1/workflows/{workflow['id']}/publish",
            {"requestKey": str(uuid.uuid4())},
        )
        compiled_node_ids = [
            next(
                node["id"]
                for node in published["compiledSnapshot"]["nodes"]
                if node["caseId"] == case["id"]
            )
            for case, _ in cases
        ]

        request_key = str(uuid.uuid4())
        run_request = {
            "projectId": project["id"],
            "targetId": target["id"],
            "environmentId": environment["id"],
            "workflowId": workflow["id"],
            "workflowVersion": published["version"],
            "priority": 5,
            "maxConcurrency": 2,
            "requestKey": request_key,
            "targetRevision": {"type": "BRANCH", "value": branch},
            "taskRevisionOverrides": [
                {
                    "workflowNodeId": compiled_node_ids[1],
                    "revision": {"type": "COMMIT", "value": previous},
                }
            ],
        }
        first = api_data(args.base_url, "POST", "/api/v1/runs", run_request, 201)
        by_node = {task["workflowNodeId"]: task for task in first["tasks"]}
        assert by_node[compiled_node_ids[0]]["targetRevision"]["resolvedCommit"] == head
        assert by_node[compiled_node_ids[1]]["targetRevision"]["resolvedCommit"] == previous

        git(repo, "branch", "-f", branch, previous)
        retried = api_data(args.base_url, "POST", "/api/v1/runs", run_request, 201)
        assert retried["id"] == first["id"]
        retry_by_node = {task["workflowNodeId"]: task for task in retried["tasks"]}
        assert retry_by_node[compiled_node_ids[0]]["targetRevision"]["resolvedCommit"] == head

        new_request = dict(run_request)
        new_request["requestKey"] = str(uuid.uuid4())
        moved = api_data(args.base_url, "POST", "/api/v1/runs", new_request, 201)
        moved_by_node = {task["workflowNodeId"]: task for task in moved["tasks"]}
        assert moved_by_node[compiled_node_ids[0]]["targetRevision"]["resolvedCommit"] == previous

        conflicting = dict(run_request)
        conflicting["targetRevision"] = {"type": "COMMIT", "value": previous}
        conflict_status, conflict_response = request(
            args.base_url, "POST", "/api/v1/runs", conflicting
        )
        assert conflict_status == 409, (conflict_status, conflict_response)

        evidence.update(
            {
                "projectId": project["id"],
                "targetId": target["id"],
                "workflowId": workflow["id"],
                "workflowVersion": published["version"],
                "firstRunId": first["id"],
                "retryRunId": retried["id"],
                "newRunAfterBranchMoveId": moved["id"],
                "firstRunCommits": {
                    node_id: by_node[node_id]["targetRevision"]["resolvedCommit"]
                    for node_id in compiled_node_ids
                },
                "retryPreservedCommit": retry_by_node[compiled_node_ids[0]]["targetRevision"]["resolvedCommit"],
                "newRunResolvedMovedBranch": moved_by_node[compiled_node_ids[0]]["targetRevision"]["resolvedCommit"],
                "idempotencyConflictStatus": conflict_status,
                "result": "PASS",
            }
        )
    finally:
        git(repo, "branch", "-D", branch)

    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
