# Session prompt — fresh-login room list collapses to the pinned rooms

> **ROUND 7 (2026-09-20): FIXED + user-verified.** The collapse is dead —
> `projectRoom` never writes a row below the room's summary timestamp
> (`a6e31f2`), and the restore crawl (now reliably triggered) upgrades rows
> to real heads. Status line made honest ("Synced" gates on restore+backfill
> settling; "Syncing" once rooms land), and the network-label race got a
> one-shot heal (`eb0cb9d`). Ops lessons: killed daemons corrupt Gradle
> up-to-date state (APK mtime gate before every install), and queued
> watchers' pgrep self-matches their own cmdline. Remaining: one clean
> full fresh-login pass (log out → in) as the 0.19.0 release gate.

## Mission

Find and fix the root cause of the fresh-login room-list collapse in chats
(`com.lightphone.chats`), then re-verify the fresh-login end-to-end flow on
the real LP3 (user-driven — read-only adb for you; install only on request).
This is the LAST blocker before cutting the 0.19.0 release; the release is
parked on this test passing.

## Symptom (reproduced 3× on the LP3, real Beeper account, ~360 rooms)

Log out → log in → verify. Then, in order:

1. The room list shows every room as **"Chat"** (placeholder seed rows) — expected.
2. Timestamps/names render in room by room — the heal working (projection rows
   get real `lastRealTs`, the tool's ts-0 junk guard unhides them). Expected.
3. **Then the whole list collapses back to only the 3 pinned rooms.** The
   collapse happens AFTER the rooms rendered correctly — something actively
   removes or re-hides them. It never recovers for the rest of the session.

Also observed the same round: the Account screen's "Syncing messages… x of
362" counter ticks live (fixed in `d1bfebb`), and "connecting" can outlast
several minutes (initial sync round is huge — accepted, not the bug).

## What was already fixed (do NOT redo — commits `54eee4a`…`d1bfebb` on local main)

- `cb78cdc`'s ingest skip dropped own echoes forever → ingest heal + pending
  retirement at the ingest seam + attach repair pass (`7be74fb`).
- Seed routes through the canonical builder (row-builder unification, `7be74fb`).
- Resolver crawl declared done on a cursor wrap over a STILL-GROWING room map
  (froze the list at 3 rooms on the first login attempt) → wrap now counts only
  when the map size is stable across wraps (`49e5dad`).
- The projection backfill's `projectRoom` pass runs BEFORE the key restore, so
  all heads were undecryptable → 360/362 `RoomProjection` rows wrote
  `lastRealTs=0`; the tool hides ts-0 non-pinned rows (ChatListScreen.kt:428,
  correct guard). Fixed by: the decrypt recheck recomputes a room's projection
  when it fills placeholders (`8a866dd`), and the backfill step recomputes the
  projection + publishes the room-list row per room, in lockstep with the
  "Syncing messages… x of y" counter (`d1bfebb`).

## Prime suspect (unverified)

`resolveRoomListEntry` / the resolver pass **remove** a room from
`roomListCache` whenever its `MatrixRoom.membership != Membership.JOIN`
(MatrixRepository.kt ~8312 and ~8489). On a fresh login, Trixnity's room map
streams in progressively — a room's snapshot can transiently report
non-JOIN (member state not yet processed). The removal is STICKY: re-seeding
only happens during the initial crawl, which is already done by the time the
bad snapshot arrives — so the room is gone for the whole session. This matches
"rendered correctly, then disappeared". Second suspect: the resolver's
`hideStaleCommunityDuplicates` over-matching while the network/community maps
are still incomplete mid-login (same-network/same-name keys collapsing when
`network` is null).

## Evidence plan (gather BEFORE fixing — no guessing)

1. Reproduce on the lightos emulator with a Synapse account of ~dozens of
   encrypted rooms (fresh login, not the LP3's 362) — smaller + faster, same
   code paths. The emulator is the default control target.
2. Add TEMPORARY instrumentation (removed before commit) at the three
   removal/hide sites + at publishRoomList: log room count + roomId suffix +
   membership per decision. Enable the device's debug log flag
   (`am start … --es debugLog 1`).
3. Confirm which path removes the rooms (membership prune vs
   hideStaleCommunityDuplicates vs something else), and WHEN relative to the
   key-restore crawl and the ThreadRow backfill progress.
4. Check the disk cache (`files/chats_cache_v3/room_list.json`, count via
   python json) against the in-memory list at the collapse moment.

## Fix guidance

- Root cause only: make removal decisions stable (e.g., never prune on a
  transient non-JOIN during/shortly after the initial crawl — require the
  non-JOIN to hold across a later pass, or re-seed pruned rooms on subsequent
  passes while the room still exists in the Trixnity room map with JOIN).
- Do NOT weaken the tool-side ts-0 guard (it protects against real junk) and
  do NOT re-add read-time derivation (P2 deleted it deliberately).
- Keep the fresh-login UX shape that now works: "Syncing messages… x of y"
  ticking live, list growing in lockstep, "Synced · up to date" at the end.
- Constraints: kotlin-test only; tool module stays plugin-clean; battery rules
  (no new per-event sweeps); update WORKLOG.md + PLAN.md follow-ups.

## Verification

- Emulator: fresh login → list grows monotonically to all rooms, names + ts
  render, nothing collapses after 10+ min; force-stop + relaunch stable.
- LP3: user repeats log out → log in → verify → watch for 15 min.
- Then: rebuild the release APK (`tools/build --dir chats -Dorg.gradle.jvmargs=
  "-Xmx5g -XX:MaxMetaspaceSize=768m" -Dkotlin.daemon.jvmargs="-Xmx2g"
  :app:assembleRelease`), refresh SHA-256 in `chats/releases/0.19.0-notes.md`,
  and hold for the user's go on push/tag/release.

## State at handoff (2026-09-20, late)

- Local `main` (NOT pushed): `54eee4a`, `7be74fb`, `afaadff`, `49e5dad`,
  `8a866dd`, `d1bfebb` on top of `38e1f22`; working tree clean.
- Release APK built earlier is STALE (predates `afaadff`) — rebuild at cut time.
- Changelog draft: `chats/releases/0.19.0-notes.md` (update if the fix changes
  user-visible wording).
- LP3 (serial LP3LHMA551300790) runs the latest debug build (`d1bfebb`).
