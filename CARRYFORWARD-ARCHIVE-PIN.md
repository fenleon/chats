# Chats carry-forward build spec (2026-08-28): archive hiding + search VIEW ALL/VIEW DIRECT + PIN (synced)

**Status: spec only — NOT implemented.** Build from this in a fresh session. Source design: `chats/PLAN.md` §452; every anchor + API verified against the codebase and the Trixnity 4.22.7 jars on 2026-08-28 (the §452 line refs all still hold; the API-package guesses were corrected below).

Three features on `chats/`, all driven by **synced Matrix/Beeper state** (no new local stores), plus one new user rule:

1. **Archive** — hide Beeper-archived rooms from the main room list; findable only via search; fully silent (no notifications).
2. **Search** — results toggle **VIEW ALL** (archived + groups included) ↔ **VIEW DIRECT** (direct, non-archived). Default = VIEW DIRECT.
3. **PIN** — PIN/UNPIN button on the contact page (above MUTE); pinned chats at the top of the room list, recency-ordered among themselves; **MUTE also becomes synced** (Matrix push rules).
4. **NEW (user, 2026-08-28):** pinned rooms show **no latest timestamp** in the main room list.

User decisions: **pinned wins over archive** (pinned+archived stays visible at top); search **defaults to VIEW DIRECT**; archived rooms **do not notify**; pinned rows drop the timestamp.

## Verified API surface (javap, Trixnity 4.22.7 — trust, don't re-verify)

Corrections vs PLAN.md §462:
- `TagEventContent` / `PushRulesEventContent` → `net.folivo.trixnity.core.model.events.m`
- `PushRuleKind` / `PushAction` → `net.folivo.trixnity.core.model.push`
- `SetPushRule` (+`Request`) → `net.folivo.trixnity.clientserverapi.model.push`
- `RoomService` (with the `getAccountData` store read) → `trixnity-client-jvm` jar (`net.folivo.trixnity.client.room.RoomService`); `trixnity-client-repository-room-jvm` is only the Room-DB impl
- `UnknownEventContent` implements `RoomAccountDataEventContent` ✓ (the archive marker reads through the same API as tags)
- **Archive marker corrected on-device (2026-08-28 LP3+Beeper experiment):** Beeper's real archive state is `com.beeper.inbox.done`, whose content carries `at_ts`/`at_order`/`updated_ts` when archived and is reset to `{}` on unarchive (the row is never deleted). `com.beeper.chats.auto_archive` (the marker rounds 1-3 used) is a write-only orphan Beeper's app ignores — archiving a fresh room wrote `inbox.done` and NO `auto_archive`; unarchiving reset `inbox.done` to `{}` and left `auto_archive` behind; the DELETE route 405s on Beeper's server, so unarchive must PUT `{}`.

