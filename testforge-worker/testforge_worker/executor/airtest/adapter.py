from __future__ import annotations

import json
import os
import sys
from collections.abc import Callable, Mapping
from pathlib import Path
from urllib.parse import unquote, urlparse

from testforge_worker.artifact import ArtifactStore, LocalArtifactStore
from testforge_worker.executor.artifact_support import attempt_directory, build_artifact
from testforge_worker.executor.asset_resolver import ManagedAssetResolver, is_managed_asset
from testforge_worker.executor.spi import (
    AutomationDriver,
    AutomationDriverDescriptor,
    AutomationPlatform,
)
from testforge_worker.runtime.model import (
    AttemptFailure,
    AttemptResult,
    ProcessOutcome,
    ProcessSpec,
    TaskEnvelope,
)


_ARTIFACT_TYPES = {
    "STEP_SCREENSHOT": "SCREENSHOT",
    "FAILURE_SCREENSHOT": "SCREENSHOT",
    "STRUCTURED_RESULT": "WORLD_STATE",
    "CASE_RESULT": "OTHER",
    "ATTACHMENT": "OTHER",
}


class AirtestAdapter(AutomationDriver):
    descriptor = AutomationDriverDescriptor(
        name="airtest",
        runner="airtest",
        platforms=frozenset(
            {
                AutomationPlatform.WINDOWS,
                AutomationPlatform.ANDROID,
                AutomationPlatform.IOS,
            }
        ),
    )

    def __init__(
        self,
        artifact_store: ArtifactStore | None = None,
        runtime_environment: Callable[[], Mapping[str, str]] | None = None,
        asset_resolver: ManagedAssetResolver | None = None,
    ) -> None:
        self._directories: dict[str, Path] = {}
        self._artifact_store = artifact_store or LocalArtifactStore()
        self._runtime_environment = runtime_environment or (lambda: {})
        self._asset_resolver = asset_resolver

    def prepare_automation(
        self, task: TaskEnvelope, platform: AutomationPlatform
    ) -> ProcessSpec:
        directory = attempt_directory(task)
        self._directories[task.attempt_id] = directory
        script_contract = task.execution.get("script", {})
        source_ref = str(script_contract.get("sourceRef") or task.execution.get("sourceRef", ""))
        checksum = str(script_contract.get("checksum") or task.execution.get("checksum", ""))
        if is_managed_asset(source_ref):
            if self._asset_resolver is None:
                raise OSError("Worker 未配置 TestForge Asset Resolver")
            script = self._asset_resolver.materialize(source_ref, checksum, directory / "case-asset")
        else:
            script = _local_path(source_ref)
        parameters = task.execution.get("parameters", {})
        managed_environment = {
            key: str(value)
            for key, value in self._runtime_environment().items()
            if key in {"TEST_BRIDGE_ORIGIN", "TEST_BRIDGE_TOKEN", "TESTFORGE_DEVICE_URI"}
        }
        device = str(
            managed_environment.get("TESTFORGE_DEVICE_URI")
            or parameters.get("deviceUri")
            or os.environ.get("TESTFORGE_DEVICE_URI", "")
        )
        if not script.exists() or not device:
            raise OSError(f"Airtest script/device 无效: script={script}, device={device}")
        device_platform = AutomationPlatform.from_value(device.partition(":")[0])
        if device_platform is not platform:
            raise OSError(
                f"Airtest task/device 平台不一致: task={platform.value}, "
                f"device={device_platform.value}"
            )
        environment = {
            **managed_environment,
            "TESTFORGE_EVIDENCE_DIR": str(directory),
            "TESTFORGE_RUN_ID": task.run_id,
            "TESTFORGE_CASE_ID": str(task.execution.get("sourceRef", task.task_id)),
            "TESTFORGE_ATTEMPT_ID": task.attempt_id,
            "TESTFORGE_AUTOMATION_PLATFORM": platform.value,
        }
        for key, env_key in (("bridgeOrigin", "TEST_BRIDGE_ORIGIN"), ("bridgeToken", "TEST_BRIDGE_TOKEN")):
            value = parameters.get(key)
            if value and env_key not in environment:
                environment[env_key] = str(value)
        scenario_id = parameters.get("scenarioId")
        if scenario_id:
            environment["TESTFORGE_SCENARIO_ID"] = str(scenario_id)
        return ProcessSpec([
            sys.executable, "-m", "testforge_worker.executor.airtest.entry",
            "--script", str(script), "--device", device, "--output", str(directory),
        ], environment=environment)

    def result(self, task: TaskEnvelope, outcome: ProcessOutcome) -> AttemptResult:
        directory = self._directories[task.attempt_id]
        result_file = directory / "airtest-result.json"
        payload = json.loads(result_file.read_text(encoding="utf-8")) if result_file.exists() else {}
        status = str(payload.get("status", "PASSED" if outcome.exit_code == 0 else "INFRA_FAILED"))
        artifacts: list[dict[str, object]] = []
        indexed_paths: set[Path] = set()
        manifest = directory / "evidence-manifest.json"
        if manifest.exists():
            indexed = json.loads(manifest.read_text(encoding="utf-8"))
            for item in indexed.get("artifacts", []):
                relative = item.get("path") or item.get("relativePath")
                if not relative:
                    continue
                path = directory / relative
                if path.is_file():
                    indexed_paths.add(path.resolve())
                    source_type = str(item.get("type", "OTHER"))
                    artifacts.append(
                        build_artifact(
                            task,
                            path,
                            _ARTIFACT_TYPES.get(source_type, source_type),
                            self._artifact_store,
                        )
                    )
            artifacts.append(build_artifact(task, manifest, "OTHER", self._artifact_store))
        elif result_file.exists():
            artifacts.append(build_artifact(task, result_file, "OTHER", self._artifact_store))
            indexed_paths.add(result_file.resolve())
        for path, kind in (
            (directory / "worker.log", "LOG"),
            (directory / "airtest-report" / "index.html", "AIRTEST_HTML"),
        ):
            if path.is_file() and path.resolve() not in indexed_paths:
                artifacts.append(build_artifact(task, path, kind, self._artifact_store))
        if status == "PASSED":
            return AttemptResult(
                status="PASSED", duration_ms=outcome.duration_ms,
                summary=str(payload.get("summary", "Airtest normal hit passed")),
                artifacts=tuple(artifacts),
            )
        failure = payload.get("failure") if isinstance(payload.get("failure"), dict) else {}
        failure_type = str(payload.get("failureType", failure.get("type", "SCRIPT_ERROR")))
        return AttemptResult(
            status="ASSERTION_FAILED" if failure_type == "ASSERTION_FAILED" else "INFRA_FAILED",
            duration_ms=outcome.duration_ms,
            summary=str(payload.get("summary", "Airtest execution failed")),
            failure=AttemptFailure(
                type=failure_type,
                message=str(payload.get("message", failure.get("message", payload.get("summary", "Airtest execution failed")))),
                retryable=failure_type == "DEVICE_ERROR",
                details={"exitCode": outcome.exit_code},
            ),
            artifacts=tuple(artifacts),
        )


def _local_path(value: str) -> Path:
    parsed = urlparse(value)
    if parsed.scheme == "file":
        return Path(unquote(parsed.path.lstrip("/")))
    return Path(value).resolve()
