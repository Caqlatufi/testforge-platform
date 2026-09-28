from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from testforge_worker.config import load_local_environment
from testforge_worker.service import ServiceConfig


class LocalEnvironmentTest(unittest.TestCase):
    def test_loads_values_without_overriding_existing_environment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            env_file = Path(temporary) / ".env.local"
            env_file.write_text(
                "# comment\nTESTFORGE_ONE=from-file\nTESTFORGE_TWO='two'\n",
                encoding="utf-8",
            )
            with patch.dict(os.environ, {"TESTFORGE_ONE": "existing"}, clear=False):
                os.environ.pop("TESTFORGE_TWO", None)

                loaded = load_local_environment(env_file)

                self.assertEqual(env_file, loaded)
                self.assertEqual("existing", os.environ["TESTFORGE_ONE"])
                self.assertEqual("two", os.environ["TESTFORGE_TWO"])
                os.environ.pop("TESTFORGE_TWO", None)

    def test_rejects_non_positive_worker_concurrency(self) -> None:
        with patch.dict(os.environ, {"TESTFORGE_WORKER_CONCURRENCY": "0"}, clear=False):
            with self.assertRaisesRegex(ValueError, "必须大于 0"):
                ServiceConfig.from_environment()

    def test_parses_managed_sandbox_command_as_json_array(self) -> None:
        values = {
            "TESTFORGE_SANDBOX_COMMAND_JSON": '["npm","run","dev"]',
            "TESTFORGE_DEVICE_ID": "sandbox-vm-a",
            "TESTFORGE_DEVICE_URI": "Windows:///sandbox",
            "TESTFORGE_WORKER_ID": "worker-vm-a",
            "TESTFORGE_WORKER_CONCURRENCY": "1",
        }
        with patch.dict(os.environ, values, clear=True):
            config = ServiceConfig.from_environment()

        self.assertEqual(("npm", "run", "dev"), config.sandbox_command)
        self.assertEqual("environment-worker-vm-a", config.sandbox_environment_id)

    def test_managed_sandbox_rejects_shared_worker_concurrency(self) -> None:
        values = {
            "TESTFORGE_SANDBOX_COMMAND_JSON": '["sandbox"]',
            "TESTFORGE_DEVICE_ID": "sandbox-vm-a",
            "TESTFORGE_DEVICE_URI": "Windows:///sandbox",
            "TESTFORGE_WORKER_CONCURRENCY": "2",
        }
        with patch.dict(os.environ, values, clear=True):
            with self.assertRaisesRegex(ValueError, "CONCURRENCY=1"):
                ServiceConfig.from_environment()

    def test_rejects_string_instead_of_sandbox_command_array(self) -> None:
        with patch.dict(
            os.environ,
            {"TESTFORGE_SANDBOX_COMMAND_JSON": '"npm run dev"'},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "JSON 字符串数组"):
                ServiceConfig.from_environment()


if __name__ == "__main__":
    unittest.main()
