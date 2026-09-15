# Session prompt — chats: projection P2 (delete the derive machinery) + Account "x of n rooms synced" stat

Paste everything below the line into a fresh session in /home/fenn/Repo/light-phone.

---

Execute **P2** of the "Ingest-time projection (2026-09-14): replace derive-at-read"
plan in `chats/PLAN.md` (dated 2026-09-14 — read the full section first), plus
one small addition (the Account sync stat, below). P0 and P1 are landed,
LP3-verified, and signed off.

## Context (read before coding)

1. `WORKLOG.md` (repo root): the **2026-09-15 "Chats: P1 landed"** entry and the
   **2026-09-14 (late) "P0 shadow"** entry — current architecture, what's
   verified, and the two real-account bugs already fixed (backfill silent
   death `c52454f`, table-ready race `30f6aac`).
2. Code: `chats/server/src/main/kotlin/com/lightphone/chats/server/MatrixRepository.kt`
   (~11.5k lines). The projection block is marked `// ---- Ingest-time
   projection ... ----` (~10550-10730): `ProjectionPredicate` (also its own
   file `ProjectionPredicate.kt` + 12 kotlin-test cases), `RoomProjection`
   table (raw SQL inside Trixnity's `matrix_client` DB), ingest hook
   (`observeProjectionIngest` on `subscribeAsFlow`), backfill
   (`backfillProjection` with retry loop), consumers (`projectionRow`,
   `projectionMarkRead`, row flip in `resolveRoomListEntry`, registration-gate
   flip in `observeNotifications`).
3. Beeper reference (behavior spec, not code to copy): `reference/beeper/`
   decompile. The first-sign-in counter design is summarized in the WORKLOG
   2026-09-15 entry ("x of n rooms synced" stat section).
4. Raw dumps from the LP3 verification: `chats-audit-2026-09-15/` (pulled DBs).

## Current state (verified 2026-09-15 on LP3, 358-room account)

- 358/358 rooms projected; room rows + badge + registration gate + disk cache
  read the projection. Old derive path = pre-backfill fallback only.
- Restart test (3× force-stop → open): 1 legitimate notify, 0 ghost fires.
- Storm rooms (224 targets in `chats-audit-2026-09-14/storm-rooms-p0.txt`):
  11/12 clean; 1 benign head = decryptable bridge **batch** re-import of a
  2025 message in a dormant WhatsApp DM (`!80JqZvnSJJHwvSH3SlCu:beeper.local`)
  — replay duplicate a 30-per-window flood rule can't catch.

## P2 scope

**Delete** (all now dead or fallback-only — confirm zero callers via grep
before each removal; the row consumer and gate must end up projection-only):

- `effectiveLastEvent` + `effectiveLastCache` (+ its retry/pin windows) and
  the `collectRelevantTimelineEvents` / summary-gap head walks that fed them
- `isMessageClassHead` stamping (the row-stamp artifact source)
- `servedUnread` + `pendingReadClear`
- `receiptCursorUnreadQuery` / `receiptCursorUnreadBatch` + `unreadCursorMemo`
- preview-retry parks (`previewRetryAtMs` machinery in `resolveRoomListEntry`)
- any remaining disk-preload re-derive remnants

**Handle the no-row case:** with the derive machinery gone, a room without a
projection row (fresh login before backfill; brand-new room pre-ingest) needs
a defined minimal row (name + nothing, or the room summary's ts) — NOT a
re-introduced derive. Keep it dumb; the backfill + ingest hook fill truth
within one round. Decide the smallest correct behavior and note it in the
commit.

**Keep** (plan-mandated): `contentSignature` (send dedupe), `isFloodGhost`
(it MOVES INTO the predicate — fold the flood/txn-replay check into
`ProjectionPredicate` so the decision is one function again), the
decrypt-retry wait that feeds pending encrypted events into the recompute.

**Add — batch-txn replay dedupe (small):** bridge `batch/`-txn history
re-imports (single copies, original old timestamps) currently become the head
of dormant rooms (the 1/12 case above). Extend the predicate's replay rules
so a `batch/` txn-id event doesn't advance the head when an existing row
already has a newer-or-equal head… pick the smallest rule that fixes the case
without re-introducing per-event store walks; add kotlin-test cases.

**Add — Account "x of n rooms synced" stat (one line):** in the tool's
Settings → Account area, render "Synced x of n rooms" where x = COUNT(*) of
`RoomProjection` rows and n = joined-room count (`c.room.getAll()` size or the
sync's room-count estimate — reuse what exists). One query, no new table, no
screen. This is the honest aggregate of restore→sync→projecting (user
request; Beeper's analogue is onboarding-only, ours is persistent).

## Execution order

1. Delete + fold + dedupe + stat, in small commits (one logical group each).
2. Build: `tools/build --dir chats :app:assembleDebug :server:testDebugUnitTest`
   (never two builds concurrently; the wrapper enforces the memory gate —
   stop leftover daemons first if it refuses). kotlin-test for the predicate
   additions only.
3. Verify on the `lightos` emulator (skill: lightos-emulator): login flow,
   room list correct, markRead clears, notification gate quiet on restart ×2,
   stat shows "x of n". Reads are free; the emulator is the default control
   target.
4. LP3: reads (logcat, dumpsys, run-as sqlite pull) are allowed freely;
   **INSTALLS need explicit user permission** (control policy) — ask at the
   verification step, not before.
5. After LP3 verification: update root `WORKLOG.md`, run
   `tools/check-agents-size`, and **STOP for P3 sign-off** (P3 = LP3 overnight
   battery re-test on the final build — the 530 mAh / FGS-slow-mode question).

## Hard constraints

Battery-first (the deletions ARE the battery win — no new steady-state
work); the sync-ingest gate still applies to any store-touching path; privacy
rules (no token/PII logging); never `TaskStop` a running subagent — resume
it. Only `kotlin-test` as test framework. State lives in WORKLOG/PLAN — a
fresh session re-reading them beats a compacted one.
