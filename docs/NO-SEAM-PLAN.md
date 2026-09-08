# NO-SEAM PLAN — Chats: drop the binder seam, UI reflects the DB truth via flows

Status: **implemented 2026-09-07 — see WORKLOG top entry.** No SDK changes required.

## Handover prompt

> Task: implement this file — drop the chats binder seam so the UI reflects the Matrix
> DB truth via flows (single process already; the seam is the `LightServiceMethod`
> dispatch + revision-poll loops).
>
> Read, in order: this file (the full verified plan), then `WORKLOG.md` top entries for
> chats context. Do **not** modify `light-sdk/` — the plan requires no SDK changes (the
> plugin scan only bans framework imports in tool source, verified in
> `light-sdk/plugin/src/main/kotlin/com/thelightphone/plugin/LightSdkPlugin.kt`).
>
> Key facts already established (2026-09-07):
> - chats is single-APK/single-process since 2026-08-19 (`lighttool.toml`
>   `serverPackage = "com.lightphone.chats"`, no `android:process` anywhere).
> - `MatrixRepository` (chats/server) already holds reactive truth: `_roomList`
>   StateFlow (private), `connectionState`/`verification`/`restoreProgress` (public),
>   `messagePageCache` + `bumpMessagePageRevision` as the page-change choke point.
> - Sluggishness = `callRemoteServiceMethod` serialization + revision-poll loops
>   (`waitForRoomListChange`/`waitForPageChange`/`waitForFlagChange`) in
>   ChatListScreen, ThreadScreen, SearchScreen.
>
> Build with `tools/build --dir chats :app:assembleDebug`; never two builds
> concurrently. End-of-session gates: `lightos-design` skill on touched screens,
> `tools/check-agents-size`, WORKLOG.md entry.

## Finding (verified 2026-09-07)

The light-sdk plugin scan (`LightSdkPlugin.kt`) only bans framework imports/patterns in
tool-module source (`android.content.Context`, `Intent`, `LocalContext`, reflection, …).
It does **not** ban importing `:server` classes, and `:server` doesn't apply the plugin
at all (no scan there). Chats is already single-APK/single-process
(`implementation(project(":server"))` in `chats/app/build.gradle.kts`,
`serverPackage = "com.lightphone.chats"`). The Molly-Light-style model is allowed today —
no upstream change needed.

The sluggishness comes from the remaining **API seam**: `ChatClient` (app module) calls
everything through `callRemoteServiceMethod` (serialized `LightServiceMethod` binder
dispatch), and screens hold state via revision-poll loops (`waitForRoomListChange`,
`waitForPageChange`, `waitForFlagChange`) instead of collecting flows.

Meanwhile `MatrixRepository` already holds the truth reactively:

- `_roomList: MutableStateFlow<List<GetRooms.Room>>` (private; published by
  `publishRoomList`) — MatrixRepository.kt ~line 7399
- `connectionState`, `verification`, `restoreProgress` — already public `StateFlow`s
- `messagePageCache` + `bumpMessagePageRevision(roomId)` — single choke point where page
  content changes are signalled (MatrixRepository.kt ~line 7439)
- flags revision bump (locate beside the flag store / `WaitForFlagChange` handler)

## Approach

Expose the existing flows from `:server`, re-point `ChatClient` at direct calls, convert
the three polling screens to `collectAsState`. Screens keep their structure; the polling
loops die.

## Steps

### 1. `:server` — expose reactive truth (`MatrixRepository.kt`)

- Make the room list flow public: `val roomList: StateFlow<List<GetRooms.Room>>`
  (rename `_roomList` exposure; keep `publishRoomList` as the only writer).
- Add `publishedPages: StateFlow<Map<String, MessagesPage>>`, updated **inside
  `bumpMessagePageRevision(roomId)`** (it's already the single "page content changed"
  event — piggyback `messagePageCache[roomId]?.page` into the flow; zero changes to the
  ~8 cache write sites).
- Add `roomFlags: StateFlow<Map<String, GetRoomFlags.Response>>` updated beside the
  existing flags revision bump (locate the flag store first).
- Check every public fun the app will call for `Context`-typed parameters — app source
  can't import `android.content.Context`. If any signature forces it, wrap those calls
  in a small Context-free facade object in `:server` instead of changing callers.
  (Expect none: `MatrixRepository` is an object holding its own context.)

### 2. app — re-point `ChatClient.kt`

- Replace each `callRemoteServiceMethod(...)` body with a direct `MatrixRepository`
  call; public API stays identical so screens don't change.
- Delete `waitForRoomListChange` / `waitForPageChange` / `waitForFlagChange` /
  `roomListRevision` / `messagePageRevision` (screens stop using them in step 3).
- Keep: `startPhotoSend` / `startVoiceNoteSend` (activity launching must stay
  server-side via `startServerActivity`), notification-permission request path, and any
  method whose server handler needs the activity context.
- Keep `ChatServiceMethods.kt` + `LightSdkService` fully intact (vetted-tools contract,
  `MainActivity` adb dev control, emulator pipeline) — they just stop being the UI's
  hot path.

### 3. app — convert the polling screens to flow collection

- `ChatListScreen.kt`: ViewModel collects `MatrixRepository.roomList` +
  `connectionState` (+ account state); delete the `waitForRoomListChange` loop. Contact
  panel flags come from `roomFlags`; delete the per-row `waitForFlagChange` loop.
- `ThreadScreen.kt`: newest page comes from `publishedPages[roomId]`; delete the page
  revision loop and the quiet re-fetch loop. Pagination (older pages via
  `beforeEventId`) stays a one-shot `getMessages(...)` call.
- `SearchScreen.kt`: room list from the flow; delete its revision loop.
- One-shot reads that have no flow (e2ee state, verification actions, media, voice
  notes) become direct calls; no behavior change.

### 4. Verify

- `tools/build --dir chats :app:assembleDebug` (memory-guarded, no concurrent builds).
- Install on the `lightos` emulator; exercise: incoming message appears without poll
  delay, send echo/edit/react/unsend/mark-read/mute/pin/archived reflect immediately,
  room list + badges update, pagination still loads older pages, photo/voice-note
  activity launches still work, boot receiver + sync service unaffected.
- Battery sanity: polling timers gone — strictly less wakeups; quick logcat check.
- End-of-session gates: `lightos-design` skill pass on touched screens;
  `tools/check-agents-size`; WORKLOG.md entry (rationale + Molly-Light/scan finding).

## Risks

- `bumpMessagePageRevision` also fires for outbox/pending-echo state changes — verify
  the published page object is the served one (same source as `getMessages` returns) so
  UI never shows a different page shape than today.
- The service-method handlers stay compiled in; if any screen subtly still depends on
  binder-side state initialization order, `ServerBootstrapProvider` already covers
  process-start init.
- If a needed `MatrixRepository` signature turns out Context-typed, the facade object in
  step 1 is the fallback — plan does not require touching the SDK.
