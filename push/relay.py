#!/usr/bin/env python3
"""Matrix notify -> UnifiedPush passthrough relay (dev, 2026-08-21).

Serves the Matrix Push Gateway path (/_matrix/push/v1/notify) that Beeper /
Synapse require on data.url, and forwards the notify body to a per-device
UnifiedPush endpoint (LightOS's distributor) wrapped as the UP message.
Stateless, stdlib only.

Usage:
    UP_ENDPOINT=https://... ./relay.py [port]
"""
import http.server
import json
import os
import sys
import urllib.request

UP_ENDPOINT = os.environ["UP_ENDPOINT"]


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        if self.path != "/_matrix/push/v1/notify":
            self.send_response(404)
            self.end_headers()
            return
        # UP expects {"message": ...}; the notify body rides as the message
        # string. The distributor delivers its bytes to the device app.
        payload = json.dumps({"message": body.decode("utf-8", "replace")}).encode()
        req = urllib.request.Request(UP_ENDPOINT, data=payload, method="POST")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                print(f"forwarded {len(body)}B -> UP {resp.status}", flush=True)
        except Exception as e:
            print(f"forward FAILED: {e}", flush=True)
            self.send_response(502)
            self.end_headers()
            return
        # Matrix push gateway response shape.
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"rejected": []}).encode())

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print(f"relay on 127.0.0.1:{port} -> {UP_ENDPOINT}", flush=True)
    http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
