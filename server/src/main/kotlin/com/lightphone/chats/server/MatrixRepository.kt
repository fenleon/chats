package com.lightphone.chats.server

import android.content.Context
import androidx.room.Room
import com.lightphone.chats.server.MatrixRepository.ChatConnectionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.MatrixClientConfiguration
import net.folivo.trixnity.client.fromStore
import net.folivo.trixnity.client.key
import net.folivo.trixnity.client.login
import net.folivo.trixnity.client.media.okio.createOkioMediaStoreModule
import net.folivo.trixnity.client.room
import net.folivo.trixnity.client.room.GetTimelineEventsConfig
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.serverDiscovery
import net.folivo.trixnity.client.store.TimelineEvent
import net.folivo.trixnity.client.store.Room as MatrixRoom
import net.folivo.trixnity.client.store.repository.room.TrixnityRoomDatabase
import net.folivo.trixnity.client.store.repository.room.createRoomRepositoriesModule
import net.folivo.trixnity.client.user
import net.folivo.trixnity.client.verification
import net.folivo.trixnity.client.verification.ActiveDeviceVerification
import net.folivo.trixnity.client.verification.ActiveSasVerificationMethod
import net.folivo.trixnity.client.verification.ActiveSasVerificationState
import net.folivo.trixnity.client.verification.ActiveVerificationState
import net.folivo.trixnity.clientserverapi.client.SyncState
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.clientserverapi.model.authentication.LoginType
import net.folivo.trixnity.clientserverapi.model.rooms.GetEvents.Direction
import net.folivo.trixnity.clientserverapi.model.rooms.GetEvents.Direction.BACKWARDS
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.key.verification.VerificationMethod
import net.folivo.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import net.folivo.trixnity.core.model.events.m.room.Membership
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.seconds

/**
 * The Chats companion's Matrix core. Owns the [MatrixClient] lifecycle — login,
 * session restore from the Room store, logout — and exposes snapshot queries for
 * the binder methods (rooms, messages, send, read, typing). The persistent sync
 * loop lives in [ChatSyncService]; this object is the single source of truth the
 * service and the binder methods share.
 */
object MatrixRepository {

    sealed interface ChatConnectionState {
        data object LoggedOut : ChatConnectionState
        data object Connecting : ChatConnectionState
        data object Syncing : ChatConnectionState
        data class Offline(val detail: String) : ChatConnectionState
    }

    private const val PREFS = "chats_account"
    private const val KEY_HOMESERVER = "homeserver"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_LOGIN_MODE = "login_mode"
    private const val KEY_BEEPER_REQUEST_ID = "beeper_request_id"
    private const val DB_NAME = "matrix_client"
    private const val MEDIA_DIR = "matrix_media"

    // PRIVATE Beeper integration — Beeper's private API (undocumented, unstable).
    // These endpoints and the API token stay in the companion only (the chats/
    // tree is non-publishable); keep them out of any public-facing docs or repos.
    private const val BEEPER_API_BASE = "https://api.beeper.com"
    private const val BEEPER_HOMESERVER = "https://matrix.beeper.com"
    private const val BEEPER_API_TOKEN = "BEEPER-PRIVATE-API-PLEASE-DONT-USE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()

    @Volatile
    private var client: MatrixClient? = null

    @Volatile
    private var appContext: Context? = null

    /** Guards the one-shot megolm restore after verification. */
    @Volatile
    private var restoreAttempted = false

    /** The room currently shown in the tool; notifications for it are suppressed. */
    @Volatile
    private var activeRoomId: String? = null

    /**
     * Room the tool should open next time its list shows (set when a message
     * notification is posted — see [ChatNotifier]); consumed by [takeNotifyRoom].
     */
    @Volatile
    var pendingNotifyRoomId: String? = null

    /** Client the sync-state + notification observers are currently attached to. */
    @Volatile
    private var observedClient: MatrixClient? = null
    private val notificationWatcherJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

    /**
     * True while [logout] is running, so the login-state observer doesn't
     * misread the logout's own LOGGED_OUT transition as an expired session.
     */
    @Volatile
    private var manualLogout = false

    /**
     * The session was invalidated server-side (expired token / logged out
     * elsewhere). Sync is stopped and the UI reports it; the stored session is
     * kept until the user logs out so their data isn't wiped behind them.
     */
    @Volatile
    private var sessionExpired = false

    private val _connectionState = MutableStateFlow<ChatConnectionState>(ChatConnectionState.LoggedOut)
    val connectionState: StateFlow<ChatConnectionState> = _connectionState.asStateFlow()

