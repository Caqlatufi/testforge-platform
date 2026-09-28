from __future__ import annotations

import unittest
from io import BytesIO
from unittest.mock import patch
from urllib.error import HTTPError

from testforge_worker.callback.client import LeaseRejected
from testforge_worker.consumer.claim import ClaimDeferred, GatewayTaskResolver
from testforge_worker.consumer.stream import StreamDelivery


class GatewayTaskResolverTest(unittest.TestCase):
    def setUp(self) -> None:
        self.resolver = GatewayTaskResolver("http://gateway", "worker-1")
        self.delivery = StreamDelivery(
            "1-0",
            {
                "messageId": "40000000-0000-4000-8000-000000000001",
                "taskId": "50000000-0000-4000-8000-000000000001",
                "runId": "30000000-0000-4000-8000-000000000001",
            },
        )

    def test_locked_response_is_retryable(self) -> None:
        with patch(
            "testforge_worker.consumer.claim.urlopen",
            side_effect=self._http_error(423),
        ):
            with self.assertRaises(ClaimDeferred):
                self.resolver(self.delivery)

    def test_conflict_response_is_terminal_for_delivery(self) -> None:
        with patch(
            "testforge_worker.consumer.claim.urlopen",
            side_effect=self._http_error(409),
        ):
            with self.assertRaises(LeaseRejected):
                self.resolver(self.delivery)

    @staticmethod
    def _http_error(status: int) -> HTTPError:
        return HTTPError(
            "http://gateway/claim", status, "claim failed", {}, BytesIO(b"{}")
        )


if __name__ == "__main__":
    unittest.main()
