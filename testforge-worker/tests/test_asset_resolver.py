from __future__ import annotations

import hashlib
import io
import tempfile
import unittest
import zipfile
from email.message import Message
from pathlib import Path
from unittest.mock import patch

from testforge_worker.executor.asset_resolver import ManagedAssetResolver


class _Response:
    def __init__(self, content: bytes) -> None:
        self._content = content
        self.headers = Message()
        self.headers["X-TestForge-SHA256"] = "sha256:" + hashlib.sha256(content).hexdigest()

    def __enter__(self):
        return self

    def __exit__(self, *_: object) -> None:
        return None

    def read(self) -> bytes:
        return self._content


class ManagedAssetResolverTest(unittest.TestCase):
    def test_downloads_verifies_and_materializes_file(self) -> None:
        content = b"def test_ok():\n    assert True\n"
        checksum = "sha256:" + hashlib.sha256(content).hexdigest()
        with tempfile.TemporaryDirectory() as temporary, patch(
            "testforge_worker.executor.asset_resolver.urlopen", return_value=_Response(content)
        ) as download:
            path = ManagedAssetResolver("http://gateway").materialize(
                "testforge://assets/asset-1#tests/case.py", checksum, Path(temporary)
            )
            self.assertTrue(path.as_posix().endswith("tests/case.py"))
            self.assertEqual(content, path.read_bytes())
            self.assertIn("/api/v1/assets/asset-1/content", download.call_args.args[0].full_url)

    def test_extracts_zip_and_blocks_checksum_mismatch(self) -> None:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as package:
            package.writestr("scenario.air/main.py", "print('ok')")
        content = buffer.getvalue()
        checksum = "sha256:" + hashlib.sha256(content).hexdigest()
        with tempfile.TemporaryDirectory() as temporary, patch(
            "testforge_worker.executor.asset_resolver.urlopen", return_value=_Response(content)
        ):
            path = ManagedAssetResolver("http://gateway").materialize(
                "testforge://assets/asset-2#scenario.air", checksum, Path(temporary)
            )
            self.assertTrue(path.is_dir())

            with self.assertRaisesRegex(OSError, "SHA-256"):
                ManagedAssetResolver("http://gateway").materialize(
                    "testforge://assets/asset-2#scenario.air", "sha256:" + "0" * 64,
                    Path(temporary) / "bad",
                )


if __name__ == "__main__":
    unittest.main()
