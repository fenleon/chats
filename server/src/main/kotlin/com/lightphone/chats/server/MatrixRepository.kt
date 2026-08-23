package com.lightphone.chats.server

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
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
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
import net.folivo.trixnity.client.room.GetTimelineEventConfig
import net.folivo.trixnity.client.room.GetTimelineEventsConfig
import net.folivo.trixnity.client.room.TimelineEventHandler
import net.folivo.trixnity.client.room.message.image
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.serverDiscovery
import net.folivo.trixnity.client.store.GlobalAccountDataStore
import net.folivo.trixnity.client.store.OlmCryptoStore
import net.folivo.trixnity.client.store.TimelineEvent
import net.folivo.trixnity.client.store.Room as MatrixRoom
import net.folivo.trixnity.client.store.joinedMemberCount
import net.folivo.trixnity.client.store.repository.RoomStateRepository
import net.folivo.trixnity.client.store.repository.RoomStateRepositoryKey
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
import net.folivo.trixnity.core.model.events.UnsignedRoomEventData
import net.folivo.trixnity.core.model.events.m.room.MemberEventContent
import net.folivo.trixnity.core.model.events.m.Presence
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
    /** When the one-shot megolm restore scan last ran (daily gate, 2026-08-15). */
    private const val KEY_RESTORE_LAST_RUN_MS = "restore_last_run_ms"
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

    /**
     * Voice-note sends awaiting their sync echo (room key → txn → send info).
     * Multi-slot: two rapid sends in one room must BOTH keep their optimistic
     * rows — a single per-room slot let the second send overwrite the first,
     * whose row then vanished until its echo (feedback 2026-08-17: "only one
     * shows").
     */
    private val pendingAudioEcho = java.util.concurrent.ConcurrentHashMap<
        String, java.util.concurrent.ConcurrentHashMap<String, PendingAudioSend>>()

    private data class PendingAudioSend(
        override val txnId: String,
        override val timestampMs: Long,
        val durationMs: Long?,
        /** Copy of the recorded file kept so the pending row stays playable
         *  before the sync echo lands (the activity deletes the original
         *  right after the send RPC — see [sendVoiceNote]). Null when the
         *  copy failed; sending still works, the pending row just can't
         *  play until the echo resolves. */
        val localFile: java.io.File? = null,
    ) : PendingSend

    /**
     * Text sends awaiting their sync echo (room key → txn → send info), the
     * same optimistic-row pattern as [pendingAudioEcho]. The echo (which can
     * take a full sync tick on a big account) replaces the row; until then
     * every getMessages shows the sent message — including a re-opened thread,
     * which is why the echo lives server-side and not in the tool's view model
     * (feedback 2026-08-14: a sent message vanished from a re-opened thread).
     */
    private val pendingTextEcho = java.util.concurrent.ConcurrentHashMap<
        String, java.util.concurrent.ConcurrentHashMap<String, PendingTextSend>>()

    private data class PendingTextSend(
        override val txnId: String,
        override val timestampMs: Long,
        val body: String,
    ) : PendingSend

    /** A send awaiting its sync echo (text or audio), shown as an optimistic row. */
    private sealed interface PendingSend {
        val txnId: String
        val timestampMs: Long
    }

    /** A room's in-flight text sends, oldest first (chronological row order). */
    private fun pendingTextEchoes(roomId: String): List<PendingTextSend> =
        pendingTextEcho[roomId]?.values?.sortedBy { it.timestampMs }.orEmpty()

    /** A room's in-flight audio sends, oldest first. */
    private fun pendingAudioEchoes(roomId: String): List<PendingAudioSend> =
        pendingAudioEcho[roomId]?.values?.sortedBy { it.timestampMs }.orEmpty()

    /** The newest in-flight send in a room (the panel bump / pending override
     *  only ever shows the latest one). */
    private fun newestPending(roomId: String): PendingSend? =
        (pendingTextEcho[roomId]?.values?.maxByOrNull { it.timestampMs })
            ?: (pendingAudioEcho[roomId]?.values?.maxByOrNull { it.timestampMs })

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

    /** Sync cadence. ACTIVE = continuous long-poll while the screen is on;
     *  SLOW = periodic [net.folivo.trixnity.client.MatrixClient.syncOnce] once
     *  the screen's been off for a while — the long-poll's per-response
     *  parse/decrypt/store processing is the main standby cost on an
     *  always-active bridged account (battery, 2026-08-14). */
    enum class SyncMode { ACTIVE, SLOW }

    @Volatile
    private var syncMode = SyncMode.ACTIVE

    /** True while the periodic slow-sync loop owns sync. The FGS/watchdog must
     *  not restart a long-poll then (ChatSyncService reads this). */
    val isSlowSyncing: Boolean get() = syncMode == SyncMode.SLOW

    /** Screen truth for the sync-cadence decision. ChatSyncService reads this
     *  so it never starts a long-poll while the screen is dark (battery
     *  2026-08-19 audit: a service restart at night long-polled all night). */
    val isScreenOn: Boolean get() = isScreenInteractive()

    private var slowSyncJob: Job? = null
    private var screenOffJob: Job? = null

    /** Elapsed-realtime of the last push-wake syncOnce. Read-receipt/unread-
     *  count push bursts collapse against this (see [onPushDelivered]): every
     *  group member's reads POST one push, and each syncOnce costs ~30-50 s of
     *  CPU on this account (battery 2026-08-17 audit). */
    private var lastPushWakeSyncAtMs = 0L

    /** Screen must stay off this long before dropping to slow sync. */
    private const val SLOW_SYNC_GRACE_MS = 60_000L

    /** Slow-sync cadence: one sync round every 5 min.
     *  ponytail: 5 min is a session value — tighten if message latency feels
     *  too high, loosen if battery still burns. */
    private const val SLOW_SYNC_INTERVAL_MS = 300_000L

    /** Min gap between read-receipt-push wakeups (see [onPushDelivered]). One
     *  sync per window is enough — the unread badge is at most this stale, and
     *  the next event push / slow round catches up. Matches the old 5-min round
     *  cadence, which was proven acceptable for badge freshness. */
    private const val COUNTS_WAKE_MIN_INTERVAL_MS = 300_000L

    /**
     * Slow-sync cadence while the push channel is connected (2026-08-17): the
     * SSE wake delivers messages instantly (each push = one syncOnce), so the
     * rounds are pure redundancy — stretch them to a 30-min safety net that
     * still catches a silent SSE/Beeper failure. Without a live channel the
     * 5-min cadence runs.
     *  ponytail: 30 min is a session value — if push proves rock-solid over
     *  nights, drop the net entirely (O4 end state).
     */
    private const val SLOW_SYNC_PUSH_NET_MS = 1_800_000L

    /** Screen on/off → sync cadence. Registered on the app context in [init],
     *  so it lives as long as the process (which the FGS keeps alive). */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    applySyncModeForScreenState()
                    // A thread was open when the screen went dark — resume
                    // keeping its page fresh now that it's visible again.
                    startActiveRoomRefresh()
                    // A message likely landed while the screen was dark — end
                    // the resolver's screen-off sleep so the list is fresh the
                    // moment the user opens it (feedback 2026-08-17).
                    wakeRoomList()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    applySyncModeForScreenState()
                    // Battery (2026-08-15 audit): the active-room refresh was
                    // running 24/7 with no visibility coupling — every 2s a full
                    // page rebuild (SQL chain walk + key-backup restore + API
                    // re-reads). Nobody is looking while the screen is off.
                    stopActiveRoomRefresh()
                }
            }
        }
    }

    /** Called once from [ServerApplication]; restores a stored session if there is one. */
    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext == null) appContext = app
        enableTrixnityLogging()
        // Settings → Sync pause (audit 2026-08-14): a paused companion starts
        // no sync loop and no foreground service — the battery escape hatch.
        syncEnabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_ENABLED, true)
        // Screen-driven cadence: long-poll while the screen is on, periodic
        // syncOnce after it's been off for a while (battery, 2026-08-14).
        app.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        // Booted with the screen already dark: no SCREEN_OFF broadcast is
        // coming — drop to slow sync after the grace instead of long-polling
        // with nobody watching.
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == false) scheduleSlowSync()
        scope.launch {
            // Restore the session regardless of the sync toggle. GetAccountState
            // reads the live client, so a paused companion that skips the restore
            // makes the tool report "Not signed in" while the session is fine
            // (2026-08-14, user-verified on the LP3). Only the sync loop (and
            // its FGS) is gated on the toggle; the restored client's observers
            // stay dormant without sync (room flows never emit).
            if (ensureClient() != null) {
                if (syncEnabled) {
                    // Screen-state-aware start (battery 2026-08-19 audit): a
                    // session restore that lands while the screen is dark must
                    // NOT long-poll — the boot-time sample above races the
                    // restore, and a SCREEN_OFF broadcast that fired before the
                    // receiver registered is gone. The shared entry point
                    // applies the cadence the screen actually calls for.
                    applySyncModeForScreenState()
                    // Push wake-up channel (2026-08-16): register the Matrix
                    // HTTP pusher + hold the SSE subscription so idle sync has
                    // zero latency — see PushChannel.
                    PushChannel.start(app, client!!)
                } else {
                    _connectionState.value = ChatConnectionState.Offline("sync paused")
                    android.util.Log.d(TAG, "sync disabled by preference — session restored, no sync loop")
                }
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
        // Tear down the background sync machinery in both branches (pausing
        // stops it all; re-enabling restarts it below).
        slowSyncJob?.cancel()
        slowSyncJob = null
        screenOffJob?.cancel()
        screenOffJob = null
        syncMode = SyncMode.ACTIVE
        if (!enabled) {
            runCatching { c?.stopSync() }
            inProcessSyncRunning = false
            activeRoomRefreshJob?.cancel()
            activeRoomRefreshJob = null
            PushChannel.stop()
            ctx.stopService(android.content.Intent(ctx, ChatSyncService::class.java))
            _connectionState.value = ChatConnectionState.Offline("sync paused")
            android.util.Log.d(TAG, "sync paused by user")
        } else {
            if (c == null) {
                if (ensureClient() != null) startSyncLoop(ctx)
            } else {
                startSyncLoop(ctx)
            }
            client?.let { PushChannel.start(ctx, it) }
            android.util.Log.d(TAG, "sync resumed by user")
        }
    }

    /**
     * Single entry point for the screen-state → sync-cadence decision (battery
     * 2026-08-19 audit: the slow-sync gate could silently never fire — a
     * process restart raced the grace, `enterSlowSync` bailed on a null
     * client, and the restore's long-poll never re-checked the screen).
     * Called from [init] after the client is ready, the SCREEN_ON/OFF
     * receiver, and [ChatSyncService] before it starts a long-poll, so a sync
     * loop never runs while the screen is dark. Screen on → active long-poll;
     * dark → slow sync after the grace.
     */
    fun applySyncModeForScreenState() {
        if (!syncEnabled) return
        if (isScreenInteractive()) {
            scope.launch { enterActiveSync() }
        } else {
            scheduleSlowSync()
        }
    }

    /**
     * Drops to the slow cadence once the screen has been dark for the grace
     * period: stop the long-poll and run periodic [MatrixClient.syncOnce]
     * rounds instead. The FGS stays (it keeps the process alive for the slow
     * loop); the manual Settings → Sync toggle is the fully-off control.
     */
    private fun scheduleSlowSync() {
        screenOffJob?.cancel()
        screenOffJob = scope.launch {
            delay(SLOW_SYNC_GRACE_MS)
            // Screen came back on during the grace — [enterActiveSync] already
            // cancelled this job; this is just paranoia.
            val power = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power?.isInteractive == true) return@launch
            enterSlowSync()
        }
    }

    private suspend fun enterSlowSync() {
        if (!syncEnabled || syncMode == SyncMode.SLOW) return
        // The session restore may still be in flight when the grace fires (a
        // process restart while dark — battery 2026-08-19 audit). Wait for it
        // (bounded, screen re-checked) instead of bailing: a bail left syncMode
        // ACTIVE and the restore's long-poll ran all night with no re-check.
        var c = client
        if (c == null) {
            val deadline = android.os.SystemClock.elapsedRealtime() + SLOW_SYNC_GRACE_MS
            while (c == null && !isScreenInteractive() &&
                android.os.SystemClock.elapsedRealtime() < deadline
            ) {
                delay(250)
                c = client
            }
            if (c == null) {
                android.util.Log.w(
                    TAG,
                    "slow-sync grace: client still not ready after ${SLOW_SYNC_GRACE_MS / 1000}s — " +
                        "applySyncModeForScreenState() re-arms on client-ready",
                )
                return
            }
            if (isScreenInteractive()) return // screen came back on — enterActiveSync owns sync
            android.util.Log.w(TAG, "slow-sync grace: waited for client — engaging slow sync")
        }
        // Screen truth re-check (2026-08-22): the grace's check above can race
        // a SCREEN_ON broadcast (they fire in the same ms on a button press).
        // With a live client (the c == null branch above is skipped) engaging
        // slow mode then STOPS the long-poll while the screen is on — the
        // "sync mode: active" + "sync mode: slow" back-to-back log + a dead
        // loop until the next SCREEN_ON (LP3 2026-08-22: stuck offline banner
        // + no message delivery while slow-sync rounds ran underneath).
        if (isScreenInteractive()) return // screen came back on — enterActiveSync owns sync
        syncMode = SyncMode.SLOW // gate first: the watchdog must not restart the long-poll
        runCatching { c.stopSync() }
        inProcessSyncRunning = false
        slowSyncJob?.cancel()
        slowSyncJob = startSlowSyncRounds(c)
        android.util.Log.d(
            TAG,
            "sync mode: slow (syncOnce every ${SLOW_SYNC_INTERVAL_MS / 1000}s, " +
                "push-gated ${SLOW_SYNC_PUSH_NET_MS / 1000}s when SSE connected)",
        )
    }

    /** One syncOnce round with a wall-clock duration log — the per-sync cost is
     *  the battery metric that decides whether sync can be leaner (Beeper's
     *  client wakes in ~1s; ours measured here — battery 2026-08-17 audit). The
     *  round's outcome re-asserts the connection state (2026-08-22): a
     *  successful round proves connectivity, so it clears a stale "offline"
     *  left by the syncState observer (which can freeze on a long-poll TIMEOUT
     *  while the rounds keep succeeding — the LP3's stuck "Can't reach server"
     *  banner). The observer still reports long-poll TIMEOUT/ERROR instantly;
     *  this just makes recovery not depend on it. */
    private suspend fun timedSyncOnce(c: MatrixClient, reason: String): Result<Unit> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val result = runCatching { c.syncOnce(Presence.OFFLINE).getOrThrow() }
            .onSuccess { _connectionState.value = ChatConnectionState.Syncing }
            .onFailure { _connectionState.value = ChatConnectionState.Offline("sync failed") }
        android.util.Log.d(TAG, "syncOnce took ${android.os.SystemClock.elapsedRealtime() - t0}ms ($reason)")
        return result
    }

    /** The periodic syncOnce rounds (also restarted by a push-wake — see [onPushDelivered]). */
    private fun startSlowSyncRounds(c: MatrixClient): Job {
        val job = scope.launch {
            while (isActive) {
                if (client !== c) return@launch // logged out / re-logged in under us
                // Delay before the first round (audit 2026-08-23): a wake
                // (push/send) cancels the rounds, runs its own syncOnce, then
                // recreates this job — an immediate first round duplicated the
                // wake's syncOnce (two /sync per wake, ~2x the per-push cost).
                // The wake's own round already delivers; the cadence below is
                // the redundancy net.
                // Push-health-gated cadence (2026-08-17): while the SSE channel
                // is connected, pushes deliver messages instantly (each wakes
                // one syncOnce), so the rounds are pure redundancy — stretch to
                // a 30-min safety net that still catches a silent SSE/Beeper
                // failure. Without a live channel (no config, reconnecting,
                // stopped) fall back to the 5-min cadence.
                val cadenceMs = if (PushChannel.isConnected) SLOW_SYNC_PUSH_NET_MS else SLOW_SYNC_INTERVAL_MS
                delay(cadenceMs)
                timedSyncOnce(c, "round")
                    .onFailure { android.util.Log.w(TAG, "slow sync round failed: ${it.message}") }
            }
        }
        return job
    }

    /** Back to the real-time long-poll (screen on, or any reason sync restarts). */
    private suspend fun enterActiveSync() {
        screenOffJob?.cancel()
        screenOffJob = null
        slowSyncJob?.cancel()
        slowSyncJob = null
        // Skip only when ACTIVE mode already owns a live loop (in-process or
        // the FGS's). syncMode starts ACTIVE in a fresh process with nothing
        // running — bailing there (as the old `init`-independent guard did)
        // would leave a fresh screen-on start with no sync at all.
        if (syncMode == SyncMode.ACTIVE && (inProcessSyncRunning || ChatSyncService.isRunning)) return
        syncMode = SyncMode.ACTIVE
        if (!syncEnabled) return
        val ctx = appContext ?: return
        if (client != null) {
            startSyncLoop(ctx)
            android.util.Log.d(TAG, "sync mode: active (long-poll)")
        }
    }

    /**
     * Push-wake (2026-08-16, see PushChannel): an SSE push notification
     * arrived, so a message is waiting. While idle (slow sync, screen off)
     * run ONE syncOnce round — the notification watcher then posts the local
     * notification and the room flows update. While active the long-poll
     * already delivers it, so the push is redundant and skipped. The slow-sync
     * rounds are the fallback delivery (a silent SSE drop must not mean missed
     * messages) — push-gated to a 30-min net while the SSE is connected
     * (2026-08-17, see startSlowSyncRounds).
     *
     * [countsOnly] = the push carried no room/event id (Beeper's read-receipt /
     * unread-count payloads). Those must not each run a full ~30-50 s syncOnce
     * — a group chat with N members generates one per read action. Bursts
     * collapse to one sync per [COUNTS_WAKE_MIN_INTERVAL_MS]: the FIRST push
     * still syncs (it can be the only signal for a real message, e.g.
     * note-to-self on Beeper's fork), and an event push right before covers
     * the state anyway. Real event pushes (message arriving) always sync.
     */
    suspend fun onPushDelivered(countsOnly: Boolean = false) {
        val c = client ?: return
        if (syncMode != SyncMode.SLOW) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (countsOnly && now - lastPushWakeSyncAtMs < COUNTS_WAKE_MIN_INTERVAL_MS) {
            android.util.Log.d(TAG, "counts push collapsed (last wake ${(now - lastPushWakeSyncAtMs) / 1000}s ago)")
            return
        }
        slowSyncJob?.cancel()
        slowSyncJob = null
        timedSyncOnce(c, "push")
            .onFailure { android.util.Log.w(TAG, "push-wake sync failed: ${it.message}") }
        lastPushWakeSyncAtMs = android.os.SystemClock.elapsedRealtime()
        // A push means events landed in the store — end the resolver's
        // screen-off sleep so the next list read is fresh (feedback 2026-08-17).
        wakeRoomList()
        // Restart the fallback rounds. If the screen came back on mid-wake,
        // enterActiveSync owns sync (its long-poll already delivers); only
        // restart when it is still dark.
        val power = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (syncEnabled && power?.isInteractive == false) {
            slowSyncJob = startSlowSyncRounds(c)
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
        runCatching { c.startSync(Presence.OFFLINE) }
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
            // The old client's loop is stopped; a stale flag would silently
            // kill the new session's sync (re-login after restore/expiry).
            inProcessSyncRunning = false
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
                configuration = clientConfiguration("chats"),
            ).getOrThrow()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HOMESERVER, baseUrl.toString())
                .putString(KEY_USER_ID, loginResult.userId.full)
                .putString(KEY_LOGIN_MODE, "homeserver")
                .apply()
            finishLogin(ctx, loginResult)
        }
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
            inProcessSyncRunning = false
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
                configuration = clientConfiguration("chats-beeper"),
            ).getOrThrow()
            prefs.edit()
                .putString(KEY_HOMESERVER, BEEPER_HOMESERVER)
                .putString(KEY_USER_ID, loginResult.userId.full)
                .putString(KEY_LOGIN_MODE, "beeper")
                .remove(KEY_BEEPER_REQUEST_ID)
                .apply()
            finishLogin(ctx, loginResult)
        }
    }

    /**
     * Post-login wiring shared by both login paths: installs the client,
     * resets per-session state, and starts the sync loop + push channel.
     * Call inside [initMutex] as the lock block's last expression.
     */
    private suspend fun finishLogin(ctx: Context, newClient: MatrixClient): MatrixClient {
        client = newClient
        sessionExpired = false
        manualLogout = false
        e2eeStateCache = null // fresh account — recompute on next read
        resetRoomList()
        observeClient(newClient)
        restoreAttempted = false
        slowSyncJob?.cancel()
        slowSyncJob = null
        screenOffJob?.cancel()
        screenOffJob = null
        syncMode = SyncMode.ACTIVE
        startSyncLoop(ctx)
        PushChannel.start(ctx, newClient)
        return newClient
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
            e2eeStateCache = null // now verified — recompute on next read
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
        data object Verifying : VerificationUi        // SAS exchange in progress (keys/macs)
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

    /** E2EE status memoized for [E2EE_STATE_TTL_MS] — the network getDevices()
     *  on every poll (1-5 s) + every thread open was the slow, constant
     *  "Checking if account is verified" work (2026-08-23). (elapsedRealtime
     *  fetchedAt, verified, other-device count.) */
    @Volatile
    private var e2eeStateCache: Triple<Long, Boolean, Int>? = null

    /** E2EE status: whether this device is cross-signing verified, and whether other devices exist to verify with. */
    suspend fun e2eeState(): com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response {
        val c = client ?: return com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response(
            verified = false, canVerify = false, detail = "not logged in",
        )
        val now = android.os.SystemClock.elapsedRealtime()
        e2eeStateCache?.let { (fetchedAt, verified, devices) ->
            if (now - fetchedAt < E2EE_STATE_TTL_MS) {
                return com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response(
                    verified = verified,
                    canVerify = devices > 0,
                    detail = if (verified) null else "not verified",
                )
            }
        }
        // Same policy as [isDeviceVerified]: a timed-out trust read must NOT
        // read as "unverified" — on the LP3 the first read after opening the
        // screen can exceed the budget on a cold trust store, and the Account
        // row flipped "not verified" → "verified" on the next 5s poll
        // (2026-08-17, user report). Only a genuine non-CrossSigned trust
        // result counts as unverified.
        val verified = isDeviceVerified(c)
        if (verified && !restoreAttempted) {
            restoreAttempted = true
            scope.launch { restoreMegolmSessions() }
        }
        val devices = runCatching {
            c.api.device.getDevices().getOrNull()?.map { it.deviceId }?.filter { it != c.deviceId }?.size ?: 0
        }.getOrDefault(0)
        e2eeStateCache = Triple(now, verified, devices)
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
        android.util.Log.i(TAG, "startDeviceVerification: requesting from ${otherDevices.size} devices")
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
                VerificationUi.Verifying -> "verifying"
                is VerificationUi.Compare -> "compare"
                VerificationUi.Done -> "done"
                VerificationUi.Cancelled -> "cancelled"
                is VerificationUi.Error -> "error"
            },
            emoji = (ui as? VerificationUi.Compare)?.emoji,
            detail = (ui as? VerificationUi.Error)?.detail,
        )
    }

    /** Drives the interactive verification; [action] ∈ accept | match | no_match | cancel | reset. */
    suspend fun verifyAction(action: String): Result<Unit> = runCatching {
        android.util.Log.i(TAG, "verify: action=$action")
        when (action) {
            "accept" -> {
                // The "accept" UI covers the other device's SAS start, an
                // incoming request, and our Ready (both sides ready — start
                // the SAS ourselves). Prefer whichever is pending; the states
                // churn fast (their accept → their SAS start within ms), so
                // the tap may land on a later state than the panel rendered.
                val request = pendingTheirRequest
                val sas = pendingTheirSasStart
                val ready = pendingReady
                when {
                    sas != null -> sas.accept()
                    request != null -> request.ready()
                    ready != null -> ready.start(VerificationMethod.Sas)
                    else -> error("no incoming request")
                }
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
        android.util.Log.i(TAG, "verify: top-level state -> ${state::class.simpleName}")
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
                // The SAS is engaging — stay on the accept panel instead of
                // dipping back to "waiting" (the other device's SAS start
                // follows their accept within ms; the dip read as the flow
                // reverting — LP3 2026-08-19).
                VerificationUi.Accept
            }
            is ActiveVerificationState.Done -> {
                e2eeStateCache = null // verification changed — recompute on next read
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
        android.util.Log.i(TAG, "verify: SAS state -> ${state::class.simpleName}")
        _verification.value = when (state) {
            is ActiveSasVerificationState.OwnSasStart -> VerificationUi.Verifying
            is ActiveSasVerificationState.TheirSasStart -> {
                pendingTheirSasStart = state
                VerificationUi.Accept
            }
            is ActiveSasVerificationState.ComparisonByUser -> {
                pendingCompare = state
                VerificationUi.Compare(state.emojis.map { it.second })
            }
            // WaitForKeys / WaitForMacs are exchange progress — "Verifying…",
            // not "waiting for the other device to accept".
            else -> VerificationUi.Verifying
        }
    }

    /**
     * After the device is verified, load every undecrypted event's megolm session
     * from the server-side key backup so the room store can decrypt it. Called on
     * verification success; loading a session decrypts what the session covers.
     *
     * Battery/UX (2026-08-15): gated to at most once per day and bounded tighter
     * per room. Before, it ran on EVERY process start (reboot/install/force-stop)
     * and could crawl for 20+ min at several cores, starving the tool's requests
     * ("Loading messages…" / "…" account). The on-demand page path
     * (collectRelevantTimelineEvents → restoreRoomSessions) still restores when a
     * room is actually read, so the daily crawl is only the preemptive pass.
     */
    private suspend fun restoreMegolmSessions() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastRun = prefs.getLong(KEY_RESTORE_LAST_RUN_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - lastRun < RESTORE_INTERVAL_MS) {
            android.util.Log.d(TAG, "restore: skipped — last run ${(now - lastRun) / 60_000}m ago")
            return
        }
        // Written on START: even a scan that dies mid-run won't re-run for a day.
        prefs.edit().putLong(KEY_RESTORE_LAST_RUN_MS, now).apply()
        val c = client ?: return
        val keyBackup = keyBackupOf(c)
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
            // Parked rooms stay parked until the 4h park expires (the
            // preview/ghost paths re-check them then) — no point re-scanning
            // them on the daily crawl too (battery 2026-08-17 audit).
            if (inDecryptRestoreCooldown(roomId)) continue
            val events = collectNewestEvents(c, roomId, { maxSize = RESTORE_ROOM_EVENTS }, RESTORE_ROOM_BUDGET_MS)
                ?: continue
            val hasEncrypted = events.any {
                it.content?.isFailure == true ||
                    it.event.content is EncryptedMessageEventContent.MegolmEncryptedMessageEventContent
            }
            if (hasEncrypted) {
                val loaded = restoreRoomSessions(c, roomId, events)
                // Park only genuinely undecryptable rooms (restore found
                // nothing to load) — an all-decrypted room must not have its
                // restore suppressed for the next 4h.
                if (loaded == 0 && events.any { it.content?.isFailure == true }) {
                    parkFutileRestore(roomId)
                }
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
            slowSyncJob?.cancel()
            slowSyncJob = null
            screenOffJob?.cancel()
            screenOffJob = null
            syncMode = SyncMode.ACTIVE
            inProcessSyncRunning = false
            observedClient = null
            resetVerification()
            e2eeStateCache = null // logged out — no stale verified state
            activeRoomId = null
            pendingNotifyRoomId = null
            stopAudioPlayback()
            resetRoomList()
            // Pending voice-note copies are app-private temp files — drop them
            // with the session (the echo that would clean them never lands now).
            pendingAudioEcho.values.forEach { room ->
                room.values.forEach { pending -> pending.localFile?.let { runCatching { it.delete() } } }
            }
            // Drop the push subscription and remove the pusher from the account
            // (best-effort — an unguessable ntfy topic is harmless if it fails).
            PushChannel.stop()
            old?.let { runCatching { PushChannel.unregister(ctx, it) } }
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
                    configuration = clientConfiguration("chats"),
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
        // Binder cap: the whole reply crosses as one transaction (~1 MB hard
        // limit); the list is sorted newest-first, so the cap drops the stale
        // tail. The full census stays in _roomList (resolver keeps refreshing
        // every room); any room that gets a new message sorts back into the
        // window on the next publish.
        return _roomList.value.take(MAX_ROOMS_OVER_BINDER)
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

    /** Rooms with a background page refresh currently in flight (battery
     *  2026-08-15: the 2s ticker used to launch unbounded concurrent rebuilds). */
    private val messagePageRefreshInFlight =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Room → its last event id at the previous refresh. An unchanged id means
     *  nothing new arrived — the refresh skips the rebuild (one cheap read). */
    private val lastRefreshedEventId = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Room → elapsed-realtime timestamp until which the futile key-backup
     *  restore is suppressed (battery 2026-08-15: pre-verification history
     *  never gets its sessions back, yet every page build retried it). */
    private val decryptRestoreCooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** True while the room's key-backup restore is in the futile cooldown. */
    private fun inDecryptRestoreCooldown(matrixRoomId: RoomId): Boolean {
        val until = decryptRestoreCooldown[matrixRoomId.full] ?: return false
        return android.os.SystemClock.elapsedRealtime() < until
    }

    /** Parks a room whose key-backup restore found nothing to load: every retry
     *  path (preview, ghost walk, page build, daily crawl) stops re-attempting
     *  until the park expires. In-band sync decryption is unaffected, so a real
     *  session arriving mid-park still decrypts events. (Battery 2026-08-17
     *  audit — the pre-verification history on this account never gets its
     *  sessions back, yet the retry paths re-ran doomed restores every 60-120 s,
     *  ~3 cores continuously.) */
    private fun parkFutileRestore(matrixRoomId: RoomId) {
        decryptRestoreCooldown[matrixRoomId.full] =
            android.os.SystemClock.elapsedRealtime() + DECRYPT_RESTORE_COOLDOWN_MS
    }

    /** Room → elapsed-realtime until which a failed gap backfill is suppressed
     *  (battery: a fill that errored (network, token) is retried at most once
     *  per [GAP_BACKFILL_COOLDOWN_MS], and only while the room is active). */
    private val gapBackfillCooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** True when a gap backfill is allowed for this room: the user must be
     *  looking at it (throttle — the fill is a network + store + decrypt cost)
     *  and a previous failed fill's cooldown must have elapsed. */
    private fun isGapBackfillAllowed(matrixRoomId: RoomId): Boolean {
        if (activeRoomId != matrixRoomId.full) return false
        val until = gapBackfillCooldown[matrixRoomId.full] ?: return true
        return android.os.SystemClock.elapsedRealtime() >= until
    }

    private fun parkGapBackfill(matrixRoomId: RoomId) {
        gapBackfillCooldown[matrixRoomId.full] =
            android.os.SystemClock.elapsedRealtime() + GAP_BACKFILL_COOLDOWN_MS
    }

    /** Fills a room's timeline gap from the server (Trixnity's
     *  [net.folivo.trixnity.client.room.TimelineEventHandler.unsafeFillTimelineGaps]
     *  — a windowed GET /rooms/{id}/messages + store + chain re-link, single
     *  attempt) and re-reads the chain. The public [RoomService.fillTimelineGaps]
     *  wrapper is NOT used: it retries indefinitely on the client scope (a
     *  persistent failure would tick forever — battery), while this path hands
     *  the retry policy to our own [GAP_BACKFILL_COOLDOWN_MS]. Returns the
     *  re-walked chain, or null when there was nothing to fill / it failed (the
     *  caller keeps its current page). Bounded: one window of
     *  [GAP_BACKFILL_LIMIT] events, [GAP_BACKFILL_BUDGET_MS] budget. */
    private suspend fun backfillTimelineGap(
        c: MatrixClient,
        matrixRoomId: RoomId,
        startEventId: String,
        limit: Int,
        gapEventId: String,
    ): Pair<List<TimelineEvent>, Boolean>? {
        val ok = withTimeoutOrNull(GAP_BACKFILL_BUDGET_MS) {
            runCatching {
                c.di.get<TimelineEventHandler>()
                    .unsafeFillTimelineGaps(EventId(gapEventId), matrixRoomId, GAP_BACKFILL_LIMIT)
                    .getOrThrow()
                true
            }.getOrDefault(false)
        } ?: false
        if (!ok) {
            parkGapBackfill(matrixRoomId)
            android.util.Log.d(TAG, "gap backfill failed for $matrixRoomId — retrying in ${GAP_BACKFILL_COOLDOWN_MS / 1000}s")
            return null
        }
        // The store now holds the missing window — re-walk the chain.
        return readTimelineChainFromDb(c, matrixRoomId, startEventId, limit + 1)
    }

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
    private val SANITIZE_NAME_REGEX = Regex("[^A-Za-z0-9_-]")
    private fun sanitizeFileName(roomId: String): String =
        roomId.replace(SANITIZE_NAME_REGEX, "_")

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
        // Battery (2026-08-15 audit): never pile up refreshes — a tick that
        // finds one already in flight is a no-op (the 2s ticker used to launch
        // unbounded concurrent rebuilds that saturated the CPU).
        if (!messagePageRefreshInFlight.add(roomId)) return
        scope.launch {
            try {
                val c = client ?: return@launch
                val lastId = withTimeoutOrNull(ROOM_BUDGET_MS) {
                    c.room.getById(RoomId(roomId)).firstOrNull()?.lastEventId?.full
                } ?: return@launch
                // Nothing new since the last refresh AND the memory cache
                // still covers the requested limit → keep the cached page; a
                // quiet room costs one cheap last-event read per tick instead
                // of a full chain walk + restore + re-reads. The cache check
                // matters: the incremental cold-open path caches a limit=8
                // page, and after that every limit=20 poll bounced to the
                // DISK cache (serving the stale page it held) while this guard
                // skipped the rebuild — the anni-room misorder stayed on the
                // LP3 screen long after the page build was fixed (2026-08-21).
                val cached = messagePageCache[roomId]
                if (cached != null && cached.limit >= limit && lastRefreshedEventId[roomId] == lastId) {
                    return@launch
                }
                runCatching {
                    val page = computeMessagesPage(roomId, null, limit)
                    messagePageCache[roomId] = MessagePageEntry(
                        page,
                        limit,
                        android.os.SystemClock.elapsedRealtime(),
                    )
                    lastRefreshedEventId[roomId] = lastId
                    saveMessagePageToDisk(roomId, page)
                }
            } finally {
                messagePageRefreshInFlight.remove(roomId)
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
            if (cached != null && cached.limit >= limit) {
                // The memory page is never older than disk (disk writes are
                // throttled/skipped AFTER memory is updated), so a stale page
                // is served from memory — recompute in the background and the
                // next poll is fresh. The old path re-read + JSON-decoded the
                // same page from disk on every TTL expiry (the thread polls
                // every 3 s vs the 5 s TTL — constant disk churn; profile
                // 2026-08-20: loadMessagePageFromDisk + sanitizeFileName).
                if (android.os.SystemClock.elapsedRealtime() - cached.refreshedAtMs >= MESSAGE_PAGE_TTL_MS) {
                    refreshMessagePage(roomId, limit)
                }
                return injectPendingEchoes(roomId, cached.page)
            }
            // Cold process / first open: serve the persisted page at once and
            // recompute in the background — the next poll is fresh.
            loadMessagePageFromDisk(roomId)?.let { disk ->
                messagePageCache[roomId] = MessagePageEntry(
                    disk,
                    limit,
                    android.os.SystemClock.elapsedRealtime(),
                )
                refreshMessagePage(roomId, limit)
                return injectPendingEchoes(roomId, disk)
            }
            // Cold with no disk page (first open of the room): serve a SMALL
            // page immediately — the decrypt restores + status walks that make
            // a full page slow are what kept the thread on "Loading messages…"
            // — then recompute the full page in the background; the thread's
            // poll swaps it in seconds later (feedback 2026-08-19: "could the
            // room load incrementally? the last 5-8 messages").
            val first = computeMessagesPage(roomId, null, minOf(limit, INCREMENTAL_FIRST_PAGE), fast = true)
            messagePageCache[roomId] = MessagePageEntry(
                first,
                limit,
                android.os.SystemClock.elapsedRealtime(),
            )
            saveMessagePageToDisk(roomId, first)
            refreshMessagePage(roomId, limit)
            return first
        }
        return computeMessagesPage(roomId, beforeEventId, limit)
    }

    /**
     * Appends the optimistic voice-note/text rows to a SERVED page (memory
     * cache or disk). The send's sync echo can still be in the outbox when the
     * page is read — and re-opening a thread served the stale cached page,
     * so a just-sent message appeared missing until the background refresh
     * landed (feedback 2026-08-15: "the voice note didn't appear, even after
     * exiting and entering"). The rows dedup by their "local-…" id; the
     * refresh's [computeMessagesPage] replaces them with the real echo.
     */
    private suspend fun injectPendingEchoes(roomId: String, page: MessagesPage): MessagesPage {
        val c = client ?: return page
        if (pendingAudioEcho[roomId] == null && pendingTextEcho[roomId] == null) return page
        val result = page.messages.toMutableList()
        val existing = result.mapTo(HashSet()) { it.id }
        fun addIfMissing(message: com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message) {
            if (existing.add(message.id)) result += message
        }
        // [pendingEchoRow]: an acked send renders with its real event id (sent),
        // a failed one with the FAIL_ marker, a queued one as the optimistic
        // "local-…" row — dedup by id keeps a page that already holds the real
        // row from gaining a duplicate. Every in-flight send in the room is
        // injected (rapid sends must ALL keep their rows — feedback 2026-08-17).
        for (pending in pendingAudioEchoes(roomId)) {
            addIfMissing(
                pendingEchoRow(
                    c, RoomId(roomId), pending.txnId, pending.timestampMs,
                    body = "Voice note", contentType = "audio", durationMs = pending.durationMs,
                ),
            )
        }
        for (pending in pendingTextEchoes(roomId)) {
            addIfMissing(
                pendingEchoRow(c, RoomId(roomId), pending.txnId, pending.timestampMs, pending.body),
            )
        }
        return if (result.size == page.messages.size) page
        else MessagesPage(result, page.hasMore, page.encrypted)
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
     * Drops re-import copies from [raw] (newest first). Newest-first: the
     * first occurrence of a content signature is kept (the newest event),
     * older duplicates are the copies — a real new message whose body repeats
     * an older one must win over the older message (2026-08-23; matches
     * [dedupeChain]'s thread-path semantics). Flood events (density fallback)
     * are dropped regardless of content readability.
     */
    private fun filterGhosts(c: MatrixClient, raw: List<TimelineEvent>): List<TimelineEvent> {
        val seen = HashSet<String>()
        return raw.filter { te ->
            if (isFloodGhost(c, te, raw)) return@filter false
            val sig = contentSignature(c, te) ?: return@filter true
            if (sig in seen) false else {
                seen.add(sig)
                true
            }
        }
    }

    /**
     * Room's recent events for the bridge-flood density check. Cached per room
     * for [FLOOD_CONTEXT_TTL_MS] — the verdict (txn-id density within
     * [GHOST_BURST_WINDOW_MS]) can't change within seconds, and this read is a
     * full 250-event walk with network/decrypt timeouts, so running it per
     * event made a message burst cost N× the walk (battery 2026-08-17 audit;
     * Beeper's server does this dedup for its own client — we do it here).
     */
    private data class FloodContext(val fetchedAtMs: Long, val events: List<TimelineEvent>)
    private val floodContextCache = java.util.concurrent.ConcurrentHashMap<String, FloodContext>()

    private suspend fun ghostContext(c: MatrixClient, matrixRoomId: RoomId): List<TimelineEvent> {
        val key = matrixRoomId.full
        val now = android.os.SystemClock.elapsedRealtime()
        floodContextCache[key]?.let { cached ->
            if (now - cached.fetchedAtMs < FLOOD_CONTEXT_TTL_MS) return cached.events
        }
        val config: GetTimelineEventsConfig.() -> Unit = {
            this.maxSize = SEND_STATUS_WINDOW.toLong()
            fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
            decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
        }
        val events = collectNewestEvents(c, matrixRoomId, config, MESSAGES_BUDGET_MS) ?: emptyList()
        floodContextCache[key] = FloodContext(now, events)
        return events
    }

    /**
     * Raw timeline events for a message page, with bridge re-import copies
     * dropped (content dedup + flood fallback), plus whether older events
     * exist beyond the page (the caller's hasMore).
     *
     * Reads the room's timeline chain straight from the Room store (raw SQL
     * over the stored previous-event links) — the handler's API reads
     * (getTimelineEvents/getTimelineEvent) serve its in-memory view, which
     * after a cold start holds a partial, fluctuating subset of the room's
     * history and stops at sync-chunk boundaries ("hasGapBefore", never
     * backfilled): the newest page came back short, hasMore flipped false, and
     * older messages never loaded. The store rows are the only consistent
     * truth. Falls back to a single windowed API fetch if the query fails.
     */
    private suspend fun collectRelevantTimelineEvents(
        c: MatrixClient,
        matrixRoomId: RoomId,
        startEventId: String?,
        limit: Int,
        fast: Boolean = false,
    ): Pair<List<TimelineEvent>, Boolean> {
        if (startEventId == null) return emptyList<TimelineEvent>() to false
        val fromDb = readTimelineChainFromDb(c, matrixRoomId, startEventId, limit + 1)
        var events: List<TimelineEvent>
        var hasMore: Boolean
        if (fromDb != null) {
            events = fromDb.first
            hasMore = fromDb.second
        } else {
            val fallback = collectTimelineEvents(c, matrixRoomId, startEventId, limit + 1)
            events = fallback
            hasMore = fallback.size >= limit + 1
        }
        // Gap-marker backfill (PLAN §8, 2026-08-21): a `limited=true` sync
        // stores a gap marker whose missing window the store never fills —
        // events created during the missed window are silently absent (seen
        // live on the LP3: a WhatsApp message existed in Beeper's clients but
        // never in Chats). When the walked chain carries a gap marker, fill it
        // from the server (bounded window, active room only, cooldown on
        // failure) and re-walk. Skipped on the fast first-page path — the
        // background refresh fills it seconds later, keeping the first render
        // instant. A gap on the room's newest event (the walk's head) is
        // skipped: the next sync naturally picks up those events, and
        // Trixnity's fill no-ops it anyway.
        if (!fast && isGapBackfillAllowed(matrixRoomId)) {
            val head = events.firstOrNull()
            val gapEvent = events.firstOrNull { it.gap != null && it !== head }
            if (gapEvent != null) {
                backfillTimelineGap(c, matrixRoomId, startEventId, limit, gapEvent.event.id.full)?.let {
                    events = it.first
                    hasMore = it.second
                }
            }
        }
        // E2EE: a stored event's content can still be undecrypted (its megolm
        // session wasn't in the local store at sync time). Restore the
        // sessions from the key backup, then re-read those events once through
        // the API — the read decrypts and re-persists them. Skipped in the
        // fast first-page path (2026-08-19 feedback round): the background
        // full-page refresh resolves them; the fast page may briefly show
        // "[Encrypted message]" placeholders instead of a long loading state.
        if (!fast) {
            val undecrypted = events.filter { it.content?.isFailure == true }
            if (undecrypted.isNotEmpty()) {
                // Battery (2026-08-15 audit): events that can't decrypt (e.g.
                // pre-verification history — the bridge never re-shares those
                // sessions) made every page build / 2s refresh repeat a doomed
                // key-backup restore + per-event API re-read. When a restore finds
                // nothing to load, back off for a cooldown — the normal sync path
                // decrypts in-band the moment real sessions do arrive.
                val roomKey = matrixRoomId.full
                val cooldownUntil = decryptRestoreCooldown[roomKey]
                if (cooldownUntil == null || android.os.SystemClock.elapsedRealtime() >= cooldownUntil) {
                    val loaded = restoreRoomSessions(c, matrixRoomId, undecrypted)
                    if (loaded == 0) parkFutileRestore(matrixRoomId)
                    val config: GetTimelineEventConfig.() -> Unit = {
                        fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
                        decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
                    }
                    val resolved = HashMap<String, TimelineEvent>()
                    undecrypted.forEach { te ->
                        withTimeoutOrNull(DECRYPT_WAIT_MS) {
                            c.room.getTimelineEvent(matrixRoomId, te.event.id, config).firstOrNull()
                                ?.takeIf { it.content?.getOrNull() != null }
                        }?.let { resolved[te.event.id.full] = it }
                    }
                    if (resolved.isNotEmpty()) {
                        events = events.map { resolved[it.event.id.full] ?: it }
                    }
                }
            }
        }
        // Bridge re-import dedup (battery 2026-08-15 audit): the old
        // filterGhosts heuristic (per-page signature + a 30-per-minute txn-id
        // flood rule) was assumption-based and could drop real messages; the
        // account's duplicates are exact re-imports, so keep the first
        // occurrence of each content signature in the walked chain and drop
        // the copies. Undecrypted events (no readable content) pass through.
        // Ceiling: bounded by the walked window — a burst longer than the page
        // still spills one copy per page (ingest-time dedup is the endgame).
        return dedupeChain(c, events) to hasMore
    }

    /** First occurrence of each content signature in [raw] (chain order,
     *  newest first). Events without readable content fall back to their
     *  event id, so they pass through untouched. */
    private fun dedupeChain(c: MatrixClient, raw: List<TimelineEvent>): List<TimelineEvent> {
        val seen = HashSet<String>()
        val out = ArrayList<TimelineEvent>(raw.size)
        for (te in raw) {
            if (seen.add(contentSignature(c, te) ?: te.event.id.full)) out += te
        }
        return out
    }

    /**
     * The room's timeline chain (newest-first, [startEventId] inclusive)
     * straight from the store via a recursive SQL walk over the stored
     * previous-event links. Returns (events, hasMore) where hasMore says the
     * deepest event has a further previous link; null when the store query
     * isn't available (the caller falls back to the API). Bounded by
     * [maxEvents].
     */
    private suspend fun readTimelineChainFromDb(
        c: MatrixClient,
        matrixRoomId: RoomId,
        startEventId: String,
        maxEvents: Int,
    ): Pair<List<TimelineEvent>, Boolean>? {
        val db = runCatching {
            c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class)
        }.onFailure { e ->
            android.util.Log.w(TAG, "readTimelineChainFromDb: TrixnityRoomDatabase not in DI — falling back to the API walk", e)
        }.getOrNull() ?: return null
        val json = runCatching { c.di.get<Json>() }.getOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val sql = """
                    WITH RECURSIVE chain(prev, value, n) AS (
                        SELECT json_extract(value, '$.previousEventId'), value, 1
                        FROM TimelineEvent WHERE roomId = ? AND eventId = ?
                        UNION ALL
                        SELECT json_extract(t.value, '$.previousEventId'), t.value, c.n + 1
                        FROM TimelineEvent t JOIN chain c ON t.eventId = c.prev
                        WHERE c.n < ?
                    )
                    SELECT value FROM chain ORDER BY n
                """.trimIndent()
                val events = ArrayList<TimelineEvent>()
                var hasMore = false
                db.openHelper.writableDatabase.query(
                    sql,
                    // maxEvents must bind as a number — a string makes SQLite's
                    // `n < '21'` (INTEGER vs TEXT) compare true for every row.
                    arrayOf<Any>(matrixRoomId.full, startEventId, maxEvents),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val value = cursor.getString(0) ?: continue
                        // The DI Json carries the store's serializers module,
                        // which registers TimelineEvent's serializer — the
                        // reified decode resolves it.
                        events += json.decodeFromString<TimelineEvent>(value)
                    }
                }
                if (events.isNotEmpty()) {
                    // The deepest event's stored prev link decides hasMore.
                    hasMore = events.last().previousEventId != null
                }
                android.util.Log.d(TAG, "readTimelineChainFromDb: $matrixRoomId from=$startEventId → ${events.size} events hasMore=$hasMore")
                events to hasMore
            }.onFailure { e ->
                android.util.Log.w(TAG, "readTimelineChainFromDb: store query failed — falling back to the API walk", e)
            }.getOrNull()
        }
    }

    /**
     * One room's newest page, computed from the timeline. Shared by the
     * [getMessages] cache path and the background [refreshMessagePage]; the
     * body itself is unchanged from the pre-cache implementation.
     */
    /**
     * Whether a pending echo's real event is decrypted and renderable. The
     * store decode alone can leave an E2EE event's content unresolved (it
     * resolves when the event is re-read through the API, which triggers
     * decryption), so a matching-but-unresolved echo gets one bounded API
     * re-read — the device holds the outbound megolm session for its own
     * sends, so this resolves promptly. Returns the resolved event, or null
     * when it is not renderable yet.
     */
    private suspend fun resolvePendingEcho(
        c: MatrixClient,
        matrixRoomId: RoomId,
        events: List<TimelineEvent>,
        txnId: String,
    ): TimelineEvent? {
        val echo = events.firstOrNull { txnIdOf(it) == txnId } ?: return null
        if (echo.content?.getOrNull() != null) return echo
        val config: GetTimelineEventConfig.() -> Unit = {
            fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
            decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
        }
        return withTimeoutOrNull(DECRYPT_WAIT_MS) {
            c.room.getTimelineEvent(matrixRoomId, echo.event.id, config)
                .filterNotNull().firstOrNull { it.content?.getOrNull() != null }
        }
    }

    /** Trixnity's KeyBackupService via the client's DI; null when the key
     *  backup module isn't registered (e.g. an account without one). */
    private fun keyBackupOf(c: MatrixClient): net.folivo.trixnity.client.key.KeyBackupService? =
        runCatching {
            c.di.get<net.folivo.trixnity.client.key.KeyBackupService>(
                org.koin.core.qualifier.named<net.folivo.trixnity.client.key.KeyBackupService>(),
            )
        }.getOrNull()

    /** Collects a room's newest events (newest-first) from Trixnity's
     *  newest-page stream, bounded by [timeoutMs]; null when the budget ran
     *  out (callers fall back to an empty result). */
    private suspend fun collectNewestEvents(
        c: MatrixClient,
        matrixRoomId: RoomId,
        config: GetTimelineEventsConfig.() -> Unit,
        timeoutMs: Long,
    ): List<TimelineEvent>? = withTimeoutOrNull(timeoutMs) {
        val list = mutableListOf<TimelineEvent>()
        c.room.getLastTimelineEvents(matrixRoomId, config).filterNotNull().first()
            .collect { eventFlow ->
                eventFlow.filterNotNull().firstOrNull()?.let { list.add(it) }
            }
        list
    }

    /** Row for a send whose sync echo hasn't rendered yet. The message is
     *  treated as SENT the moment the server acks it: once the outbox records
     *  the real event id (the /send 200 — ~1s after the send, long before the
     *  sync echo), the row carries that id + the send time; a recorded outbox
     *  error renders as "! not delivered" (FAIL_ status, shown by the tool);
     *  only a still-queued send keeps the optimistic "local-…" row. The tool
     *  shows the send time for all three, so the thread reflects a send
     *  immediately, until proven sent or not delivered (feedback 2026-08-17).
     */
    private suspend fun pendingEchoRow(
        c: MatrixClient,
        matrixRoomId: RoomId,
        txnId: String,
        timestampMs: Long,
        body: String,
        contentType: String = "text",
        durationMs: Long? = null,
    ): com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message {
        val outbox = withTimeoutOrNull(OUTBOX_READ_TIMEOUT_MS) {
            c.room.getOutbox(matrixRoomId, txnId).first()
        }
        return com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
            id = outbox?.eventId?.full ?: "$LOCAL_PENDING_ID_PREFIX$txnId",
            sender = c.userId.full,
            senderName = "",
            body = body,
            timestampMs = timestampMs,
            isMine = true,
            sendStatus = if (outbox?.sendError != null) "FAIL_LOCAL_SEND" else null,
            contentType = contentType,
            durationMs = durationMs,
        )
    }

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

        // The newest page (null cursor) pages BACKWARDS from the room's newest
        // event instead of using getLastTimelineEvents' newest-page stream:
        // that stream serves the handler's in-memory/cache view, which after a
        // cold start holds a partial and fluctuating subset of the room's real
        // history (observed: a 64-event room returned 9-17 events, hasMore
        // flipped false, and pagination died — "older messages don't load").
        // The Room store's lastEventId is the authoritative newest event, and
        // [collectRelevantTimelineEvents] walks BACKWARDS from it, chaining
        // across sync-chunk boundaries via the stored previous-event links.
        // [keepCursor] keeps that event as the page's newest row; older pages
        // drop their boundary cursor instead.
        val keepCursor = beforeEventId == null
        val pageCursor = if (keepCursor) {
            withTimeoutOrNull(ROOM_BUDGET_MS) {
                c.room.getById(matrixRoomId).firstOrNull()?.lastEventId?.full
            }
        } else {
            beforeEventId
        }
        if (keepCursor && pageCursor == null) {
            // No events in the room at all (the getById read is a cache hit —
            // only an empty room misses).
            return MessagesPage(emptyList(), false)
        }

        // Lazy per-room decrypt, but seeded BEFORE the walk: the seed read
        // triggers decryption of the page's events and restores the megolm
        // sessions that are missing (a decrypt that can't land until the
        // sessions are in the store made the first open slow). Skipped inside
        // the futile-restore cooldown (battery 2026-08-15 audit) and in the
        // fast first-page path (2026-08-19 feedback round — the background
        // full-page refresh primes decrypts).
        if (!fast) {
            val seed = collectTimelineEvents(c, matrixRoomId, pageCursor, limit + 1)
            // Restore only when the page still has undecryptable events — an
            // all-decrypted room needs no priming, so skip the parse entirely
            // (battery 2026-08-17: the seed restore ran on every page read).
            if (seed.any { it.content?.isFailure == true } && !inDecryptRestoreCooldown(matrixRoomId)) {
                // Park genuinely undecryptable pages too — the same futility signal
                // as the page-build path, so opening a doomed room doesn't re-seed
                // a restore on every read (battery 2026-08-17 audit).
                if (restoreRoomSessions(c, matrixRoomId, seed) == 0) {
                    parkFutileRestore(matrixRoomId)
                }
            }
        }

        // The page comes from [collectRelevantTimelineEvents] — a straight
        // store read (raw SQL over the previous-event links, per-event API
        // re-reads for anything still undecrypted), so the page size and
        // hasMore no longer depend on the handler's in-memory view.
        var events: List<TimelineEvent>
        var hasMore = false
        val (e, h) = collectRelevantTimelineEvents(c, matrixRoomId, pageCursor, limit, fast)
        events = e
        hasMore = h
        // The retries exist to catch a decrypt that lands a beat late. Inside
        // the restore cooldown they can't succeed — skip them (battery audit);
        // the fast first-page path skips them too (the background refresh
        // re-reads with full decrypt handling).
        if (!fast && !inDecryptRestoreCooldown(matrixRoomId)) {
            repeat(DECRYPT_RETRIES) {
                val stillEncrypted = events.any { it.content?.isFailure == true }
                if (!stillEncrypted) return@repeat
                kotlinx.coroutines.delay(DECRYPT_RETRY_DELAY_MS)
                val (e2, h2) = collectRelevantTimelineEvents(c, matrixRoomId, pageCursor, limit, fast)
                events = e2
                hasMore = h2
            }
        }
        // Older-page dead-end guard (2026-08-17): the chain can run through a
        // block of events that build no rows — the re-import's m.replace edits
        // are dropped by [messageFrom], so an older page landing on the edit
        // wall returned an empty page. The tool's cursor can't advance past
        // invisible events, so it re-polled the same cursor forever (1 req/s
        // storm → server ANR). Walk deeper (in big steps — walls can be 100+
        // edits, e.g. the Crocs room's 168) until the page holds renderable
        // events or the chain ends, bounded.
        //
        // Extended to the NEWEST page (2026-08-23): the bridge's history
        // re-import (the 09:08 wall after a WhatsApp number change) leaves a
        // run of re-import copies at the top of a room that resolve to EMPTY
        // bodies — [messageFrom] now drops blank rows, so a page sitting
        // entirely on copies rendered "no messages" (LP3: the Lillian room)
        // while Beeper showed the real conversation. Walk past them to the
        // first non-empty message the same way.
        if (hasMore) {
            var guard = 0
            while (guard++ < OLDER_PAGE_SKIP_WALKS &&
                events.drop(1).none { isRenderableRow(it) && previewText(it)?.isNotBlank() == true }
            ) {
                val deepest = events.lastOrNull()?.event?.id?.full ?: break
                val (e2, h2) = collectRelevantTimelineEvents(c, matrixRoomId, deepest, OLDER_PAGE_SKIP_STEP, fast)
                if (e2.size <= 1) { hasMore = h2; break }
                events += e2.drop(1) // e2 re-includes `deepest` (start-inclusive walk)
                hasMore = h2
            }
        }
        android.util.Log.d(
            TAG,
            "getMessages: room=$matrixRoomId before=$beforeEventId limit=$limit page=${events.size} hasMore=$hasMore",
        )

        val result = mutableListOf<com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message>()
        val startIndex = if (keepCursor) 0 else 1 // drop the boundary cursor on older pages
        // Delivery status only matters for the newest page (what the user just
        // sent): the status events sit right after the message in the timeline.
        // Older pages don't get the marker (Phase 10, minimal scope). The fast
        // first-page path skips the status windows (each is a 250-event
        // network walk); the background refresh adds them.
        val sendStatuses = if (beforeEventId == null && !fast) sendStatusByEventId(c, matrixRoomId) else emptyMap()
        // Same scoping for read receipts: only the newest page reports whether
        // the other party has read an outgoing message (receipts point at the
        // newest events; an older page's messages are always "read" in practice
        // but re-resolving each receipt per page isn't worth it).
        val readEventIds = if (beforeEventId == null && !fast) readReceiptsByEvent(c, matrixRoomId, events) else emptySet()
        // Reactions, same newest-page scope: m.reaction events sit after their
        // target in the timeline, so the newest window carries the reactions
        // that matter. Older pages report none (minimal Phase 14 scope).
        val reactionsByEvent = if (beforeEventId == null && !fast) reactionLabelsByEvent(c, matrixRoomId) else emptyMap()
        // A just-sent message's echo can sit in the timeline before its
        // decryption lands; the optimistic rows below represent it, so skip
        // the undecrypted "[Encrypted]" placeholder — a message the user just
        // sent from this device must never read "waiting for key" (feedback
        // 2026-08-17: the newest sent message appeared missing on re-entry).
        val pendingTxnIds = buildSet {
            pendingAudioEcho[roomId]?.keys?.let { addAll(it) }
            pendingTextEcho[roomId]?.keys?.let { addAll(it) }
        }
        for (i in startIndex until events.size) {
            if (result.size >= limit) break
            val te = events[i]
            val txnId = txnIdOf(te)
            if (txnId != null && txnId in pendingTxnIds && te.content?.getOrNull() == null) continue
            messageFrom(
                c,
                matrixRoomId,
                te,
                sendStatuses[te.event.id.full],
                read = te.event.id.full in readEventIds,
                reactions = reactionsByEvent[te.event.id.full].orEmpty(),
            )?.let { result.add(it) }
        }
        // Optimistic rows for sends whose sync echo hasn't landed (voice notes
        // + text share the resolve/replace dance, feedback 2026-08-13/14): a
        // just-sent message shows in every newest page (even a re-opened
        // thread) until its sync echo replaces it. Only a DECRYPTED echo
        // retires the row — the echo is re-read via the API (which triggers
        // decryption; a store decode can leave E2EE content unresolved), so a
        // just-sent note never flickers into "[Encrypted]" (feedback
        // 2026-08-17).
        suspend fun insertPendingEchoes(
            pendings: List<PendingSend>,
            removeFrom: (String) -> Unit,
            rowFactory: suspend (PendingSend) -> com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message,
        ) {
            for (pending in pendings) {
                // The loop skips the echo only while its content is unresolved —
                // the in-page render below must fire only then, or the real row
                // is added twice (LazyColumn duplicate-key crash, 2026-08-17).
                val echo = events.firstOrNull { txnIdOf(it) == pending.txnId }
                val echoWasSkipped = echo != null && echo.content?.getOrNull() == null
                val resolved = resolvePendingEcho(c, matrixRoomId, events, pending.txnId)
                if (resolved != null) {
                    removeFrom(pending.txnId)
                    if (echoWasSkipped && beforeEventId == null && result.size < limit + 1) {
                        messageFrom(
                            c, matrixRoomId, resolved,
                            sendStatuses[resolved.event.id.full],
                            read = resolved.event.id.full in readEventIds,
                            reactions = reactionsByEvent[resolved.event.id.full].orEmpty(),
                        )?.let { result.add(0, it) }
                    }
                } else if (beforeEventId == null && result.size < limit + 1) {
                    // Insert at index 0: [result] is newest-first, so index 0
                    // is the NEWEST slot — after the final ordering the row
                    // lands at the newest end. (The old append put it at the
                    // OLDEST end: the just-sent message surfaced at the TOP of
                    // the thread and dead-ended pagination via the local-row
                    // guard; feedback 2026-08-17.) Oldest-first iteration keeps
                    // rapid sends in chronological order.
                    result.add(0, rowFactory(pending))
                }
            }
        }
        // Voice-note rows first, then text — same order as the two original
        // loops, so rapid mixed sends keep their relative order.
        insertPendingEchoes(
            pendingAudioEchoes(roomId),
            { txn ->
                // The echo landed — the pending copy is no longer needed (the
                // real event's media lives in the matrix_media store).
                pendingAudioEcho[roomId]?.get(txn)?.localFile?.let { runCatching { it.delete() } }
                pendingAudioEcho[roomId]?.remove(txn)
            },
            { p ->
                val a = p as PendingAudioSend
                pendingEchoRow(
                    c, matrixRoomId, a.txnId, a.timestampMs,
                    body = "Voice note", contentType = "audio", durationMs = a.durationMs,
                )
            },
        )
        // Text rows: the row id matches the tool's "local-<txn>" id, so the
        // tool's own pending row dedupes.
        insertPendingEchoes(
            pendingTextEchoes(roomId),
            { pendingTextEcho[roomId]?.remove(it) },
            { p ->
                val t = p as PendingTextSend
                pendingEchoRow(c, matrixRoomId, t.txnId, t.timestampMs, t.body)
            },
        )
        // Order the page by each message's real time (origin_server_ts), not the
        // timeline's topological chain order. Beeper bridges ingest messages
        // when they arrive but stamp them with the ORIGINAL send time, so the
        // chain can place a 1:43 PM message above a 1:41 PM one (the 1:41 was
        // ingested late) — the visible "wrong order" in bridged rooms
        // (2026-08-21, the anni room on the LP3). The sort is stable, so equal
        // timestamps keep the chain order — a no-op for native (non-bridged)
        // rooms, whose homeserver stamps events at ingest. Only CONFIRMED rows
        // sort by timestamp: pending "local-…" rows carry device-clock times
        // that can lag the server clock, which would sort a just-sent message
        // into the middle of the thread (2026-08-23); they are the newest
        // sends by definition, so they append last, in send order.
        val (pending, confirmed) = result.partition { it.id.startsWith(LOCAL_PENDING_ID_PREFIX) }
        // Sort the REVERSED (oldest-first) confirmed rows like the old code
        // did, so equal timestamps keep the old tie order; then append the
        // pending rows (also oldest-first) at the newest end.
        val oldestFirst = confirmed.reversed().sortedWith(compareBy { it.timestampMs }) + pending.reversed()
        return MessagesPage(messages = oldestFirst, hasMore = hasMore)
    }

    /** Type of Beeper's per-message delivery-state events (unencrypted, posted
     *  by the bridge right after the message). */
    private val BEEPER_SEND_STATUS_EVENT_TYPE = "com.beeper.message_send_status"

    /** Type of the room-state event flagging bridge/service bots ("functional
     *  members", Element's MSC): e.g. Beeper's @whatsappbot. Those users post
     *  m.read receipts as bridge bookkeeping, not as a human read — see
     *  [readReceiptsByEvent]. */
    private val FUNCTIONAL_MEMBERS_STATE_TYPE = "io.element.functional_members"
    private val BRIDGE_STATE_TYPE = "m.bridge"
    private val LEGACY_BRIDGE_STATE_TYPE = "uk.half-shot.bridge"

    /** Memoized bridge-bot id per room ("" = non-bridged), resolved lazily on
     *  a room's first newest-page build — see [bridgeBotOf]. */
    private val bridgeBotByRoom = java.util.concurrent.ConcurrentHashMap<String, String>()

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
        // Walk the RAW chain (like the page build), not the handler's
        // getLastTimelineEvents view: that view can miss events in a room
        // with a chain gap — the Annette room's FAIL statuses never reached
        // the rows or the session heal ("2 messages show sent but weren't
        // delivered", LP3 2026-08-23; the page build abandoned that API for
        // the same reason — "a partial and fluctuating subset"). Statuses are
        // unencrypted, so the fast walk (no gap backfill / session restore)
        // is enough; the FAIL events sit just past the room's newest events.
        val start = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).firstOrNull()?.lastEventId?.full
        } ?: return emptyMap()
        val collected = collectRelevantTimelineEvents(c, matrixRoomId, start, SEND_STATUS_WINDOW, fast = true).first
        for (te in collected) {
            val content = te.event.content
            if (content !is UnknownEventContent || content.eventType != BEEPER_SEND_STATUS_EVENT_TYPE) continue
            val raw = content.raw
            // Beeper's WhatsApp/… bridges report delivery as a SUCCESS status
            // carrying the recipient ghosts in "delivered_to_users" — that IS
            // the delivered state Beeper's own clients show (the statuses are
            // otherwise only SUCCESS, never "DELIVERED").
            val status = raw["status"]?.jsonPrimitive?.contentOrNull
                ?.let {
                    if (raw["delivered_to_users"]?.jsonArray?.isNotEmpty() == true && it == "SUCCESS") "DELIVERED" else it
                }
                ?: continue
            // A bridge FAIL with an undecryptable reason means the bridge never
            // received this room's megolm key (the LP3's outbound session
            // predates the bridge's current identity — the other device's
            // sessions decrypt fine). Rotate the session ONCE per room per run
            // the moment the FAIL lands: waiting for the next send let the
            // first breach in a room fail (LP3 feedback 2026-08-23, Annette +
            // Anni rooms: every LP3 send undecryptable for the bridge).
            raw["reason"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { status.startsWith("FAIL") && it.contains("undecryptable") }
                ?.let { rotateOnBridgeUndecryptable(c, matrixRoomId, it) }
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
        val collected = collectNewestEvents(c, matrixRoomId, config, MESSAGES_BUDGET_MS)
            ?: return emptyMap()
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
    /** The room's bridge-bot user id ("" = non-bridged) — the bot whose m.read
     *  receipts are Beeper bridge bookkeeping, not human reads. Resolved lazily
     *  on a room's first newest-page build and memoized per room: the old
     *  per-pass resolver was the 280-400% CPU battery disaster (WORKLOG
     *  2026-08-17). Beeper flags its bot via m.bridge ("bridgebot"); the
     *  fallbacks cover older bridges (uk.half-shot.bridge) and rooms without
     *  bridge state (functional_members "service_members" — which misses the
     *  Instagram DM, whose @instagramgobot posts receipts too). Callers must be
     *  inside a store transaction (Room-backed repo reads need one). */
    /**
     * NOTE (2026-08-22): the m.bridge channel's `fi.mau.receiver` looks like
     * the contact's number but is the USER'S OWN WhatsApp number — it repeats
     * across every LID DM. A contact-phone source was explored here and
     * reverted; see the comment at contactIdentifierOf/contactIdentifier in
     * the app. LID contacts carry no number in the room data (Beeper
     * resolves LIDs server-side).
     */
    private suspend fun bridgeBotOf(c: MatrixClient, matrixRoomId: RoomId): String {
        bridgeBotByRoom[matrixRoomId.full]?.let { return it }
        val stateRepo = c.di.get<RoomStateRepository>(RoomStateRepository::class)
        suspend fun bridgebotOfState(type: String): String? =
            (stateRepo.get(RoomStateRepositoryKey(matrixRoomId, type), "")?.content as? UnknownEventContent)
                ?.raw?.get("bridgebot")?.jsonPrimitive?.contentOrNull
        val bot = bridgebotOfState(BRIDGE_STATE_TYPE)
            ?: bridgebotOfState(LEGACY_BRIDGE_STATE_TYPE)
            ?: (stateRepo.get(RoomStateRepositoryKey(matrixRoomId, FUNCTIONAL_MEMBERS_STATE_TYPE), "")?.content as? UnknownEventContent)
                ?.raw?.get("service_members")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val resolved = bot ?: ""
        bridgeBotByRoom[matrixRoomId.full] = resolved
        return resolved
    }

    private suspend fun readReceiptsByEvent(
        c: MatrixClient,
        matrixRoomId: RoomId,
        events: List<TimelineEvent>,
    ): Set<String> {
        val rawIndex = HashMap<String, Int>() // event id -> newest-first index
        events.forEachIndexed { i, te -> rawIndex[te.event.id.full] = i }
        val (receiptsByUser, bridgebot) = withTimeoutOrNull(MESSAGES_BUDGET_MS) {
            // The Room-backed repositories only work inside a store transaction
            // (the flow APIs set it up themselves; direct repo reads need the
            // explicit scope, or Room answers "read transaction is missing").
            val txManager = c.di.get<RepositoryTransactionManager>(RepositoryTransactionManager::class)
            txManager.readTransaction {
                val receipts = c.di.get<RoomUserReceiptsRepository>(RoomUserReceiptsRepository::class)
                    .get(matrixRoomId)
                // Bridge bots (Beeper's @whatsappbot, @instagramgobot, …) post
                // m.read receipts as room bookkeeping, not human reads — ignoring
                // them keeps the "seen" tag honest. Resolve the bot lazily
                // (memoized, see [bridgeBotOf]) and only when there are receipts
                // to filter.
                val bridgebot = if (receipts.isEmpty()) "" else bridgeBotOf(c, matrixRoomId)
                receipts to bridgebot
            }
        } ?: return emptySet()
        val readEventIds = mutableSetOf<String>()
        for ((userId, roomUserReceipts) in receiptsByUser) {
            if (userId == c.userId || userId.full == bridgebot) continue
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
        // A null read (timeout / trust store not warm) must NOT read as
        // "unverified": the encrypted-room fast path then fires intermittently
        // on a verified device, emptying the thread mid-use (LP3 2026-08-17:
        // verified at 11:30:35, "unverified" 25 s later — the Note-to-self
        // page collapsed). A genuinely unverified device's read succeeds and
        // returns a non-CrossSigned trust level.
        val trust = withTimeoutOrNull(KEY_BACKUP_VERIFY_TIMEOUT_MS) {
            c.key.getTrustLevel(c.userId, c.deviceId).firstOrNull()
        } ?: return true
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

        val keyBackup = keyBackupOf(c)
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

    /** An own send leaves its echo waiting on the server; without a wake,
     *  the outbox drain + echo are gated on the sync cycle — the long-poll
     *  can hold up to 30s, the slow rounds 5/30 min — so the just-sent
     *  message sat on "SENDING" and the room panel kept the pre-send preview
     *  (LP3 2026-08-17, user report: minutes). A queued syncOnce ABORTS an
     *  in-flight long-poll immediately (SyncApiClient selects it away), so
     *  run ONE in both modes: the round drains the outbox (the message
     *  reaches the server now) and the follow-up long-poll returns the echo
     *  in ~1-2s — same ~640ms cost as a push-wake round, only on a user
     *  send. Also wakes the room-list resolver so the panel recomputes the
     *  row. Skipped when sync is paused by the user.
     */
    private fun wakeAfterSend(roomId: String) {
        roomListDirty = true
        wakeRoomList()
        // Publish the sent room's pending bump NOW — [publishRoomList] alone
        // waits for the resolver's next full pass, which on a big bridged
        // account is gated by the resolve loop's ghost-walk work (measured
        // 10-40s on the LP3). The bump is a direct cache update: row time +
        // preview come from the in-flight send, so the room jumps to the top
        // the moment the tool's next list read lands. [resolveRoomListEntry]'s
        // own pending override keeps the row bumped until the echo lands.
        val pending = newestPending(roomId)
        if (pending != null) {
            val preview = when (pending) {
                is PendingTextSend ->
                    if (roomListCache[roomId]?.room?.isDirect == true) pending.body else "You: ${pending.body}"
                is PendingAudioSend -> "Voice note"
            }
            roomListCache[roomId]?.let { entry ->
                roomListCache[roomId] = entry.copy(
                    room = entry.room.copy(
                        lastTimestampMs = maxOf(entry.room.lastTimestampMs, pending.timestampMs),
                        lastMessage = preview,
                    ),
                )
            }
            publishRoomList()
        }
        val c = client ?: return
        if (!syncEnabled) return
        slowSyncJob?.cancel()
        slowSyncJob = null
        scope.launch {
            timedSyncOnce(c, "send")
                .onFailure { android.util.Log.w(TAG, "send-wake sync failed: ${it.message}") }
            // Slow mode owns the rounds (active mode's long-poll restarts
            // itself after the syncOnce). The screen may have come back on
            // mid-wake — enterActiveSync owns sync then; restart the fallback
            // rounds only while it is still dark.
            val power = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (syncMode == SyncMode.SLOW && power?.isInteractive == false) {
                slowSyncJob = startSlowSyncRounds(c)
            }
        }
    }

    // --- Megolm session self-heal (2026-08-23) -----------------------------
    // The 2026-08-12 bridge-key bug window left some rooms with an outbound
    // megolm session created while the bridge device was absent from the
    // key-share set; since per-send rotation was removed (2026-08-22) the
    // stale session is reused forever and the bridge reports an encryption
    // issue for every send. One-shot self-heal: when a FAIL send-status for
    // one of our own messages cites encryption/session problems, rotate the
    // outbound session once and never check the room again.

    /** Rooms whose outbound megolm session was already rotated (one-shot). */
    private val megolmRotatedRooms = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Room → (checked-at elapsedRealtime, stale verdict) — the check walks
     *  the room's newest events, so it's cached for [MEGOLM_STALE_CHECK_TTL_MS]
     *  instead of running before every send. */
    private val encryptionFailCheck = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** The FAIL reason that implicates the outbound session — observed on the
     *  LP3 2026-08-23: `com.beeper.undecryptable_event` on every Anni send
     *  (device DB). Only this exact marker rotates; other FAIL reasons
     *  (delivery failures etc.) must not churn the session. */
    private val MEGOLM_STALE_KEYWORDS = listOf("undecryptable")

    /** Rotates a stale outbound megolm session once per room. Called before
     *  every send; the scan is TTL-cached, and a parse failure only logs —
     *  the send must never fail because of the check. */
    private suspend fun rotateStaleMegolmIfNeeded(c: MatrixClient, matrixRoomId: RoomId) {
        if (megolmRotatedRooms.contains(matrixRoomId.full)) return
        val now = android.os.SystemClock.elapsedRealtime()
        encryptionFailCheck[matrixRoomId.full]?.let { (fetchedAt, stale) ->
            if (now - fetchedAt < MEGOLM_STALE_CHECK_TTL_MS) {
                if (stale) rotateStaleMegolmOnce(c, matrixRoomId, "cached")
                return
            }
        }
        val reason = staleMegolmFailReason(c, matrixRoomId)
        encryptionFailCheck[matrixRoomId.full] = now to (reason != null)
        if (reason != null) rotateStaleMegolmOnce(c, matrixRoomId, reason)
    }

    /**
     * Kicks off the stale-session self-heal WITHOUT blocking the send. The
     * first scan per room walks ~250 events with decrypt waits — run inline it
     * froze the composer for seconds (LP3 feedback 2026-08-23). The scan only
     * acts on FAILs the bridge already posted, so an async heal costs at most
     * one more failed send in an already-broken room; the verdict is
     * TTL-cached after the first scan, and [rotateStaleMegolmIfNeeded] is
     * otherwise a cheap map read.
     */
    private fun healStaleMegolmIfNeeded(c: MatrixClient, matrixRoomId: RoomId) {
        scope.launch { rotateStaleMegolmIfNeeded(c, matrixRoomId) }
    }

    /**
     * Rotation triggered directly by a bridge FAIL in the status walk
     * ([sendStatusByEventId], which runs on every newest-page build): once per
     * room per process run, fire-and-forget. The next send creates a fresh
     * session whose key goes to all currently-known devices — including the
     * bridge's current identity.
     */
    private fun rotateOnBridgeUndecryptable(c: MatrixClient, matrixRoomId: RoomId, reason: String) {
        if (megolmRotatedRooms.contains(matrixRoomId.full)) return
        scope.launch { rotateStaleMegolmOnce(c, matrixRoomId, reason) }
    }

    /** First FAIL send-status reason for one of our own messages in the
     *  room's newest window that implicates the encryption session, or null.
     *  Mirrors [sendStatusByEventId]'s walk + parse (unencrypted status
     *  events, no decrypt wait). */
    private suspend fun staleMegolmFailReason(c: MatrixClient, matrixRoomId: RoomId): String? {
        val config: GetTimelineEventsConfig.() -> Unit = {
            this.maxSize = SEND_STATUS_WINDOW.toLong()
            fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
            decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
        }
        val events = runCatching {
            collectNewestEvents(c, matrixRoomId, config, MESSAGES_BUDGET_MS)
        }.getOrElse { e ->
            android.util.Log.w(TAG, "megolm stale check failed for $matrixRoomId: ${e.message}")
            return null
        } ?: return null
        val statuses = mutableMapOf<String, String>()
        val reasons = mutableMapOf<String, String>()
        for (te in events) {
            val content = te.event.content
            if (content !is UnknownEventContent || content.eventType != BEEPER_SEND_STATUS_EVENT_TYPE) continue
            val raw = content.raw
            val status = raw["status"]?.jsonPrimitive?.contentOrNull ?: continue
            val relatedEventId = raw["m.relates_to"]?.jsonObject?.get("event_id")?.jsonPrimitive?.contentOrNull
                ?: content.relatesTo?.eventId?.full
                ?: continue
            // Newest-first: the first status for a message is the latest one.
            if (relatedEventId !in statuses) {
                statuses[relatedEventId] = status
                reasons[relatedEventId] = raw["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }
        for (te in events) {
            val id = te.event.id.full
            if (te.event.sender != c.userId || id !in statuses) continue
            val status = statuses[id] ?: continue
            if (!status.startsWith("FAIL")) continue
            val reason = reasons[id].orEmpty()
            if (MEGOLM_STALE_KEYWORDS.any { reason.contains(it, ignoreCase = true) }) return reason
        }
        return null
    }

    private suspend fun rotateStaleMegolmOnce(c: MatrixClient, matrixRoomId: RoomId, reason: String) {
        runCatching {
            c.di.get<OlmCryptoStore>(OlmCryptoStore::class).updateOutboundMegolmSession(matrixRoomId) { null }
        }.onSuccess {
            megolmRotatedRooms.add(matrixRoomId.full)
            android.util.Log.d(TAG, "rotated stale megolm session for $matrixRoomId (bridge FAIL: $reason)")
        }
    }

    suspend fun sendMessage(
        roomId: String,
        body: String,
        replyToEventId: String?,
    ): com.thelightphone.sdk.shared.LightServiceMethod.SendMessage.Response {
        val c = client ?: error("not logged in")
        val matrixRoomId = RoomId(roomId)
        // One-shot self-heal for the 2026-08-12 bridge-key bug window (see the
        // megolm section above) — before enqueueing, never per-send, and never
        // blocking: the first scan per room walks ~250 events and froze the
        // composer for seconds (LP3 feedback 2026-08-23: "pressed send 3×,
        // nothing, then it sent").
        healStaleMegolmIfNeeded(c, matrixRoomId)
        // No per-send megolm rotation: a fresh session per message made a burst
        // of sends race — a retried/late event encrypted with an older session
        // arrived after the newer session's room key, and Beeper flagged it
        // "sent using an outdated encryption session" (2026-08-22). The
        // rotation was a stopgap for the 2026-08-12 bridge-key bug; that root
        // cause (broken /keys/claim deserialization) is fixed, and Trixnity
        // re-shares the room key to new devices on every send with the existing
        // session, so the bridge keeps getting keys without per-message churn.
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
        val roomPending = pendingTextEcho.computeIfAbsent(matrixRoomId.full) { java.util.concurrent.ConcurrentHashMap() }
        roomPending[txnId] = PendingTextSend(txnId, System.currentTimeMillis(), body)
        // Keep the cached/disk newest page — re-opening the thread serves it
        // instantly with the optimistic row injected ([injectPendingEchoes]),
        // and the active-room refresher (or the next poll) recomputes the page
        // once the sync echo lands (feedback 2026-08-15: re-open showed
        // "Loading messages…" because the send had dropped the cache).
        // Fetch the echo + refresh the panel even in slow-sync mode (screen off).
        wakeAfterSend(matrixRoomId.full)
        android.util.Log.d(TAG, "SendMessage: room=$roomId txn=$txnId body=$body")
        return com.thelightphone.sdk.shared.LightServiceMethod.SendMessage.Response(
            transactionId = txnId,
            eventId = null, // not awaited — the sync echo supplies the real id
        )
    }

    /**
     * Clears the outbox send error on [transactionId] so Trixnity's retry loop
     * re-sends the same transaction (the outbox store emission restarts the
     * loop; the PUT is idempotent by txn id, so a message the homeserver
     * already stored returns its existing event id instead of duplicating).
     * No-op when no outbox entry exists (e.g. the message already echoed).
     * @return true when the outbox error was cleared.
     */
    suspend fun retrySend(roomId: String, transactionId: String): Boolean {
        val c = client ?: return false
        return runCatching {
            c.room.retrySendMessage(RoomId(roomId), transactionId)
            true
        }.getOrDefault(false)
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
        // One-shot self-heal for the 2026-08-12 bridge-key bug window (see the
        // megolm section above) — before enqueueing, never blocking (first
        // scan per room is a ~250-event walk; async, LP3 feedback 2026-08-23).
        healStaleMegolmIfNeeded(c, matrixRoomId)
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
        wakeAfterSend(matrixRoomId.full)
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
        // A still-pending send: the echoed event isn't in the store yet, but
        // the server kept a copy of the recorded file when the note was sent
        // ([sendVoiceNote]) — play that instead of resolving by event id
        // (2026-08-23: "can't play a voice note while it's sending").
        if (eventId.startsWith(LOCAL_PENDING_ID_PREFIX)) {
            val txnId = eventId.removePrefix(LOCAL_PENDING_ID_PREFIX)
            val local = pendingAudioEcho[roomId]?.get(txnId)?.localFile
            if (local != null && local.exists()) {
                val ctx = appContext ?: return false to "no context"
                // Play from a fresh temp copy so stopping playback (which
                // deletes the played file) doesn't consume the pending copy.
                val tmp = java.io.File(ctx.cacheDir, "voice_$txnId")
                if (runCatching { local.copyTo(tmp, overwrite = true) }.isSuccess) {
                    android.util.Log.d(TAG, "playVoiceNote: playing local pending audio (id=$eventId)")
                    return playLocalAudioFile(
                        ctx, eventId, tmp,
                        successDetail = "playing local pending audio (id=$eventId, ${tmp.length()} bytes)",
                        failureDetail = "for local pending audio (id=$eventId, ${tmp.length()} bytes)",
                    )
                }
            }
            // Missing copy → fall through to the store path's error handling.
        }
        val matrixRoomId = RoomId(roomId)
        val te = withTimeoutOrNull(MEDIA_BUDGET_MS) {
            var event: TimelineEvent? = null
            repeat(MEDIA_CONTENT_RETRIES) {
                event = c.room.getTimelineEvent(matrixRoomId, EventId(eventId)).firstOrNull()
                if (event?.content?.getOrNull() != null) return@withTimeoutOrNull event
                // An older note's megolm session is often not in the local
                // store (sessions load lazily per room, mostly via getMessages)
                // — the content stays encrypted and the note silently "doesn't
                // play" (feedback 2026-08-14). Pull the session from the key
                // backup, then re-read so decryption can land. Retried on every
                // iteration (unless parked): a session the backup index hadn't
                // caught up with on the first try may be there a moment later
                // (feedback 2026-08-19 — playback "does not always work").
                // EXPLICIT play bypasses the futile-restore park: a
                // freshly-arrived note whose session isn't cached yet would
                // otherwise be unrecoverable for 4h (2026-08-23). Parking
                // still fires on failure, so repeated taps on a genuinely
                // undecryptable note keep backing off (other paths honor it).
                if (event?.content?.isFailure == true) {
                    if (restoreRoomSessions(c, matrixRoomId, listOf(event)) == 0) {
                        parkFutileRestore(matrixRoomId)
                    }
                }
                delay(MEDIA_CONTENT_RETRY_DELAY_MS)
            }
            event
        }
        val content = te?.content?.getOrNull()
        if (content == null && te?.content?.isFailure == true) {
            // An UNDECRYPTABLE note is not a non-audio row — the session
            // restore above ran; say what actually happened so a re-tap after
            // the session lands can play it (2026-08-23).
            android.util.Log.d(TAG, "playVoiceNote: undecryptable, restoring sessions (id=$eventId)")
            return false to "Couldn't play — couldn't decrypt audio"
        }
        if (content !is RoomMessageEventContent.FileBased.Audio) {
            // Diagnosis hook for the LP3 (feedback 2026-08-21): which content
            // type is actually on the timeline when the row reads as a voice
            // note — a bridge sending a different type would land here.
            android.util.Log.w(
                TAG,
                "playVoiceNote: content is ${content?.javaClass?.simpleName ?: "null"}, not FileBased.Audio",
            )
            return false to "not an audio message"
        }
        val file = content.file?.takeIf { !it.url.isNullOrBlank() }
        val url = content.url?.takeIf { it.isNotBlank() }
        if (file == null && url == null) return false to "no audio file"
        val mediaService = c.di.get<MediaService>(MediaService::class)
        // One retry: a single flaky fetch failing once shouldn't fail playback
        // outright — the first attempt can hit a slow window (2026-08-23).
        // Each attempt is logged separately so a silent tap maps to one cause.
        var download: Result<net.folivo.trixnity.client.media.PlatformMedia>? = null
        for (attempt in 1..2) {
            val result = withTimeoutOrNull(MEDIA_BUDGET_MS) {
                when {
                    file != null -> mediaService.getEncryptedMedia(file, saveToCache = false)
                    url != null -> mediaService.getMedia(url, saveToCache = false)
                    else -> return@withTimeoutOrNull null
                }
            }
            if (result == null) {
                // withTimeoutOrNull fired — the fetch itself exceeded the
                // budget (MEDIA_BUDGET_MS). Kept separate from the failure log
                // so a silent tap maps to exactly one cause.
                android.util.Log.w(
                    TAG,
                    "playVoiceNote: download timed out for $eventId after ${MEDIA_BUDGET_MS}ms " +
                        "(attempt $attempt/2, encrypted=${file != null}, url=${url ?: "null"})",
                )
                continue
            }
            if (result.isFailure) {
                // Diagnosis hook for the LP3 (feedback 2026-08-22 "download
                // failed"): this stage was silent — the common failure (the
                // media fetch) must log what actually went wrong (network
                // error, missing sha256 on the EncryptedFile →
                // MediaValidationException, …).
                android.util.Log.w(
                    TAG,
                    "playVoiceNote: download failed for $eventId (attempt $attempt/2, encrypted=${file != null}, " +
                        "url=${url ?: "null"}, size=${content.info?.size})",
                    result.exceptionOrNull(),
                )
                continue
            }
            download = result
            break
        }
        val bytes = (download?.getOrNull()?.toByteArray()?.takeIf { it.isNotEmpty() })
            ?: return false to "audio download failed"
        val ctx = appContext ?: return false to "no context"
        // The temp file must carry the ACTUAL format: MediaPlayer's file-source
        // path uses the extension as an extractor hint, and Beeper/WhatsApp
        // audio files (ogg/opus, mp3, aac…) mislabeled ".m4a" fail to prepare
        // (2026-08-13: an audio-file voice note played everywhere but the LP3).
        // Incoming notes often lack a usable mimetype (our own sends always set
        // "audio/ogg; codecs=opus", so only THEY played — feedback 2026-08-20),
        // so sniff the container from the magic bytes and fall back to the
        // mimetype label.
        val mime = content.info?.mimeType?.lowercase().orEmpty()
        val ext = sniffAudioExtension(bytes, mime)
        // WhatsApp/bridge quirk: the identification page is written with
        // header-type 0x02 (continuation) instead of 0x01 (BOS) — WhatsApp's
        // own decoder ignores the flag, but Android's OggExtractor requires
        // BOS to identify the codec, so prepare() fails on the LP3's stricter
        // media stack (verified 2026-08-21: the emulator's lenient extractor
        // plays the raw file, the LP3 rejects it; the BOS-repaired file plays
        // on both). Repair before writing so MediaPlayer never sees the
        // broken stream.
        val repaired = repairOgg(bytes)
        if (repaired !== bytes) {
            android.util.Log.w(
                TAG,
                "repairOgg: first page missing BOS — fixed (head=${repaired.take(8).joinToString("") { "%02x".format(it) }})",
            )
        }
        val playBytes = repaired
        // An unknown container/mime yields "" — write the file WITHOUT an
        // extension so MediaExtractor sniffs the content instead of chasing a
        // wrong hint (see [sniffAudioExtension]).
        val tmp = java.io.File(
            ctx.cacheDir,
            if (ext.isEmpty()) "voice_$eventId" else "voice_$eventId.$ext",
        )
        runCatching { tmp.writeBytes(playBytes) }.getOrElse { return false to "audio write failed" }
        // Shared fd-based playback tail (media attributes, audio focus, the
        // file-descriptor data source — see [playLocalAudioFile]).
        return playLocalAudioFile(
            ctx, eventId, tmp,
            successDetail = "playing $eventId (${playBytes.size} bytes, ext=$ext, mime=$mime, " +
                "head=${playBytes.take(8).joinToString("") { "%02x".format(it) }})",
            failureDetail = "for $eventId (ext=$ext, mime=$mime, size=${bytes.size}, " +
                "head=${bytes.take(8).joinToString("") { "%02x".format(it) }})",
        )
    }

    /**
     * fd-based playback tail shared by downloaded and local-pending voice
     * notes: media/speech classification (the hardware volume rocker controls
     * it), transient audio focus, and the FILE-DESCRIPTOR data source — the
     * media server is a different uid and can't traverse the app's private
     * cache dir, so a path source hits "Permission denied" on the LP3
     * (verified 2026-08-22; the recording preview plays fine because it hands
     * over an fd). The file is deleted on stop/failure (see
     * [stopAudioPlayback]). @return (playing, error).
     */
    private fun playLocalAudioFile(
        ctx: Context,
        eventId: String,
        tmp: java.io.File,
        successDetail: String,
        failureDetail: String,
    ): Pair<Boolean, String?> {
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
            // Pass the FILE DESCRIPTOR, not the path: the media server is a
            // different uid and can't traverse the app's private cache dir —
            // a path source hits "Permission denied" on the LP3 (verified
            // 2026-08-22: FileSource 'Failed to open file … (Permission
            // denied)' — the app-private /data/user/0/<pkg> dir is
            // drwx------, so setReadable on the file never helped; the
            // recording preview plays fine because it hands over an fd).
            // setDataSource(FileDescriptor) is the AOSP-blessed route for
            // app-private files; the fd stays valid for the player's lifetime.
            java.io.FileInputStream(tmp).use { input ->
                player.setDataSource(input.fd)
            }
            player.prepare()
            player.start()
        }.onFailure { e ->
            // Diagnosis hook for the LP3 (feedback 2026-08-21): the failing
            // stage is MediaPlayer prepare/start — log what was actually
            // handed to it (container, mime label, size, first bytes) so the
            // next device test identifies the container MediaPlayer rejects.
            android.util.Log.w(TAG, "playVoiceNote: play failed $failureDetail", e)
            runCatching { player.release() }
            tmp.delete()
            return false to "playback failed"
        }
        audioPlayer = player
        audioPlayerFile = tmp
        playingAudioEventId = eventId
        android.util.Log.d(TAG, "playVoiceNote: $successDetail")
        return true to null
    }

    /**
     * Container extension for a downloaded voice note: sniffed from the magic
     * bytes first (bridged audio is often mislabeled or carries no mimetype),
     * the mimetype label as the fallback. MediaPlayer's file-source path uses
     * the extension as its extractor hint, so the right one matters — a real
     * ogg named ".m4a" fails prepare (2026-08-13; feedback 2026-08-20: only
     * the LP3's own notes played, because only they carried the ogg mimetype).
     */
    private fun sniffAudioExtension(bytes: ByteArray, mime: String): String {
        fun has(s: String, at: Int) = bytes.size >= at + s.length &&
            s.indices.all { i -> bytes[at + i] == s[i].code.toByte() }
        return when {
            has("OggS", 0) -> "ogg"
            has("fLaC", 0) -> "flac"
            has("ID3", 0) -> "mp3"
            // Matroska/WebM (EBML 1A 45 DF A3) — some bridges serve voice
            // notes as webm/opus (2026-08-21: previously fell to the ".m4a"
            // default and failed prepare).
            bytes.size >= 4 && bytes[0].toInt() == 0x1A && bytes[1].toInt() == 0x45 &&
                bytes[2].toInt() == 0xDF && bytes[3].toInt() == 0xA3 -> "webm"
            // ADTS AAC (sync 0xFFF, layer bits 00) — must be checked BEFORE
            // the MPEG sync test below, which would mislabel it ".mp3" and
            // fail prepare (2026-08-21).
            bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xF6) == 0xF0 -> "aac"
            // MPEG audio frame sync (0xFFE/0xFFF): byte 1's top three bits
            // 111 with the layer bits NOT 000 (000 = reserved, i.e. ADTS).
            bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xE0) == 0xE0 && (bytes[1].toInt() and 0x06) != 0 -> "mp3"
            has("ftyp", 4) -> "m4a" // MP4/M4A ("....ftyp")
            has("RIFF", 0) -> "wav"
            has("#!AMR", 0) -> "amr"
            "ogg" in mime || "opus" in mime -> "ogg"
            "mpeg" in mime -> "mp3"
            "mp4" in mime || "m4a" in mime -> "m4a"
            "aac" in mime -> "aac"
            "amr" in mime -> "amr"
            "wav" in mime -> "wav"
            "flac" in mime -> "flac"
            "webm" in mime -> "webm"
            // Unknown container AND unknown mime: leave the extension off —
            // MediaExtractor then sniffs the actual content instead of being
            // misled by a guessed hint (the old ".m4a" default was exactly
            // the mislabel that broke playback on the LP3, 2026-08-13/21).
            else -> ""
        }
    }

    /**
     * Repairs a downloaded Ogg stream whose identification page is misflagged:
     * WhatsApp/bridge voice notes carry the OpusHead packet on a page written
     * with header-type 0x01 ("continuation") instead of 0x02 (BOS) — the
     * stream has NO BOS page at all. WhatsApp's own decoder ignores the flags,
     * but Android's OggExtractor requires BOS to identify the codec, so
     * MediaPlayer prepare() fails on the LP3's stricter media stack (the
     * emulator's lenient extractor plays the raw file; the BOS-repaired file
     * plays on both). Sets the BOS bit on the first page and recomputes that
     * page's CRC. Returns the original bytes unchanged when there is nothing
     * to fix (or the data isn't Ogg).
     *
     * header_type bits (RFC 3533): 0x01 = continuation, 0x02 = BOS,
     * 0x04 = EOS. (2026-08-22: an earlier version had these INVERTED — it
     * "repaired" valid files (BOS=0x02) into continuation pages (0x01), which
     * is exactly why the user's own notes stopped playing on the LP3.)
     */
    private fun repairOgg(bytes: ByteArray): ByteArray {
        if (bytes.size < 27 || bytes[0] != 'O'.code.toByte() || bytes[1] != 'g'.code.toByte() ||
            bytes[2] != 'g'.code.toByte() || bytes[3] != 'S'.code.toByte()
        ) {
            return bytes
        }
        if (bytes[5].toInt() and 0x02 != 0) return bytes // BOS already set
        val out = bytes.copyOf()
        out[5] = 0x02 // BOS only — the first page carries the stream's first packet
        // First page length: 27-byte header + segment table + laced bodies.
        var pageLen = 27
        val nseg = out[26].toInt() and 0xFF
        if (27 + nseg > out.size) return bytes
        for (i in 0 until nseg) pageLen += out[27 + i].toInt() and 0xFF
        if (pageLen > out.size) return bytes
        // Ogg CRC (poly 0x04c11db7, MSB-first, no reflection) over the page
        // with the CRC field (bytes 22..25) zeroed; stored little-endian.
        out[22] = 0; out[23] = 0; out[24] = 0; out[25] = 0
        var crc = 0
        for (i in 0 until pageLen) {
            crc = crc xor ((out[i].toInt() and 0xFF) shl 24)
            for (j in 0 until 8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
            }
        }
        out[22] = crc.toByte(); out[23] = (crc ushr 8).toByte()
        out[24] = (crc ushr 16).toByte(); out[25] = (crc ushr 24).toByte()
        return out
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
     * pattern as [startPhotoSend]). The activity records an ogg/Opus note and
     * sends it.
     */
    fun startVoiceNoteSend(roomId: String): String {
        android.util.Log.d(TAG, "startVoiceNoteSend: registering room $roomId")
        VoiceNoteActivity.register(roomId)
        return VOICE_NOTE_ACTIVITY
    }

    /**
     * Uploads and sends a recorded voice note (an ogg/Opus file) to the room —
     * encrypted media when the room is end-to-end encrypted (WhatsApp/Beeper),
     * plain otherwise. The content is hand-built as an
     * [RoomMessageEventContent.Unknown] (whose raw JSON is sent verbatim) so
     * it can carry the `org.matrix.msc3245.voice` marker: the WhatsApp bridge
     * renders an m.audio as a WhatsApp *voice note* only when that key is
     * present, otherwise WhatsApp shows a plain audio file (feedback
     * 2026-08-14). Trixnity's typed audio DSL has no extension slot, so the
     * upload is done here (same encrypted/plain split the DSL performs).
     * `audio/ogg; codecs=opus` is the MSC3245 canonical voice-message
     * mimetype — every Matrix client and the mautrix bridges treat it as a
     * voice message, and it is ~2-3× smaller than the old AAC/m4a notes
     * (2026-08-15 switch).
     * @return true when the send was enqueued.
     */
    suspend fun sendVoiceNote(roomId: String, file: java.io.File): Boolean {
        val c = client ?: return false
        val matrixRoomId = RoomId(roomId)
        // One-shot self-heal for the 2026-08-12 bridge-key bug window (see the
        // megolm section above) — before enqueueing, never blocking (first
        // scan per room is a ~250-event walk; async, LP3 feedback 2026-08-23).
        healStaleMegolmIfNeeded(c, matrixRoomId)
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
        val mimeType = "audio/ogg; codecs=opus"
        val isEncryptedRoom = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).first()?.encrypted
        } == true
        val json = c.di.get<Json>()
        val raw = if (isEncryptedRoom) {
            val encryptedFile = mediaService.prepareUploadEncryptedMedia(flowOf(bytes))
            // prepareUploadEncryptedMedia returns an EncryptedFile whose url is
            // the LOCAL "upload://" cache key — the real mxc:// URL exists only
            // after uploadMedia() uploads the bytes. Trixnity's typed outbox
            // uploader does that upload + URI rewrite for the image()/audio()
            // DSL content, but this event is hand-built (the msc3245 voice
            // marker has no DSL slot), so the upload must happen here or the
            // note ships with an unreachable upload:// url and the media never
            // reaches the server — other clients see the note but can't play
            // it (feedback 2026-08-17: Beeper "!" on the play button).
            val mxcUrl = mediaService.uploadMedia(encryptedFile.url).getOrNull()
                ?: return false
            val sentFile = encryptedFile.copy(url = mxcUrl)
            buildJsonObject {
                put("msgtype", "m.audio")
                put("body", "")
                put("filename", "voice.ogg")
                putJsonObject("info") {
                    put("mimetype", mimeType)
                    put("size", bytes.size)
                    if (durationMs != null) put("duration", durationMs)
                }
                putJsonObject("org.matrix.msc3245.voice") {}
                put("file", json.parseToJsonElement(json.encodeToString(EncryptedFile.serializer(), sentFile)))
            }
        } else {
            val cacheUri = mediaService.prepareUploadMedia(
                flowOf(bytes),
                runCatching { io.ktor.http.ContentType.parse(mimeType) }.getOrNull(),
            )
            // Same upload:// → mxc:// step as the encrypted branch (see above).
            val url = mediaService.uploadMedia(cacheUri).getOrNull() ?: return false
            buildJsonObject {
                put("msgtype", "m.audio")
                put("body", "")
                put("filename", "voice.ogg")
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
        // Keep the cached/disk newest page — re-opening serves it instantly
        // with the optimistic "Voice note" row injected ([injectPendingEchoes])
        // until the sync echo lands (the refresher then replaces it with the
        // real event). The send previously dropped the cache, so a re-open
        // recomputed from scratch (slow — "Loading messages…") and could show
        // the note as missing (feedback 2026-08-15).
        val roomPending = pendingAudioEcho.computeIfAbsent(matrixRoomId.full) { java.util.concurrent.ConcurrentHashMap() }
        // Keep a copy of the recorded file for the pending row: the activity
        // deletes the original as soon as this RPC returns, and the row must
        // stay playable until the sync echo replaces it (2026-08-23 — "can't
        // play a voice note while it's sending"). Best-effort: a failed copy
        // just leaves the pending row unplayable, the send is unaffected.
        val localFile = runCatching {
            java.io.File(appContext?.cacheDir ?: return@runCatching null, "voice_pending_$txnId.ogg")
                .also { file -> file.writeBytes(bytes) }
        }.getOrNull()
        roomPending[txnId] = PendingAudioSend(txnId, System.currentTimeMillis(), durationMs, localFile)
        wakeAfterSend(matrixRoomId.full)
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
        val matrixRoomId = RoomId(roomId)
        // The tool's id can be stale (a page served from cache), so the marker
        // used to cover an OLD event and the unread count never cleared. Bump
        // it to the store's current newest event — the read marker then covers
        // every unread message and the echo drops the badge (2026-08-23).
        val newest = withTimeoutOrNull(ROOM_BUDGET_MS) {
            // The last-timeline-event stream emits the inner event flow
            // (non-null), whose events are nullable — mirror the
            // collectNewestEvents pattern for the inner unwrap.
            c.room.getLastTimelineEvent(matrixRoomId).first()
                ?.filterNotNull()?.firstOrNull()
        }
        val newestId = newest?.event?.id?.full ?: eventId
        val newestTs = newest?.event?.originTimestamp ?: 0L
        c.api.room.setReadMarkers(
            roomId = matrixRoomId,
            fullyRead = EventId(newestId),
            read = EventId(newestId),
        )
        // Opening the thread makes the room's notification moot.
        appContext?.let { ChatNotifier.cancelRoom(it, roomId) }
        // Optimistically clear the room's unread in the served list — the
        // store's unreadMessageCount only drops after the read-marker echo
        // round-trips through sync (a full tick on a big account), which used
        // to leave the badge up long after the thread was opened. The
        // resolver keeps serving 0 until the echo confirms or a newer event
        // arrives ([servedUnread]).
        pendingReadClear[roomId] = newestId to newestTs
        roomListCache[roomId]?.let { entry ->
            if (entry.room.unreadCount > 0) {
                val cleared = entry.copy(room = entry.room.copy(unreadCount = 0))
                roomListCache[roomId] = cleared
                _roomList.value = _roomList.value.map { if (it.id == roomId) cleared.room else it }
            }
        }
        roomListDirty = true
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
        // tool's next poll without it blocking on a compute. Stops on thread
        // close, navigation, SCREEN_OFF and sync-pause (battery 2026-08-15).
        stopActiveRoomRefresh()
        if (roomId != null) startActiveRoomRefresh()
        // The tool just showed the list (null = list/settings/background) —
        // end the resolver's idle sleep so its next pass publishes promptly
        // instead of waiting out the screen-off 60 s breather (feedback
        // 2026-08-17: stale panel after a push-woken message).
        if (roomId == null) wakeRoomList()
        val ctx = appContext ?: return
        if (roomId != null) ChatNotifier.cancelRoom(ctx, roomId)
    }

    /** The active-room page refresh: rebuild the room's newest page cache every
     *  [ACTIVE_ROOM_REFRESH_MS] while the screen is on and a thread is open.
     *  [refreshMessagePage] itself skips rooms that haven't changed and never
     *  piles up, so a quiet room costs one cheap last-event read per tick. */
    private fun startActiveRoomRefresh() {
        val roomId = activeRoomId ?: return
        stopActiveRoomRefresh()
        activeRoomRefreshJob = scope.launch {
            while (true) {
                delay(ACTIVE_ROOM_REFRESH_MS)
                if (activeRoomId != roomId) break
                refreshMessagePage(roomId)
            }
        }
    }

    private fun stopActiveRoomRefresh() {
        activeRoomRefreshJob?.cancel()
        activeRoomRefreshJob = null
    }

    /** Screen truth for the speculative-work gates (battery 2026-08-15: eager
     *  page pre-computes and resolver passes are wasted while the screen is
     *  dark — nobody reads them until the next wake). */
    private fun isScreenInteractive(): Boolean =
        (appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

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
        // Notifications blocked (POST_NOTIFICATIONS not granted — requested from
        // the tool via the SDK flow, audit 2026-08-23): skip the whole chain —
        // the decrypt wait, flood/ghost walk and page warm built a preview the
        // OS drops. The room list still updates (separate resolver path).
        if (!ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
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
        // Beeper re-imports old media as m.replace edits — each used to surface
        // as a fresh image row + notification. Matrix semantics: an edit
        // replaces its target, never a new message. Don't notify.
        if (isReplaceEdit(te)) {
            android.util.Log.d(TAG, "notifyForEvent: skipping m.replace edit $eventId in $roomId")
            return
        }
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
            // Own account — no notification whether it was sent from THIS
            // device (outbox echo) or from another Beeper/WhatsApp device:
            // a message the user sent themselves needs no alert (LP3 feedback
            // 2026-08-23: sending from another device notified this one).
            // (Previously only outbox-matched sends were suppressed and
            // same-account other-device sends notified — reversed on request.)
            return
        }
        val resolved = te.content?.getOrNull()
        val isMessage = resolved is RoomMessageEventContent ||
            (resolved == null && te.event.content is EncryptedMessageEventContent)
        if (!isMessage) return
        val preview = previewText(te) ?: return
        val name = resolveRoomName(c, roomId, room)
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
    /** Room ids the user has opened (MarkRead fired) → (event id marked read,
     *  its timestamp). The store's unreadMessageCount only drops after the
     *  read-marker echo round-trips through sync (a full tick on a big
     *  account), so the served list shows 0 until the echo confirms or a
     *  message NEWER than the marked one arrives (feedback 2026-08-15: the
     *  badge lingered after viewing). */
    private val pendingReadClear = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
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

    /** Wakes the resolver's idle sleep immediately (screen-on, push-wake, the
     *  tool opening the list). Without it, a message that arrived while the
     *  screen was off left the panel stale for the rest of the screen-off
     *  sleep (60 s) after the user woke the phone (feedback 2026-08-17: "the
     *  main room panel doesn't update on push" — the message was in the store,
     *  the served list wasn't). Conflated: many signals collapse to one wake.
     */
    private val roomListWake = Channel<Unit>(Channel.CONFLATED)

    private fun wakeRoomList() {
        roomListWake.trySend(Unit)
    }

    /** The room-map fields that decide whether a resolver pass can be skipped. */
    private data class RoomSig(
        val lastEventId: String?,
        val lastTsMs: Long,
        val unreadCount: Long,
        val membership: String?,
    )

    /**
     * Unread count to show in the list. While a MarkRead is pending (the
     * store hasn't echoed it yet — see [pendingReadClear]) the count reads 0;
     * the suppression lifts when the echo confirms (store unread 0) or a
     * message NEWER than the one marked read arrives (real unread again —
     * compared by timestamp, so a lagging summary can't undo the clear for
     * events the page simply didn't carry, 2026-08-23).
     */
    private fun servedUnread(
        roomId: String,
        markedAt: String?,
        markedTs: Long?,
        newestId: String?,
        newestTs: Long?,
        storeUnread: Long,
    ): Long {
        if (markedAt == null) return storeUnread
        return if (storeUnread == 0L || newestId == markedAt ||
            (newestTs != null && markedTs != null && newestTs > markedTs)
        ) {
            pendingReadClear.remove(roomId)
            storeUnread
        } else {
            0
        }
    }

    private fun resetRoomList() {
        roomListCache.clear()
        roomSigSeen.clear()
        bridgeBotByRoom.clear()
        pendingReadClear.clear()
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
                    // Idle sleep, interruptible: [wakeRoomList] (screen-on,
                    // push-wake, list re-opened) ends it early so a message
                    // that landed while the screen was dark is served the
                    // moment the user looks at the list (feedback 2026-08-17).
                    val sleepMs =
                        if (isScreenInteractive()) ROOM_LIST_REFRESH_DELAY_MS else SLOW_RESOLVER_DELAY_MS
                    withTimeoutOrNull(sleepMs) { roomListWake.receive() }
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
                        if (room == null) continue
                        // Membership filter: only rooms the user is in appear in
                        // the list. Left rooms (bridge re-link / network
                        // disconnect artifacts) are pruned here — before this
                        // they were dropped from `loaded` but never removed from
                        // roomListCache, so a disconnected-then-reconnected
                        // network left the old rooms listed next to the fresh
                        // ones Beeper creates (the duplicate-room bug).
                        if (room.membership != Membership.JOIN) {
                            roomListCache.remove(roomId.full)
                            continue
                        }
                        loaded += roomId to room
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
                    // Publish the resolved list BEFORE the eager page
                    // pre-compute below: the precompute builds the newest
                    // rooms' pages first, and a slow page (e.g. a room whose
                    // history is still undecryptable) used to delay the
                    // publish — the panel's bump/reorder waited on it
                    // (LP3 2026-08-17: a sent self-note didn't bump the room
                    // until a later pass). The precompute only touches the
                    // message-page cache, never the room rows, so publishing
                    // first is safe.
                    publishRoomList()
                    // Eager page pre-compute (2026-08-13): the most-recent rooms'
                    // newest pages are computed in the background so opening a
                    // thread is a cache hit instead of a cold walk. A few per
                    // pass; rooms with a fresh page (memory or disk) are skipped.
                    // Battery (2026-08-15): screen-gated — the slow-sync rounds
                    // kept the list dirty on the live account, so the resolver
                    // was rebuilding the hottest room's page on every pass,
                    // 24/7, screen off or not (the second speculative-work loop
                    // after the active-room refresh). Nobody opens a thread
                    // while the screen is dark; the refresh re-arms on wake.
                    if (isScreenInteractive()) {
                        var precomputed = 0
                        for ((roomId, _) in loaded) {
                            if (precomputed >= EAGER_PAGES_PER_PASS) break
                            if (android.os.SystemClock.elapsedRealtime() >= passDeadline) break
                            val key = roomId.full
                            // Any in-memory page (fresh OR stale) already covers
                            // this room — getMessages serves stale memory and
                            // refreshes in the background, so pre-computing again
                            // is redundant. The disk check is a file stat, not a
                            // JSON decode: the old loadMessagePageFromDisk ran a
                            // full decode per room per pass just to learn the
                            // page exists (profile 2026-08-20).
                            if (messagePageCache.containsKey(key)) continue
                            if (messagePageCacheFile(key)?.exists() == true) continue
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
                    }
                } catch (e: Exception) {
                    android.util.Log.w(
                        TAG,
                        "room list resolver pass failed: ${e.javaClass.simpleName}: ${e.message}" +
                            "\n${e.stackTraceToString().lineSequence().take(6).joinToString("\n")}",
                    )
                }
                // Battery (2026-08-15): while the screen is off, coalesce the
                // resolver's dirty-loop — the room list only needs to be fresh
                // for the next wake, not sub-minute (the slow-sync rounds kept
                // it dirty on the live account, so the old 2s breather meant
                // near-continuous passes).
                // Wakeable (2026-08-17): a send/push/list-open wake ends the
                // breather early, so the next pass — and its publish — runs
                // immediately and the tool's very next list refresh shows the
                // bump instead of waiting out the cadence. Coalescing is
                // unchanged: the breather only shortens on a wake signal.
                withTimeoutOrNull(
                    if (isScreenInteractive()) ROOM_LIST_REFRESH_DELAY_MS else SLOW_RESOLVER_DELAY_MS
                ) {
                    roomListWake.receive()
                }
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
            val cleared = pendingReadClear[key]
            roomListCache[key] = RoomListEntry(
                room = com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room(
                    id = key,
                    name = ROOM_NAME_PLACEHOLDER, // filled in by the resolver
                    lastMessage = "",
                    // An unverified device can't decrypt — suppress unread for
                    // encrypted rooms only; unencrypted ones stay readable.
                    unreadCount = servedUnread(
                        key,
                        cleared?.first,
                        cleared?.second,
                        room.lastRelevantEventId?.full,
                        room.lastRelevantEventTimestamp?.toEpochMilliseconds(),
                        if (verified || !room.encrypted) room.unreadMessageCount else 0,
                    ),
                    lastTimestampMs = room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L,
                    lastEventId = room.lastRelevantEventId?.full,
                    // Beeper never writes m.direct for self-rooms — a
                    // Note-to-Self room (only yourself in it) reads as a group
                    // chat otherwise.
                    isDirect = room.isDirect || (room.joinedMemberCount ?: 0L) <= 1L,
                    contactId = contactIdOf(room),
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
        // Own send in flight (echo not yet in the store): the row must bump to
        // the top NOW with the send's preview + time — the panel must not keep
        // the pre-send state while the user's own message is on its way (LP3
        // 2026-08-17: the panel kept the old timestamp for ~10-15s after a
        // send). The pending's values (send time + body) match the echo's
        // closely, so the swap when the echo lands is invisible; once the
        // pending is gone (echo processed) the normal store row takes over.
        val pending = newestPending(key)
        val rowTs = when (val p = pending) {
            is PendingTextSend -> maxOf(ts, p.timestampMs)
            is PendingAudioSend -> maxOf(ts, p.timestampMs)
            null -> ts
        }
        // An unverified device can't decrypt — suppress unread for encrypted
        // rooms only; unencrypted ones stay readable.
        val storeUnread = if (verified || !room.encrypted) room.unreadMessageCount else 0
        val cleared = pendingReadClear[key]
        val unread = servedUnread(
            key,
            cleared?.first,
            cleared?.second,
            room.lastRelevantEventId?.full,
            room.lastRelevantEventTimestamp?.toEpochMilliseconds(),
            storeUnread,
        )
        val stateChanged = prev == null ||
            prev.room.lastEventId != lastEventId ||
            prev.room.unreadCount != unread ||
            prev.room.lastTimestampMs != rowTs
        // A parked preview stays stale only while the room is unchanged — a new
        // message (lastEventId move) re-attempts the preview immediately, so a
        // freshly-decryptable arrival isn't hidden for the rest of the park.
        val newMessageArrived = lastEventId != null && lastEventId != prev?.room?.lastEventId

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
            pending != null -> {
                // A send in flight: the preview is the sent body ("Voice note"
                // for audio), named like [resolveRoomPreview] would.
                preview = pending.let { p ->
                    when (p) {
                        is PendingTextSend ->
                            if (room.isDirect) p.body else "You: ${p.body}"
                        is PendingAudioSend -> "Voice note"
                    }
                }
                previewResolved = true
                previewRetryAtMs = 0L
            }
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
            !newMessageArrived && android.os.SystemClock.elapsedRealtime() < (prev?.previewRetryAtMs ?: 0L) -> {
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
                lastTimestampMs = rowTs,
                lastEventId = lastEventId,
                // Beeper never writes m.direct for self-rooms — a
                // Note-to-Self room (only yourself in it) reads as a group
                // chat otherwise.
                isDirect = room.isDirect || (room.joinedMemberCount ?: 0L) <= 1L,
                contactId = contactIdOf(room),
                // Resolved here (not in the seed pass — this one has a client
                // for the member-state read). Null for groups/seed rows.
                contactPhone = contactIdOf(room)?.let { contactPhoneOf(c, roomId, it) },
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
        nameMemo: MutableMap<String, String>? = null,
    ): String {
        room.name?.explicitName?.takeIf { it.isNotBlank() }?.let { return it }
        val heroes = titleHeroesOf(c, roomId, room)
        if (heroes.isNotEmpty()) {
            val names = heroes.mapNotNull { hero ->
                nameMemo?.getOrPut(hero.full) { heroName(c, roomId, hero) }
                    ?: heroName(c, roomId, hero)
            }.filter { it.isNotBlank() }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return "Chat"
    }

    /** A hero's display name, or its localpart when the user lookup times out. */
    private suspend fun heroName(c: MatrixClient, roomId: RoomId, hero: UserId): String =
        withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.user.getById(roomId, hero).firstOrNull()?.name ?: hero.localpart
        } ?: hero.localpart

    /**
     * Heroes that deserve a spot in a generated title. Self-hosted bridges
     * (BlueBubbles/OpenBubbles, mautrix, …) add their bridge bot as a hero,
     * which would glue e.g. "imessagebot" onto the contact names. Exact match
     * first: the bridge's declared bot ([bridgeBotOf], m.bridge state) is a
     * user id — remove just it, so a human whose name ends in "bot" keeps
     * their spot. Bridges that declare nothing (and bots not in the heroes)
     * fall back to contactIdOf's suffix heuristic. Bots stay only when every
     * hero is one, so an all-bot room doesn't fall through to "Chat".
     */
    private suspend fun titleHeroesOf(c: MatrixClient, roomId: RoomId, room: MatrixRoom): List<UserId> {
        val heroes = room.name?.heroes.orEmpty()
        if (heroes.isEmpty()) return heroes
        // Cache-hit avoids a store transaction on steady-state room passes;
        // the cold read owns its own scope like readReceiptsByEvent does.
        val declared = bridgeBotByRoom[roomId.full] ?: withTimeoutOrNull(ROOM_BUDGET_MS) {
            val txManager = c.di.get<RepositoryTransactionManager>(RepositoryTransactionManager::class)
            txManager.readTransaction { bridgeBotOf(c, roomId) }
        }.orEmpty()
        val humans = if (declared.isNotEmpty()) {
            heroes.filterNot { it.full == declared }
        } else {
            heroes.filterNot { it.localpart.endsWith("bot", ignoreCase = true) }
        }
        return humans.ifEmpty { heroes }
    }

    /**
     * The room's other participant for 1:1s — its single non-bot hero.
     * Beeper bridged DMs list the contact as the (single) hero (e.g.
     * @whatsapp_lid-273581128826955 / @instagramgo-xxxx); bridge bots like
     * @whatsappbot are members but never the hero. Groups have several heroes
     * → null, so the contact overlay shows name + network only there. The
     * full Matrix ID; the app derives the localpart (the phone number /
     * username) for display (feedback 2026-08-21).
     */
    private fun contactIdOf(room: MatrixRoom): String? =
        room.name?.heroes
            ?.filterNot { it.localpart.endsWith("bot", ignoreCase = true) }
            ?.singleOrNull()?.full

    /**
     * The contact's phone number for a 1:1, when the room data carries one:
     * 1) the contact ghost itself is a @whatsapp_<number> id (the number IS
     * the localpart); 2) the LID ghost's member event — the bridge shows the
     * phone as the displayname until the profile name syncs, so the number
     * survives in `unsigned.prev_content` (e.g. "+61478649413" → "Annette
     * Tuohy"); 3) a current displayname that is still a number. The
     * prev_content needs a cast to the concrete unsigned type — the
     * `UnsignedRoomEventData` interface hides it, but
     * `UnsignedStateEventData` carries it (feedback 2026-08-23).
     */
    private suspend fun contactPhoneOf(c: MatrixClient, matrixRoomId: RoomId, contactId: String): String? {
        val localpart = contactId.substringAfter("@").substringBefore(":")
        val phoneGhost = localpart.removePrefix("whatsapp_")
            .takeIf { it != localpart && !it.startsWith("lid-") && it.all { ch -> ch.isDigit() } }
        if (phoneGhost != null) return phoneGhost
        val user = withTimeoutOrNull(ROOM_BUDGET_MS) {
            runCatching { c.user.getById(matrixRoomId, UserId(contactId)).firstOrNull() }.getOrNull()
        } ?: return null
        val prevPhone = ((user?.event?.unsigned as? UnsignedRoomEventData.UnsignedStateEventData)
            ?.previousContent as? MemberEventContent)
            ?.displayName?.takeIf { it.isPhoneNumber() }
        if (prevPhone != null) return prevPhone
        return user?.name?.takeIf { it.isPhoneNumber() }
    }

    /** A display string that is a phone number (country code + digits). */
    private fun String.isPhoneNumber(): Boolean =
        replace(" ", "").replace("-", "").matches(Regex("^\\+?\\d{7,15}$"))

    /**
     * The room's newest event that renders as a message row, for the list's
     * sort + timestamp + preview (LP3 2026-08-17: the panel showed the
     * bridge's `com.beeper.message_send_status` delivery ack as the latest
     * message — 22:08 — while the thread's newest message was 21:59; Beeper's
     * status acks sit after the message in the timeline, so Trixnity's
     * summary, which counts any timeline event, bumped the row to the ack
     * time). The server's room summary points at the newest event — after a
     * bridge re-import flood that is a ghost, which bumped every room to the
     * top of the chat list. When the server's last event is a ghost (or any
     * non-renderable event — ack, reaction, redaction, edit), walk back
     * through the store to the first renderable event (bounded); otherwise
     * the server values pass through. Cached per server last event (the
     * summary is stable between new messages, so the walk runs once).
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
        // Last-known-good row for the unresolved paths below: the cache can
        // hold the PREVIOUS server-last event's resolution — keep showing
        // that message instead of stamping the row with an undecryptable
        // event's time (re-import copies arrive re-encrypted; their content
        // can't be read, 2026-08-23).
        val prev = effectiveLastCache[key]
        // Fast in-path walk (no session restore — old originals may not
        // decrypt yet): if the server's newest event survives the dedup AND
        // isn't inside a flood, it's a real message (the common case). The
        // renderable filter also drops bridge status acks / reactions /
        // redactions — events the thread never shows — so the row's time +
        // preview match the newest actual message (2026-08-17: a
        // message_send_status ack stamped 22:08 topped the row for a 21:59
        // message).
        val fast = withTimeoutOrNull(ROOM_BUDGET_MS) {
            // The DB chain, not the API walk: a gap marker at the room's
            // newest event truncates the API view to just the head event, so
            // the real messages behind a re-import copy were invisible and the
            // stamp persisted (LP3: Antoine/+15052308756/🌠/+491625672577
            // stuck at 09:08). The chain read walks past gap markers.
            collectRelevantTimelineEvents(c, matrixRoomId, serverLastId, EFFECTIVE_LAST_FAST, fast = true).first
        }.orEmpty()
        // Renderable AND non-empty: a bridge status ack, reaction, redaction,
        // or an empty-rendering re-import copy must not be the "real last
        // message" — the first entry here is what the row shows. Filtering
        // empties out lets the FIRST real message behind a run of copies/acks
        // resolve immediately (the fast window holds it), instead of waiting
        // on the async ghost resolve (LP3 2026-08-23: Sophie's room stamped
        // 18:26 by a status ack; the 09:08 wall rooms persisted).
        val fastFiltered = filterGhosts(c, fast)
            .filter { isRenderableRow(it) && previewText(it)?.isNotBlank() == true }
        val serverLast = fast.firstOrNull { it.event.id.full == serverLastId }
        val inFlood = serverLast != null && isFloodGhost(c, serverLast, fast)
        // Pin permanently only when the server-last event is a REAL message:
        // its content must be READABLE (an undecryptable re-import copy must
        // not top the list — 2026-08-23) AND it must RENDER something (a
        // re-import copy whose body resolves to nothing shows an empty
        // preview — LP3: rooms stamped 09:08 with lastMsg='' after the
        // bridge's history re-import; "the timestamp should reflect the
        // latest real message").
        if (fastFiltered.firstOrNull()?.event?.id?.full == serverLastId && !inFlood &&
            serverLast?.let { contentSignature(c, it) != null && previewText(it)?.isNotBlank() == true } == true
        ) {
            effectiveLastCache[key] = EffectiveLast(serverLastId, serverLastId, serverTs)
            return serverLastId to serverTs
        }
        // The server's newest event renders as nothing — a dropped edit, a
        // bridge status ack / reaction / redaction, or an in-flood ghost. When
        // the fast window already holds a renderable event, resolve it
        // immediately: real messages decrypt fine, so the background walk
        // would find the same event after a needless session-restore detour
        // (feedback 2026-08-17: rooms topped by edits showed "last message
        // Thursday").
        val firstReal = fastFiltered.firstOrNull()
        if (firstReal != null && firstReal.event.id.full != serverLastId) {
            val realTs = firstReal.event.originTimestamp
            effectiveLastCache[key] = EffectiveLast(serverLastId, firstReal.event.id.full, realTs)
            return firstReal.event.id.full to realTs
        }
        // Suspicious (dropped by the dedup, or inside a flood): resolve in the
        // background with a session restore (the copies' originals are old
        // messages whose keys load from the backup — slow, so not on the
        // resolver's critical path). Keep the server's values until it lands.
        if (inDecryptRestoreCooldown(matrixRoomId)) {
            // Parked room (futile restore): a ghost walk can't resolve it
            // either — the originals' sessions are gone, so the dedup can't
            // identify the real event. Retry at the park's end, not in 2
            // minutes (battery 2026-08-17 audit).
            effectiveLastCache[key] = EffectiveLast(
                serverLastId, prev?.effectiveEventId ?: serverLastId, prev?.effectiveTs ?: serverTs,
                retryAtMs = decryptRestoreCooldown[key] ?: (now + GHOST_WALK_RETRY_MS),
            )
            return (prev?.effectiveEventId ?: serverLastId) to (prev?.effectiveTs ?: serverTs)
        }
        enqueueGhostResolve(c, matrixRoomId, serverLastId, serverTs)
        effectiveLastCache[key] = EffectiveLast(
            serverLastId, prev?.effectiveEventId ?: serverLastId, prev?.effectiveTs ?: serverTs,
            retryAtMs = now + GHOST_WALK_RETRY_MS,
        )
        return (prev?.effectiveEventId ?: serverLastId) to (prev?.effectiveTs ?: serverTs)
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
                    // Skip the re-collect when the restore found nothing to
                    // load — nothing changed, the first walk's events are the
                    // final answer (battery 2026-08-17: the second walk doubled
                    // every ghost resolve, and on the live account the big
                    // rooms' walks exceeded the 8s budget and never resolved).
                    val loaded = restoreRoomSessions(c, matrixRoomId, events)
                    if (loaded > 0) {
                        collectTimelineEvents(c, matrixRoomId, serverLastId, EFFECTIVE_LAST_WALK)
                    } else events
                }
                if (walked != null) {
                    val real = filterGhosts(c, walked).filter { isRenderableRow(it) }.firstOrNull()
                    effectiveLastCache[key] = EffectiveLast(
                        serverLastId,
                        real?.event?.id?.full,
                        real?.event?.originTimestamp ?: 0L,
                    )
                    android.util.Log.d(
                        TAG,
                        "ghostResolve: $key server last $serverLastId → real last ${real?.event?.id?.full ?: "none"}",
                    )
                } else {
                    // Timed out: the walk can't complete within budget (large or
                    // undecryptable rooms). Back off hard instead of retrying
                    // every 2 minutes — the room isn't going to resolve, and the
                    // fast path re-checks it on every server-last change anyway
                    // (battery 2026-08-17 audit).
                    effectiveLastCache[key] = EffectiveLast(
                        serverLastId, serverLastId, serverTs,
                        retryAtMs = android.os.SystemClock.elapsedRealtime() + GHOST_WALK_FAIL_BACKOFF_MS,
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
     * retryAtMs): an "[Encrypted]" preview is unresolved; the room is parked
     * (futile-restore cooldown) and retried only after the park expires. A
     * real session arrival decrypts in-band and re-resolves it via the room's
     * state change, so short retries just burn CPU on rooms that can't decrypt
     * (battery 2026-08-17 audit).
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
            // An undecryptable preview won't resolve until a megolm session
            // arrives — and when one does, sync decrypts in-band and the
            // room's state change re-resolves this preview fresh. Park the
            // room instead of re-attempting every 60 s forever; the park also
            // lets the resolver's pass loop idle (no pending work to wake for).
            parkFutileRestore(roomId)
            return Triple("", false, decryptRestoreCooldown[roomId.full] ?: 0L)
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
            if (encrypted) {
                parkFutileRestore(roomId)
                decryptRestoreCooldown[roomId.full] ?: 0L
            } else 0L,
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
        // /sync size + duration — one log line per sync, always on. The per-sync
        // cost is the battery metric for whether the sync is lean enough
        // (battery 2026-08-17 audit; no body buffering — this must stay cheap).
        val path = chain.request().url.encodedPath
        if (path.contains("/sync")) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val response = chain.proceed(chain.request())
            android.util.Log.d(
                TAG,
                "sync response: ${response.header("Content-Length") ?: "chunked"}B in " +
                    "${android.os.SystemClock.elapsedRealtime() - t0}ms",
            )
            return@Interceptor response
        }
        // Off by default (efficiency audit 2026-08-14): the body buffering +
        // UTF-8 conversion ran on every request, 24/7, logging multi-KB megolm
        // ciphertexts. Live-read, so the debugLog toggle applies immediately.
        if (!debugLogging()) return@Interceptor chain.proceed(chain.request())
        if (httpTrafficSeen.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "HTTP-TRAFFIC: interceptor armed (first request through)")
        }
        val request = chain.request()
        val rebuilt = if (request.body != null) {
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

    /** Client configuration, differing only in the client name (the Beeper
     *  profile identifies as "chats-beeper" so its device appears distinctly).
     *  Qualified `httpClientEngine`: inside the receiver lambda, the
     *  unqualified name would resolve to the receiver's own (null) property —
     *  a silent no-op that left the client on Ktor's default engine (no
     *  logging/claim interceptors). */
    private fun clientConfiguration(name: String): MatrixClientConfiguration.() -> Unit = {
        this.name = name
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
                        // Slow sync (screen off) stops the long-poll between
                        // periodic syncOnce rounds — that's still "syncing",
                        // not an outage.
                        isSlowSyncing -> ChatConnectionState.Syncing
                        // The sync toggle is the source of truth while paused —
                        // the restored client reports STOPPED until resumed, and
                        // that must read as "paused", not "stopped" (or, worse,
                        // the race with init's explicit assignment).
                        !syncEnabled -> ChatConnectionState.Offline("sync paused")
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
                PushChannel.stop()
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
        val heroes = titleHeroesOf(c, roomId, room)
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

    /** True for m.replace edit events — Beeper re-imports old media as edits
     *  (new events referencing originals we never sync); Matrix semantics make
     *  them replace their target, never a row, so they render as nothing here:
     *  dropped from pages, previews, and notifications. */
    private fun isReplaceEdit(te: TimelineEvent): Boolean {
        val content = te.content?.getOrNull()
        return content is RoomMessageEventContent && content.relatesTo is RelatesTo.Replace
    }

    /** Cheap renderability check for older-page pagination (no user lookups):
     *  an event builds a row unless it's a dropped edit or an unresolvable
     *  non-message payload. Undecrypted encrypted events render as
     *  "[Encrypted]" placeholders, so they count as renderable. */
    private fun isRenderableRow(te: TimelineEvent): Boolean {
        if (isReplaceEdit(te)) return false
        val content = te.content?.getOrNull()
        return content is RoomMessageEventContent ||
            (content == null && te.event.content is EncryptedMessageEventContent)
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
        // ponytail: m.replace edits are dropped (not merged into their target) —
        // the app has no edit UI and Beeper's re-import edits target originals
        // we never sync (whatsapp.com bridge rooms); merge only if real local
        // edits appear.
        if (isReplaceEdit(te)) return null
        val (body, contentType) = when (content) {
            is RoomMessageEventContent.FileBased.Image ->
                (content.fileName?.takeIf { it.isNotBlank() } ?: "[Photo]") to "image"
            is RoomMessageEventContent.FileBased.Audio ->
                (content.fileName?.takeIf { it.isNotBlank() } ?: "Voice note") to "audio"
            is RoomMessageEventContent.TextBased -> {
                // m.notice = bridge system messages ("Turned off disappearing
                // messages", timer-set notices… — the mautrix bridge sends them
                // as notices from the contact's own ghost, so sender can't
                // distinguish them). The tool renders them as a small centered
                // system line instead of a normal message (2026-08-22).
                // A BLANK body renders nothing — the bridge's re-import copies
                // (09:08 wall after a WhatsApp number change) resolve to empty
                // text, and an empty bubble reads as "no messages" (LP3: the
                // Lillian room). Drop them so the real conversation shows.
                val text = stripReplyQuote(content.body)
                if (text.isBlank()) return null
                text to if (content is RoomMessageEventContent.TextBased.Notice) "notice" else "text"
            }
            else -> ((previewText(te)?.takeIf { it.isNotBlank() }) ?: return null) to "text"
        }
        val sender = te.event.sender
        return com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
            id = te.event.id.full,
            sender = sender.full,
            senderName = senderNameOf(c, roomId, sender),
            body = body,
            timestampMs = te.event.originTimestamp,
            isMine = sender == c.userId,
            // Delivery status only ever describes this device's own sends.
            sendStatus = sendStatus.takeIf { sender == c.userId },
            contentType = contentType,
            read = read,
            reactions = reactions,
            // The voice-note row shows the length + playing progress.
            durationMs = (content as? RoomMessageEventContent.FileBased.Audio)?.info?.duration,
            // The image's caption (the m.image body — most clients put the
            // caption there, separate from the file name). A caption that
            // equals the file name is not a caption (feedback round 2026-08-19).
            caption = (content as? RoomMessageEventContent.FileBased.Image)?.let { image ->
                image.body.takeIf { it.isNotBlank() && it != image.fileName }
            },
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
        // m.replace edits (Beeper's re-imported media) never become a preview —
        // see [messageFrom].
        if (isReplaceEdit(te)) return null
        if (content != null) {
            return when (content) {
                is RoomMessageEventContent.TextBased -> stripReplyQuote(content.body).take(MAX_PREVIEW_LENGTH)
                is RoomMessageEventContent.FileBased -> when (content) {
                    is RoomMessageEventContent.FileBased.Image ->
                        // A caption beats the generic "[Photo]" placeholder in
                        // the room list (feedback round 2026-08-19).
                        content.body.takeIf { it.isNotBlank() && it != content.fileName }
                            ?.take(MAX_PREVIEW_LENGTH) ?: "[Photo]"
                    is RoomMessageEventContent.FileBased.Video -> "[Video]"
                    is RoomMessageEventContent.FileBased.Audio -> "[Audio]"
                    else -> "[File]"
                }
                else -> null
            }
        }
        return when {
            // A genuinely undecryptable message renders as a single calm
            // placeholder (2026-08-19 feedback round — was "[Encrypted —
            // waiting for key…]" and "[Encrypted]" depending on the failure
            // state; same meaning, one label). Only a real decrypt FAILURE
            // shows it: an event whose decrypt is still pending (content
            // unresolved, raw content still m.room.encrypted) returns null,
            // so the thread skips the row and it appears once decrypted —
            // new messages no longer flash "[Encrypted message]" for a poll
            // (feedback 2026-08-20: "new messages come through as [encrypted
            // message] and are decrypted shortly after").
            te.content?.isFailure == true -> "[Encrypted message]"
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
    /** Flood-context read TTL (see [ghostContext]): the density verdict can't
     *  change within seconds, so a message burst reuses the walk instead of
     *  re-reading 250 events per event (battery 2026-08-17 audit). */
    private const val FLOOD_CONTEXT_TTL_MS = 10_000L
    /** Rebuild the network map at most this often (space membership is stable). */
    private const val NETWORK_MAP_TTL_MS = 300_000L
    /** Bound for a full network-map build (600+ room flows on a big account). */
    private const val NETWORK_MAP_BUDGET_MS = 15_000L
    private const val ROOMS_BUDGET_MS = 15_000L
    private const val ROOM_BUDGET_MS = 3_000L
    private const val MESSAGES_BUDGET_MS = 15_000L
    /** Restore-scan cadence: at most one full crawl per day (battery/UX
     *  2026-08-15 — it used to run on every process start and starve the app
     *  for 20+ min on the bridged account; the on-demand page path still
     *  restores when a room is actually read). */
    private const val RESTORE_INTERVAL_MS = 86_400_000L
    /** Per-room budget + window for the restore scan (the shared
     *  [MESSAGES_BUDGET_MS] / 100-event window is fine for reads, wasteful for
     *  the preemptive crawl). */
    private const val RESTORE_ROOM_BUDGET_MS = 6_000L
    private const val RESTORE_ROOM_EVENTS = 40L
    private const val FETCH_TIMEOUT_SECONDS = 5L
    /** The thread's page size (matches the tool's PAGE_SIZE). */
    private const val THREAD_PAGE_SIZE = 20
    /** Cold-open first page (2026-08-19 feedback round): a room with no cached
     *  page opens with this many messages at once — fast, no decrypt/status
     *  work — while the background refresh fills the full page. */
    private const val INCREMENTAL_FIRST_PAGE = 8
    /** Max extra chain walks an older page may take to skip a run of dropped
     *  events (the m.replace edit wall) before giving up — bounded so a
     *  pathological chain can't turn one page read into a long walk. The
     *  steps are big ([OLDER_PAGE_SKIP_STEP]); re-import walls run 100+
     *  (the Crocs room has 168 consecutive edits). */
    private const val OLDER_PAGE_SKIP_WALKS = 10
    /** Events per guard walk when skipping a dropped-event wall. */
    private const val OLDER_PAGE_SKIP_STEP = 100
    /** Serve a cached newest page within this window (feedback pass). */
    private const val MESSAGE_PAGE_TTL_MS = 5_000L
    /** Recompute the active room's cached newest page at this cadence. The
     *  tool's own poll (3s) hits the cache, so 30s is invisible — and the
     *  refresh skips unchanged rooms entirely (battery audit 2026-08-15: was
     *  2s, running 24/7 with no screen coupling — the drain's engine). */
    private const val ACTIVE_ROOM_REFRESH_MS = 30_000L
    private const val TYPING_TIMEOUT_MS = 30_000L
    private const val DECRYPT_RETRIES = 3
    private const val DECRYPT_RETRY_DELAY_MS = 1_500L
    /** How long a room with a futile key-backup restore stays parked: after a
     *  restore finds nothing to load (0 sessions in the backup), every retry
     *  path stops re-attempting for this long. Long on purpose — these rooms
     *  (pre-verification bridged history) essentially never get their sessions,
     *  and the short retries (60 s preview / 120 s ghost walk) burned ~3 cores
     *  continuously (battery 2026-08-17 audit). In-band sync decryption is
     *  unaffected: a session arriving mid-park decrypts the room fresh. */
    private const val DECRYPT_RESTORE_COOLDOWN_MS = 14_400_000L
    private const val DECRYPT_WAIT_MS = 3_000L
    /** Peek budget for events after the first one failed to decrypt. */
    private const val QUICK_DECRYPT_WAIT_MS = 100L
    /** Local outbox read for the pending-row state (event id / send error). */
    private const val OUTBOX_READ_TIMEOUT_MS = 500L
    /** Id prefix of optimistic pending rows (see [pendingEchoRow]) — the row
     *  id matches the tool's "local-<txn>" id so the tool's own row dedupes. */
    private const val LOCAL_PENDING_ID_PREFIX = "local-"

    // Room-list resolver (Phase 5).
    private const val ROOM_NAME_PLACEHOLDER = "…"
    /** Per-pass time budget; the resolver loops until the list is settled. */
    private const val ROOM_LIST_PASS_BUDGET_MS = 12_000L
    /** Breather between passes; a settled pass itself takes milliseconds. */
    private const val ROOM_LIST_REFRESH_DELAY_MS = 2_000L
    /** Cap on rooms shipped over the binder: the encoded reply is one binder
     *  transaction, hard-capped at ~1 MB — beyond ~1,600 rooms every GetRooms
     *  call failed and the list stuck on "loading…". 200 rooms ≈ 200 KB. Rooms
     *  past the cap are still tracked/refreshed; a new message sorts them back
     *  into the served window. (Roadmap: search / a cap-raising page flow.) */
    private const val MAX_ROOMS_OVER_BINDER = 200
    /** Resolver breather while the screen is off (battery 2026-08-15): the
     *  live bridged account keeps the list dirty, so the 2s breather meant
     *  near-continuous passes overnight; the list only needs freshness for
     *  the next wake. */
    private const val SLOW_RESOLVER_DELAY_MS = 60_000L
    /** Bounded decrypt-wait per room preview. */
    private const val PREVIEW_BUDGET_MS = 1_500L
    /** Per-room state collect in the resolver (the store cache emits instantly). */
    private const val ROOM_LIST_ROOM_BUDGET_MS = 500L

    // Gap-marker backfill (PLAN §8, 2026-08-21): a `limited=true` sync stores a
    // gap marker whose missing window Trixnity never fills — events created
    // during the missed window are silently absent from the store. The fill is
    // triggered by the page walk and bounded below so it can't burn battery.
    /** Events fetched per gap fill (one windowed GET /rooms/{id}/messages). */
    private const val GAP_BACKFILL_LIMIT = 30L
    /** A fill must complete within this (the sync-aware retry can back off long). */
    private const val GAP_BACKFILL_BUDGET_MS = 8_000L
    /** After a failed/blocked fill, back off this long before retrying. */
    private const val GAP_BACKFILL_COOLDOWN_MS = 300_000L
    /** Delay before stopping the sync service after an expiry detection. */
    private const val SYNC_STOP_DELAY_MS = 3_000L
    /** Bound for the device-verification check before key-backup work. */
    private const val KEY_BACKUP_VERIFY_TIMEOUT_MS = 1_000L
    /** Bound for a single megolm-session load (can hang waiting for a key). */
    private const val KEY_BACKUP_LOAD_TIMEOUT_MS = 2_000L
    /** How long the memoized [e2eeState] result stays fresh (see the cache
     *  field) — long enough that the 1-5 s account polls + thread opens don't
     *  hit the network getDevices() on every call, short enough that a
     *  verification started elsewhere shows up within a minute. */
    private const val E2EE_STATE_TTL_MS = 60_000L
    /** Megolm stale-session check TTL (see [rotateStaleMegolmIfNeeded]): the
     *  check walks the room's newest events, so it runs at most once per
     *  room per window instead of before every send. */
    private const val MEGOLM_STALE_CHECK_TTL_MS = 300_000L

    // Bridge-ghost filtering (Phase 14.5).
    /** Density-fallback window: a txn-id event inside this many of [GHOST_FLOOD_THRESHOLD] others is a flood. */
    private const val GHOST_BURST_WINDOW_MS = 60_000L
    /** Flood fallback: real conversations rarely exceed this rate (re-imports are per-second). */
    private const val GHOST_FLOOD_THRESHOLD = 30
    /** Room-list effective-last walk cap (decrypted events). */
    private const val EFFECTIVE_LAST_WALK = 800
    /** In-path fast window — big enough to spot a >=[GHOST_FLOOD_THRESHOLD] flood. */
    private const val EFFECTIVE_LAST_FAST = 50
    /** How long a pending ghost-resolution is parked before the resolver retries. */
    private const val GHOST_WALK_RETRY_MS = 120_000L
    /** Backoff after a ghost walk times out (can't complete within its budget):
     *  the room isn't going to resolve, so re-attempting in 2 minutes just
     *  re-runs the same doomed walk (battery 2026-08-17 audit). */
    private const val GHOST_WALK_FAIL_BACKOFF_MS = 14_400_000L
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
    /** The tool's photo-picker activity, flattened for the tool to launch. The
     *  package is the TOOL's own id — the single-APK merge (2026-08-19) made
     *  the former companion a library inside com.lightphone.chats, so the old
     *  com.lightphone.chats.server package no longer resolves (feedback
     *  2026-08-19: "mic and photos do not work"). */
    private const val PHOTO_PICKER_ACTIVITY = "com.lightphone.chats/.server.PhotoSendActivity"
    /** The tool's voice-note recording activity, flattened for the tool. */
    private const val VOICE_NOTE_ACTIVITY = "com.lightphone.chats/.server.VoiceNoteActivity"

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
