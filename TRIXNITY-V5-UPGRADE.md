# Trixnity 4.22.7 → 5.8.0 upgrade map — chats

Date: 2026-08-28 · Research session (read-only; no code written) · Source of truth for the v5 implementation branch.

**How verified:** every symbol below was checked against the v5.8.0 `-sources` jars (extracted at `/tmp/trixnity-v5/jars/v5-*-jvm-src/`, from Maven Central) and `javap` on the 5.8.0 `-jvm` jars, with the 4.22.7 sources/jars alongside for diffing. No gradle build, no emulator, no repo modifications.

**The one mechanical rule covering ~80% of the diff:** `net.folivo.trixnity.*` → `de.connect2x.trixnity.*` everywhere; a few packages renamed beyond the group (flagged below).

**Chats' trixnity surface lives in 4 files:** `server/src/main/kotlin/com/lightphone/chats/server/MatrixRepository.kt` (6.4k lines), `ChatSyncService.kt`, `PushChannel.kt`, `PlaintextVerificationOlmEncryptionService.kt`. Deps pinned in `chats/gradle/libs.versions.toml` (3 artifacts: `trixnity-client`, `trixnity-client-repository-room`, `trixnity-client-media-okio`; olm came transitively via `net.folivo:trixnity-olm-android`).

---

## 1. Per-symbol breaking-change table

### A. Client creation & lifecycle

| Symbol | chats call site | v5 status | v5 replacement | Effort |
|---|---|---|---|---|
| `MatrixClient.login(baseUrl, identifier, password, token, loginType, initialDeviceDisplayName, repositoriesModule, mediaStoreModule, configuration)` | MatrixRepository.kt:814-824, :911-921 | **removed** | Two-step: `MatrixClientAuthProviderData.classicLogin(baseUrl, identifier=, password=, token=, loginType=, initialDeviceDisplayName=, ...): Result<ClassicMatrixClientAuthProviderData>` (`ClassicMatrixClientAuthProvider.kt:118-151`) → `MatrixClient.create(..., authProviderData = it)`. Beeper JWT login keeps `loginType = LoginType.Unknown("org.matrix.login.jwt", buildJsonObject {})` + `token` | Medium |
| `MatrixClient.fromStore(repositoriesModule, mediaStoreModule, configuration)` | :1354-1358 | **removed** | `MatrixClient.create(repositoriesModule, mediaStoreModule, cryptoDriverModule, authProviderData = null)` — restores the session from the store (built-in credential migration, see §2) | Small |
| `MatrixClient.create(...)` | — | **new, only entry point** | `suspend fun MatrixClient.Companion.create(repositoriesModule: RepositoriesModule, mediaStoreModule: MediaStoreModule, cryptoDriverModule: CryptoDriverModule, authProviderData: MatrixClientAuthProviderData? = null, coroutineContext = Dispatchers.Default, configuration: MatrixClientConfiguration.() -> Unit = {}): Result<MatrixClient>` (`MatrixClient.kt:176-183`). `cryptoDriverModule` is mandatory (required even with no E2EE rooms — `create()` resolves `CryptoDriver`/`OlmCryptoStore` for the Olm account + device-key upload on init) | Medium |
| `createRoomRepositoriesModule(dbBuilder)` | :821, :918, :1355 | **renamed** | `RepositoriesModule.room(databaseBuilder: RoomDatabase.Builder<TrixnityRoomDatabase>)` (`RoomRepositoriesModule.kt:46`) — same parameter type | Small |
| `createOkioMediaStoreModule(mediaDir)` | :822, :919, :1356 | **renamed** | `MediaStoreModule.okio(basePath: Path, fileSystem = ..., coroutineContext = ...)` (`OkioMediaStore.kt:187-204`) | Small |
| `c.startSync(Presence.OFFLINE)` | :782 | kept | `startSync(presence: Presence? = ONLINE)` — same | Trivial |
| `c.stopSync()` / `c.logout()` / `c.closeSuspending()` | :512,:603,:802,:881 / :1332 / :1333 | kept | identical (a non-suspend `close()` was added via `AutoCloseable`; `logout(): Result<Unit>`) | Trivial |
| `c.syncOnce(Presence.OFFLINE).getOrThrow()` | :624 | kept | `syncOnce(presence: Presence?, timeout = Duration.ZERO): Result<Unit>` — single-arg call still valid | Trivial |
| `c.syncState` / `SyncState.{INITIAL_SYNC,STARTED,RUNNING,ERROR,TIMEOUT,STOPPED}` | :5934-5938, ChatSyncService.kt:96 | kept | `val syncState: StateFlow<SyncState>` — same enum, same package | Trivial |
| `c.loginState.value` / `MatrixClient.LoginState.LOGGED_IN` | :5949, :5973-5974 | kept | `StateFlow<LoginState?>`; enum gained `LOCKED`/`LOGGED_OUT_SOFT`, `LOGGED_IN` semantics unchanged | Trivial |
| `String.serverDiscovery(httpClientEngine)` | :812 | kept | same signature (`serverDiscovery.kt:14-24`) | Trivial |
| `LoginType.Password/Token()/Unknown(...)`, `IdentifierType.User`, `Presence.OFFLINE` | :816-916, ChatSyncService.kt:59 | kept | all identical (`model/authentication/`, `core/model/events/m/Presence.kt`) | Trivial |
| `c.key` / `c.room` / `c.user` / `c.verification` extension accessors | imports :69-96 | kept | `val MatrixClient.key get() = di.get<KeyService>()` etc. (`defaultModules.kt:284-297`) | Trivial |

