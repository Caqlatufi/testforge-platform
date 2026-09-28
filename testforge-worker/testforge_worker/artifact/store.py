from __future__ import annotations

import hashlib
import json
import os
import threading
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable, Mapping, Protocol
from urllib.parse import urlparse
from urllib.request import Request, urlopen

import requests

if TYPE_CHECKING:
    from testforge_worker.runtime.model import TaskEnvelope


class ArtifactStore(Protocol):
    def put(
        self, task: TaskEnvelope, path: Path, object_key: str, media_type: str
    ) -> str: ...


class LocalArtifactStore:
    """开发兼容模式：文件留在 TESTFORGE_ARTIFACT_ROOT，键仍使用统一格式。"""

    def put(
        self, task: TaskEnvelope, path: Path, object_key: str, media_type: str
    ) -> str:
        del task, path, media_type
        return object_key


class MinioArtifactStore:
    def __init__(self, client: Any, bucket: str) -> None:
        if not bucket:
            raise ValueError("MinIO bucket 不能为空")
        self._client = client
        self._bucket = bucket
        self._ready = False
        self._lock = threading.Lock()

    @classmethod
    def from_environment(cls) -> "MinioArtifactStore":
        from minio import Minio

        raw_endpoint = os.environ.get("TESTFORGE_MINIO_ENDPOINT", "http://127.0.0.1:9000")
        parsed = urlparse(raw_endpoint if "://" in raw_endpoint else f"http://{raw_endpoint}")
        if not parsed.netloc:
            raise ValueError(f"MinIO endpoint 非法: {raw_endpoint}")
        client = Minio(
            parsed.netloc,
            access_key=os.environ.get("TESTFORGE_MINIO_ACCESS_KEY", "testforge"),
            secret_key=os.environ.get("TESTFORGE_MINIO_SECRET_KEY", "testforge_minio"),
            secure=parsed.scheme == "https",
        )
        return cls(client, os.environ.get("TESTFORGE_MINIO_BUCKET", "testforge-artifacts"))

    def put(
        self, task: TaskEnvelope, path: Path, object_key: str, media_type: str
    ) -> str:
        del task
        self._ensure_bucket()
        self._client.fput_object(
            self._bucket,
            object_key,
            str(path),
            content_type=media_type,
        )
        return object_key

    def _ensure_bucket(self) -> None:
        if self._ready:
            return
        with self._lock:
            if self._ready:
                return
            if not self._client.bucket_exists(self._bucket):
                self._client.make_bucket(self._bucket)
            self._ready = True


PolicyRequester = Callable[[str, Mapping[str, object], float], Mapping[str, Any]]
FormUploader = Callable[[str, Mapping[str, str], Path, str, float], None]


class GatewayArtifactStore:
    """Worker 只向 Gateway 换取短期策略，不持有 OSS 永久密钥。"""

    def __init__(
        self,
        worker_id: str,
        *,
        timeout_seconds: float = 15.0,
        policy_requester: PolicyRequester | None = None,
        form_uploader: FormUploader | None = None,
    ) -> None:
        if not worker_id:
            raise ValueError("worker_id 不能为空")
        self._worker_id = worker_id
        self._timeout_seconds = timeout_seconds
        self._request_policy = policy_requester or _request_policy
        self._upload_form = form_uploader or _upload_form

    def put(
        self, task: TaskEnvelope, path: Path, object_key: str, media_type: str
    ) -> str:
        raw = path.read_bytes()
        response = self._request_policy(
            task.callbacks.artifact_upload,
            {
                "workerId": self._worker_id,
                "leaseToken": task.lease_token,
                "objectKey": object_key,
                "mediaType": media_type,
                "sizeBytes": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
            },
            self._timeout_seconds,
        )
        data = response.get("data")
        if not isinstance(data, Mapping):
            raise OSError("Gateway 未返回附件上传策略")
        upload_url = data.get("uploadUrl")
        stored_key = data.get("objectKey")
        fields = data.get("fields")
        if not isinstance(upload_url, str) or not isinstance(stored_key, str) or not isinstance(fields, Mapping):
            raise OSError("Gateway 附件上传策略格式错误")
        normalized_fields = {str(key): str(value) for key, value in fields.items()}
        self._upload_form(
            upload_url, normalized_fields, path, media_type, self._timeout_seconds
        )
        return stored_key


def _request_policy(
    url: str, payload: Mapping[str, object], timeout_seconds: float
) -> Mapping[str, Any]:
    request = Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=timeout_seconds) as response:
        return json.loads(response.read().decode("utf-8"))


def _upload_form(
    url: str,
    fields: Mapping[str, str],
    path: Path,
    media_type: str,
    timeout_seconds: float,
) -> None:
    with path.open("rb") as source:
        response = requests.post(
            url,
            data=dict(fields),
            files={"file": (path.name, source, media_type)},
            timeout=timeout_seconds,
        )
    if response.status_code not in {200, 201, 204}:
        raise OSError(
            f"OSS 附件上传失败: HTTP {response.status_code} {response.text[:500]}"
        )


def create_artifact_store(worker_id: str) -> ArtifactStore:
    backend = os.environ.get("TESTFORGE_ARTIFACT_BACKEND", "gateway").strip().lower()
    if backend == "gateway":
        return GatewayArtifactStore(worker_id)
    if backend == "local":
        return LocalArtifactStore()
    if backend == "minio":
        return MinioArtifactStore.from_environment()
    raise ValueError(f"不支持的附件存储后端: {backend}")
