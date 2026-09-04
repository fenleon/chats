# Chats sync-perf spec — event latency + battery

Source: optimization-planning session, 2026-09-04. Phase 0 is the next step —
start a fresh session, read this file, implement Phase 0 only.

## Problem

User report: "all events take 20 s to update." Goal 1: events feel quick. Goal 2: battery stays good.

### What actually produces the ~20 s (root-cause model, evidence-based)

There is no single 20 s constant in the code. An incoming event's end-to-end path
while the screen is on sums to roughly that number:

| Hop | Cost | Evidence |
|---|---|---|
| 1. Trixnity sync ingest (parse → decrypt → Room store) | **17–30 s** on the live 1284-room bridged account (30–50 s before the v6 sync filters) | `MatrixRepository.kt:3751` on-device admission; WORKLOG 2026-08-28 analysis |
| 2. Room-list resolver publishes only at pass end | up to **12 s** pass budget (`ROOM_LIST_PASS_BUDGET_MS`, :8856) + rotation — a room late in the cursor waits a full extra pass | `publishRoomList()` only at :6789, revision bump :8074 |
| 3. Tool-side poll tick notices the revision | up to **5 s** list / **3 s** thread | `ChatListScreen.kt:382`, `ThreadScreen.kt:1144` |

**The dominant term is hop 1, and it compounds:** Trixnity processes sync
responses sequentially — while a 17–30 s ingest is running, the next long-poll
isn't in flight, so steady-state event latency ≈ ingest time. Every other
component (push wake 1 s debounce, send-wake syncOnce, long-poll tail) is
seconds. Hop 1 is also the battery driver: the 08-31 audit attributes chats'
remaining drain (1 h40 m CPU + 1 h37 m radio/day) to **event-processing volume,
not rounds** — same lever, both goals.

Screen-off is already fast and cheap (ntfy SSE push → 1 s debounce → syncOnce
≈ 1 s; fallback rounds 5 min / 15 min lazy → measured 1.28%/h overnight).

### What the real Beeper APK does (decompile study, 4.55.1)

- **No WebSocket/SSE in app code.** Go SDK runs a classic long-poll `syncLoop`
  (mautrix-go constants: `timeout=30000`, forced `timeout=0` on first sync and
  after failures, 10 s failed-sync wait).
- **Push = data-only FCM `wake` → one-shot WorkManager catchup** (`HandlePushWithCatchup`): max 3 attempts, reschedule **with network constraint** on not-caught-up, **durable push-payload Room queue** (survives process death, batched).
- **No periodic jobs, no wakelocks, no adaptive intervals.** Battery model = "sync either runs (push/foreground) or doesn't run at all" — after catchup, nothing runs.
- **UI is 100 % reactive from the local DB** (Room invalidation Flows) — zero polling in ViewModels. Notifications are emitted **from the sync write path**, so notification latency = sync latency.
- **Not portable:** FCM/imux, the Go SDK, server-side scheduled sends.

Chats already mirrors the good parts (push-gated sync, no FGS background
long-poll when dark, sync-on-push with catchup verification). The gap vs
Beeper is (a) our ingest is slow, (b) our UI is poll-based, (c) our push queue
isn't durable.

## Plan (phased; each phase ends verifiable on emulator, LP3 steps user-directed)

### Phase 0 — Instrument the latency chain (do first; ~half session)

Confirm the model with real numbers before touching anything.

1. Add stage timers, all under the existing runtime opt-in `debugLog` flag:
   - "sync processed: Xms" — wall time around Trixnity's ingest (after HTTP response, before store completion). `sync response: NB in Xms` (:8096) already logs HTTP time; we need the post-response half.
   - "store→publish: Xms" — timestamp when `roomListDirty` is set vs when `publishRoomList` bumps the revision.
   - "revision→RPC: Xms" — log the delta at `GetRooms` between revision change and fetch (tool side, `ChatClient`).
2. One read-only LP3 logcat capture during a natural 10-min window (user
   drives; agents may read logcat freely per workspace policy).
3. Deliverable: a one-line-per-stage latency table → confirms which phase gets the main effort.

### Phase 1 — Make sync ingest fast (the 80 % fix)

Target: per-round ingest from 17–30 s → low single-digit seconds. Levers, in order:

1. **Stop starving the ingest.** Gate heavy in-process work (resolver pass, page rebuilds, ghost walks — all sharing the Room DB + CPU) behind sync activity: while a sync response is being ingested, the resolver sleeps; heavy builds yield. Contention is the suspected multiplier on the 17–30 s (emulator `syncOnce` is 639 ms with the same filters).
2. **Slim the background payload.** Slow-round/push-wake `syncOnce` filter: `timeline.limit` 50 → 10–20 for the *background* filter only (ACTIVE long-poll keeps 50 — the silent-gap concern applies to gap-fill, not steady incremental deltas; Trixnity's `limited` flag keeps correctness observable). Drops parse/decrypt/store work per round on the chattiest rooms.
3. **Drop `m.typing`/receipt already done (v6); next: room state.** Verify the filters exclude `state` for background rounds (state deltas on 1284 rooms are pure overhead for notification purposes; UI reads the local store, not the live response).
4. **Re-measure after each lever** with the Phase 0 timers — stop when the marginal lever buys < 1 s.

Ceiling note (`ponytail:`): if ingest is still > 5 s after 1–3, the next step is Trixnity-level (decrypt-off-critical-path / repository write batching) — out of scope until measured.

### Phase 2 — Shrink the tail after the store (target: store→UI < 5 s)

1. **Publish dirty rooms incrementally.** `publishRoomList()` runs only at pass end after a ≤12 s budgeted crawl. Publish the single dirty room's row immediately when its collector fires (`observeNotifications` already sets `roomListDirty` there), and keep the full pass for reordering/crawl work. This is the root-cause fix for "row appears one pass late".
   **Found during implementation (2026-09-04):** the room-state collector fires BEFORE the timeline event is queryable in the store (`readTimelineChainFromDb … → 0 events` every round), so the row parks on last-known-good and the background ghost walk heals `effectiveLastCache` ~0.8 s later — but nothing re-published after the heal. Fix: `enqueueGhostResolve`'s success path marks dirty + wakes the resolver when it found a NEW real event, so the healed row publishes within ~20 ms of the heal.
2. **Tighten the tool poll ticks** — the revision check is one tiny binder read; list 5 s → 2 s, thread 3 s → 1.5 s while screen-on. Cheap; no flow plumbing needed (tool-side receivers risk plugin-scan bans — not worth it for ≤2 s).
3. **Persist the room-list cache.** ~~Cold process start re-crawls previews~~ **Already shipped** (Phase 12-14.5, `saveRoomListToDisk`/`preloadRoomListFromDisk` — the "memory-only" premise was stale; re-verified on emulator 2026-09-04: cold start logs "preloaded 12 rooms from disk cache").

### Phase 3 — Battery: keep what's proven, measure the rest

1. **A/B the 15-min lazy cadence** (0.8.4, shipped but never measured): one control night vs the 08-20 baseline (1.28%/h). If no measurable win and delivery gaps appear, revert to 5-min flat — simpler.
2. **Durable push queue** (Beeper's `PushPayload` pattern, lite version): persist SSE-delivered pending pushes to a small file so a process death before catchup can't silently drop them (currently only ntfy `?since=` replay saves us). Bounded file, ids only.
3. Push-wake worker hygiene (copy Beeper): keep the existing 3-attempt cap; add the network-constraint-style re-arm (already effectively covered by network-reset callback — verify, don't rebuild).
4. No new periodic jobs, no wakelocks, no keepalive changes — the 08-20 numbers say the FGS/push model is already near the platform floor (~65 % of night drain is system-side).

### Explicitly rejected (Beeper mimicry that doesn't transfer)

- WebSocket/SSE event stream from homeserver — Beeper doesn't have one either; long-poll + push-wake is their model.
- Adaptive intervals by screen/charging/network — Beeper has none; our screen-state mode switch already does this job better.
- `syncLoopTimeout` changes — stays 30 s (rejected before, still right); timeout=0-on-failure like mautrix-go is inherited from Trixnity's loop already.

## Verification

- Emulator (Synapse, seeded multi-room): Phase 0 timers show each hop's ms; Phase 1 ingest < 5 s; Phase 2 row appears < 5 s after server-side event, cold restart shows list without re-crawl.
- LP3: one instrumented logcat window before/after Phase 1 (read-only capture, user drives install). Battery: one overnight after Phase 3 vs 08-20 control night.
- Design review (`lightos-design` skill) only if any UI touch (Phase 2.3 doesn't change UI).
- `tools/check-agents-size` at the end; WORKLOG entry per session.

## Effort estimate

Phase 0: half session. Phase 1: 1–2 sessions (measure-driven). Phase 2: 1 session. Phase 3: 1 session + one overnight measurement. Phases are independently shippable; 0 → 1 is the mandatory order, 2/3 can follow in any order.
