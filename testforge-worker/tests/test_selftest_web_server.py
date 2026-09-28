from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[2] / "scripts" / "selftest" / "selftest_web_server.py"
SPEC = importlib.util.spec_from_file_location("selftest_web_server", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SelftestWebServerTest(unittest.TestCase):
    def test_module_assets_use_browser_compatible_mime_types(self) -> None:
        self.assertEqual("application/javascript", MODULE.Handler.extensions_map[".js"])
        self.assertEqual("application/javascript", MODULE.Handler.extensions_map[".mjs"])
        self.assertEqual("text/css", MODULE.Handler.extensions_map[".css"])


if __name__ == "__main__":
    unittest.main()
