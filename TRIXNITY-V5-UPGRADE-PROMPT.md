# New-session prompt — Trixnity v5 upgrade for chats

Paste this whole file as the first message of a new coding session. It is self-contained; the map file it references is `chats/TRIXNITY-V5-UPGRADE.md` in the repo.

---

Implement the Trixnity 4.22.7 → 5.8.0 upgrade for the chats app (workspace: `/home/fenn/Repo/light-phone`). This is a real coding session: write the code, build it, verify it on the emulator.

## Git / branch setup (do this first)

1. Work on a **new branch from `main`** in `chats/`: `git switch -c trixnity-v5-upgrade` (verify it is based on `main`; if the tree is dirty, see below).
2. **The working tree contains unrelated, uncommitted "carryforward archive-pin" WIP** (modified: `app/src/main/kotlin/com/lightphone/chats/ChatClient.kt`, `screens/ChatListScreen.kt`, `screens/ContactScreen.kt`, `screens/SearchScreen.kt`, `screens/ThreadScreen.kt`, `server/.../ChatServiceMethods.kt`, `server/.../MatrixRepository.kt`; deleted: `server/.../MuteStore.kt`; untracked: `CARRYFORWARD-ARCHIVE-PIN.md`, `screenshots/carryforward-*`). **This WIP is deliberately ignored for this task** — it will be re-applied later (it is small compared to this rewrite). Do NOT stage, commit, integrate, or "fix" it. If you need a clean tree, stash it first: `git stash push -u -m "carryforward archive-pin WIP (reapply after trixnity-v5)"` (note: this also stashes untracked files — if `TRIXNITY-V5-UPGRADE.md`/`TRIXNITY-V5-UPGRADE-PROMPT.md` get stashed too, restore them from the stash before proceeding).
3. Never `git add .` / `git add -A` — add only the specific files this upgrade touches.
4. `chats/` is a **public repo** (`fenleon/chats`). No Beeper endpoints/tokens/flows in any committed docs; no verification screenshots showing real account content.

## The upgrade map (authoritative reference)

Read **`chats/TRIXNITY-V5-UPGRADE.md`** in full before touching code. It is a verified per-symbol breaking-change table (symbol | chats call site | v5 replacement | effort), the store-migration verdict, the notification-swap sketch, the dependency list, and Beeper-compat notes — every claim was checked against v5.8.0 sources and javap on 2026-08-28.

Key facts (from the map; the map has the details):
- Group/package rename `net.folivo.trixnity` → `de.connect2x.trixnity` everywhere; plus `clientserverapi.model.users` → `…user` (singular), `…keys` → `…key`, crypto `net.folivo.trixnity.crypto` → `de.connect2x.trixnity.client.crypto` for the olm services.
- **Client creation is the big break:** `MatrixClient.login(...)` and `MatrixClient.fromStore(...)` are gone. New flow: `MatrixClientAuthProviderData.classicLogin(...)` (login request, incl. Beeper JWT via `LoginType.Unknown("org.matrix.login.jwt", ...)`) → `MatrixClient.create(repositoriesModule, mediaStoreModule, cryptoDriverModule, authProviderData = …)`. Restore-from-store = `create(..., authProviderData = null)`; v5 has a built-in Account→Authentication credential migration, so an existing 4.22.7 session restores **without re-login** (do NOT clear `accessToken`/`baseUrl` from the Account row pre-upgrade).
- `syncFilter` / `syncOnceFilter` / `lastRelevantEventFilter` and the `Filters`/`EventFilter`/`RoomFilter`/`RoomEventFilter` constructors survive unchanged (PLAN item-1 work survives).
- **`MatrixRoom.unreadMessageCount` is REMOVED** — replace the 4 reads (MatrixRepository.kt:4527, 4677, 5030, 5102) via the new notification service (`c.notification.getCount(roomId)` / `isUnread(roomId)`); badge semantics become boolean/notification-count, not message-count — this is an accepted UX change.
- **`OlmEncryptionService` was rewritten:** megolm methods moved to a new `MegolmEncryptionService`; `encryptOlm` is 3-arg + has a new batch overload (`encryptOlm(content, recipients: Set<Pair<UserId,String>>)`) that the plaintext-verification override MUST also force plaintext on; `decryptOlm` returns `Result<PlaintextOlmEvent<*>>`; `OlmEncryptionServiceImpl` ctor gains `driver: CryptoDriver`. The plaintext-override pattern itself is still supported (Koin).
- New required dep: `de.connect2x.trixnity:trixnity-client-cryptodriver-libolm:5.8.0` + `CryptoDriverModule.libOlm()` (libOlm = same pickle format as v4 → no crypto migration; do NOT switch to vodozemac).
- Bumps needed: ktor ≥ 3.5.2, androidx.room ≥ 2.8.4, kotlinx-coroutines 1.11.0.
- DB migration: v5 auto-migrates Room schema 4 → 9 on open (no custom migration; but TEST on a copy of a real DB first — schema-4 identity is source-verified, not execution-verified).
- Keep `claimFailuresFixInterceptor` (Beeper `ClaimKeys.Response.failures` still has no default).

