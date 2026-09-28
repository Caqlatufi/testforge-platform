import io
import json
import unittest
from contextlib import redirect_stdout

from testforge_worker.cli import health_payload, main


class WorkerCliTest(unittest.TestCase):
    def test_health_payload_identifies_worker(self) -> None:
        self.assertEqual("testforge-worker", health_payload()["service"])
        self.assertEqual("ready", health_payload()["status"])

    def test_main_prints_machine_readable_health(self) -> None:
        output = io.StringIO()

        with redirect_stdout(output):
            exit_code = main([])

        self.assertEqual(0, exit_code)
        self.assertEqual("ready", json.loads(output.getvalue())["status"])


if __name__ == "__main__":
    unittest.main()
