# Ingest-Materialized Thread Store — Spec (v1, for sign-off)

**Status:** APPROVED — all parts A–H signed off by user, 2026-09-18. Implementation
plan: `chats/docs/THREAD-STORE-PLAN.md`.
**Problem:** opening a room recomputes the whole visible page (chain walk + 250-event
send-status walk + 250-event reaction walk + eager decrypt, all behind a 2-permit
semaphore): measured 10–40 s on the LP3 (`PLAN.md:391`). Root cause: the rendered page
is a *derived view* built at **read** time. The real Beeper app builds it at **ingest**
time and room-open is a plain paged SELECT.
**Reference:** Beeper Android 4.55.1 decompile (`reference/beeper/`): `Messages` table
(`defpackage/un0.java`), `MessageRepository` Paging3 read path, `IncomingMessageProcessor`
ingest, `MessageSource` enum, `ConversationRemoteMediator`.

---

## 0. Plain-English overview

Today, when you open a room, we *re-derive* what the thread looks like: walk the raw
event chain out of Trixnity's store, apply edits, look up reactions and "seen" tags and
delivery ticks, wait for decryption of anything stuck — every single open, and again
every time the 5-second cache expires.

Beeper never does this. When a message arrives (via background sync), it is written
**once** into a local `messages` table in already-render-ready form — edited body
applied, send state stamped, reactions and receipts stored alongside. Opening a room
reads 50 rows with one indexed SQL query. Scroll-up reads the next 50 from the same
table; only when local history actually runs out does the network get asked. The DB is
kept current by background sync, so a room is always up to date when you open it.

This spec moves chats to that model: **a per-message local store, written by the sync
ingest hook, read by a simple paged SELECT.** The existing recompute engine
(`computeMessagesPage`) survives only as a one-time seeding/fallback path and is then
deleted from the hot path.

What stays ours (deliberately not copied from Beeper): no Paging3 dependency, no
reliance on Beeper-server-injected ordering fields, no bulk login backfill of all
history, and our existing FGS/long-poll sync stays. Details per section below.

---

## 1. The store — `ThreadRow` table

### What Beeper does and why
One giant polymorphic `Messages` Room table (`jadx/sources/defpackage/un0.java`) — one
row per renderable item, with every derived fact as a column: `isEdited`/`lastEdition*`
(edited body), `echo_*` (send state), `countsAsUnread`, `reaction_description`,
`inReplyToId`. Indexed for paging: `(roomId, order, timestamp, originalId)`. Why: the UI
is then a dumb paged reader; no derivation can ever be stale *on open*, because the
ingest is the only writer.

### Why we don't do it today
We came at it from the other side: Trixnity owns the event store (raw JSON blobs, no
order column — `TimelineEvent` table is `(roomId, eventId, value JSON)`), and we built
`computeMessagesPage` as a read-time view over it. That was the smaller diff at the time
and it is correct-by-construction; the cost only became measurable (10–40 s) later.

### Can we do it?
Yes. We already run a raw side-table inside Trixnity's SQLite with an ingest hook:
`RoomProjection` (room-list materialization, `MatrixRepository.kt:9716-9861`,
`ensureProjectionTable` + batched `INSERT OR REPLACE` in one transaction on
`Dispatchers.IO`). `ThreadRow` is the same pattern, one level deeper. Room ignores
foreign tables; the table dies with logout's `deleteDatabase`. New module code goes in
`chats/server` (unscanned library — raw SQL + Trixnity access is banned in the scanned
tool module).

### Proposal

