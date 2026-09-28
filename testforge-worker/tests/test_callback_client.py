from __future__ import annotations

import unittest
from collections.abc import Mapping
from typing import Any

from testforge_worker.callback.client import GatewayError, HttpGatewayClient
from testforge_worker.runtime.model import (
    AttemptResult,
    CallbackUrls,
    TaskEnvelope,
)


def task() -> TaskEnvelope:
    return TaskEnvelope(
        message_id="40000000-0000-4000-8000-000000000001",
        task_id="50000000-0000-4000-8000-000000000001",
        run_id="30000000-0000-4000-8000-000000000001",
        attempt_id="20000000-0000-4000-8000-000000000001",
        lease_token="60000000-0000-4000-8000-000000000001",
        timeout_seconds=2,
        execution={"runner": "stub"},
        callbacks=CallbackUrls("start", "heartbeat", "complete", "artifacts"),
        traceparent="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    )


class CallbackClientTest(unittest.TestCase):
    def test_start_and_heartbeat_follow_gateway_contract(self) -> None:
        requests: list[tuple[str, Mapping[str, Any]]] = []

        def transport(
            url: str,
            body: Mapping[str, Any],
            headers: Mapping[str, str],
            timeout: float,
        ) -> Mapping[str, Any]:
            del headers, timeout
            requests.append((url, dict(body)))
            return {"data": {"cancelRequested": url == "heartbeat"}}

        client = HttpGatewayClient(transport=transport)
        client.start(task(), "worker-1")
        decision = client.heartbeat(task(), "worker-1", progress=0.25)

        self.assertEqual(
            {"workerId": "worker-1", "leaseToken": task().lease_token},
            requests[0][1],
        )
        self.assertEqual("worker-1", requests[1][1]["workerId"])
        self.assertEqual(0.25, requests[1][1]["progress"])
        self.assertIn("at", requests[1][1])
        self.assertTrue(decision.cancel_requested)

    def test_complete_retries_identical_idempotent_payload(self) -> None:
        requests: list[tuple[str, Mapping[str, Any]]] = []

        def transport(
            url: str,
            body: Mapping[str, Any],
            headers: Mapping[str, str],
            timeout: float,
        ) -> Mapping[str, Any]:
            del headers, timeout
            requests.append((url, dict(body)))
            if len(requests) < 3:
                raise GatewayError("temporary")
            return {"data": {"disposition": "ACCEPTED"}}

        client = HttpGatewayClient(
            transport=transport,
            max_attempts=3,
            base_backoff_seconds=0,
            sleep=lambda seconds: None,
            random_value=lambda: 0,
        )
        client.complete(task(), "worker-1", "callback-key", AttemptResult("PASSED", 12, "ok"))

        self.assertEqual(3, len(requests))
        self.assertEqual(requests[0], requests[1])
        self.assertEqual(requests[1], requests[2])
        self.assertEqual("callback-key", requests[0][1]["callbackKey"])


if __name__ == "__main__":
    unittest.main()
