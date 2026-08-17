#!/usr/bin/env python3
"""End-to-end Matrix push test against the local Synapse dev homeserver.

Theory A proof: homeserver -> POST /notify -> gateway -> long-poll client.

Flow (all dummy users on the LOCAL dev Synapse, nothing touches a real
account):
  alice registers a Matrix HTTP pusher whose data.url points at the gateway
  bob sends a message to a room alice is in
  the homeserver POSTs a notification to the gateway
  the long-poll channel (/wait) returns it
  alice does one /sync (the "push-wake" step) and sees the event

Usage:  python3 test_local.py [gateway_port]   (default 8721)
Requires: the local Synapse on 127.0.0.1:8008 and gateway.py running.
"""

import json
import sys
import time
import urllib.error
import urllib.request

HS = "http://127.0.0.1:8008"
GATEWAY_PORT = int(sys.argv[1] if len(sys.argv) > 1 else 8721)
CHANNEL = sys.argv[2] if len(sys.argv) > 2 else "longpoll"  # longpoll (Theory A) | sse (Theory B)
GATEWAY = f"http://127.0.0.1:{GATEWAY_PORT}"
USER = f"pushuser{int(time.time()) % 100000}"  # fresh user per run

print(f"homeserver={HS} gateway={GATEWAY} user={USER} channel={CHANNEL}", flush=True)


def req(method, url, body=None, token=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw.decode(errors="replace")}


def register(user):
    # Open registration on the dev server; retry with m.login.dummy if needed.
    for attempt in range(6):
        code, body = req("POST", f"{HS}/_matrix/client/v3/register",
                         {"username": user, "password": "pw", "auth": {"type": "m.login.dummy"}})
        if code == 401 and "flows" in body:
            code, body = req("POST", f"{HS}/_matrix/client/v3/register",
                             {"username": user, "password": "pw",
                              "auth": {"type": "m.login.dummy", "session": body.get("session")}})
        if code == 200:
            return body["user_id"], body["access_token"]
        if code == 429:  # dev Synapse rate-limits registrations
            time.sleep(body.get("retry_after_ms", 1000) / 1000 + 0.5)
            continue
        assert False, f"register failed: {code} {body}"
    assert False, f"register failed after retries: {code} {body}"


def login(user):
    code, body = req("POST", f"{HS}/_matrix/client/v3/login",
                     {"type": "m.login.password",
                      "identifier": {"type": "m.id.user", "user": user},
                      "password": "pw"})
    assert code == 200, f"login failed: {code} {body}"
    return body["user_id"], body["access_token"]


def main():
    alice, at = register(f"{USER}a")
    bob, bt = register(f"{USER}b")

    code, room = req("POST", f"{HS}/_matrix/client/v3/createRoom", {
        "name": "Push Test Room", "visibility": "public",
        "preset": "public_chat",
    }, token=at)
    assert code == 200, f"createRoom failed: {code} {room}"
    room_id = room["room_id"]
    print(f"room: {room_id}", flush=True)

    code, _ = req("POST", f"{HS}/_matrix/client/v3/join/{room_id}", {}, token=bt)
    assert code == 200, f"join failed: {code}"

    # alice registers the HTTP pusher -> the gateway. This Synapse serves the
    # newer pushers/set API (flat body, no "pusher" wrapper) and requires the
    # gateway URL to end in /_matrix/push/v1/notify.
    code, body = req("POST", f"{HS}/_matrix/client/v3/pushers/set", {
        "append": False,
        "app_id": "com.lightphone.chats.test",
        "pushkey": "test-pushkey-alice",
        "kind": "http",
        "app_display_name": "Chats test",
        "device_display_name": "Chats test device",
        "lang": "en",
        "data": {"url": f"{GATEWAY}/_matrix/push/v1/notify"},
    }, token=at)
    assert code == 200, f"set pusher failed: {code} {body}"
    print("pusher registered (app_id=com.lightphone.chats.test, pushkey=test-pushkey-alice)", flush=True)

    # The delivery channel: what the LP3 companion (long-poll) or a UP-style
    # distributor app (SSE) will hold open. Run it on a thread so we can send
    # the message after; both channels tolerate the message arriving before
    # the subscribe (long-poll queues, SSE replays nothing — so we subscribe
    # first for SSE, same as a real device would).
    import http.client
    import socket
    import threading
    wait_result = {}

    def longpoll_receive():
        try:
            wait_result["status"], wait_result["body"] = req(
                "GET", f"{GATEWAY}/wait", timeout=35)
        except Exception as e:  # noqa: BLE001 - report any transport failure
            wait_result["error"] = repr(e)

    def sse_receive():
        try:
            conn = http.client.HTTPConnection("127.0.0.1", GATEWAY_PORT)
            conn.request("GET", "/events")
            resp = conn.getresponse()
            resp.fp.raw._sock.settimeout(35)
            while True:
                line = resp.readline().decode(errors="replace")
                if not line:
                    break
                if line.startswith("data: "):
                    wait_result["body"] = {"notification": json.loads(line[6:].strip())}
                    conn.close()
                    return
            conn.close()
            wait_result["body"] = {"notification": None}
        except Exception as e:  # noqa: BLE001
            wait_result["error"] = repr(e)

    receive = longpoll_receive if CHANNEL == "longpoll" else sse_receive
    t = threading.Thread(target=receive)
    t.start()
    time.sleep(1)  # ensure the channel is attached before the message lands

    # bob sends the message -> Synapse evaluates push rules -> POSTs to gateway
    code, sent = req("PUT",
                     f"{HS}/_matrix/client/v3/rooms/{room_id}/send/m.room.message/txn1",
                     {"msgtype": "m.text", "body": f"push test from {bob}"}, token=bt)
    assert code == 200, f"send failed: {code} {sent}"
    event_id = sent["event_id"]
    print(f"bob sent: {event_id}", flush=True)

    t.join(timeout=40)
    assert not t.is_alive(), "channel did not return within 35s — no push delivered"
    assert "error" not in wait_result, f"channel failed: {wait_result['error']}"
    notif = wait_result["body"].get("notification")
    assert notif, f"empty notification: {wait_result['body']}"
    print(f"PUSH DELIVERED ({CHANNEL}): room={notif.get('room_id')} event={notif.get('event_id')} "
          f"sender={notif.get('sender')} body={notif.get('content', {}).get('body')!r}", flush=True)
    assert notif.get("event_id") == event_id, "event_id mismatch"
    assert notif.get("content", {}).get("body") == f"push test from {bob}", "body mismatch"

    # The push-wake step: one short sync pulls the actual event into alice's store.
    code, sync = req("GET", f"{HS}/_matrix/client/v3/sync?timeout=0", token=at)
    timeline = sync.get("rooms", {}).get("join", {}).get(room_id, {}).get("timeline", {}).get("events", [])
    ids = [e.get("event_id") for e in timeline]
    assert event_id in ids, f"event {event_id} not in alice's sync: {ids}"
    print(f"SYNC-WAKE OK: event present in alice's /sync (count={len(ids)})", flush=True)

    # Cleanup: delete the pusher (leave the test users on the dev server).
    code, body = req("POST", f"{HS}/_matrix/client/v3/pushers/set", {
        "append": False, "app_id": "com.lightphone.chats.test",
        "pushkey": "test-pushkey-alice", "kind": None,
    }, token=at)
    assert code == 200, f"delete pusher failed: {code} {body}"
    print("pusher removed", flush=True)
    print(f"THEORY {'B' if CHANNEL == 'sse' else 'A'}: PASS", flush=True)


if __name__ == "__main__":
    main()
