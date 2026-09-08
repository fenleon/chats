# INGEST-DERIVED PLAN — Chats: derive row facts at ingest, serve stored facts (Beeper's model)

Status: **implemented (2026-09-08), emulator-verified + LP3 gap-report re-checked**
(all three reported change classes pass; see WORKLOG). Remaining: 24 h soak.
Supersedes the steady-state room-list resolver crawl.
Companion to `NO-SEAM-PLAN.md` (transport, done) and `SYNC-PERF-SPEC.md` (ingest
perf, done). Read this file, then the `MatrixRepository` sections named per step.

## Goal

Every room-row fact (head timestamp, preview, unread count, order) is derived
**once, at ingest time, for the room the change happened in** — Beeper's cost
model (O(events)) — and the UI reads the stored facts. The steady-state
all-rooms resolver crawl dies; it survives only as the cold-start bootstrap.

## Why (the finding, verified 2026-09-08)

The UI reads a materialized snapshot (`_roomList`) rebuilt by a budgeted crawl
whose dirty flag is one account-wide boolean (`roomListDirty`). The sync
response already carries the per-room change set (`syncResponse.room.join`),
and a per-room reactive publisher already exists (`publishRoomRowNow`,
MatrixRepository.kt ~7682) — but its trigger is gated on `RoomSig` (summary
`lastRelevantEventId`/timestamp movement, ~7184-7195), so three change classes
never reach it:

1. **Receipt-only changes** (own `m.read` echo after markRead, or a read on
   another device) — no summary move → row never re-derives → stale unread.
2. **Rooms whose summary the partial sync never re-stamps** (the in-code
   "85 rooms" class, ~8216-8254) — `RoomSig` frozen forever → no bump, no
   timestamp, despite events arriving in the room's timeline.
3. **Unread-only moves** — ride `serverUnreadCounts` → `markRoomListDirty` →
   full crawl pass, not a row publish.

Additionally `servedUnread` (~7795) releases its optimistic suppression by
comparing device wall clock (`System.currentTimeMillis()`, set in `markRead`
~6724) against server `origin_server_ts` — clock skew pins a room's badge at 0
indefinitely.

## Approach

Derive per-room at the point the change is known, publish that one row, keep
the derived store we already have (`roomListCache` + `saveRoomListToDisk`).
The resolver crawl is demoted to cold-start bootstrap.

### Phase A — changed-room trigger (the core)

In the existing sync-flow collector (`observeNotifications`, the
"server-unread collector" site, ~7090-7103 — it already iterates
`syncResponse.room.join`):

- For every room in the `join` map — **not** only when `notificationCount`
  moved — trigger a single-room derive+publish via `publishRoomRowNow`.
  The response's join map IS Beeper's invalidation list.
- Drop the `RoomSig`-change gate on that trigger (keep the gate on the
  separate room-flow collector path — its cost model is fine there).
- Verify `subscribeAsFlow` emission ordering first: the collector must see the
  store AFTER Trixnity persists/decrypts the response's events (one log-line
  probe: response emission vs `store→publish` timing on the emulator). If it
  emits pre-ingest, add a bounded re-read delay for the affected rooms only
  (the existing park/retry windows already cover late decryption).

### Phase B — receipt-driven row re-derivation

- In the same collector, detect per-room receipt changes from the response's
  ephemeral block (`join[roomId].ephemeral` `m.receipt`): own receipt landed
  or moved → that room's unread recompute + row publish.
- Replace `servedUnread`'s wall-clock release with the receipt echo itself:
  `pendingReadClear` releases when the room's own receipt ts/id in the store
  matches or passes the marker (the same check `receiptCursorUnread` reads),
  not on `newestTs > markedTs`. Clock skew can no longer pin a badge.
- Keep `pendingReadClear` only as the optimistic window between `markRead`
  and the echo (one sync round trip).
- **Thin-sync-filter dependency (researched 2026-09-08):** the background/
  push-wake filter (`fullSyncOnceFilters`, ~9588) drops `m.receipt` from
  ephemeral — receipts do NOT arrive in background rounds (battery: receipt
  payloads on the busy bridged account; the foreground `fullSyncFilters`
  still carries them). This is *compatible* with the derivation model and
  must not be "fixed" by re-including receipts in background rounds:
  - Badge **setting** needs no receipts — new messages sync in background
    rounds (timeline limit) and `receiptCursorUnread` computes unread from
    stored receipts + the new head.
  - Badge **clearing** needs the own-receipt echo — but markRead only fires
    with the tool open (screen on, foreground filter active), so the echo
    arrives in the same filter regime that produced the mark.
  - Accepted staleness: a read on ANOTHER device while our screen is dark
    clears our badge on the next screen-on round, not instantly — same
    "fresh for the next wake" contract the resolver cadence already uses.

### Phase C — demote the crawl

- `startRoomListResolver`'s loop keeps only: the **initial crawl** (until
  `initialRoomCrawlDone` — names/previews for the whole account must be built
  once per process) and **pending-resolve retries** (`hasPendingResolveWork`:
  parked `[Encrypted]` previews, ghost-walk retry windows). **No steady-state
  sweep** — pure reactive (confirmed 2026-09-08); any missed trigger shows as
  a stale row and is fixed at the trigger, never swept under.
- Steady-state passes gated on `roomListDirty` are removed; every current
  `markRoomListDirty()` site is audited: sync-derived ones (server counts,
  room-state) become per-row publishes; local-only ones (PIN/MUTE/ARCHIVE,
  verification-state) keep the flags-only fast path they already have.
- Keep: `yieldToSyncIngest` around every derive (unchanged), the eager page
  precompute's screen gate (unchanged), and `publishRoomList` as the only
  publish choke point (unchanged).

