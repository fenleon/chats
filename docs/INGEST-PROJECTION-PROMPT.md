# Session prompt — chats: ingest-time projection (execute PLAN.md 2026-09-14 section)

Paste everything below the line into a fresh session in /home/fenn/Repo/light-phone.

---

Execute the "Ingest-time projection (2026-09-14): replace derive-at-read" plan
in `chats/PLAN.md` (the section dated 2026-09-14 — read it fully first).

Context (read before coding):
1. `WORKLOG.md` (repo root), the 2026-09-14 entries — the overnight audit that
   motivated this: bridge re-delivery storm (967 undecryptable
   `m.room.encrypted` copies across 82 rooms), four separate consumer patches
   applied today, row-stamp artifact still open. Raw dumps:
   `chats-audit-2026-09-14/`.
2. The code: `chats/server/src/main/kotlin/com/lightphone/chats/server/MatrixRepository.kt`
   (~11.1k lines). Key pieces per the plan: serial sync-event subscriber
   (~10346), `ownReceiptEchoed` (~7385), `effectiveLastEvent` (9679),
   `isMessageClassHead`, `receiptCursorUnread*` (8206/8265),
   `servedUnread` (8152), `pendingReadClear`, notification gate (~7460),
   `notifyForEvent` (~7592), disk cache (`preloadRoomListFromDisk` 2830,
   `saveRoomListToDisk` 2744).
3. Beeper reference (behavior spec, not code to copy): `reference/beeper/`
   decompile notes in PLAN.md + `reference/beeper/notes/NOTES-ARCHITECTURE.md`
   — per-message `countsAsUnread` decided once at ingest.

Execution order (phases in PLAN.md — do NOT skip ahead):
0. Commit the currently uncommitted `MatrixRepository.kt` fixes first (they are
   today's user-approved audit fixes) as their own commit before touching
   anything.
1. P0 shadow build: `RoomProjection` Room table (same DB as Trixnity's — see how
   the Signal store shares/uses the DB; follow the existing Room setup), ingest
   hook on the serial sync subscriber (O(changed rooms), inside the sync-ingest
   gate), one-time backfill with the predicate (not-renderable ⇒ not new:
   stale-undecryptable encrypted > 10 min, future-stamped > 5 min, m.replace
   edits, own-sender, reactions/acks, `isFloodGhost` replays). Consumers
   unchanged. Build: `tools/build --dir chats :app:assembleDebug
   :server:testDebugUnitTest` (never two builds concurrently; the wrapper
   enforces the memory gate). Add unit tests for the predicate (kotlin-test
   only).
2. Verify P0 shadow on the `lightos` emulator (skill: lightos-emulator; uiautomator
   for UI checks) and then on the real LP3 — reads (logcat, dumpsys,
   uiautomator dump, run-as sqlite pull) are allowed freely; INSTALLS on the
   LP3 need explicit user permission per the control policy. Shadow-compare:
   projection vs live derivation must agree on clean rooms and beat it on the
   storm rooms (`../chats-audit-2026-09-14/` has room ids).
3. Stop. Report the shadow results and get user approval before P1 (flip
   consumers: rows, badge, notification gate, disk cache v3).
4. P1/P2/P3 continue only after each phase's on-device verification and user
   sign-off. P2 deletes: effectiveLastEvent + effectiveLastCache +
   collectRelevantTimelineEvents walks, isMessageClassHead stamping,
   servedUnread/pendingReadClear, receiptCursorUnread(Query/Batch) +
   unreadCursorMemo, preview-retry parks, the disk-preload re-derive. Keep
   contentSignature, isFloodGhost (moves into the predicate), decrypt-retry
   wait (feeds the predicate).

Hard constraints: battery-first (no per-event writes beyond one projection row
per changed room, batched per sync round); the sync-ingest gate still applies
to the hook; privacy rules (no token/PII logging); never TaskStop a running
subagent — resume it. When P1 lands, add the superseding note to
`docs/INGEST-DERIVED-PLAN.md` Phase C. Update root `WORKLOG.md` at the end of
the session.
