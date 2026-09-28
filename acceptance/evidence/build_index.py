#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    entries = []
    errors = []
    for declared in manifest["entries"]:
        path = ROOT / declared["path"]
        exists = path.is_file()
        if declared["status"] == "PASS" and not exists:
            errors.append(f"PASS evidence is missing: {declared['id']} -> {declared['path']}")
        item = dict(declared)
        item["exists"] = exists
        item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest() if exists else None
        entries.append(item)
    index = {
        "schema": "io.testforge/evidence-index/v1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "entries": entries,
    }
    if args.check:
        if errors:
            raise SystemExit("\n".join(errors))
        print(f"Evidence manifest valid: {len(entries)} entries")
        return
    (ROOT / "index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Wrote {ROOT / 'index.json'}")


if __name__ == "__main__":
    main()
