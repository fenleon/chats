# Chats push — Matrix push to the LP3, no Google

Chats receives Beeper/Matrix push on the LP3 through the standard Matrix
**HTTP pusher** path, with **ntfy.sh as the production relay** — the app
currently uses ntfy (auto-provisioned, deployed on the LP3, live-verified).
No Google services, no always-on box, zero setup.

```
matrix homeserver (Beeper)                 ntfy.sh (Matrix Push Gateway)       LP3
┌──────────────────┐  POST /_matrix/   ┌──────────────────────┐  SSE /json  ┌──────────────┐
│ message arrives  │ push/v1/notify    │ routes by pushkey →  │ ◄────────── │ companion    │
│ push rules match │ ─────────────────►│ private topic        │  holds open │ wakes, one   │
│ pusher data.url  │                    │ (per install)       │             │ /sync, notif │
└──────────────────┘                    └──────────────────────┘             └──────────────┘
```

No tokens or message content leave the phone: the notify payload is
`event_id_only` (room/event ids, sender, count) — a wake-up signal only. The
phone does one authenticated, encrypted `/sync` and posts the notification.

## How it works

1. **Pusher registration** — on login/session restore the companion registers
   a Matrix HTTP pusher with Beeper: `app_id = com.lightphone.chats`, a
   persisted per-install `pushkey` (a private ntfy topic URL, which doubles as
   a bearer token), `format = event_id_only`, and `data.url =
   https://ntfy.sh/_matrix/push/v1/notify`. Idempotent — the same pushkey
   replaces the pusher on every start.
2. **SSE channel** — `PushChannel` (companion, one object) holds an OkHttp SSE
   subscription to `https://ntfy.sh/<topic>/json` (`readTimeout(90s)`, reconnect
   with backoff, `?since=<last-id>` resume so pushes published during a gap are
   replayed). No new dependencies — manual SSE on the OkHttp Trixnity already
   ships.
3. **Wake + sync (verified, 2026-09-02)** — on a real-message push (debounced
   1 s, last wins): one `syncOnce` (`MatrixRepository.onPushDelivered` →
   `runPushWake`), then the wake **verifies the pushed `event_id` landed in
   the Room store** (`isEventStored` — `TimelineEvent` row, else `RoomState`
   `json_extract`); the notification watcher posts the local notification.
   Counts-only pushes collapse to one wake per 5 min. Not caught up → up to 2
   bounded retries with 2 s/4 s backoff, then a low-key "Checking for messages
   failed — will retry" notification (cleared on the next successful sync or
   foreground). A wake whose event a fallback round already delivered skips
   the sync entirely.
4. **Fallback** — the screen-off `syncOnce` rounds stay as a safety net: a
   silent SSE drop (or Beeper not POSTing) must not mean missed messages. The
   cadence is push-gated (2026-08-31): 15-min lazy while the SSE channel is
   connected, 5-min when it's down.
5. **Reboot** — `BootReceiver` re-arms `ChatSyncService` (and with it the SSE
   subscription) on `BOOT_COMPLETED`; a rebooted LP3 does not stay silent
   until the tool is opened.

## Config (dev extras — no default, polling-only when unset)

```bash
adb shell am start -n com.lightphone.chats/com.lightphone.chats.server.MainActivity \
  --es pushsse <url>      # SSE subscription URL, phone side: https://ntfy.sh/<topic>/json
  --es pushnotify <url>   # pusher data.url, homeserver side: https://ntfy.sh/_matrix/push/v1/notify
  --es pushkey <url>      # pushkey = ntfy routing topic: https://ntfy.sh/<topic>
adb shell am start -n com.lightphone.chats/com.lightphone.chats.server.MainActivity --es pushclear 1  # remove pusher + config + ?since resume point
```

Two URLs (plus the pushkey) because one side sees the relay from the phone and
the other from the homeserver. On the installed build everything is
**auto-provisioned** — install → launch → private topic `chats-<32-hex>` →
pusher on Beeper → SSE, zero setup. `pushnotify` must be public (Beeper POSTs
from their servers) and **must end in `/_matrix/push/v1/notify`** — both
Synapse and Beeper reject any other path. ntfy.sh serves exactly that path as
its Matrix Push Gateway, routing each push by the `pushkey` in the payload.

## Status

- **Production: ntfy.sh — DEPLOYED, live-verified (2026-08-17).** The LP3 runs
  the auto-provisioning build; Beeper→ntfy delivery live-proven on the real
  account; `?since` resume + the push-gated 5/15-min fallback rounds cover the
  gap classes; push wakes are store-verified with bounded retries (2026-09-02).
- **UnifiedPush (LightOS distributor) — proven as a probe, NOT the production
  path (2026-08-21).** LightOS serves a per-device UP endpoint
  (`…/api/webhooks/unified_push/deliver/<uuid>`) and delivered a push
  end-to-end through a dev relay; the app still uses ntfy. Switching would
  replace the phone's SSE socket with Light's channel but still needs a hosted
  Matrix-notify → UP relay (Beeper mandates the `/_matrix/push/v1/notify`
  path). Not built.
- History, verdicts, teardowns, and measurements live in `WORKLOG.md` (the
  2026-08-15 → 2026-08-21 entries); this file is how-it-works/config only.

## Safety boundary

The rig only ever talks to the local dev Synapse with throwaway users.
Real-account tests are receive-only and require explicit go-ahead.
