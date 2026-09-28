from __future__ import annotations

import json
import sys
from pathlib import Path

from testforge_worker.artifact import ArtifactStore, LocalArtifactStore
from testforge_worker.executor.artifact_support import attempt_directory, build_artifact
from testforge_worker.executor.asset_resolver import ManagedAssetResolver, is_managed_asset
from testforge_worker.runtime.model import AttemptFailure, AttemptResult, ProcessOutcome, ProcessSpec, TaskEnvelope


class PlaywrightWebAdapter:
    """Execute a TestForge-managed Python Playwright case in an isolated process."""

    def __init__(self, artifact_store: ArtifactStore | None = None, asset_resolver: ManagedAssetResolver | None = None) -> None:
        self._directories: dict[str, Path] = {}
        self._artifact_store = artifact_store or LocalArtifactStore()
        self._asset_resolver = asset_resolver

    def prepare(self, task: TaskEnvelope) -> ProcessSpec:
        directory = attempt_directory(task)
        self._directories[task.attempt_id] = directory
        execution = dict(task.execution)
        script_contract = execution.get("script", {})
        source_ref = str(script_contract.get("sourceRef") or execution.get("sourceRef", ""))
        checksum = str(script_contract.get("checksum") or execution.get("checksum", ""))
        if is_managed_asset(source_ref):
            if self._asset_resolver is None:
                raise OSError("Worker 未配置 TestForge Asset Resolver")
            script = self._asset_resolver.materialize(source_ref, checksum, directory / "case-asset")
        else:
            script = Path(source_ref).resolve()
        if not script.is_file() or script.suffix.lower() != ".py":
            raise OSError(f"Playwright Web 脚本无效: {script}")
        task_file = directory / "task.json"
        task_file.write_text(json.dumps({"execution": execution}, ensure_ascii=False), encoding="utf-8")
        return ProcessSpec([
            sys.executable, "-m", "testforge_worker.executor.playwright_web.entry",
            "--task", str(task_file), "--script", str(script), "--output", str(directory),
        ])

    def result(self, task: TaskEnvelope, outcome: ProcessOutcome) -> AttemptResult:
        directory = self._directories[task.attempt_id]
        result_file = directory / "playwright-result.json"
        payload = json.loads(result_file.read_text(encoding="utf-8")) if result_file.exists() else {}
        artifacts = []
        for name, kind in (
            ("worker.log", "LOG"), ("browser.log", "LOG"), ("final.png", "SCREENSHOT"),
            ("failure.png", "SCREENSHOT"), ("trace.zip", "TRACE"), ("playwright-result.json", "OTHER"),
        ):
            path = directory / name
            if path.is_file():
                artifacts.append(build_artifact(task, path, kind, self._artifact_store))
        if outcome.cancelled:
            return AttemptResult("CANCELLED", outcome.duration_ms, "Playwright execution cancelled",
                                 AttemptFailure("CANCELLED", "用户取消执行"), tuple(artifacts))
        status = str(payload.get("status", "INFRA_FAILED"))
        if status == "PASSED" and outcome.exit_code == 0:
            return AttemptResult("PASSED", outcome.duration_ms, str(payload.get("summary", "Playwright UI case passed")), artifacts=tuple(artifacts))
        failure = payload.get("failure", {}) if isinstance(payload.get("failure"), dict) else {}
        failure_type = str(failure.get("type", "SCRIPT_ERROR"))
        return AttemptResult(
            "ASSERTION_FAILED" if failure_type == "ASSERTION_FAILED" else "INFRA_FAILED",
            outcome.duration_ms,
            str(payload.get("summary", "Playwright UI case failed")),
            AttemptFailure(failure_type, str(failure.get("message", "Playwright UI case failed")),
                           retryable=failure_type in {"BROWSER_ERROR", "ENVIRONMENT"}),
            tuple(artifacts),
        )