### B. Configuration & filters (the PLAN item-1 work)

| Symbol | chats call site | v5 status | v5 replacement | Effort |
|---|---|---|---|---|
| `MatrixClientConfiguration.syncFilter` / `syncOnceFilter` | :5877-5884 | **kept, same types** | `var syncFilter: Filters`, `var syncOnceFilter: Filters` (`MatrixClientConfiguration.kt:67,70`) — item-1 work survives unchanged | Trivial |
| `MatrixClientConfiguration.lastRelevantEventFilter` | :5891-5896 | **kept, same type** | `var lastRelevantEventFilter: (RoomEvent<*>) -> Boolean` (`:64`) — chats' Boolean-returning lambda compiles as-is | Trivial |
| `Filters` / `EventFilter` / `RoomFilter` / `RoomEventFilter` | :5877-5884 (import :106) | **kept, package renamed** | Identical ctor params (`limit: Long?`, `notTypes: Set<String>?`, `types`, ...) but package `…clientserverapi.model.users` → `…clientserverapi.model.user` (**singular** — import change only) | Trivial |
| `createTrixnityDefaultModuleFactories()` + `modulesFactories` | :5869 | kept | same name/signature (`defaultModules.kt:108`); `typealias ModuleFactory = () -> Module`; the `+ ::plaintextVerificationModule + ::archiveMappingsModule` append still compiles | Trivial |
| `MatrixClientConfiguration.name` / `httpClientEngine` | :5867-5868 | kept | identical | Trivial |
| (new) `enableExternalNotifications` | — | new, default `false` | `MatrixClientConfiguration.kt:52` — the v5 NotificationService's external-update stream is empty unless set; leave unset for the minimal notification path (§3) | — |

### C. Room API & server endpoints

| Symbol | chats call site | v5 status | v5 replacement | Effort |
|---|---|---|---|---|
| `c.room.getById` / `getAll()` / `getLastTimelineEvents` / `getLastTimelineEvent` / `getTimelineEvents` / `getTimelineEvent` | :1693, :2311, :2922, :2166, ... | kept | `RoomService.kt:244,242,154,126,140,110` — no signature changes | Trivial |
| `c.room.sendMessage(roomId){ text/reply/image/content }` / `retrySendMessage` / `getOutbox` | :3241, :3336, :4087, :3290, :2337 | kept | `MessageBuilder` DSL + methods identical (`MessageBuilder.kt`, `text.kt`, `reply.kt`, `image.kt`) | Trivial |
| `c.room.getAccountData(roomId, KClass, key)` / `getAllState` | :4562, :4572, :5256 | kept | `RoomService.kt:249,267` — same | Trivial |
| `TimelineEventHandler` (DI) + `unsafeFillTimelineGaps(EventId, RoomId, limit)` | :1525-1526 | kept | `room/TimelineEventHandler.kt:39-41`, same DI registration | Trivial |
| `GetTimelineEventConfig` / `GetTimelineEventsConfig` (maxSize, fetchTimeout, decryptionTimeout) | :2074-2078, :2159-2162, :2806-2810 | kept | identical (`GetTimelineEventsConfig.kt:8-53`) | Trivial |
| `c.api.room.setReadMarkers(roomId, fullyRead, read)` | :4241-4245 | kept | gains defaulted `privateRead: EventId? = null` — no call-site change | Trivial |
| `c.api.room.setTyping` | :4267-4272 | kept | same | Trivial |
| `c.api.room.setTag(userId, roomId, "m.favourite", Tag)` / `deleteTag` | :4284, :4286 | kept | `RoomApiClient.kt:336,339` | Trivial |
| `c.api.push.setPushRule` / `deletePushRule` / `setPushers` | :4302-4307, PushChannel.kt:158,203 | kept | `PushApiClient.kt:29-39`; `SetPushRule.Request` unchanged; `SetPushers` became `data object` + nested `Request.Serializer` — `Request.Set`/`Request.Remove` field-identical, compiles | Small |
| `PusherData(format="event_id_only", url, customFields)` / `PushRuleKind.ROOM` / `PushAction.Unknown` | PushChannel.kt:187-202, :4303-4304 | kept | identical | Trivial |
| `c.api.baseClient.baseClient.put/get` (raw Beeper archive PUT/GET) | :4340, :4371 | kept | `MatrixClientServerApiClient.baseClient: MatrixClientServerApiBaseClient` → superclass `MatrixApiClient`; `baseClient` still the ktor `HttpClient` — **exact `MatrixApiClient` member signature unverified** (v5 `trixnity-api-client` jar not inspected); confirm at build | Small |