```sql
CREATE TABLE IF NOT EXISTS ThreadRow (
  roomId        TEXT NOT NULL,
  eventId       TEXT NOT NULL,
  kind          TEXT NOT NULL,          -- 'message' | 'reaction' | 'edit' | 'redaction' | 'send_status'
  sender        TEXT,
  timestampMs   INTEGER,
  ingestSeq     INTEGER NOT NULL,       -- per-room monotonic ingest order (minted by us)
  body          TEXT,                   -- render-ready: edit applied, redaction applied
  formattedHtml TEXT,
  contentType   TEXT,                   -- text|image|audio|notice|redacted
  replyToId     TEXT,
  mediaMeta     TEXT,                   -- JSON: durationMs/caption/forwarded flags as served today
  sendStatus    TEXT,                   -- stamped from com.beeper.message_send_status at ingest
  encrypted     INTEGER NOT NULL DEFAULT 0,  -- 1 = body not yet decrypted (placeholder row)
  prevEventId   TEXT,                   -- chain link for hasMore (from TimelineEvent JSON)
  batchBefore   TEXT,                   -- gap?.batchBefore token, for backfill resume
  targetEventId TEXT,                   -- kind != 'message': which row this applies to
  payload       TEXT,                   -- kind != 'message': raw content (reaction key, edit body, status)
  reactionSummary TEXT,                 -- kind='message': cached JSON {reaction: count}, maintained at ingest
  PRIMARY KEY (roomId, eventId)
);
CREATE INDEX IF NOT EXISTS idx_threadrow_page
  ON ThreadRow (roomId, kind, timestampMs, ingestSeq);
CREATE INDEX IF NOT EXISTS idx_threadrow_target
  ON ThreadRow (roomId, targetEventId);
```

Rules:
- `ingestSeq` = `SELECT COALESCE(MAX(ingestSeq),0)+1 FROM ThreadRow WHERE roomId=?`
  inside the same write transaction (per-room, so bridged late-ingest of old events
  can't distort page windows).
- **Flood ghosts are not written** — the existing `ProjectionPredicate`
  (`chats/server/.../ProjectionPredicate.kt`, already kotlin-test covered) runs at
  ingest, same rule as the room list. This is what keeps the store small on the
  1284-room bridged account.
- Reactions/edits/redactions/send-statuses are stored as their own rows (`kind !=
  'message'`) **and** applied to the target row's columns at ingest when the target is
  present (`body` rewritten by edit, `body='Redacted'`+`contentType='redacted'` by
  redaction, `sendStatus` stamped by status events). Reaction writes **and**
  reaction redactions/unsends recompute the target row's `reactionSummary` (JSON map
  `reaction → count` from that target's reaction rows) in the same transaction — the
  column is a cache of the side rows, and the side rows are the only truth. If the
  target isn't stored yet, the
  side row waits and is applied when the target arrives (ingest applies all
  unapplied side rows for a target on write). This preserves today's page-scoped edit
  semantics (edits to events we never stored are ignored) with no extra machinery.
- Logout wipes it via `deleteDatabase` (same as `RoomProjection`). Re-login reseeds.

**Sign-off point A.**

---

## 2. Ingest writer

### What Beeper does and why
Go core syncs/decrypts → callbacks `onSync` / `onDecryptedEvents` / `onBackfill`
(`jadx/sources/matrix/MatrixCallbacks.java`) → Kotlin `IncomingMessageProcessor` writes
`Messages` rows, tagged with a `MessageSource` enum (SYNC/BACKFILL/DECRYPT/…,
`com/beeper/database/persistent/messages/MessageSource.java`). One write path for live
sync, decryption results, and backfill — so the table is always the single truth the UI
reads.

### Why we don't do it today
We *have* the seam already — `observeProjectionIngest`
(`MatrixRepository.kt:9736-9752`: `c.api.sync.subscribeAsFlow()`, DEFAULT priority =
post-store-persist, changed-room list = `syncEvents.syncResponse.room?.join` keys) — but
it only feeds the room-list projection. Message pages are still derived on demand.

### Can we do it?
Yes, with one real constraint: **decryption is not synchronous with the sync round.**
Trixnity v5.8 decrypts in-band and re-persists decrypted content into the
`TimelineEvent` JSON, but not atomically with the round our hook sees. The projection
already solved this (`pendingDecryption` + 30 s recheck, `:9705`, `:9870-9876`); we copy
that exact trick.

### Proposal
`observeThreadStoreIngest(c)` — registered next to the projection hook (`:9544`):
1. On each sync round: for each changed room, for each timeline event in the round:
   - run `ProjectionPredicate`; ghosts and non-message-class events (except
     reaction/edit/redaction/send-status kinds) are dropped;
   - read the event's `TimelineEvent` JSON from the store (it is persisted before the
     DEFAULT-priority flow emits); if `content` is decrypted → full row; else →
     `encrypted=1` placeholder row (body null);
   - `INSERT OR REPLACE` batched, one transaction, `Dispatchers.IO` — same shape as
     `recomputeProjectionRows` (`:9835-9861`).
