package com.lightphone.chats.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.MatrixClientConfiguration
import net.folivo.trixnity.client.createTrixnityDefaultModuleFactories
import net.folivo.trixnity.client.fromStore
import net.folivo.trixnity.client.key
import net.folivo.trixnity.client.key.KeySecretService
import net.folivo.trixnity.client.key.KeyTrustService
import net.folivo.trixnity.client.login
import net.folivo.trixnity.client.media.MediaService
import net.folivo.trixnity.client.media.MediaStore
import net.folivo.trixnity.client.media.okio.createOkioMediaStoreModule
import net.folivo.trixnity.client.room
import net.folivo.trixnity.client.room.GetTimelineEventsConfig
import net.folivo.trixnity.client.room.message.image
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.serverDiscovery
import net.folivo.trixnity.client.store.GlobalAccountDataStore
import net.folivo.trixnity.client.store.OlmCryptoStore
import net.folivo.trixnity.client.store.TimelineEvent
import net.folivo.trixnity.client.store.Room as MatrixRoom
import net.folivo.trixnity.client.store.repository.RoomUserReceiptsRepository
import net.folivo.trixnity.client.store.repository.RepositoryTransactionManager
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
import net.folivo.trixnity.core.model.events.m.ReceiptType
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.key.verification.VerificationMethod
import net.folivo.trixnity.core.model.events.m.room.CreateEventContent
import net.folivo.trixnity.core.model.events.m.ReactionEventContent
import net.folivo.trixnity.core.model.events.m.room.EncryptedFile
import net.folivo.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import net.folivo.trixnity.core.model.events.m.room.Membership
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.core.model.events.m.space.ChildEventContent
import net.folivo.trixnity.core.model.events.m.secretstorage.DefaultSecretKeyEventContent
import net.folivo.trixnity.core.model.events.m.secretstorage.SecretKeyEventContent
import net.folivo.trixnity.core.model.events.UnknownEventContent
import net.folivo.trixnity.crypto.key.decodeRecoveryKey
import net.folivo.trixnity.crypto.olm.OlmEncryptionService
import net.folivo.trixnity.crypto.olm.OlmEncryptionServiceImpl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toPath
import org.koin.dsl.module
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
    private const val KEY_SYNC_ENABLED = "sync_enabled"
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

    /** Main-thread handler for the delayed sync-service stop (see [scheduleSyncStop]). */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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

    // --- Voice-note playback (Phase 14) -------------------------------------
    // The companion plays m.audio messages (the tool runtime forbids audio
    // playback APIs): PlayVoiceNote downloads the audio (decrypting when the
    // room is encrypted) and plays it with a plain MediaPlayer. The tool
    // highlights the playing row via the audioPlayingEventId the GetMessages
    // response carries, so no extra polling RPC is needed.

    /** Event id of the voice note currently playing (null = nothing playing). */
    @Volatile
    private var playingAudioEventId: String? = null

    /** Player owned by the companion; released when playback ends or changes. */
    @Volatile
    private var audioPlayer: android.media.MediaPlayer? = null

    @Volatile
    private var audioPlayerFile: java.io.File? = null

    /**
     * The audio-focus request held while a voice note plays. Playback is
     * classified as media/speech and holds transient focus, so the hardware
     * volume buttons control it and another app's playback pauses ours —
     * without explicit attributes some builds route voice notes to a stream
     * the rocker doesn't touch (feedback 2026-08-14: inaudible notes).
     */
    @Volatile
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    fun audioPlayingEventId(): String? = playingAudioEventId

    /** Playback position (ms) of the playing voice note, or null when idle. */
    fun audioPositionMs(): Long? =
        audioPlayer?.takeIf { playingAudioEventId != null }?.currentPosition?.toLong()

    /** Voice-note sends awaiting their sync echo (room key → send info). */
    private val pendingAudioEcho = java.util.concurrent.ConcurrentHashMap<String, PendingAudioSend>()

    private data class PendingAudioSend(val txnId: String, val timestampMs: Long, val durationMs: Long?)

    /**
     * Text sends awaiting their sync echo (room key → send info), the same
     * optimistic-row pattern as [pendingAudioEcho]. The echo (which can take a
     * full sync tick on a big account) replaces the row; until then every
     * getMessages shows the sent message — including a re-opened thread, which
     * is why the echo lives server-side and not in the tool's view model
     * (feedback 2026-08-14: a sent message vanished from a re-opened thread).
     */
    private val pendingTextEcho = java.util.concurrent.ConcurrentHashMap<String, PendingTextSend>()

    private data class PendingTextSend(val txnId: String, val timestampMs: Long, val body: String)

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

    /**
     * True while the degraded in-process sync loop is running (see
     * [startSyncLoop]) — lets ChatSyncService skip starting a second loop on
     * the same client when the foreground-service promotion finally lands.
     */
    @Volatile
    private var inProcessSyncRunning = false

    /** Lets [ChatSyncService] skip starting a second loop when the fallback owns it. */
    val isInProcessSyncRunning: Boolean get() = inProcessSyncRunning

    private val _connectionState = MutableStateFlow<ChatConnectionState>(ChatConnectionState.LoggedOut)
    val connectionState: StateFlow<ChatConnectionState> = _connectionState.asStateFlow()

    /** User pause for the sync loop (Settings → Sync, audit 2026-08-14): when
     *  false, no sync loop / foreground service runs — the battery escape hatch. */
    @Volatile
    private var syncEnabled = true

    val isSyncEnabled: Boolean get() = syncEnabled

    /** Called once from [ServerApplication]; restores a stored session if there is one. */
    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext == null) appContext = app
        enableTrixnityLogging()
        // Settings → Sync pause (audit 2026-08-14): a paused companion starts
        // no sync loop and no foreground service — the battery escape hatch.
        syncEnabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_ENABLED, true)
        if (!syncEnabled) {
            android.util.Log.d(TAG, "sync disabled by preference — not starting sync loop")
            return
        }
        scope.launch {
            if (ensureClient() != null) {
                startSyncLoop(app)
            }
        }
    }

    /**
     * Pauses/resumes the Matrix sync loop + foreground service (Settings → Sync).
     * Pausing stops all background sync work; the notification watcher and
     * room-list resolver go dormant on their own (without sync the room flows
     * never emit). Re-enabling restores the session if needed and restarts the
     * loop. Persisted, so it survives reboots.
     */
    suspend fun setSyncEnabled(enabled: Boolean) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
        syncEnabled = enabled
        val c = client
        if (!enabled) {
            runCatching { c?.stopSync() }
            inProcessSyncRunning = false
            activeRoomRefreshJob?.cancel()
            activeRoomRefreshJob = null
            ctx.stopService(android.content.Intent(ctx, ChatSyncService::class.java))
            _connectionState.value = ChatConnectionState.Offline("sync paused")
            android.util.Log.d(TAG, "sync paused by user")
        } else {
            if (c == null) {
                if (ensureClient() != null) startSyncLoop(ctx)
            } else {
                startSyncLoop(ctx)
            }
            android.util.Log.d(TAG, "sync resumed by user")
        }
    }

    /**
     * Trixnity logs via kotlinlogging → java.util.logging, which Android discards
     * without a handler. Route it to logcat (tag "Trixnity") so crypto/sync
     * internals are debuggable — e.g. why a /keys/claim doesn't result in an olm
     * session ("could not encrypt room key with olm").
     *
     * Full FINE tracing is gated behind the runtime `debugLog` flag (default
     * off — efficiency audit 2026-08-14: FINE→logcat was always on and burned
     * standby CPU/logd volume all night); WARN+ always stays visible.
     */
    private fun enableTrixnityLogging() {
        val root = java.util.logging.Logger.getLogger("net.folivo")
        root.level = if (debugLogging()) java.util.logging.Level.FINE else java.util.logging.Level.WARNING
        if (root.handlers.none { it is TrixnityLogcatHandler }) {
            root.addHandler(TrixnityLogcatHandler())
        }
    }

    /**
     * Verbose debug logging (Trixnity FINE + the HTTP-TRAFFIC interceptor).
     * Debug builds only — the release APK never enables it, even if the pref
     * is set (a release shipped with the pref enabled would log full message
     * bodies). The runtime pref still applies on debug builds (the LP3 runs
     * DEBUGGABLE APKs, so a plain BuildConfig.DEBUG gate couldn't quiet it
     * there). Toggle on debug:
     * `adb shell am start -n com.lightphone.chats.server/.MainActivity --es debugLog 1`
     * The flag persists (survives reboots) until toggled back.
     */
    private fun debugLogging(): Boolean =
        BuildConfig.DEBUG &&
            (appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                ?.getBoolean("debug_logging", false) ?: false)

    private class TrixnityLogcatHandler : java.util.logging.Handler() {
        override fun publish(record: java.util.logging.LogRecord) {
            val msg = record.message ?: return
            when {
                record.level.intValue() >= java.util.logging.Level.WARNING.intValue() ->
                    android.util.Log.w("Trixnity", "$record.loggerName: $msg")
                record.level.intValue() >= java.util.logging.Level.INFO.intValue() ->
                    android.util.Log.i("Trixnity", "$record.loggerName: $msg")
                else -> android.util.Log.d("Trixnity", "$record.loggerName: $msg")
            }
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    /**
     * Starts the Matrix sync loop with a foreground-service fallback. Android
     * blocks `startForegroundService` while the server process boots in the
     * background (mAllowStartForeground=false right after install/update), so
     * first run the loop in-process — that works as long as the tool is bound/
     * foreground — then keep promoting to the foreground service (with backoff)
     * so sync survives the tool closing. ChatSyncService treats a running
     * in-process loop as keep-alive-only instead of starting a second loop.
     */
    private suspend fun startSyncLoop(context: Context) {
        val c = client ?: return
        if (inProcessSyncRunning) return
        inProcessSyncRunning = true
        runCatching { c.startSync() }
            .onFailure { android.util.Log.w(TAG, "in-process sync failed to start: ${it.message}") }
        android.util.Log.d(TAG, "in-process sync loop started for ${c.userId.full}")
        var delayMs = 3_000L
        while (!ChatSyncService.isRunning) {
            if (ChatSyncService.tryStart(context)) break
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(60_000L)
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
        startSyncLoop(ctx)
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
        startSyncLoop(ctx)
        client!!
    }

    /**
     * Verifies this device non-interactively with the account's recovery key
     * (Beeper's interactive verification is unreliable — Beeper4LightOS's own
     * README says to use a recovery code instead). Restores the cross-signing
     * keys, making the device trusted; [e2eeState] then reports verified and
     * kicks the megolm key-backup restore. Bypasses Trixnity's checkRecoveryKey
     * gate (see below) — Beeper-created storage keys fail its name="" config-MAC
     * check even with the correct key, so the key is proven by actually
     * decrypting the cross-signing secrets instead.
     */
    suspend fun recoverWithKey(recoveryKey: String): Result<Unit> {
        val outcome = runCatching {
            val c = client ?: error("not logged in")
            val methods = withTimeoutOrNull(ROOM_BUDGET_MS) {
                c.verification.getSelfVerificationMethods().first()
            } ?: error("no self-verification methods available")
            if (methods !is net.folivo.trixnity.client.verification.VerificationService.SelfVerificationMethods.CrossSigningEnabled) {
                error("cross-signing is not set up on this account")
            }
            if (methods.methods
                    .none { it is net.folivo.trixnity.client.verification.SelfVerificationMethod.AesHmacSha2RecoveryKey }
            ) error("no recovery-key method available")
            // Strip everything but letters/digits (dash- or space-grouped keys,
            // pasted or typed). Case is preserved — the key is case-sensitive and
            // Trixnity's decodeRecoveryKey strips whitespace but not dashes.
            val key = recoveryKey.filter { it.isLetterOrDigit() }
            if (key.length < 48) error("recovery key needs 48 characters — got ${key.length}")

            // Beeper compatibility (2026-08-12): stock Trixnity's
            // AesHmacSha2RecoveryKey.verify() first runs checkRecoveryKey, which
            // checks the key against the storage-key config MAC derived with
            // name="". Beeper's clients created this account's storage keys with
            // the secret-name derivation, so the config MAC never matches — the
            // correct key is rejected as "expected mac … but got …" (verified
            // host-side: the key decrypts m.cross_signing.master with
            // name="m.cross_signing.master", so it is the right key). Instead of
            // that gate, prove the key the way the rest of the library uses it:
            // actually decrypt the cross-signing secrets (secret-name derivation,
            // no config-MAC check), then let KeyTrustService trust + sign the
            // device, which uploads the signatures that mark it verified.
            val keyBytes = decodeRecoveryKey(key)
            val globalAccountDataStore = c.di.get<GlobalAccountDataStore>(GlobalAccountDataStore::class)
            val keyId = globalAccountDataStore.get(DefaultSecretKeyEventContent::class).first()?.content?.key
                ?: error("no default secret-storage key")
            val keyInfo = globalAccountDataStore.get(SecretKeyEventContent::class, keyId).first()?.content
                ?: error("no secret-storage key config for $keyId")
            c.di.get<KeySecretService>(KeySecretService::class).decryptOrCreateMissingSecrets(keyBytes, keyId, keyInfo)
            c.di.get<KeyTrustService>(KeyTrustService::class)
                .checkOwnAdvertisedMasterKeyAndVerifySelf(keyBytes, keyId, keyInfo)
                .getOrThrow()
            Unit
        }
        outcome.exceptionOrNull()?.let { e ->
            // Failures were invisible in logcat (only success logged); log the
            // real detail so a rejected key shows up server-side too.
            val detail = e.message ?: e.javaClass.simpleName
            android.util.Log.w(TAG, "recoverWithKey failed: $detail")
            // The raw MAC text ("expected mac …, but got …", "bad mac") or the
            // master-key comparison after a failed decrypt ("did not match") is
            // crypto noise — surface what the user can act on instead.
            if (detail.contains("mac", ignoreCase = true) ||
                detail.contains("did not match", ignoreCase = true)
            ) {
                return Result.failure(
                    IllegalArgumentException("That recovery key doesn't match — double-check it and try again."),
                )
            }
        }
        if (outcome.isSuccess) {
            android.util.Log.d(TAG, "recoverWithKey: recovery-key verification succeeded")
            roomListDirty = true // verification changes unread suppression — refresh the list
        }
        return outcome
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
        // The verification events go out unencrypted via
        // PlaintextVerificationOlmEncryptionService (Beeper drops encrypted
        // verification events); Trixnity's own state machine drives the rest.
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
                roomListDirty = true // newly verified device → unread counts recompute
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
            inProcessSyncRunning = false
            observedClient = null
            resetVerification()
            activeRoomId = null
            pendingNotifyRoomId = null
            stopAudioPlayback()
            resetRoomList()
            ChatNotifier.clearAll(ctx)
            runCatching { old?.logout() } // API logout + clears Trixnity's store
            runCatching { old?.closeSuspending() }
            ctx.stopService(android.content.Intent(ctx, ChatSyncService::class.java))
            ctx.deleteDatabase(DB_NAME)
            ctx.cacheDir.resolve(MEDIA_DIR).deleteRecursively()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            clearDiskCache()
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
                ).getOrThrow()
            }.onFailure { e ->
                android.util.Log.w(TAG, "ensureClient: session restore failed: $e")
            }.getOrNull()
            return if (restored != null) {
                client = restored
                observeClient(restored)
                // Show the last-known chats immediately while the resolver's
                // first pass warms the store (Phase 14 disk cache).
                if (_roomList.value.isEmpty()) preloadRoomListFromDisk()
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
            // An expired session counts as logged out for the UI: the user
            // should land on the login form, not on LOG OUT.
            loggedIn = c != null && !sessionExpired,
            userId = c?.userId?.full ?: prefs?.getString(KEY_USER_ID, null),
            homeserver = prefs?.getString(KEY_HOMESERVER, null),
            loginMode = prefs?.getString(KEY_LOGIN_MODE, null),
        )
    }

    fun connectionState(): com.thelightphone.sdk.shared.LightServiceMethod.GetConnectionState.Response {
        val state = _connectionState.value
        val roomsTotal = roomListCache.size
        val roomsResolved = roomListCache.values.count { it.nameResolved }
        return com.thelightphone.sdk.shared.LightServiceMethod.GetConnectionState.Response(
            state = when (state) {
                ChatConnectionState.LoggedOut -> "logged_out"
                ChatConnectionState.Connecting -> "connecting"
                ChatConnectionState.Syncing -> "syncing"
                is ChatConnectionState.Offline -> "offline"
            },
            detail = (state as? ChatConnectionState.Offline)?.detail,
            roomsTotal = roomsTotal,
            roomsResolved = roomsResolved,
            syncEnabled = syncEnabled,
        )
    }

    /** Newest activity first — a pure read of the background-refreshed cache. */
    suspend fun getRooms(): List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room> {
        if (client == null) return emptyList()
        // The resolver seeds the cache promptly at attach and refreshes it in
        // the background; a binder call never triggers a 1284-room resolution
        // burst. On a cold process the first calls may return empty until the
        // resolver's first pass lands — the tool's refresh retries cover that.
        // The persisted list (see [preloadRoomListFromDisk]) fills the gap so
        // the tool shows the last-known chats immediately after a cold boot.
        if (_roomList.value.isEmpty()) {
            preloadRoomListFromDisk()
        }
        return _roomList.value
    }

    /**
     * A page of a room's messages (oldest first) plus whether older messages
     * exist beyond it. [hasMore] is computed from the raw timeline page, not
     * the message-filtered list, so a page full of state events or
     * still-encrypted events doesn't end pagination early. [encrypted] is set
     * without fetching when the room needs decryption the device can't do.
     */
    data class MessagesPage(
        val messages: List<com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message>,
        val hasMore: Boolean,
        val encrypted: Boolean = false,
    )

    /** One cached newest page: the page plus when it was computed. */
    private data class MessagePageEntry(
        val page: MessagesPage,
        val limit: Int,
        val refreshedAtMs: Long,
    )

    /**
     * Newest-page cache (feedback pass): re-opening a thread within the TTL is
     * a pure map read instead of a timeline re-collect + key-backup restore.
     * Recomputed in the background by [refreshMessagePage].
     */
    private val messagePageCache =
        java.util.concurrent.ConcurrentHashMap<String, MessagePageEntry>()

    /**
     * Display JPEGs served to the tool for image rows, keyed by
     * "roomId/eventId". LRU-capped — each entry is a compressed ~100-300 KB
     * display image, so the cap bounds the memory.
     */
    private val mediaCache = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_MEDIA_CACHE_ENTRIES
    }

    // --- Disk cache (Phase 14) ----------------------------------------------
    // The "instant re-open" goal: the room list and each room's newest message
    // page are persisted as JSON so a cold process (or a thread re-open after
    // the 5 s memory TTL) serves from disk immediately, while the background
    // resolver/refresher recomputes fresh data. Bounded to the newest
    // [DISK_CACHE_MAX_PAGES] rooms — the surface the user actually re-opens.

    /** Last disk-write time per cache key (room list = "", pages = roomId). */
    private val diskWriteAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun cacheDir(): java.io.File? =
        appContext?.let { java.io.File(it.filesDir, DISK_CACHE_DIR) }

    private fun roomListCacheFile(): java.io.File? =
        cacheDir()?.let { java.io.File(it, DISK_ROOM_LIST_FILE) }

    private fun messagePageCacheFile(roomId: String): java.io.File? =
        cacheDir()?.let { java.io.File(it, sanitizeFileName(roomId)) }

    /** Room ids contain `!` and `:` — both legal on ext4, but underscore them
     *  anyway so the cache dir stays portable. */
    private fun sanitizeFileName(roomId: String): String =
        roomId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    @Synchronized
    private fun saveRoomListToDisk(rooms: List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room>) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (diskWriteAt["list"] ?: 0L) < DISK_WRITE_THROTTLE_MS) return
        val file = roomListCacheFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.encodeResponse(
                    com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Response(rooms),
                ),
            )
            diskWriteAt["list"] = now
        }
    }

    @Synchronized
    private fun loadRoomListFromDisk(): List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room> {
        val file = roomListCacheFile() ?: return emptyList()
        return runCatching {
            com.thelightphone.sdk.shared.LightServiceMethod.GetRooms
                .decodeResponse(file.readText())
                .rooms
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveMessagePageToDisk(roomId: String, page: MessagesPage) {
        // A tiny/empty page is a transient artifact of the first poll (the walk
        // is still filling in past a ghost flood) — persisting it would poison
        // re-opens with a near-empty thread; the background refresh saves the
        // real page a moment later.
        if (page.messages.size < MIN_PERSISTED_PAGE_SIZE) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (diskWriteAt[roomId] ?: 0L) < DISK_WRITE_THROTTLE_MS) return
        val file = messagePageCacheFile(roomId) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.encodeResponse(
                    com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Response(
                        page.messages,
                        page.hasMore,
                        page.encrypted,
                    ),
                ),
            )
            diskWriteAt[roomId] = now
        }
        pruneMessagePageDiskCache()
    }

    @Synchronized
    private fun loadMessagePageFromDisk(roomId: String): MessagesPage? {
        val file = messagePageCacheFile(roomId) ?: return null
        return runCatching {
            val r = com.thelightphone.sdk.shared.LightServiceMethod.GetMessages
                .decodeResponse(file.readText())
            MessagesPage(r.messages, r.hasMore, r.encrypted)
        }.getOrNull()
    }

    /** Keeps the on-disk page cache to the newest [DISK_CACHE_MAX_PAGES] rooms. */
    @Synchronized
    private fun pruneMessagePageDiskCache() {
        val dir = cacheDir() ?: return
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name != DISK_ROOM_LIST_FILE }
            ?: return
        if (files.size <= DISK_CACHE_MAX_PAGES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - DISK_CACHE_MAX_PAGES)
            .forEach { it.delete() }
    }

    private fun clearDiskCache() {
        cacheDir()?.deleteRecursively()
    }

    /**
     * Seeds the in-memory caches from the persisted room list, so a cold
     * process shows the last-known chats instantly (the resolver then refreshes
     * names/previews as the store warms). Resolved flags are derived from the
     * content — a placeholder/encrypted row is treated as unresolved and gets
     * re-resolved on the next pass.
     */
    private fun preloadRoomListFromDisk() {
        val disk = loadRoomListFromDisk()
        if (disk.isEmpty()) return
        disk.forEach { room ->
            roomListCache.putIfAbsent(
                room.id,
                RoomListEntry(
                    room = room,
                    nameResolved = room.name != ROOM_NAME_PLACEHOLDER && room.name.isNotBlank(),
                    previewResolved = room.lastMessage.isNotBlank() &&
                        !room.lastMessage.startsWith("[Encrypted"),
                    previewRetryAtMs = 0L,
                ),
            )
        }
        _roomList.value = disk
        android.util.Log.d(TAG, "room list: preloaded ${disk.size} rooms from disk cache")
    }

    @Volatile
    private var activeRoomRefreshJob: Job? = null

    /** Recomputes and re-stores a room's newest page in the background. */
    private fun refreshMessagePage(roomId: String, limit: Int = THREAD_PAGE_SIZE) {
        scope.launch {
            runCatching {
                val page = computeMessagesPage(roomId, null, limit)
                messagePageCache[roomId] = MessagePageEntry(
                    page,
                    limit,
                    android.os.SystemClock.elapsedRealtime(),
                )
                saveMessagePageToDisk(roomId, page)
            }
        }
    }

    /**
     * Messages of a room, oldest first. [beforeEventId] pages further back;
     * null returns the newest [limit] messages.
     *
     * The newest page is served from the in-memory [messagePageCache] when
     * fresh — re-opening a thread is a cache read, not a timeline re-collect +
     * key-backup restore (which is what made every open slow). When the memory
     * cache is cold but a page exists on disk (e.g. re-opening a thread after
     * the TTL, or a cold process), it is served immediately and recomputed in
     * the background so the next poll is fresh — the Beeper-like "messages are
     * instantly available" behavior. Pagination ([beforeEventId] != null)
     * always reads the store directly.
     */
    suspend fun getMessages(
        roomId: String,
        beforeEventId: String?,
        limit: Int,
    ): MessagesPage {
        if (beforeEventId == null) {
            val cached = messagePageCache[roomId]
            if (cached != null &&
                cached.limit >= limit &&
                android.os.SystemClock.elapsedRealtime() - cached.refreshedAtMs < MESSAGE_PAGE_TTL_MS
            ) {
                return cached.page
            }
            // Cold (TTL expired / fresh process): serve the persisted page at
            // once and recompute in the background — the next poll is fresh.
            loadMessagePageFromDisk(roomId)?.let { disk ->
                messagePageCache[roomId] = MessagePageEntry(
                    disk,
                    limit,
                    android.os.SystemClock.elapsedRealtime(),
                )
                refreshMessagePage(roomId, limit)
                return disk
            }
            return computeMessagesPage(roomId, null, limit, fast = true).also {
                messagePageCache[roomId] = MessagePageEntry(
                    it,
                    limit,
                    android.os.SystemClock.elapsedRealtime(),
                )
                saveMessagePageToDisk(roomId, it)
            }
        }
        return computeMessagesPage(roomId, beforeEventId, limit)
    }

    // --- Bridge re-import ("ghost") detection (Phase 14.5) ------------------
    // Beeper's WhatsApp bridge occasionally re-imports room history as NEW
    // events (fresh event ids + timestamps, one megolm session) — surfacing
    // old media in threads and bumping rooms to the top of the chat list.
    // The re-import COPIES existing messages, so the reliable discriminator is
    // CONTENT: an event whose decrypted content (sender + type + body/url)
    // matches an OLDER event in the room is a copy, and real new messages
    // never duplicate older content. (Transaction ids don't discriminate:
    // real incoming messages carry them too.)
    //
    // A density fallback catches floods whose content can't be read yet
    // (still-encrypted): an event in a >=30-per-minute txn flood — a real
    // conversation almost never reaches that rate.

    /** A raw event's transaction id, or null when it has none. */
    private fun txnIdOf(te: TimelineEvent): String? = te.event.unsigned?.transactionId

    /**
     * Content signature for dedup: sender + message kind + body/url + file
     * name. Null when the content isn't readable yet (still-encrypted) — such
     * events can't be deduped and fall through to the flood rule.
     */
    private fun contentSignature(c: MatrixClient, te: TimelineEvent): String? {
        val content = te.content?.getOrNull() ?: return null
        val sender = te.event.sender.full
        return when (content) {
            is RoomMessageEventContent.TextBased -> "$sender|text|${content.body}"
            is RoomMessageEventContent.FileBased.Image ->
                "$sender|image|${content.url ?: content.file?.url}|${content.fileName ?: ""}"
            is RoomMessageEventContent.FileBased.Audio ->
                "$sender|audio|${content.url ?: content.file?.url}|${content.fileName ?: ""}"
            is RoomMessageEventContent.FileBased.Video ->
                "$sender|video|${content.url ?: content.file?.url}|${content.fileName ?: ""}"
            is RoomMessageEventContent.FileBased.File ->
                "$sender|file|${content.url ?: content.file?.url}|${content.fileName ?: ""}"
            else -> null
        }
    }

    /** Density fallback: a txn-id event inside a >=[GHOST_FLOOD_THRESHOLD]-per-minute flood. */
    private fun isFloodGhost(c: MatrixClient, te: TimelineEvent, context: List<TimelineEvent>): Boolean {
        if (txnIdOf(te) == null) return false
        val ts = te.event.originTimestamp
        var nearby = 0
        for (other in context) {
            if (other.event.id == te.event.id) continue
            if (txnIdOf(other) == null) continue
            if (kotlin.math.abs(other.event.originTimestamp - ts) < GHOST_BURST_WINDOW_MS) {
                nearby++
                if (nearby >= GHOST_FLOOD_THRESHOLD) return true
            }
        }
        return false
    }

    /**
     * Drops re-import copies from [raw] (newest first). Oldest-first: the
     * first occurrence of a content signature is kept (the original), later
     * duplicates are the copies. Flood events (density fallback) are dropped
     * regardless of content readability.
     */
    private fun filterGhosts(c: MatrixClient, raw: List<TimelineEvent>): List<TimelineEvent> {
        val seen = HashSet<String>()
        return raw.asReversed().filter { te ->
            if (isFloodGhost(c, te, raw)) return@filter false
            val sig = contentSignature(c, te) ?: return@filter true
            if (sig in seen) false else {
                seen.add(sig)
                true
            }
        }.asReversed()
    }

    /** The room's recent raw events (newest first) as ghost-burst context. */
    private suspend fun ghostContext(c: MatrixClient, matrixRoomId: RoomId): List<TimelineEvent> {
        val config: GetTimelineEventsConfig.() -> Unit = {
            this.maxSize = SEND_STATUS_WINDOW.toLong()
            fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
            decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
        }
        return withTimeoutOrNull(MESSAGES_BUDGET_MS) {
            val events = mutableListOf<TimelineEvent>()
            c.room.getLastTimelineEvents(matrixRoomId, config).filterNotNull().first()
                .collect { eventFlow ->
                    eventFlow.filterNotNull().firstOrNull()?.let { events.add(it) }
                }
            events
        } ?: emptyList()
    }

    /**
     * Raw timeline events for a message page, with bridge re-import copies
     * dropped (content dedup + flood fallback). The newest page (null cursor)
     * grows its raw window — doubling until it holds [limit] + 1 real events or
     * the timeline ends — so a 7am bridge flood is walked past instead of
     * becoming the thread's newest messages. Older pages (cursor set) are
     * filtered in place; the ghost region sits at the top of the timeline, so
     * pagination never reaches it.
     */
    private suspend fun collectRelevantTimelineEvents(
        c: MatrixClient,
        matrixRoomId: RoomId,
        beforeEventId: String?,
        limit: Int,
        fast: Boolean = false,
    ): List<TimelineEvent> {
        if (beforeEventId != null) {
            val events = collectTimelineEvents(c, matrixRoomId, beforeEventId, limit + 1)
            return filterGhosts(c, events)
        }
        // [fast] bounds the newest-page walk for the first (user-visible) poll:
        // show the newest real messages quickly; the background refresher walks
        // to [GHOST_WALK_MAX] and refines the page within a few seconds.
        val walkMax = if (fast) FIRST_POLL_GHOST_WALK_MAX else GHOST_WALK_MAX
        var window = limit + 1
        var raw = collectTimelineEvents(c, matrixRoomId, null, window)
        while (
            filterGhosts(c, raw).size < limit + 1 &&
            raw.size >= window &&
            window < walkMax
        ) {
            window = (window * 2).coerceAtMost(walkMax)
            raw = collectTimelineEvents(c, matrixRoomId, null, window)
        }
        return filterGhosts(c, raw)
    }

    /**
     * One room's newest page, computed from the timeline. Shared by the
     * [getMessages] cache path and the background [refreshMessagePage]; the
     * body itself is unchanged from the pre-cache implementation.
     */
    private suspend fun computeMessagesPage(
        roomId: String,
        beforeEventId: String?,
        limit: Int,
        fast: Boolean = false,
    ): MessagesPage {
        val c = client ?: return MessagesPage(emptyList(), false)
        val matrixRoomId = RoomId(roomId)

        // Fast path: an encrypted room on an unverified device can't decrypt —
        // say so immediately instead of fetching events and waiting on
        // decryption that can never land.
        val roomEncrypted = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getAll().first()[matrixRoomId]?.filterNotNull()?.first()?.encrypted
        } == true
        if (roomEncrypted && !isDeviceVerified(c)) {
            android.util.Log.d(TAG, "getMessages: $matrixRoomId is encrypted and device is unverified — returning immediately")
            return MessagesPage(emptyList(), false, encrypted = true)
        }

        // Lazy per-room decrypt, but seeded BEFORE the newest-page walk: the
        // walk re-reads in growing windows and each read used to wait ~3 s for
        // a decrypt that couldn't land until the megolm sessions were in the
        // store (5-7 windows × 3 s ≈ the whole first load). One small seed read
        // + session restore makes every walk read decrypt instantly.
        val seed = collectTimelineEvents(c, matrixRoomId, beforeEventId, limit + 1)
        val sessionsLoaded = restoreRoomSessions(c, matrixRoomId, seed)

        // If no megolm session could be loaded (e.g. an unverified device with
        // no key-backup access), the events stay encrypted no matter how often
        // we re-read — return the ghost-filtered seed ("[Encrypted]" rows beat
        // a 20 s dead walk).
        var events = if (sessionsLoaded > 0) {
            collectRelevantTimelineEvents(c, matrixRoomId, beforeEventId, limit, fast)
        } else {
            filterGhosts(c, seed)
        }
        repeat(DECRYPT_RETRIES) {
            if (sessionsLoaded == 0) return@repeat
            val stillEncrypted = events.any { it.content?.isFailure == true }
            if (!stillEncrypted) return@repeat
            kotlinx.coroutines.delay(DECRYPT_RETRY_DELAY_MS)
            events = collectRelevantTimelineEvents(c, matrixRoomId, beforeEventId, limit, fast)
        }

        // A full page (limit + 1, including the cursor event for older pages)
        // means older messages exist beyond this one. Ghosts don't count — the
        // newest-page walk guarantees this size when real history exists.
        val hasMore = events.size >= limit + 1
        android.util.Log.d(
            TAG,
            "getMessages: room=$matrixRoomId before=$beforeEventId limit=$limit page=${events.size} hasMore=$hasMore",
        )

        val result = mutableListOf<com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message>()
        val startIndex = if (beforeEventId == null) 0 else 1 // drop the cursor event
        // Delivery status only matters for the newest page (what the user just
        // sent): the status events sit right after the message in the timeline.
        // Older pages don't get the marker (Phase 10, minimal scope).
        val sendStatuses = if (beforeEventId == null) sendStatusByEventId(c, matrixRoomId) else emptyMap()
        // Same scoping for read receipts: only the newest page reports whether
        // the other party has read an outgoing message (receipts point at the
        // newest events; an older page's messages are always "read" in practice
        // but re-resolving each receipt per page isn't worth it).
        val readEventIds = if (beforeEventId == null) readReceiptsByEvent(c, matrixRoomId, events) else emptySet()
        // Reactions, same newest-page scope: m.reaction events sit after their
        // target in the timeline, so the newest window carries the reactions
        // that matter. Older pages report none (minimal Phase 14 scope).
        val reactionsByEvent = if (beforeEventId == null) reactionLabelsByEvent(c, matrixRoomId) else emptyMap()
        for (i in startIndex until events.size) {
            if (result.size >= limit) break
            messageFrom(
                c,
                matrixRoomId,
                events[i],
                sendStatuses[events[i].event.id.full],
                read = events[i].event.id.full in readEventIds,
                reactions = reactionsByEvent[events[i].event.id.full].orEmpty(),
            )?.let { result.add(it) }
        }
        // Optimistic voice-note row (feedback 2026-08-13): a voice note sent
        // from this device shows immediately as a "Voice note" row — the sync
        // echo (which can take a full tick on a big account) replaces it.
        pendingAudioEcho[roomId]?.let { pending ->
            val echoed = events.any { txnIdOf(it) == pending.txnId }
            if (echoed) {
                pendingAudioEcho.remove(roomId)
            } else if (beforeEventId == null && result.size < limit + 1) {
                result.add(
                    com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
                        id = "local-${pending.txnId}",
                        sender = c.userId.full,
                        senderName = "",
                        body = "Voice note",
                        timestampMs = pending.timestampMs,
                        isMine = true,
                        contentType = "audio",
                        durationMs = pending.durationMs,
                    ),
                )
            }
        }
        // Optimistic text row (feedback 2026-08-14): same pattern as the voice
        // echo above — a just-sent message shows in every newest page (even a
        // re-opened thread) until its sync echo lands. The row id matches the
        // tool's "local-<txn>" id, so the tool's own pending row dedupes.
        pendingTextEcho[roomId]?.let { pending ->
            val echoed = events.any { txnIdOf(it) == pending.txnId }
            if (echoed) {
                pendingTextEcho.remove(roomId)
            } else if (beforeEventId == null && result.size < limit + 1) {
                result.add(
                    com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
                        id = "local-${pending.txnId}",
                        sender = c.userId.full,
                        senderName = "",
                        body = pending.body,
                        timestampMs = pending.timestampMs,
                        isMine = true,
                    ),
                )
            }
        }
        return MessagesPage(messages = result.reversed(), hasMore = hasMore) // oldest first
    }

    /** Type of Beeper's per-message delivery-state events (unencrypted, posted
     *  by the bridge right after the message). */
    private val BEEPER_SEND_STATUS_EVENT_TYPE = "com.beeper.message_send_status"

    /**
     * Latest Beeper send status per message event id, from the room's most
     * recent [BEEPER_SEND_STATUS_EVENT_TYPE] events (newest-first window, so the
     * first status seen for a message is the latest). Reads the raw content —
     * the events are unencrypted, so no decrypt wait.
     */
    private suspend fun sendStatusByEventId(
        c: MatrixClient,
        matrixRoomId: RoomId,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val config: GetTimelineEventsConfig.() -> Unit = {
            this.maxSize = SEND_STATUS_WINDOW.toLong()
            fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
            decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
        }
        val collected = withTimeoutOrNull(MESSAGES_BUDGET_MS) {
            val statusEvents = mutableListOf<net.folivo.trixnity.client.store.TimelineEvent>()
            c.room.getLastTimelineEvents(matrixRoomId, config).filterNotNull().first()
                .collect { eventFlow ->
                    eventFlow.filterNotNull().firstOrNull()?.let { statusEvents.add(it) }
                }
            statusEvents
        } ?: return emptyMap()
        for (te in collected) {
            val content = te.event.content
            if (content !is UnknownEventContent || content.eventType != BEEPER_SEND_STATUS_EVENT_TYPE) continue
            val raw = content.raw
            val status = raw["status"]?.jsonPrimitive?.contentOrNull ?: continue
            val relatedEventId = raw["m.relates_to"]?.jsonObject?.get("event_id")?.jsonPrimitive?.contentOrNull
                ?: content.relatesTo?.eventId?.full
                ?: continue
            // Newest-first: the first status for a message is the latest one.
            if (relatedEventId !in result) result[relatedEventId] = status
        }
        return result
    }

    /**
     * Reaction labels per message event id, from the room's recent `m.reaction`
     * events (newest-first window, like [sendStatusByEventId]). Reactions are
     * never encrypted (per the Matrix spec), so the raw event content reads
     * directly — no decrypt wait. A reaction's `m.relates_to` carries the
     * target event id + the reaction key (an emoji). Each entry is a display
     * string — "Name reacted with ❤️" (own reactions read "You reacted with
     * ❤️") — deduped per (sender, key) so a re-reaction doesn't repeat
     * (feedback 2026-08-14).
     */
    private suspend fun reactionLabelsByEvent(
        c: MatrixClient,
        matrixRoomId: RoomId,
    ): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        val config: GetTimelineEventsConfig.() -> Unit = {
            this.maxSize = SEND_STATUS_WINDOW.toLong()
            fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
            decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
        }
        val collected = withTimeoutOrNull(MESSAGES_BUDGET_MS) {
            val reactionEvents = mutableListOf<net.folivo.trixnity.client.store.TimelineEvent>()
            c.room.getLastTimelineEvents(matrixRoomId, config).filterNotNull().first()
                .collect { eventFlow ->
                    eventFlow.filterNotNull().firstOrNull()?.let { reactionEvents.add(it) }
                }
            reactionEvents
        } ?: return emptyMap()
        val seen = HashSet<String>() // "sender|key", dedupes re-reactions
        for (te in collected) {
            val content = te.content?.getOrNull() ?: (te.event.content as? ReactionEventContent)
            if (content !is ReactionEventContent) continue
            val relates = content.relatesTo as? RelatesTo.Annotation ?: continue
            val targetId = relates.eventId.full
            val key = relates.key?.takeIf { it.isNotBlank() } ?: continue
            val sender = te.event.sender
            if (!seen.add("${sender.full}|$key")) continue
            val who = if (sender == c.userId) "You" else senderNameOf(c, matrixRoomId, sender)
            result.getOrPut(targetId) { mutableListOf() }.add("$who reacted with $key")
        }
        return result
    }

    /**
     * Which raw-timeline event ids the other room members have read (their
     * m.read receipts), as a set of event ids. Newest-first index math: a
     * receipt pointing at raw index r covers every event at index >= r — all
     * events at-or-older than the receipt, chronologically up to it. Events
     * newer than the receipt stay unread. Receipts pointing outside the page
     * (older than its oldest event) cover nothing here — they describe an
     * earlier read position.
     */
    private suspend fun readReceiptsByEvent(
        c: MatrixClient,
        matrixRoomId: RoomId,
        events: List<TimelineEvent>,
    ): Set<String> {
        val rawIndex = HashMap<String, Int>() // event id -> newest-first index
        events.forEachIndexed { i, te -> rawIndex[te.event.id.full] = i }
        val receiptsByUser = withTimeoutOrNull(MESSAGES_BUDGET_MS) {
            // The Room-backed repositories only work inside a store transaction
            // (the flow APIs set it up themselves; direct repo reads need the
            // explicit scope, or Room answers "read transaction is missing").
            val txManager = c.di.get<RepositoryTransactionManager>(RepositoryTransactionManager::class)
            txManager.readTransaction {
                c.di.get<RoomUserReceiptsRepository>(RoomUserReceiptsRepository::class).get(matrixRoomId)
            }
        } ?: return emptySet()
        val readEventIds = mutableSetOf<String>()
        for ((userId, roomUserReceipts) in receiptsByUser) {
            if (userId == c.userId) continue
            val receiptIndex = roomUserReceipts.receipts[ReceiptType.Read]?.eventId?.full
                ?.let { rawIndex[it] } ?: continue
            for ((eventId, index) in rawIndex) {
                if (index >= receiptIndex) readEventIds.add(eventId)
            }
        }
        return readEventIds
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
            // Decryption is all-or-nothing per room: if the first event can't
            // decrypt (no megolm session / unverified device), the rest can't
            // either — don't burn DECRYPT_WAIT_MS per event on an undecryptable
            // room (31 events × 3s blows the whole fetch budget). Once one
            // event decrypts, the session is loaded and the rest follow fast.
            var waitForDecrypt = true
            eventFlows.collect { eventFlow ->
                // Reading a timeline triggers decryption; the event flow re-emits
                // once the content resolves (first emission may carry null or a
                // failure while the decrypt is pending), so wait for the
                // decrypted emission (bounded), falling back to the first.
                val resolved = withTimeoutOrNull(if (waitForDecrypt) DECRYPT_WAIT_MS else QUICK_DECRYPT_WAIT_MS) {
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
                    waitForDecrypt = resolved.content?.getOrNull() != null ||
                        resolved.event.content !is EncryptedMessageEventContent
                    events.add(resolved)
                }
            }
        }
        // Even if the budget expires mid-collect, hand back what we got (the
        // encrypted events are still worth showing — "[Encrypted]" is more
        // useful than an empty thread).
        return events
    }

    /** Whether this device is cross-signing verified (so E2EE rooms can decrypt). */
    private suspend fun isDeviceVerified(c: MatrixClient): Boolean {
        val trust = withTimeoutOrNull(KEY_BACKUP_VERIFY_TIMEOUT_MS) {
            c.key.getTrustLevel(c.userId, c.deviceId).firstOrNull()
        }
        return trust is net.folivo.trixnity.crypto.key.DeviceTrustLevel.CrossSigned && trust.verified
    }

    /** Loads the megolm sessions for the given events' undecrypted content from
     * the key backup. Returns how many sessions were loaded — 0 means the
     * account has no usable backup key (e.g. an unverified device), so the
     * caller can skip pointless decrypt retries.
     *
     * Never blocks a getMessages call: an unverified device skips the restore
     * outright (it has no backup key) and each load is bounded by a timeout
     * (loadMegolmSession can hang waiting for a key that never arrives).
     */
    private suspend fun restoreRoomSessions(
        c: MatrixClient,
        matrixRoomId: RoomId,
        events: List<TimelineEvent>,
    ): Int {
        val sessionIds = events.mapNotNull { te ->
            // Only events that failed to decrypt need a key-backup restore:
            // already-resolved events have their megolm session in the local
            // store. Loading every session id of the page from the backup was
            // the slow path — a network round-trip (up to
            // KEY_BACKUP_LOAD_TIMEOUT_MS each) on every open, even when
            // nothing needed restoring.
            val content = te.content
            if (content?.getOrNull() != null) null
            else (te.event.content as? EncryptedMessageEventContent.MegolmEncryptedMessageEventContent)?.sessionId
        }.distinct()
        android.util.Log.d(TAG, "restoreRoomSessions: $matrixRoomId — ${events.size} events, ${sessionIds.size} megolm sessions, " +
            "encrypted classes: ${events.map { it.event.content::class.simpleName }.distinct()}")
        if (sessionIds.isEmpty()) return 0

        // The key backup is only reachable once the device is verified — check
        // first so an unverified device returns instantly instead of hanging.
        if (!isDeviceVerified(c)) {
            android.util.Log.d(TAG, "restoreRoomSessions: device not verified — no key-backup access, skipping restore")
            return 0
        }

        val keyBackup = runCatching {
            c.di.get<net.folivo.trixnity.client.key.KeyBackupService>(
                org.koin.core.qualifier.named<net.folivo.trixnity.client.key.KeyBackupService>(),
            )
        }.getOrNull()
        if (keyBackup == null) {
            android.util.Log.e(TAG, "restoreRoomSessions: KeyBackupService not available via DI")
            return 0
        }
        var loaded = 0
        sessionIds.forEach { sessionId ->
            try {
                val ok = withTimeoutOrNull(KEY_BACKUP_LOAD_TIMEOUT_MS) {
                    keyBackup.loadMegolmSession(matrixRoomId, sessionId)
                }
                if (ok != null) loaded++ else {
                    android.util.Log.w(TAG, "restoreRoomSessions: loadMegolmSession timed out for $matrixRoomId / $sessionId")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "restoreRoomSessions: loadMegolmSession failed for $matrixRoomId / $sessionId: ${e.message}")
            }
        }
        android.util.Log.d(TAG, "restoreRoomSessions: loaded $loaded/${sessionIds.size} sessions for $matrixRoomId")
        return loaded
    }

    suspend fun sendMessage(
        roomId: String,
        body: String,
        replyToEventId: String?,
    ): com.thelightphone.sdk.shared.LightServiceMethod.SendMessage.Response {
        val c = client ?: error("not logged in")
        val matrixRoomId = RoomId(roomId)
        // Rotate the outbound megolm session so the room key is re-shared with
        // every device on each message. The session previously created for this
        // room predates the bridge's olm session (its /keys/claim failed at
        // creation — Beeper omits `failures`, which broke Trixnity's claim until
        // the engine fix), so the bridge never got the key and reported every
        // message undecryptable. A fresh session re-runs the share flow:
        // keys/claim → olm session → sendToDevice, so WhatsApp/Instagram bridges
        // always receive the key.
        runCatching {
            c.di.get<OlmCryptoStore>(OlmCryptoStore::class)
                .updateOutboundMegolmSession(matrixRoomId) { null }
        }
        val txnId = c.room.sendMessage(matrixRoomId) {
            if (replyToEventId != null) {
                val replyEvent = c.room.getTimelineEvent(matrixRoomId, EventId(replyToEventId)).firstOrNull()
                if (replyEvent != null) reply(replyEvent)
            }
            text(body = body)
        }
        // Record the optimistic echo server-side and return immediately —
        // Trixnity's outbox sends in the background (encrypt + ack), so the
        // composer pops back the moment the message is enqueued instead of
        // sitting on "Sending…" while the homeserver acks (feedback
        // 2026-08-14). The echo row survives leaving the thread; the sync
        // echo (matched by txn id) replaces it in [computeMessagesPage].
        pendingTextEcho[matrixRoomId.full] = PendingTextSend(txnId, System.currentTimeMillis(), body)
        // The cached newest page is stale now; the active-room refresher (or
        // the tool's next poll) recomputes it once the echo lands. The disk
        // page is dropped too, so a cold re-open can't serve a pre-send page.
        messagePageCache.remove(matrixRoomId.full)
        messagePageCacheFile(matrixRoomId.full)?.delete()
        roomListDirty = true // the room's preview/unread change once the echo lands
        android.util.Log.d(TAG, "SendMessage: room=$roomId txn=$txnId body=$body")
        return com.thelightphone.sdk.shared.LightServiceMethod.SendMessage.Response(
            transactionId = txnId,
            eventId = null, // not awaited — the sync echo supplies the real id
        )
    }

    // --- Photos (Phase 13) --------------------------------------------------

    /**
     * Records the room a photo attach should land in and returns the
     * flattened component name of the companion's photo-picker activity, which
     * the tool launches via `SimpleLightScreen.startServerActivity` (the tool
     * runtime forbids startActivity; the companion can't launch activities
     * from the background). The activity shows the system photo picker, then
     * uploads and sends the chosen photo in [roomId] itself.
     */
    fun startPhotoSend(roomId: String): String {
        PhotoSendActivity.register(roomId)
        return PHOTO_PICKER_ACTIVITY
    }

    /** A photo ready to send: compressed JPEG + metadata for the Matrix event. */
    data class PhotoPayload(
        val jpeg: ByteArray,
        val fileName: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
    )

    /**
     * Uploads and sends a photo (a compressed [PhotoPayload]) to the room.
     * Uses Trixnity's image DSL, which uploads *encrypted* media when the room
     * is end-to-end encrypted (WhatsApp/Beeper) and plain media otherwise.
     * @return true when the send was acked by the homeserver.
     */
    suspend fun sendPhoto(
        roomId: String,
        payload: PhotoPayload,
    ): Boolean {
        val c = client ?: return false
        val matrixRoomId = RoomId(roomId)
        // Same megolm-session rotation as text sends — the bridge must get the
        // fresh room key for the media event to decrypt on the other side.
        runCatching {
            c.di.get<OlmCryptoStore>(OlmCryptoStore::class)
                .updateOutboundMegolmSession(matrixRoomId) { null }
        }
        val txnId = runCatching {
            c.room.sendMessage(matrixRoomId) {
                image(
                    body = payload.fileName,
                    image = flowOf(payload.jpeg),
                    fileName = payload.fileName,
                    type = runCatching { ContentType.parse(payload.mimeType) }.getOrNull(),
                    size = payload.jpeg.size.toLong(),
                    width = payload.width,
                    height = payload.height,
                )
            }
        }.getOrNull() ?: return false
        android.util.Log.d(TAG, "SendPhoto: room=$roomId txn=$txnId bytes=${payload.jpeg.size}")
        messagePageCache.remove(matrixRoomId.full)
        return true
    }

    // --- Voice notes (Phase 14) ---------------------------------------------

    /**
     * Toggles playback of an m.audio message: stops any current playback and
     * plays [eventId], or stops it if it is already the one playing. Downloads
     * the audio (decrypting when the room is encrypted — the timeline content
     * carries the EncryptedFile) to a cache file and plays it with a plain
     * [android.media.MediaPlayer]; playback lives in the companion because the
     * tool runtime forbids media APIs. @return (playing, error).
     */
    suspend fun playVoiceNote(roomId: String, eventId: String): Pair<Boolean, String?> {
        val c = client ?: return false to "not logged in"
        // Tap the playing row again → stop (toggle semantics).
        if (playingAudioEventId == eventId) {
            stopAudioPlayback()
            return false to null
        }
        stopAudioPlayback()
        val matrixRoomId = RoomId(roomId)
        val te = withTimeoutOrNull(MEDIA_BUDGET_MS) {
            var event: TimelineEvent? = null
            repeat(MEDIA_CONTENT_RETRIES) {
                event = c.room.getTimelineEvent(matrixRoomId, EventId(eventId)).firstOrNull()
                if (event?.content?.getOrNull() != null) return@withTimeoutOrNull event
                delay(MEDIA_CONTENT_RETRY_DELAY_MS)
            }
            event
        }
        val content = te?.content?.getOrNull()
        if (content !is RoomMessageEventContent.FileBased.Audio) {
            return false to "not an audio message"
        }
        val file = content.file?.takeIf { !it.url.isNullOrBlank() }
        val url = content.url?.takeIf { it.isNotBlank() }
        if (file == null && url == null) return false to "no audio file"
        val mediaService = c.di.get<MediaService>(MediaService::class)
        val bytes = withTimeoutOrNull(MEDIA_BUDGET_MS) {
            when {
                file != null -> mediaService.getEncryptedMedia(file, saveToCache = false)
                url != null -> mediaService.getMedia(url, saveToCache = false)
                else -> return@withTimeoutOrNull null
            }
        }?.getOrNull()?.toByteArray()?.takeIf { it.isNotEmpty() }
            ?: return false to "audio download failed"
        val ctx = appContext ?: return false to "no context"
        // The temp file must carry the ACTUAL format: MediaPlayer's file-source
        // path uses the extension as an extractor hint, and Beeper/WhatsApp
        // audio files (ogg/opus, mp3, aac…) mislabeled ".m4a" fail to prepare
        // (2026-08-13: an audio-file voice note played everywhere but the LP3).
        val mime = content.info?.mimeType?.lowercase().orEmpty()
        val ext = when {
            "ogg" in mime || "opus" in mime -> "ogg"
            "mpeg" in mime -> "mp3"
            "mp4" in mime || "m4a" in mime -> "m4a"
            "aac" in mime -> "aac"
            "amr" in mime -> "amr"
            "wav" in mime -> "wav"
            "flac" in mime -> "flac"
            else -> "m4a"
        }
        val tmp = java.io.File(ctx.cacheDir, "voice_$eventId.$ext")
        runCatching { tmp.writeBytes(bytes) }.getOrElse { return false to "audio write failed" }
        // MediaPlayer hands the file to the media server (a different uid) —
        // an app-private 600 file gets "Permission denied" on the real LP3
        // (the emulator's in-process media stack hid this).
        tmp.setReadable(true, false)
        val player = android.media.MediaPlayer()
        // Explicit media/speech classification + transient focus: playback
        // follows the media volume (the hardware rocker controls it) and stops
        // on focus loss — the default attributes let some builds route voice
        // notes to a stream the volume buttons don't touch (feedback
        // 2026-08-14: "can't hear voice notes", "volume toggle does nothing").
        val mediaAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        player.setAudioAttributes(mediaAttributes)
        player.setOnCompletionListener { stopAudioPlayback() }
        player.setOnErrorListener { _, _, _ -> stopAudioPlayback(); true }
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val focusRequest = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )
            .setAudioAttributes(mediaAttributes)
            .setOnAudioFocusChangeListener { change ->
                // Focus loss (another app / a call) stops the note; the row
                // state follows via the poll's audioPlayingEventId.
                if (change != android.media.AudioManager.AUDIOFOCUS_GAIN) {
                    mainHandler.post { stopAudioPlayback() }
                }
            }
            .build()
        audioManager.requestAudioFocus(focusRequest)
        audioFocusRequest = focusRequest
        runCatching {
            player.setDataSource(tmp.absolutePath)
            player.prepare()
            player.start()
        }.onFailure { e ->
            android.util.Log.w(TAG, "playVoiceNote: play failed for $eventId", e)
            runCatching { player.release() }
            tmp.delete()
            return false to "playback failed"
        }
        audioPlayer = player
        audioPlayerFile = tmp
        playingAudioEventId = eventId
        android.util.Log.d(TAG, "playVoiceNote: playing $eventId (${bytes.size} bytes)")
        return true to null
    }

    /** Stops any in-flight voice-note playback and clears its state. */
    private fun stopAudioPlayback() {
        playingAudioEventId = null
        runCatching { audioPlayer?.stop() }
        runCatching { audioPlayer?.release() }
        audioPlayer = null
        audioPlayerFile?.delete()
        audioPlayerFile = null
        audioFocusRequest?.let { focus ->
            runCatching {
                (appContext?.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                    .abandonAudioFocusRequest(focus)
            }
            audioFocusRequest = null
        }
    }

    /**
     * Records the room a voice-note send should land in and returns the
     * flattened component name of the companion's recording activity, which
     * the tool launches via `SimpleLightScreen.startServerActivity` (same
     * pattern as [startPhotoSend]). The activity records an m4a and sends it.
     */
    fun startVoiceNoteSend(roomId: String): String {
        VoiceNoteActivity.register(roomId)
        return VOICE_NOTE_ACTIVITY
    }

    /**
     * Uploads and sends a recorded voice note (an m4a file) to the room —
     * encrypted media when the room is end-to-end encrypted (WhatsApp/Beeper),
     * plain otherwise. The content is hand-built as an
     * [RoomMessageEventContent.Unknown] (whose raw JSON is sent verbatim) so
     * it can carry the `org.matrix.msc3245.voice` marker: the WhatsApp bridge
     * renders an m.audio as a WhatsApp *voice note* only when that key is
     * present, otherwise WhatsApp shows a plain audio file (feedback
     * 2026-08-14). Trixnity's typed audio DSL has no extension slot, so the
     * upload is done here (same encrypted/plain split the DSL performs).
     * @return true when the send was enqueued.
     */
    suspend fun sendVoiceNote(roomId: String, file: java.io.File): Boolean {
        val c = client ?: return false
        val matrixRoomId = RoomId(roomId)
        // Same megolm-session rotation as text/photo sends — the bridge must
        // get the fresh room key for the audio event to decrypt on the other
        // side.
        runCatching {
            c.di.get<OlmCryptoStore>(OlmCryptoStore::class)
                .updateOutboundMegolmSession(matrixRoomId) { null }
        }
        val bytes = file.readBytes()
        val durationMs = runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull()
            retriever.release()
            ms
        }.getOrNull()
        val mediaService = c.di.get<MediaService>(MediaService::class)
        val mimeType = "audio/mp4"
        val isEncryptedRoom = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).first()?.encrypted
        } == true
        val json = c.di.get<Json>()
        val raw = if (isEncryptedRoom) {
            val encryptedFile = mediaService.prepareUploadEncryptedMedia(flowOf(bytes))
            buildJsonObject {
                put("msgtype", "m.audio")
                put("body", "")
                put("filename", "voice.m4a")
                putJsonObject("info") {
                    put("mimetype", mimeType)
                    put("size", bytes.size)
                    if (durationMs != null) put("duration", durationMs)
                }
                putJsonObject("org.matrix.msc3245.voice") {}
                put("file", json.parseToJsonElement(json.encodeToString(EncryptedFile.serializer(), encryptedFile)))
            }
        } else {
            val url = mediaService.prepareUploadMedia(
                flowOf(bytes),
                runCatching { io.ktor.http.ContentType.parse(mimeType) }.getOrNull(),
            )
            buildJsonObject {
                put("msgtype", "m.audio")
                put("body", "")
                put("filename", "voice.m4a")
                putJsonObject("info") {
                    put("mimetype", mimeType)
                    put("size", bytes.size)
                    if (durationMs != null) put("duration", durationMs)
                }
                putJsonObject("org.matrix.msc3245.voice") {}
                put("url", url)
            }
        }
        val content = RoomMessageEventContent.Unknown(
            type = RoomMessageEventContent.FileBased.Audio.TYPE,
            body = "",
            raw = raw,
        )
        val txnId = runCatching {
            c.room.sendMessage(matrixRoomId) {
                // Empty body: other clients render the body as the caption,
                // and Beeper/WhatsApp showed "Voice note" as a text message
                // (feedback 2026-08-13) — voice notes carry no caption.
                content(content)
            }
        }.getOrNull() ?: return false
        android.util.Log.d(TAG, "SendVoiceNote: room=$roomId txn=$txnId bytes=${bytes.size} duration=${durationMs}ms voice=m.audio+msc3245")
        // Invalidate the page caches so the thread doesn't serve the pre-send
        // page, and show an optimistic "Voice note" row until the sync echo lands.
        messagePageCache.remove(matrixRoomId.full)
        messagePageCacheFile(matrixRoomId.full)?.delete()
        pendingAudioEcho[matrixRoomId.full] = PendingAudioSend(txnId, System.currentTimeMillis(), durationMs)
        return true
    }

    /**
     * Display-ready JPEG for an image message: reads the event from the store,
     * downloads its media (decrypting when the room is encrypted — the
     * timeline content carries the EncryptedFile), compresses to a
     * binder-friendly size, and caches the result. Null when the event isn't
     * an image, the media can't be fetched yet (e.g. still-encrypted), or —
     * with [allowMobileData] false — the device is on the cellular connection
     * (Settings → Mobile data downloads; the tool shows the row's text
     * fallback until Wi-Fi or the toggle flips).
     */
    suspend fun getMessageMedia(
        roomId: String,
        eventId: String,
        allowMobileData: Boolean,
    ): ByteArray? {
        val c = client ?: return null
        val cacheKey = "$roomId/$eventId"
        // The cache is local — serve it regardless of the connection state.
        mediaCache[cacheKey]?.let { return it }
        if (!allowMobileData && isOnCellularData()) {
            android.util.Log.d(TAG, "getMessageMedia: $eventId skipped (mobile data, allow=$allowMobileData)")
            return null
        }
        val matrixRoomId = RoomId(roomId)
        // The event's content can lag its first read on encrypted rooms (the
        // decrypt is async) — re-read a few times before giving up, or the row
        // stays on its text fallback even though the media exists.
        val te = withTimeoutOrNull(MEDIA_BUDGET_MS) {
            var event: TimelineEvent? = null
            repeat(MEDIA_CONTENT_RETRIES) {
                event = c.room.getTimelineEvent(matrixRoomId, EventId(eventId)).firstOrNull()
                if (event?.content?.getOrNull() != null) return@withTimeoutOrNull event
                delay(MEDIA_CONTENT_RETRY_DELAY_MS)
            }
            event
        }
        val content = te?.content?.getOrNull()
        if (content !is RoomMessageEventContent.FileBased.Image) return null
        // Some clients post m.image events with an empty url (a failed upload,
        // or a bot artifact) — those have no media to fetch, ever. Blanking the
        // uri here keeps the fetch branch below from calling
        // MediaService.getMedia("") (IllegalArgumentException, which used to
        // fail the whole row instead of falling back to its text).
        val file = content.file?.takeIf { !it.url.isNullOrBlank() }
        val url = content.url?.takeIf { it.isNotBlank() }
        if (file == null && url == null) {
            android.util.Log.d(TAG, "getMessageMedia: $eventId has no media uri (url=$url) — unfetchable")
            return null
        }
        // Media this device already has (sent from here, or previously
        // downloaded) renders on any connection — the mobile-data gate only
        // blocks downloads that would hit the network.
        val mediaStore = c.di.get<MediaStore>(MediaStore::class)
        val localUri = file?.url ?: url
        val mediaIsLocal = localUri != null && withTimeoutOrNull(ROOM_BUDGET_MS) {
            mediaStore.getMedia(localUri)
        } != null
        if (!allowMobileData && isOnCellularData() && !mediaIsLocal) {
            android.util.Log.d(TAG, "getMessageMedia: $eventId skipped (mobile data, allow=$allowMobileData)")
            return null
        }
        val mediaService = c.di.get<MediaService>(MediaService::class)
        val bytes = withTimeoutOrNull(MEDIA_BUDGET_MS) {
            val result = when {
                file != null -> mediaService.getEncryptedMedia(file, saveToCache = true)
                url != null -> mediaService.getMedia(url, saveToCache = true)
                else -> return@withTimeoutOrNull null
            }
            if (result.isFailure) {
                android.util.Log.w(TAG, "getMessageMedia: fetch failed for $eventId", result.exceptionOrNull())
            }
            result.getOrNull()?.toByteArray()
        }?.takeIf { it.isNotEmpty() } ?: return null
        val jpeg = compressImage(bytes, DISPLAY_MAX_DIMENSION, DISPLAY_JPEG_QUALITY) ?: return null
        mediaCache[cacheKey] = jpeg
        return jpeg
    }

    /** Whether the active network connection is cellular (mobile data). */
    private fun isOnCellularData(): Boolean {
        val context = appContext ?: return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * Downscales + re-encodes an image to a JPEG no larger than [maxDimension]
     * on its longest side — the tool's display rows and the sent-photo upload
     * both stay small (binder-friendly and bandwidth-friendly).
     */
    fun compressImage(bytes: ByteArray, maxDimension: Int, quality: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim <= 0) return null
        var sample = 1
        while (maxDim / (sample * 2) >= maxDimension) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        if (!bitmap.isRecycled) bitmap.recycle()
        return output.toByteArray()
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
        roomListDirty = true // the room's unread count changed
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
        // While a thread is open, keep its cached newest page fresh in the
        // background — sync echoes and Beeper send-status events then reach the
        // tool's next poll without it blocking on a compute.
        activeRoomRefreshJob?.cancel()
        activeRoomRefreshJob = if (roomId != null) {
            scope.launch {
                while (true) {
                    delay(ACTIVE_ROOM_REFRESH_MS)
                    if (activeRoomId != roomId) break
                    refreshMessagePage(roomId)
                }
            }
        } else {
            null
        }
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
                                    // Resolver skip-gate signal (efficiency audit
                                    // 2026-08-14): any room-state change (message,
                                    // unread count, membership) wakes the
                                    // room-list resolver instead of its old
                                    // unconditional 2 s pass loop.
                                    val sig = RoomSig(
                                        updated.lastRelevantEventId?.full,
                                        updated.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L,
                                        updated.unreadMessageCount,
                                        updated.membership?.name,
                                    )
                                    if (roomSigSeen[key] != sig) {
                                        roomSigSeen[key] = sig
                                        roomListDirty = true
                                    }
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
        // Bridge re-import floods (the 7am wall) must not notify — a real
        // conversation almost never reaches 30 messages per minute, so the
        // density fallback skips the flood without touching real messages.
        if (isFloodGhost(c, te, ghostContext(c, roomId))) {
            android.util.Log.d(TAG, "notifyForEvent: skipping bridge-flood event $eventId in $roomId")
            return
        }
        // A new message means the user may open this thread. Invalidate the
        // page caches synchronously (an immediate open must recompute, not
        // serve the pre-message page from disk), then warm the newest page in
        // the background (debounced).
        messagePageCache.remove(roomId.full)
        messagePageCacheFile(roomId.full)?.delete()
        warmRoomPage(roomId.full)
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

    /**
     * Background-warms a room's newest page (fast walk) so the next open is a
     * cache hit. Debounced per room — a burst of messages triggers one compute;
     * the active room is skipped (its page already refreshes every 2 s).
     */
    private val roomWarmAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun warmRoomPage(roomKey: String) {
        if (activeRoomId == roomKey) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = roomWarmAt[roomKey] ?: 0L
        if (now - last < ROOM_WARM_DEBOUNCE_MS) return
        roomWarmAt[roomKey] = now
        scope.launch {
            runCatching {
                val page = computeMessagesPage(roomKey, null, THREAD_PAGE_SIZE, fast = true)
                messagePageCache[roomKey] = MessagePageEntry(
                    page, THREAD_PAGE_SIZE, android.os.SystemClock.elapsedRealtime(),
                )
                saveMessagePageToDisk(roomKey, page)
            }
        }
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

    /** Cached ghost-aware "last event" per room (see [effectiveLastEvent]). */
    private data class EffectiveLast(
        /** The server's lastRelevantEventId this was computed for. */
        val serverLastEventId: String,
        /** Newest NON-ghost event (id, timestamp) in the room. */
        val effectiveEventId: String?,
        val effectiveTs: Long,
        /** When a timed-out walk may be retried (0 = resolved, cache forever). */
        val retryAtMs: Long = 0L,
    )

    private val effectiveLastCache = java.util.concurrent.ConcurrentHashMap<String, EffectiveLast>()

    private val roomListCache = java.util.concurrent.ConcurrentHashMap<String, RoomListEntry>()
    /** Last-seen room signatures, maintained by [observeNotifications] — a
     *  difference sets [roomListDirty] (the resolver's skip gate, audit 2026-08-14). */
    private val roomSigSeen = java.util.concurrent.ConcurrentHashMap<String, RoomSig>()
    private val _roomList = MutableStateFlow<List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room>>(emptyList())

    @Volatile
    private var roomListJob: Job? = null

    /** Dirty flag for the room-list resolver (efficiency audit 2026-08-14):
     *  a full pass runs only when [observeNotifications] saw a room-state change
     *  or a parked resolution retry came due — previously every 2 s, 24/7 (the
     *  overnight CPU/IO drain).
     */
    @Volatile
    private var roomListDirty = true

    /** The room-map fields that decide whether a resolver pass can be skipped. */
    private data class RoomSig(
        val lastEventId: String?,
        val lastTsMs: Long,
        val unreadCount: Long,
        val membership: String?,
    )

    private fun resetRoomList() {
        roomListCache.clear()
        roomSigSeen.clear()
        effectiveLastCache.clear()
        ghostResolveInFlight.clear()
        _roomList.value = emptyList()
        roomListJob?.cancel()
        roomListJob = null
        messagePageCache.clear()
        activeRoomRefreshJob?.cancel()
        activeRoomRefreshJob = null
    }

    /**
     * Background resolver for the room list. Runs continuously while a client
     * is attached: seeds every room with a placeholder row first (so the list
     * shows instantly), then resolves names + previews newest-first within a
     * per-pass time budget, publishing the snapshot after each pass.
     * Since 2026-08-14 a pass runs only when [observeNotifications] observed a
     * room-state change (see [roomListDirty] / [hasPendingResolveWork]) instead
     * of every 2 s, 24/7 (the standby CPU/IO drain, efficiency audit).
     */

    /**
     * True when a parked room-list entry has resolution work that just came
     * due: an unresolved "[Encrypted]" preview past its retry time, or a
     * ghost-walk retry window that expired. Parked (future-retry) entries
     * don't count — they'd otherwise force a pass every tick on accounts with
     * many still-encrypted rooms.
     */
    private fun hasPendingResolveWork(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        for (e in roomListCache.values) {
            if (!e.previewResolved && now >= e.previewRetryAtMs) return true
        }
        for (e in effectiveLastCache.values) {
            if (e.retryAtMs > 0L && now >= e.retryAtMs) return true
        }
        return false
    }

    private fun startRoomListResolver(c: MatrixClient) {
        roomListJob?.cancel()
        roomListDirty = true
        roomListJob = scope.launch {
            android.util.Log.d(TAG, "room list resolver starting for ${c.userId.full}")
            // Hero-name memo: the profile store is warm, but resolving names for
            // every room sequentially still benefits from not re-reading the
            // same hero (WhatsApp DMs reuse the same few profiles).
            val nameMemo = HashMap<String, String>()
            while (true) {
                // Skip the pass unless the room map moved or a parked
                // preview/ghost-walk retry came due (efficiency audit
                // 2026-08-14 — the resolver ran a full pass every 2 s, 24/7).
                if (!roomListDirty && !hasPendingResolveWork()) {
                    kotlinx.coroutines.delay(ROOM_LIST_REFRESH_DELAY_MS)
                    continue
                }
                roomListDirty = false
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
                        // Membership filter: only rooms the user is in appear in
                        // the list — old left rooms (bridge re-link artifacts)
                        // stay out of the cache entirely.
                        if (room != null && room.membership == Membership.JOIN) {
                            loaded += roomId to room
                        }
                    }
                    loaded.sortByDescending { it.second.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L }
                    // An unverified device can't decrypt incoming messages, so the
                    // server-computed unread counts are meaningless there (those
                    // events never even notify). Suppress them until verified.
                    val verified = isDeviceVerified(c)
                    // Per-network labels from Beeper's spaces (m.space.child).
                    // Built from the FULL room map (cached) — the budget-bound
                    // loaded subset may not include the (older) space rooms.
                    val networks = networkByRoom(c, rooms)
                    seedRoomList(loaded, verified, networks)
                    // Phase 14 feedback: every joined room gets a preview attempt
                    // (the user's list looked inconsistent — rooms beyond the old
                    // 30-room preview window showed no latest message at all).
                    // The per-pass budget + the encrypted-room retry backoff keep
                    // it cheap: decrypted reads are milliseconds, still-encrypted
                    // rooms back off for a minute, and each pass stops at the
                    // deadline — the rest finish on the next pass.
                    for ((roomId, room) in loaded) {
                        if (android.os.SystemClock.elapsedRealtime() >= passDeadline) break
                        resolveRoomListEntry(
                            c, roomId, room, nameMemo,
                            resolvePreview = true,
                            verified = verified,
                            networks = networks,
                        )
                    }
                    // Eager page pre-compute (2026-08-13): the most-recent rooms'
                    // newest pages are computed in the background so opening a
                    // thread is a cache hit instead of a cold walk. A few per
                    // pass; rooms with a fresh page (memory or disk) are skipped.
                    var precomputed = 0
                    for ((roomId, _) in loaded) {
                        if (precomputed >= EAGER_PAGES_PER_PASS) break
                        if (android.os.SystemClock.elapsedRealtime() >= passDeadline) break
                        val key = roomId.full
                        val now = android.os.SystemClock.elapsedRealtime()
                        val cached = messagePageCache[key]
                        if (cached != null && now - cached.refreshedAtMs < MESSAGE_PAGE_TTL_MS) continue
                        if (loadMessagePageFromDisk(key) != null) continue
                        val page = runCatching {
                            computeMessagesPage(key, null, THREAD_PAGE_SIZE, fast = true)
                        }.getOrNull()
                        if (page != null) {
                            messagePageCache[key] = MessagePageEntry(
                                page, THREAD_PAGE_SIZE, android.os.SystemClock.elapsedRealtime(),
                            )
                            saveMessagePageToDisk(key, page)
                            precomputed++
                        }
                    }
                    publishRoomList()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "room list resolver pass failed: ${e.message}")
                }
                kotlinx.coroutines.delay(ROOM_LIST_REFRESH_DELAY_MS)
            }
        }
    }

    /** Inserts a placeholder row for every joined room not yet in the cache. */
    private fun seedRoomList(
        rooms: List<Pair<RoomId, MatrixRoom>>,
        verified: Boolean,
        networks: Map<String, String>,
    ) {
        var seeded = 0
        for ((roomId, room) in rooms) {
            if (room.membership != Membership.JOIN) continue
            val key = roomId.full
            if (roomListCache.containsKey(key)) continue
            roomListCache[key] = RoomListEntry(
                room = com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room(
                    id = key,
                    name = ROOM_NAME_PLACEHOLDER, // filled in by the resolver
                    lastMessage = "",
                    // An unverified device can't decrypt — suppress unread for
                    // encrypted rooms only; unencrypted ones stay readable.
                    unreadCount = if (verified || !room.encrypted) room.unreadMessageCount else 0,
                    lastTimestampMs = room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L,
                    lastEventId = room.lastRelevantEventId?.full,
                    isDirect = room.isDirect,
                    network = networks[key],
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
     * with a bounded decrypt-wait, but only for rooms inside the preview window
     * (the newest ones the user sees — see [startRoomListResolver]). Rows whose
     * state hasn't changed keep their resolved name/preview, so steady-state
     * passes are cheap.
     */
    private suspend fun resolveRoomListEntry(
        c: MatrixClient,
        roomId: RoomId,
        room: MatrixRoom,
        nameMemo: MutableMap<String, String>,
        resolvePreview: Boolean,
        verified: Boolean,
        networks: Map<String, String>,
    ) {
        val key = roomId.full
        // Rooms the user left drop out of the list live (membership filter).
        if (room.membership != Membership.JOIN) {
            roomListCache.remove(key)
            return
        }
        val prev = roomListCache[key]
        // Ghost-aware last event: after a bridge re-import flood the server's
        // summary points at a ghost, which would bump the room to the top of
        // the list and show the re-imported message as its preview.
        val serverTs = room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L
        val serverLastId = room.lastRelevantEventId?.full
        val (lastEventId, ts) = effectiveLastEvent(c, roomId, serverLastId, serverTs)
        // An unverified device can't decrypt — suppress unread for encrypted
        // rooms only; unencrypted ones stay readable.
        val unread = if (verified || !room.encrypted) room.unreadMessageCount else 0
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
            !resolvePreview && prev?.previewResolved != true -> {
                // Outside the preview window and never resolved: keep the name
                // row without a preview. Opening the thread fills it in.
                preview = ""
                previewResolved = false
                previewRetryAtMs = 0L
            }
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
                val resolved = resolveRoomPreview(c, roomId, lastEventId, room)
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
                isDirect = room.isDirect,
                network = networks[key],
            ),
            nameResolved = true,
            previewResolved = previewResolved,
            previewRetryAtMs = previewRetryAtMs,
        )
    }

    /** Network-map cache (see [networkByRoom]): rebuilt at most every TTL. */
    @Volatile
    private var networkByRoomCache: Map<String, String> = emptyMap()
    @Volatile
    private var networkByRoomBuiltAtMs = 0L

    /**
     * Maps each room id to its bridged-network label, from Beeper's
     * per-network spaces: a space's `m.space.child` state (stateKey = child
     * room id) lists its rooms, and the space's explicit name carries the
     * network ("WhatsApp (+61420460590)" → "WhatsApp"). Rooms outside any
     * account space (Beeper-internal, e.g. Note to self) stay ungrouped.
     *
     * Only ACCOUNT spaces are mapped (see [isAccountSpace]): Beeper also
     * creates spaces for WhatsApp group/community chats, named after the group
     * itself — those must not become selectable accounts.
     *
     * Reads the FULL room map (not the budget-bound newest subset — the space
     * rooms are older than the room activity) and caches the result, since
     * space membership changes rarely.
     */
    private suspend fun networkByRoom(
        c: MatrixClient,
        rooms: Map<RoomId, Flow<MatrixRoom?>>,
    ): Map<String, String> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (networkByRoomCache.isNotEmpty() && now - networkByRoomBuiltAtMs < NETWORK_MAP_TTL_MS) {
            return networkByRoomCache
        }
        val result = mutableMapOf<String, String>()
        withTimeoutOrNull(NETWORK_MAP_BUDGET_MS) {
            for ((spaceId, spaceFlow) in rooms) {
                val space = spaceFlow.filterNotNull().firstOrNull() ?: continue
                if (space.createEventContent?.type !is CreateEventContent.RoomType.Space) continue
                val spaceName = space.name?.explicitName.orEmpty()
                if (!isAccountSpace(spaceName, space.name?.heroes.orEmpty())) continue
                val label = networkLabelOf(spaceName)
                if (label.isBlank()) continue
                val childIds = c.room.getAllState(spaceId, ChildEventContent::class).first().keys
                for (childId in childIds) result[childId] = label
            }
        }
        networkByRoomCache = result
        networkByRoomBuiltAtMs = now
        return result
    }

    /** "WhatsApp (+61420460590)" → "WhatsApp"; a plain name keeps itself. */
    private fun networkLabelOf(spaceName: String): String {
        val trimmed = spaceName.trim()
        val paren = trimmed.indexOf(" (")
        return if (paren > 0) trimmed.substring(0, paren) else trimmed
    }

    /**
     * Known bridged-network names (lowercase). An account space is named after
     * its network ("WhatsApp (+61420460590)", "Instagram (FENN)"); a group/
     * community space is named after the group and must not be offered as an
     * account. Open-ended fallback: a space whose single hero is a bridge bot
     * (@whatsappbot, @instagramgobot, …) is an account even if the name is
     * unfamiliar.
     */
    private val ACCOUNT_NETWORK_NAMES = setOf(
        "whatsapp", "instagram", "telegram", "imessage", "sms", "signal",
        "slack", "discord", "facebook messenger", "messenger", "x", "twitter",
        "linkedin", "google chat", "hangouts", "matrix", "beeper", "note to self",
        "phone", "threads",
    )

    private fun isAccountSpace(spaceName: String, heroes: List<UserId>): Boolean {
        val label = networkLabelOf(spaceName).trim().lowercase()
        if (label in ACCOUNT_NETWORK_NAMES) return true
        return heroes.size == 1 && heroes[0].localpart.endsWith("bot", ignoreCase = true)
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
     * The room's newest event that is not a bridge re-import ghost, for the
     * list's sort + preview. The server's room summary points at the newest
     * event — after a bridge re-import flood that is a ghost, which bumped
     * every room to the top of the chat list. When the server's last event is
     * a ghost, walk back through the store to the first real event (bounded);
     * otherwise the server values pass through. Cached per server last event
     * (the summary is stable between new messages, so the walk runs once).
     */
    private suspend fun effectiveLastEvent(
        c: MatrixClient,
        matrixRoomId: RoomId,
        serverLastId: String?,
        serverTs: Long,
    ): Pair<String?, Long> {
        if (serverLastId == null) return null to serverTs
        val key = matrixRoomId.full
        val now = android.os.SystemClock.elapsedRealtime()
        effectiveLastCache[key]?.let { cached ->
            if (cached.serverLastEventId == serverLastId) {
                if (cached.retryAtMs == 0L || now < cached.retryAtMs) {
                    // Resolved, or a timed-out walk still inside its retry
                    // window — serve the cached values without re-walking.
                    return cached.effectiveEventId to cached.effectiveTs
                }
                // Retry window expired — re-walk.
                effectiveLastCache.remove(key)
            }
        }
        // Fast in-path walk (no session restore — old originals may not
        // decrypt yet): if the server's newest event survives the dedup AND
        // isn't inside a flood, it's a real message (the common case).
        val fast = withTimeoutOrNull(ROOM_BUDGET_MS) {
            collectTimelineEvents(c, matrixRoomId, serverLastId, EFFECTIVE_LAST_FAST)
        }.orEmpty()
        val fastFiltered = filterGhosts(c, fast)
        val serverLast = fast.firstOrNull { it.event.id.full == serverLastId }
        val inFlood = serverLast != null && isFloodGhost(c, serverLast, fast)
        if (fastFiltered.firstOrNull()?.event?.id?.full == serverLastId && !inFlood) {
            effectiveLastCache[key] = EffectiveLast(serverLastId, serverLastId, serverTs)
            return serverLastId to serverTs
        }
        // Suspicious (dropped by the dedup, or inside a flood): resolve in the
        // background with a session restore (the copies' originals are old
        // messages whose keys load from the backup — slow, so not on the
        // resolver's critical path). Keep the server's values until it lands.
        enqueueGhostResolve(c, matrixRoomId, serverLastId, serverTs)
        effectiveLastCache[key] = EffectiveLast(
            serverLastId, serverLastId, serverTs,
            retryAtMs = now + GHOST_WALK_RETRY_MS,
        )
        return serverLastId to serverTs
    }

    /** Rooms currently being ghost-resolved in the background (keyed by room|serverLast). */
    private val ghostResolveInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Background resolution of a room's real last event: collects the newest
     * events, restores the originals' megolm sessions from the key backup
     * (bounded), re-reads, and content-dedups — then caches the result so the
     * resolver's next pass picks it up. Runs off the resolver's pass loop so a
     * slow key-backup restore doesn't stall the whole list.
     */
    private fun enqueueGhostResolve(c: MatrixClient, matrixRoomId: RoomId, serverLastId: String, serverTs: Long) {
        val key = matrixRoomId.full
        if (!ghostResolveInFlight.add("$key|$serverLastId")) return
        scope.launch {
            try {
                val walked = withTimeoutOrNull(GHOST_WALK_BUDGET_MS) {
                    val events = collectTimelineEvents(c, matrixRoomId, serverLastId, EFFECTIVE_LAST_WALK)
                    restoreRoomSessions(c, matrixRoomId, events)
                    collectTimelineEvents(c, matrixRoomId, serverLastId, EFFECTIVE_LAST_WALK)
                }
                if (walked != null) {
                    val real = filterGhosts(c, walked).firstOrNull()
                    effectiveLastCache[key] = EffectiveLast(
                        serverLastId,
                        real?.event?.id?.full,
                        real?.event?.originTimestamp ?: 0L,
                    )
                    android.util.Log.d(
                        TAG,
                        "ghostResolve: $key server last $serverLastId → real last ${real?.event?.id?.full ?: "none"}",
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "ghostResolve failed for $key", e)
            } finally {
                ghostResolveInFlight.remove("$key|$serverLastId")
            }
        }
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
        room: MatrixRoom,
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
        // Group-chat previews name the sender ("Anni: the message", "You: …" —
        // the WhatsApp/Beeper convention); DMs don't need it and an unresolved
        // "[Encrypted]" row has no readable sender.
        val preview = if (encrypted || room.isDirect) text else {
            val sender = if (te.event.sender == c.userId) "You" else senderNameOf(c, roomId, te.event.sender)
            "$sender: $text"
        }
        return Triple(
            preview,
            !encrypted,
            if (encrypted) android.os.SystemClock.elapsedRealtime() + ROOM_LIST_ENCRYPTED_RETRY_MS else 0L,
        )
    }

    /** Publishes the cache as the sorted, SDK-shaped list (and persists it). */
    private fun publishRoomList() {
        val rooms = roomListCache.values
            .map { it.room }
            .sortedByDescending { it.lastTimestampMs }
        _roomList.value = rooms
        saveRoomListToDisk(rooms)
    }

    // --- internals -----------------------------------------------------------

    /**
     * Logs HTTP traffic to logcat for debugging the verification/binder/send
     * paths: request + response (with bodies) for every call except /sync (huge,
     * always 200). Bodies are truncated to 400 chars — full megolm ciphertexts
     * are multi-KB, and logd silently drops/truncates such lines (that's why the
     * earlier narrow filter that embedded full bodies saw nothing during a send).
     * Read-only — request/response streams are rebuilt from a clone so callers
     * see the untouched payload. Grep for "HTTP-TRAFFIC".
     */
    private fun httpLoggingInterceptor(): okhttp3.Interceptor = okhttp3.Interceptor { chain ->
        // Off by default (efficiency audit 2026-08-14): the body buffering +
        // UTF-8 conversion ran on every request, 24/7, logging multi-KB megolm
        // ciphertexts. Live-read, so the debugLog toggle applies immediately.
        if (!debugLogging()) return@Interceptor chain.proceed(chain.request())
        if (httpTrafficSeen.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "HTTP-TRAFFIC: interceptor armed (first request through)")
        }
        val request = chain.request()
        val path = request.url.encodedPath
        val logThis = !path.contains("/sync")
        val rebuilt = if (logThis && request.body != null) {
            val originalBody = request.body!!
            val buffer = okio.Buffer()
            originalBody.writeTo(buffer)
            val loggedBody = object : okhttp3.RequestBody() {
                override fun contentType(): okhttp3.MediaType? = originalBody.contentType()
                // The body must be replayable AND its length explicit: logging used
                // to consume the buffer (readUtf8), so the rebuilt body went out
                // empty with Content-Length: 0 — Beeper's server rejected every
                // POST with M_NOT_JSON "Request body is not valid JSON" (seen on
                // /keys/claim, read_markers and room sends). Snapshot for logging,
                // and declare the length so OkHttp doesn't fall back to chunked.
                override fun contentLength(): Long = buffer.size
                override fun writeTo(sink: okio.BufferedSink) {
                    sink.write(buffer.clone(), buffer.size)
                }
            }
            val body = buffer.snapshot().utf8()
            android.util.Log.d(
                TAG,
                "HTTP-TRAFFIC ${request.method} ${request.url} req=${body.take(400)}" +
                    (if (body.length > 400) "…(+${body.length - 400})" else ""),
            )
            request.newBuilder().method(request.method, loggedBody).build()
        } else request

        val response = chain.proceed(rebuilt)
        if (logThis) {
            val body = response.body
            val bodyStr = if (body != null) {
                val source = body.source()
                source.request(Long.MAX_VALUE)
                source.buffer.clone().readUtf8()
            } else ""
            android.util.Log.d(
                TAG,
                "HTTP-TRAFFIC ${request.method} ${request.url} -> ${response.code} ${response.message} " +
                    "body=${bodyStr.take(400)}${if (bodyStr.length > 400) "…(+${bodyStr.length - 400})" else ""}",
            )
        }
        response
    }

    private val httpTrafficSeen = java.util.concurrent.atomic.AtomicBoolean(false)

    // Beeper's homeserver omits the `failures` member from /keys/claim
    // responses, which Trixnity's E2EE key claiming chokes on (ClaimKeys.Response
    // declares `failures` without a default, so the response fails to deserialize
    // and no olm session is created — the bridge never gets the room key);
    // inject it when missing (adopted from the MIT-licensed Beeper4LightOS
    // bootstrap, which proved it on-device). Applied to BOTH engines: the
    // session-restore path (fromStore) uses the generic engine, and that's the
    // normal boot path on the LP3 — scoping it to the Beeper engine alone left
    // every claim failing on the restored client. No-op for compliant servers
    // (they always include `failures`).
    private val claimFailuresFixInterceptor = okhttp3.Interceptor { chain ->
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
    }

    private val httpClientEngine = OkHttp.create {
        addInterceptor(httpLoggingInterceptor())
        addInterceptor(claimFailuresFixInterceptor)
    }.also { android.util.Log.d(TAG, "HTTP-TRAFFIC: generic engine armed") }

    private fun matrixConfiguration(): MatrixClientConfiguration.() -> Unit = {
        name = "chats"
        // Qualified: inside this receiver lambda, the unqualified `httpClientEngine`
        // would resolve to the receiver's own (null) property — a silent no-op that
        // left the client on Ktor's default engine (no logging/claim interceptors).
        httpClientEngine = this@MatrixRepository.httpClientEngine
        modulesFactories = createTrixnityDefaultModuleFactories() + ::plaintextVerificationModule
    }

    private fun beeperMatrixConfiguration(): MatrixClientConfiguration.() -> Unit = {
        name = "chats-beeper"
        httpClientEngine = this@MatrixRepository.httpClientEngine
        modulesFactories = createTrixnityDefaultModuleFactories() + ::plaintextVerificationModule
    }

    /**
     * Overrides the Olm encryption service so every to-device verification
     * event goes out unencrypted — Beeper's mautrix-go SDK sends the whole
     * verification chain plaintext and drops encrypted verification events
     * (see [PlaintextVerificationOlmEncryptionService]).
     */
    private fun plaintextVerificationModule() = module {
        single<OlmEncryptionService> {
            PlaintextVerificationOlmEncryptionService(get<OlmEncryptionServiceImpl>()).also {
                android.util.Log.i("MatrixRepository", "plaintext-verification olm wrapper armed")
            }
        }
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
     * LOGGED_OUT/LOGGED_OUT_SOFT; we surface "session expired" and stop the
     * sync service (no point retrying a dead token). [logout]'s own transition
     * is excluded via [manualLogout].
     *
     * Only fires on a runtime transition AWAY from LOGGED_IN: a restored
     * session that was already dead (persisted soft/locked state) never
     * triggers it — such a session errors out on sync instead. This guards
     * against misreading a transient initial state as expiry.
     */
    private fun observeLoginState(c: MatrixClient) {
        scope.launch {
            var sawLoggedIn = false
            c.loginState.collect { state ->
                if (state == MatrixClient.LoginState.LOGGED_IN) {
                    sawLoggedIn = true
                    return@collect
                }
                if (!sawLoggedIn || manualLogout || sessionExpired) return@collect
                sessionExpired = true
                android.util.Log.w(TAG, "session no longer logged in ($state) — treating as expired")
                _connectionState.value = ChatConnectionState.Offline("session expired — sign in again")
                scheduleSyncStop()
            }
        }
    }

    /**
     * Stops the sync service shortly after an expiry is detected. The delay
     * matters: stopping a just-started foreground service (the start from
     * `ensureClient` can race this) crashes it with
     * ForegroundServiceDidNotStartInTimeException. The re-check also skips the
     * stop if a re-login already reset the flag and started a fresh service.
     */
    private fun scheduleSyncStop() {
        val ctx = appContext ?: return
        mainHandler.postDelayed({
            if (sessionExpired) {
                ctx.stopService(android.content.Intent(ctx, ChatSyncService::class.java))
            }
        }, SYNC_STOP_DELAY_MS)
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
        sendStatus: String? = null,
        read: Boolean = false,
        reactions: List<String> = emptyList(),
    ): com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message? {
        // The resolved content decides the row type: a decrypted image event
        // renders as an image row (its bytes fetched via GetMessageMedia), an
        // audio event as a playable voice-note row (played via PlayVoiceNote);
        // every other showable event renders as text. Text bodies are the full
        // message — the 80-char preview cap is only for the room list.
        val content = te.content?.getOrNull()
        val (body, contentType) = when (content) {
            is RoomMessageEventContent.FileBased.Image ->
                (content.fileName?.takeIf { it.isNotBlank() } ?: "[Photo]") to "image"
            is RoomMessageEventContent.FileBased.Audio ->
                (content.fileName?.takeIf { it.isNotBlank() } ?: "Voice note") to "audio"
            is RoomMessageEventContent.TextBased -> stripReplyQuote(content.body) to "text"
            else -> (previewText(te) ?: return null) to "text"
        }
        val sender = te.event.sender
        return com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
            id = te.event.id.full,
            sender = sender.full,
            senderName = senderNameOf(c, roomId, sender),
            body = body,
            timestampMs = runCatching { te.event.originTimestamp }.getOrDefault(0L),
            isMine = sender == c.userId,
            // Delivery status only ever describes this device's own sends.
            sendStatus = sendStatus.takeIf { sender == c.userId },
            contentType = contentType,
            read = read,
            reactions = reactions,
            // The voice-note row shows the length + playing progress.
            durationMs = (content as? RoomMessageEventContent.FileBased.Audio)?.info?.duration,
        )
    }

    private suspend fun senderNameOf(c: MatrixClient, roomId: RoomId, sender: UserId): String =
        withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.user.getById(roomId, sender).firstOrNull()?.name
        } ?: sender.localpart

    /** Human-readable text for a timeline event; null for events with nothing to show.
     *  Used for the room list's last-message preview, so bodies are capped. */
    private fun previewText(te: TimelineEvent): String? {
        // The decrypted content first: for a decrypted event the raw event
        // content is still the m.room.encrypted payload, so it must not shadow
        // the resolved content.
        val content = te.content?.getOrNull()
        if (content != null) {
            return when (content) {
                is RoomMessageEventContent.TextBased -> stripReplyQuote(content.body).take(MAX_PREVIEW_LENGTH)
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

    /** Removes the leading quoted block of a Matrix reply so only the new text
     *  shows. Returns the full text — callers cap it where a preview is wanted. */
    private fun stripReplyQuote(body: String): String {
        if (!body.startsWith(">")) return body
        val index = body.indexOf("\n\n")
        return if (index != -1) body.substring(index + 2).trimStart() else body
    }

    private const val MAX_PREVIEW_LENGTH = 80
    /** How many recent timeline events to scan for Beeper send-status events. */
    private const val SEND_STATUS_WINDOW = 250
    /** Rebuild the network map at most this often (space membership is stable). */
    private const val NETWORK_MAP_TTL_MS = 300_000L
    /** Bound for a full network-map build (600+ room flows on a big account). */
    private const val NETWORK_MAP_BUDGET_MS = 15_000L
    private const val ROOMS_BUDGET_MS = 15_000L
    private const val ROOM_BUDGET_MS = 3_000L
    private const val MESSAGES_BUDGET_MS = 15_000L
    private const val FETCH_TIMEOUT_SECONDS = 5L
    /** The thread's page size (matches the tool's PAGE_SIZE). */
    private const val THREAD_PAGE_SIZE = 20
    /** Serve a cached newest page within this window (feedback pass). */
    private const val MESSAGE_PAGE_TTL_MS = 5_000L
    /** Recompute the active room's cached newest page at this cadence. */
    private const val ACTIVE_ROOM_REFRESH_MS = 2_000L
    private const val TYPING_TIMEOUT_MS = 30_000L
    private const val DECRYPT_RETRIES = 3
    private const val DECRYPT_RETRY_DELAY_MS = 1_500L
    private const val DECRYPT_WAIT_MS = 3_000L
    /** Peek budget for events after the first one failed to decrypt. */
    private const val QUICK_DECRYPT_WAIT_MS = 100L

    // Room-list resolver (Phase 5).
    private const val ROOM_NAME_PLACEHOLDER = "…"
    /** Per-pass time budget; the resolver loops until the list is settled. */
    private const val ROOM_LIST_PASS_BUDGET_MS = 12_000L
    /** Breather between passes; a settled pass itself takes milliseconds. */
    private const val ROOM_LIST_REFRESH_DELAY_MS = 2_000L
    /** A "[Encrypted]" preview is retried no sooner than this. */
    private const val ROOM_LIST_ENCRYPTED_RETRY_MS = 60_000L
    /** Bounded decrypt-wait per room preview. */
    private const val PREVIEW_BUDGET_MS = 1_500L
    /** Per-room state collect in the resolver (the store cache emits instantly). */
    private const val ROOM_LIST_ROOM_BUDGET_MS = 500L
    /** Delay before stopping the sync service after an expiry detection. */
    private const val SYNC_STOP_DELAY_MS = 3_000L
    /** Bound for the device-verification check before key-backup work. */
    private const val KEY_BACKUP_VERIFY_TIMEOUT_MS = 1_000L
    /** Bound for a single megolm-session load (can hang waiting for a key). */
    private const val KEY_BACKUP_LOAD_TIMEOUT_MS = 2_000L

    // Bridge-ghost filtering (Phase 14.5).
    /** Density-fallback window: a txn-id event inside this many of [GHOST_FLOOD_THRESHOLD] others is a flood. */
    private const val GHOST_BURST_WINDOW_MS = 60_000L
    /** Flood fallback: real conversations rarely exceed this rate (re-imports are per-second). */
    private const val GHOST_FLOOD_THRESHOLD = 30
    /** Newest-page walk cap: how far past a ghost flood we look for real messages. */
    private const val GHOST_WALK_MAX = 2000
    /** First-poll walk cap: the first page must return fast — the background
     *  refresher (every 2 s) walks further and refines it. */
    private const val FIRST_POLL_GHOST_WALK_MAX = 200
    /** Room-list effective-last walk cap (decrypted events). */
    private const val EFFECTIVE_LAST_WALK = 800
    /** In-path fast window — big enough to spot a >=[GHOST_FLOOD_THRESHOLD] flood. */
    private const val EFFECTIVE_LAST_FAST = 50
    /** How long a pending ghost-resolution is parked before the resolver retries. */
    private const val GHOST_WALK_RETRY_MS = 120_000L
    /** Bound for the effective-last decrypting walk (one-time per room, cached). */
    private const val GHOST_WALK_BUDGET_MS = 8_000L

    // Media / photos (Phase 13).
    /**
     * Longest side (px) of the display JPEG served to the tool. Sized so the
     * base64-encoded payload stays comfortably inside the ~1 MB binder
     * transaction limit even for detailed photos — a 1280 px / q82 JPEG could
     * reach 600-900 KB, which base64 inflated past the limit and made image
     * rows fail to load (TransactionTooLargeException → text fallback forever).
     */
    const val DISPLAY_MAX_DIMENSION = 1024
    const val DISPLAY_JPEG_QUALITY = 78
    /** Longest side (px) of the compressed photo uploaded to the room. */
    const val SENT_PHOTO_MAX_DIMENSION = 2048
    const val SENT_PHOTO_JPEG_QUALITY = 85
    /** Bound for a single media download / decode / upload. */
    private const val MEDIA_BUDGET_MS = 10_000L
    /** Re-reads of the event while its content is still decrypting. */
    private const val MEDIA_CONTENT_RETRIES = 4
    private const val MEDIA_CONTENT_RETRY_DELAY_MS = 1_500L
    /** How many display JPEGs the LRU keeps (each ~100-300 KB). */
    private const val MAX_MEDIA_CACHE_ENTRIES = 24
    /** The companion's photo-picker activity, flattened for the tool to launch. */
    private const val PHOTO_PICKER_ACTIVITY = "com.lightphone.chats.server/.PhotoSendActivity"
    /** The companion's voice-note recording activity, flattened for the tool. */
    private const val VOICE_NOTE_ACTIVITY = "com.lightphone.chats.server/.VoiceNoteActivity"

    // Disk cache (Phase 14).
    // Versioned so a stale pre-ghost-filter cache (pages/lists polluted by the
    // bridge re-import) is never served after an upgrade.
    private const val DISK_CACHE_DIR = "chats_cache_v2"
    private const val DISK_ROOM_LIST_FILE = "room_list.json"
    /** How many rooms keep an on-disk message page (the re-open surface). */
    private const val DISK_CACHE_MAX_PAGES = 100
    /** A page below this size isn't persisted (transient first-poll fragment). */
    private const val MIN_PERSISTED_PAGE_SIZE = 5
    /** Minimum gap between disk writes per key (the refresher runs every 2 s). */
    private const val DISK_WRITE_THROTTLE_MS = 10_000L
    /** How many newest pages the resolver pre-computes per pass (background warm-up). */
    private const val EAGER_PAGES_PER_PASS = 3
    /** How often a room's page is re-warmed on new messages (debounce). */
    private const val ROOM_WARM_DEBOUNCE_MS = 10_000L

    private const val TAG = "MatrixRepository"
}
