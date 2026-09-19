# Ingest-Materialized Thread Store — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the read-time page recompute (10–40 s room open on LP3) with a
Beeper-style ingest-materialized `ThreadRow` store: rows written at sync-ingest, room
open = one keyset SELECT, fresh-login backfill bounded per room.

**Architecture:** New raw SQLite table riding Trixnity's DB (the proven `RoomProjection`
pattern). Pure decision logic lives in `ThreadRowLogic.kt` (kotlin-test covered); SQL in
a thin `ThreadRowStore.kt`; one new ingest consumer next to `observeProjectionIngest`;
`getMessages` serves from the store with the existing engine as seed/fallback. Tool-side
(`ThreadScreen`/`ChatClient`) is untouched except speed.

**Tech Stack:** Kotlin, Trixnity 5.8.0 (raw `SupportSQLiteDatabase` via
`db.openHelper.writableDatabase`), kotlinx.serialization, kotlin-test. No new deps.

**Spec:** `chats/docs/THREAD-STORE-SPEC.md` (APPROVED, A–H, 2026-09-18). The plan argues
from the spec; executors read both.

## Global Constraints

- All store code in `chats/server` (unscanned library) — raw SQL/Trixnity is banned in
  the scanned tool module. No new dependencies.
- `ThreadRow` is `CREATE TABLE IF NOT EXISTS` raw SQL inside Trixnity's DB (dies with
  logout `deleteDatabase`) — never an app-owned `@Database`.
- Flood ghosts and non-message-class events never become `kind='message'` rows —
  `ProjectionPredicate` gates every write.
- The tool RPC shape (`LightServiceMethod.GetMessages`) is unchanged; `ThreadScreen`
  and `ChatClient` are not modified in tasks 1–8.
- Served ordering is `ORDER BY timestampMs DESC, ingestSeq DESC` (spec §3 — matches the
  proven ts-stable sort, `WORKLOG.md:2686`).
- Matrix ordering of events inside one sync round must be preserved when minting
  `ingestSeq` (later events get higher seq).
- Never log tokens, keys, or message bodies (workspace privacy rule) — debug logs count
  rows/rooms only.
- Build via `tools/build --dir chats :app:assembleDebug`; tests via
  `cd chats && ./gradlew :server:testDebugUnitTest`. Never two builds concurrently.

## File Structure

- Create: `chats/server/src/main/kotlin/com/lightphone/chats/server/ThreadRowLogic.kt`
  — pure functions: row building from a timeline event, side-row application
  (edit/redaction/status/reaction), `reactionSummary` recompute, keyset cursor math.
- Create: `chats/server/src/main/kotlin/com/lightphone/chats/server/ThreadRowStore.kt`
  — thin SQL: table ensure, batched writes, newest/older page queries, target queries.
- Create: `chats/server/src/main/kotlin/com/lightphone/chats/server/ThreadBackfill.kt`
  — fresh-login backfill worker (Part H).
