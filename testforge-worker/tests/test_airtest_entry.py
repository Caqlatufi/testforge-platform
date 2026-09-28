from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from testforge_worker.executor.airtest.entry import main


class AirtestEntryTest(unittest.TestCase):
    def test_creates_report_directory_before_report_command(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "attempt"
            commands: list[list[str]] = []

            def run(command: list[str], **_: object) -> subprocess.CompletedProcess[str]:
                commands.append(command)
                if "report" in command:
                    outfile = Path(command[command.index("--outfile") + 1])
                    self.assertTrue(outfile.parent.is_dir())
                return subprocess.CompletedProcess(command, 0)

            with patch(
                "sys.argv",
                ["airtest-entry", "--script", "case.air", "--device", "Windows:///", "--output", str(output)],
            ), patch("subprocess.run", side_effect=run):
                self.assertEqual(0, main())

            self.assertEqual(2, len(commands))
            self.assertTrue((output / "airtest-report").is_dir())


if __name__ == "__main__":
    unittest.main()
