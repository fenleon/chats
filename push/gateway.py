#!/usr/bin/env python3
"""Chats push gateway — Matrix HTTP pusher endpoint + device delivery.

Receives Matrix push notifications (POST /notify, the homeserver side) and
delivers them to connected devices over two channels:

  GET /wait   — HTTP long-poll: returns one queued notification, blocks up
                to POLL_TIMEOUT s when the queue is empty. (Theory A: the
                LP3 companion's laziest persistent channel — reconnect on
                timeout.)
  GET /events — SSE stream: one `data: <json>` line per notification, with
                periodic heartbeats to keep NAT/proxies alive. (Theory B:
                UnifiedPush-shape delivery — any UP-style client, e.g. a
                future ntfy-style distributor app, subscribes here.)

The same POST /notify endpoint is what a UnifiedPush gateway exposes to the
Matrix homeserver, so one server covers both theories.

Stdlib only. Run:  python3 gateway.py [port]   (default 8721)
"""

import json
import os
import queue
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.environ.get("PUSH_GATEWAY_HOST", "0.0.0.0")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8721
POLL_TIMEOUT = 25  # s; keep under typical NAT idle limits

# One queue per connected "device". Notifications are small; unbounded is fine
# for a dev gateway. ponytail: single shared queue per process, split per
# device only if a real deployment ever needs it.
_notifications: "queue.Queue[dict]" = queue.Queue()
_sses: set = set()
_sses_lock = threading.Lock()


def _handle_notify(body: dict) -> list:
    """Accept a Matrix push notification. Returns rejected pushkeys."""
    notif = (body or {}).get("notification")
    if not isinstance(notif, dict):
        return list((body or {}).get("rejected", []))
    # Queue one copy for the long-poll channel...
    _notifications.put(notif)
    # ...and fan out to every live SSE subscriber.
    event = "data: " + json.dumps(notif) + "\n\n"
    with _sses_lock:
        for sse in list(_sses):
            try:
                sse.put_nowait(event)
            except queue.Full:
                pass
    return []


class Handler(BaseHTTPRequestHandler):
    server_version = "ChatsPushGateway/0.1"

    # ---- helpers -----------------------------------------------------
    def _log(self, line: str):
        # Access log override: keep notify deliveries loud, they're the point.
        print(f"[{time.strftime('%H:%M:%S')}] {line}", flush=True)

    def _send(self, code: int, body: str = "", ctype: str = "application/json"):
        payload = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    # ---- endpoints ---------------------------------------------------
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            body = {}
        if self.path in ("/notify", "/_matrix/push/v1/notify"):
            rejected = _handle_notify(body)
            self._log(f"notify: {_short(body.get('notification'))} -> {len(_sses)} sse, queued")
            self._log(f"notify payload: {json.dumps(body)[:400]}")
            self._send(200, json.dumps({"rejected": rejected}))
        else:
            self._log(f"POST {self.path} 404")
            self._send(404, json.dumps({"error": "not found"}))

    def do_GET(self):
        if self.path == "/wait":
            self._log("wait: long-poll connected")
            try:
                notif = _notifications.get(timeout=POLL_TIMEOUT)
            except queue.Empty:
                notif = None
            self._send(200, json.dumps({"notification": notif}))
            self._log(f"wait: delivered {'notification' if notif else 'timeout'}")
        elif self.path == "/events":
            self._log("events: SSE connected")
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            sse: "queue.Queue[str]" = queue.Queue(maxsize=64)
            with _sses_lock:
                _sses.add(sse)
            try:
                while True:
                    try:
                        event = sse.get(timeout=15)
                    except queue.Empty:
                        event = ": heartbeat\n\n"  # keep the socket alive
                    self.wfile.write(event.encode())
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with _sses_lock:
                    _sses.discard(sse)
                self._log("events: SSE disconnected")
        else:
            self._log(f"GET {self.path} 404")
            self._send(404, json.dumps({"error": "not found"}))

    def log_message(self, *args):  # silence BaseHTTPRequestHandler's stderr noise
        pass


def _short(notif) -> str:
    if not isinstance(notif, dict):
        return "<empty>"
    n = notif.get("notification") or notif
    return (f"room={n.get('room_id','?')[:16]} event={n.get('event_id','?')[:16]} "
            f"sender={n.get('sender','?')} prio={n.get('prio','?')}")


if __name__ == "__main__":
    print(f"gateway on http://{HOST}:{PORT}  (notify /wait /events)", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