### D. MatrixRoom fields

| Symbol | chats call site | v5 status | v5 replacement | Effort |
|---|---|---|---|---|
| `lastRelevantEventId` / `lastRelevantEventTimestamp` | :4525-4526, :4900, :5028-5109 | kept | `store/Room.kt:16-17` (still `Instant`) — the watcher's baseline-diff signal survives | Trivial |
| `membership` / `encrypted` / `isDirect` / `lastEventId` / `name` (`explicitName`, `heroes`) / `createEventContent` / `joinedMemberCount` ext | :4528-5040, :5245-5253, ... | kept | all present (`Room.kt:11-24,34-35`) | Trivial |
| **`unreadMessageCount` (and v4's `isUnread`)** | **:4527, :4677, :5030, :5102** | **REMOVED (5.0.0)** | `c.notification.getCount(roomId): Flow<Int>` / `c.notification.isUnread(roomId): Flow<Boolean>` (`notification/NotificationService.kt:94,101`; accessor `defaultModules.kt:299-300`). **Semantics differ** — `isUnread` is Boolean ("last read event before lastRelevantEventId or MarkedUnread"), `getCount` counts notifications, not unread timeline events. No 1:1 for the old Long message count | **Medium–High** |

### E. Store, serialization, repositories

| Symbol | chats call site | v5 status | v5 replacement | Effort |
|---|---|---|---|---|
| `TrixnityRoomDatabase` (DI + `Room.databaseBuilder`) | :2214, :5913-5915, `deleteDatabase` :1335 | kept, **version 4 → 9** | see §2 Store-migration verdict | (below) |
| `c.di.get<Json>()` + `json.decodeFromString<TimelineEvent>` | :2218, :2245, :4034 | kept | `createMatrixEventJson`/DI Json with the store's serializers unchanged (`defaultModules.kt:81-98`) — raw-SQL decode path still works | Trivial |
| `TimelineEvent` fields: `event`, `previousEventId`, `nextEventId`, `gap`, `content` | :1771, :1997-2029, :2130, :2250, ... | kept | `store/TimelineEvent.kt` — fields identical; `gap` still `@JsonClassDiscriminator("position")`; serializer moved to nested `TimelineEvent.Serializer` (same 2-ctor shape). Stored JSON shape **byte-identical** → `json_extract(value,'$.previousEventId')` walk unaffected | Trivial |
| `UnsignedRoomEventData.UnsignedStateEventData.previousContent` | :5506-5507 | kept | same | Trivial |
| `GlobalAccountDataStore.get(type, key)` | :997-999, :4498, :5356 | kept | `store/GlobalAccountDataStore.kt:59-72` | Trivial |
| `OlmCryptoStore.updateOutboundMegolmSession(roomId) { null }` | :3212 | kept | `OlmCryptoStore.kt:167` (updater no longer suspend — compatible) | Trivial |
| `RoomStateRepository` + `RoomStateRepositoryKey` | :2858, :2862 | kept | unchanged | Trivial |
| `RoomUserReceiptsRepository` + `receipts[ReceiptType.Read]?.eventId` | :2883, :2896 | kept | unchanged (`ReceiptType.Read` same; serializer renamed `ReceiptType.Serializer`) | Trivial |
| `RepositoryTransactionManager.readTransaction { }` | :2881, :5463 | **renamed** | `StoreTransactionManager` (`client/store/StoreTransactionManager.kt:14-29`; Room impl `RoomStoreTransactionManager`) — block signature `suspend StoreReadTransaction.() -> T` unchanged, compiles as-is | Small |
| `DefaultEventContentSerializerMappings` | :2695 | **renamed** | `EventContentSerializerMappings.default` (`EventContentSerializerMappingsDefault.kt:162`) | Small |
| `createEventContentSerializerMappings { roomAccountDataOf(type, serializer) }` | :2695-2697 | **renamed** | `EventContentSerializerMappings { ... }` (companion invoke); `roomAccountDataOf` kept (`EventContentSerializerMappingsBuilder.kt:106-111`) | Small |
| (new) `RepositoryMigration` | — | new | v5 runs `di.getAll<RepositoryMigration>().forEach { it.run() }` before store init (`MatrixClient.kt:444-449`); `CryptoDriverModule.libOlm()` registers none — **chats needs no custom migration** | — |

### F. Event content models

| Symbol | chats call site | v5 status | v5 replacement | Effort |
|---|---|---|---|---|
| `RoomMessageEventContent` (Unknown/TextBased/FileBased.Image/Audio/Video/File; `body`, `url`, `file`, `fileName`, `info`, `FileBased.Audio.TYPE`) | :4081-4091, :2008-2016, :6062-6161 | kept | unchanged | Trivial |
| `EncryptedMessageEventContent.MegolmEncryptedMessageEventContent.sessionId` | :1287, :2995 | kept | unchanged | Trivial |
| `ReactionEventContent` + `RelatesTo.Annotation` (`.eventId`, `.key`) / `RelatesTo.Replace` (`.eventId`, `.newContent`) | :1795-1812, :2494-2497, :2815-2819 | kept | unchanged (`RelatesTo.kt:30-36,60-62`) | Trivial |
| `MessageEventContent` (PV override + isReplaceEdit) | :5893, PlaintextVerificationOlmEncryptionService.kt:60 | kept | unchanged | Trivial |
| `UnknownEventContent.raw/.eventType/.relatesTo` | :2761-2783, :3187-3191 | **ctor changed** | now `UnknownEventContent(raw: JsonObject, blocks: EventContentBlocks, eventType: String)` (`EventContent.kt:70-74`) — chats only **reads** fields, never constructs → compiles | Small |
| `MemberEventContent.displayName` / `CreateEventContent.RoomType.Space` / `ChildEventContent` / `TagEventContent.Tag(order=1.0)`+`TagName.Favourite` / `PushRulesEventContent.global.room` / `DefaultSecretKeyEventContent.content.key` / `SecretKeyEventContent.content` / `EncryptionEventContent` | :5245-5508, :997-999, :5356 | kept | all unchanged | Trivial |
| `DecryptedMegolmEvent<*>` | PV:67 | kept | unchanged | Trivial |
| `DecryptedOlmEvent<*>` | PV:57 | **deprecated typealias** | `@Deprecated typealias DecryptedOlmEvent<C> = PlaintextOlmEvent<C>` (`core/model/events/PlaintextOlmEvent.kt:10`) — compiles with a warning; new name has extra optional `senderDeviceKeys` | Trivial |

### G. Crypto

| Symbol | chats call site | v5 status | v5 replacement | Effort |
|---|---|---|---|---|
| `OlmEncryptionService.encryptOlm(content, userId, deviceId, forceNewSession)` | PV:36-53 (delegate) | **changed** | 3-arg `encryptOlm(content, recipientUserId, recipientDeviceId)` — `forceNewSession` dropped (`crypto/olm/OlmEncryptionService.kt:85-89`) | Medium |
| `OlmEncryptionService` batch fan-out | (verification steps flow through it in v5) | **new member** | `encryptOlm(content, recipients: Set<Pair<UserId, String>>): Map<Pair<UserId,String>, Result<...>>` (`:97-100`) — **the plaintext override must force plaintext here too** or SAS steps encrypt | Medium |
| `OlmEncryptionService.decryptOlm(...): Result<DecryptedOlmEvent<*>>` | PV:55-57 | **return type changed** | `Result<PlaintextOlmEvent<*>>` (`:155-157`) | Medium |
| `OlmEncryptionService.encryptMegolm` / `decryptMegolm` | PV:59-67 | **moved to new interface** | `de.connect2x.trixnity.crypto.olm.MegolmEncryptionService` (`MegolmEncryptionService.kt:51-95`) — same signatures; `PlaintextVerificationOlmEncryptionService` drops both overrides | Medium |
| `OlmEncryptionServiceImpl` ctor | :5907 (`get<OlmEncryptionServiceImpl>()`) | **ctor grew** | now `(userInfo, json, store, requests, signService, clock, driver: CryptoDriver)` — chats' Koin wiring must supply the `driver` binding (comes from `CryptoDriverModule.libOlm()`) | Medium |
| Plaintext-override pattern (`single<OlmEncryptionService> { PlaintextVerificationOlmEncryptionService(get<OlmEncryptionServiceImpl>()) }`) | :5905-5911 | **still supported** | `createCryptoModule()` (`client/cryptodriver/createCryptoModule.kt:25-52`) registers `singleOf(::OlmEncryptionServiceImpl) { bind<OlmEncryptionService>() }` — the appended-module override seam survives | Medium |
| `net.folivo:trixnity-olm-android` (transitive) | — | **gone** | `de.connect2x.trixnity:trixnity-client-cryptodriver-libolm` → `CryptoDriverModule.libOlm()` (`LibOlmCryptoDriverModule.kt:8-9`, pure-Kotlin, **same pickle format as v4**) — pulls `trixnity-libolm-android` AAR transitively | Medium |
| `KeyBackupService.version` / `loadMegolmSession(roomId, sessionId)` + Koin `named` qualifier | :2294-2299, :1265, :3017 | kept | `client/key/KeyBackupService.kt:72,74`; `named<KeyBackupService>()` survives (`createKeyModule.kt:47-50`) | Trivial |
| `decodeRecoveryKey` / `KeySecretService.decryptOrCreateMissingSecrets` / `KeyTrustService.checkOwnAdvertisedMasterKeyAndVerifySelf` | :995-1004 | kept | same APIs (`crypto/key/RecoveryKeyUtils.kt:22`, `KeySecretService.kt:28`, `KeyTrustService.kt:50-54`) — package rename only | Trivial |
| SAS/verification: `ActiveDeviceVerification`, `ActiveVerificationState.{TheirRequest,Ready,OwnRequest,Start,Done}`, `ActiveSasVerificationState.{TheirSasStart,ComparisonByUser,OwnSasStart}`, `VerificationMethod.Sas`, `accept()/ready()/start()/match()/noMatch()/cancel()` | :1050-1234 | kept | `client/verification/...` — same names; `SelfVerificationMethods.CrossSigningEnabled`, `AesHmacSha2RecoveryKey` kept (+ new `...WithPbkdf2Passphrase` sibling) | Trivial |
| `DeviceTrustLevel.CrossSigned` + `c.key.getTrustLevel(userId, deviceId)` | :2967-2969 | kept | `crypto/key/DeviceTrustLevel.kt:9` — package rename only | Trivial |

### H. Media

| Symbol | chats call site | v5 status | v5 replacement | Effort |
|---|---|---|---|---|
| `MediaService.getMedia(url, saveToCache)` | :3483, :3851, :4181 | **maxSize now required** (5.7.x removed the default) | `getMedia(uri, maxSize: Long?, expectedSize: Long? = null, progress, saveToCache: Boolean = true)` → `getMedia(url, maxSize = null, saveToCache = false)` | Low |
| `MediaService.getEncryptedMedia(file, saveToCache)` | :3482, :3850, :4180 | same shape | add `maxSize = null` (5.7.0 deprecated the no-maxSize form; 5.7.1 added `expectedSize`) | Low |
| `getThumbnail` | (thumbnails) | survives | now `getThumbnail(uri, width, height, maxSize: Long?, expectedSize = null, method = CROP, ...)` | Low |
| `prepareUploadMedia` / `prepareUploadEncryptedMedia` / `uploadMedia` | :4036-4067 | kept | identical (`client/media/MediaService.kt`) | Trivial |
| `MediaStore.getMedia(localUri)` / `PlatformMedia.toByteArray()` | :4168-4171, :3515 | kept | identical (`MediaStore.kt:9-11`, `PlatformMedia.kt:13,26`) | Trivial |
| `EncryptedFile` (`.url`, `.copy`, `serializer()`) | :4046-4059 | kept | **verified field-identical** (`url/key/initialisationVector/hashes/version="v2"`, `EncryptedFile.kt:7-13`) | Trivial |
| `ClaimKeys.Response.failures` | :5841-5853 (`claimFailuresFixInterceptor`) | **still no default** | `Response(failures: Map<String, JsonElement>, ...)` (`model/key/ClaimKeys.kt:26-29`, package **`keys`→`key`**) — **keep the interceptor** (Beeper fix still required) | Trivial |

---

## 2. Store-migration verdict

**A 4.22.7 store opens under v5.8.0 — automatically, no custom migration, no wipe.**

- **DB version 4 → 9.** v5 `TrixnityRoomDatabase.kt:49` declares version 9 with compiled-in auto-migrations `3→4, 4→5, 5→6, 6→7, 7→8, 8→9` (all six `AutoMigration_*_Impl` classes are in the 5.8.0 jar). Room runs `4→5→…→9` on open; chats uses plain `Room.databaseBuilder` (:5913), so no `.addMigrations` calls are needed.
- **Schema delta:** adds `Authentication`, `Notification`/`NotificationState`/`NotificationUpdate`, `Migration`, `StickyEvent` (MSC4354); rebuilds `Account` (drops `oauth2Login`/`oauth2ClientId`/`displayName`/`avatarUrl`, swaps `filterId`/`backgroundFilterId` → `filter`) while **preserving `baseUrl`, `accessToken`, `refreshToken`, `userId`, `deviceId`, `olmPickleKey`, `syncBatchToken`**. Room/timeline/keys/account-data tables untouched; the `TimelineEvent` table is byte-identical (PKs `[roomId, eventId]`, cols `roomId/eventId/value`), so `json_extract(value,'$.previousEventId')` + `decodeFromString<TimelineEvent>` still work.
- **No re-login:** v5 `create()` with `authProviderData = null` hits the built-in legacy branch (`MatrixClient.kt:242-256`): if the old `Account` still has non-null `baseUrl` + `accessToken`, it builds `ClassicMatrixClientAuthProviderData` from them, writes the new `Authentication` row, and clears those columns. **Don't clear `accessToken`/`baseUrl` from the Account row before upgrading**, or `create()` throws `"No stored authProviderData found"` (:282-284).
- **One caveat, test first:** v5's "schema version 4" is assumed identical to 4.22.7's actual schema (4.22.7 was the last v4 release). A mismatch would crash at open (auto-migration failure). Verify on a **copy** of the LP3 DB before shipping; `fallbackToDestructiveMigration` as a stopgap only.
- **`RepositoryMigration`:** v5 runs `di.getAll<RepositoryMigration>()` before store init; `libOlm` registers none, so chats writes no migration. (vodozemac would add a libolm→vodozemac pickle conversion — unnecessary for a plaintext Beeper account; libOlm keeps v4's pickle format.)

## 3. Notification-swap sketch

**The old notification system is gone.** `NotificationService.getNotifications` survives only as a `@Deprecated("use the new notification system instead")` shim on the brand-new service (`NotificationService.kt:73-82`). New surface:

- `client.notification` accessor → `NotificationService` (`defaultModules.kt:299-300`)
- `getAllUpdates(): Flow<NotificationUpdate>` (:116) — `NotificationUpdate` = sealed `New/Update/Remove` (each `id`, `sortKey`, `actions: Set<PushAction>`, `Content.Message(timelineEvent)` | `Content.State(stateEvent)`)
- `processPending(setPresence: Presence = Presence.OFFLINE)` (:132) — drains pending updates, runs a `sync.startOnce` when `needsSync`, blocks until `getAllUpdates` is drained
- `onPush(roomId, eventId?): Boolean` (:123) — returns `false` if a full sync is needed; `getCount(roomId): Flow<Int>` (:94), `isUnread(roomId): Flow<Boolean>` (:101), `dismiss(id)`/`dismissAll()` (:104,107)

**Messenger wiring (reference, `connect2x-de/trixnity-messenger`):** `PushNotificationProvider` calls `onPush(roomId, eventId)` on push-wake and, when it returns `false`, `processPending(Presence.OFFLINE)`; its room list uses `notificationService.isUnread(any())` — a **Boolean**, not a count.

**Mapping onto chats (the swap is NOT drop-in — the unread-count source changes):**
- Chats' hand-rolled per-room collector (:4314-4381) + `notifyForEvent` (:4384) + `servedUnread` (:4787-4804) currently read `room.unreadMessageCount` — a Long message count. v5 has no such field.
- **Minimal path (recommended):** keep the hand-rolled system-notification path (still viable — `TimelineEvent` shape and the sync loop are unchanged; `lastRelevantEventId`/`lastRelevantEventTimestamp` survive on `Room`), but replace the badge source: feed room-list unread from `notification.isUnread(roomId)` (Boolean) or `notification.getCount(roomId)` (per-room **notification** count, not message count). `servedUnread`'s `pendingReadClear` needs a new driver — e.g. a `MutableStateFlow<Map<RoomId, Int>>` fed by collecting `getCount()` per joined room, cleared on read-receipt send.
- **Full path:** `enableExternalNotifications = true`, collect `getAllUpdates()` into chats' notifier, `onPush`/`processPending` on push-wake (messenger pattern). Gotchas: the stream is **empty unless the config flag is set**, **unbuffered** (values deleted from the DB once collected), `processPending` can suspend long, and `NotificationUpdate` carries **no per-room unread counts** — badges still need `getCount(roomId)`.
- Either way the room-list badge becomes **boolean/notification-count, not message-count** — a UX decision to confirm with the user.

## 4. Dependency / build map

Swap in `chats/gradle/libs.versions.toml`:

| Old (4.22.7) | New (5.8.0) |
|---|---|
| `net.folivo:trixnity-client` | `de.connect2x.trixnity:trixnity-client:5.8.0` |
| `net.folivo:trixnity-client-repository-room` | `de.connect2x.trixnity:trixnity-client-repository-room:5.8.0` |
| `net.folivo:trixnity-client-media-okio` | `de.connect2x.trixnity:trixnity-client-media-okio:5.8.0` |
| (transitive `net.folivo:trixnity-olm-android`) | **add** `de.connect2x.trixnity:trixnity-client-cryptodriver-libolm:5.8.0` (brings `trixnity-crypto-driver`, `trixnity-crypto-driver-libolm`, `trixnity-libolm-android` transitively — exactly what messenger depends on) |

Pinned versions that must reach v5's transitive floor: `ktor` **≥ 3.5.2** (chats pins 3.4.2), `androidx.room` **≥ 2.8.4** (chats pins 2.7.0), `kotlinx-coroutines` **1.11.0** (chats pins 1.10.2). Other transitive: kotlin-stdlib 2.3.21, kotlinx-serialization-json 1.11.0, koin-core 4.2.2, okio 3.18.1, jna 5.19.1, `lognity-api 2.2.0` (replaces `io.github.oshai:kotlin-logging` — runtime-only; chats never references `KotlinLogging` directly, no code change). A BOM exists (`trixnity-bom`) but messenger pins artifacts individually — follow that.

**Build compat: yes.** v5.8.0 builds with Kotlin 2.3.21 — 2.3.20 metadata is mutually readable; AGP 8.12.3 / Gradle 9.0.0 / jvmTarget 17 / compileSdk 36 / minSdk 34 all fine. The real work is source-level (§1).

## 5. Beeper server compat (API-surface reasoning only)

- **Wire behavior is equivalent to v4** for every call chats makes: same `/login` types (incl. `org.matrix.login.jwt`), same filter uploads, same `/sync` with `set_presence=offline`, same `/keys/upload` on first init. Sync request params are identical (`Sync.kt:59-66`); response additions are all optional. Spec 1.14–1.17 support is additive/parsing-side. **No new endpoint pressure** — v5's NotificationService computes notifications locally from sync + stored push rules, no `/notifications` polling.
- **`claimFailuresFixInterceptor` still required** — `ClaimKeys.Response.failures` has no default in v5 (package `keys`→`key`).
- **Two real risks:**
  1. On the **fresh-login path only** (`authProviderData != null`), v5 `create()` calls `GET /account/whoami` and hard-requires `device_id` in the response (`checkNotNull(whoAmI.deviceId)`, `MatrixClient.kt:224-233`). If Beeper's whoami omits `device_id`, login fails. Spec leaves `device_id` optional — **verify before shipping**. (The daily `fromStore` restore path never calls whoami, so existing sessions are unaffected.)
  2. `classicLogin(refreshToken = null)` doesn't request a refresh token, so `/refresh` isn't called — unless Beeper's login response carries one anyway, in which case v5 stores it and may use it on expiry. Worth a smoke test.

## 6. Flags: re-login / store wipe

- **No full re-login required** and **no store wipe**: built-in Account→Authentication credential migration + the auto-migration chain handle a 4.22.7 store on first `create()` (verified in source, not by execution — test on a DB copy first).
- **Would force re-login:** Beeper's whoami omitting `device_id` (only matters when re-logging in), or chats clearing `accessToken`/`baseUrl` from the Account row pre-upgrade.
- **Largest actual work items:** the `OlmEncryptionService` interface rewrite (megolm split-off + batch `encryptOlm` + `driver` ctor arg — the plaintext-verification override must cover the batch overload), the `unreadMessageCount` removal (4 call sites, semantics change), and the `login`→`classicLogin`+`create` rework.

## 7. Implementation plan (ordered for a coding session)

1. **Version catalog** — `chats/gradle/libs.versions.toml`: trixnity 4.22.7→5.8.0 + group rename, add `trixnity-client-cryptodriver-libolm`, bump ktor→3.5.2, room→2.8.4, coroutines→1.11.0. (`server/build.gradle.kts` deps unchanged apart from catalog refs.)
2. **Mechanical import rename** — `net.folivo` → `de.connect2x` across the 4 server files; then the package renames: `clientserverapi.model.users`→`…user`, `clientserverapi.model.keys`→`…key`, `crypto.olm` moved to `client.crypto.olm`. Build once to surface remaining breaks.
3. **Client creation rework** — replace the two `MatrixClient.login(...)` calls with `MatrixClientAuthProviderData.classicLogin(...)` + `MatrixClient.create(..., authProviderData = …)`; replace `fromStore(...)` with `create(..., authProviderData = null)`; add `cryptoDriverModule = CryptoDriverModule.libOlm()`; keep `clientConfiguration(name)` (all config fields survive).
4. **Module factories** — `createRoomRepositoriesModule(db)` → `RepositoriesModule.room(db)`; `createOkioMediaStoreModule(dir)` → `MediaStoreModule.okio(dir)`; `RepositoryTransactionManager` → `StoreTransactionManager`; serializer mappings: `DefaultEventContentSerializerMappings` → `EventContentSerializerMappings.default`, `createEventContentSerializerMappings {}` → `EventContentSerializerMappings {}`.
5. **Crypto override rewrite** (`PlaintextVerificationOlmEncryptionService.kt` + Koin wiring at :5905-5911) — implement the v5 `OlmEncryptionService` interface: drop megolm overrides, 3-arg `encryptOlm` + **batch `encryptOlm` (force plaintext there too)**, `decryptOlm` → `Result<PlaintextOlmEvent<*>>`; supply `driver: CryptoDriver` to `OlmEncryptionServiceImpl` (from `CryptoDriverModule.libOlm()`). Verify SAS + recovery-key flows still work.
6. **Media** — add `maxSize = null` (and `expectedSize` where needed) to `getMedia`/`getEncryptedMedia`/`getThumbnail` call sites.
7. **Unread counts** — replace the 4 `room.unreadMessageCount` reads: decide boolean (`notification.isUnread`) vs count (`notification.getCount`) vs deriving from `RoomUserReceiptsRepository`; keep the watcher's `lastRelevantEventId/timestamp` baseline. **UX decision: badge becomes boolean/notification-count, not message-count.**
8. **Notification swap** — default to the minimal path (keep `notifyForEvent`, re-drive badges + `servedUnread`/`pendingReadClear` from the notification service); optionally wire `getAllUpdates()`+`processPending(Presence.OFFLINE)` for push-wake (messenger pattern) only if the minimal path falls short.
9. **DB migration test** — copy a real LP3 DB (or the emulator's `chats.db`), open under v5, assert session restores **without re-login** and rooms/timeline survive (schema 4→9 auto-migration); keep `accessToken`/`baseUrl` in the Account row until migration fires.
10. **Beeper smoke test** — login (JWT) + whoami `device_id` presence; sync; send/receive; reactions/edits; media upload/download; pusher registration; push-wake.
11. **Cleanup** — keep `claimFailuresFixInterceptor` (still needed); remove any now-dead code; update `chats/PLAN.md` item 4 status, root `WORKLOG.md`, and this file's status header; run `tools/check-agents-size` + the `lightos-design` skill pass.

**Verification checklist (emulator, `tools/build --dir chats :app:assembleDebug`):** build green → install → login (Beeper + plain Matrix homeserver if available) → room list, unread badges, read-marker clear → send text/reply/image/voice note → reactions, edits, flood-ghost suppression → archive PUT/GET (Beeper) → media load/thumbnail/encrypted → SAS + recovery-key verification → key-backup restore → logout/login → push wake (`timedSyncOnce`). Real-LP3 control only with explicit user permission.