2. Recheck loop (copy of the projection's): every 30 s, re-read placeholder rows'
   `TimelineEvent`s; decrypted → fill body, clear `encrypted`. Bounded (only rooms with
   placeholders, `LIMIT` per pass).
3. Bump `messagePageRevision` for touched rooms (existing signal, `:7775-7784`) — the
   tool's open-thread push (`pageChanges`, NO-SEAM) lights up for free.
4. Receipts and outbox echoes do **not** come through here (see §4).
5. Backfill results (§6) write through the same function with the same predicate —
   one write path, like Beeper's `MessageSource`.

Cost model vs today: O(new events) once at ingest, replacing O(page + 2×250 walks +
decrypt retries) per open. On a quiet account this is a handful of rows per minute.

**Sign-off point B.**

---

## 3. Read path — room open

### What Beeper does and why
Paging3 `Pager` (50/page) over indexed queries on `Messages`; start offset from a cached
unread marker or cheap local probe (`EXACT | PRELOADED | BOTTOM_PROBE`,
`com/beeper/messages/PagingMessagesOffsetSource.java`). No recompute anywhere on open.

### Why we don't do it today
`getMessages` (`:3261`) → memory cache with a **5 s TTL** (`:10466`) → disk-page JSON →
cold `computeMessagesPage`. Three layers of cache because the underlying derivation is
expensive; the TTL is why re-entering a room after 5 s pays full price.

### Can we do it?
Yes — and we can be *simpler* than Beeper: no Paging3 (not on the plugin allowlist, and
`ThreadScreen`'s custom list + explicit `loadOlder` already work). No offset probing:
"newest page" is just `ORDER BY timestampMs DESC, ingestSeq DESC LIMIT 20` — bridged
out-of-order timestamps are handled exactly as today's stable sort handles them
(`WORKLOG.md:2686-2687`).

### Proposal
`getMessages` becomes, for a warm store:
```sql
SELECT * FROM ThreadRow
WHERE roomId=? AND kind='message'
ORDER BY timestampMs DESC, ingestSeq DESC
LIMIT ? OFFSET 0            -- older pages: AND (timestampMs, ingestSeq) < (cursor ts, seq)
```
- One indexed query on `Dispatchers.IO`, no semaphore, no budgets, no decrypt waits.
- Reactions for the page: read the maintained `reactionSummary` column straight off the
  served rows — no extra read query.
- Receipts ("seen" flags): one indexed read of `RoomUserReceipts` for the room (the
  existing read-transaction pattern, `:4694-4705`), joined against the 20 rows in
  memory. Receipts are ephemeral Matrix data; joining 20 rows is cheaper than rewriting
  rows on every receipt event.
- Sender names: unchanged (`senderNameOf` / batch resolve) — already cheap.
- Optimistic sends: unchanged (`injectPendingEchoes` `:3322-3362` / tool-side
  `mergeWithPending`); the outbox-acked echo replaces the pending row as today.
- The whole page-cache stack **retires for served pages**: memory TTL cache, disk-page
  JSON, `patchQuietPage`/`patchReadReceipts`/`patchReactionTags`/`patchSendStatuses`,
  `lastRefreshedEventId` quiet-guard, `refreshMessagePage` background recompute. The
  store *is* the disk cache. (Kept only for the fallback in §7.)
- `Response`/`Message` RPC shape (`LightServiceMethod.kt:640-741`) is untouched — the
  tool side (`ChatClient.kt:74-80` → `ThreadScreen`) does not change at all except
  speed.

**Sign-off point C.**

---

## 4. Decryption — placeholders, never waits

### What Beeper does and why
`onDecryptedEvents` is a separate callback; undecrypted rows can be written first and
patched when keys land. The UI shows what's renderable immediately.

### Why we don't do it today
The read path *waits*: seed walk + `restoreRoomSessions` key-backup restore
(`:3924-3939`), `DECRYPT_RETRIES=3 × 1.5 s` (`:10472`), per-event 3 s waits
(`:4749`), API re-reads (`:3544`). One stuck event costs the open 10–20 s.

### Could we just not store undecrypted rows?
No — a room whose tail is stuck-encrypted would show a stale page (today's fast-open
path has exactly this bug class; `WORKLOG.md:1201`). Placeholder rows keep the page
shape correct; the 30 s recheck (§2) fills them when Trixnity persists the decrypted
content, and the existing key-backup restore runs at *ingest* recheck time (bounded,
once) instead of on every open.

### Proposal
Placeholder rows with `encrypted=1` render as today's undecrypted rows do (the tool
already handles `encrypted=true` pages). Recheck patches them; read path never blocks
on crypto. The 5 s fetch/decrypt timeouts and 3×1.5 s retry loop are deleted from the
open path.

**Sign-off point D.**

---

## 5. Keeping it current (no change)

### What Beeper does and why
Go-side long-poll + FCM push wake; app holds no socket; DB is current *before* any UI
shows.

### Why we're already fine
`ChatSyncService` FGS long-poll + the push channel work do this role. The tool already
receives per-page revision push (`pageChanges` → `loadNewest(quiet=true)`, implemented
2026-09-07, `NO-SEAM-PLAN.md`). Nothing in this spec changes sync. (The sync-filter /
battery items in `PLAN.md:389` remain separate, orthogonal work.)

### One inherited caveat, stated honestly
Trixnity marks `limited` syncs but never backfills (`PLAN.md:389` note) — a truncated
burst leaves a gap in the *event store*, so it leaves a gap in `ThreadRow` too. This is
today's behavior as well, not a regression. §6's gap backfill is the mitigation, same
as it is today.

**Sign-off point E.**

---

## 6. Scroll-up, deep history, and hasMore

### What Beeper does and why
- Scroll-up = next Paging3 page, local, instant — history bulk-backfilled at login
  (`onBackfill`/`MessageSource.BACKFILL`) means you scroll very far before any network.
- When local runs out: `MessageBatchTokenDao` tokens / `ConversationRemoteMediator`
  fetch from the network via the Go core, results land through the same ingest write
  path, Room invalidation re-emits the page.

### Why we don't do it today
Scroll-up (`loadOlder`, 6 rows) re-runs the **full** non-fast `computeMessagesPage` —
seed walk, gap backfill via `GET /rooms/{id}/messages`, both auxiliary walks — for 6
rows. `hasMore`/`nextBeforeEventId` are chain-derived each time (`:3657-3666`,
`:4067`).

### Could we bulk-backfill all history at login like Beeper?
**Yes — bounded (Part H).** Beeper's server cooperates (bridge-driven backfill tasks);
ours is plain `/messages` pagination, so the compromise is: fresh login only, per-room
depth cap of 1000 events, resumable cursors, progress in the existing "x of n threads
synced" line. Beyond the cap, deep scroll stays fetch-as-you-go (this section's
on-demand top-up).

