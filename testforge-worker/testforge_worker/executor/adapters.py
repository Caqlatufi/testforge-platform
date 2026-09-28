from __future__ import annotations

import json
import sys
from pathlib import Path

from testforge_worker.artifact import ArtifactStore, LocalArtifactStore
from testforge_worker.executor.airtest import AirtestAdapter
from testforge_worker.executor.artifact_support import attempt_directory, build_artifact
from testforge_worker.executor.asset_resolver import ManagedAssetResolver, is_managed_asset
from testforge_worker.runtime.model import (
    AttemptFailure,
    AttemptResult,
    ProcessOutcome,
    ProcessSpec,
    TaskEnvelope,
)


class PytestHttpAdapter:
    def __init__(self, artifact_store: ArtifactStore | None = None, asset_resolver: ManagedAssetResolver | None = None) -> None:
        self._directories: dict[str, Path] = {}
        self._artifact_store = artifact_store or LocalArtifactStore()
        self._asset_resolver = asset_resolver

    def prepare(self, task: TaskEnvelope) -> ProcessSpec:
        directory = attempt_directory(task)
        self._directories[task.attempt_id] = directory
        execution = dict(task.execution)
        script = execution.get("script", {})
        source_ref = str(script.get("sourceRef") or execution.get("sourceRef", ""))
        if is_managed_asset(source_ref):
            if self._asset_resolver is None:
                raise OSError("Worker 未配置 TestForge Asset Resolver")
            checksum = str(script.get("checksum") or execution.get("checksum", ""))
            execution["resolvedScriptPath"] = str(
                self._asset_resolver.materialize(source_ref, checksum, directory / "case-asset")
            )
        task_file = directory / "task.json"
        task_file.write_text(json.dumps({"execution": execution}, ensure_ascii=False), encoding="utf-8")
        return ProcessSpec([
            sys.executable, "-m", "testforge_worker.executor.pytest_http.entry",
            "--task", str(task_file), "--output", str(directory),
        ])

    def result(self, task: TaskEnvelope, outcome: ProcessOutcome) -> AttemptResult:
        directory = self._directories[task.attempt_id]
        result_file = directory / "result.json"
        if not result_file.exists():
            raise ValueError(f"pytest-http 未生成 {result_file}")
        payload = json.loads(result_file.read_text(encoding="utf-8"))
        artifacts = []
        for name, kind in (("worker.log", "LOG"), ("pytest.log", "LOG"), ("junit.xml", "JUNIT_XML"), ("result.json", "OTHER")):
            path = directory / name
            if path.exists():
                artifacts.append(build_artifact(task, path, kind, self._artifact_store))
        failure_payload = payload.get("failure")
        failure = None
        if failure_payload:
            failure = AttemptFailure(
                type=failure_payload["type"], message=failure_payload["message"],
                retryable=bool(failure_payload.get("retryable")),
                stack_digest=failure_payload.get("stackDigest"),
                details=failure_payload.get("details", {}),
            )
        return AttemptResult(
            status=payload["status"], duration_ms=int(payload.get("durationMs", outcome.duration_ms)),
            summary=payload["summary"], failure=failure, artifacts=tuple(artifacts),
        )

__all__ = ["AirtestAdapter", "PytestHttpAdapter"]
