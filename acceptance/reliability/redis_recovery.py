#!/usr/bin/env python3
"""Prove a persisted Redis Stream pending message survives a Redis restart and is reclaimed."""
from __future__ import annotations

import argparse, json, subprocess, sys, time, uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "testforge-worker"))
import redis  # noqa: E402
from testforge_worker.consumer.stream import RedisStreamConsumer  # noqa: E402

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--redis-url", default="redis://:testforge_redis@127.0.0.1:6379/0")
    parser.add_argument("--container", default="testforge-dev-redis-1")
    parser.add_argument("--output", type=Path, default=ROOT / "acceptance/evidence/reliability-observability-delivery/redis-recovery.json")
    args = parser.parse_args()
    token = uuid.uuid4().hex[:10]; stream = f"testforge:acceptance:recovery:{token}"; group = f"recovery-{token}"
    client = redis.from_url(args.redis_url, decode_responses=True)
    first = RedisStreamConsumer(client, stream=stream, group=group, consumer="before-restart", claim_idle_ms=1000, block_ms=50)
    first.ensure_group(); message_id = client.xadd(stream, {"payload": json.dumps({"messageId": str(uuid.uuid4()), "probe": "redis-recovery"})})
    delivered = first.receive()
    if delivered is None or delivered.stream_id != message_id: raise AssertionError("message was not pending before restart")
    subprocess.run(["docker", "restart", args.container], cwd=ROOT, check=True)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            client = redis.from_url(args.redis_url, decode_responses=True); client.ping(); break
        except redis.RedisError: time.sleep(.5)
    else: raise TimeoutError("Redis did not recover")
    time.sleep(1.2)
    second = RedisStreamConsumer(client, stream=stream, group=group, consumer="after-restart", claim_idle_ms=1000, block_ms=50)
    recovered = second.receive()
    if recovered is None or not recovered.claimed or recovered.stream_id != message_id: raise AssertionError("pending message was not reclaimed")
    second.ack(recovered); pending = int(client.xpending(stream, group)["pending"])
    evidence = {"schema":"io.testforge/redis-recovery/v1","executedAt":datetime.now(timezone.utc).isoformat(),"result":"PASS","container":args.container,"stream":stream,"messageId":message_id,"claimedAfterRestart":recovered.claimed,"pendingAfterAck":pending}
    if pending != 0: raise AssertionError(evidence)
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False)); return 0

if __name__ == "__main__": raise SystemExit(main())