    /** Called once from [ServerApplication]; restores a stored session if there is one. */
    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext == null) appContext = app
        scope.launch {
            if (ensureClient() != null) {
                ChatSyncService.start(app)
            }
        }
    }

    suspend fun login(
        homeserver: String,
        user: String,
        passwordOrToken: String,
        tokenLogin: Boolean,
    ): Result<MatrixClient> = runCatching {
        val ctx = appContext ?: error("companion not initialized")
        initMutex.withLock {
            client?.let { old ->
                runCatching { old.stopSync() }
                client = null
            }
            _connectionState.value = ChatConnectionState.Connecting

            // Accept a bare domain ("matrix.org") or a full URL; .well-known
            // discovery runs when the host serves one, else the URL is used as-is.
            val baseUrl = homeserver.trim().serverDiscovery(httpClientEngine = httpClientEngine).getOrThrow()

            val loginResult = MatrixClient.login(
                baseUrl = baseUrl,
                identifier = IdentifierType.User(user.trim()),
                password = if (tokenLogin) null else passwordOrToken,
                token = if (tokenLogin) passwordOrToken else null,
                loginType = if (tokenLogin) LoginType.Token() else LoginType.Password,
                initialDeviceDisplayName = "Chats (Light Phone)",
                repositoriesModule = createRoomRepositoriesModule(databaseBuilder(ctx)),
                mediaStoreModule = createOkioMediaStoreModule(mediaDir(ctx)),
                configuration = matrixConfiguration(),
            )
            val newClient = loginResult.getOrThrow()
            client = newClient
            sessionExpired = false
            manualLogout = false
            resetRoomList()
            observeClient(newClient)
            restoreAttempted = false

            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HOMESERVER, baseUrl.toString())
                .putString(KEY_USER_ID, newClient.userId.full)
                .putString(KEY_LOGIN_MODE, "homeserver")
                .apply()
        }
        ChatSyncService.start(ctx)
        client!!
    }

    // --- Beeper login (PRIVATE — the v1 login path) --------------------------

    /**
     * Step 1 of the Beeper login: starts a login request against Beeper's
     * private API and emails [email] a 6-digit code. The request id is stored
     * for [beeperLogin], which exchanges the code. (The user checks their email
     * between the two calls, so they cannot be one binder round-trip.)
     */
    suspend fun beeperRequestCode(email: String): Result<Unit> = runCatching {
        val ctx = appContext ?: error("companion not initialized")
        val http = HttpClient()
        try {
            val init = http.post("$BEEPER_API_BASE/user/login") {
                header("Authorization", "Bearer $BEEPER_API_TOKEN")
                contentType(ContentType.Application.Json)
            }
            if (init.status.value !in 200..299) error("Beeper login init failed (HTTP ${init.status.value})")
            val requestId = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(init.bodyAsText())
                .jsonObject["request"]?.jsonPrimitive?.content
                ?: error("missing request id")
            val emailReq = http.post("$BEEPER_API_BASE/user/login/email") {
                header("Authorization", "Bearer $BEEPER_API_TOKEN")
                contentType(ContentType.Application.Json)
                setBody("""{"request":"$requestId","email":"$email"}""")
            }
            if (emailReq.status.value !in 200..299) {
                error("Beeper code request failed (HTTP ${emailReq.status.value})")
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BEEPER_REQUEST_ID, requestId).apply()
        } finally {
            http.close()
        }
    }

    /**
     * Step 2 of the Beeper login: exchanges the emailed [code] for a Matrix
     * JWT (Beeper's private API, org.matrix.login.jwt) and logs the session in
     * on [BEEPER_HOMESERVER] — WhatsApp via Beeper's own bridges. Requires a
     * prior [beeperRequestCode] call (its request id is consumed here).
     */
    suspend fun beeperLogin(email: String, code: String): Result<MatrixClient> = runCatching {
        val ctx = appContext ?: error("companion not initialized")
        initMutex.withLock {
            client?.let { old ->
                runCatching { old.stopSync() }
                client = null
            }
            _connectionState.value = ChatConnectionState.Connecting

            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val requestId = prefs.getString(KEY_BEEPER_REQUEST_ID, null)
                ?: error("no pending code request — request a code first")
            val loginToken: String
            val username: String
            val http = HttpClient()
            try {
                val resp = http.post("$BEEPER_API_BASE/user/login/response") {
                    header("Authorization", "Bearer $BEEPER_API_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"request":"$requestId","response":"$code"}""")
                }
                if (resp.status.value !in 200..299) {
                    error("Beeper code verification failed (HTTP ${resp.status.value})")
                }
                val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp.bodyAsText()).jsonObject
                val whoami = json["whoami"]?.jsonObject ?: error("missing whoami")
                val userInfo = whoami["userInfo"]?.jsonObject ?: error("missing userInfo")
                username = userInfo["username"]?.jsonPrimitive?.content ?: error("missing username")
                loginToken = json["token"]?.jsonPrimitive?.content ?: error("missing token")
            } finally {
                http.close()
            }

            val loginResult = MatrixClient.login(
                baseUrl = Url(BEEPER_HOMESERVER),
                identifier = IdentifierType.User(username.trim()),
                password = null,
                token = loginToken,
                loginType = LoginType.Unknown("org.matrix.login.jwt", buildJsonObject {}),
                initialDeviceDisplayName = "Chats (Light Phone)",
                repositoriesModule = createRoomRepositoriesModule(databaseBuilder(ctx)),
                mediaStoreModule = createOkioMediaStoreModule(mediaDir(ctx)),
                configuration = beeperMatrixConfiguration(),
            )
            val newClient = loginResult.getOrThrow()
            client = newClient
            sessionExpired = false
            manualLogout = false
            resetRoomList()
            observeClient(newClient)
            restoreAttempted = false

            prefs.edit()
                .putString(KEY_HOMESERVER, BEEPER_HOMESERVER)
                .putString(KEY_USER_ID, newClient.userId.full)
                .putString(KEY_LOGIN_MODE, "beeper")
                .remove(KEY_BEEPER_REQUEST_ID)
                .apply()
        }
        ChatSyncService.start(ctx)
        client!!
    }

    // --- E2EE (Trixnity crypto in the companion; SAS device verification) -----

    /** UI-facing shape of the interactive verification, serialized over the binder. */
    sealed interface VerificationUi {
        data object Idle : VerificationUi
        data object Waiting : VerificationUi          // request sent / SAS started, awaiting the other device
        data object Accept : VerificationUi           // their request or their SAS start
        data object Start : VerificationUi            // both ready; this side starts the SAS
        data class Compare(val emoji: List<String>) : VerificationUi
        data object Done : VerificationUi
        data object Cancelled : VerificationUi
        data class Error(val detail: String) : VerificationUi
    }

    private val _verification = MutableStateFlow<VerificationUi>(VerificationUi.Idle)
    val verification: StateFlow<VerificationUi> = _verification.asStateFlow()

    @Volatile
    private var activeVerification: ActiveDeviceVerification? = null
    @Volatile
    private var pendingTheirRequest: ActiveVerificationState.TheirRequest? = null
    @Volatile
    private var pendingReady: ActiveVerificationState.Ready? = null
    @Volatile
    private var pendingTheirSasStart: ActiveSasVerificationState.TheirSasStart? = null
    @Volatile
    private var pendingCompare: ActiveSasVerificationState.ComparisonByUser? = null

    /** E2EE status: whether this device is cross-signing verified, and whether other devices exist to verify with. */
    suspend fun e2eeState(): com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response {
        val c = client ?: return com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response(
            verified = false, canVerify = false, detail = "not logged in",
        )
        val trust = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.key.getTrustLevel(c.userId, c.deviceId).firstOrNull()
        }
        val verified = trust is net.folivo.trixnity.crypto.key.DeviceTrustLevel.CrossSigned && trust.verified
        if (verified && !restoreAttempted) {
            restoreAttempted = true
            scope.launch { restoreMegolmSessions() }
        }
        val devices = runCatching {
            c.api.device.getDevices().getOrNull()?.map { it.deviceId }?.filter { it != c.deviceId }?.size ?: 0
        }.getOrDefault(0)
        return com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response(
            verified = verified,
            canVerify = devices > 0,
            detail = if (verified) null else "not verified",
        )
    }

    /** Starts SAS verification with the account's other devices (their Beeper app responds). */
    suspend fun startDeviceVerification(): Result<Unit> = runCatching {
        val c = client ?: error("not logged in")
        val otherDevices = c.api.device.getDevices().getOrThrow()
            .map { it.deviceId }.filter { it != c.deviceId }.toSet()
        if (otherDevices.isEmpty()) error("no other devices on this account to verify with")
        resetVerification()
        val request = c.verification.createDeviceVerificationRequest(c.userId, otherDevices).getOrThrow()
        activeVerification = request
        _verification.value = VerificationUi.Waiting
        scope.launch {
            request.state.collectLatest { state -> onVerificationState(state) }
        }
    }

    fun verificationState(): com.thelightphone.sdk.shared.LightServiceMethod.GetVerificationState.Response {
        val ui = _verification.value
        return com.thelightphone.sdk.shared.LightServiceMethod.GetVerificationState.Response(
            state = when (ui) {
                VerificationUi.Idle -> "none"
                VerificationUi.Waiting -> "waiting"
                VerificationUi.Accept -> "accept"
                VerificationUi.Start -> "start"
                is VerificationUi.Compare -> "compare"
                VerificationUi.Done -> "done"
                VerificationUi.Cancelled -> "cancelled"
                is VerificationUi.Error -> "error"
            },
            emoji = (ui as? VerificationUi.Compare)?.emoji,
            detail = (ui as? VerificationUi.Error)?.detail,
        )
    }

    /** Drives the interactive verification; [action] ∈ accept | start | match | no_match | cancel | reset. */
    suspend fun verifyAction(action: String): Result<Unit> = runCatching {
        when (action) {
            "accept" -> {
                // The "accept" UI covers both an incoming request and the SAS
                // start; act on whichever is pending.
                val request = pendingTheirRequest
                val sas = pendingTheirSasStart
                when {
                    request != null -> request.ready()
                    sas != null -> sas.accept()
                    else -> error("no incoming request")
                }
            }
            "start" -> {
                val ready = pendingReady ?: error("verification not ready")
                ready.start(VerificationMethod.Sas)
            }
            "match" -> (pendingCompare ?: error("no emoji comparison")).match()
            "no_match" -> (pendingCompare ?: error("no emoji comparison")).noMatch()
            "cancel" -> activeVerification?.cancel()
            "reset" -> resetVerification()
            else -> error("unknown action: $action")
        }
    }

    private fun resetVerification() {
        activeVerification = null
        pendingTheirRequest = null
        pendingReady = null
        pendingTheirSasStart = null
        pendingCompare = null
        _verification.value = VerificationUi.Idle
    }

    private suspend fun onVerificationState(state: ActiveVerificationState) {
        _verification.value = when (state) {
            is ActiveVerificationState.OwnRequest -> VerificationUi.Waiting
            is ActiveVerificationState.TheirRequest -> {
                pendingTheirRequest = state
                VerificationUi.Accept
            }
            is ActiveVerificationState.Ready -> {
                pendingReady = state
                VerificationUi.Start
            }
            is ActiveVerificationState.Start -> {
                val method = state.method
                if (method is ActiveSasVerificationMethod) {
                    scope.launch { method.state.collectLatest { sas -> onSasState(sas) } }
                }
                VerificationUi.Waiting
            }
            is ActiveVerificationState.Done -> {
                scope.launch { restoreMegolmSessions() }
                VerificationUi.Done
            }
            else -> {
                if (state::class.simpleName?.contains("Cancel", ignoreCase = true) == true) {
                    VerificationUi.Cancelled
                } else {
                    VerificationUi.Waiting
                }
            }
        }
    }

    private suspend fun onSasState(state: ActiveSasVerificationState) {
        _verification.value = when (state) {
            is ActiveSasVerificationState.OwnSasStart -> VerificationUi.Waiting
            is ActiveSasVerificationState.TheirSasStart -> {
                pendingTheirSasStart = state
                VerificationUi.Accept
            }
            is ActiveSasVerificationState.ComparisonByUser -> {
                pendingCompare = state
                VerificationUi.Compare(state.emojis.map { it.second })
            }
            // Accept / WaitForKeys / WaitForMacs are progress states; Done and
            // Cancelled arrive on the top-level verification state instead.
            else -> VerificationUi.Waiting
        }
    }

    /**
     * After the device is verified, load every undecrypted event's megolm session
     * from the server-side key backup so the room store can decrypt it. Called on
     * verification success; loading a session decrypts what the session covers.
     */
    private suspend fun restoreMegolmSessions() {
        val c = client ?: return
        val keyBackup = runCatching {
            c.di.get<net.folivo.trixnity.client.key.KeyBackupService>(
                org.koin.core.qualifier.named<net.folivo.trixnity.client.key.KeyBackupService>(),
            )
        }.getOrNull()
        if (keyBackup == null) {
            android.util.Log.e(TAG, "restore: KeyBackupService not available via DI")
            return
        }
        val backupVersion = runCatching { keyBackup.version.firstOrNull() }.getOrNull()
        android.util.Log.d(TAG, "restore: backup version = $backupVersion")
        if (backupVersion == null) {
            android.util.Log.w(TAG, "restore: no server-side key backup configured on this account")
        }
        val rooms = withTimeoutOrNull(ROOMS_BUDGET_MS) { c.room.getAll().first() } ?: return
        android.util.Log.d(TAG, "restore: scanning ${rooms.size} rooms")
        var roomsTouched = 0
        var scanned = 0
        for ((roomId, _) in rooms) {
            scanned++
            if (scanned % 200 == 0) {
                android.util.Log.d(TAG, "restore: $scanned/${rooms.size} rooms scanned, $roomsTouched with encrypted content")
            }
            val events = mutableListOf<TimelineEvent>()
            val done = withTimeoutOrNull(MESSAGES_BUDGET_MS) {
                c.room.getLastTimelineEvents(roomId) { maxSize = 100 }
                    .filterNotNull().first()
                    .collect { eventFlow ->
                        eventFlow.filterNotNull().firstOrNull()?.let { events.add(it) }
                    }
            }
            if (done == null) continue
            val hasEncrypted = events.any {
                it.content?.isFailure == true ||
                    it.event.content is EncryptedMessageEventContent.MegolmEncryptedMessageEventContent
            }
            if (hasEncrypted) {
                restoreRoomSessions(c, roomId, events)
                roomsTouched++
            }
        }
        android.util.Log.d(TAG, "restore: done — $roomsTouched rooms with encrypted content")
    }

    suspend fun logout() {
        val ctx = appContext ?: return
        initMutex.withLock {
            manualLogout = true
            val old = client
            client = null
            observedClient = null
            resetVerification()
            activeRoomId = null
            pendingNotifyRoomId = null
            resetRoomList()
            ChatNotifier.clearAll(ctx)
            runCatching { old?.logout() } // API logout + clears Trixnity's store
            runCatching { old?.closeSuspending() }
            ctx.stopService(android.content.Intent(ctx, ChatSyncService::class.java))
            ctx.deleteDatabase(DB_NAME)
            ctx.cacheDir.resolve(MEDIA_DIR).deleteRecursively()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            sessionExpired = false
            manualLogout = false
            _connectionState.value = ChatConnectionState.LoggedOut
        }
    }

    fun getClient(): MatrixClient? = client

    /**
     * Restores a session from the Room store, if one exists (idempotent).
     * Called by [ServerApplication] on boot and by [ChatSyncService] before sync.
     */
    suspend fun ensureClient(): MatrixClient? {
        initMutex.withLock {
            client?.let { return it }
            val ctx = appContext ?: return null
            val restored = runCatching {
                MatrixClient.fromStore(
                    repositoriesModule = createRoomRepositoriesModule(databaseBuilder(ctx)),
                    mediaStoreModule = createOkioMediaStoreModule(mediaDir(ctx)),
                    configuration = matrixConfiguration(),
                ).getOrNull()
            }.getOrNull()
            return if (restored != null) {
                client = restored
                observeClient(restored)
                restored
            } else {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
                null
            }
        }
    }

    fun accountState(): com.thelightphone.sdk.shared.LightServiceMethod.GetAccountState.Response {
        val c = client
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return com.thelightphone.sdk.shared.LightServiceMethod.GetAccountState.Response(
            loggedIn = c != null,
            userId = c?.userId?.full ?: prefs?.getString(KEY_USER_ID, null),
            homeserver = prefs?.getString(KEY_HOMESERVER, null),
            loginMode = prefs?.getString(KEY_LOGIN_MODE, null),
        )
    }

    fun connectionState(): com.thelightphone.sdk.shared.LightServiceMethod.GetConnectionState.Response {
        val state = _connectionState.value
        return com.thelightphone.sdk.shared.LightServiceMethod.GetConnectionState.Response(
            state = when (state) {
                ChatConnectionState.LoggedOut -> "logged_out"
                ChatConnectionState.Connecting -> "connecting"
                ChatConnectionState.Syncing -> "syncing"
                is ChatConnectionState.Offline -> "offline"
            },
            detail = (state as? ChatConnectionState.Offline)?.detail,
        )
    }

    /** Newest activity first — a pure read of the background-refreshed cache. */
    suspend fun getRooms(): List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room> {
        if (client == null) return emptyList()
        // The resolver seeds the cache promptly at attach and refreshes it in
        // the background; a binder call never triggers a 1284-room resolution
        // burst. On a cold process the first calls may return empty until the
        // resolver's first pass lands — the tool's refresh retries cover that.
        return _roomList.value
    }

    /**
     * A page of a room's messages (oldest first) plus whether older messages
     * exist beyond it. [hasMore] is computed from the raw timeline page, not
     * the message-filtered list, so a page full of state events or
     * still-encrypted events doesn't end pagination early.
     */
    data class MessagesPage(
        val messages: List<com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message>,
        val hasMore: Boolean,
    )

    /**
     * Messages of a room, oldest first. [beforeEventId] pages further back;
     * null returns the newest [limit] messages.
     */
    suspend fun getMessages(
        roomId: String,
        beforeEventId: String?,
        limit: Int,
    ): MessagesPage {
        val c = client ?: return MessagesPage(emptyList(), false)
        val matrixRoomId = RoomId(roomId)

        // Lazy per-room decrypt: load any undecrypted events' megolm sessions
        // first, then re-read. Decryption lands asynchronously once the session
        // is in the store, so re-read up to a few times before giving up.
        val firstPass = collectTimelineEvents(c, matrixRoomId, beforeEventId, limit + 1)
        restoreRoomSessions(c, matrixRoomId, firstPass)

        var events = collectTimelineEvents(c, matrixRoomId, beforeEventId, limit + 1)
        repeat(DECRYPT_RETRIES) {
            val stillEncrypted = events.any { it.content?.isFailure == true }
            if (!stillEncrypted) return@repeat
            kotlinx.coroutines.delay(DECRYPT_RETRY_DELAY_MS)
            events = collectTimelineEvents(c, matrixRoomId, beforeEventId, limit + 1)
        }

        // A full raw page (limit + 1, including the cursor event for older
        // pages) means older messages exist beyond this one.
        val hasMore = events.size >= limit + 1
        android.util.Log.d(
            TAG,
            "getMessages: room=$matrixRoomId before=$beforeEventId limit=$limit rawPage=${events.size} hasMore=$hasMore",
        )

        val result = mutableListOf<com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message>()
        val startIndex = if (beforeEventId == null) 0 else 1 // drop the cursor event
        for (i in startIndex until events.size) {
            if (result.size >= limit) break
            messageFrom(c, matrixRoomId, events[i])?.let { result.add(it) }
        }
        return MessagesPage(messages = result.reversed(), hasMore = hasMore) // oldest first
    }

    /** Collects a room's timeline events (newest first), null cursor = newest page. */
    private suspend fun collectTimelineEvents(
        c: MatrixClient,
        matrixRoomId: RoomId,
        beforeEventId: String?,
        maxSize: Int,
    ): List<TimelineEvent> {
        val events = mutableListOf<TimelineEvent>()
        val config: GetTimelineEventsConfig.() -> Unit = {
            this.maxSize = maxSize.toLong()
            fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
            decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
        }
        val collected = withTimeoutOrNull(MESSAGES_BUDGET_MS) {
            val eventFlows = if (beforeEventId == null) {
                c.room.getLastTimelineEvents(matrixRoomId, config).filterNotNull().first()
            } else {
                c.room.getTimelineEvents(matrixRoomId, EventId(beforeEventId), Direction.BACKWARDS, config)
            }
            eventFlows.collect { eventFlow ->
                // Reading a timeline triggers decryption; the event flow re-emits
                // once the content resolves (first emission may carry null or a
                // failure while the decrypt is pending), so wait for the
                // decrypted emission (bounded), falling back to the first.
                val resolved = withTimeoutOrNull(DECRYPT_WAIT_MS) {
                    eventFlow.filterNotNull().firstOrNull {
                        it.content?.getOrNull() != null || it.event.content !is EncryptedMessageEventContent
                    }
                } ?: eventFlow.filterNotNull().firstOrNull()
                if (resolved != null) {
                    val content = resolved.content
                    if (content?.isFailure == true) {
                        val ex = content.exceptionOrNull()
                        android.util.Log.w(TAG, "collect: event ${resolved.event.id.full} still encrypted: ${ex?.javaClass?.simpleName}: ${ex?.message}")
                    }
                    events.add(resolved)
                }
            }
        }
        return if (collected == null) emptyList() else events
    }

    /** Loads the megolm sessions for the given events' undecrypted content from the key backup. */
    private suspend fun restoreRoomSessions(
        c: MatrixClient,
        matrixRoomId: RoomId,
        events: List<TimelineEvent>,
    ) {
        val sessionIds = events.mapNotNull { te ->
            (te.event.content as? EncryptedMessageEventContent.MegolmEncryptedMessageEventContent)?.sessionId
        }.distinct()
        android.util.Log.d(TAG, "restoreRoomSessions: $matrixRoomId — ${events.size} events, ${sessionIds.size} megolm sessions, " +
            "encrypted classes: ${events.map { it.event.content::class.simpleName }.distinct()}")
        if (sessionIds.isEmpty()) return
        val keyBackup = runCatching {
            c.di.get<net.folivo.trixnity.client.key.KeyBackupService>(
                org.koin.core.qualifier.named<net.folivo.trixnity.client.key.KeyBackupService>(),
            )
        }.getOrNull()
        if (keyBackup == null) {
            android.util.Log.e(TAG, "restoreRoomSessions: KeyBackupService not available via DI")
            return
        }
        sessionIds.forEach { sessionId ->
            try {
                keyBackup.loadMegolmSession(matrixRoomId, sessionId)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "restoreRoomSessions: loadMegolmSession failed for $matrixRoomId / $sessionId: ${e.message}")
            }
        }
        android.util.Log.d(TAG, "restoreRoomSessions: loaded ${sessionIds.size} sessions for $matrixRoomId")
    }

    suspend fun sendMessage(
        roomId: String,
        body: String,
        replyToEventId: String?,
    ): com.thelightphone.sdk.shared.LightServiceMethod.SendMessage.Response {
        val c = client ?: error("not logged in")
        val matrixRoomId = RoomId(roomId)
        val txnId = c.room.sendMessage(matrixRoomId) {
            if (replyToEventId != null) {
                val replyEvent = c.room.getTimelineEvent(matrixRoomId, EventId(replyToEventId)).firstOrNull()
                if (replyEvent != null) reply(replyEvent)
            }
            text(body = body)
        }
        return com.thelightphone.sdk.shared.LightServiceMethod.SendMessage.Response(transactionId = txnId)
    }

    suspend fun markRead(roomId: String, eventId: String) {
        val c = client ?: return
        c.api.room.setReadMarkers(
            roomId = RoomId(roomId),
            fullyRead = EventId(eventId),
            read = EventId(eventId),
        )
        // Opening the thread makes the room's notification moot.
        appContext?.let { ChatNotifier.cancelRoom(it, roomId) }
    }

    suspend fun setTyping(roomId: String, active: Boolean) {
        val c = client ?: return
        c.api.room.setTyping(
            roomId = RoomId(roomId),
            userId = c.userId,
            typing = active,
            timeout = if (active) TYPING_TIMEOUT_MS else null,
        )
    }

    // --- Notifications (Phase 4) --------------------------------------------

    /**
     * Records the room the tool is currently showing. New-message
     * notifications for it are suppressed, and any standing notification for
     * it is removed (opening the thread marks it read). null = no room on
     * screen (list/settings/tool backgrounded).
     */
    fun setActiveRoom(roomId: String?) {
        activeRoomId = roomId
        val ctx = appContext ?: return
        if (roomId != null) ChatNotifier.cancelRoom(ctx, roomId)
    }

    /**
     * One-shot read of the room a posted notification belongs to (set by
     * [ChatNotifier]), so the tool can auto-open the right thread after a tap.
     * Cleared on read.
     */
    fun takeNotifyRoom(): String? {
        val roomId = pendingNotifyRoomId
        pendingNotifyRoomId = null
        return roomId
    }

    /**
     * Watches every joined room's newest relevant event and posts a
     * notification when one arrives from someone else — the sync loop is the
     * delivery mechanism, so no push service is involved. Attached to a client
     * once via [observeClient]; per-room collectors are idempotent (first
     * emission per room establishes the baseline, later ones notify).
     */
    private fun observeNotifications(c: MatrixClient) {
        android.util.Log.d(TAG, "notification watcher starting for ${c.userId.full}")
        val watcher = scope.launch {
            try {
                // roomId.full -> last relevant event id seen so far ("" = none yet).
                val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
                // roomId.full -> collector launched (dedup against map re-emissions).
                val registered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                var watched = 0
                c.room.getAll().collect { rooms ->
                    for ((roomId, roomFlow) in rooms) {
                        val key = roomId.full
                        if (!registered.add(key)) continue
                        // No preload here: the collector's first emission
                        // establishes the baseline (rooms already synced are not
                        // notified), and later emissions notify for new ones.
                        val job = scope.launch {
                            try {
                                roomFlow.filterNotNull().collect { updated ->
                                    val lastId = updated.lastRelevantEventId?.full ?: return@collect
                                    if (updated.membership != Membership.JOIN) return@collect
                                    val prev = seen[key]
                                    if (prev == null) {
                                        // Baseline: the first loaded state is
                                        // already-synced history, never notified.
                                        seen[key] = lastId
                                        return@collect
                                    }
                                    if (prev != lastId) {
                                        seen[key] = lastId
                                        notifyForEvent(c, roomId, lastId, updated)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "notification watcher: room collector ended for $key: ${e.message}")
                            }
                        }
                        notificationWatcherJobs.add(job)
                        watched++
                        if (watched % 200 == 0) {
                            android.util.Log.d(TAG, "notification watcher: $watched rooms registered")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "notification watcher failed", e)
            }
        }
        notificationWatcherJobs.add(watcher)
    }

    /** Posts the notification for a new event in [room], if it is a message from someone else. */
    private suspend fun notifyForEvent(
        c: MatrixClient,
        roomId: RoomId,
        eventId: String,
        room: MatrixRoom,
    ) {
        val ctx = appContext ?: return
        if (activeRoomId == roomId.full) return
        if (room.membership != Membership.JOIN) return
        // Wait briefly for decryption so the preview shows the real text (the
        // raw m.room.encrypted payload resolves within milliseconds for live
        // events once the megolm session is in the store).
        val te = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getTimelineEvent(roomId, EventId(eventId)).filterNotNull().firstOrNull {
                it.content?.getOrNull() != null || it.event.content !is EncryptedMessageEventContent
            }
        } ?: return
        if (te.event.sender == c.userId) {
            // Suppress only messages sent from THIS device — they have an
            // outbox entry. Same-account messages from another device (e.g. a
            // WhatsApp "Message yourself" sent on the phone) should still
            // notify.
            val ownEventIds = withTimeoutOrNull(ROOM_BUDGET_MS) {
                c.room.getOutbox(roomId).first()
                    .mapNotNull { it.filterNotNull().firstOrNull()?.eventId?.full }
                    .toSet()
            }.orEmpty()
            if (te.event.id.full in ownEventIds) return
        }
        val resolved = te.content?.getOrNull()
        val isMessage = resolved is RoomMessageEventContent ||
            (resolved == null && te.event.content is EncryptedMessageEventContent)
        if (!isMessage) return
        val preview = previewText(te) ?: return
        val name = roomDisplayName(c, roomId, room)
        ChatNotifier.notifyMessage(
            context = ctx,
            roomId = roomId.full,
            roomName = name,
            // No sender prefix in DMs, and never for our own account (a
            // note-to-self message needs no "FENN:" prefix).
            senderName = if (room.isDirect || te.event.sender == c.userId) null else senderNameOf(c, roomId, te.event.sender),
            preview = preview,
            direct = room.isDirect,
            unreadCount = room.unreadMessageCount,
        )
    }

    // --- Room-list cache (Phase 5) ------------------------------------------
    // The tool's chat list is served from an in-memory cache refreshed in the
    // background, newest-first. A binder call never triggers the 1284-room
    // resolution burst (whose parallel lookups timed out and previews snapshotted
    // before decryption); the list shows placeholders immediately and fills in
    // as the resolver works, pass by pass.

    /** One cache row: the room's snapshot plus internal resolution flags. */
    private data class RoomListEntry(
        val room: com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room,
        val nameResolved: Boolean,
        val previewResolved: Boolean,
        /** A "[Encrypted]" preview retries no earlier than this (elapsedRealtime). */
        val previewRetryAtMs: Long,
    )

    private val roomListCache = java.util.concurrent.ConcurrentHashMap<String, RoomListEntry>()
    private val _roomList = MutableStateFlow<List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room>>(emptyList())

    @Volatile
    private var roomListJob: Job? = null

    private fun resetRoomList() {
        roomListCache.clear()
        _roomList.value = emptyList()
        roomListJob?.cancel()
        roomListJob = null
    }

    /**
     * Background resolver for the room list. Runs continuously while a client
     * is attached: seeds every room with a placeholder row first (so the list
     * shows instantly), then resolves names + previews newest-first within a
     * per-pass time budget, publishing the snapshot after each pass.
     */
    private fun startRoomListResolver(c: MatrixClient) {
        roomListJob?.cancel()
        roomListJob = scope.launch {
            android.util.Log.d(TAG, "room list resolver starting for ${c.userId.full}")
            // Hero-name memo: the profile store is warm, but resolving names for
            // every room sequentially still benefits from not re-reading the
            // same hero (WhatsApp DMs reuse the same few profiles).
            val nameMemo = HashMap<String, String>()
            while (true) {
                try {
                    val rooms = withTimeoutOrNull(ROOMS_BUDGET_MS) { c.room.getAll().first() }
                        ?: emptyMap()
                    val passDeadline = android.os.SystemClock.elapsedRealtime() + ROOM_LIST_PASS_BUDGET_MS
                    // Collect each room's current state (the store cache is warm,
                    // so these emit immediately) newest-first; rooms still loading
                    // are retried on the next pass.
                    val loaded = mutableListOf<Pair<RoomId, MatrixRoom>>()
                    for ((roomId, roomFlow) in rooms) {
                        if (android.os.SystemClock.elapsedRealtime() >= passDeadline) break
                        val room = withTimeoutOrNull(ROOM_LIST_ROOM_BUDGET_MS) {
                            roomFlow.filterNotNull().first()
                        }
                        if (room != null) loaded += roomId to room
                    }
                    loaded.sortByDescending { it.second.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L }
                    seedRoomList(loaded)
                    for ((roomId, room) in loaded) {
                        if (android.os.SystemClock.elapsedRealtime() >= passDeadline) break
                        resolveRoomListEntry(c, roomId, room, nameMemo)
                    }
                    publishRoomList()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "room list resolver pass failed: ${e.message}")
                }
                kotlinx.coroutines.delay(ROOM_LIST_REFRESH_DELAY_MS)
            }
        }
    }

    /** Inserts a placeholder row for every room not yet in the cache. */
    private fun seedRoomList(rooms: List<Pair<RoomId, MatrixRoom>>) {
        var seeded = 0
        for ((roomId, room) in rooms) {
            val key = roomId.full
            if (roomListCache.containsKey(key)) continue
            roomListCache[key] = RoomListEntry(
                room = com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room(
                    id = key,
                    name = ROOM_NAME_PLACEHOLDER, // filled in by the resolver
                    lastMessage = "",
                    unreadCount = room.unreadMessageCount,
                    lastTimestampMs = room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L,
                    lastEventId = room.lastRelevantEventId?.full,
                ),
                nameResolved = false,
                previewResolved = false,
                previewRetryAtMs = 0L,
            )
            seeded++
        }
        if (seeded > 0) android.util.Log.d(TAG, "room list: seeded $seeded placeholder rows")
    }

    /**
     * Refreshes one room's cache row. Names resolve sequentially from the warm
     * profile store (no parallel contention); previews read the newest event
     * with a bounded decrypt-wait. Rows whose state hasn't changed keep their
     * resolved name/preview, so steady-state passes are cheap.
     */
    private suspend fun resolveRoomListEntry(
        c: MatrixClient,
        roomId: RoomId,
        room: MatrixRoom,
        nameMemo: MutableMap<String, String>,
    ) {
        val key = roomId.full
        val prev = roomListCache[key]
        val ts = room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L
        val lastEventId = room.lastRelevantEventId?.full
        val unread = room.unreadMessageCount
        val stateChanged = prev == null ||
            prev.room.lastEventId != lastEventId ||
            prev.room.unreadCount != unread ||
            prev.room.lastTimestampMs != ts

        val nameResolved = prev?.nameResolved == true && !stateChanged
        val name = if (nameResolved) {
            prev.room.name
        } else {
            resolveRoomName(c, roomId, room, nameMemo)
        }

        val preview: String
        val previewResolved: Boolean
        val previewRetryAtMs: Long
        when {
            prev != null && prev.previewResolved && !stateChanged -> {
                preview = prev.room.lastMessage
                previewResolved = true
                previewRetryAtMs = 0L
            }
            lastEventId == null -> {
                preview = ""
                previewResolved = true
                previewRetryAtMs = 0L
            }
            android.os.SystemClock.elapsedRealtime() < (prev?.previewRetryAtMs ?: 0L) -> {
                preview = prev?.room?.lastMessage.orEmpty()
                previewResolved = false
                previewRetryAtMs = prev!!.previewRetryAtMs
            }
            else -> {
                val resolved = resolveRoomPreview(c, roomId, lastEventId)
                preview = resolved.first
                previewResolved = resolved.second
                previewRetryAtMs = resolved.third
            }
        }

        roomListCache[key] = RoomListEntry(
            room = com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room(
                id = key,
                name = name,
                lastMessage = preview,
                unreadCount = unread,
                lastTimestampMs = ts,
                lastEventId = lastEventId,
            ),
            nameResolved = true,
            previewResolved = previewResolved,
            previewRetryAtMs = previewRetryAtMs,
        )
    }

    /** The room's display name: explicit name → heroes (memoized) → "Chat". */
    private suspend fun resolveRoomName(
        c: MatrixClient,
        roomId: RoomId,
        room: MatrixRoom,
        nameMemo: MutableMap<String, String>,
    ): String {
        room.name?.explicitName?.takeIf { it.isNotBlank() }?.let { return it }
        val heroes = room.name?.heroes.orEmpty()
        if (heroes.isNotEmpty()) {
            val names = heroes.mapNotNull { hero ->
                nameMemo.getOrPut(hero.full) {
                    withTimeoutOrNull(ROOM_BUDGET_MS) {
                        c.user.getById(roomId, hero).firstOrNull()?.name ?: hero.localpart
                    } ?: hero.localpart
                }
            }.filter { it.isNotBlank() }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return "Chat"
    }

    /**
     * Bounded read of the room's newest event. Returns (preview, resolved,
     * retryAtMs): an "[Encrypted]" preview is unresolved and retried after
     * [ROOM_LIST_ENCRYPTED_RETRY_MS] (the key-backup restore reaches rooms
     * over time and decrypts them).
     */
    private suspend fun resolveRoomPreview(
        c: MatrixClient,
        roomId: RoomId,
        lastEventId: String,
    ): Triple<String, Boolean, Long> {
        val te = withTimeoutOrNull(PREVIEW_BUDGET_MS) {
            c.room.getTimelineEvent(roomId, EventId(lastEventId)).filterNotNull().firstOrNull {
                it.content?.getOrNull() != null || it.event.content !is EncryptedMessageEventContent
            }
        }
        if (te == null) {
            return Triple("", false, android.os.SystemClock.elapsedRealtime() + ROOM_LIST_ENCRYPTED_RETRY_MS)
        }
        val text = previewText(te) ?: ""
        val encrypted = text.startsWith("[Encrypted")
        return Triple(
            text,
            !encrypted,
            if (encrypted) android.os.SystemClock.elapsedRealtime() + ROOM_LIST_ENCRYPTED_RETRY_MS else 0L,
        )
    }

    /** Publishes the cache as the sorted, SDK-shaped list. */
    private fun publishRoomList() {
        _roomList.value = roomListCache.values
            .map { it.room }
            .sortedByDescending { it.lastTimestampMs }
    }

    // --- internals -----------------------------------------------------------

    private val httpClientEngine = OkHttp.create { }

    // Beeper's homeserver omits the `failures` member from /keys/claim
    // responses, which Trixnity's E2EE key claiming chokes on; inject it when
    // missing (adopted from the MIT-licensed Beeper4LightOS bootstrap, which
    // proved it on-device). Scoped to a Beeper-only engine so the generic
    // homeserver path (and its verified behavior) is untouched.
    private val beeperHttpClientEngine = OkHttp.create {
        addInterceptor(okhttp3.Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.url.encodedPath.contains("/keys/claim")) {
                val stringBody = response.body.string()
                val newString = if (!stringBody.contains("\"failures\"")) {
                    stringBody.replaceFirst("{", "{\"failures\":{},")
                } else stringBody
                val newBody = newString.toResponseBody(response.body.contentType())
                return@Interceptor response.newBuilder().body(newBody).build()
            }
            response
        })
    }

    private fun matrixConfiguration(): MatrixClientConfiguration.() -> Unit = {
        name = "chats"
        httpClientEngine = httpClientEngine
    }

    private fun beeperMatrixConfiguration(): MatrixClientConfiguration.() -> Unit = {
        name = "chats-beeper"
        httpClientEngine = beeperHttpClientEngine
    }

    private fun databaseBuilder(context: Context) =
        Room.databaseBuilder(context, TrixnityRoomDatabase::class.java, DB_NAME)

    private fun mediaDir(context: Context) = (context.cacheDir.absolutePath + "/$MEDIA_DIR").toPath()

    /** Attaches the sync-state + notification observers to a client, exactly once per instance. */
    private fun observeClient(c: MatrixClient) {
        if (observedClient === c) return
        observedClient = c
        notificationWatcherJobs.forEach { it.cancel() }
        notificationWatcherJobs.clear()
        observeSyncState(c)
        observeLoginState(c)
        observeNotifications(c)
        // The room-list resolver's first pass seeds + warms the full room map,
        // so the tool's getRooms (a pure cache read) returns instantly.
        startRoomListResolver(c)
    }

    private fun observeSyncState(c: MatrixClient) {
        scope.launch {
            c.syncState.collect { state ->
                _connectionState.value = when (state) {
                    SyncState.INITIAL_SYNC -> ChatConnectionState.Connecting
                    SyncState.STARTED, SyncState.RUNNING -> ChatConnectionState.Syncing
                    SyncState.ERROR, SyncState.TIMEOUT -> ChatConnectionState.Offline("sync $state")
                    SyncState.STOPPED -> when {
                        c.loginState.value == MatrixClient.LoginState.LOGGED_IN -> ChatConnectionState.Offline("sync stopped")
                        sessionExpired -> ChatConnectionState.Offline("session expired — sign in again")
                        else -> ChatConnectionState.LoggedOut
                    }
                }
            }
        }
    }

    /**
     * Watches the session's login state. When the server invalidates the
     * session (expired token, logged out on another device) Trixnity drops to
     * LOGGED_OUT/LOGGED_OUT_SOFT; we stop the sync service (no point retrying
     * a dead token) and surface "session expired" instead of the generic
     * offline state. [logout]'s own transition is excluded via [manualLogout].
     */
    private fun observeLoginState(c: MatrixClient) {
        scope.launch {
            c.loginState.collect { state ->
                if (state == MatrixClient.LoginState.LOGGED_IN || manualLogout) return@collect
                if (sessionExpired) return@collect
                sessionExpired = true
                android.util.Log.w(TAG, "session no longer logged in ($state) — treating as expired")
                _connectionState.value = ChatConnectionState.Offline("session expired — sign in again")
                appContext?.stopService(android.content.Intent(appContext, ChatSyncService::class.java))
            }
        }
    }

    private suspend fun roomDisplayName(c: MatrixClient, roomId: RoomId, room: MatrixRoom): String {
        room.name?.explicitName?.takeIf { it.isNotBlank() }?.let { return it }
        val heroes = room.name?.heroes.orEmpty()
        if (heroes.isNotEmpty()) {
            val names = heroes.mapNotNull { hero ->
                withTimeoutOrNull(ROOM_BUDGET_MS) {
                    c.user.getById(roomId, hero).firstOrNull()?.name ?: hero.localpart
                }
            }.filter { it.isNotBlank() }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return "Chat"
    }

    private suspend fun messageFrom(
        c: MatrixClient,
        roomId: RoomId,
        te: TimelineEvent,
    ): com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message? {
        val body = previewText(te) ?: return null
        val sender = te.event.sender
        return com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
            id = te.event.id.full,
            sender = sender.full,
            senderName = senderNameOf(c, roomId, sender),
            body = body,
            timestampMs = runCatching { te.event.originTimestamp }.getOrDefault(0L),
            isMine = sender == c.userId,
        )
    }

    private suspend fun senderNameOf(c: MatrixClient, roomId: RoomId, sender: UserId): String =
        withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.user.getById(roomId, sender).firstOrNull()?.name
        } ?: sender.localpart

    /** Human-readable text for a timeline event; null for events with nothing to show. */
    private fun previewText(te: TimelineEvent): String? {
        // The decrypted content first: for a decrypted event the raw event
        // content is still the m.room.encrypted payload, so it must not shadow
        // the resolved content.
        val content = te.content?.getOrNull()
        if (content != null) {
            return when (content) {
                is RoomMessageEventContent.TextBased -> stripReplyQuote(content.body)
                is RoomMessageEventContent.FileBased -> when (content) {
                    is RoomMessageEventContent.FileBased.Image -> "[Photo]"
                    is RoomMessageEventContent.FileBased.Video -> "[Video]"
                    is RoomMessageEventContent.FileBased.Audio -> "[Audio]"
                    else -> "[File]"
                }
                else -> null
            }
        }
        return when {
            te.content?.isFailure == true -> "[Encrypted — waiting for key…]"
            te.event.content is EncryptedMessageEventContent -> "[Encrypted]"
            else -> null
        }
    }

    /** Removes the leading quoted block of a Matrix reply so only the new text shows. */
    private fun stripReplyQuote(body: String): String {
        if (!body.startsWith(">")) return body.take(MAX_PREVIEW_LENGTH)
        val index = body.indexOf("\n\n")
        return (if (index != -1) body.substring(index + 2).trimStart() else body)
            .take(MAX_PREVIEW_LENGTH)
    }

    private const val MAX_PREVIEW_LENGTH = 80
    private const val ROOMS_BUDGET_MS = 15_000L
    private const val ROOM_BUDGET_MS = 3_000L
    private const val MESSAGES_BUDGET_MS = 15_000L
    private const val FETCH_TIMEOUT_SECONDS = 5L
    private const val TYPING_TIMEOUT_MS = 30_000L
    private const val DECRYPT_RETRIES = 3
    private const val DECRYPT_RETRY_DELAY_MS = 1_500L
    private const val DECRYPT_WAIT_MS = 3_000L

    // Room-list resolver (Phase 5).
    private const val ROOM_NAME_PLACEHOLDER = "…"
    /** Per-pass time budget; the resolver loops until the list is settled. */
    private const val ROOM_LIST_PASS_BUDGET_MS = 12_000L
    /** Breather between passes; a settled pass itself takes milliseconds. */
    private const val ROOM_LIST_REFRESH_DELAY_MS = 2_000L
    /** A "[Encrypted]" preview is retried no sooner than this. */
    private const val ROOM_LIST_ENCRYPTED_RETRY_MS = 10_000L
    /** Bounded decrypt-wait per room preview. */
    private const val PREVIEW_BUDGET_MS = 1_500L
    /** Per-room state collect in the resolver (the store cache emits instantly). */
    private const val ROOM_LIST_ROOM_BUDGET_MS = 500L

    private const val TAG = "MatrixRepository"
}
