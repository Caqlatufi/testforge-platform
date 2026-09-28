from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from testforge_worker.artifact.store import (
    GatewayArtifactStore,
    LocalArtifactStore,
    MinioArtifactStore,
)


class FakeMinioClient:
    def __init__(self, exists: bool = False) -> None:
        self.exists = exists
        self.made: list[str] = []
        self.uploaded: list[tuple[str, str, str, str]] = []

    def bucket_exists(self, bucket: str) -> bool:
        return self.exists

    def make_bucket(self, bucket: str) -> None:
        self.made.append(bucket)
        self.exists = True

    def fput_object(self, bucket: str, key: str, path: str, *, content_type: str) -> None:
        self.uploaded.append((bucket, key, path, content_type))


class ArtifactStoreTest(unittest.TestCase):
    def test_local_store_keeps_object_key(self) -> None:
        self.assertEqual(
            "runs/run-1/result.json",
            LocalArtifactStore().put(None, Path("result.json"), "runs/run-1/result.json", "application/json"),
        )

    def test_minio_store_creates_bucket_once_and_uploads_every_file(self) -> None:
        client = FakeMinioClient()
        store = MinioArtifactStore(client, "testforge-artifacts")
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary) / "first.json"
            second = Path(temporary) / "second.log"
            first.write_text("{}", encoding="utf-8")
            second.write_text("ok", encoding="utf-8")

            store.put(None, first, "runs/1/first.json", "application/json")
            store.put(None, second, "runs/1/second.log", "text/plain")

        self.assertEqual(["testforge-artifacts"], client.made)
        self.assertEqual(2, len(client.uploaded))
        self.assertEqual("runs/1/first.json", client.uploaded[0][1])

    def test_gateway_store_requests_short_lived_policy_and_uploads_form(self) -> None:
        requested: list[tuple[str, dict[str, object]]] = []
        uploaded: list[tuple[str, dict[str, str], Path, str]] = []

        def request_policy(url, payload, timeout):
            del timeout
            requested.append((url, dict(payload)))
            return {"data": {
                "uploadUrl": "https://storage.example.com",
                "objectKey": "universal-test-platform/runs/run-1/result.json",
                "fields": {"key": "universal-test-platform/runs/run-1/result.json", "policy": "temporary"},
            }}

        def upload_form(url, fields, path, media_type, timeout):
            del timeout
            uploaded.append((url, dict(fields), path, media_type))

        store = GatewayArtifactStore(
            "worker-1", policy_requester=request_policy, form_uploader=upload_form
        )
        task = SimpleNamespace(
            lease_token="60000000-0000-4000-8000-000000000001",
            callbacks=SimpleNamespace(
                artifact_upload="http://gateway/api/v1/attempts/attempt-1/artifacts"
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            result = Path(temporary) / "result.json"
            result.write_text("{}", encoding="utf-8")

            stored_key = store.put(
                task, result, "runs/run-1/result.json", "application/json"
            )

        self.assertEqual(
            "universal-test-platform/runs/run-1/result.json", stored_key
        )
        self.assertEqual("worker-1", requested[0][1]["workerId"])
        self.assertEqual(2, requested[0][1]["sizeBytes"])
        self.assertEqual(64, len(requested[0][1]["sha256"]))
        self.assertEqual("https://storage.example.com", uploaded[0][0])
        self.assertEqual("temporary", uploaded[0][1]["policy"])


if __name__ == "__main__":
    unittest.main()
