from __future__ import annotations

import argparse
import json
import os
import uuid
from urllib.request import Request, urlopen


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--status", required=True, choices=("BUILDING", "READY", "FAILED"))
    parser.add_argument("--summary", required=True)
    parser.add_argument("--endpoint", default="")
    args = parser.parse_args()
    callback_url = os.environ.get("CALLBACK_URL", "").strip()
    if not callback_url:
        raise SystemExit("CALLBACK_URL is required for TestForge deployment")
    payload = {
        "callbackKey": str(uuid.uuid4()),
        "providerRunId": os.environ.get("BUILD_URL", os.environ.get("BUILD_TAG", "jenkins")),
        "status": args.status,
        "summary": args.summary,
    }
    if args.endpoint:
        payload["endpoint"] = args.endpoint
    request = Request(callback_url, data=json.dumps(payload).encode("utf-8"), headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(request, timeout=20) as response:
        response.read()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