- Modify: `chats/server/src/main/kotlin/com/lightphone/chats/server/MatrixRepository.kt`
  (569 KB — always Grep for the anchor, read only the anchor's ±30 lines):
  ingest registration (~`:9544`), `getMessages` (`:3261`), gap backfill (`:3502`),
  `messagePageRevision` (`:7775-7784`), login-reset site (`:8094`).
- Create tests under `chats/server/src/test/kotlin/com/lightphone/chats/server/`:
  `ThreadRowLogicTest.kt`, `ThreadBackfillCursorTest.kt`.
- Modify (final task only): `chats/PLAN.md`, root `WORKLOG.md`, this spec's §9 ledger.

---

### Task 1: `ThreadRowLogic` — pure row rules (Part A/B logic)

**Files:**
- Create: `chats/server/src/main/kotlin/com/lightphone/chats/server/ThreadRowLogic.kt`
- Test: `chats/server/src/test/kotlin/com/lightphone/chats/server/ThreadRowLogicTest.kt`

**Interfaces:**
- Produces (used by Tasks 2–4, 7):
  - `data class ThreadRowValues(roomId, eventId, kind, sender, timestampMs, ingestSeq,
    body, formattedHtml, contentType, replyToId, mediaMeta, sendStatus, encrypted,
    prevEventId, batchBefore, targetEventId, payload, reactionSummary)`
  - `enum class RowKind { MESSAGE, REACTION, EDIT, REDACTION, SEND_STATUS }`
  - `fun buildRows(roomId: String, ingestBase: Int, events: List<RawEventInput>):
      List<ThreadRowValues>` where `RawEventInput(eventId, type, sender, originTs,
      contentJson: String?, decryptedBody: String?, formattedBody: String?,
      prevEventId: String?, batchBefore: String?)` — encrypted ⇒ `encrypted=1`, null
      body.
  - `fun applySideRow(target: ThreadRowValues, side: ThreadRowValues, reactions:
      List<ThreadRowValues>): ThreadRowValues` — returns patched target (edit body /
      redaction / sendStatus / refreshed `reactionSummary` computed by
      `reactionSummaryOf(reactions)` = JSON `{reactionKey: count}` via
      `kotlinx.serialization` `Map<String,Int>`), or unchanged target when the side row
      doesn't apply to its kind.
  - `fun keysetBefore(cursorTs: Long, cursorSeq: Int): String` and
    `fun parseKeyset(raw: String): Pair<Long, Int>` — cursor encoding for older pages
    ( `(timestampMs, ingestSeq)` tuple, `\u0000`-joined with escaping not needed: both
    components are numeric, encode as `"$ts|$seq"`).
- Consumes: `ProjectionPredicate` (already exists, `chats/server/.../ProjectionPredicate.kt`)
  — `buildRows` drops rows the predicate rejects for message-class events (side kinds
  REACTION/EDIT/REDACTION/SEND_STATUS bypass the predicate; they are not messages).

- [ ] **Step 1: Write failing tests** — `ThreadRowLogicTest.kt` (kotlin-test, follow
  `ProjectionPredicateTest.kt` style):

```kotlin
class ThreadRowLogicTest {
    private fun msg(id: String, body: String = "hi", ts: Long = 100) = ThreadRowValues(
        roomId = "!r", eventId = id, kind = "message", sender = "@a", timestampMs = ts,
        ingestSeq = 1, body = body, formattedHtml = null, contentType = "text",
        replyToId = null, mediaMeta = null, sendStatus = null, encrypted = 0,
        prevEventId = null, batchBefore = null, targetEventId = null, payload = null,
        reactionSummary = null)

    @Test fun `edit applies to present target`() {
        val target = msg("m1")
        val edit = target.copy(kind = "edit", targetEventId = "m1", payload = "edited!")
        val out = applySideRow(target, edit, emptyList())
        assertEquals("edited!", out.body)
    }

    @Test fun `edit to absent target is caller's no-op`() { // buildRows never emits; applySideRow unchanged
        val target = msg("m2")
        val edit = target.copy(kind = "edit", targetEventId = "mX", payload = "e")
        assertEquals(target, applySideRow(target, edit, emptyList()))
    }

    @Test fun `redaction blanks body and sets contentType`() { /* msg → body="Redacted", contentType="redacted" */ }

    @Test fun `reaction write recomputes summary`() {
        val target = msg("m1")
        val reactions = listOf(
            target.copy(kind = "reaction", targetEventId = "m1", payload = "❤️", sender = "@a"),
            target.copy(kind = "reaction", targetEventId = "m1", payload = "❤️", sender = "@b"),
            target.copy(kind = "reaction", targetEventId = "m1", payload = "👍", sender = "@a"))
        val out = applySideRow(target, target.copy(kind = "reaction"), reactions)
        assertEquals("""{"❤️":2,"👍":1}""", out.reactionSummary)
    }

    @Test fun `reaction redaction drops the key`() { /* remove one reaction row from list → count drops or key vanishes */ }

    @Test fun `keyset roundtrip`() { assertEquals(100L to 7, parseKeyset(keysetBefore(100, 7))) }

    @Test fun `encrypted event is placeholder`() { /* buildRows: decryptedBody=null ⇒ encrypted=1, body=null */ }
}
```
  Fill the three comment-only tests with the same arrange/act/assert shape (redaction:
  `body == "Redacted" && contentType == "redacted"`; reaction-redaction: the ❤️ count
  drops to 1; encrypted: `encrypted == 1 && body == null`).

- [ ] **Step 2: Run** `cd chats && ./gradlew :server:testDebugUnitTest` — expect FAIL
  (unresolved reference: ThreadRowLogic).

- [ ] **Step 3: Implement** `ThreadRowLogic.kt` — pure Kotlin + kotlinx.serialization,
  no Android imports. `buildRows` assigns `ingestSeq = ingestBase + index` (index =
  position in `events`, preserving in-round order); message-class events go through
  `ProjectionPredicate` first. Content parsing: `Json { ignoreUnknownKeys = true }`;
  m.relation for reactions/edits (`rel_type` `m.annotation` / `m.replace`), `m.redaction`
  for redactions, `com.beeper.message_send_status` for status (const mirrors
  `BEEPER_SEND_STATUS_EVENT_TYPE`, `MatrixRepository.kt:4436` region).

- [ ] **Step 4: Run tests** — PASS.

- [ ] **Step 5: Commit** `git add -A && git commit -m "chats: ThreadRowLogic pure row rules"`

---

### Task 2: `ThreadRowStore` — thin SQL wrapper (Part A storage)

**Files:**
- Create: `chats/server/src/main/kotlin/com/lightphone/chats/server/ThreadRowStore.kt`

**Interfaces:**
- Consumes: `ThreadRowValues` (Task 1); a `SupportSQLiteDatabase` obtained in-scope from
  Trixnity DI exactly like `ensureProjectionTable` does (`MatrixRepository.kt:9716-9730`).
- Produces (used by Tasks 3, 4, 5, 6, 7):
  - `fun ensureTable(db: SupportSQLiteDatabase)` — the §1 schema DDL verbatim
    (table + `idx_threadrow_page` + `idx_threadrow_target`).
  - `suspend fun writeRows(c: MatrixClient, rows: List<ThreadRowValues>)` — one
    transaction (`db.beginTransaction/…`), `INSERT OR REPLACE`, per-room `ingestSeq`
    rebase: recompute actual seq inside the transaction with
    `SELECT COALESCE(MAX(ingestSeq),0)+1` for the room (spec §1) so concurrent writers
    can't collide. Also applies pending side rows for targets that arrive in the same
    batch (Task 1's `applySideRow`).
  - `suspend fun newestPage(c, roomId, limit): List<ThreadRowValues>`
  - `suspend fun olderPage(c, roomId, before: Pair<Long,Int>, limit): List<ThreadRowValues>`
  - `suspend fun rowCount(c, roomId): Int` (seed/fallback trigger, Task 4)
  - `suspend fun hasMoreFrom(c, roomId, deepestEventId): Boolean` — deepest row's
    `prevEventId != null || batchBefore != null` (spec §6).
  - `suspend fun markBackfillCursor(c, roomId, batchBefore: String?)` /
    `fun backfillCursor(db, roomId): String?` — Part H bookmarks (PREFS JSON map keyed
    by roomId is acceptable; keep it in this file so callers stay dumb).

- [ ] **Step 1:** Create the file; port the DDL from spec §1 verbatim; implement queries:
  `newestPage` = `SELECT * FROM ThreadRow WHERE roomId=? AND kind='message' ORDER BY
  timestampMs DESC, ingestSeq DESC LIMIT ?`; `olderPage` adds
  `AND (timestampMs < ? OR (timestampMs = ? AND ingestSeq < ?))`. Row mapping
  `Cursor → ThreadRowValues` in one private fun. Follow the
  `ownReceiptTs` raw-SQL pattern (`MatrixRepository.kt:8068-8084`) for cursor/transaction
  handling on `Dispatchers.IO`.
- [ ] **Step 2:** `tools/build --dir chats :app:assembleDebug` — compile green. (No unit
  test: Android SQLite has no JVM test rig; the logic above it is tested in Task 1 and
  the queries are exercised on-emulator in Task 9.)
- [ ] **Step 3: Commit** `chats: ThreadRowStore SQL wrapper`

---

### Task 3: Ingest writer (Part B)

**Files:**
- Modify: `MatrixRepository.kt` (ingest registration ~`:9544`; new private functions
  near `observeProjectionIngest` `:9736-9752`)
- Consumes: `ThreadRowLogic.buildRows` (Task 1), `ThreadRowStore.writeRows` (Task 2).

**Interfaces:**
- Produces: `observeThreadStoreIngest(c: MatrixClient)` — registered beside
  `observeProjectionIngest(c)` at `:9544`, same coroutine scope.

- [ ] **Step 1:** Add the hook: subscribe `c.api.sync.subscribeAsFlow()` (copy the
  exact DEFAULT-priority pattern from `:9739`); on each emission
  `scope.launch { yieldToSyncIngest(); … }` (copy `:9743-9746`); changed rooms =
  `syncEvents.syncResponse.room?.join` keys (`:9740-9741`). For each room: read the
  round's timeline events, map each to `RawEventInput` (eventId/type/sender/ts from the
  raw JSON; decrypted body by reading the persisted `TimelineEvent` JSON the same way
  `readTimelineChainFromDb` decodes it, `:3654` — null body ⇒ placeholder). Call
  `buildRows` + `writeRows`; then `bumpMessagePageRevision(roomId)` for touched rooms
  (`:7775-7784`).
- [ ] **Step 2: Recheck loop** — copy the projection's `pendingDecryption` 30 s recheck
  (`PROJECTION_RECHECK_MS = 30_000` `:9705`, loop `:9870-9876`): every 30 s, for rooms
  with `encrypted=1` rows, re-read their `TimelineEvent`s; decrypted ⇒ rewrite the row
  (body filled, `encrypted=0`, `reactionSummary` of message rows recomputed); run the
  existing key-backup restore `restoreRoomSessions` here (moved off the read path —
  spec §4), bounded per pass.
- [ ] **Step 3:** `tools/build --dir chats :app:assembleDebug` green.
- [ ] **Step 4: Commit** `chats: ThreadRow ingest writer + decrypt recheck`

---

### Task 4: Seed + read path (Parts C, G)

**Files:**
- Modify: `MatrixRepository.kt` — `getMessages` (`:3261`), new private
  `seedThreadRow(c, roomId)`, `serveFromStore(c, roomId, cursor, limit)`.

**Interfaces:**
- Produces: `getMessages` behavior swap. `GetMessages.Request(roomId, beforeEventId?,
  limit)` handling:
  1. `ThreadRowStore.rowCount(roomId) == 0` → serve the existing path once
     (`computeMessagesPage`, unchanged code) **and** `scope.launch { seedThreadRow(...) }`
     (seed = map the computed `MessagesPage.messages` back to `ThreadRowValues` message
     rows + run the reaction/status side-row application, then `writeRows`). Self-heals:
     if the seed write fails, next open retries (rowCount still 0).
  2. rowCount > 0 → `newestPage` / `olderPage` (keyset from `beforeEventId` when
     non-null — the cursor now carries `ts|seq` from Task 1's codec), reactions read
     from `reactionSummary`, receipts via the existing `RoomUserReceipts` read (the
     `:4694-4705` transaction pattern) joined on the ≤20 rows in memory, sender names
     unchanged. No TTL cache, no disk page, no patches on this path.
- Note: `nextBeforeEventId` on store-served pages = encode `(oldestServed.timestampMs,
  oldestServed.ingestSeq)`; `hasMore = hasMoreFrom(deepestServed)`.

- [ ] **Step 1:** Implement `serveFromStore` + `seedThreadRow` + the `getMessages`
  branch. Keep the existing cold-path call intact for case 1 (spec §7: recompute engine
  becomes the seeding tool).
- [ ] **Step 2:** `tools/build --dir chats :app:assembleDebug` green;
  `:server:testDebugUnitTest` still green.
- [ ] **Step 3: Emulator smoke** (lightos skill): install, open a room — page renders
  from store on second entry; first entry seeds. `adb shell uiautomator dump` to verify
  rows + reaction tags.
- [ ] **Step 4: Commit** `chats: getMessages serves ThreadRow; lazy seed fallback`

---

### Task 5: Scroll-up + gap top-up (Part F)

**Files:**
- Modify: `MatrixRepository.kt` — older-page branch of `getMessages`; gap backfill site
  (`:3502-3510`, keep its 300 s failure cooldown).

- [ ] **Step 1:** Older page = `olderPage(keyset(beforeEventId), limit=6)`. When it
  returns < limit rows AND `hasMoreFrom(deepest)` → run the existing gap-marker
  backfill once for that room, write results through `buildRows`+`writeRows` (ingest
  writer rules — Task 3's functions, reused verbatim), then re-serve from the store.
- [ ] **Step 2:** Emulator: scroll up in a backfilled-able room — local pages first, one
  network round only past local history, cursor advances, no re-polled identical cursor
  (the `WORKLOG.md:3347` dead-end class — assert newest visible row changes).
- [ ] **Step 3: Commit** `chats: keyset scroll-up with one-round gap top-up`

---

### Task 6: Fresh-login backfill worker (Part H)

**Files:**
- Create: `chats/server/src/main/kotlin/com/lightphone/chats/server/ThreadBackfill.kt`
- Test: `chats/server/src/test/kotlin/com/lightphone/chats/server/ThreadBackfillCursorTest.kt`
- Modify: `MatrixRepository.kt` — start the worker after initial sync / projection
  backfill completes (`backfillProjection` completion site, `:9758-9821`); reset hooks at
  logout (`:8094`).

**Interfaces:**
- Produces: `ThreadBackfill.start(scope, deps)` — sequential, one room at a time, rooms
  in existing room-list order (whatever order the projection/list already reports — no
  new ordering code); per-room stop: `THREAD_BACKFILL_MAX_EVENTS = 1000` fetched this
  login, or `batchBefore == null` (chain end); resumable cursor per room persisted
  (`ThreadRowStore.backfillCursor`); progress surfaces through the existing
  "Synced x of n" settings line — add nothing UI-side; it advances because synced-room
  count moves. No charging/wifi gates, no global budget, no toggle (spec §8 decision).

- [ ] **Step 1: Failing test** `ThreadBackfillCursorTest.kt` — pure cursor state machine
  (`ThreadBackfillState` object, pure Kotlin): `advance(state, roomId, fetchedN,
  batchBeforeOrNull)` stops at cap (1000), records resume cursor, returns "room done";
  `resume(state)` continues an interrupted room from its bookmark; fresh-login detection
  (`empty store` ⇒ `state = fresh`). Run → FAIL.
- [ ] **Step 2:** Implement `ThreadBackfillState` + the worker (fetch loop reuses the
  gap backfill `/messages` call from `:3502` with its token semantics; each fetched
  batch → `buildRows`+`writeRows`; heals `limited` gaps for free — same walk).
  Run test → PASS.
- [ ] **Step 3:** Wire start + logout reset. Build green.
- [ ] **Step 4: Emulator:** fresh login against local Synapse → rooms backfill in list
  order to cap; force-stop the app mid-backfill → relaunch resumes from bookmark;
  Settings line advances; no media requests in logcat.
- [ ] **Step 5: Commit** `chats: fresh-login bounded ThreadRow backfill`

---

### Task 7: Deletions (spec §10 ledger) — only after Tasks 3–6 verified on emulator

**Files:**
- Modify: `MatrixRepository.kt`, `ThreadScreen.kt` (nothing structural — see below).

- [ ] **Step 1:** Delete from all *served* paths (keep `computeMessagesPage` itself —
  it is Task 4's seeder): memory page cache + `MESSAGE_PAGE_TTL_MS`; disk-page
  save/load/`encodeResponse` (`:2748-2824`) + `DISK_WRITE_THROTTLE_MS` /
  `DISK_CACHE_MAX_PAGES` / `MIN_PERSISTED_PAGE_SIZE`;
  `patchQuietPage`/`patchReadReceipts`/`patchReactionTags`/`patchSendStatuses` +
  `lastRefreshedEventId`; the 250-event send-status and reaction walks from all read
  paths (`SEND_STATUS_WINDOW` machinery); decrypt retry loop from the open path
  (`DECRYPT_RETRIES`/`DECRYPT_RETRY_DELAY_MS`/`DECRYPT_WAIT_MS` waits — key-restore now
  lives in Task 3's recheck). Grep each symbol before removing to catch stragglers.
- [ ] **Step 2:** Build green + full unit tests green.
- [ ] **Step 3: Regression pass on emulator** (spec §11 list): seen-tags on
  receipts-only rooms; reaction tags on quiet rooms; SENDING→delivered echo; burst send
  order; ts-stable ordering in a bridged room; edit-wall pagination.
- [ ] **Step 4: Commit** `chats: retire read-path page caches and walks (store serves)`

---

### Task 8: LP3 verification + docs

- [ ] **Step 1: LP3 (read-only; user drives installs):** logcat timing of `getMessages`
  before/after on the 1284-room account; fresh-login backfill trickle visible;
  batterystats next-day check on the ingest writer's cost.
- [ ] **Step 2:** Root `WORKLOG.md` session entry (design deltas, measurements, the
  `WORKLOG.md:314`-style Beeper cross-reference); `chats/PLAN.md` item already points at
  the spec — mark implemented; spec §9 ledger ticked.
- [ ] **Step 3:** `tools/check-agents-size` (no AGENTS.md grew); `lightos-design` skill
  pass is N/A (no UI surface changed — note it in the WORKLOG entry).
- [ ] **Step 4: Commit** `chats: thread store verified on device; docs`

---

## Self-review notes

- Spec coverage: §1→Task 1/2, §2→Task 3, §3→Task 4, §4→Task 3 (recheck) + Task 7
  (deletions), §5→no task (no sync change), §6→Task 5, §7→Task 4 (seed), §8→Task 6, §10
  ledger→Task 7, §11 verification→Tasks 4–8 steps. No gaps.
- Types: `ThreadRowValues` field list is single-sourced in Task 1 and consumed verbatim
  by Tasks 2–7; keyset codec single-sourced (`ts|seq`).
- Known risk carried from the spec: Trixnity decrypt not atomic with the sync round —
  handled only by the Task 3 recheck; its correctness is the first emulator check in
  Task 4 Step 3.
