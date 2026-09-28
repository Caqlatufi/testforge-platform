#!/usr/bin/env python3
"""Run 100 real subprocess tasks through three bounded Redis Stream workers."""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "testforge-worker"))

import redis  # noqa: E402

from testforge_worker.callback.client import HeartbeatDecision  # noqa: E402
from testforge_worker.consumer.stream import RedisStreamConsumer  # noqa: E402
from testforge_worker.consumer.worker import ConsumerWorker  # noqa: E402
from testforge_worker.runtime.model import AttemptResult, ProcessOutcome, ProcessSpec, TaskEnvelope  # noqa: E402
from testforge_worker.runtime.worker import WorkerRuntime  # noqa: E402


class SleepRunner:
    def prepare(self, task: TaskEnvelope) -> ProcessSpec:
        del task
        return ProcessSpec((sys.executable, "-c", "import time; time.sleep(0.03)"))

    def result(self, task: TaskEnvelope, outcome: ProcessOutcome) -> AttemptResult:
        del task
        return AttemptResult("PASSED", outcome.duration_ms, "concurrency acceptance passed")


class RecordingGateway:
    def __init__(self, total: int) -> None:
        self.total = total
        self.completed = threading.Event()
        self.results: dict[str, str] = {}
        self.completion_calls: Counter[str] = Counter()
        self.worker_distribution: Counter[str] = Counter()
        self.active_by_worker: Counter[str] = Counter()
        self.peak_by_worker: Counter[str] = Counter()
        self.global_active = 0
        self.global_peak = 0
        self._lock = threading.Lock()

    def start(self, task: TaskEnvelope, worker_id: str) -> None:
        with self._lock:
            if task.attempt_id in self.results:
                raise AssertionError(f"completed Attempt was started twice: {task.attempt_id}")
            self.active_by_worker[worker_id] += 1
            self.peak_by_worker[worker_id] = max(
                self.peak_by_worker[worker_id], self.active_by_worker[worker_id]
            )
            self.global_active += 1
            self.global_peak = max(self.global_peak, self.global_active)

    def heartbeat(
        self, task: TaskEnvelope, worker_id: str, progress: float | None = None
    ) -> HeartbeatDecision:
        del task, worker_id, progress
        return HeartbeatDecision(False)

    def complete(
        self,
        task: TaskEnvelope,
        worker_id: str,
        callback_key: str,
        result: AttemptResult,
    ) -> dict[str, Any]:
        del callback_key
        with self._lock:
            self.completion_calls[task.attempt_id] += 1
            self.results.setdefault(task.attempt_id, result.status)
            self.worker_distribution[worker_id] += 1
            self.active_by_worker[worker_id] -= 1
            self.global_active -= 1
            if len(self.results) == self.total:
                self.completed.set()
        return {"accepted": True}


def payload(index: int) -> dict[str, Any]:
    def identity(namespace: str) -> str:
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"testforge:{namespace}:{index}"))

    return {
        "messageId": identity("message"),
        "taskId": identity("task"),
        "runId": str(uuid.uuid5(uuid.NAMESPACE_URL, "testforge:run:concurrency-100")),
        "attemptId": identity("attempt"),
        "leaseToken": identity("lease"),
        "execution": {"runner": "acceptance", "timeoutSeconds": 10},
        "callbacks": {
            "startUrl": "recording://start",
            "heartbeatUrl": "recording://heartbeat",
            "completeUrl": "recording://complete",
            "artifactUploadUrl": "recording://artifacts",
        },
        "traceparent": f"00-{index:032x}-{index:016x}-01",
    }


def pending_count(client: redis.Redis, stream: str, group: str) -> int:
    value = client.xpending(stream, group)
    return int(value["pending"] if isinstance(value, dict) else value[0])


def run(args: argparse.Namespace) -> dict[str, Any]:
    client = redis.from_url(args.redis_url, decode_responses=True)
    client.ping()
    token = uuid.uuid4().hex
    stream = f"testforge:acceptance:concurrency:{token}"
    group = f"testforge-acceptance-{token}"
    workers = [f"acceptance-worker-{index}" for index in range(1, 4)]
    gateway = RecordingGateway(args.tasks)
    stop = threading.Event()
    errors: dict[str, list[str]] = defaultdict(list)
    threads: list[threading.Thread] = []
    started = time.monotonic()
    try:
        for worker_id in workers:
            consumer = RedisStreamConsumer(
                redis.from_url(args.redis_url, decode_responses=True),
                stream=stream,
                group=group,
                consumer=worker_id,
                claim_idle_ms=30_000,
                block_ms=100,
            )
            worker = ConsumerWorker(
                consumer=consumer,
                runtime=WorkerRuntime(worker_id=worker_id, gateway=gateway),
                runners={"acceptance": SleepRunner()},
                max_concurrency=args.concurrency,
                execution_error=lambda error, wid=worker_id: errors[wid].append(repr(error)),
            )
            thread = threading.Thread(target=worker.serve, args=(stop,), name=worker_id)
            thread.start()
            threads.append(thread)

        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                client.xinfo_groups(stream)
                break
            except redis.ResponseError:
                time.sleep(0.02)

        for index in range(args.tasks):
            body = payload(index)
            client.xadd(stream, {"payload": json.dumps(body, separators=(",", ":"))})

        if not gateway.completed.wait(timeout=args.timeout):
            raise TimeoutError(
                f"only {len(gateway.results)}/{args.tasks} tasks completed in {args.timeout}s"
            )
        deadline = time.monotonic() + 5
        while pending_count(client, stream, group) and time.monotonic() < deadline:
            time.sleep(0.02)
        residual = pending_count(client, stream, group)
    finally:
        stop.set()
        for thread in threads:
            thread.join(timeout=5)

    elapsed_ms = round((time.monotonic() - started) * 1000)
    duplicate_attempts = sum(count - 1 for count in gateway.completion_calls.values())
    successful = sum(status == "PASSED" for status in gateway.results.values())
    evidence = {
        "schema": "io.testforge/worker-concurrency-acceptance/v1",
        "completedAt": datetime.now(timezone.utc).isoformat(),
        "taskTotal": args.tasks,
        "successful": successful,
        "failed": args.tasks - successful,
        "terminal": len(gateway.results),
        "duplicateAttempts": duplicate_attempts,
        "messageResidual": residual,
        "elapsedMs": elapsed_ms,
        "workerCount": len(workers),
        "configuredConcurrencyPerWorker": args.concurrency,
        "configuredConcurrencyTotal": args.concurrency * len(workers),
        "observedGlobalPeak": gateway.global_peak,
        "observedPeakByWorker": dict(gateway.peak_by_worker),
        "workerDistribution": dict(gateway.worker_distribution),
        "workerErrors": dict(errors),
    }
    if (
        successful != args.tasks
        or residual != 0
        or duplicate_attempts != 0
        or gateway.global_peak > args.concurrency * len(workers)
        or set(gateway.worker_distribution) != set(workers)
        or errors
        or any(thread.is_alive() for thread in threads)
    ):
        raise AssertionError(json.dumps(evidence, ensure_ascii=False))
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--redis-url",
        default=os.environ.get(
            "TESTFORGE_REDIS_URL", "redis://:testforge_redis@127.0.0.1:6379/0"
        ),
    )
    parser.add_argument("--tasks", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "acceptance" / "evidence" / "worker-concurrency-100.json",
    )
    args = parser.parse_args()
    if args.tasks < 1 or args.concurrency < 1:
        parser.error("tasks and concurrency must be positive")
    evidence = run(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASSED", "evidence": str(args.output), **evidence}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