### Phase D — read-receipt send honesty (small, separate)

`ChatClient.markRead` (app) wraps `MatrixRepository.markRead` in
`runCatching` and swallows all failures (:136) — a failed `setReadMarkers`
(~6708) vanishes while the badge still clears on the echo-less list. Change:
server-side `markRead` catches its own `setReadMarkers` failure, logs it, and
skips the optimistic clear + `pendingReadClear` when the send failed (the
badge then honestly stays up). Tool-side `runCatching` stays (binder
semantics).

### Phase E — drop the served-list window (confirmed 2026-09-08)

Remove `MAX_ROOMS_OVER_BINDER` + the per-network prepend from `getRooms`
(~2455-2501): every room is served. Rationale:
- The cap is a legacy of the binder seam no-seam removed — the UI path is
  direct calls, no 1 MB transaction; ~300 rows ≈ well under it anyway.
- The window is a staleness machine in the reactive model: a room outside
  the window is invisible regardless of its row being derived — the
  per-network prepend existed only to paper over that.
- Contacts/Search already use the uncapped `getAllRooms` census.

Constraint: `ChatServiceMethods`' `GetRooms` RPC path (adb dev control,
vetted-tools contract) still crosses a real binder transaction — if the
account ever grows past ~1,600 rooms, cap THAT path only (the UI path stays
uncapped). Not needed today at ~300 rooms.

## Deviations from Beeper (status 2026-09-08)

**Confirmed:**
1. **Derived-facts store:** Beeper writes derived facts into its own Room
   tables. We reuse `roomListCache` + the existing disk JSON
   (`saveRoomListToDisk`) as the store — same function (UI reads stored
   facts, updated at ingest), no second database (confirmed 2026-09-08).
2. **Decrypt timing:** Beeper decrypts inline in its Go pipeline; Trixnity
   decrypts asynchronously, so a preview can park `[Encrypted]` for a retry
   window when a megolm key is late (existing park/retry machinery covers
   it). Unfixable without deep Trixnity patches; accepted.
3. **No safety-net sweep** (confirmed 2026-09-08): pure reactive, like
   Beeper. On-device diagnosis rides the runtime `debugLog` flag (off by
   default, `adb shell am start -n com.lightphone.chats.server/.MainActivity
   --es debugLog 1`) — no separate debug build.

**Flagged, not blocking (forced by platform or deliberate parity):**

- **Push transport:** Beeper wakes via FCM (OS-delivered); the LP3 has no
  FCM, so chats runs the ntfy SSE socket + lazy sync rounds as the wake.
  Affects latency/radio, not the row-derivation model.
- **Thin sync filter:** our battery filter leaves most rooms with 0-1
  timeline rows locally, so ingest-time derivation sometimes reads the DB
  chain (head walks) instead of the response payload. Beeper syncs fuller
  timelines and derives from the stream directly. Cost is bounded per
  changed room; revisited only if a walk shows up hot in profiles.
- **Unread definition:** cursor-based own-receipt count with the >6
  same-millisecond group drop (bridge re-import guard). Beeper uses
  server-side `notification_count`; we treat that as fallback only (it
  counts non-message classes we don't render). Known under-count ceiling on
  genuine same-ms bursts of >6 (documented at `receiptCursorUnread`).
- **Order basis (researched against `libgojni.so`, 2026-09-08):** Beeper
  does NOT order rooms by timestamp — its Go stack assigns every event a
  monotonic ingest-time position ("Messages.order"; inbox sorting =
  `decodeAndSortInboxEvents` over those orders, with an "archive floor" so
  below-floor activity can't resurface archived rooms, and receipts get
  "order injection" without bumping chats). Our rows sort by the derived
  head `origin_server_ts`. Near-equivalent in practice (position ≈ arrival
  time), and our walk-back to the newest renderable message matches
  Beeper's non-bump behavior for edits/reactions/acks. Known divergence:
  bridge backfills of OLD messages — Beeper gives them new (high) order
  positions, we sort them at their old ts. Keep ts ordering (matches user
  expectation; Beeper's bump-on-backfill is arguably a bug); adopt position
  ordering only if a real reordering case appears.
- **List window:** removed by Phase E (confirmed 2026-09-08).
- **Order bumps:** edits, reactions, redactions, and bridge status acks
  deliberately do not reorder rows or move timestamps (walk-back to the
  newest renderable message) — consistent with Beeper's order model (see
  Order basis: receipts/edits don't bump a chat's position there either).

## Battery guardrails (unchanged contract with the 08-31 audit)

- Per-event work is bounded by event volume (Beeper pays the same); the
  expensive per-room paths (ghost walk, head walk) run only for changed rooms
  and only after the ingest gate.
- No new wake sources: the collector runs inside the existing sync loop.
- LP3 A/B: idle CPU must stay within the measured 0.14–0.32 cores band;
  `batterystats` window before/after.

## Verification

- Emulator (alice+bob, Synapse): message → row bump/timestamp/unread in one
  publish, no pass in between (logcat: `publishRoomList` without a resolver
  pass); markRead → badge clears on receipt echo (not wall clock); read on a
  second device (bob's other session / second alice client) → LP3 row
  updates without a message arriving.
- LP3: the reported classes — receipt-only rooms (badge), summary-frozen
  rooms (order/timestamp), suppressed-unread rooms — each re-checked against
  the pulled DB head (the 09-08 gap-report method).
- Soak: 24h monitor; idle CPU band (pure reactive — no sweep to delete; a
  stale row after this is a trigger bug to fix at the source).

## Out of scope (unchanged)

- Read-receipt *position* semantics (marker at newest rendered row vs head) —
  the existing `markRead` head-snap logic stands; only failure logging
  changes (Phase D).
- The thin sync filter, push channel, notifications pipeline.
