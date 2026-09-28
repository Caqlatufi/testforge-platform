#!/usr/bin/env python3
"""Create and read one persistent baseline/candidate comparison group through public APIs."""
from __future__ import annotations

import argparse, hashlib, json, subprocess, uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]

def call(base: str, method: str, path: str, body: dict | None = None) -> dict:
    req = Request(base.rstrip("/") + path, data=None if body is None else json.dumps(body).encode(), method=method, headers={"Content-Type":"application/json"})
    with urlopen(req, timeout=30) as response: return json.loads(response.read().decode())["data"]

def git(revision: str) -> str:
    return subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", revision], text=True, encoding="utf-8").strip()

def main() -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--base-url", default="http://127.0.0.1:8081"); parser.add_argument("--output", type=Path, default=ROOT / "testforge-platform/acceptance/evidence/target-revision-deployment/tfp-045-comparison-group.json"); args = parser.parse_args()
    base = args.base_url.rstrip("/"); suffix = uuid.uuid4().hex[:10]
    project = call(base,"POST","/api/v1/projects",{"name":"Comparison Group Acceptance","code":f"comparison-{suffix}"})
    target = call(base,"POST",f"/api/v1/projects/{project['id']}/targets",{"name":"Local Git Target","type":"DESKTOP","repositoryUrl":str(ROOT),"defaultBranch":subprocess.check_output(["git","-C",str(ROOT),"branch","--show-current"],text=True,encoding="utf-8").strip()})
    environment = call(base,"POST",f"/api/v1/targets/{target['id']}/environments",{"name":"Local","endpoint":"http://127.0.0.1","config":{},"secretRefs":{}})
    case = call(base,"POST",f"/api/v1/projects/{project['id']}/cases",{"targetId":target["id"],"name":"Comparison Case","kind":"ASSERTION","parameters":{"type":"object"},"tags":["comparison"],"timeoutSeconds":30})
    source = "acceptance/generated/comparison.py"; script = call(base,"POST",f"/api/v1/cases/{case['id']}/scripts",{"runner":"pytest-http","sourceRef":source,"checksum":"sha256:"+hashlib.sha256(source.encode()).hexdigest()})
    workflow = call(base,"POST",f"/api/v1/projects/{project['id']}/workflows",{"targetId":target["id"],"name":"Comparison Workflow"})
    workflow = call(base,"PUT",f"/api/v1/workflows/{workflow['id']}/graph",{"expectedVersion":workflow["draftRevision"],"nodes":[{"id":str(uuid.uuid4()),"type":"CASE","referenceId":case["id"],"referenceVersion":script["version"],"required":True,"timeoutSeconds":30,"parameterOverrides":{},"positionX":0.0,"positionY":0.0}],"edges":[]})
    published = call(base,"POST",f"/api/v1/workflows/{workflow['id']}/publish",{"requestKey":str(uuid.uuid4())})
    request_key = str(uuid.uuid4()); baseline = git("HEAD~1"); candidate = git("HEAD")
    body = {"projectId":project["id"],"targetId":target["id"],"environmentId":environment["id"],"workflowId":workflow["id"],"workflowVersion":published["version"],"priority":5,"maxConcurrency":2,"processConcurrency":2,"deviceConcurrency":1,"requestKey":request_key,"baselineRevision":{"type":"COMMIT","value":baseline},"candidateRevision":{"type":"COMMIT","value":candidate}}
    created = call(base,"POST","/api/v1/comparison-runs",body); retried = call(base,"POST","/api/v1/comparison-runs",body); loaded = call(base,"GET",f"/api/v1/comparison-runs/{created['id']}"); report = call(base,"GET",f"/api/v1/reports/comparison-groups/{created['id']}")
    assert created["id"] == retried["id"] == loaded["id"]
    assert created["baselineResolvedCommit"] == baseline and created["candidateResolvedCommit"] == candidate
    assert created["baselineRun"]["comparisonGroupId"] == created["id"] and created["candidateRun"]["comparisonGroupId"] == created["id"]
    evidence = {"schema":"io.testforge/comparison-group-http/v1","executedAt":datetime.now(timezone.utc).isoformat(),"result":"PASS","projectId":project["id"],"targetId":target["id"],"environmentId":environment["id"],"caseId":case["id"],"workflowId":workflow["id"],"workflowVersion":published["version"],"comparisonGroupId":created["id"],"baselineRunId":created["baselineRun"]["id"],"candidateRunId":created["candidateRun"]["id"],"baselineCommit":baseline,"candidateCommit":candidate,"idempotentRetry":True,"reportSummary":report["summary"]}
    args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+"\n",encoding="utf-8"); print(json.dumps(evidence,ensure_ascii=False)); return 0

if __name__ == "__main__": raise SystemExit(main())
