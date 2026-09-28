from __future__ import annotations

import hashlib
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlparse
from urllib.request import Request, urlopen


class ManagedAssetResolver:
    """Download immutable TestForge assets and materialize a verified entrypoint."""

    _MAX_ARCHIVE_ENTRIES = 2000
    _MAX_EXPANDED_BYTES = 200 * 1024 * 1024

    def __init__(self, gateway_url: str, timeout_seconds: float = 30) -> None:
        self._gateway_url = gateway_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def materialize(self, source_ref: str, checksum: str, target: Path) -> Path:
        asset_id, entrypoint = self._parse(source_ref)
        request = Request(
            f"{self._gateway_url}/api/v1/assets/{asset_id}/content",
            headers={"Accept": "application/octet-stream"},
        )
        with urlopen(request, timeout=self._timeout_seconds) as response:
            content = response.read()
            response_checksum = response.headers.get("X-TestForge-SHA256", "")
        actual = "sha256:" + hashlib.sha256(content).hexdigest()
        expected = checksum or response_checksum
        if expected and actual != expected:
            raise OSError(f"Asset SHA-256 不匹配: expected={expected}, actual={actual}")

        target.mkdir(parents=True, exist_ok=True)
        archive = target / "asset.zip"
        archive.write_bytes(content)
        if zipfile.is_zipfile(archive):
            with zipfile.ZipFile(archive) as package:
                self._validate_members(package, target)
                package.extractall(target)
            archive.unlink()
            resolved = target / Path(*PurePosixPath(entrypoint).parts)
        else:
            archive.unlink()
            resolved = target / Path(*PurePosixPath(entrypoint).parts)
            resolved.parent.mkdir(parents=True, exist_ok=True)
            resolved.write_bytes(content)
        if not resolved.exists():
            raise OSError(f"Asset entrypoint 不存在: {entrypoint}")
        return resolved.resolve()

    @staticmethod
    def _parse(source_ref: str) -> tuple[str, str]:
        parsed = urlparse(source_ref)
        if parsed.scheme != "testforge" or parsed.netloc != "assets":
            raise ValueError(f"不是 TestForge Asset URI: {source_ref}")
        asset_id = parsed.path.strip("/")
        entrypoint = unquote(parsed.fragment).replace("\\", "/").strip("/")
        if not asset_id or not entrypoint or ".." in PurePosixPath(entrypoint).parts:
            raise ValueError(f"TestForge Asset URI 无效: {source_ref}")
        return asset_id, entrypoint

    @staticmethod
    def _validate_members(package: zipfile.ZipFile, target: Path) -> None:
        target_root = target.resolve()
        members = package.infolist()
        if len(members) > ManagedAssetResolver._MAX_ARCHIVE_ENTRIES:
            raise OSError("ZIP 文件条目超过 2000 个")
        if sum(member.file_size for member in members) > ManagedAssetResolver._MAX_EXPANDED_BYTES:
            raise OSError("ZIP 解压后内容超过 200 MiB")
        for member in members:
            member_path = (target / member.filename).resolve()
            if member_path != target_root and target_root not in member_path.parents:
                raise OSError(f"ZIP 包含不安全路径: {member.filename}")


def is_managed_asset(source_ref: str) -> bool:
    parsed = urlparse(source_ref)
    return parsed.scheme == "testforge" and parsed.netloc == "assets"
