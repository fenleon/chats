# Chats — UI as a view of a committed fact (branch plan)

**Status: planned, NOT started. This work goes on a NEW BRANCH off main.**

Sequencing vs the current line: main first resolves its open work — commit the
SYNC-PERF Phase 1 ingest gate (implemented, emulator-verified, uncommitted as of
2026-09-05) and push a release. Only then branch (suggested name `ui-as-view`)
and start Phase W. Rationale: Phase I (ingest) is the dominant latency term and
is already written; don't entangle an unfinished perf fix with new UI plumbing.

## Goal

No screen in Chats ever fetches because a timer fired. Every render follows a
committed fact: the companion publishes state (revision bump) → the tool learns
of it in the same instant → renders. Timer-driven fetches go to zero; timeouts
exist only as dead-man switches.

Evidence base: Beeper decompile (Room invalidation flows, zero polling) and the
Molly Light LP3 test (2026-09-06, WORKLOG): socket→pixels ≤460 ms foreground,
58 ms background delivery, WS survives LightOS backgrounding — the event-driven
model is proven on-device; only our ingest + publish + poll chain is slow.

## Phase W — Wake channel (the core; ~1 day)

The tool can't observe the DB (Light SDK tool/companion split), so the companion
pushes the instant a fact commits — via a held binder call, the one push
primitive the transport already supports (`binder.transact` blocks until the
server replies; the client-side 5 s timeout only gates awaiting the binder, and
`awaitOutboxAck` already proves 2 s holds work).

1. **Server (`MatrixRepository`)**: `suspend fun waitForRoomListRevision(current,
   timeoutMs)` / `waitForMessagePageRevision(roomId, current, timeoutMs)` —
   return immediately if already ahead, else park on a `MutableSharedFlow` until
   the revision moves or timeout. Wake sites = exactly where revisions bump
   today: `publishRoomList`, `publishRoomRowNow`, the page-publish path, outbox
   ack/echo, send/typing changes. Coalesce (one wake per publish, not per
   event). Cap the hold at ~15 s; on timeout return the current revision (the
   dead-man re-check — no separate fallback loop).
2. **SDK patch**: two new `LightServiceMethod`s (`WaitRoomListRevision`,
   `WaitMessagePageRevision`) following the existing patched-method pattern in
   `LIGHT-SDK-PATCHES.md`.
3. **Tool**: in `ChatListScreen` / `ThreadScreen`, replace
   `delay(POLL_INTERVAL_MS)` + revision check with `waitFor…` + the existing
   revision-gated fetch. The revision gate stays the truth check — wakes are
   hints; a dropped wake costs one timeout.
4. **Binder death**: `callRemoteServiceMethod` already errors on dead bindings;
   the loop re-enters the wait. No new machinery.

**Verify (emulator, alice+bob on Synapse):** message from bob → list row updates
with no scheduled wakeup in between (logcat: zero timer-driven fetches between
event-store and render); change→frame latency in the tens of ms; idle cost = 1
held call per open screen. LP3 verification rides along with Phase I's
instrumented logcat window.

## Phase S — Send becomes commit-then-render (~half day)

The composer renders the local fact, never waits for the network.

1. **Server**: `sendMessage` persists to the outbox and returns the txn
   immediately — delete `awaitOutboxAck` and the 100 ms poll loop
   (`SEND_ACK_WAIT_MS`, `SEND_ACK_POLL_INTERVAL_MS`). The homeserver ack becomes
   just another committed fact: ack bumps the page revision → Phase W wakes the
   thread → fetch delivers the real event id → pending row replaced. The
   optimistic-row machinery already handles `local-<txn>` rows.
2. `setTyping` / `markRead` from the composer: fire-and-forget (launch, don't
   await).

**Verify:** composer pops back in one round-trip (~10 ms binder), message
appears instantly, ack replaces the row without flicker, failed send surfaces
the outbox error row as today.

## Phase C — Kill the remaining pollers (~half day audit)

`rg "delay\(" chats/app/` — every hit is a violation of the law until proven
otherwise. Known offenders: contact panel `getRoomFlags` live poll,
connection-state checks, cold-start retry loop (10×1 s). Convert each to: a
revision wait where a revision exists, an event-driven refresh on the wake
channel (add a room-flags revision if needed), or a one-shot on screen entry.
Only user-facing animations and the dead-man timeout inside the wait itself may
keep a `delay`.

## Phase I — Make the committed fact fresh (existing spec, parallel track)

The law fixes *when the tool sees the fact*; the fact is only as fresh as
ingest. That's `chats/docs/SYNC-PERF-SPEC.md` phase 1 (implemented, uncommitted)
→ LP3 instrumented window on the 1284-room account → that spec's remaining
phases. This track decides whether felt latency is ~50 ms or ~20 s; Phases
W/S/C are pointless without it.

## Sequencing

Main: commit Phase 1, push release → branch `ui-as-view` → W (emulator-ready
now) → S → C. W+S+C ≈ 2 days. Phase I continues on main per SYNC-PERF-SPEC.

## Done when

- `rg "delay\(" chats/app/` shows only animations.
- A message sent while the thread is open renders with no timer-driven RPC in
  its trace, measured in tens of ms on the LP3.
- Idle: zero periodic binder calls when no Chats screen is open (the 2 s poll
  currently wakes the binder the whole time the tool is open — battery win on
  top of the latency win).
