from __future__ import annotations

import argparse
import http.client
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


class Handler(SimpleHTTPRequestHandler):
    root: Path
    backend: str
    # Windows may inherit a text/plain mapping for JavaScript from the registry.
    # Browser module scripts require an explicit JavaScript MIME type.
    extensions_map = {
        **SimpleHTTPRequestHandler.extensions_map,
        ".js": "application/javascript",
        ".mjs": "application/javascript",
        ".css": "text/css",
        ".json": "application/json",
        ".wasm": "application/wasm",
    }

    def translate_path(self, path: str) -> str:
        relative = urlsplit(path).path.lstrip("/")
        candidate = self.root / relative
        return str(candidate if candidate.is_file() else self.root / "index.html")

    def do_GET(self) -> None:
        if self.path.startswith(("/api/", "/actuator/")): self._proxy()
        else: super().do_GET()

    def do_POST(self) -> None: self._proxy()
    def do_PUT(self) -> None: self._proxy()
    def do_DELETE(self) -> None: self._proxy()

    def _proxy(self) -> None:
        target = urlsplit(self.backend)
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        connection = http.client.HTTPConnection(target.hostname, target.port, timeout=120)
        headers = {key: value for key, value in self.headers.items() if key.lower() not in {"host", "connection", "content-length"}}
        connection.request(self.command, self.path, body=body or None, headers=headers)
        response = connection.getresponse()
        self.send_response(response.status)
        for key, value in response.getheaders():
            if key.lower() not in {"connection", "transfer-encoding", "content-length"}: self.send_header(key, value)
        self.end_headers()
        while True:
            chunk = response.read(64 * 1024)
            if not chunk: break
            self.wfile.write(chunk)
            self.wfile.flush()
        connection.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--backend", required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    Handler.root = Path(args.root).resolve()
    Handler.backend = args.backend.rstrip("/")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__": main()