| State | Read (store) | Write (API) |
|---|---|---|
| Pinned | `c.room.getAccountData(roomId, TagEventContent::class, "").first()` → `tags.containsKey("m.favourite")` | `c.api.room.setTag(c.userId, RoomId(roomId), "m.favourite", TagEventContent.Tag(order = 1.0))` / `c.api.room.deleteTag(c.userId, RoomId(roomId), "m.favourite")` |
| Archived | `c.room.getAccountData(roomId, BeeperInboxDoneContent::class, "").firstOrNull()` → content `atTs != null` (row presence alone is NOT the flag — Beeper resets content to `{}` on unarchive) | `PUT /user/{userId}/rooms/{roomId}/account_data/com.beeper.inbox.done` body `{"at_order":now,"at_ts":now,"updated_ts":now}` to archive, body `{}` to unarchive (never DELETE — 405 on Beeper's server) |
| Muted | `c.di.get<GlobalAccountDataStore>(...).get(PushRulesEventContent::class).first()?.content?.global?.room` → rule with `ruleId == roomId && actions.contains(PushAction.DontNotify)` (store access pattern at MatrixRepository.kt:971) | `c.api.pushRule.setPushRule("global", PushRuleKind.ROOM, roomId, SetPushRule.Request(actions = setOf(PushAction.DontNotify)))` / `c.api.pushRule.deletePushRule("global", PushRuleKind.ROOM, roomId)` |

Note: `SetPushRule.Request` has NO `enabled` field — mute = dont_notify rule present, unmute = rule deleted (matches Beeper's own representation from the LP3 device findings).

## Changes

### 1. SDK shared + server routing (documented patch — `light-sdk`)

`light-sdk/sdk/shared/src/main/kotlin/com/thelightphone/sdk/shared/LightServiceMethod.kt`:

- `GetRooms.Room` (:548, `muted` at :585) — add two defaulted fields:
```kotlin
/** The user archived this room on Beeper: hidden from the main room list,
 *  silent, reachable only via search VIEW ALL. */
val archived: Boolean = false,
/** The user pinned this room (m.favourite tag): sorted to the top of the
 *  room list; its row shows no latest timestamp. */
val pinned: Boolean = false,
```
- New method (mirror `SetRoomMuted` :753-760):
```kotlin
/** Pins or unpins a room (m.favourite tag, synced, chats 2026-08-28). */
object SetRoomPinned : LightServiceMethod<SetRoomPinned.Request, Unit> {
    override val id = "SetRoomPinned"
    override val requestSerializer = serializer<Request>()
    override val responseSerializer = serializer<Unit>()

    @Serializable
    data class Request(val roomId: String, val pinned: Boolean)
}
```
- Registry list (:1140-1146): add `LightServiceMethod.SetRoomPinned` next to `SetRoomMuted`.

`light-sdk/sdk/server/src/main/kotlin/com/thelightphone/sdk/server/LightSdkService.kt`:
- Add `LightServiceMethod.SetRoomPinned,` to the custom-resolver dispatch list next to `SetRoomMuted` (:296).

`LIGHT-SDK-PATCHES.md`: entry mirroring the SetRoomMuted shape (~:116) — the two Room fields + SetRoomPinned + routing.

### 2. Server — `chats/server/src/main/kotlin/com/lightphone/chats/server/MatrixRepository.kt` (5829 lines)

- **Delete `MuteStore.kt`**; remove `MuteStore.init(app)` (:416) and the `MuteStore` import/uses. Mute truth moves to Matrix push rules.
- **Flags cache** (mirror `networkByRoomCache`, :4782-4854):
```kotlin
data class RoomFlags(
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
)

@Volatile private var roomFlagsCache: Map<String, RoomFlags> = emptyMap()
@Volatile private var roomFlagsBuiltAtMs = 0L
private val ROOM_FLAGS_TTL_MS = NETWORK_MAP_TTL_MS // reuse the same TTL

/** Pinned/archived/muted per room, from synced state (m.favourite tag,
 *  Beeper inbox.done account data, global push rules). TTL + budget
 *  mirrors [networkByRoom]. */
private suspend fun roomFlagsByRoom(
    c: MatrixClient,
    rooms: Map<RoomId, Flow<MatrixRoom?>>,
): Map<String, RoomFlags> {
    val now = android.os.SystemClock.elapsedRealtime()
    if (roomFlagsCache.isNotEmpty() && now - roomFlagsBuiltAtMs < ROOM_FLAGS_TTL_MS) {
        return roomFlagsCache
    }
    val result = mutableMapOf<String, RoomFlags>()
    withTimeoutOrNull(NETWORK_MAP_BUDGET_MS) {
        val pushRules = try {
            c.di.get<GlobalAccountDataStore>(GlobalAccountDataStore::class)
                .get(PushRulesEventContent::class).first()?.content?.global?.room.orEmpty()
        } catch (_: Exception) { emptyList() }
        for ((roomId, _) in rooms) {
            val key = roomId.full
            val tags = c.room.getAccountData(roomId, TagEventContent::class, "").first()
            val inboxDone = c.room.getAccountData(roomId, BeeperInboxDoneContent::class, "").firstOrNull()
            val archived = inboxDone?.atTs != null
            result[key] = RoomFlags(
                pinned = tags?.tags?.containsKey("m.favourite") == true,
                archived = archived,
                muted = pushRules.any { it.ruleId == key && it.actions.contains(PushAction.DontNotify) },
            )
        }
    }
    if (result.isNotEmpty()) {
        roomFlagsCache = result
        roomFlagsBuiltAtMs = now
    }
    return roomFlagsCache
}
```
  Add `private const val BEEPER_INBOX_DONE_EVENT_TYPE = "com.beeper.inbox.done"` next to the existing `BEEPER_SEND_STATUS_EVENT_TYPE` constant, plus the `BeeperInboxDoneContent` data class + lenient serializer (see MatrixRepository.kt); imports: `PushRulesEventContent`, `PushAction`, `PushRuleKind` (unused here), `TagEventContent`, `BeeperInboxDoneContent`, `SetPushRule`. The stale-store phantom fix: an archived store claim is confirmed with a GET (`isRoomArchivedOnServer`: 200 with `at_ts` → archived; 200 `{}` or 404 → not; else keep the claim).
- **Wire into the resolver pass** (:4496-4507): `val flags = roomFlagsByRoom(c, rooms)` next to `networkByRoom`; pass `flags` into `seedRoomList` (:4588) and `resolveRoomListEntry` (:4644); set at both Room-construction sites (:4625 seed, :4774 resolve):
```kotlin
archived = flags[key]?.archived ?: false,
pinned = flags[key]?.pinned ?: false,
muted = flags[key]?.muted ?: false,
```
  (replaces the `muted = MuteStore.isMuted(key)` lines).
- **`setRoomPinned` / `setRoomMuted`** (both become `suspend`, optimistic cache + dirty + wake; replace `setRoomMuted` :4044-4047):
```kotlin
suspend fun setRoomPinned(roomId: String, pinned: Boolean) {
    val c = client ?: return
    if (pinned) {
        c.api.room.setTag(c.userId, RoomId(roomId), "m.favourite", TagEventContent.Tag(order = 1.0))
    } else {
        c.api.room.deleteTag(c.userId, RoomId(roomId), "m.favourite")
    }
    roomFlagsCache = roomFlagsCache + (roomId to (roomFlagsCache[roomId] ?: RoomFlags()).copy(pinned = pinned))
    roomListDirty = true
    wakeRoomList()
}

suspend fun setRoomMuted(roomId: String, muted: Boolean) {
    val c = client ?: return
    if (muted) {
        c.api.pushRule.setPushRule("global", PushRuleKind.ROOM, roomId,
            SetPushRule.Request(actions = setOf(PushAction.DontNotify)))
    } else {
        c.api.pushRule.deletePushRule("global", PushRuleKind.ROOM, roomId)
    }
    roomFlagsCache = roomFlagsCache + (roomId to (roomFlagsCache[roomId] ?: RoomFlags()).copy(muted = muted))
    roomListDirty = true
    wakeRoomList()
}
```
  (Keep the existing `android.util.Log.d(TAG, ...)` lines if you like; `getOrThrow` on the API call is fine — the tool shows the optimistic state anyway.)
- **`notifyForEvent`** (:4186; gate at :4203-4205): replace the `MuteStore.isMuted(roomId.full)` check:
```kotlin
val flags = roomFlagsCache[roomId.full]
if (flags?.muted == true || flags?.archived == true) {
    android.util.Log.d(TAG, "notifyForEvent: skipping ${if (flags.archived) "archived" else "muted"} room $roomId")
    return
}
```
- **`ChatServiceMethods.kt`** (:103-107): add the `SetRoomPinned` case; both mute/pin handlers wrap in `runBlocking` (methods are now suspend) — mirror the `SetTyping` case (:97-101):
```kotlin
LightServiceMethod.SetRoomPinned.id -> {
    val request = LightServiceMethod.SetRoomPinned.decodeRequest(payload!!)
    runBlocking { MatrixRepository.setRoomPinned(request.roomId, request.pinned) }
    LightResult.Success(LightServiceMethod.SetRoomPinned.encodeResponse(Unit))
}
```

### 3. Tool UI — `chats/app/src/main/kotlin/com/lightphone/chats/`

- `ChatClient.kt` (:114-119): mirror `setRoomMuted`:
```kotlin
/** Pins/unpins [roomId] (m.favourite tag, synced; contact panel, 2026-08-28). */
suspend fun setRoomPinned(roomId: String, pinned: Boolean) {
    callRemoteServiceMethod(
        LightServiceMethod.SetRoomPinned,
        LightServiceMethod.SetRoomPinned.Request(roomId, pinned),
    )
}
```
- `screens/ChatListScreen.kt`:
  - `filteredRooms` (:356-360): archive drop + pinned-first stable sort:
```kotlin
val filteredRooms = remember(rooms, networkFilter) {
    rooms.filter { room ->
        (networkFilter == null || room.network == networkFilter) &&
            !(room.archived && !room.pinned) // archived hidden unless pinned wins
    }.sortedByDescending { it.pinned } // stable — server recency holds within groups
}
```
  - `RoomRow` (:629-632): pinned rows show no timestamp:
```kotlin
// Pinned rows drop the latest-message time (user, 2026-08-28).
if (!room.pinned) {
    LightText(
        text = formatRelativeTimestamp(room.lastTimestampMs),
        variant = LightTextVariant.Fine,
    )
}
```
- `screens/SearchScreen.kt`:
  - `groupOnly` (:71) → `val dmsOnly = MutableStateFlow(true)` (default VIEW DIRECT).
  - `matchingRooms` (:94-103) filter line (:99):
```kotlin
(!dmsOnly.value || (room.isDirect && !room.archived)) &&
```
  - `Content` (:120, :130-134): collect/toggle `dmsOnly`; `ResultsView(dmsOnly = dmsOnly, onToggleDmsOnly = { viewModel.dmsOnly.value = !viewModel.dmsOnly.value }, ...)`.
  - `ResultsView` (:206-211, :244): param + label:
```kotlin
text = if (dmsOnly) "VIEW ALL" else "VIEW DIRECT",
```
- `screens/ContactScreen.kt` (:55-63, :117-136): add `pinned: Boolean = false` + `onTogglePin: () -> Unit = {}` params; `var isPinned by remember { mutableStateOf(pinned) }`; PIN/UNPIN button **above** MUTE, same treatment (`LightTextVariant.Button`, `padding(top = 4f.gridUnitsAsDp())`, `lightClickable` toggling `isPinned` + `onTogglePin()`), `text = if (isPinned) "UNPIN" else "PIN"`.
- `screens/ThreadScreen.kt`: mirror the muted block (:187-194):
```kotlin
val pinned = MutableStateFlow(room.pinned)

fun togglePinned() {
    val next = !pinned.value
    pinned.value = next
    viewModelScope.launch { ChatClient.setRoomPinned(room.id, next) }
}
```
  and pass into `ContactScreen` (:1025-1035): `pinned = viewModel.pinned.value, onTogglePin = viewModel::togglePinned`.

## Verification

1. `tools/build --dir chats :app:assembleDebug` (chats build includes the light-sdk included build → the SDK patch compiles too).
2. **Emulator (dev Synapse account)** — all three read paths with real Matrix state: log in, create a room, then curl:
   - archive: `PUT /_matrix/client/v3/user/{userId}/rooms/{id}/account_data/com.beeper.inbox.done` body `{"at_ts":<now_ms>}`; unarchive: same PUT with body `{}`
   - pin: `PUT /_matrix/client/v3/rooms/{id}/account_data/m.tag` body `{"tags":{"m.favourite":{"order":1}}}`
   - mute: `PUT /_matrix/client/v3/pushrules/global/room/{url-encoded-id}` (roomId URL-encoded: `!`→`%21`, `:`→`%3A`) body `{"actions":["dont_notify"]}`
   - Check: archived hidden from list but found in search VIEW ALL (not VIEW DIRECT); pinned on top + **no timestamp in its row**; pinned+archived still visible at top; PIN round-trip writes m.tag (curl GET); MUTE writes/deletes the push rule; archived room stays silent (no notify).
3. **Real LP3 (user-driven, control needs permission)**: the 4 rooms with stale `auto_archive` orphans (Sophie/Anni/1€ FILM/Tiki) reappear in the list (LP3 now ignores that marker); a room archived in the Beeper app hides; unarchive in Beeper unhides; PIN/UNPIN + mute round-trip against Beeper (contact panel reflects Beeper-side toggles within seconds).
4. `lightos-design` skill over the touched screens; update `WORKLOG.md` (session log + the archive-marker finding); `tools/check-agents-size`.

## Out of scope / notes

- Archived rooms fully silent on the LP3: hidden from list, no notifications, reachable only via search VIEW ALL (a new message still bumps the row there). No un-archive-on-message.
- Old-schema on-disk room-list cache decodes fine (new fields default).
- Flags cache has the same freshness envelope as `networkByRoomCache` (TTL; our own PIN/MUTE toggles mutate it optimistically, external Beeper changes land on the next cache rebuild; pre-first-resolve notifyForEvent cache miss = notify, same as today).
- Binder cap (~1600 rooms) > account (~1284) — no server-side re-sort needed.
- Pinned ordering in search stays alphabetical.