### Proposal
- Scroll-up = keyset page off `ThreadRow` (`(timestampMs, ingestSeq) < cursor LIMIT 6`,
  keyset not OFFSET — bridged inserts don't shift it).
- `hasMore` = stored `prevEventId != NULL OR batchBefore != NULL` on the deepest served
  row — no walk.
- When the keyset query returns fewer than 6 rows and `hasMore` says more: run the
  existing gap-marker backfill (`:3502-3510`, incl. its 300 s failure cooldown) once,
  write results through the ingest writer, re-serve. First scroll into unfetched
  history pays one network round; subsequent scrolls are local.
- Brand-new login: rooms seed lazily (§7) — first open of each room does the one-time
  seed, after which opens are pure reads.

**Sign-off point F.**

---

## 7. Seeding and fallback (the migration story)

### The problem unique to us
Beeper ships with its store populated at first sync. We ship with **existing installs**
whose `ThreadRow` is empty. Options considered:

- **(chosen) Lazy per-room seed:** on `getMessages` for a room with no `ThreadRow`
  rows, run the *existing* `computeMessagesPage` once, write its rendered rows into the
  store (with `prevEventId`/`batchBefore`), serve. Old rows keep working; the expensive
  path runs at most once per room per login, in the background after a fast first
  paint (the cold-open fast path, `:3300-3309`, already exists as the model).
- (rejected) Bulk seed of all rooms at login: same objection as §6's bulk backfill —
  1284-room walk on battery.
- (rejected) Dual-read forever: keeps both code paths alive; the whole point is to
  delete the recompute from the hot path.

### Fallback correctness
If the seed-write fails (DB busy, process death), the next open retries — `getMessages`
checks "rows exist?" as its fallback trigger, so the system self-heals. The sync-filter
note (`PLAN.md:389`: never truncate a burst — timeline limit 50) applies unchanged.

**Sign-off point G.**

---

## 8. Part H — fresh-login history backfill

### What Beeper does
Server-driven backfill tasks (`backfill_task` table in the Go core) pull full history at
first sync; deep scroll works offline immediately. Their real-device stores run multiple
GB — accepted as normal.

### Decision (user, 2026-09-18): yes — bulk backfill at fresh login, bounded per room, zero ceremony
- **Trigger:** fresh login only (empty store / empty `ThreadRow`). Existing-install
  upgrades are covered by Part G's lazy seed (no network — history is already in the
  Trixnity store; the seed is a local walk).