## Implementation order (from the map §7)

1. `chats/gradle/libs.versions.toml`: trixnity 5.8.0 + group rename, add cryptodriver-libolm, bump ktor/room/coroutines.
2. Mechanical import renames across the 4 server files (see key facts), then build to surface remaining breaks.
3. Client creation rework (`classicLogin` + `create` + `cryptoDriverModule = CryptoDriverModule.libOlm()`).
4. Module factories: `RepositoriesModule.room`, `MediaStoreModule.okio`, `StoreTransactionManager`, `EventContentSerializerMappings.default` / `EventContentSerializerMappings {}`.
5. Crypto override rewrite (`PlaintextVerificationOlmEncryptionService.kt` + Koin wiring) — including the batch `encryptOlm`.
6. Media: add `maxSize = null` to `getMedia`/`getEncryptedMedia`/`getThumbnail`.
7. Unread counts: replace the 4 `unreadMessageCount` reads (minimal path: keep `notifyForEvent`, re-drive badges from the notification service).
8. Notification swap: minimal path unless it demonstrably falls short.
9. DB migration test on a copy; assert no re-login.
10. Beeper smoke test (whoami `device_id` check is the flagged risk).
11. Cleanup + docs (below).

## Build / verify (repo rules)

- Build: `source tools/env.sh` then `tools/build --dir chats :app:assembleDebug` (memory-guarded — never run two builds concurrently; never build outside `tools/build`). Server unit tests: `./gradlew :server:testDebugUnitTest`.
- Verify on the **emulator** (AVD `lightos`; boot with `tools/emulator.sh` — `-writable-system` required, then `adb root && adb remount`). UI checks via `adb shell uiautomator dump` + `tools/check-screenshot` on captured PNGs — never delegate screenshot verification to a subagent. The emulator's `serverPackage` is `com.thelightphone.sdk.emulator`.
- Real LP3 (serial `LP3LHMA551300790`): read/debug freely, but any control (install/launch/taps/settings) needs explicit user permission.
- **DB migration test:** `adb shell run-as com.lightphone.chats cp databases/chats.db /sdcard/...` (or pull) a copy, then verify a fresh install + restore opens the migrated DB without re-login and without room loss. If a real Beeper session isn't available on the emulator, at minimum verify the migration path with a schema-copy and the local Synapse dev homeserver (`127.0.0.1:8008`) per the lightos-emulator skill.
- E2E on the local Synapse dev homeserver (127.0.0.1:8008) if Beeper creds aren't available: login, sync, send/receive, reactions/edits, media, verification, logout/login.

## Deliverables

- The upgrade implemented on the `trixnity-v5-upgrade` branch, commits scoped to upgrade files only (never the carryforward WIP files).
- `chats/PLAN.md` item 4 marked done with a phase summary; root `WORKLOG.md` entry (what changed, when, why).
- If you make map corrections (symbols that differed from the map), update `chats/TRIXNITY-V5-UPGRADE.md` and note the delta in your reply.
- End-of-session gates: `tools/check-agents-size` (AGENTS.md caps) and the `lightos-design` skill pass over touched screens.
- Reply with: branch name, build status, what was verified on the emulator (with the uiautomator/check-screenshot evidence), any flags (whoami device_id, migration test result, unread-badge UX confirmation), and what remains.
