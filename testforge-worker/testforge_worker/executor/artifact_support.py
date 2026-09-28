from __future__ import annotations

import hashlib
import mimetypes
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path

from testforge_worker.artifact import ArtifactStore
from testforge_worker.runtime.model import TaskEnvelope


def artifact_root() -> Path:
    return Path(os.environ.get("TESTFORGE_ARTIFACT_ROOT", "build/artifacts")).resolve()


def attempt_directory(task: TaskEnvelope) -> Path:
    path = artifact_root() / "runs" / task.run_id / "attempts" / task.attempt_id
    path.mkdir(parents=True, exist_ok=True)
    return path


def build_artifact(
    task: TaskEnvelope,
    path: Path,
    kind: str,
    store: ArtifactStore,
) -> dict[str, object]:
    raw = path.read_bytes()
    media_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    object_key = path.resolve().relative_to(artifact_root()).as_posix()
    stored_key = store.put(task, path, object_key, media_type)
    return {
        "schemaVersion": "1.0.0",
        "artifactId": str(uuid.uuid4()),
        "attemptId": task.attempt_id,
        "type": kind,
        "objectKey": stored_key,
        "mediaType": media_type,
        "sizeBytes": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
