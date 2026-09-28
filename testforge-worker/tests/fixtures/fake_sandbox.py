from __future__ import annotations

import json
import os
import secrets
import signal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


TOKEN = secrets.token_hex(32)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/test/health":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_error(401)
            return
        body = json.dumps({"status": "ready"}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        del format, args


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
for name in ("SIGTERM", "SIGBREAK"):
    signum = getattr(signal, name, None)
    if signum is not None:
        signal.signal(signum, lambda *_: os._exit(0))
origin = f"http://127.0.0.1:{server.server_address[1]}"
print(f"TEST_BRIDGE_READY {json.dumps({'origin': origin, 'token': TOKEN})}", flush=True)
print("fake sandbox ready", flush=True)
server.serve_forever()
