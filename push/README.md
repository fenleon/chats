# Chats push — test rig for push-without-Google on the LP3

How the LP3 can receive Beeper/Matrix push with **no Google services**: the
standard Matrix **HTTP pusher** path — the same mechanism Beeper's own clients
use (their config points at `sygnal.beeper.com`, Matrix's reference push
gateway; the Beeper desktop client on this Linux box is the live proof that
Beeper → non-FCM delivery exists).

```
matrix homeserver                         the relay (ntfy.sh)              LP3
┌──────────────────┐   POST /_matrix/ ┌──────────────────┐   channel    ┌──────────────┐
│ message arrives  │  push/v1/notify  │  /notify (queue) │ ◄─────────── │ companion    │
│ push rules match │ ───────────────► │  /wait (long-poll)│  holds open │ wakes, syncs │
│ pusher data.url  │                  │  /events (SSE)   │             │ once, notif  │
└──────────────────┘                  └──────────────────┘             └──────────────┘
```

No tokens leave the phone: the gateway is a dumb relay. The phone gets a
notification (room, event id, sender, count), does **one** `/sync`, posts the
local notification, and goes quiet — instead of the current always-on
long-poll / 5-min rounds.

## Two theories, one gateway, both proven against the local dev Synapse

- **Theory A — self-hosted gateway + companion-held channel.** The companion
  (or a tiny daemon) keeps one outbound connection to a small gateway on the
  user's always-on Linux box. `POST /notify` receives the homeserver push;
  `GET /wait` is the long-poll channel (reconnect on timeout). The LP3 uses
  OkHttp (already a Trixnity dependency). **PASS (2026-08-16, local Synapse).**
- **Theory B — UnifiedPush shape.** `GET /events` is an SSE stream (with
  heartbeats) — exactly the delivery channel a UnifiedPush gateway exposes.
  The companion could instead be a UP connector with a distributor app on the
  LP3. Same gateway, same pusher registration. **PASS (2026-08-16, local
  Synapse).** Not chosen for v1: adds a distributor app; Theory A is fewer
  moving parts on a calmness-first device.
- **Theory C — Beeper-native (observation, not built).** Register the pusher
  with `data.url = https://sygnal.beeper.com` and connect a websocket to
  sygnal directly — no self-hosted gateway. Same gating question as A/B
  (does matrix.beeper.com honor an external pusher?), but depends on Beeper's
  private infra for every delivery. Kept as a fallback if A/B's gating test
  fails.

## Findings that matter for the real build