- **Automatic:** no charging gate, no Wi-Fi gate, no user-facing toggle. It starts once
  the initial sync completes and the room list is known.
- **Order:** rooms are processed in the existing room-list order (recency from the
  initial-sync summaries — known before backfill starts, so no ordering machinery is
  added; we just iterate the list we already have).
- **Per-room depth cap:** stop after `THREAD_BACKFILL_MAX_EVENTS = 1000` events per
  room (~20 full screens) or at the room's creation, whichever first. This constant is
  the spec's single tuning knob. No global budget — storage is accepted (user's real
  Beeper device is multi-GB and that's expected).
- **Resumable:** a per-room cursor (the existing `batchBefore` gap token, `PLAN.md:281`)
  is persisted as it walks. Process death, rate-limiting, or reboot → the next pass
  resumes from the bookmark instead of restarting. Rooms are done sequentially, one at
  a time, at a trickle so the homeserver and the sync loop stay healthy.
- **Progress:** surfaced through the existing Settings "Synced x of n threads" line —
  no new UI.
- **Media:** never fetched (backfill stores event JSON with MXC URLs; blobs stay lazy
  on view/play — the default, per `PLAN.md:375`).
- **Side benefit:** the same walk heals `limited`-sync gaps (`PLAN.md:389`) per room as
  it passes through, since it is already doing `/messages` pagination.

