from __future__ import annotations

import unittest
import threading
import time
from typing import Any

from redis.exceptions import ResponseError

from testforge_worker.callback.client import LeaseRejected, RetryExhausted
from testforge_worker.consumer.claim import ClaimDeferred
from testforge_worker.consumer.stream import RedisStreamConsumer, StreamDelivery
from testforge_worker.consumer.worker import ConsumerWorker
from testforge_worker.runtime.worker import DuplicateInFlight


def payload(message_id: str = "40000000-0000-4000-8000-000000000001") -> dict[str, Any]:
    return {
        "messageId": message_id,
        "taskId": "50000000-0000-4000-8000-000000000001",
        "runId": "30000000-0000-4000-8000-000000000001",
        "attemptId": "20000000-0000-4000-8000-000000000001",
        "leaseToken": "60000000-0000-4000-8000-000000000001",
        "execution": {"runner": "stub", "timeoutSeconds": 30},
        "callbacks": {
            "startUrl": "http://gateway/start",
            "heartbeatUrl": "http://gateway/heartbeat",
            "completeUrl": "http://gateway/callback",
            "artifactUploadUrl": "http://gateway/artifacts",
        },
        "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    }


class FakeConsumer:
    def __init__(self, deliveries: list[StreamDelivery]) -> None:
        self.deliveries = deliveries
        self.acked: list[str] = []
        self.received = 0
        self._lock = threading.Lock()

    def ensure_group(self) -> None:
        pass

    def receive(self) -> StreamDelivery | None:
        with self._lock:
            if not self.deliveries:
                time.sleep(0.005)
                return None
            self.received += 1
            return self.deliveries.pop(0)

    def ack(self, delivery: StreamDelivery) -> None:
        with self._lock:
            self.acked.append(delivery.stream_id)


class RejectDuplicateRuntime:
    def __init__(self) -> None:
        self.calls = 0
        self.executions = 0

    def execute(self, task: Any, runner: Any) -> None:
        del task, runner
        self.calls += 1
        if self.calls == 2:
            raise LeaseRejected("attempt already completed")
        self.executions += 1


class FailingCallbackRuntime:
    def execute(self, task: Any, runner: Any) -> None:
        del task, runner
        raise RetryExhausted("callback unavailable")


class BlockingRuntime:
    def __init__(self, total: int, stop: threading.Event) -> None:
        self.total = total
        self.stop = stop
        self.release = threading.Event()
        self.started = threading.Event()
        self.active = 0
        self.peak = 0
        self.completed = 0
        self._lock = threading.Lock()

    def execute(self, task: Any, runner: Any) -> None:
        del task, runner
        with self._lock:
            self.active += 1
            self.peak = max(self.peak, self.active)
            if self.active == 3:
                self.started.set()
        self.release.wait(timeout=2)
        with self._lock:
            self.active -= 1
            self.completed += 1
            if self.completed == self.total:
                self.stop.set()


class RedisStub:
    def __init__(self) -> None:
        self.read_called = False
        self.acked: tuple[str, str, str] | None = None

    def xgroup_create(self, **kwargs: Any) -> None:
        del kwargs
        raise ResponseError("BUSYGROUP Consumer Group name already exists")

    def xautoclaim(self, **kwargs: Any) -> list[Any]:
        del kwargs
        import json

        return [
            b"0-0",
            [(b"7-0", {b"payload": json.dumps(payload()).encode("utf-8")})],
            [],
        ]

    def xreadgroup(self, **kwargs: Any) -> list[Any]:
        del kwargs
        self.read_called = True
        return []

    def xack(self, stream: str, group: str, stream_id: str) -> None:
        self.acked = (stream, group, stream_id)


class ConsumerWorkerTest(unittest.TestCase):
    def test_duplicate_delivery_is_acked_without_second_execution(self) -> None:
        consumer = FakeConsumer(
            [
                StreamDelivery("1-0", payload()),
                StreamDelivery("2-0", payload(), claimed=True),
            ]
        )
        runtime = RejectDuplicateRuntime()
        worker = ConsumerWorker(
            consumer=consumer,
            runtime=runtime,  # type: ignore[arg-type]
            runners={"stub": object()},  # type: ignore[dict-item]
        )

        self.assertTrue(worker.run_once())
        self.assertTrue(worker.run_once())
        self.assertEqual(["1-0", "2-0"], consumer.acked)
        self.assertEqual(2, runtime.calls)
        self.assertEqual(1, runtime.executions)

    def test_callback_failure_leaves_message_pending(self) -> None:
        consumer = FakeConsumer([StreamDelivery("3-0", payload())])
        worker = ConsumerWorker(
            consumer=consumer,
            runtime=FailingCallbackRuntime(),  # type: ignore[arg-type]
            runners={"stub": object()},  # type: ignore[dict-item]
        )

        with self.assertRaises(RetryExhausted):
            worker.run_once()
        self.assertEqual([], consumer.acked)

    def test_serve_executes_up_to_configured_concurrency_with_backpressure(self) -> None:
        stop = threading.Event()
        deliveries = []
        for index in range(6):
            body = payload(f"40000000-0000-4000-8000-{index:012d}")
            body["taskId"] = f"50000000-0000-4000-8000-{index:012d}"
            body["attemptId"] = f"20000000-0000-4000-8000-{index:012d}"
            deliveries.append(StreamDelivery(f"{index + 1}-0", body))
        consumer = FakeConsumer(deliveries)
        runtime = BlockingRuntime(len(deliveries), stop)
        worker = ConsumerWorker(
            consumer=consumer,
            runtime=runtime,  # type: ignore[arg-type]
            runners={"stub": object()},  # type: ignore[dict-item]
            max_concurrency=3,
        )

        serving = threading.Thread(target=worker.serve, args=(stop,))
        serving.start()
        self.assertTrue(runtime.started.wait(timeout=1))
        self.assertEqual(3, runtime.peak)
        self.assertEqual(3, consumer.received, "饱和时不应继续从 Stream 预取")

        runtime.release.set()
        serving.join(timeout=2)
        self.assertFalse(serving.is_alive())
        self.assertEqual(6, runtime.completed)
        self.assertEqual(6, len(consumer.acked))
        self.assertLessEqual(runtime.peak, 3)

    def test_duplicate_in_flight_delivery_is_not_acked_early(self) -> None:
        class DuplicateRuntime:
            def execute(self, task: Any, runner: Any) -> None:
                del task, runner
                raise DuplicateInFlight("already active")

        consumer = FakeConsumer([StreamDelivery("active-1", payload())])
        worker = ConsumerWorker(
            consumer=consumer,
            runtime=DuplicateRuntime(),  # type: ignore[arg-type]
            runners={"stub": object()},  # type: ignore[dict-item]
        )

        self.assertTrue(worker.run_once())
        self.assertEqual([], consumer.acked)

    def test_temporarily_unclaimable_task_leaves_message_pending(self) -> None:
        consumer = FakeConsumer([StreamDelivery("busy-1", payload())])

        def deferred_resolver(delivery: StreamDelivery) -> Any:
            del delivery
            raise ClaimDeferred("device busy")

        worker = ConsumerWorker(
            consumer=consumer,
            runtime=RejectDuplicateRuntime(),  # type: ignore[arg-type]
            runners={"stub": object()},  # type: ignore[dict-item]
            task_resolver=deferred_resolver,
        )

        self.assertTrue(worker.run_once())
        self.assertEqual([], consumer.acked)

    def test_invalid_message_is_quarantined_and_acked(self) -> None:
        consumer = FakeConsumer([StreamDelivery("bad-1", {"messageId": "bad"})])
        invalid: list[Exception] = []
        worker = ConsumerWorker(
            consumer=consumer,
            runtime=RejectDuplicateRuntime(),  # type: ignore[arg-type]
            runners={},
            invalid_message=lambda delivery, error: invalid.append(error),
        )

        self.assertTrue(worker.run_once())
        self.assertEqual(["bad-1"], consumer.acked)
        self.assertEqual(1, len(invalid))

    def test_pending_message_is_claimed_before_new_delivery(self) -> None:
        redis = RedisStub()
        consumer = RedisStreamConsumer(
            redis,
            stream="testforge:tasks:pytest-http:linux",
            group="testforge-workers",
            consumer="worker-1",
        )

        consumer.ensure_group()
        delivery = consumer.receive()
        self.assertIsNotNone(delivery)
        self.assertTrue(delivery.claimed)
        self.assertEqual("7-0", delivery.stream_id)
        self.assertFalse(redis.read_called)
        consumer.ack(delivery)
        self.assertEqual(
            ("testforge:tasks:pytest-http:linux", "testforge-workers", "7-0"),
            redis.acked,
        )

    def test_outbox_index_fields_are_merged_into_partial_payload(self) -> None:
        consumer = RedisStreamConsumer(
            RedisStub(), stream="stream", group="group", consumer="worker"
        )
        delivery = consumer._delivery(  # noqa: SLF001 - narrow contract regression
            b"8-0",
            {
                b"messageId": b"message-8",
                b"runId": b"run-8",
                b"taskId": b"task-8",
                b"payload": b'{"schemaVersion":1,"runner":"airtest"}',
            },
            claimed=False,
        )

        self.assertEqual("message-8", delivery.payload["messageId"])
        self.assertEqual("run-8", delivery.payload["runId"])
        self.assertEqual("task-8", delivery.payload["taskId"])
        self.assertEqual("airtest", delivery.payload["runner"])


if __name__ == "__main__":
    unittest.main()