- **Modern homeservers use `POST /_matrix/client/v3/pushers/set`** with a
  **flat** body: `app_id`, `pushkey`, `kind: "http"`, `app_display_name`,
  `device_display_name`, `lang`, `data.url`. The legacy `PUT /pushers` is gone
  (this Synapse; Trixnity's `api.push.setPushers` targets the new endpoint).
  Delete = same endpoint with `kind: null`.
- **The gateway URL must end in `/_matrix/push/v1/notify`** (Synapse
  validates this; Beeper's fork may not — the gateway serves both paths).
- **Synapse blocks pusher URLs to loopback/private IPs** by default (SSRF
  guard, `403 IP address blocked` in the log). The dev homeserver config at
  `/tmp/synapse/homeserver.yaml` got an `ip_range_whitelist` for
  loopback/private (backup: `homeserver.yaml.bak-push-test`). Undo = restore
  the backup + restart. Only the dev server — real Beeper pushes come from
  their servers to a public URL.
- **Full-format notify payload** carries `room_id`, `event_id`, `sender`,
  `prio`, `count` and the event `content` — enough to render a notification
  without syncing; `format: "event_id_only"` shrinks it further if wanted.
- Delivery is asynchronous: Synapse's pusher loop retries with backoff
  (`Push failed: delaying for Ns`), so a relay being briefly down is not
  data loss.

## LP3 companion integration — IMPLEMENTED (2026-08-16, emulator + LIVE verified)

The direct-SSE channel lives in the companion (`PushChannel.kt`, one object):

1. On login + session restore (`MatrixRepository`): registers the Matrix
   pusher with `app_id = "com.lightphone.chats"`, a persisted per-install
   `pushkey`, `format = "event_id_only"` and `data.url` = the gateway's notify
   endpoint. Idempotent — the same pushkey replaces the pusher on every start.
2. `PushChannel` holds the OkHttp SSE subscription to the same gateway (manual
   SSE line-reading on the OkHttp already on the classpath via Trixnity —
   zero new dependencies; reconnect with backoff; **`readTimeout(90s)`** — the
   gateway heartbeats every 15s and ntfy's JSON stream keeps a ~45s keepalive,
   so 90s of silence = dead connection, forcing a reconnect instead of
   blocking on a half-open socket). Accepts both `data: {…}` SSE frames
   (gateway.py) and bare `{…}` lines (ntfy's raw JSON stream). On a
   notification: one `syncOnce(Presence.OFFLINE)`
   (`MatrixRepository.onPushDelivered`), which the existing notification
   watcher turns into the local notification via `ChatNotifier`, then idle.
   **The wake is unconditional once a notification payload is recognized** —
   Beeper sends counts-style payloads WITHOUT room/event ids
   (read-receipt/unread updates), and those still warrant a sync.
3. The screen-off 5-min `syncOnce` rounds stay as the fallback delivery (a
   silent SSE drop must not mean missed messages) — push makes delivery
   instant instead of up to 5 minutes late. Now that live delivery is proven,
   they can be loosened or dropped (O4, PLAN.md).

No tokens or message content leave the phone: `event_id_only` payloads carry
no content or keys — push is a wake-up signal only.

Config (URLs required — **no default**, polling-only when unset):
```bash
adb shell am start -n com.lightphone.chats.server/.MainActivity \
  --es pushsse <url>      # SSE subscription URL (from the phone's side)
  --es pushnotify <url>   # pusher data.url (from the homeserver's side)
  --es pushkey <url>      # pusher pushkey — ntfy routing needs https://ntfy.sh/<topic>
adb shell am start -n com.lightphone.chats.server/.MainActivity --es pushclear 1  # remove pusher + config (cleanup)
```
Two URLs are needed because one side sees the gateway from the phone and the
other from the homeserver: `pushnotify` must be **public** (Beeper POSTs from
their servers — a tunnel to the gateway, or ntfy.sh), while `pushsse` is
whatever the phone can reach (LAN IP, tunnel, `adb reverse` loopback in dev,
or ntfy.sh's `/json` stream). **The `/_matrix/push/v1/notify` path is
mandatory** — both Synapse and Beeper reject any other path. ntfy.sh serves
exactly that path (see "ntfy.sh WORKS" below).

**Verified end-to-end on the emulator (2026-08-16):** dev Synapse → gateway.py
→ SSE → companion with a dummy account pair — `pusher registered`,
`SSE connected`, `push received: room=… event=…`, then
`ChatNotifier: notify <room> — <sender>: <body>` for both screen-on (long-poll
already delivers; push is the parallel signal) and screen-off idle (slow-sync
engaged; the push-wake `syncOnce` was the only delivery path). Logcat tags:
`PushChannel`.

## ntfy.sh WORKS — Matrix Push Gateway at the required path (2026-08-16 re-verification)

Earlier entries in this file called ntfy.sh "dead" — that was wrong. ntfy
implements a **Matrix Push Gateway** (`/_matrix/push/v1/notify`, docs
§Matrix Gateway) that routes each push **by the `pushkey` in the payload** to
an ntfy topic — the body-routing that was assumed not to exist.

**Verified end-to-end (2026-08-16, live):**
1. **Homeservers accept the URL** — `data.url = https://ntfy.sh/_matrix/push/v1/notify`:
   local Synapse returns 200 (fresh 4-URL registration test) and **Beeper
   accepted it on the real account** (`setPushers ACCEPTED`, `--es pushtest`).
   The path check is path-only — the host is not inspected
   (`synapse/push/httppusher.py:157`, "url must have a path of
   '/_matrix/push/v1/notify'").
2. **ntfy accepts the payloads** — POSTs of Beeper's exact recorded shapes
   (event-style and counts-style) return `200 {"rejected":[]}` and publish to
   the topic named by the pushkey.
3. **App chain (emulator, local Synapse, dummy users):** pusher registered with
   the URL pushkey → message from user B → Synapse POSTs → ntfy routes → SSE →
   `push received → waking one sync` → `ChatNotifier` notification.
4. **The pushkey must be a full URL** (`https://ntfy.sh/<topic>`); bare strings
   are rejected by the gateway (`{"rejected":[...]}`). Per-install random
   topic = a private bearer token (topic-as-password), not shared.

**Working config (all three extras):**
```bash
--es pushkey https://ntfy.sh/<random-topic>          # routing: pushkey IS the topic URL
--es pushsse https://ntfy.sh/<random-topic>/json     # ntfy raw JSON stream
--es pushnotify https://ntfy.sh/_matrix/push/v1/notify
```
(`--es pushkey` added 2026-08-16.)

**Current install state (2026-08-17, DEPLOYED):** the LP3 runs the final
auto-provisioning build — push is **on with zero setup** (install → launch →
auto-provisions a private ntfy topic `chats-<32-hex>` → registers the pusher
on Beeper → SSE). Live-verified on the emulator (install-and-go cycle:
pushclear → relaunch → fresh topic → `?since=all` → push received →
notification). **Beeper→ntfy delivery live-proven** (real counts-style POST
landed on the topic; the phone's SSE consumed a synthetic POST in real
time). Note-to-self pushes stay intermittent on Beeper's fork (state-update
payloads); the reliable trigger is incoming bridged traffic. Lifecycle:
config is static per install (prefs), regenerated only by `--es pushclear 1`
(which now also clears the `?since` resume point). To re-test/cleanup:
`--es pushclear 1`.

**Caveats:** ntfy's gateway has a cold-topic rejection path (a push is
rejected if the topic has no recent visitor AND the publish hits a storage
error) — keep the SSE connected; it reconnects with backoff and stays warm.
A silent SSE gap is caught by the 5-min fallback rounds (no data loss).
`format=event_id_only` keeps message content off ntfy — a leaked topic leaks
event/room ids only. The matrix-push path is receive-only, exactly like the
gateway.

**Cross-checked against canonical sources (2026-08-17)** — ntfy server
`server_matrix.go`, ntfy-android `ApiService`/`JsonConnection`,
unifiedpush.org gateway docs, docs.ntfy.sh §Matrix Gateway. Verdict: pipeline
matches the canonical design — pushkey must start with the gateway base URL
(`HasPrefix(pushkey, baseURL+"/")`) and IS the publish URL (topic = path);
the gateway parses `notification.devices[].pushkey` (devices under
notification — the shape Synapse/Beeper actually send) and uses only the
first device; rejection only for non-prefixed pushkeys or a topic cold > 12 h
(+ a 507). **The one gap found vs the canonical client — the `?since` resume
param — is FIXED (2026-08-17):** `PushChannel` now persists the last ntfy
message id (`push_last_msg_id`) and subscribes as `?since=<id>` (first ever
connect: `since=all`), so a reconnect replays pushes published during the gap
(ntfy caches ~12 h for exactly this, docs/subscribe/api.md §since). Verified
on the emulator: message B sent while the app was stopped was replayed on
relaunch (`SSE connected …?since=<A-id>` → `push received`). The 5-min
polling fallback remains for the other gap class — Beeper not POSTing at all
(their note-to-self intermittency) — which no `since` can fix.

## Verdict (2026-08-16) — after Beeper acceptance test + LIVE end-to-end proof

- ✅ **`matrix.beeper.com` accepts external HTTP pushers and DELIVERS to them
  on real messages — live-proven 2026-08-16.** Full chain on the real account:
  Beeper's pusher loop POSTed both event notifications (WhatsApp-bridge
  messages: `room_id` + `event_id` + `sender` + `counts`, `prio=high`) and
  counts-only payloads (read-receipt/unread updates with `com.beeper.*`
  fields, no event id) → gateway → SSE → the phone's `PushChannel` logged
  `push received` → wake `syncOnce` → `ChatNotifier`. Push-rule matching
  applies as usual: a note-to-self **without** the user's name matched no
  rule (no POST); with the name it did.
- ✅ **Beeper's own push infra is not usable by an external client.** Their
  sygnal instance (and upstream sygnal, incl. their fork) has **only
  APNS + FCM/WebPush pushkins — no websocket channel**; the public hostname
  404s everything. `bpns.beeper.com` is the "Beeper Push Notification
  Service" — a server-side relay for *third-party network* credentials
  (iMessage/Signal) that still wakes devices **via Google FCM** (their
  Google-free desktop uses raw FCM/MCS, `beeper/push-receiver`). Beeper has
  **no Beeper-owned push-to-device channel**. Private, undocumented, already
  locked down — not a dependency.
- ❌ **No websocket sync** on matrix.beeper.com (msc3883 absent) — but it does
  advertise **simplified sliding sync** (`org.matrix.simplified_msc3575`),
  a lever for cheaper polling.

## Production architecture — who hosts the relay is the real question

**Constraint (unchanged since the original design): the general public cannot
be expected to run an always-on box.** That is exactly why the ntfy.sh path
matters — and it WORKS (verified 2026-08-16, see the ntfy.sh section above):
ntfy serves the exact `/_matrix/push/v1/notify` path as a Matrix Push
Gateway that routes by the pushkey, so the no-box option is alive. The
original self-hosted-gateway design (a `gateway.py` on an always-on box
behind a public tunnel) was **torn down the same day** — ntfy makes it
obsolete (see "What's next").

**Deployment 2 — a hosted relay for everyone (the general-public answer):**
one public service serves `/_matrix/push/v1/notify` and routes each
notification to the right phone **by the `pushkey` in the payload body**
(the Matrix notify body carries `devices[].pushkey` — no per-user path
secret needed, unlike ntfy). Each install registers its SSE channel with the
relay under its pushkey at login; the phone holds the SSE, exactly as in
Deployment 1, but pointed at the shared relay. This is the same shape as a
Matrix push gateway with a websocket/SSE channel (upstream sygnal lacks
one; a chats/community relay would fill it). Cost: the relay must be hosted
+ maintained — the same box run as a public service, or community infra.
A UnifiedPush distributor app is the standard way devices register channels
with such a relay (the README rejected it for v1 only as "an extra app on
the LP3" in the box-owning context — it becomes the natural fit for a
hosted relay).

**No-box hosting for the relay (not built yet):** a Cloudflare Worker +
Durable Object can host it on the free tier — the Worker serves
`/_matrix/push/v1/notify` (any path is fine on a Worker route), each phone
opens an SSE/WebSocket to `https://<worker>/channel/<pushkey>` backed by a
Durable Object per pushkey, and the Worker forwards Beeper's POST (routed by
the body's `pushkey`) to the object holding that phone's channel. ~150 lines
of Worker code, no box, no tunnel, no cost — the concrete no-always-on-box
production path.

**Fallback: polling** (already built) — the only zero-third-party,
zero-infrastructure option, works for everyone today. If Beeper's
simplified sliding sync (`org.matrix.simplified_msc3575`) is usable, polling
gets much cheaper.

Trade-off vs the UP-standard path (distributor app + connector lib): the
distributor's value is sharing ONE connection across many apps — with a
single app it's pure overhead (an extra app on the LP3). If chats ever wants
multi-app push sharing, the UP connector is a drop-in upgrade; the pusher
registration doesn't change.

## Encryption — no issue

Push is a **wake-up signal only**: the notification payload (`event_id_only`)
carries no message content and no keys. All megolm sessions stay on-device;
the phone fetches the event through its own authenticated, encrypted `/sync`
and decrypts locally. Exactly how E2EE messengers treat push everywhere.

## What's next

1. **✅ LIVE DELIVERY TEST PASSED (2026-08-16):** Beeper POSTs to an external
   HTTP pusher on real messages — event-style AND counts-style payloads,
   received end-to-end on the LP3 (`push received` → wake → `ChatNotifier`).
   The test pusher was removed afterwards (`--es pushclear 1`).
2. **✅ ntfy.sh IS THE PRODUCTION PATH (2026-08-16/17):** ntfy serves the exact
   `/_matrix/push/v1/notify` path as a Matrix Push Gateway that routes by the
   pushkey — no box, no tunnel. The LP3 runs the auto-provisioning build
   (install → launch → private ntfy topic → pusher on Beeper → SSE, zero
   setup). The self-hosted gateway/tunnel deployment (a named Cloudflare
   tunnel `push.fenleon.com` built 2026-08-16, both legs verified) was
   **torn down the same day** — ntfy makes it obsolete.
3. Once running in production: the 5-min fallback rounds are proven redundant
   — loosen `SLOW_SYNC_INTERVAL_MS` or drop the rounds entirely (O4 end
   state), then an LP3 overnight `batterystats` for the real battery score.
4. Transport notes from the test: adb-reverse (USB) is fine for dev but can
   stall silently — the SSE `readTimeout(30s)` self-heals; the phone's wifi
   path to the gateway LAN IP is the solid production route (host firewall
   permitting).

## What you can do to help (user actions, 2026-08-17)

Push is **deployed on the LP3 with zero setup** (ntfy auto-provisioning —
see "ntfy.sh WORKS" above; the earlier self-hosted tunnel experiment was
torn down as obsolete). What's left is measurement:

1. **Use the app normally for a day or two** and report: notification latency
   with the screen off, and any missed messages (the 5-min fallback rounds
   catch silent drops, so nothing is lost — that's their job).
2. **Overnight `batterystats` on the push build** — the real battery score.
   Same ritual as the polling audit: install, `batterystats --reset`, unplug,
   screen off overnight, hand the phone back in the morning. Compare vs the
   polling baseline (84.8 mAh, 17% of a core, 1.3%/h).
3. **Optional: run the sliding-sync probe against the real account** — the
   hook (`runSlidingSyncProbe`, `--es slidingsync 1`) is built and
   emulator-verified against the local Synapse (R1-R4, scale, incremental
   deltas all PASS — see WORKLOG top entry). What's unproven is Beeper's
   side; one receive-only run tells us if polling gets ~10× cheaper and push
   stops being urgent. (WORKLOG 2026-08-17.)

## Safety boundary

This rig only ever talks to the **local dev Synapse with throwaway users**.
Real-account tests (the live delivery test, the sliding-sync probe) are
receive-only — register a pusher / read an endpoint, never send or deliver
anything to anyone; per user instruction they still require explicit go-ahead
before touching the real account.

## UnifiedPush feasibility (2026-08-21) — "LightOS is a UP distributor now" assessment

User relayed (feedback round 11): *"LightOS is a UnifiedPush distributor app
now (though admittedly we need to test this more), so you shouldn't need to
use ntfy.sh. Should be able to take advantage of our existing notification
channel and save even more battery/simpler setup."* Assessment requested
before deciding anything. What the SDK actually does, and where the two
protocols meet.

### What the SDK's push surface is

- `light-sdk/sdk/client` implements the **UnifiedPush app** role:
  `LightPushService : PushService()` + `LightPushManager` (persists the
  endpoint), and `LightSdkApplication` calls `UnifiedPush.saveDistributor(this,
  serverPackage)` then registers two instances — `light-local` (tool↔server
  IPC) always, `light-push` (remote) only when the tool's `LightEntryPoint`
  sets `enablePushNotifications = true`. Messages arrive via
  `onPushNotification(data: ByteArray)`.
- **The distributor is the SDK server package.** For chats that is
  `com.lightphone.chats` itself (single-APK self-serving build), NOT
  `com.lightos` — so chats' SDK UP registration currently targets its own
  embedded server. Chats' tool also doesn't implement `LightEntryPoint`, so
  the remote instance is never registered.
- Chats' Matrix push (this file's whole subject) is entirely independent of
  the SDK's UP plumbing: a Matrix HTTP pusher → ntfy.sh gateway → OkHttp SSE
  held by the companion (`PushChannel.kt`).

### Where the protocols meet (and where they don't)

- **Matrix push** = the homeserver POSTs the notify body to the pusher's
  `data.url`, which must end in `/_matrix/push/v1/notify` (Synapse validates;
  Beeper accepted ntfy's, which serves exactly that path).
- **UnifiedPush** = the app registers with a distributor and receives an
  endpoint; the app's OWN backend POSTs `{"message": …}` to it; the
  distributor delivers to the device. The endpoint is not a Matrix push
  gateway — no `/_matrix/push/v1/notify` path, different payload shape
  (`notification.devices[].pushkey` routing).
- **Therefore a relay between the two is unavoidable today**: Beeper's pusher
  cannot point at a generic UP endpoint (path check + body shape). The UP
  channel only replaces the **device side** of the current chain (the phone's
  SSE socket → the distributor's connection, shared across apps). The
  notify→device relay must still exist somewhere — either ntfy.sh (today) or
  a hosted gateway whose backend speaks to LightOS's distributor (Light-hosted
  or the community relay from "Production architecture" above).

### What the switch would look like (when the relay question is answered)

1. The chats companion registers a UP instance with `com.lightos` as the
   distributor (the connector lib is already on the classpath via the SDK
   client) and implements `onMessage` → `MatrixRepository.onPushDelivered`
   — replacing the SSE subscription in `PushChannel`. Device side: trivial.
2. The Matrix pusher registration stays identical (`kind = http`, pushkey),
   but `data.url` becomes the gateway's `/_matrix/push/v1/notify` — the only
   real question is who hosts that gateway and whether it can reach the
   distributor/device (Light's push infra, or the hosted relay with a
   UP-facing device side).
3. Battery: the phone drops its per-app SSE socket; the distributor's shared
   connection serves it — the win the user is after, IF LightOS actually
   keeps one persistent channel.

### Verdict + next step

Feasible in principle; **not shippable today**. The device side is easy; the
missing piece is a public Matrix-notify gateway that delivers through
LightOS's UP distributor (or Light exposing a `/_matrix/push/v1/notify`-
serving endpoint that routes to devices). ntfy.sh remains the zero-setup
production path meanwhile — it IS the notify→device relay, just with an SSE
device side instead of UP.

Test sequence (needs a real LP3 + LightOS with the distributor): (1) enable
the SDK remote push instance on a test build (`enablePushNotifications = true`
in a chats `LightEntryPoint`) and confirm `com.lightos` delivers an endpoint
+ actually routes a message — proves the distributor; (2) capture the
endpoint's URL shape and check whether it can accept the Matrix notify body;
(3) decide with the user/Light whether the notify→UP gateway is theirs to
host. Until then: keep ntfy; revisit when the distributor is proven.