### Interaction with Part F
Scroll-up beyond the cap (or before a room's backfill pass completes) still uses Part
F's on-demand one-round gap top-up. The backfill worker just makes that case rare.

---

## 9. What we deliberately do NOT copy from Beeper

| Beeper mechanism | Why they do it | Why we don't |
|---|---|---|
| Paging3 + LimitOffset source | Standard AndroidX UX plumbing | Not on the plugin allowlist; our custom list + explicit `loadOlder` works; a dep for what a keyset query does is net negative |
| `com.beeper.hs.order`/`.suborder` ordering | Their server injects stream order for their clients | Not available on standard `/sync` (verified: 0/13224 LP3 events carry it, `WORKLOG.md:787`); `(timestampMs, ingestSeq)` reproduces today's proven ordering |
| Bulk history backfill at login | Their server/bridge drives backfill tasks | **Partially adopted now (Part H):** bounded per room (1000 events), fresh login only, resumable. Still not copied: their server-cooperated backfill-task protocol and unlimited depth |
| Reaction summary materialized on the row | Ultra-cheap read with their row density | **Adopted (user, 2026-09-18):** `reactionSummary` cache column maintained at ingest (§1/§2); side rows remain the truth |
| FCM push + two-DB split | Their architecture (Go core + Play services) | We have FGS long-poll + push channel; LightOS constraints, not choices |
| `countsAsUnread` on the message row | Thread-side unread math | `RoomProjection` already materializes unread at ingest for the list; no second copy |

---

## 10. Deletions (the payoff ledger)

After Part 3 (sign-off points A–D live):
- `computeMessagesPage` recompute from `refreshMessagePage`/open paths — kept only for
  §7 seeding and as the §7 fallback.
- Memory page cache + `MESSAGE_PAGE_TTL_MS`; disk-page save/load/`encodeResponse`
  (`:2748-2824`); `DISK_WRITE_THROTTLE_MS`/`DISK_CACHE_MAX_PAGES`/`MIN_PERSISTED_PAGE_SIZE`.
- `patchQuietPage`/`patchReadReceipts`/`patchReactionTags`/`patchSendStatuses` +
  `lastRefreshedEventId` quiet-guard.
- Send-status 250-event walk (`sendStatusByEventId`, `SEND_STATUS_WINDOW`) and reaction
  250-event walk from all read paths.
- Decrypt retry machinery from the open path (`DECRYPT_RETRIES`, `DECRYPT_RETRY_DELAY_MS`,
  per-event `DECRYPT_WAIT_MS` waits) — key-backup restore moves to the §4 recheck.
- `chainDbSemaphore` contention from the open path (walk sites drop from 4 to the 2 that
  aren't thread-reads).

## 11. Verification

- **kotlin-test (server module, pure logic):** side-row application rules (edit onto
  present/absent target, redaction, status stamping, reaction rows), `reactionSummary`
  recompute on reaction write / redact / unsend, `ingestSeq`
  monotonicity, keyset cursor math, seed-fallback trigger, predicate gating. Pattern:
  existing `ProjectionPredicateTest.kt`.
- **Emulator (lightos AVD + local Synapse):** open room ≤1 s warm; scroll-up local;
  gap backfill only on unfetched history; edit/redaction/reaction lands within one
  revision push; stuck-encrypted tail renders placeholders then fills; re-login reseeds;
  logout wipes. **Part H:** fresh login backfills rooms in list order to the 1000-event
  cap, resumes after forced process death from the per-room bookmark, progress line
  advances, no media fetched.
- **LP3 (read-only observation):** logcat timing of `getMessages` before/after;
  batterystats delta on the ingest writer over a day.
- **Regression guards:** existing behaviors with recorded bugs must not regress —
  seen-tags on receipts-only rooms (`WORKLOG.md:2036`), reaction tags on quiet rooms
  (`:1201`), SENDING→delivered echo (`:936`), burst send order (`:2531`),
  ts-stable ordering in bridged rooms (`:2686`), older-page deep-walk past edit walls
  (`:3347` — now solved by storing non-renderable side rows + keyset, verify explicitly).

## 12. Parts to sign off

- [ ] **A — Store:** `ThreadRow` schema (incl. `reactionSummary` cache column) + write rules (§1)
- [ ] **B — Ingest writer:** hook, predicate gating, placeholder + 30 s recheck, revision bump (§2)
- [ ] **C — Read path:** keyset SELECT + reaction GROUP BY + receipt join; cache-stack retirement (§3)
- [ ] **D — Decryption:** placeholders at read, restore at recheck, retry deletion (§4)
- [ ] **E — Sync stance:** no sync changes; `limited`-gap caveat accepted (§5)
- [ ] **F — Scroll/backfill:** keyset older-pages, stored `hasMore`, one-round gap top-up (§6)
- [ ] **G — Seed/fallback:** lazy per-room seed via existing engine, self-healing trigger, then hot-path deletion (§7, §10)
- [ ] **H — Fresh-login backfill:** automatic, list order, 1000-event/room cap, resumable cursors, progress via "x of n threads synced", no media, no gates/budgets/toggles (§8)

Reply per part: "approve" / "revise: …". After sign-off, a task-by-task
implementation plan (TDD steps per part) is derived from this spec.
