from __future__ import annotations

import os
from pathlib import Path


def load_local_environment(path: Path | None = None) -> Path | None:
    """读取被 Git 忽略的本机配置，且不覆盖调用方已设置的环境变量。"""
    candidates = [path] if path is not None else _default_candidates()
    for candidate in candidates:
        if candidate is None or not candidate.is_file():
            continue
        for raw_line in candidate.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", maxsplit=1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if key:
                os.environ.setdefault(key, value)
        return candidate
    return None


def _default_candidates() -> list[Path]:
    explicit = os.environ.get("TESTFORGE_ENV_FILE")
    if explicit:
        return [Path(explicit).expanduser().resolve()]
    platform_root = Path(__file__).resolve().parents[2]
    return [Path.cwd() / ".env.local", platform_root / ".env.local"]
