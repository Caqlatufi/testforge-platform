from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--script", required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    log_dir = output / "airtest-log"
    command = [
        sys.executable, "-m", "airtest", "run", args.script,
        "--device", args.device, "--log", str(log_dir), "--compress", "90",
    ]
    completed = subprocess.run(command, env=os.environ.copy(), check=False)
    report_dir = output / "airtest-report"
    report_dir.mkdir(parents=True, exist_ok=True)
    report = subprocess.run(
        [sys.executable, "-m", "airtest", "report", args.script,
         "--log_root", str(log_dir), "--outfile", str(report_dir / "index.html")],
        env=os.environ.copy(), check=False,
    )
    return completed.returncode if completed.returncode != 0 else report.returncode


if __name__ == "__main__":
    raise SystemExit(main())
