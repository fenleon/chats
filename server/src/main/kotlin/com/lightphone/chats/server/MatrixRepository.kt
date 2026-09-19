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
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore as AndroidMediaStore
import android.webkit.MimeTypeMap
import android.content.ContentValues
import androidx.room.Room
import com.lightphone.chats.server.MatrixRepository.ChatConnectionState
import com.thelightphone.sdk.shared.LightServiceMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import de.connect2x.trixnity.clientserverapi.model.push.SetPushRule
import de.connect2x.trixnity.clientserverapi.model.user.Filters
import de.connect2x.trixnity.core.ClientEventEmitter
import de.connect2x.trixnity.core.MatrixServerException
import de.connect2x.trixnity.core.subscribeAsFlow
import de.connect2x.trixnity.core.model.events.MessageEventContent
import de.connect2x.trixnity.core.model.events.RoomAccountDataEventContent
import de.connect2x.trixnity.core.model.events.m.PushRulesEventContent
import de.connect2x.trixnity.core.model.events.m.ReceiptEventContent
import de.connect2x.trixnity.core.model.events.m.TagEventContent
import de.connect2x.trixnity.core.model.push.PushAction
import de.connect2x.trixnity.core.model.push.PushRuleKind
import de.connect2x.trixnity.core.serialization.events.EventContentSerializerMappings
import de.connect2x.trixnity.core.serialization.events.UnknownEventContentSerializer
import de.connect2x.trixnity.core.serialization.events.default
import de.connect2x.trixnity.core.serialization.events.invoke
import de.connect2x.trixnity.core.serialization.events.messageOf
import de.connect2x.trixnity.core.serialization.events.roomAccountDataOf
import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.core.EventHandler
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.client.MatrixClientConfiguration
import de.connect2x.trixnity.client.MatrixClientConfiguration.DeleteRooms
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.createTrixnityDefaultModuleFactories
import de.connect2x.trixnity.client.key
import de.connect2x.trixnity.client.key.KeySecretService
import de.connect2x.trixnity.client.key.KeyTrustService
import de.connect2x.trixnity.client.key.OutgoingRoomKeyRequestEventHandler
import de.connect2x.trixnity.client.media.MediaService
import de.connect2x.trixnity.client.media.MediaStore
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.notification
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.GetTimelineEventConfig
import de.connect2x.trixnity.client.room.GetTimelineEventsConfig
import de.connect2x.trixnity.client.room.TimelineEventHandler
import de.connect2x.trixnity.client.room.MegolmRoomEventEncryptionService
import de.connect2x.trixnity.client.room.RoomEventEncryptionService
import de.connect2x.trixnity.client.room.TimelineEventHandlerImpl
import de.connect2x.trixnity.client.room.message.image
import de.connect2x.trixnity.client.room.message.reply
import de.connect2x.trixnity.client.room.message.replace
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.serverDiscovery
import de.connect2x.trixnity.client.store.Account
import de.connect2x.trixnity.client.store.AccountStore
import de.connect2x.trixnity.client.store.GlobalAccountDataStore
import de.connect2x.trixnity.client.store.KeyStore
import de.connect2x.trixnity.client.store.OlmCryptoStore
import de.connect2x.trixnity.client.store.Room as MatrixRoom
import de.connect2x.trixnity.client.store.RoomTimelineStore
import de.connect2x.trixnity.client.store.StoredSecretKeyRequest
import de.connect2x.trixnity.client.store.StoreTransactionManager
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.isVerified
import de.connect2x.trixnity.client.store.joinedMemberCount
import de.connect2x.trixnity.client.store.repository.RoomStateRepository
import de.connect2x.trixnity.client.store.repository.RoomStateRepositoryKey
import de.connect2x.trixnity.client.store.repository.RoomUserReceiptsRepository
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase
import de.connect2x.trixnity.client.store.repository.room.room
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.verification
import de.connect2x.trixnity.core.model.events.m.KeyRequestAction
import de.connect2x.trixnity.core.model.events.m.secret.SecretKeyRequestEventContent
import de.connect2x.trixnity.crypto.SecretType
import de.connect2x.trixnity.crypto.core.SecureRandom
import kotlin.time.Clock
import de.connect2x.trixnity.client.verification.ActiveDeviceVerification
import de.connect2x.trixnity.client.verification.ActiveSasVerificationMethod
import de.connect2x.trixnity.client.verification.ActiveSasVerificationState
import de.connect2x.trixnity.client.verification.ActiveVerificationState
import de.connect2x.trixnity.core.model.events.m.key.verification.VerificationCancelEventContent
import de.connect2x.trixnity.clientserverapi.client.SyncState
import de.connect2x.trixnity.clientserverapi.client.classicLogin
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.clientserverapi.model.authentication.LoginType
import de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction
import de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction.BACKWARDS
import de.connect2x.trixnity.clientserverapi.model.sync.Sync
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.UnsignedRoomEventData
import de.connect2x.trixnity.core.model.events.m.room.MemberEventContent
import de.connect2x.trixnity.core.model.events.m.Presence
import de.connect2x.trixnity.core.model.events.m.ReceiptType
import de.connect2x.trixnity.core.model.events.m.Mentions
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.key.verification.VerificationMethod
import de.connect2x.trixnity.core.model.events.m.room.CreateEventContent
import de.connect2x.trixnity.core.model.events.m.ReactionEventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptedFile
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.RedactionEventContent
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.connect2x.trixnity.core.model.events.m.space.ChildEventContent
import de.connect2x.trixnity.core.model.events.m.secretstorage.DefaultSecretKeyEventContent
import de.connect2x.trixnity.core.model.events.m.secretstorage.SecretKeyEventContent
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.RedactedEventContent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
import de.connect2x.trixnity.core.subscribeEventList
import de.connect2x.trixnity.core.unsubscribeOnCompletion
import de.connect2x.trixnity.crypto.key.decodeRecoveryKey
import de.connect2x.trixnity.crypto.olm.OlmEncryptionService
import de.connect2x.trixnity.crypto.olm.OlmEncryptionServiceImpl
import de.connect2x.trixnity.client.cryptodriver.libolm.libOlm
import de.connect2x.trixnity.utils.nextString
import de.connect2x.trixnity.utils.ReadTransaction
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toPath
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
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
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_LOGIN_MODE = "login_mode"
    private const val KEY_BEEPER_REQUEST_ID = "beeper_request_id"
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    /** When the one-shot megolm restore scan last ran (daily gate, 2026-08-15). */
    private const val KEY_RESTORE_LAST_RUN_MS = "restore_last_run_ms"
    /** True when a full restore crawl completed. Persisted so the
     *  Account screen's "All messages restored" line survives process restarts —
     *  the crawl runs at most once per 24h, so the in-memory flag alone could
     *  never show after a reboot/install/force-stop. Cleared at login. */
    private const val KEY_RESTORE_COMPLETED = "restore_completed"
    /** Per-room event id the notification watcher last alerted (prefix + roomId.full),
     *  persisted so a watcher re-attach — every process start / app launch — does not
     *  re-alert the same newest event a previous run already dinged (ghost burst
     *  fix). Cleared with the prefs at logout. */
    private const val KEY_LAST_NOTIFIED_PREFIX = "last_notified_"
    private const val KEY_LAST_READ_PREFIX = "last_read_"
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

    // --- Voice-note playback -------------------------------------
    // The companion plays m.audio messages (the tool runtime forbids audio
    // playback APIs): PlayVoiceNote downloads the audio (decrypting when the
    // room is encrypted) and plays it with a plain MediaPlayer. The tool
    // highlights the playing row via the audioPlayingEventId the GetMessages
    // response carries, so no extra polling RPC is needed.

    /** Event id of the voice note currently playing (null = nothing playing). */
    @Volatile
    private var playingAudioEventId: String? = null

    /**
     * Event id of a PAUSED voice note (null when nothing is paused). Pausing
     * keeps the player + file + position alive (audioPositionMs reports
     * null — the row shows the note's length), so re-tapping the same note
     * RESUMES from the pause point instead of restarting.
     */
    @Volatile
    private var pausedAudioEventId: String? = null

    /** Room id of the note currently playing/paused — needed by the
     *  completion handler to auto-advance to the next note in the same room.
     */
    @Volatile
    private var playingAudioRoomId: String? = null

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
     * the rocker doesn't touch.
     */
    @Volatile
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    fun audioPlayingEventId(): String? = playingAudioEventId

    /** True while a voice note is playing OR paused — the volume rocker then
     *  controls the media stream in-app instead of relaying to LightOS.
     */
    fun isVoiceNoteActive(): Boolean = playingAudioEventId != null || pausedAudioEventId != null

    /** Whether a thread is currently on screen (the tool's SetActiveRoom) — the
     *  server-side half of the volume-panel gate (feedback 2026-08-30). */
    fun isThreadOnScreen(): Boolean = activeRoomId != null

    /** Media volume (level, max) — the tool's in-app volume panel bar
     *  (feedback 2026-08-30). */
    fun mediaVolumeLevel(): LightServiceMethod.GetVolumeLevel.Response? {
        val audio = appContext?.getSystemService(android.content.Context.AUDIO_SERVICE)
            as? android.media.AudioManager ?: return null
        return LightServiceMethod.GetVolumeLevel.Response(
            level = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
            max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
        )
    }

    /** Playback position (ms) of the playing voice note, or null when idle. */
    fun audioPositionMs(): Long? =
        audioPlayer?.takeIf { playingAudioEventId != null }?.currentPosition?.toLong()

    /**
     * Voice-note sends awaiting their sync echo (room key → txn → send info).
     * Multi-slot: two rapid sends in one room must BOTH keep their optimistic
     * rows — a single per-room slot let the second send overwrite the first,
     * whose row then vanished until its echo.
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
        /** Real event id once the homeserver acks the send (/send 200 — see
         *  [sendVoiceNote]). Cached because Trixnity removes the outbox row as
         *  soon as the sync echo processes, so a served pending row could
         *  otherwise fall back to the "local-…" id → SENDING. */
        val eventId: String? = null,
    ) : PendingSend

    /**
     * Text sends awaiting their sync echo (room key → txn → send info), the
     * same optimistic-row pattern as [pendingAudioEcho]. The echo (which can
     * take a full sync tick on a big account) replaces the row; until then
     * every getMessages shows the sent message — including a re-opened thread,
     * which is why the echo lives server-side and not in the tool's view model.
     */
    private val pendingTextEcho = java.util.concurrent.ConcurrentHashMap<
        String, java.util.concurrent.ConcurrentHashMap<String, PendingTextSend>>()

    private data class PendingTextSend(
        override val txnId: String,
        override val timestampMs: Long,
        val body: String,
        /** Event id the send replies to (m.in_reply_to), for the optimistic
         *  row's excerpt header; null for a plain send. */
        val replyToEventId: String? = null,
    ) : PendingSend

    /** Photo sends awaiting their sync echo, the same optimistic-row pattern as
     *  [pendingTextEcho]. The pending row shows the file name +
     *  SENDING; there's no local thumbnail, so the tool's media fetch for its
     *  "local-…" id returns null until the echo resolves. */
    private val pendingImageEcho = java.util.concurrent.ConcurrentHashMap<
        String, java.util.concurrent.ConcurrentHashMap<String, PendingImageSend>>()

    private data class PendingImageSend(
        override val txnId: String,
        override val timestampMs: Long,
        val fileName: String,
    ) : PendingSend

    /** A send awaiting its sync echo (text, audio, or photo), shown as an optimistic row. */
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

    /** A room's in-flight photo sends, oldest first. */
    private fun pendingImageEchoes(roomId: String): List<PendingImageSend> =
        pendingImageEcho[roomId]?.values?.sortedBy { it.timestampMs }.orEmpty()

    /** The newest in-flight send in a room (the panel bump / pending override
     *  only ever shows the latest one). */
    private fun newestPending(roomId: String): PendingSend? =
        (pendingTextEcho[roomId]?.values?.maxByOrNull { it.timestampMs })
            ?: (pendingAudioEcho[roomId]?.values?.maxByOrNull { it.timestampMs })
            ?: (pendingImageEcho[roomId]?.values?.maxByOrNull { it.timestampMs })

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
     * True while the in-process path owns the sync arm (see [startSyncLoop]) —
     * lets ChatSyncService skip arming a second loop on the same client when
     * the foreground-service promotion finally lands.
     */
    @Volatile
    private var inProcessSyncRunning = false

    /** Lets [ChatSyncService] skip starting a second loop when the fallback owns it. */
    val isInProcessSyncRunning: Boolean get() = inProcessSyncRunning

    private val _connectionState = MutableStateFlow<ChatConnectionState>(ChatConnectionState.LoggedOut)
    val connectionState: StateFlow<ChatConnectionState> = _connectionState.asStateFlow()

    /** Progress of the background key-backup restore crawl (see
     *  [restoreMegolmSessions]) — mirrored for the Account screen's
     *  "Recovering… x of y rooms" line. */
    data class RestoreProgress(
        val scanning: Boolean = false,
        val scanned: Int = 0,
        val roomsTotal: Int = 0,
        val completed: Boolean = false,
    )

    private val _restoreProgress = MutableStateFlow(RestoreProgress())

    /** User pause for the sync loop (Settings → Sync, audit 2026-08-14): when
     *  false, no sync loop / foreground service runs — the battery escape hatch. */
    @Volatile
    private var syncEnabled = true

    val isSyncEnabled: Boolean get() = syncEnabled

    /** Sync cadence. ACTIVE = continuous long-poll while the screen is on;
     *  SLOW = periodic [de.connect2x.trixnity.client.MatrixClient.syncOnce] once
     *  the screen's been off for a while — the long-poll's per-response
     *  parse/decrypt/store processing is the main standby cost on an
     *  always-active bridged account. */
    enum class SyncMode { ACTIVE, SLOW }

    @Volatile
    private var syncMode = SyncMode.ACTIVE

    /** True while the periodic slow-sync loop owns sync. The FGS/watchdog must
     *  not restart a long-poll then (ChatSyncService reads this). */
    val isSlowSyncing: Boolean get() = syncMode == SyncMode.SLOW

    /** Screen truth for the sync-cadence decision. ChatSyncService reads this
     *  so it never starts a long-poll while the screen is dark. */
    val isScreenOn: Boolean get() = isScreenInteractive()

    private var slowSyncJob: Job? = null
    private var screenOffJob: Job? = null

    /**
     * The in-process sync arm (see [startSyncLoop]): a short-lived job that
     * calls [de.connect2x.trixnity.client.MatrixClient.startSync] once. Under
     * Trixnity v5 that call just arms the client's internal sync loop — the
     * /sync rounds run inside the client, which retries errors itself — so no
     * restart supervision lives here; [ChatSyncService]'s state watchdog
     * covers a wedged loop after the foreground-service promotion. Kept
     * cancellable so teardown paths can drop a queued arm.
     */
    private var inProcessSyncJob: Job? = null

    /** Elapsed-realtime of the last push-wake syncOnce. Read-receipt/unread-
     *  count push bursts collapse against this (see [onPushDelivered]): every
     *  group member's reads POST one push, and each syncOnce costs ~30-50 s of
     *  CPU on this account. */
    private var lastPushWakeSyncAtMs = 0L

    // --- Verification-first sync ----------------------------

    /**
     * True between a fresh login and the device-verification outcome: while
     * set, [clientConfiguration] hands the client the ultra-slim
     * [verificationSyncFilters] so the initial sync ingests ~300 room-list
     * bones instead of 6902 events (~180 s of serialized emit on the LP3) —
     * the SAS emoji round-trips must not be stuck behind it. Set by the login
     * paths before the client is built (the configuration is read at build
     * time; [finishLogin] runs too late), cleared by [swapToFullSync] and on
     * teardown paths. Stale-true degrades to a slim-rooms session, never a
     * broken one (the next login re-arms the full swap).
     */
    @Volatile
    private var pendingVerificationPhase = false

    /** The MatrixClientConfiguration captured while the current client was
     *  built ([clientConfiguration]'s receiver). Its filter properties are
     *  `var` (MatrixClientConfiguration.kt, Trixnity 5.8), so [swapToFullSync]
     *  mutates it in place instead of rebuilding the client. Cleared with the
     *  session, re-captured on every client build. */
    @Volatile
    private var activeClientConfig: MatrixClientConfiguration? = null

    /** Guards [swapToFullSync] — one swap per login (reset by the login paths). */
    private val verificationSyncSwapped = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The default network dropped — the next [networkCallback] onAvailable
     *  resets the sync loop (Beeper's `networkChanged`/`resetNetworkConnections`).
     */
    @Volatile
    private var networkWasLost = false

    /** Elapsed-realtime of the last network-triggered sync reset. */
    @Volatile
    private var lastNetworkResetAtMs = 0L

    /** Pending debounced push-wake sync (see [onPushDelivered]); restarted per
     *  real-message push so a burst coalesces to one syncOnce. */
    private var pushWakeJob: Job? = null

    /** Screen must stay off this long before dropping to slow sync. */
    private const val SLOW_SYNC_GRACE_MS = 60_000L

    /** Slow-sync cadence: one sync round every 5 min.
     *  ponytail: 5 min is a session value — tighten if message latency feels
     *  too high, loosen if battery still burns. */
    private const val SLOW_SYNC_INTERVAL_MS = 300_000L

    /** Push-gated lazy cadence: while the SSE push channel is
     *  provably connected, rounds stretch to 15 min — the push is the
     *  zero-latency wake for real messages, so rounds are only the redundancy
     *  net. A dead channel flips [PushChannel.isConnected] false within its
     *  90s read timeout and the next round drops back to
     *  [SLOW_SYNC_INTERVAL_MS]; the per-round re-check self-heals, so a
     *  silently-dead push costs at most one 15-min gap (the 08-28 30-min
     *  stretch failed because it never re-checked).
     *  ponytail: 15 min is a session value — tighten if the monitor shows
     *  receive latency, loosen if battery still burns. */
    private const val SLOW_SYNC_LAZY_INTERVAL_MS = 900_000L

    /** Events stamped more than this far into the future are bridge clock
     *  skew — never notified, never counted (the same rule lives in
     *  ProjectionPredicate as FUTURE_SKEW_MS). */
    private const val UNREAD_FUTURE_SKEW_MS = 300_000L

    /** Foreground-service promotion cadence. */
    private const val FGS_PROMOTE_INTERVAL_MS = 5_000L

    /** Min gap between read-receipt-push wakeups (see [onPushDelivered]). One
     *  sync per window is enough — the unread badge is at most this stale, and
     *  the next event push / slow round catches up. Matches the old 5-min round
     *  cadence, which was proven acceptable for badge freshness. */
    private const val COUNTS_WAKE_MIN_INTERVAL_MS = 300_000L

    /** Real-message push-wake debounce: a burst of messages is N
     *  wakes but needs one syncOnce — the trailing-edge debounce drains the
     *  window's pending wakes into a single sync, at ~1s latency. */
    private const val PUSH_WAKE_DEBOUNCE_MS = 1_000L

    /** Max syncOnce attempts per push wake (1 + 2 retries, WAKE-COMPARISON.md
     *  #2): a wake whose sync didn't reach the pushed event retries with
     *  backoff instead of silently dropping to the 5-min round (Beeper's
     *  NotCaughtUp retry, in-process — no WorkManager needed while the FGS
     *  holds the process). */
    private const val PUSH_WAKE_ATTEMPTS = 3

    /** Backoff base between push-wake retries. */
    private const val PUSH_WAKE_RETRY_DELAY_MS = 2_000L

    // ---- Durable push queue (SYNC-PERF-SPEC §3.2) ---------------------------
    //
    // A push delivered over SSE whose catch-up never completed must survive a
    // process death: the queue persists it (ids only) until a sync is proven
    // to have caught up. ntfy's `?since=` replay is the only other net, and
    // only for ntfy URLs.

    /** A delivered-but-not-caught-up push. Ids only — the push payload itself
     *  carries no content (push/README.md), so neither does the queue. */
    @Serializable
    private data class QueuedPush(val eventId: String, val roomId: String, val at: Long)

    private const val PUSH_QUEUE_PREFS = "push_queue"
    private const val PUSH_QUEUE_KEY = "pending"
    /** Age bound: ntfy replays the stream ~12h (push/README.md) — an older gap
     *  is unreachable anyway, and the entry would only ever wake on stale
     *  evidence. Entries beyond this age out of the queue. */
    private const val PUSH_QUEUE_MAX_AGE_MS = 12L * 60 * 60 * 1000
    private const val PUSH_QUEUE_MAX_ENTRIES = 50

    private val pushQueueJson = Json { ignoreUnknownKeys = true }
    private val pushQueueLock = Any()

    /** One drain attempt per process (retried while the client is still null). */
    @Volatile
    private var pushQueueDrained = false

    /** The queue lives as one JSON array in SharedPreferences — a bounded,
     *  ids-only file. */
    private fun loadPushQueue(): List<QueuedPush> =
        runCatching {
            appContext?.getSharedPreferences(PUSH_QUEUE_PREFS, Context.MODE_PRIVATE)
                ?.getString(PUSH_QUEUE_KEY, null)
                ?.let { pushQueueJson.decodeFromString<List<QueuedPush>>(it) }
        }.getOrNull().orEmpty()
            .filter { it.at > System.currentTimeMillis() - PUSH_QUEUE_MAX_AGE_MS }

    private fun savePushQueue(queue: List<QueuedPush>) {
        appContext?.getSharedPreferences(PUSH_QUEUE_PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(
                PUSH_QUEUE_KEY,
                queue.takeLast(PUSH_QUEUE_MAX_ENTRIES)
                    .takeIf { it.isNotEmpty() }
                    ?.let { pushQueueJson.encodeToString(it) },
            )
            ?.apply()
    }

    private fun enqueuePush(eventId: String, roomId: String) {
        synchronized(pushQueueLock) {
            savePushQueue(
                loadPushQueue().filterNot { it.eventId == eventId } +
                    QueuedPush(eventId, roomId, System.currentTimeMillis()),
            )
        }
    }

    private fun clearPushQueue() {
        synchronized(pushQueueLock) { savePushQueue(emptyList()) }
    }

    /**
     * Re-wake a push that was delivered over SSE but never proven caught up —
     * the process died between delivery and sync (SYNC-PERF-SPEC §3.2). Runs
     * once per process when slow sync first engages; entries the restore's
     * initial sync already delivered cost one store query each. One catch-up
     * wake covers the whole queue: the syncOnce runs to the present, so
     * everything queued before it landed too.
     */
    private suspend fun drainPushQueue() {
        if (pushQueueDrained) return
        val c = client ?: return
        pushQueueDrained = true
        val pending = loadPushQueue()
        val missing = pending.firstOrNull { !isEventStored(c, it.roomId, it.eventId) }
        if (missing == null) {
            if (pending.isNotEmpty()) clearPushQueue()
            return
        }
        android.util.Log.i(TAG, "push queue: ${pending.size} pending — catching up")
        runPushWake(c, missing.eventId, missing.roomId) // clears the queue when caught up
        // Fallback rounds own anything the wake couldn't reach; keeping the
        // ids would only re-wake on stale evidence (the age bound caps both).
        clearPushQueue()
    }


    /** Min gap between network-triggered sync restarts (flappy-radio guard,
     *  — see [networkCallback]). */
    private const val NETWORK_RESET_MIN_INTERVAL_MS = 60_000L

    /**
     * Per-room timeline window for the ACTIVE long-poll filter (PLAN §8.1):
     * bounds each room's per-/sync payload — the 30-50 s CPU per
     * sync on the 1284-room account was mostly pages of timeline events nobody
     * read. 50 is high enough to never truncate a busy bridged room's burst:
     * Trixnity marks `limited` syncs but never backfills, so a truncated burst
     * is a silent message gap — and while the screen is on, gap-fill matters.
     */
    private const val SYNC_TIMELINE_LIMIT = 50L

    /**
     * Background-only timeline window (SYNC-PERF-SPEC §Phase 1):
     * the syncOnce filter (slow rounds / push wakes / send-wakes) serves steady
     * incremental deltas, not gap-fill, so a slimmer window parses, decrypts
     * and stores less per round. 20, not 10: a burst deeper than the window
     * truncates (the incident), and the wake's [isEventStored]
     * verification then misses → retries → a false sync-pending notification.
     */
    private const val SYNC_TIMELINE_LIMIT_BACKGROUND = 20L

    /** Screen on/off → sync cadence. Registered on the app context in [init],
     *  so it lives as long as the process (which the FGS keeps alive). */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    applySyncModeForScreenState()
                    // A message likely landed while the screen was dark — end
                    // the resolver's screen-off sleep so the list is fresh the
                    // moment the user opens it.
                    wakeRoomList()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    applySyncModeForScreenState()
                }
            }
        }
    }

    /**
     * Network-loss recovery: a transport drop can leave
     * Trixnity's sync loop dead until its internal retry or the watchdog
     * fires — reset it as soon as the network is back. Registered on the app
     * context in [init], process-lifetime like [screenReceiver].
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: android.net.Network) {
            // Don't act yet — the radio may flap; the reset fires once the
            // next onAvailable proves the transport is really back.
            networkWasLost = true
        }

        override fun onAvailable(network: android.net.Network) {
            if (!networkWasLost) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastNetworkResetAtMs < NETWORK_RESET_MIN_INTERVAL_MS) return
            networkWasLost = false
            lastNetworkResetAtMs = now
            val c = client ?: return
            scope.launch {
                runCatching { c.stopSync() }
                android.util.Log.d(TAG, "network back after loss — resetting sync loop")
                Diagnostics.record("network back after loss — sync reset")
                if (isScreenInteractive()) {
                    // Re-arm through the shared entry point (the stopSync above
                    // un-armed the slot, so [startSyncLoop] must re-arm it). A
                    // dark screen must NOT start a long-poll: that branch goes
                    // through the shared screen → cadence entry point (the
                    // battery-saver and slow-sync gates live in it).
                    inProcessSyncRunning = false
                    startSyncLoop(appContext ?: return@launch)
                } else {
                    applySyncModeForScreenState()
                }
            }
        }
    }

    /** Called once from [ServerApplication]; restores a stored session if there is one. */
    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext == null) appContext = app
        Diagnostics.init(app)
        enableTrixnityLogging()
        // Settings → Sync pause (audit 2026-08-14): a paused companion starts
        // no sync loop and no foreground service — the battery escape hatch.
        syncEnabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_ENABLED, true)
        // Seed the restore-completed flag from prefs: the crawl is
        // throttled to once per 24h, so after a restart the in-memory
        // [RestoreProgress] would claim "not completed" until the next real
        // crawl — the Account screen's "All messages restored" could never show.
        _restoreProgress.value = RestoreProgress(
            completed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RESTORE_COMPLETED, false),
        )
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
        // Network-loss recovery: reset the sync loop when the
        // transport returns — Trixnity can sit dead until its internal retry.
        // The initial onAvailable for the current default network is a no-op
        // (networkWasLost starts false).
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?.registerDefaultNetworkCallback(networkCallback)
        // Booted with the screen already dark: no SCREEN_OFF broadcast is
        // coming — drop to slow sync after the grace instead of long-polling
        // with nobody watching.
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == false) scheduleSlowSync()
        scope.launch {
            // Restore the session regardless of the sync toggle. GetAccountState
            // reads the live client, so a paused companion that skips the restore
            // makes the tool report "Not signed in" while the session is fine.
            if (ensureClient() != null) {
                // Screen-state-aware start: a
                // session restore that lands while the screen is dark must
                // NOT long-poll — the boot-time sample above races the
                // restore, and a SCREEN_OFF broadcast that fired before the
                // receiver registered is gone. The shared entry point
                // applies the cadence the screen actually calls for (battery
                // saver: nothing while dark, full sync while on).
                applySyncModeForScreenState()
                // Push wake-up channel: register the Matrix
                // HTTP pusher + hold the SSE subscription so idle sync has
                // zero latency — see PushChannel. It is background keep-alive,
                // so battery saver never starts it.
                if (syncEnabled) PushChannel.start(app, client!!)
            }
        }
    }

    /**
     * Toggles Battery Saver (Settings): when on, all sync machinery stops
     * while the screen is dark — messages arrive only while the screen is
     * on. Re-enabling restores the session if needed and restarts the loop.
     * Persisted, so it survives reboots.
     */
    suspend fun setSyncEnabled(enabled: Boolean) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
        syncEnabled = enabled
        val c = client
        // Cancel the dark-screen schedules in both branches (battery saver
        // stops them; re-enabling restarts them below).
        slowSyncJob?.cancel()
        slowSyncJob = null
        screenOffJob?.cancel()
        screenOffJob = null
        syncMode = SyncMode.ACTIVE
        if (!enabled) {
            if (isScreenInteractive()) {
                // Foreground sync keeps running while the screen is on — the
                // dark-screen teardown happens on the next SCREEN_OFF.
                PushChannel.stop()
            } else {
                stopBackgroundSync()
                setConnectionState(ChatConnectionState.Offline("battery saver"))
            }
            android.util.Log.d(TAG, "battery saver on")
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
     * Stops every piece of sync machinery (battery saver with a dark screen):
     * the long-poll, slow-sync rounds, the push channel and the FGS. The
     * notification watcher and room-list resolver go dormant on their own
     * (without sync the room flows never emit).
     */
    private suspend fun stopBackgroundSync() {
        val ctx = appContext ?: return
        runCatching { client?.stopSync() }
        inProcessSyncJob?.cancel()
        inProcessSyncJob = null
        inProcessSyncRunning = false
        PushChannel.stop()
        ctx.stopService(android.content.Intent(ctx, ChatSyncService::class.java))
        Diagnostics.record("sync stopped")
    }

    /**
     * Single entry point for the screen-state → sync-cadence decision.
     * Called from [init] after the client is ready, the SCREEN_ON/OFF
     * receiver, and [ChatSyncService] before it starts a long-poll, so a sync
     * loop never runs while the screen is dark. Screen on → active long-poll;
     * dark → slow sync after the grace, or nothing at all under battery saver.
     */
    fun applySyncModeForScreenState() {
        if (isScreenInteractive()) {
            scope.launch { enterActiveSync() }
        } else if (syncEnabled) {
            scheduleSlowSync()
        } else {
            // Battery saver: nothing runs while the screen is dark.
            scope.launch { stopBackgroundSync() }
        }
    }

    /**
     * Drops to the slow cadence once the screen has been dark for the grace
     * period: stop the long-poll and run periodic [MatrixClient.syncOnce]
     * rounds instead. The FGS stays (it keeps the process alive for the slow
     * loop); battery saver (the toggle off) runs nothing at all while dark.
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
        // process restart while dark — audit). Wait for it
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
        // Screen truth re-check: the grace's check above can race
        // a SCREEN_ON broadcast (they fire in the same ms on a button press).
        // With a live client (the c == null branch above is skipped) engaging
        // slow mode then STOPS the long-poll while the screen is on — the
        // "sync mode: active" + "sync mode: slow" back-to-back log + a dead
        // loop until the next SCREEN_ON.
        if (isScreenInteractive()) return // screen came back on — enterActiveSync owns sync
        syncMode = SyncMode.SLOW // gate first: the watchdog must not restart the long-poll
        runCatching { c.stopSync() }
        Diagnostics.record("sync paused (slow mode)")
        inProcessSyncJob?.cancel()
        inProcessSyncJob = null
        inProcessSyncRunning = false
        slowSyncJob?.cancel()
        slowSyncJob = startSlowSyncRounds(c)
        // Catch up on pushes the previous process delivered but never synced
        // (SYNC-PERF-SPEC §3.2) — slow sync is the cadence whose gaps the
        // queue exists to close; active long-poll delivery needs no replay.
        scope.launch { drainPushQueue() }
        android.util.Log.d(
            TAG,
            "sync mode: slow (syncOnce every ${SLOW_SYNC_INTERVAL_MS / 1000}s)",
        )
        Diagnostics.record("sync mode: slow (syncOnce every ${SLOW_SYNC_INTERVAL_MS / 1000}s)")
    }

    /** One syncOnce round with a wall-clock duration log — the per-sync cost is
     *  the battery metric that decides whether sync can be leaner (Beeper's
     *  client wakes in ~1s; ours measured here — audit). The
     *  round's outcome re-asserts the connection state: a
     *  successful round proves connectivity, so it clears a stale "offline"
     *  left by the syncState observer (which can freeze on a long-poll TIMEOUT
     *  while the rounds keep succeeding — the LP3's stuck "Can't reach server"
     *  banner). The observer still reports long-poll TIMEOUT/ERROR instantly;
     *  this just makes recovery not depend on it. */
    private suspend fun timedSyncOnce(c: MatrixClient, reason: String): Result<Unit> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val result = runCatching { c.syncOnce(Presence.OFFLINE).getOrThrow() }
            .onSuccess {
                setConnectionState(ChatConnectionState.Syncing)
                // Any successful sync means the "checking failed" signal (if
                // any) is stale (WAKE-COMPARISON.md #3).
                appContext?.let { ChatNotifier.clearSyncPending(it) }
            }
            .onFailure { setConnectionState(ChatConnectionState.Offline("sync failed")) }
        // The round's ingest (parse/decrypt/store) is done once syncOnce
        // returns — release the sync-ingest gate here. In slow mode no further
        // /sync request follows for minutes, so without this stamp the gate
        // would read "in flight" until the next round and every heavy consumer
        // would burn its full 8s yield for nothing.
        syncRoundEndedAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d(TAG, "syncOnce took ${android.os.SystemClock.elapsedRealtime() - t0}ms ($reason)")
        Diagnostics.record("syncOnce took ${android.os.SystemClock.elapsedRealtime() - t0}ms ($reason)")
        return result
    }

    /** The periodic syncOnce rounds (also restarted by a push-wake — see [onPushDelivered]). */
    private fun startSlowSyncRounds(c: MatrixClient): Job {
        val job = scope.launch {
            var lastInterval = 0L
            while (isActive) {
                if (client !== c) return@launch // logged out / re-logged in under us
                // Delay before the first round: a wake
                // (push/send) cancels the rounds, runs its own syncOnce, then
                // recreates this job — an immediate first round duplicated the
                // wake's syncOnce (two /sync per wake, ~2x the per-push cost).
                // The wake's own round already delivers; the cadence below is
                // the redundancy net.
                // Push-gated cadence: while the push
                // channel is connected the rounds run lazy — pushes wake us
                // for real messages, so a 15-min net is enough; when it's
                // down we fall back to the 5-min cadence (a dead push must
                // not mean a long receive delay, 08-28 lesson — the per-round
                // re-check keeps that bounded to one lazy interval).
                val interval =
                    if (PushChannel.isConnected) SLOW_SYNC_LAZY_INTERVAL_MS else SLOW_SYNC_INTERVAL_MS
                if (interval != lastInterval) {
                    lastInterval = interval
                    android.util.Log.d(
                        TAG,
                        "slow sync interval: ${interval / 1000}s (push ${if (PushChannel.isConnected) "connected" else "down"})",
                    )
                }
                delay(interval)
                timedSyncOnce(c, "round")
                    .onFailure { android.util.Log.w(TAG, "slow sync round failed: ${it.message}") }
            }
        }
        return job
    }

    /** Back to the real-time long-poll (screen on, or any reason sync restarts). */
    private suspend fun enterActiveSync() {
        // Foreground (screen on): any "checking failed" signal is stale now
        // (WAKE-COMPARISON.md #3).
        appContext?.let { ChatNotifier.clearSyncPending(it) }
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
        val ctx = appContext ?: return
        if (client != null) {
            startSyncLoop(ctx)
            android.util.Log.d(TAG, "sync mode: active (long-poll)")
            Diagnostics.record("sync mode: active (long-poll)")
        }
    }

    /**
     * Push-wake: an SSE push notification
     * arrived, so a message is waiting. While idle (slow sync, screen off)
     * run ONE syncOnce round — the notification watcher then posts the local
     * notification and the room flows update. While active the long-poll
     * already delivers it, so the push is redundant and skipped. The slow-sync
     * rounds are the fallback delivery (a silent SSE drop must not mean missed
     * messages) — the same 5-min cadence with or without a live channel
     * (PLAN §8.2: was a 30-min push-gated net; a silently-dead
     * push meant a 30-min receive delay, and rounds are cheap with the sync
     * filter).
     * [countsOnly] = the push carried no room/event id (Beeper's read-receipt /
     * unread-count payloads). Those must not each run a full ~30-50 s syncOnce
     * — a group chat with N members generates one per read action. Bursts
     * collapse to one sync per [COUNTS_WAKE_MIN_INTERVAL_MS]: the FIRST push
     * still syncs (it can be the only signal for a real message, e.g.
     * note-to-self on Beeper's fork), and an event push right before covers
     * the state anyway. Real event pushes (message arriving) sync once per
     * burst — trailing-edge debounced [PUSH_WAKE_DEBOUNCE_MS] so N messages
     * cost one syncOnce (~1s latency). [eventId]/[roomId] come from the push
     * payload (event_id_only format) and let the wake verify the sync actually
     * reached the event (WAKE-COMPARISON.md #2).
     */
    suspend fun onPushDelivered(countsOnly: Boolean = false, eventId: String? = null, roomId: String? = null) {
        val c = client ?: return
        if (syncMode != SyncMode.SLOW) return
        if (countsOnly) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPushWakeSyncAtMs < COUNTS_WAKE_MIN_INTERVAL_MS) {
                android.util.Log.d(TAG, "counts push collapsed (last wake ${(now - lastPushWakeSyncAtMs) / 1000}s ago)")
                return
            }
            runPushWake(c, eventId, roomId)
            return
        }
        // Real-message push: coalesce bursts. The window's last push wins — the
        // sync runs once at the end instead of once per push. The push is
        // queued first: if the process dies before the wake's sync completes,
        // drainPushQueue() re-wakes it on the next slow-sync engagement
        // (SYNC-PERF-SPEC §3.2). Cleared by runPushWake once the event is
        // proven in the store.
        if (eventId != null && roomId != null) enqueuePush(eventId, roomId)
        pushWakeJob?.cancel()
        pushWakeJob = scope.launch {
            delay(PUSH_WAKE_DEBOUNCE_MS)
            if (client !== c || syncMode != SyncMode.SLOW) return@launch
            runPushWake(c, eventId, roomId)
        }
    }

    /** True when the event is already in the Room store — the store is the
     *  only consistent truth for "did sync reach this event" (the same tables
     *  readTimelineChainFromDb walks; single indexed point queries). Message
     *  events land in TimelineEvent; state events (invites, member/topic
     *  changes) land in RoomState's JSON `event` column instead — both are
     *  pushable, so both are checked. */
    private suspend fun isEventStored(c: MatrixClient, roomId: String, eventId: String): Boolean {
        val db = runCatching {
            c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class)
        }.onFailure { e ->
            android.util.Log.w(TAG, "isEventStored: TrixnityRoomDatabase not in DI", e)
        }.getOrNull() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val reader = db.openHelper.readableDatabase
                val inTimeline = reader.query(
                    "SELECT count(*) FROM TimelineEvent WHERE roomId = ? AND eventId = ?",
                    arrayOf<Any>(roomId, eventId),
                ).use { it.moveToFirst() && it.getInt(0) > 0 }
                if (inTimeline) return@withContext true
                reader.query(
                    "SELECT count(*) FROM RoomState WHERE roomId = ? AND json_extract(event, '$.event_id') = ?",
                    arrayOf<Any>(roomId, eventId),
                ).use { it.moveToFirst() && it.getInt(0) > 0 }
            }.onFailure { e ->
                android.util.Log.w(TAG, "isEventStored: store query failed", e)
            }.getOrDefault(false)
        }
    }

    /** The wake itself: cancel the fallback rounds, run ONE syncOnce, verify
     *  it reached the pushed event (bounded retries with backoff), restart
     *  the rounds if the screen is still dark. Shared by counts-only pushes
     *  (immediate) and debounced real-message wakes. */
    private suspend fun runPushWake(c: MatrixClient, eventId: String?, roomId: String?) {
        // Skip-when-useless (WAKE-COMPARISON.md #4): a slow round or an
        // earlier wake already delivered this event — no sync needed (the
        // notification watcher posted it when it was stored).
        if (eventId != null && roomId != null && isEventStored(c, roomId, eventId)) {
            android.util.Log.d(TAG, "push wake skipped — event already in store")
            clearPushQueue() // the round/sync that stored it already ran to the present
            return
        }
        slowSyncJob?.cancel()
        slowSyncJob = null
        Diagnostics.record("push wake")
        var caughtUp = false
        // NOTE: `return@repeat` would NOT break here — repeat's inline lambda
        // returning just continues the next index.
        // A plain for loop with `break` stops the retries once caught up.
        for (attempt in 0 until PUSH_WAKE_ATTEMPTS) {
            timedSyncOnce(c, if (attempt == 0) "push" else "push-retry")
                .onSuccess {
                    // Caught up = the sync actually stored the pushed event;
                    // counts-only wakes (no ids) have nothing to verify.
                    caughtUp = eventId == null || roomId == null || isEventStored(c, roomId, eventId)
                }
                .onFailure { android.util.Log.w(TAG, "push-wake sync failed: ${it.message}") }
            if (caughtUp) break
            if (attempt < PUSH_WAKE_ATTEMPTS - 1) delay(PUSH_WAKE_RETRY_DELAY_MS * (attempt + 1))
        }
        // Retries exhausted without the event landing — tell the user
        // something may be waiting (WAKE-COMPARISON.md #3). The fallback
        // rounds keep retrying, and the next successful sync clears it.
        if (!caughtUp) appContext?.let { ChatNotifier.notifySyncPending(it) } else clearPushQueue()
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
     * Full FINE tracing is gated behind the runtime `debugLog` flag (default
     * off — efficiency: FINE→logcat was always on and burned
     * standby CPU/logd volume all night); WARN+ always stays visible.
     */
    private fun enableTrixnityLogging() {
        val root = java.util.logging.Logger.getLogger("de.connect2x")
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
     * first arm the loop in-process — that works as long as the tool is bound/
     * foreground — then keep promoting to the foreground service so sync
     * survives the tool closing. ChatSyncService treats an armed in-process
     * loop as keep-alive-only instead of arming a second loop.
     * Arm-once since: under Trixnity v5, [MatrixClient.startSync]
     * does NOT run the sync inline — it arms the client's internal sync loop
     * (the /sync rounds and their error retries happen inside the client) and
     * returns. The old restart-with-backoff loop was built on v4 semantics
     * (startSync suspended until the loop died), so on v5 it logged "loop
     * ended" after every arm and re-armed on a 1→30 s clock, aborting the
     * in-flight round each time. A wedged loop is recovered by
     * [ChatSyncService]'s syncState watchdog once the promotion lands.
     */
    private fun startSyncLoop(context: Context) {
        val c = client ?: return
        if (inProcessSyncRunning) return
        inProcessSyncJob?.cancel()
        inProcessSyncRunning = true
        inProcessSyncJob = scope.launch {
            runCatching { c.startSync(Presence.OFFLINE) }
                .onFailure { android.util.Log.w(TAG, "in-process sync failed to arm: ${it.message}") }
        }
        android.util.Log.d(TAG, "in-process sync loop armed for ${c.userId.full}")
        // Foreground-service promotion at a fixed cadence (the old 3→60 s
        // geometric backoff stretched a blocked promotion to ~176 s — a fixed
        // interval converges within one tick of the system allowing it).
        scope.launch {
            while (isActive && client === c && !ChatSyncService.isRunning) {
                // Battery saver tore sync down with the screen dark — don't
                // resurrect the FGS behind the teardown's back.
                if (!syncEnabled && !isScreenInteractive()) return@launch
                if (ChatSyncService.tryStart(context)) break
                delay(FGS_PROMOTE_INTERVAL_MS)
            }
        }
    }

    /**
     * Login-path session teardown: stops the old client's sync loop and clears
     * the stale in-process-sync state (a stale flag would silently kill the
     * new session's sync — re-login after restore/expiry). Runs inside
     * [initMutex] BEFORE the new credentials are exchanged, so a failed
     * exchange leaves the old session stopped.
     */
    private suspend fun stopPreviousSession() {
        client?.let { old ->
            runCatching { old.stopSync() }
            client = null
        }
        inProcessSyncJob?.cancel()
        inProcessSyncJob = null
        inProcessSyncRunning = false
        setConnectionState(ChatConnectionState.Connecting)
    }

    /**
     * The client build + session-prefs tail shared by [login] and
     * [beeperLogin]. The verification-first sync configuration is read at
     * client build time, so the phase flag must be armed BEFORE create
     * (finishLogin runs too late — see [pendingVerificationPhase]).
     * Call after [stopPreviousSession], inside [initMutex].
     */
    private suspend fun createAndStoreClient(
        ctx: Context,
        authProviderData: MatrixClientAuthProviderData,
        configName: String,
        baseUrl: String,
        loginMode: String,
        removeKeys: List<String> = emptyList(),
    ): MatrixClient {
        // The raw ktor client (used for Beeper's provision API, see
        // [bridgeContacts]) does not attach the bearer — persist it here.
        val accessToken = (authProviderData as? ClassicMatrixClientAuthProviderData)?.accessToken
        pendingVerificationPhase = true
        verificationSyncSwapped.set(false)
        val loginResult = MatrixClient.create(
            repositoriesModule = RepositoriesModule.room(databaseBuilder(ctx)),
            mediaStoreModule = MediaStoreModule.okio(mediaDir(ctx)),
            cryptoDriverModule = CryptoDriverModule.libOlm(),
            authProviderData = authProviderData,
            configuration = clientConfiguration(configName),
        ).onFailure { pendingVerificationPhase = false }.getOrThrow()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOMESERVER, baseUrl)
            .putString(KEY_USER_ID, loginResult.userId.full)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_LOGIN_MODE, loginMode)
            .also { editor -> removeKeys.forEach { key -> editor.remove(key) } }
            .apply()
        return loginResult
    }

    suspend fun login(
        homeserver: String,
        user: String,
        passwordOrToken: String,
        tokenLogin: Boolean,
    ): Result<MatrixClient> = runCatching {
        val ctx = appContext ?: error("companion not initialized")
        initMutex.withLock {
            stopPreviousSession()

            // Accept a bare domain ("matrix.org") or a full URL; .well-known
            // discovery runs when the host serves one, else the URL is used as-is.
            val baseUrl = homeserver.trim().serverDiscovery(httpClientEngine = httpClientEngine).getOrThrow()

            val authProviderData = MatrixClientAuthProviderData.classicLogin(
                baseUrl = baseUrl,
                identifier = IdentifierType.User(user.trim()),
                password = if (tokenLogin) null else passwordOrToken,
                token = if (tokenLogin) passwordOrToken else null,
                loginType = if (tokenLogin) LoginType.Token() else LoginType.Password,
                initialDeviceDisplayName = "Chats (Light Phone)",
            ).getOrThrow()
            val loginResult = createAndStoreClient(
                ctx, authProviderData,
                configName = "chats",
                baseUrl = baseUrl.toString(),
                loginMode = "homeserver",
            )
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
            val requestId = pushQueueJson
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
    /**
     * Login wrappers projecting to [Result]`<Unit>` (NO-SEAM):
     * the tool module can't see Trixnity's MatrixClient (:server hides its
     * deps), so the direct caller maps the response from
     * [lastLoginUserId]/[lastLoginDeviceId]/[lastLoginNeedsVerification].
     */
    suspend fun loginAsUnit(
        homeserver: String,
        user: String,
        passwordOrToken: String,
        tokenLogin: Boolean,
    ): Result<Unit> = login(homeserver, user, passwordOrToken, tokenLogin).map { }

    suspend fun beeperLoginAsUnit(email: String, code: String): Result<Unit> =
        beeperLogin(email, code).map { }

    suspend fun beeperLogin(email: String, code: String): Result<MatrixClient> = runCatching {
        val ctx = appContext ?: error("companion not initialized")
        initMutex.withLock {
            stopPreviousSession()

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
                val json = pushQueueJson.parseToJsonElement(resp.bodyAsText()).jsonObject
                val whoami = json["whoami"]?.jsonObject ?: error("missing whoami")
                val userInfo = whoami["userInfo"]?.jsonObject ?: error("missing userInfo")
                username = userInfo["username"]?.jsonPrimitive?.content ?: error("missing username")
                loginToken = json["token"]?.jsonPrimitive?.content ?: error("missing token")
            } finally {
                http.close()
            }

            val authProviderData = MatrixClientAuthProviderData.classicLogin(
                baseUrl = Url(BEEPER_HOMESERVER),
                identifier = IdentifierType.User(username.trim()),
                password = null,
                token = loginToken,
                loginType = LoginType.Unknown("org.matrix.login.jwt", buildJsonObject {}),
                initialDeviceDisplayName = "Chats (Light Phone)",
            ).getOrThrow()
            val loginResult = createAndStoreClient(
                ctx, authProviderData,
                configName = "chats-beeper",
                baseUrl = BEEPER_HOMESERVER,
                loginMode = "beeper",
                removeKeys = listOf(KEY_BEEPER_REQUEST_ID),
            )
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
        _restoreProgress.value = RestoreProgress()
        // A fresh login must not inherit the previous account's "all messages
        // restored" claim (2026-09-01; logout already clears all prefs).
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RESTORE_COMPLETED).apply()
        slowSyncJob?.cancel()
        slowSyncJob = null
        screenOffJob?.cancel()
        screenOffJob = null
        syncMode = SyncMode.ACTIVE
        startSyncLoop(ctx)
        // Background keep-alive — battery saver never starts it.
        if (syncEnabled) PushChannel.start(ctx, newClient)
        // Verification is part of login (Beeper's model) — but only when there
        // is something to verify against: the account has cross-signing keys
        // uploaded and this fresh session isn't trusted yet. The request goes
        // out here so it is already pending when the tool shows the verify UI;
        // a fresh Synapse account (no cross-signing) skips the flow entirely.
        lastLoginNeedsVerification = postLoginNeedsVerification(newClient)
        if (lastLoginNeedsVerification) {
            runCatching { startDeviceVerification() }
                .onFailure { android.util.Log.w(TAG, "post-login verification auto-start failed: ${it.message}") }
        } else {
            // Nothing to verify (fresh account / no other device / already
            // trusted) — leave the verification-phase slim sync immediately.
            swapToFullSync()
        }
        // Identity the tool's login responses project (NO-SEAM: the tool module
        // can't see Trixnity's MatrixClient — :server hides its deps — so the
        // direct ChatClient mapping reads these instead of the client object).
        lastLoginUserId = newClient.userId.full
        lastLoginDeviceId = newClient.deviceId
        return newClient
    }

    /** Restore-path counterpart of [finishLogin]'s E2EE wiring: a verified
     *  restored session kicks the key-backup crawl (its own cooldown guards
     *  the cost); an unverified one flows into device verification like a
     *  fresh login — [postLoginNeedsVerification]'s false-bail behavior
     *  (cross-signing absent / no other devices) keeps this a no-op for
     *  accounts with nothing to verify. */
    private suspend fun armRestoreVerification(c: MatrixClient) {
        if (isDeviceVerified(c)) {
            if (!restoreAttempted) {
                restoreAttempted = true
                restoreMegolmSessions()
            }
        } else {
            lastLoginNeedsVerification = postLoginNeedsVerification(c)
            if (lastLoginNeedsVerification) {
                runCatching { startDeviceVerification() }
                    .onFailure { android.util.Log.w(TAG, "post-restore verification auto-start failed: ${it.message}") }
            }
        }
    }

    /** Whether the most recent login ended with device verification pending —
     *  stamped by [finishLogin], read by ChatServiceMethods to add
     *  `needsVerification` to the SetAccount/SetBeeperAccount responses. */
    @Volatile
    var lastLoginNeedsVerification: Boolean = false
        private set

    /** The last finished login's identity (see the NO-SEAM note in
     *  [finishLogin]) — null until a login completes in this process. */
    @Volatile
    var lastLoginUserId: String? = null
        private set

    @Volatile
    var lastLoginDeviceId: String? = null
        private set

    /**
     * Whether a just-finished login should flow into device verification:
     * the account has cross-signing keys uploaded server-side (something to
     * verify against) and at least one other device exists. The trust read at
     * the end only rules out an already-trusted session; a fresh login always
     * mints a new device id, so a timed-out read counts as unverified here
     * (the opposite of [isDeviceVerified]'s TOFU policy, which protects
     * steady-state reads of a device already in use). Any failure (network,
     * API) reads as "no verification needed" — the manual Verify Device row
     * remains the fallback.
     */
    private suspend fun postLoginNeedsVerification(c: MatrixClient): Boolean = runCatching {
        val keys = c.api.key.getKeys(mapOf(c.userId to emptySet<String>())).getOrThrow()
        if (keys.masterKeys?.get(c.userId) == null && keys.userSigningKeys?.get(c.userId) == null) {
            return@runCatching false // fresh account — no cross-signing to verify with
        }
        val others = c.api.device.getDevices().getOrThrow().count { it.deviceId != c.deviceId }
        if (others == 0) return@runCatching false
        val trust = withTimeoutOrNull(KEY_BACKUP_VERIFY_TIMEOUT_MS) {
            c.key.getTrustLevel(c.userId, c.deviceId).firstOrNull()
        }
        trust !is de.connect2x.trixnity.crypto.key.DeviceTrustLevel.CrossSigned
    }.getOrDefault(false)

    /**
     * One-time /sync filter migration: Trixnity uploads the
     * sync filter once at client setup and caches its id in the Account row;
     * the LP3's cached filter predates `com.beeper.inbox.done` joining
     * the whitelist ([applyDefaultFilter]), so /sync strips it — and because
     * the sync token advanced past those changes while stripped, incremental
     * sync never re-delivers them (Jeff/Tiki never reflected on the LP3).
     * Clearing filterId/backgroundFilterId forces the client's setup to
     * upload a fresh filter.
     * v4 also cleared syncBatchToken — a full initial sync under
     * the fresh filter, needed to re-deliver state that had already passed.
     * v5 keeps the token: the ephemeral notTypes slimming (see
     * [clientConfiguration]) only affects future windows — ephemeral events
     * are never re-delivered — so a full re-sync would just burn CPU.
     * v4/v5 bug: the SQL targeted
     * filterId/backgroundFilterId columns that don't exist in the
     * de.connect2x fork — it stores BOTH filter ids as JSON in a single
     * `filter` TEXT column. The UPDATE threw SQLITE_ERROR and runCatching
     * swallowed it, so both migrations silently failed on the LP3 and the old
     * filter stayed live. v6 clears the `filter` column itself, forcing the
     * client's setup to re-upload both filters. The token stays untouched
     * (ephemeral is never re-delivered, no full re-sync needed).
     * Must run BEFORE the client is built ([ensureClient]): the client's
     * setup flow uploads a missing filter itself, while startSync
     * checkNotNulls the stored filterId — clearing it on a live client races
     * the upload and throws. AccountStore.updateAccount can't be used here:
     * it passes keyExists=false, which skips the repository write on a cold
     * cache (the updater sees null and nothing persists), so the Account row is
     * cleared via SQL directly. Prefs-gated
     * once per account, keyed by [SYNC_FILTER_MAPPINGS_VERSION].
     */
    private suspend fun migrateSyncFilterIfNeeded(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val userId = prefs.getString(KEY_USER_ID, null) ?: return
        val prefsKey = "sync_filter_mappings_v_$userId"
        if (prefs.getInt(prefsKey, 0) >= SYNC_FILTER_MAPPINGS_VERSION) return
        runCatching {
            val db = databaseBuilder(ctx).build()
            try {
                db.openHelper.writableDatabase.execSQL("UPDATE Account SET filter = NULL")
            } finally {
                db.close()
            }
            android.util.Log.d(TAG, "sync-filter migration: cleared cached filter for $userId (v$SYNC_FILTER_MAPPINGS_VERSION)")
            prefs.edit().putInt(prefsKey, SYNC_FILTER_MAPPINGS_VERSION).apply()
        }.onFailure { e ->
            android.util.Log.w(TAG, "sync-filter migration failed: ${e.message}")
        }
    }

    /**
     * Ends the verification-phase slim sync (see [pendingVerificationPhase]):
     * swaps the captured configuration's filters to the full set, re-uploads
     * them, stores the fresh ids in the Account row, and restarts the sync
     * loop so the long-poll picks up the full sync filter (background
     * syncOnce rounds read the stored id per round). Called from the
     * verification Done branch, its timeout branch, and [finishLogin] when no
     * verification is needed. Idempotent — one swap per login, one retry.
     * Trixnity 5.8 uploads filters exactly once, in the client's init
     * coroutine (MatrixClient.kt: the `filter == null || eventTypesHash
     * changed` gate); startSync/syncOnce only read the STORED ids — so the
     * [migrateSyncFilterIfNeeded] trick (clear the ids, restart) cannot work
     * on a live client: startSync checkNotNulls the stored syncFilterId and
     * the upload never re-runs. The swap therefore uploads the full filters
     * itself — replicating the private MatrixClient.applyDefaultFilter merge
     * over the CLIENT's own [EventContentSerializerMappings] — and writes the
     * ids through the client's AccountStore (raw SQL would desync its
     * in-memory cache). The upload+store happens AFTER the client's `started`
     * flag flips (the init coroutine's last act, after it stores its own ids),
     * so the swap always wins over the init store even when they race.
     */
    private suspend fun swapToFullSync() {
        if (!verificationSyncSwapped.compareAndSet(false, true)) return
        pendingVerificationPhase = false
        android.util.Log.i(TAG, "sync: verification phase done — swapping to full sync filter, restarting loop")
        swapOnceToFullSync()
            .onFailure { e ->
                android.util.Log.w(TAG, "full-sync swap failed (${e.message}) — retrying once in ${SYNC_SWAP_RETRY_MS / 1000} s")
                delay(SYNC_SWAP_RETRY_MS)
                swapOnceToFullSync().onFailure {
                    android.util.Log.w(TAG, "full-sync swap retry failed: ${it.message} — session stays on the slim verification filters until re-login")
                }
            }
    }

    private suspend fun swapOnceToFullSync(): Result<Unit> = runCatching {
        val c = client ?: error("client gone")
        val config = activeClientConfig ?: error("no captured client configuration")
        config.syncFilter = fullSyncFilters
        config.syncOnceFilter = fullSyncOnceFilters
        // The init upload reads the config BEFORE its network calls and stores
        // the ids after — a swap racing it could otherwise read filter==null,
        // skip, and let the init store the SLIM ids right after. `started` flips only AFTER the init store,
        // so waiting for it makes the stored ids final; we then overwrite them
        // with the full pair. Bounded: an init that never completes (dead
        // network) degrades to uploading with an empty eventTypesHash, which
        // simply forces a re-upload on the next client start.
        withTimeoutOrNull(SYNC_SWAP_INIT_WAIT_MS) { c.started.first { it } }
        // Stop the slim loop BEFORE nulling the batch token: the old loop's
        // in-flight round writes its token on completion, and a token landing
        // after our null turns the restarted loop into incremental rounds —
        // the state-bearing initial sync never runs.
        runCatching { c.stopSync() }
        inProcessSyncRunning = false
        val accountStore = c.di.get<AccountStore>()
        val mappings = c.di.get<EventContentSerializerMappings>()
        val storedHash = accountStore.getAccount()?.filter?.eventTypesHash
        val syncFilterId = c.api.user.setFilter(c.userId, fullSyncFilters.applyDefaultFilter(mappings, config)).getOrThrow()
        val syncOnceFilterId = c.api.user.setFilter(c.userId, fullSyncOnceFilters.applyDefaultFilter(mappings, config)).getOrThrow()
        c.di.get<StoreTransactionManager>().writeTransaction {
            accountStore.updateAccount {
                // syncBatchToken = null is essential: the slim verification
                // phase ADVANCED the batch token past all room state/timeline
                // (limit 1, state notTypes="*"), and an incremental restart
                // never re-delivers what the token passed — rooms render
                // nameless ("Chat…") and history stays "Encrypted" forever.
                // Nulling the token makes the restarted loop
                // do a true full initial sync under the full filter — same
                // reasoning as sync-filter migration v4.
                it?.copy(
                    filter = Account.Filter(syncFilterId, syncOnceFilterId, storedHash ?: ""),
                    syncBatchToken = null,
                )
            }
        }
        // Restart the loop through the shared cadence entry points (same shape
        // as the network-recovery reset): a dark screen must not start a
        // long-poll — it goes through the screen → cadence decision instead.
        if (isScreenInteractive()) {
            startSyncLoop(appContext ?: error("companion not initialized"))
        } else {
            applySyncModeForScreenState()
        }
    }

    /**
     * Replica of Trixnity's private `MatrixClientImpl.Filters.applyDefaultFilter`
     * (5.8) — it merges Trixnity's type whitelists (from the CLIENT's [mappings])
     * over [this], keeping `notTypes`, and enables lazy member loading.
     * [swapToFullSync] uploads with it; if Trixnity's version changes, this
     * must follow, or the swapped-in filter silently diverges from what the
     * init upload would have stored (verified against MatrixClient.kt 5.8.0).
     */
    private fun Filters.applyDefaultFilter(mappings: EventContentSerializerMappings, config: MatrixClientConfiguration): Filters =
        copy(
            accountData =
                (accountData ?: Filters.EventFilter()).copy(types = mappings.globalAccountData.map { it.type }.toSet()),
            room =
                (room ?: Filters.RoomFilter()).copy(
                    accountData =
                        (room?.accountData ?: Filters.RoomFilter.RoomEventFilter()).copy(
                            types = mappings.roomAccountData.map { it.type }.toSet()
                        ),
                    ephemeral =
                        (room?.ephemeral ?: Filters.RoomFilter.RoomEventFilter()).copy(
                            types = mappings.ephemeral.map { it.type }.toSet()
                        ),
                    state =
                        (room?.state ?: Filters.RoomFilter.RoomEventFilter()).copy(
                            lazyLoadMembers = true,
                            types = mappings.state.map { it.type }.toSet(),
                        ),
                    timeline =
                        (room?.timeline ?: Filters.RoomFilter.RoomEventFilter()).copy(
                            types = (mappings.message + mappings.state).map { it.type }.toSet()
                        ),
                    includeLeave = config.deleteRooms !is DeleteRooms.OnLeave,
                ),
        )

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
            if (methods !is de.connect2x.trixnity.client.verification.VerificationService.SelfVerificationMethods.CrossSigningEnabled) {
                error("cross-signing is not set up on this account")
            }
            if (methods.methods
                    .none { it is de.connect2x.trixnity.client.verification.SelfVerificationMethod.AesHmacSha2RecoveryKey }
            ) error("no recovery-key method available")
            // Strip everything but letters/digits (dash- or space-grouped keys,
            // pasted or typed). Case is preserved — the key is case-sensitive and
            // Trixnity's decodeRecoveryKey strips whitespace but not dashes.
            val key = recoveryKey.filter { it.isLetterOrDigit() }
            if (key.length < 48) error("recovery key needs 48 characters — got ${key.length}")

            // Beeper compatibility: stock Trixnity's
            // AesHmacSha2RecoveryKey.verify first runs checkRecoveryKey, which
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
            // Local-only: flags-only re-stamp, no crawl (INGEST-DERIVED-PLAN
            // Phase C); unread suppression re-derives per row as rooms change.
            flagsOnlyWake = true
            markRoomListDirty()
            wakeRoomList()
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
        data class Compare(val emoji: List<String>, val deviceId: String? = null) : VerificationUi
        data object Done : VerificationUi
        data object Cancelled : VerificationUi
        data class Error(val detail: String) : VerificationUi
    }

    private val _verification = MutableStateFlow<VerificationUi>(VerificationUi.Idle)
    val verification: StateFlow<VerificationUi> = _verification.asStateFlow()

    private var verificationCollectJob: Job? = null
    private var verificationTimeoutJob: Job? = null

    /** Device that accepted our verification request (their SAS start's from_device). */
    @Volatile
    private var acceptingDeviceId: String? = null

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
    /** When their SAS start landed (elapsedRealtime), to recognize the
     *  fan-out collision cancel (see the Cancel branch below). */
    @Volatile
    private var theirSasStartAtMs: Long = 0L

    /** E2EE status memoized for [E2EE_STATE_TTL_MS] — the network getDevices
     *  on every poll (1-5 s) + every thread open was the slow, constant
     *  "Checking if account is verified" work. (elapsedRealtime
     *  fetchedAt, verified, other-device count.) */
    @Volatile
    private var e2eeStateCache: Triple<Long, Boolean, Int>? = null

    /** E2EE status: whether this device is cross-signing verified, and whether other devices exist to verify with. */
    suspend fun e2eeState(): com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response {
        val c = client ?: return com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response(
            verified = false, canVerify = false, detail = "not logged in",
        )
        fun response(verified: Boolean, devices: Int) =
            com.thelightphone.sdk.shared.LightServiceMethod.GetE2eeState.Response(
                verified = verified,
                canVerify = devices > 0,
                detail = if (verified) null else "not verified",
            )
        val now = android.os.SystemClock.elapsedRealtime()
        e2eeStateCache?.let { (fetchedAt, verified, devices) ->
            if (now - fetchedAt < E2EE_STATE_TTL_MS) return response(verified, devices)
        }
        // Same policy as [isDeviceVerified]: a timed-out trust read must NOT
        // read as "unverified" — on the LP3 the first read after opening the
        // screen can exceed the budget on a cold trust store, and the Account
        // row flipped "not verified" → "verified" on the next 5s poll.
        // Only a genuine non-CrossSigned trust
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
        return response(verified, devices)
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
        setVerificationUi(VerificationUi.Waiting)
        verificationCollectJob?.cancel()
        verificationCollectJob = scope.launch {
            request.state.collectLatest { state -> onVerificationState(state) }
        }
        verificationTimeoutJob?.cancel()
        verificationTimeoutJob = scope.launch {
            delay(VERIFICATION_TIMEOUT_MS)
            if (activeVerification === request) {
                android.util.Log.w(TAG, "verification timed out after ${VERIFICATION_TIMEOUT_MS / 60_000L} min — cancelling")
                runCatching { request.cancel() }
                verificationCollectJob?.cancel()
                setVerificationUi(VerificationUi.Error("Verification timed out — try your recovery key"))
                swapToFullSync() // verification-first sync — see [pendingVerificationPhase]
            }
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
            deviceId = (ui as? VerificationUi.Compare)?.deviceId,
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
        verificationTimeoutJob?.cancel()
        verificationCollectJob?.cancel()
        verificationTimeoutJob = null
        verificationCollectJob = null
        acceptingDeviceId = null
        activeVerification = null
        pendingTheirRequest = null
        pendingReady = null
        pendingTheirSasStart = null
        pendingCompare = null
        theirSasStartAtMs = 0L
        setVerificationUi(VerificationUi.Idle)
    }

    private suspend fun onVerificationState(state: ActiveVerificationState) {
        android.util.Log.i(TAG, "verify: top-level state -> ${state::class.simpleName}")
        setVerificationUi(
            when (state) {
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
                // reverting — ).
                VerificationUi.Accept
            }
            is ActiveVerificationState.Done -> {
                verificationTimeoutJob?.cancel()
                e2eeStateCache = null // verification changed — recompute on next read
                // The backup-key secret lands via /sync a moment after Done, but
                // the login-time restore ran before it existed and set the 24h
                // cooldown. Clear it and retry on a short ladder so the crawl
                // actually runs once the key is local.
                scope.launch {
                    val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return@launch
                    // The backup secret's arrival is out of our control: another
                    // device answers the secret request whenever it does — 25 s
                    // in the working run, never (5+ min) in the two failing
                    // logins. The old 3-try/90 s ladder
                    // gave up before late answers could land. Each attempt
                    // re-requests the missing secrets internally (see
                    // [restoreMegolmSessions]); a success stamps the cooldown
                    // and later attempts no-op cheaply.
                    listOf(10L, 30L, 60L, 120L, 300L, 600L, 1200L).forEach { delayS ->
                        delay(delayS * 1000)
                        prefs.edit().remove(KEY_RESTORE_LAST_RUN_MS).apply()
                        restoreMegolmSessions()
                    }
                }
                // Local-only: flags-only re-stamp, no crawl (INGEST-DERIVED-PLAN
                // Phase C); the full-sync swap below re-derives unread reactively.
                flagsOnlyWake = true
                markRoomListDirty()
                wakeRoomList()
                scope.launch { swapToFullSync() } // verification-first sync — see [pendingVerificationPhase]
                VerificationUi.Done
            }
            else -> {
                if (state is ActiveVerificationState.Cancel) {
                    verificationTimeoutJob?.cancel()
                    // Trixnity quirks: a spurious
                    // m.unexpected_message cancel fires either pre-flow (~4 s
                    // in, right before the partner's accept lands) or moments
                    // after their SAS start when two devices answer the fan-out
                    // request simultaneously (collision). Swallow both so a
                    // healthy flow survives; anything else (a real user or
                    // timeout cancel from the partner) still cancels the UI.
                    val inCollisionWindow = theirSasStartAtMs > 0 &&
                        android.os.SystemClock.elapsedRealtime() - theirSasStartAtMs < SAS_COLLISION_GRACE_MS
                    if (state.content.code == VerificationCancelEventContent.Code.UnexpectedMessage &&
                        (pendingReady == null || inCollisionWindow)
                    ) {
                        android.util.Log.i(
                            TAG,
                            "verify: swallowing spurious m.unexpected_message cancel (" +
                                if (pendingReady == null) "pre-flow"
                                else "${android.os.SystemClock.elapsedRealtime() - theirSasStartAtMs} ms after their SAS start" +
                                    ", accepted by ${acceptingDeviceId ?: "?"}" +
                                    "), content=${state.content}"
                        )
                        _verification.value
                    } else {
                        android.util.Log.w(TAG, "verify: cancelled — content=${state.content}")
                        VerificationUi.Cancelled
                    }
                } else {
                    VerificationUi.Waiting
                }
            }
        },
    )
    }

    private suspend fun onSasState(state: ActiveSasVerificationState) {
        android.util.Log.i(TAG, "verify: SAS state -> ${state::class.simpleName}")
        setVerificationUi(
            when (state) {
            is ActiveSasVerificationState.OwnSasStart -> VerificationUi.Verifying
            is ActiveSasVerificationState.TheirSasStart -> {
                pendingTheirSasStart = state
                theirSasStartAtMs = android.os.SystemClock.elapsedRealtime()
                // Their start's from_device is the accepting device — the
                // request fanned out to every other device, and the UI wants
                // to show which one actually accepted.
                state.content.fromDevice.takeIf { it.isNotBlank() }?.let { deviceId ->
                    acceptingDeviceId = deviceId
                    android.util.Log.i(TAG, "verify: accepted by device $deviceId")
                }
                VerificationUi.Accept
            }
            is ActiveSasVerificationState.ComparisonByUser -> {
                pendingCompare = state
                VerificationUi.Compare(state.emojis.map { it.second }, acceptingDeviceId)
            }
            // WaitForKeys / WaitForMacs are exchange progress — "Verifying…",
            // not "waiting for the other device to accept".
            else -> VerificationUi.Verifying
            },
        )
    }

    /**
     * Process-death recovery: a restart loses the cached SSSS
     * secrets (backup key, cross-signing keys) that Trixnity normally receives
     * exactly once, right after device verification — its own auto-request is
     * a one-shot at client start and never re-fires. Ask our verified own
     * devices for the missing secrets via to-device m.secret.request and
     * register the request with the KeyStore: that's what lets Trixnity's own
     * answer handler accept the Olm-encrypted m.secret.send reply, validate it
     * and persist the secret — after which version/restore recover on their
     * own. Returns true if at least one request was sent.
     */
    private suspend fun reRequestMissingSecrets(c: MatrixClient): Boolean = runCatching {
        val keyStore = c.di.get<KeyStore>(KeyStore::class)
        val tm = c.di.get<StoreTransactionManager>(StoreTransactionManager::class)
        // An unanswered request must not block the retry ladder. Purge
        // pending requests and re-send — a late answer still matches by request
        // id at accept time, so purging is safe.
        keyStore.getAllSecretKeyRequests().forEach { req ->
            req.content.requestId?.let { id ->
                tm.writeTransaction { keyStore.deleteSecretKeyRequest(id) }
            }
        }
        val missing = SecretType.entries.filter { it.cacheable }
            .subtract(keyStore.getSecrets().keys)
            .subtract(
                keyStore.getAllSecretKeyRequests()
                    .mapNotNull { req -> req.content.name?.let { SecretType.ofId(it) } }
                    .toSet()
            )
        if (missing.isEmpty()) return@runCatching false
        // The local device-key store only knows devices a previous flow happened
        // to fetch — after repeated logout/logins that's mostly our own dead
        // past sessions, while the live Beeper clients (which hold the secrets)
        // were never asked.
        // mautrix/Beeper requests from ALL own devices ("*" semantics). Refresh
        // own keys from the server first: OutdatedKeysHandler fetches /keys/query
        // and computes trust for every current device when the user is marked
        // outdated — poll briefly for new devices to land, then ask them all.
        val knownBefore = keyStore.getDeviceKeys(c.userId).first().orEmpty().keys
        c.di.get<StoreTransactionManager>(StoreTransactionManager::class).writeTransaction {
            keyStore.updateOutdatedKeys { it + c.userId }
        }
        withTimeoutOrNull(15_000) {
            while (keyStore.getDeviceKeys(c.userId).first().orEmpty().keys.size <= knownBefore.size) {
                delay(500)
            }
        }
        val receiverDevices = keyStore.getDeviceKeys(c.userId).first().orEmpty()
            .filterKeys { it != c.deviceId }
        if (receiverDevices.isEmpty()) {
            android.util.Log.w(TAG, "restore: no own device to re-request secrets from")
            return@runCatching false
        }
        receiverDevices.forEach { (id, dk) ->
            android.util.Log.i(TAG, "restore: secret-request receiver $id trust=${dk.trustLevel}")
        }
        val receivers = receiverDevices.keys
        missing.forEach { secret ->
            val request = SecretKeyRequestEventContent(
                name = secret.id,
                action = KeyRequestAction.REQUEST,
                requestingDeviceId = c.deviceId,
                requestId = SecureRandom.nextString(22),
            )
            c.api.user
                .sendToDevice(mapOf(c.userId to receivers.associateWith { request }))
                .getOrThrow()
            tm.writeTransaction {
                keyStore.addSecretKeyRequest(StoredSecretKeyRequest(request, receivers, Clock.System.now()))
            }
            android.util.Log.i(TAG, "restore: re-requested secret ${secret.id} from ${receivers.size} devices")
        }
        true
    }.onFailure {
        android.util.Log.w(TAG, "restore: secret re-request failed: ${it.message}")
    }.getOrDefault(false)

    /**
     * After the device is verified, load every undecrypted event's megolm session
     * from the server-side key backup so the room store can decrypt it. Called on
     * verification success; loading a session decrypts what the session covers.
     * Battery/UX: gated to at most once per day and bounded tighter
     * per room. Before, it ran on EVERY process start (reboot/install/force-stop)
     * and could crawl for 20+ min at several cores, starving the tool's requests
     * ("Loading messages…" / "…" account). The on-demand page path
     * (collectRelevantTimelineEvents → restoreRoomSessions) still restores when a
     * room is actually read, so the daily crawl is only the preemptive pass.
     */
    private suspend fun restoreMegolmSessions() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val c = client ?: return
        if (!isDeviceVerified(c)) {
            // Mid-verification (fresh login): every room would log "skipping
            // restore" and the walk just burns CPU while the SAS waits. The
            // post-Done ladder relaunches this once verification completes.
            android.util.Log.i(TAG, "restore: device not verified — deferring crawl until verification completes")
            return
        }
        val keyBackup = keyBackupOf(c)
        if (keyBackup == null) {
            android.util.Log.e(TAG, "restore: KeyBackupService not available via DI")
            return
        }
        val lastRun = prefs.getLong(KEY_RESTORE_LAST_RUN_MS, 0L)
        val now = System.currentTimeMillis()
        if (now - lastRun < RESTORE_INTERVAL_MS) {
            // The cooldown must not silence the re-request recovery: a restart
            // that dropped the cached backup secret would
            // otherwise stay un-decryptable for a day. Missing secret → run.
            val hasBackupSecret = runCatching {
                c.di.get<KeyStore>(KeyStore::class).getSecrets().containsKey(SecretType.M_MEGOLM_BACKUP_V1)
            }.getOrDefault(true)
            if (hasBackupSecret) {
                android.util.Log.d(TAG, "restore: skipped — last run ${(now - lastRun) / 60_000}m ago")
                return
            }
            android.util.Log.w(TAG, "restore: cooldown active but backup secret missing — re-running to re-request it")
        }
        // Trixnity's version flow emits its current value immediately — null
        // until the service has actually fetched /room_keys/version. A process
        // restart before that fetch reads as "no backup configured" and stuck
        // the account un-decryptable for the crawl's 24h interval. Wait for a real emission;
        // null after the budget means genuinely absent/unreachable.
        var backupVersion = withTimeoutOrNull(KEY_BACKUP_VERSION_BUDGET_MS) {
            runCatching { keyBackup.version.filterNotNull().firstOrNull() }.getOrNull()
        }
        // Process-death recovery: version stays null when the
        // cached backup secret didn't survive a restart (Trixnity receives it
        // exactly once, right after verification, and never re-requests). If
        // the server still has a backup, re-request the missing secrets from
        // our verified own devices and give the answer a moment to land.
        if (backupVersion == null && c.api.key.getRoomKeysVersion().isSuccess) {
            if (reRequestMissingSecrets(c)) {
                backupVersion = withTimeoutOrNull(SECRET_RE_REQUEST_BUDGET_MS) {
                    runCatching { keyBackup.version.filterNotNull().firstOrNull() }.getOrNull()
                }
            }
        }
        android.util.Log.d(TAG, "restore: backup version = $backupVersion")
        if (backupVersion == null) {
            // No server-side backup — the per-room loadMegolmSession would
            // time out (2 s) on every room for nothing. Bail; the on-demand
            // page path still restores the moment a backup appears. Clear the
            // cooldown: a configured account whose service hadn't warmed must
            // get a retry (the cost of a false bail is one version fetch).
            android.util.Log.w(TAG, "restore: no server-side key backup configured — skipping the daily crawl")
            prefs.edit().remove(KEY_RESTORE_LAST_RUN_MS).apply()
            return
        }
        val rooms = withTimeoutOrNull(ROOMS_BUDGET_MS) { c.room.getAll().first() } ?: return
        android.util.Log.d(TAG, "restore: scanning ${rooms.size} rooms")
        _restoreProgress.value = RestoreProgress(scanning = true, roomsTotal = rooms.size)
        var roomsTouched = 0
        var scanned = 0
        try {
            for ((roomId, _) in rooms) {
                scanned++
                _restoreProgress.value = RestoreProgress(scanning = true, scanned = scanned, roomsTotal = rooms.size)
                if (scanned % 200 == 0) {
                    android.util.Log.d(TAG, "restore: $scanned/${rooms.size} rooms scanned, $roomsTouched with encrypted content")
                }
                // The futile-restore cooldown no longer skips the room here:
                // parked rooms are exactly the ones with stuck
                // rows, and restoreRoomSessions' local-first re-decrypt is
                // free (no backup round-trip) — the cooldown gates only the
                // backup part inside it, so parking stays effective.
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
                        decryptRestoreCooldown.park(roomId.full, DECRYPT_RESTORE_COOLDOWN_MS)
                    }
                    roomsTouched++
                }
            }
        } finally {
            _restoreProgress.value = _restoreProgress.value.copy(scanning = false)
        }
        android.util.Log.d(TAG, "restore: done — $roomsTouched rooms with encrypted content")
        // Written on COMPLETION: a crawl killed mid-run (update install,
        // process death) must be able to re-run.
        // The scan is idempotent; re-running costs the
        // skipped-room re-reads only.
        prefs.edit().putLong(KEY_RESTORE_LAST_RUN_MS, System.currentTimeMillis()).apply()
        _restoreProgress.value = RestoreProgress(scanned = scanned, roomsTotal = rooms.size, completed = true)
        // Persist: the in-memory flag dies with the process and
        // the 24h gate keeps the next crawl a no-op, so without this the
        // Account screen's "All messages restored" could only show in the
        // process that ran the crawl. Cleared at login / logout.
        prefs.edit().putBoolean(KEY_RESTORE_COMPLETED, true).apply()
    }

    suspend fun logout() {
        val ctx = appContext ?: return
        initMutex.withLock {
            manualLogout = true
            val old = client
            client = null
            threadBackfillJob?.cancel() // the walk walks the deleted store — stop it
            threadBackfillJob = null
            threadBackfillStoreEmptyAtAttach = null // the next login re-probes
            // The watchers (sync observers + the ThreadRow recheck loop) collect
            // from the old client — cancel them with the session or the recheck
            // keeps spinning against the closed client. The next login
            // re-observes (the attach path cancels + re-registers anyway).
            notificationWatcherJobs.forEach { it.cancel() }
            notificationWatcherJobs.clear()
            slowSyncJob?.cancel()
            slowSyncJob = null
            screenOffJob?.cancel()
            screenOffJob = null
            inProcessSyncJob?.cancel()
            inProcessSyncJob = null
            syncMode = SyncMode.ACTIVE
            inProcessSyncRunning = false
            observedClient = null
            // Verification-first sync: a torn-down session must not leak the
            // slim phase into the next one (the next login re-arms it anyway).
            pendingVerificationPhase = false
            activeClientConfig = null
            verificationSyncSwapped.set(false)
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
                .onFailure {
                    // A skipped/failed API logout leaks the device server-side
                    // (5 stale "Chats (Light Phone)" devices, one per
                    // force-killed/expired session — they poisoned the secret-
                    // request receiver list). Make the leak visible.
                    android.util.Log.w(TAG, "logout: API logout failed — device stays registered: ${it.message}")
                }
            runCatching { old?.closeSuspending() }
            ctx.stopService(android.content.Intent(ctx, ChatSyncService::class.java))
            ctx.deleteDatabase(DB_NAME)
            projectionTableReady = false
            ctx.cacheDir.resolve(MEDIA_DIR).deleteRecursively()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            clearDiskCache()
            sessionExpired = false
            manualLogout = false
            setConnectionState(ChatConnectionState.LoggedOut)
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
            // Clear a stale sync filter BEFORE the client is built — the
            // setup flow then uploads a fresh filter covering the current
            // room-account-data whitelist (one-time, prefs-gated).
            migrateSyncFilterIfNeeded(ctx)
            val restored = runCatching {
                MatrixClient.create(
                    repositoriesModule = RepositoriesModule.room(databaseBuilder(ctx)),
                    mediaStoreModule = MediaStoreModule.okio(mediaDir(ctx)),
                    cryptoDriverModule = CryptoDriverModule.libOlm(),
                    authProviderData = null, // restore from the store (built-in Account→Authentication migration)
                    configuration = clientConfiguration("chats"),
                ).getOrThrow()
            }.onFailure { e ->
                android.util.Log.w(TAG, "ensureClient: session restore failed: $e")
            }.getOrNull()
            return if (restored != null) {
                client = restored
                observeClient(restored)
                // Restore path (process restart / update install) mirrors
                // finishLogin's E2EE wiring: login-only wiring left updated
                // installs with no verification and no key-backup crawl.
                scope.launch { armRestoreVerification(restored) }
                // Show the last-known chats immediately while the resolver's
                // first pass warms the store (disk cache).
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
        val restore = _restoreProgress.value
        // P2 stat: x = COUNT(*) of RoomProjection rows, n = joined rooms (the
        // list cache is the JOIN-filtered room set). One indexed COUNT on the
        // binder thread — the GetRooms handler runBlocks multi-second calls,
        // this is noise. No table / no client → 0 (the screen hides the line).
        val roomsProjected = client?.let { c ->
            runCatching {
                val db = c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class)
                if (!projectionTableReady) 0
                else db.openHelper.writableDatabase
                    .query("SELECT COUNT(*) FROM RoomProjection", arrayOf<String>()).use { cur ->
                        if (cur.moveToFirst()) cur.getInt(0) else 0
                    }
            }.getOrDefault(0)
        } ?: 0
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
            restoreScanning = restore.scanning,
            restoreScanned = restore.scanned,
            restoreRoomsTotal = restore.roomsTotal,
            restoreCompleted = restore.completed,
            roomsProjected = roomsProjected,
            roomsJoined = roomsTotal,
        )
    }

    /** Newest activity first — a pure read of the background-refreshed cache. */
    suspend fun getRooms(): List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room> {
        if (client == null) return emptyList()
        // latency timer (SYNC-PERF-SPEC.md): how long after the revision
        // bump the tool's fetch arrives — includes the poll-tick wait + binder
        // transit (the server knows both endpoints, so this needs no tool-side flag).
        if (debugLogging() && roomListPublishedAt > 0) {
            android.util.Log.d(
                TAG,
                "revision→RPC: fetch ${android.os.SystemClock.elapsedRealtime() - roomListPublishedAt}ms " +
                    "after revision $roomListRevision",
            )
        }
        // The resolver seeds the cache promptly at attach and refreshes it in
        // the background; a binder call never triggers a 1284-room resolution
        // burst. On a cold process the first calls may return empty until the
        // resolver's first pass lands — the tool's refresh retries cover that.
        // The persisted list (see [preloadRoomListFromDisk]) fills the gap so
        // the tool shows the last-known chats immediately after a cold boot.
        if (_roomList.value.isEmpty()) {
            preloadRoomListFromDisk()
        }
        val rooms = _roomList.value
        // Every room is served — the UI path is direct in-process calls, no
        // ~1 MB binder transaction to cap against (INGEST-DERIVED-PLAN.md
        // Phase E); the old recency window + per-network prepend dropped
        // rooms whose row was still derived and is gone. Only the
        // ChatServiceMethods GetRooms RPC (adb dev control) crosses a real
        // binder transaction — cap that path only if an account ever grows
        // past ~1,600 rooms (~1 MB encoded).
        return rooms.sortedWith(
            compareByDescending<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room> { it.pinned == true }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { if (it.pinned == true) it.name else "" }
                .thenByDescending { it.lastTimestampMs }
        )
    }

    /**
     * The full room census for the tool's contacts list + search — every
     * room, trimmed (no previews/unreads; contacts/search rows don't show
     * previews, so the whole account crosses trimmed, chats). */
    suspend fun getAllRooms(): List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room> {
        if (client == null) return emptyList()
        if (_roomList.value.isEmpty()) preloadRoomListFromDisk()
        return _roomList.value.map { it.copy(lastMessage = "", unreadCount = 0, lastEventId = null) }
    }

    /**
     * A page of a room's messages (oldest first) plus whether older messages
     * exist beyond it. [hasMore] is computed from the raw timeline page, not
     * the message-filtered list, so a page full of state events or
     * still-encrypted events doesn't end pagination early. [encrypted] is set
     * without fetching when the room needs decryption the device can't do.
     * [nextBeforeEventId] is the chain position the walk stopped at — the
     * value to pass back as `beforeEventId` to continue further back.
     */
    data class MessagesPage(
        val messages: List<com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message>,
        val hasMore: Boolean,
        val encrypted: Boolean = false,
        val nextBeforeEventId: String? = null,
    )

    /**
     * Event ids this client unsend-redacted, persisted so a redacted ENCRYPTED
     * event can be recognized as a former message in the tombstone branch of
     * [computeMessagesPage]: Trixnity stores a redacted m.room.encrypted event
     * as RedactedEventContent("m.room.encrypted") whatever it was (message,
     * reaction, edit — the original type lived in the Megolm ciphertext the
     * redaction dropped), so the plain eventType check misses it and the
     * "[Message unsent]" row silently vanishes on the next page build.
     * Trusting the bare m.room.encrypted type instead is NOT an option — the
     * bridge's key-rotation redactions (megolm self-heal) and
     * un-react redactions would paint fake tombstones — so only redactions we
     * ourselves issued as message redactions get the marker. Plaintext rooms
     * keep matching on eventType alone (the type survives there).
     */
    private val unsentMessageIds: MutableSet<String> by lazy {
        val set = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        runCatching {
            unsentMessageFile()?.readText()?.lineSequence()?.filter { it.isNotBlank() }?.forEach { set.add(it) }
        }
        set
    }

    /** One event id per line (ids are opaque; no secrets in here). */
    private fun unsentMessageFile(): java.io.File? =
        appContext?.let { java.io.File(it.filesDir, "unsent_messages.txt") }

    /** Records a message redaction so every later page build renders its
     *  tombstone. Capped — a marker only matters while the redacted event sits
     *  in a page window. */
    private fun rememberUnsentMessage(eventId: String) {
        val set = unsentMessageIds
        if (!set.add(eventId)) return
        runCatching {
            unsentMessageFile()?.writeText(set.take(UNSENT_MARKER_MAX).joinToString("\n"))
        }
    }

    /** key → elapsed-realtime timestamp until which an action is suppressed
     *  (park now, re-check later). ConcurrentHashMap-backed like the raw maps
     *  it replaces. */
    private class CooldownMap {
        private val untilByKey = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun park(key: String, durationMs: Long) {
            untilByKey[key] = android.os.SystemClock.elapsedRealtime() + durationMs
        }

        /** True when the cooldown has elapsed (or was never parked). */
        fun allowed(key: String): Boolean =
            android.os.SystemClock.elapsedRealtime() >= (untilByKey[key] ?: 0L)

        /** The parked retry timestamp, 0 when none is parked. */
        fun until(key: String): Long = untilByKey[key] ?: 0L

        fun remove(key: String) {
            untilByKey.remove(key)
        }
    }

    /** Suppresses the futile key-backup restore per room: every retry path
     *  (preview, ghost walk, page build, daily crawl) stops re-attempting
     *  until the park expires. In-band sync decryption is unaffected, so a
     *  real session arriving mid-park still decrypts events. (Battery
     *  audit — the pre-verification history on this account never gets its
     *  sessions back, yet the retry paths re-ran doomed restores every 60-120 s,
     *  ~3 cores continuously.) */
    private val decryptRestoreCooldown = CooldownMap()

    /** Suppresses a failed gap backfill per room (battery: a fill that errored
     *  (network, token) is retried at most once per [GAP_BACKFILL_COOLDOWN_MS],
     *  and only while the room is active). */
    private val gapBackfillCooldown = CooldownMap()

    /** Fills a room's timeline gap from the server (Trixnity's
     *  [de.connect2x.trixnity.client.room.TimelineEventHandler.unsafeFillTimelineGaps]
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
        // Cold Wi-Fi round-trips (headers + TLS + a 30-event page) blow the
        // 8 s cellular budget — the window failed fills with
        // the cause invisible, so the budget is network-aware and the cause
        // (exception vs timeout vs token) is logged now to decide the
        // stale-token fallback.
        val budget =
            if (isOnCellularData()) GAP_BACKFILL_BUDGET_MS else GAP_BACKFILL_BUDGET_WIFI_MS
        val failure: String = withTimeoutOrNull(budget) {
            runCatching {
                // Trixnity registers this binding qualified
                // (bind<TimelineEventHandler>; named<TimelineEventHandlerImpl>)
                // — an unqualified lookup throws "No definition found" and every
                // backfill silently failed forever.
                c.di.get<TimelineEventHandler>(
                    org.koin.core.qualifier.named<TimelineEventHandlerImpl>(),
                )
                    .unsafeFillTimelineGaps(EventId(gapEventId), matrixRoomId, GAP_BACKFILL_LIMIT)
                    .getOrThrow()
            }.fold(
                onSuccess = { return@withTimeoutOrNull "" },
                onFailure = { it.message ?: it::class.simpleName ?: "error" },
            )
        } ?: "timeout after ${budget / 1000}ms"
        if (failure.isNotEmpty()) {
            gapBackfillCooldown.park(matrixRoomId.full, GAP_BACKFILL_COOLDOWN_MS)
            android.util.Log.d(
                TAG,
                "gap backfill failed for $matrixRoomId ($failure) — retrying in ${GAP_BACKFILL_COOLDOWN_MS / 1000}s",
            )
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


    // --- Disk cache (room list) -----------------------------------
    // The room list is persisted as JSON so a cold process shows the
    // last-known chats immediately; the resolver then refreshes names and
    // previews. (The per-room message-page half of this cache is retired —
    // the ThreadRow store serves pages.)

    /** Last disk-write time per cache key. */
    private val diskWriteAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun cacheDir(): java.io.File? =
        appContext?.let { java.io.File(it.filesDir, DISK_CACHE_DIR) }

    private fun roomListCacheFile(): java.io.File? =
        cacheDir()?.let { java.io.File(it, DISK_ROOM_LIST_FILE) }

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
        // Same stale-duplicate filter as publish — a cold start must not flash
        // the hidden community rooms before the first resolver pass.
        val visible = hideStaleCommunityDuplicates(disk)
        visible.forEach { room ->
            // A row whose name IS a bridge ghost's localpart ("whatsapp_lid-…")
            // was persisted before the name resolved — treat it as unresolved
            // so the next pass re-resolves it against the store + provision
            // contacts.
            val nameIsGhostLocalpart = BRIDGE_KEYS.any { room.name.startsWith("${it}_") } &&
                !room.name.contains(' ')
            roomListCache.putIfAbsent(
                room.id,
                RoomListEntry(
                    room = room,
                    nameResolved = room.name != ROOM_NAME_PLACEHOLDER && room.name.isNotBlank() &&
                        room.name != ROOM_NAME_FALLBACK && !nameIsGhostLocalpart,
                    previewResolved = room.lastMessage.isNotBlank() &&
                        !room.lastMessage.startsWith("[Encrypted"),
                ),
            )
        }
        _roomList.value = visible
        android.util.Log.d(TAG, "room list: preloaded ${visible.size} rooms from disk cache")
        // Disk cache v3 (PLAN.md 2026-09-14 P1): rows are projection-backed
        // materialized state — no re-derive pass on preload. (The 09-14
        // receipt-cursor re-derive here fixed stale derived badges; the
        // projection keeps the rows right at the source.)
    }

    /** Shared timeout config for the [MatrixClient.room.getTimelineEvent]
     *  re-reads that nudge a decrypt to land. */
    private val timelineEventConfig: GetTimelineEventConfig.() -> Unit = {
        fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
        decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
    }

    /** Rooms with a cold-store seed job in flight — the quiet re-poll that
     *  lands before the seed completes must not launch a second full compute. */
    private val threadSeedInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Messages of a room, oldest first. [beforeEventId] pages further back;
     * null returns the newest [limit] messages.
     *
     * Warm store (SPEC §3): every page is a plain paged SELECT off the
     * ingest-materialized ThreadRow store — [serveFromStore] builds the RPC
     * rows from the stored columns; no recompute, no TTL cache, no disk page,
     * no patchers. A room whose store is empty falls back to the existing
     * recompute path once and seeds the store behind it ([seedThreadRow],
     * SPEC §7); a failed seed self-heals (rowCount stays 0, the next open
     * retries). Older pages past the seeded window resume through the
     * recompute engine from the deepest stored row's chain link and write the
     * page through, so the next scroll continues locally.
     */
    suspend fun getMessages(
        roomId: String,
        beforeEventId: String?,
        limit: Int,
    ): MessagesPage = withContext(Dispatchers.Default) {
        if (debugLogging()) android.util.Log.d(TAG, "getMessages: room=${roomId.takeLast(12)} before=${beforeEventId?.takeLast(14)} limit=$limit")
        // Callers land here off their own dispatcher (the tool's
        // ThreadViewModel runs on Main) — the cold paths below walk +
        // parse the full event chain on CPU, so the whole body is pinned
        // to Default regardless of the caller (the store reads switch
        // to IO internally; nested withContext is fine).
        val attachedClient = client
        if (attachedClient != null) {
            if (beforeEventId != null) {
                // The store's keyset cursor ("ts|seq"); a plain event id means
                // the previous page came from the legacy fallback below.
                keysetParse(beforeEventId)?.let { keyset ->
                    // Part F top-up (SPEC §6): a short local page with chain
                    // links left pays ONE bounded gap-marker round — written
                    // through the ingest writer — before the serve. First
                    // scroll into unfetched history = one network round;
                    // subsequent scrolls stay local.
                    topUpOlderPage(attachedClient, roomId, keyset, limit)
                    serveFromStore(attachedClient, roomId, beforeEventId, limit)?.let { return@withContext it }
                    // Store exhausted below the keyset: deeper history lives
                    // only in the event chain — continue through the recompute
                    // engine from the deepest stored row's resume link, and
                    // write the page through so FUTURE sessions scroll locally
                    // (this page carries the legacy event-id cursor, so this
                    // session keeps computing).
                    val deepest = ThreadRowStore.deepestRow(attachedClient, roomId)
                    val resume = deepest?.prevEventId ?: deepest?.batchBefore
                    if (resume != null) {
                        val page = computeMessagesPage(roomId, resume, limit)
                        scope.launch {
                            runCatching { seedThreadRow(attachedClient, roomId, page) }
                                .onFailure { android.util.Log.w(TAG, "thread-store write-through failed: ${it.message}") }
                        }
                        return@withContext page
                    }
                    return@withContext MessagesPage(emptyList(), false)
                }
            } else {
                // A room open warms the megolm send path in the background so the
                // FIRST send in the room doesn't pay the member-load/session cost
                // inline (see [warmRoomMegolm]).
                warmRoomMegolm(roomId)
                val storeCold = ThreadRowStore.rowCount(attachedClient, roomId) == 0
                if (!storeCold) {
                    serveFromStore(attachedClient, roomId, null, limit)?.let {
                        return@withContext injectPendingEchoes(roomId, it)
                    }
                }
                if (storeCold) {
                    // Fast first paint (SPEC §7): the full recompute walks the
                    // room's whole event chain — seconds of "Loading messages…"
                    // per first open on the LP3. Serve a small page computed
                    // with the heavy aux work skipped (`fast = true` — no gap
                    // backfill, no stuck-decrypt key-restore), then seed the
                    // store with the FULL page in the background and bump the
                    // revision so the tool's pageChanges push re-serves the
                    // page from the store once it lands. The fast page keeps
                    // the legacy event-id cursor, so this session paginates
                    // through the recompute engine until the store takes over.
                    val fastPage = computeMessagesPage(roomId, null, minOf(limit, FAST_FIRST_PAGE_ROWS), fast = true)
                    if (threadSeedInFlight.add(roomId)) {
                        scope.launch {
                            try {
                                runCatching {
                                    val page = computeMessagesPage(roomId, null, THREAD_SEED_PAGE_ROWS)
                                    seedThreadRow(attachedClient, roomId, page)
                                    bumpMessagePageRevision(roomId)
                                }.onFailure { android.util.Log.w(TAG, "thread-store seed failed: ${it.message}") }
                            } finally {
                                threadSeedInFlight.remove(roomId)
                            }
                        }
                    }
                    return@withContext injectPendingEchoes(roomId, fastPage)
                }
            }
        }
        if (beforeEventId != null) return@withContext computeMessagesPage(roomId, beforeEventId, limit)
        // Fallback newest page (SPEC §7): the recompute engine IS the fallback —
        // reached when the client is down or the store read failed (a cold
        // store computed and seeded above, then returned). The pre-store
        // memory/disk page cascade it replaced is retired (Task 7).
        injectPendingEchoes(roomId, computeMessagesPage(roomId, null, limit))
    }

    /**
     * Appends the optimistic voice-note/text/photo rows to a SERVED page.
     * The send's sync echo can still be in the outbox when the page is
     * read — and re-opening a thread served the pre-echo store page, so a
     * just-sent message appeared missing until the next ingest round. The
     * rows dedup by their "local-…" id; the store's echo row replaces them
     * on the next re-serve after the ingest writer writes it through.
     */
    private suspend fun injectPendingEchoes(roomId: String, page: MessagesPage): MessagesPage {
        val c = client ?: return page
        if (pendingAudioEcho[roomId] == null && pendingTextEcho[roomId] == null && pendingImageEcho[roomId] == null) return page
        val result = page.messages.toMutableList()
        val existing = result.mapTo(HashSet()) { it.id }
        fun addIfMissing(message: com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message) {
            if (existing.add(message.id)) result += message
        }
        // [pendingEchoRow]: an acked send renders with its real event id (sent),
        // a failed one with the FAIL_ marker, a queued one as the optimistic
        // "local-…" row — dedup by id keeps a page that already holds the real
        // row from gaining a duplicate. Every in-flight send in the room is
        // injected (rapid sends must ALL keep their rows — ).
        for (pending in pendingAudioEchoes(roomId)) {
            addIfMissing(
                pendingEchoRow(
                    c, RoomId(roomId), pending.txnId, pending.timestampMs,
                    body = "Voice note", contentType = "audio", durationMs = pending.durationMs,
                    cachedEventId = pending.eventId,
                ),
            )
        }
        for (pending in pendingTextEchoes(roomId)) {
            addIfMissing(
                pendingEchoRow(
                    c, RoomId(roomId), pending.txnId, pending.timestampMs, pending.body,
                    replyToEventId = pending.replyToEventId,
                ),
            )
        }
        for (pending in pendingImageEchoes(roomId)) {
            addIfMissing(
                pendingEchoRow(
                    c, RoomId(roomId), pending.txnId, pending.timestampMs,
                    body = pending.fileName, contentType = "image",
                ),
            )
        }
        return if (result.size == page.messages.size) page
        else MessagesPage(result, page.hasMore, page.encrypted, page.nextBeforeEventId)
    }

    // --- ThreadRow read path (docs/THREAD-STORE-SPEC.md §3/§7) ----------------

    /** A `ts|seq` keyset cursor ([ThreadRowLogic.keysetBefore]), or null when
     *  [raw] is not one — a plain event id means the page came from the legacy
     *  recompute fallback, whose cursor keeps flowing through it. */
    private fun keysetParse(raw: String): Pair<Long, Int>? =
        if (raw.matches(Regex("\\d+\\|\\d+"))) {
            runCatching { ThreadRowLogic.parseKeyset(raw) }.getOrNull()
        } else null

    /**
     * One page built straight off ThreadRow rows (SPEC §3): reactions rebuilt
     * from the stored reaction side rows (gated by each row's cached
     * `reactionSummary` — the column answers "is there anything to render",
     * the side rows carry the reactor names), receipts joined on the page's
     * rows in memory, sender names / reply headers / bridge caps resolved
     * exactly as [messageFrom] resolves them. Returns null when the store has
     * no rows for the request (caller falls back to the recompute engine).
     * [rows] arrive newest-first from the store's queries.
     */
    private suspend fun serveFromStore(
        c: MatrixClient,
        roomId: String,
        beforeEventId: String?,
        limit: Int,
    ): MessagesPage? {
        val matrixRoomId = RoomId(roomId)
        val rows = if (beforeEventId == null) {
            ThreadRowStore.newestPage(c, roomId, limit)
        } else {
            val keyset = keysetParse(beforeEventId) ?: return null
            ThreadRowStore.olderPage(c, roomId, keyset, limit)
        }
        if (rows.isEmpty()) return null
        val oldestFirst = rows.asReversed()
        val senderNames = oldestFirst.mapNotNull { it.sender }.distinct().associateWith { sender ->
            senderNameOf(c, matrixRoomId, UserId(sender))
        }
        // Reaction tags: only rows whose cached summary says they have any —
        // no reaction rows in the store, no side-row query at all.
        val reactionTags = oldestFirst
            .filter { !it.reactionSummary.isNullOrEmpty() && it.reactionSummary != "{}" }
            .map { it.eventId }
            .takeIf { it.isNotEmpty() }
            ?.let { reactionTagsForStoreRows(c, matrixRoomId, it) }
            ?: emptyMap()
        // Edits: the newest edit side row per target — it marks the row edited
        // and carries the edited formattedHtml (mediaMeta), the read-path
        // parity (today's page rewrites formattedHtml from the edit).
        val editByTarget = HashMap<String, ThreadRowValues>()
        for (edit in ThreadRowStore.rowsForTargets(
            c, roomId, RowKind.EDIT.wire, oldestFirst.map { it.eventId }, excludeRedacted = false,
        )) {
            edit.targetEventId?.let { editByTarget[it] = edit } // ingestSeq order — newest wins
        }
        // Read receipts describe the newest events (the RPC contract: older
        // pages always report false) — one receipts read, joined in memory.
        val readEventIds = if (beforeEventId == null) {
            receiptReadEventIds(c, matrixRoomId, rows.map { it.eventId })
        } else emptySet()
        var features: RoomFeatures? = null
        val messages = ArrayList<com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message>(oldestFirst.size)
        for (row in oldestFirst) {
            val sender = row.sender ?: continue
            val senderName = senderNames[sender] ?: sender
            val isMine = sender == c.userId.full
            // A redacted message renders as the quiet tombstone, preserved in
            // its conversation slot — no reactions/status/read apply
            // (mirrors [redactedRow]).
            if (row.contentType == ThreadRowLogic.CONTENT_REDACTED) {
                messages += com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
                    id = row.eventId,
                    sender = sender,
                    senderName = senderName,
                    body = "[Message unsent]",
                    timestampMs = row.timestampMs,
                    isMine = isMine,
                    contentType = "redacted",
                )
                continue
            }
            val contentType = row.contentType ?: "text"
            val edit = editByTarget[row.eventId]
            val (durationMs, caption, forwarded) = mediaMetaOf(row.mediaMeta)
            // Undecrypted placeholder rows render as the same calm
            // ENCRYPTED_PLACEHOLDER_BODY the walk's preview serves (SPEC §4);
            // the recheck fills them in place.
            val body = if (row.encrypted == 1) ThreadRowLogic.ENCRYPTED_PLACEHOLDER_BODY else row.body.orEmpty()
            val reply = row.replyToId?.let { resolveReplyHeader(c, matrixRoomId, it) }
            var canEdit = true
            var canUnsend = true
            if (isMine) {
                // Bridge caps: fetched once on the first own row (same lazy
                // rule as [computeMessagesPage]).
                val f = features ?: roomFeatures(c, matrixRoomId).also { features = it }
                val ageMs = System.currentTimeMillis() - row.timestampMs
                canEdit = f.editSupported && contentType == "text" &&
                    (f.editMaxAgeMs == null || ageMs < f.editMaxAgeMs)
                canUnsend = f.deleteSupported &&
                    (f.deleteMaxAgeMs == null || ageMs < f.deleteMaxAgeMs)
            }
            messages += com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
                id = row.eventId,
                sender = sender,
                senderName = senderName,
                body = body,
                timestampMs = row.timestampMs,
                isMine = isMine,
                sendStatus = row.sendStatus?.takeIf { isMine },
                contentType = contentType,
                read = row.eventId in readEventIds,
                reactions = reactionTags[row.eventId].orEmpty(),
                durationMs = durationMs,
                caption = caption,
                forwarded = forwarded,
                edited = edit != null && contentType == "text",
                formattedHtml = when {
                    contentType != "text" -> null
                    edit != null -> edit.mediaMeta
                    else -> row.formattedHtml
                },
                replyToId = row.replyToId,
                replyToSender = reply?.first,
                replyToExcerpt = reply?.second,
                canEdit = canEdit,
                canUnsend = canUnsend,
            )
        }
        val oldest = oldestFirst.first()
        val allPlaceholders = oldestFirst.all { it.encrypted == 1 }
        // hasMore: mid-store pages ALWAYS have more below (the store's true
        // deepest row is deeper than this page's oldest) — only at the store's
        // bottom do the deepest row's own chain links decide. Row-level links
        // are unreliable mid-store: seeded/top-up-written rows carry null
        // prevEventId (LP3 2026-09-19: a 23-row store reported hasMore=false
        // with 549 chain events behind it — scroll-up dead-ended).
        val storeDeepest = ThreadRowStore.deepestRow(c, roomId)
        val hasMore = if (storeDeepest != null && storeDeepest.eventId != oldest.eventId) {
            true
        } else {
            ThreadRowStore.hasMoreFrom(c, roomId, oldest.eventId)
        }
        if (debugLogging()) android.util.Log.d(
            TAG,
            "store serve: room=${roomId.takeLast(12)} rows=${messages.size} hasMore=$hasMore oldest=${oldest.timestampMs}",
        )
        return MessagesPage(
            messages = messages,
            hasMore = hasMore,
            // An encrypted room whose whole page is still-undecrypted
            // placeholders reads as the decryption notice, not "no messages".
            encrypted = if (beforeEventId == null && allPlaceholders) {
                withTimeoutOrNull(ROOM_BUDGET_MS) {
                    c.room.getById(matrixRoomId).firstOrNull()?.encrypted
                } == true
            } else false,
            nextBeforeEventId = ThreadRowLogic.keysetBefore(oldest.timestampMs, oldest.ingestSeq),
        )
    }

    /**
     * Part F gap top-up (SPEC §6): the older store page came back short while
     * [ThreadRowStore.hasMoreFrom] still says more — pay ONE bounded round.
     * The chain walk from the deepest served row (previous-event links) spans
     * the local history the store hasn't written yet and, at its end, any gap
     * marker blocking deeper history; a gap marker triggers the recompute
     * path's gap backfill ([backfillTimelineGap], incl. its 300 s failure
     * cooldown) exactly as it does there. The walked window maps through the
     * ingest writer ([threadRowsFromRound] — [ThreadRowLogic.buildRows] plus
     * rendered overrides, [ThreadRowStore.writeRows]' re-delivery skip)
     * verbatim, then the caller re-serves from the store. A round that adds
     * nothing (fill no-op, walk already written, dead-end store) parks the
     * cooldown so retries rate-limit instead of re-polling the network on
     * every scroll — the page serves short with hasMore still true.
     */
    private suspend fun topUpOlderPage(
        c: MatrixClient,
        roomId: String,
        keyset: Pair<Long, Int>,
        limit: Int,
    ) {
        val rows = ThreadRowStore.olderPage(c, roomId, keyset, limit)
        if (rows.isEmpty() || rows.size >= limit) return
        val deepest = rows.last() // newest-first — the deepest served row
        if (!ThreadRowStore.hasMoreFrom(c, roomId, deepest.eventId)) return
        if (!gapBackfillCooldown.allowed(roomId)) return
        val matrixRoomId = RoomId(roomId)
        var walk = readTimelineChainFromDb(
            c, matrixRoomId, deepest.eventId, THREAD_TOPUP_WALK_MAX,
        ) ?: return // chain walk unavailable — the legacy fallback below keeps working
        val gapEvent = walk.first.firstOrNull { it.gap?.batchBefore != null }
        if (gapEvent != null) {
            walk = backfillTimelineGap(
                c, matrixRoomId, deepest.eventId, GAP_BACKFILL_LIMIT.toInt(),
                gapEvent.event.id.full,
            ) ?: return // failed — [backfillTimelineGap] parked the cooldown
        }
        // The walk arrives newest→oldest; the ingest writer's seq convention
        // is oldest-first input order — reversing keeps equal-timestamp ties
        // ordered like a sync round instead of inverting them.
        val newRows = threadRowsFromRound(
            c, matrixRoomId, walk.first.asReversed().map { it.event.id.full },
        )
        val (added, _) = writeThreadRowsThrough(c, roomId, newRows)
        if (added <= 0) {
            gapBackfillCooldown.park(roomId, GAP_BACKFILL_COOLDOWN_MS)
        } else {
            bumpMessagePageRevision(roomId)
            if (debugLogging()) {
                android.util.Log.d(
                    TAG,
                    "thread-store: gap top-up ${roomId.takeLast(12)} wrote $added row(s)",
                )
            }
        }
    }

    /**
     * One ThreadRow batch write, ingest-writer rules (Task 3) — the single
     * write-through seam for BOTH the live sync ingest and the Part F top-up's
     * backfilled window (sync never re-delivers old events, so the top-up is
     * the only path a backfilled real reaction ever travels, and its targets'
     * `seed:`-pseudo reaction rows must retire here exactly as they do when
     * the reaction arrives live). The batch's real reaction rows retire their
     * targets' seeded pseudo rows first ([ThreadRowLogic.seedReactionRetireTargets]
     * → [ThreadRowStore.retireSeedReactions]); the write then lands the batch
     * and re-folds. Returns the rows actually inserted (any kind — the
     * top-up's zero-added guard) paired with whether the retirement removed
     * any rows — the caller's "did this batch change the store" signal.
     */
    private suspend fun writeThreadRowsThrough(
        c: MatrixClient,
        roomId: String,
        rows: List<ThreadRowValues>,
    ): Pair<Int, Boolean> {
        val seedTargets = ThreadRowLogic.seedReactionRetireTargets(rows)
        val retired = if (seedTargets.isEmpty()) 0 else {
            ThreadRowStore.retireSeedReactions(c, roomId, seedTargets)
        }
        return ThreadRowStore.writeRows(c, rows) to (retired > 0)
    }

    // --- Part H: fresh-login ThreadRow backfill (docs/THREAD-STORE-SPEC.md §8) ---

    /**
     * One backfill round for [roomId], driven by the pure [ThreadBackfill]
     * state machine: the Part F top-up walk ([topUpOlderPage]) unrolled into
     * a worker round — chain walk from the deepest store row, ONE gap
     * `/messages` window ([backfillTimelineGap], token semantics included)
     * when the walk ends on a gap marker, then the window through the Task 5
     * write seam ([writeThreadRowsThrough]). Chain end — the room's creation
     * — is the deepest row carrying no gap token after the round. Returns
     * null when the round could not advance (walk unavailable, fill failed,
     * nothing new written and the deepest row unchanged): the caller keeps
     * the bookmark and the room resumes on a later pass (SPEC §8 resumable).
     * A room with no rows at all is done for this pass — the ingest writer
     * covers its new events, Part F its scroll-ups.
     */
    private suspend fun threadBackfillStep(
        c: MatrixClient,
        roomId: String,
    ): ThreadBackfill.RoomStep? {
        val matrixRoomId = RoomId(roomId)
        val deepest = ThreadRowStore.deepestRow(c, roomId)
            ?: return ThreadBackfill.RoomStep(0, null)
        if (deepest.batchBefore == null) return ThreadBackfill.RoomStep(0, null)
        var walk = readTimelineChainFromDb(
            c, matrixRoomId, deepest.eventId, GAP_BACKFILL_LIMIT.toInt(),
        ) ?: return null
        val gapEvent = walk.first.firstOrNull { it.gap?.batchBefore != null }
        if (gapEvent != null) {
            walk = backfillTimelineGap(
                c, matrixRoomId, deepest.eventId, GAP_BACKFILL_LIMIT.toInt(),
                gapEvent.event.id.full,
            ) ?: return null
        }
        // The walk arrives newest→oldest; the ingest writer's seq convention
        // is oldest-first input order (the same reversal topUpOlderPage does).
        val newRows = threadRowsFromRound(
            c, matrixRoomId, walk.first.asReversed().map { it.event.id.full },
        )
        val (added, _) = writeThreadRowsThrough(c, roomId, newRows)
        if (added <= 0) return null
        bumpMessagePageRevision(roomId)
        val token = ThreadRowStore.deepestRow(c, roomId)?.batchBefore
        return ThreadBackfill.RoomStep(added, token)
    }

    /** [ThreadBackfill.Deps] against this client's real APIs. */
    private fun threadBackfillDeps(c: MatrixClient): ThreadBackfill.Deps =
        object : ThreadBackfill.Deps {
            override suspend fun roomIds(): List<String>? {
                val rooms = runCatching {
                    withTimeoutOrNull(ROOMS_BUDGET_MS) { c.room.getAll().first() }
                }.getOrNull() ?: return null
                // The same JOIN-filtered walk backfillProjection uses — the
                // room-list order as-is, no new ordering code (SPEC §8).
                return rooms.keys.mapNotNull { id ->
                    withTimeoutOrNull(ROOM_LIST_ROOM_BUDGET_MS) {
                        rooms[id]?.filterNotNull()?.firstOrNull()
                    }?.takeIf { it.membership == Membership.JOIN }?.let { id.full }
                }
            }

            override suspend fun storeEmpty(): Boolean =
                threadBackfillStoreEmptyAtAttach ?: false

            override suspend fun hasBackfillBookmarks(): Boolean =
                runCatching { ThreadRowStore.hasBackfillBookmarks(c) }.getOrDefault(false)

            override suspend fun backfillBookmark(roomId: String): ThreadRowStore.BackfillBookmark? =
                runCatching { ThreadRowStore.backfillBookmark(c, roomId) }.getOrNull()

            override suspend fun markBackfillCursor(roomId: String, write: ThreadBackfill.CursorWrite) {
                when (write) {
                    is ThreadBackfill.CursorWrite.Token ->
                        ThreadRowStore.markBackfillCursor(c, roomId, write.batchBefore, write.fetchedCount)
                    is ThreadBackfill.CursorWrite.Capped ->
                        ThreadRowStore.markBackfillCapped(c, roomId, write.fetchedCount)
                    ThreadBackfill.CursorWrite.Clear ->
                        ThreadRowStore.markBackfillCursor(c, roomId, null, 0)
                }
            }

            override suspend fun stepRoom(roomId: String): ThreadBackfill.RoomStep? =
                threadBackfillStep(c, roomId)

            override fun log(message: String) {
                if (debugLogging()) android.util.Log.d(TAG, message)
            }
        }

    /**
     * The fresh-login probe (SPEC §8 trigger), captured at client attach —
     * BEFORE the first sync round can write rows, which is the only moment
     * an empty store still means "fresh login". Null = probe not landed yet
     * (the worker treats that as not-fresh, the conservative side).
     */
    @Volatile
    private var threadBackfillStoreEmptyAtAttach: Boolean? = null

    private var threadBackfillJob: Job? = null

    /** Launch the Part H backfill pass, once per client. Called when the
     *  projection backfill completes (fresh login — initial sync done, room
     *  list known, the same sequencing it retries for) or short-circuits on
     *  the already-backfilled flag (restore/restart — the resume path). */
    private fun startThreadBackfill(c: MatrixClient) {
        if (threadBackfillJob?.isActive == true) return
        threadBackfillJob = ThreadBackfill.start(scope, threadBackfillDeps(c))
    }

    /** Reaction tags for store-served rows, rebuilt from the target's reaction
     *  side rows (the truth; the summary column is their cache) with the same
     *  label shape the timeline walk produces: one tag per reactor, a person's
     *  latest reaction wins, "You" for own, collapsed at two lines. Seeded
     *  rooms carry pseudo reaction rows whose `sender` is the tag's display
     *  label ("You" / a name) — used verbatim as the label. Dedup keys on the
     *  LABEL, so a post-seed real reaction row for the same reactor replaces
     *  the seeded pseudo row instead of duplicating it. */
    private suspend fun reactionTagsForStoreRows(
        c: MatrixClient,
        matrixRoomId: RoomId,
        targetIds: List<String>,
    ): Map<String, List<String>> {
        val rows = ThreadRowStore.rowsForTargets(
            c, matrixRoomId.full, RowKind.REACTION.wire, targetIds, excludeRedacted = true,
        )
        val seen = HashMap<String, ReactionEntry>() // "target|label" → entry (latest kept)
        val result = HashMap<String, MutableList<ReactionEntry>>()
        for (row in rows) { // ingestSeq order
            val target = row.targetEventId ?: continue
            val key = row.payload?.takeIf { it.isNotBlank() } ?: continue
            val sender = row.sender ?: continue
            val who = when {
                sender == c.userId.full -> "You"
                sender.startsWith("@") -> senderNameOf(c, matrixRoomId, UserId(sender))
                else -> sender
            }
            val dedupeKey = "$target|$who"
            val existing = seen[dedupeKey]
            if (existing != null) {
                if (row.timestampMs >= existing.timestampMs) {
                    existing.timestampMs = row.timestampMs
                    existing.key = key
                }
                continue
            }
            val entry = ReactionEntry(row.timestampMs, who, key)
            seen[dedupeKey] = entry
            result.getOrPut(target) { mutableListOf() }.add(entry)
        }
        return result.mapValues { (_, entries) ->
            collapseReactionTags(entries.sortedBy { it.timestampMs }.map { "${it.who} reacted ${it.key}" })
        }
    }

    /** Which of [newestFirstEventIds] the other room members have read — the
     *  receipts read of [readReceiptsByEvent] keyed on a page's event ids
     *  instead of walked TimelineEvents: a receipt pointing at a page row
     *  covers that row and every older one. */
    private suspend fun receiptReadEventIds(
        c: MatrixClient,
        matrixRoomId: RoomId,
        newestFirstEventIds: List<String>,
    ): Set<String> {
        val rawIndex = HashMap<String, Int>()
        newestFirstEventIds.forEachIndexed { i, id -> rawIndex[id] = i }
        val (receiptsByUser, bridgebot) = withTimeoutOrNull(MESSAGES_BUDGET_MS) {
            // The Room-backed repositories only work inside a store transaction
            // (the flow APIs set it up themselves; direct repo reads need the
            // explicit scope, or Room answers "read transaction is missing").
            val txManager = c.di.get<StoreTransactionManager>(StoreTransactionManager::class)
            txManager.readTransaction {
                val receipts = c.di.get<RoomUserReceiptsRepository>(RoomUserReceiptsRepository::class)
                    .get(matrixRoomId)
                // Bridge bots post m.read receipts as room bookkeeping, not
                // human reads (see [bridgeBotOf]).
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

    /** Reply header for a store-served row, resolved at read time from the
     *  timeline store (the ThreadRow schema carries the target id only) —
     *  the same bounded lookup + excerpt rules [messageFrom] applies. */
    private suspend fun resolveReplyHeader(
        c: MatrixClient,
        matrixRoomId: RoomId,
        replyTargetId: String,
    ): Pair<String?, String?> {
        val target = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getTimelineEvent(matrixRoomId, EventId(replyTargetId)).firstOrNull()
        } ?: return null to null
        return senderNameOf(c, matrixRoomId, target.event.sender) to
            replyExcerptFor(target.content?.getOrNull() as? RoomMessageEventContent)
    }

    /** Inverse of [mediaMetaJsonOf]: the served media fields. */
    private fun mediaMetaOf(json: String?): Triple<Long?, String?, Boolean> {
        val obj = json?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        } ?: return Triple(null, null, false)
        return Triple(
            (obj["durationMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            (obj["caption"] as? JsonPrimitive)?.contentOrNull,
            (obj["forwarded"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    /**
     * Seed (SPEC §7): map a computed page's rendered rows back into ThreadRow
     * values and write them. The page was rendered by the recompute engine —
     * bodies are exactly what today's read path serves (broadcast own-name
     * stripping included), so the store keeps PRE-STRIP bodies and the serving
     * path serves them raw. Reaction tags / edit state ride along as pseudo
     * side rows ("seed:…" ids, label senders) so the served tags and the
     * `edited` flag survive the seed; real side rows from later sync rounds
     * layer on top (the label-keyed tag dedup absorbs the overlap). The
     * deepest row carries the page's chain cursor as its `batchBefore` resume
     * link so [hasMoreFrom] keeps scroll-up alive past the seeded window.
     * Optimistic rows (local-… ids, not-yet-echoed sends) are skipped — their
     * real events arrive through the ingest writer.
     */
    private suspend fun seedThreadRow(c: MatrixClient, roomId: String, page: MessagesPage) {
        val msgs = page.messages.filter {
            !it.id.startsWith(LOCAL_PENDING_ID_PREFIX) &&
                it.sendStatus != "SENT_PENDING_ECHO" && it.sendStatus != "FAIL_LOCAL_SEND"
        }
        if (msgs.isEmpty()) return
        val rows = ArrayList<ThreadRowValues>(msgs.size * 2)
        for (msg in msgs) rows += threadRowValuesFromMessage(roomId, msg)
        if (page.hasMore && page.nextBeforeEventId != null) {
            // msgs is oldest-first (the page's ts-stable sort), so the first
            // written row is the store's deepest.
            val deepest = rows.first { it.kind == RowKind.MESSAGE.wire }
            val index = rows.indexOf(deepest)
            rows[index] = deepest.copy(batchBefore = page.nextBeforeEventId)
        }
        ThreadRowStore.writeRows(c, rows)
        if (debugLogging()) {
            android.util.Log.d(
                TAG,
                "thread-store: seeded ${roomId.takeLast(12)} with ${rows.count { it.kind == RowKind.MESSAGE.wire }} row(s)",
            )
        }
    }

    /** One computed row → its ThreadRow message row + pseudo side rows (the
     *  seed mapping — the same rendered-field derivation the ingest writer's
     *  override stage applies). A row whose served body is the stuck-decrypt
     *  placeholder is stored as an `encrypted=1` placeholder (body null) so
     *  the recheck fills it when the key lands — the ingest hook never replays
     *  pre-update events, so the seed is existing installs' only entry into
     *  the store. */
    private fun threadRowValuesFromMessage(
        roomId: String,
        msg: com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message,
    ): List<ThreadRowValues> {
        val placeholder = ThreadRowLogic.isStuckDecryptBody(msg.body)
        val message = ThreadRowValues(
            roomId = roomId,
            eventId = msg.id,
            kind = RowKind.MESSAGE.wire,
            sender = msg.sender,
            timestampMs = msg.timestampMs,
            ingestSeq = 0, // rebased inside writeRows' transaction
            body = if (placeholder) null else msg.body,
            formattedHtml = if (placeholder) null else msg.formattedHtml,
            contentType = msg.contentType,
            replyToId = msg.replyToId,
            mediaMeta = mediaMetaJsonOf(msg),
            sendStatus = msg.sendStatus,
            encrypted = if (placeholder) 1 else 0,
            prevEventId = null,
            batchBefore = null,
            targetEventId = null,
            payload = null,
            reactionSummary = null,
        )
        val sides = ArrayList<ThreadRowValues>(msg.reactions.size + 1)
        msg.reactions.forEachIndexed { i, tag ->
            val at = tag.indexOf(" reacted ")
            if (at <= 0) return@forEachIndexed
            val who = tag.substring(0, at)
            val keys = tag.substring(at + " reacted ".length).takeIf { it.isNotBlank() } ?: return@forEachIndexed
            sides += ThreadRowValues(
                roomId = roomId,
                eventId = "${ThreadRowLogic.SEED_ROW_PREFIX}${msg.id}:r$i",
                kind = RowKind.REACTION.wire,
                sender = who,
                timestampMs = msg.timestampMs,
                ingestSeq = 0,
                body = null, formattedHtml = null, contentType = null, replyToId = null,
                mediaMeta = null, sendStatus = null, encrypted = 0,
                prevEventId = null, batchBefore = null,
                targetEventId = msg.id,
                payload = keys,
                reactionSummary = null,
            )
        }
        if (msg.edited) {
            sides += ThreadRowValues(
                roomId = roomId,
                eventId = "${ThreadRowLogic.SEED_ROW_PREFIX}${msg.id}:edit",
                kind = RowKind.EDIT.wire,
                sender = null,
                timestampMs = msg.timestampMs,
                ingestSeq = 0,
                body = null, formattedHtml = null, contentType = null, replyToId = null,
                mediaMeta = msg.formattedHtml, // the edited row's served HTML
                sendStatus = null, encrypted = 0,
                prevEventId = null, batchBefore = null,
                targetEventId = msg.id,
                payload = msg.body, // already the edited (stripped) body
                reactionSummary = null,
            )
        }
        return sides + message
    }

    // --- Bridge re-import ("ghost") detection ------------------
    // Beeper's WhatsApp bridge occasionally re-imports room history as NEW
    // events (fresh event ids + timestamps, one megolm session) — surfacing
    // old media in threads and bumping rooms to the top of the chat list.
    // The re-import COPIES existing messages, so the reliable discriminator is
    // CONTENT: an event whose decrypted content (sender + type + body/url)
    // matches an OLDER event in the room is a copy, and real new messages
    // never duplicate older content. (Transaction ids don't discriminate:
    // real incoming messages carry them too.)
    // A density fallback catches floods whose content can't be read yet
    // (still-encrypted): an event in a >=30-per-minute txn flood — a real
    // conversation almost never reaches that rate.

    /** A raw event's transaction id, or null when it has none. */
    private fun txnIdOf(te: TimelineEvent): String? = te.event.unsigned?.transactionId

    /**
     * Content signature for dedup: sender + message kind + body/url + file
     * name. Null when the content isn't readable yet (still-encrypted) — such
     * events can't be deduped and fall through to the flood rule. Also null
     * for m.replace edit events: they never render as rows, so they're not
     * re-import-copy candidates — and their media url lives in
     * m.new_content, leaving the top-level url/fileName null, which made
     * every same-sender media edit collapse into one signature and dedupe
     * kept only the newest (gmessages: older photo edits dropped,
     * their "Waiting for attachment …" notices never became photos).
     */
    private fun contentSignature(c: MatrixClient, te: TimelineEvent): String? {
        val content = te.content?.getOrNull() ?: return null
        if ((content as? RoomMessageEventContent)?.relatesTo is RelatesTo.Replace) return null
        val sender = te.event.sender.full
        // origin_server_ts is part of the signature: the dedup exists for the
        // bridge's EXACT re-imports (same ts), but a user legitimately
        // repeating a message ("ok", "ok") minted the same sender|text|body
        // signature minutes apart and the older copy vanished from the served
        // page (found on-emulator 2026-09-08).
        val ts = te.event.originTimestamp
        return when (content) {
            is RoomMessageEventContent.TextBased -> "$sender|text|$ts|${content.body}"
            is RoomMessageEventContent.FileBased.Image ->
                "$sender|image|$ts|${content.url ?: content.file?.url}|${content.fileName ?: ""}"
            is RoomMessageEventContent.FileBased.Audio ->
                "$sender|audio|$ts|${content.url ?: content.file?.url}|${content.fileName ?: ""}"
            is RoomMessageEventContent.FileBased.Video ->
                "$sender|video|$ts|${content.url ?: content.file?.url}|${content.fileName ?: ""}"
            is RoomMessageEventContent.FileBased.File ->
                "$sender|file|$ts|${content.url ?: content.file?.url}|${content.fileName ?: ""}"
            else -> null
        }
    }

    /** Density fallback: a txn-id event inside a >=30-per-window flood. The
     *  count is decided by [ProjectionPredicate.floodGhost] — the caller
     *  (walk or live event) supplies the window's events. */
    private fun isFloodGhost(c: MatrixClient, te: TimelineEvent, context: List<TimelineEvent>): Boolean {
        if (txnIdOf(te) == null) return false
        val ts = te.event.originTimestamp
        return ProjectionPredicate.floodGhost(
            context.count { other ->
                other.event.id != te.event.id && txnIdOf(other) != null &&
                    kotlin.math.abs(other.event.originTimestamp - ts) < GHOST_BURST_WINDOW_MS
            },
        )
    }

    /**
     * Room's recent events for the bridge-flood density check. Cached per room
     * for [FLOOD_CONTEXT_TTL_MS] — the verdict (txn-id density within
     * [GHOST_BURST_WINDOW_MS]) can't change within seconds, and this read is a
     * full 250-event walk with network/decrypt timeouts, so running it per
     * event made a message burst cost N× the walk.
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
        // Gap-marker backfill (PLAN §8): a `limited=true` sync
        // stores a gap marker whose missing window the store never fills —
        // events created during the missed window are silently absent (seen
        // live on the LP3: a WhatsApp message existed in Beeper's clients but
        // never in Chats). When the walked chain carries a gap marker, fill it
        // from the server (bounded window, active room only, cooldown on
        // failure) and re-walk. Skipped on the fast first-page path — the
        // background refresh fills it seconds later, keeping the first render
        // instant. Only a gap BEFORE an event is filled (a GapAfter on the
        // walk's head means newer events the next sync picks up naturally, and
        // Trixnity's fill no-ops it anyway) — that gap is what blocks loading
        // older messages.
        // Active room only (throttle — the fill is a network + store + decrypt
        // cost) and a previous failed fill's cooldown must have elapsed.
        if (!fast && activeRoomId == matrixRoomId.full && gapBackfillCooldown.allowed(matrixRoomId.full)) {
            val gapEvent = events.firstOrNull { it.gap?.batchBefore != null }
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
        // fast first-page path: the background
        // full-page refresh resolves them; the fast page may briefly show
        // "[Encrypted message]" placeholders instead of a long loading state.
        if (!fast) {
            val undecrypted = events.filter {
                it.content?.isFailure == true ||
                    // Decrypt never ATTEMPTED (content unresolved) counts too —
                    // the LP3's stuck rows are exactly this class, and the old
                    // failure-only filter kept them away from the key-backup
                    // restore forever. Age-gated like the placeholder:
                    // young pending events decrypt in-band; only stuck ones
                    // justify the backup round-trip.
                    (it.content == null &&
                        it.event.content is EncryptedMessageEventContent &&
                        System.currentTimeMillis() - it.event.originTimestamp >
                            DECRYPT_PENDING_PLACEHOLDER_AFTER_MS)
            }
            if (undecrypted.isNotEmpty()) {
                // Battery: events that can't decrypt (e.g.
                // pre-verification history — the bridge never re-shares those
                // sessions) made every page build / 2s refresh repeat a doomed
                // key-backup restore + per-event API re-read. When a restore finds
                // nothing to load, back off for a cooldown — the normal sync path
                // decrypts in-band the moment real sessions do arrive.
                val roomKey = matrixRoomId.full
                if (decryptRestoreCooldown.allowed(roomKey)) {
                    val loaded = restoreRoomSessions(c, matrixRoomId, undecrypted)
                    if (loaded == 0) decryptRestoreCooldown.park(roomKey, DECRYPT_RESTORE_COOLDOWN_MS)
                    val resolved = HashMap<String, TimelineEvent>()
                    undecrypted.forEach { te ->
                        withTimeoutOrNull(DECRYPT_WAIT_MS) {
                            c.room.getTimelineEvent(matrixRoomId, te.event.id, timelineEventConfig).firstOrNull()
                                ?.takeIf { it.content?.getOrNull() != null }
                        }?.let { resolved[te.event.id.full] = it }
                    }
                    if (resolved.isNotEmpty()) {
                        events = events.map { resolved[it.event.id.full] ?: it }
                    }
                }
            }
        }
        // Bridge re-import dedup: the old
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
    private fun dedupeChain(c: MatrixClient, raw: List<TimelineEvent>): List<TimelineEvent> =
        raw.distinctBy { contentSignature(c, it) ?: it.event.id.full }

    /** Bounds concurrent [readTimelineChainFromDb] walks — see the comment there. */
    private val chainDbSemaphore = Semaphore(permits = 2)

    /** A permit wait this long is worth a debug line (issue #33 probe). */
    private const val CHAIN_WAIT_LOG_MS = 100L

    /**
     * [chainDbSemaphore] body with a debugLog-gated acquisition-wait timer —
     * same permits, same critical section, instrumentation only. [label] names
     * the caller, because the first LP3 capture (2026-09-13) showed the waits
     * but not which operation queued: 232 in one 2 s window at launch, and a
     * ~500 ms wait after every send.
     */
    private suspend fun <T> chainDb(label: String, body: suspend () -> T): T {
        val queuedAt = android.os.SystemClock.elapsedRealtime()
        chainDbSemaphore.acquire()
        if (debugLogging()) {
            val waited = android.os.SystemClock.elapsedRealtime() - queuedAt
            if (waited >= CHAIN_WAIT_LOG_MS) {
                android.util.Log.d(TAG, "chainDb[$label]: waited ${waited}ms for a permit")
            }
        }
        try {
            return body()
        } finally {
            chainDbSemaphore.release()
        }
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
        // The recursive CTE streams potentially hundreds of rows per call, and
        // the crawl + thread precompute + ghost walks + tool RPCs all fire it
        // concurrently — at 4 simultaneous walks the SQLite pool (4 connections)
        // was fully occupied and SENDS waited 30+s for a connection. Bound the concurrency;
        // the walks are CPU/IO-cheap enough that 2 run near-linearly anyway.
        return chainDb("readTimelineChainFromDb") {
            withContext(Dispatchers.IO) {
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
                        // The deepest event's prev link decides hasMore — plus
                        // its gap marker: a sync-boundary tail has no prev link
                        // but carries GapBefore ("more history on the server"),
                        // and reporting false there latched the thread's paging
                        // off until the room was reopened (LP3 feedback
                        // 2026-09-12).
                        val deepest = events.last()
                        hasMore = deepest.previousEventId != null ||
                            deepest.gap?.batchBefore != null
                    }
                    android.util.Log.d(TAG, "readTimelineChainFromDb: $matrixRoomId from=$startEventId → ${events.size} events hasMore=$hasMore")
                    events to hasMore
                }.onFailure { e ->
                    android.util.Log.w(TAG, "readTimelineChainFromDb: store query failed — falling back to the API walk", e)
                }.getOrNull()
            }
        }
    }

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
        return withTimeoutOrNull(DECRYPT_WAIT_MS) {
            c.room.getTimelineEvent(matrixRoomId, echo.event.id, timelineEventConfig)
                .filterNotNull().firstOrNull { it.content?.getOrNull() != null }
        }
    }

    /** Trixnity's KeyBackupService via the client's DI; null when the key
     *  backup module isn't registered (e.g. an account without one). */
    private fun keyBackupOf(c: MatrixClient): de.connect2x.trixnity.client.key.KeyBackupService? =
        runCatching {
            c.di.get<de.connect2x.trixnity.client.key.KeyBackupService>(
                org.koin.core.qualifier.named<de.connect2x.trixnity.client.key.KeyBackupService>(),
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
     *  sync echo), the row carries that id + the send time and the
     *  SENT_PENDING_ECHO status (rendered "sent" by the tool, Beeper's
     *  SENT_PENDING_SERVER_ECHO); a recorded outbox
     *  error renders as "not delivered" (FAIL_ status, shown by the tool);
     *  only a still-queued send keeps the optimistic "local-…" row. The tool
     *  shows the send time for all three, so the thread reflects a send
     *  immediately, until proven sent or not delivered.
     */
    private suspend fun pendingEchoRow(
        c: MatrixClient,
        matrixRoomId: RoomId,
        txnId: String,
        timestampMs: Long,
        body: String,
        contentType: String = "text",
        durationMs: Long? = null,
        /** Real event id cached on the pending at ack time — the outbox row
         *  (the other source of the id) is removed once the echo processes. */
        cachedEventId: String? = null,
        /** Event id the send replies to — sender/excerpt resolve below so the
         *  pending row's header renders immediately. */
        replyToEventId: String? = null,
    ): com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message {
        val outbox = withTimeoutOrNull(OUTBOX_READ_TIMEOUT_MS) {
            c.room.getOutbox(matrixRoomId, txnId).first()
        }
        // Resolve the reply header now, not at the echo: the pending row can
        // sit for seconds before the sync echo replaces it, and a header-less
        // reply reads as a plain message (same lookup as messageFrom — a local
        // store read, no network).
        var replyToSender: String? = null
        var replyToExcerpt: String? = null
        if (replyToEventId != null) {
            withTimeoutOrNull(ROOM_BUDGET_MS) {
                c.room.getTimelineEvent(matrixRoomId, EventId(replyToEventId)).firstOrNull()
            }?.let { target ->
                replyToSender = senderNameOf(c, matrixRoomId, target.event.sender)
                (target.content?.getOrNull() as? RoomMessageEventContent.TextBased)?.let {
                    replyToExcerpt = replyExcerptOf(it.body)
                }
            }
        }
        return com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
            id = outbox?.eventId?.full ?: cachedEventId ?: "$LOCAL_PENDING_ID_PREFIX$txnId",
            sender = c.userId.full,
            senderName = "",
            body = body,
            timestampMs = timestampMs,
            isMine = true,
            sendStatus = when {
                outbox?.sendError != null -> "FAIL_LOCAL_SEND"
                // Beeper's SENT_PENDING_SERVER_ECHO parity: the homeserver ack
                // (outbox event id) flips the row to "sent" while the sync echo
                // is still in flight; the echo then replaces this row wholesale.
                outbox?.eventId != null -> "SENT_PENDING_ECHO"
                else -> null
            },
            contentType = contentType,
            durationMs = durationMs,
            replyToId = replyToEventId,
            replyToSender = replyToSender,
            replyToExcerpt = replyToExcerpt,
        )
    }

    /**
     * After a process restart the in-memory pending-echo maps are gone, but
     * Trixnity's outbox is persisted — a message still queued (sync down /
     * slow round pending) or acked-but-not-yet-echoed would show NO row in the
     * thread until the echo lands, reading as "message took minutes to send".
     * Rebuild the pending maps from the outbox once per
     * client attach: every non-draft row becomes an optimistic echo, reusing
     * the live-send machinery ([pendingEchoRow], [insertPendingEchoes], the
     * room-list pending bump). Entries self-clean when the echo lands
     * ([insertPendingEchoes] removes them); an already-echoed row resurrects
     * with the REAL event id, so [injectPendingEchoes]'s id dedup hides it.
     */
    private suspend fun reconstructOutboxPendings(c: MatrixClient) {
        val rows = withTimeoutOrNull(OUTBOX_RECONSTRUCT_BUDGET_MS) {
            c.room.getOutbox().first()
        } ?: return
        var rebuilt = 0
        val rebuiltRooms = LinkedHashSet<String>()
        for (flow in rows) {
            val om = withTimeoutOrNull(OUTBOX_RECONSTRUCT_BUDGET_MS) { flow.first() } ?: continue
            if (om.isDraft) continue
            val ts = om.createdAt.toEpochMilliseconds()
            val roomKey = om.roomId.full
            when (val content = om.content) {
                is RoomMessageEventContent.TextBased -> if (putPendingIfAbsent(
                        pendingTextEcho, roomKey, om.transactionId,
                        PendingTextSend(
                            om.transactionId, ts, content.body,
                            (content.relatesTo as? RelatesTo.Reply)?.replyTo?.eventId?.full,
                        ),
                    )) { rebuilt++; rebuiltRooms.add(roomKey) }
                is RoomMessageEventContent.FileBased.Image -> if (putPendingIfAbsent(
                        pendingImageEcho, roomKey, om.transactionId,
                        PendingImageSend(om.transactionId, ts, content.body.ifBlank { "Photo" }),
                    )) { rebuilt++; rebuiltRooms.add(roomKey) }
                // Voice notes enqueue as an Unknown audio content (hand-built
                // m.audio + org.matrix.msc3245.voice, see [sendVoiceNote]).
                is RoomMessageEventContent.FileBased.Audio -> if (putPendingIfAbsent(
                        pendingAudioEcho, roomKey, om.transactionId,
                        PendingAudioSend(om.transactionId, ts, durationMs = null, localFile = null, eventId = om.eventId?.full),
                    )) { rebuilt++; rebuiltRooms.add(roomKey) }
                is RoomMessageEventContent.Unknown ->
                    if (content.type == RoomMessageEventContent.FileBased.Audio.TYPE &&
                        putPendingIfAbsent(
                            pendingAudioEcho, roomKey, om.transactionId,
                            PendingAudioSend(om.transactionId, ts, durationMs = null, localFile = null, eventId = om.eventId?.full),
                        )
                    ) { rebuilt++; rebuiltRooms.add(roomKey) }
                else -> {}
            }
        }
        if (rebuilt > 0) {
            // The resurrected sends' rooms bump to the top with the pending
            // preview, like [wakeAfterSend] does for a live send — per-room
            // publish, no resolver sweep (INGEST-DERIVED-PLAN Phase C; during
            // the initial crawl the pass resolve covers the bump).
            for (roomKey in rebuiltRooms) {
                val roomId = RoomId(roomKey)
                c.room.getById(roomId).firstOrNull()?.let { publishRoomRowNow(c, roomId, it) }
            }
            android.util.Log.d(TAG, "outbox: rebuilt $rebuilt pending echo rows after restart")
        }
    }

    /** Adds [value] under [txnId] only when absent — never clobbers a live send. */
    private fun <T : PendingSend> putPendingIfAbsent(
        map: java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, T>>,
        roomKey: String,
        txnId: String,
        value: T,
    ): Boolean {
        val roomMap = map.computeIfAbsent(roomKey) { java.util.concurrent.ConcurrentHashMap() }
        return roomMap.putIfAbsent(txnId, value) == null
    }

    private suspend fun computeMessagesPage(
        roomId: String,
        beforeEventId: String?,
        limit: Int,
        fast: Boolean = false,
    ): MessagesPage {
        val c = client ?: return MessagesPage(emptyList(), false)
        val matrixRoomId = RoomId(roomId)

        // Broadcast channels (you + the channel ghost) echo your own posts
        // back with your display name baked into the body ("FENN: post").
        // Resolve the name once so [messageFrom] can
        // strip it; null outside broadcast rooms = no stripping.
        val ownName = broadcastOwnNameOf(c, matrixRoomId)

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
        // the futile-restore cooldown and in the
        // fast first-page path.
        if (!fast) {
            val seed = collectTimelineEvents(c, matrixRoomId, pageCursor, limit + 1)
            // Restore only when the page still has undecryptable events — an
            // all-decrypted room needs no priming, so skip the parse entirely.
            // The futile-restore cooldown now guards only the backup round-trip
            // inside [restoreRoomSessions] — the local-first re-decrypt of
            // already-held sessions must run regardless.
            if (seed.any { it.content?.isFailure == true }) {
                // Park genuinely undecryptable pages too — the same futility signal
                // as the page-build path, so opening a doomed room doesn't re-seed
                // a restore on every read.
                if (restoreRoomSessions(c, matrixRoomId, seed) == 0) {
                    decryptRestoreCooldown.park(matrixRoomId.full, DECRYPT_RESTORE_COOLDOWN_MS)
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
        if (!fast && decryptRestoreCooldown.allowed(matrixRoomId.full)) {
            repeat(DECRYPT_RETRIES) {
                val stillEncrypted = events.any { it.content?.isFailure == true }
                if (!stillEncrypted) return@repeat
                kotlinx.coroutines.delay(DECRYPT_RETRY_DELAY_MS)
                val (e2, h2) = collectRelevantTimelineEvents(c, matrixRoomId, pageCursor, limit, fast)
                events = e2
                hasMore = h2
            }
        }
        // Older-page dead-end guard: the chain can run through a
        // block of events that build no rows — the re-import's m.replace edits
        // are dropped by [messageFrom], so an older page landing on the edit
        // wall returned an empty page. The tool's cursor can't advance past
        // invisible events, so it re-polled the same cursor forever (1 req/s
        // storm → server ANR). Walk deeper (in big steps — walls can be 100+
        // edits, e.g. the Crocs room's 168) until the page holds renderable
        // events or the chain ends, bounded.
        // Extended to the NEWEST page: the bridge's history
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
        // Key-request trigger: any event still undecryptable after the retries
        // has a session this device doesn't hold — ask our own other devices
        // (ungated by design, see [requestMissingRoomKeys]). Runs AFTER the
        // skip-walk so the walk's accumulated events are included — before,
        // only the page's own events reached the trigger and genuinely-missing
        // sessions in walked-over regions were never requested.
        requestMissingRoomKeys(c, matrixRoomId, events)
        android.util.Log.d(
            TAG,
            "getMessages: room=$matrixRoomId before=$beforeEventId limit=$limit page=${events.size} hasMore=$hasMore",
        )

        // Edits (m.replace): an edit never becomes a row,
        // but it REPLACES its target's body and marks it edited. [events] is
        // newest-first and an edit is newer than its target, so the first
        // occurrence of a target is the NEWEST edit — putIfAbsent keeps it.
        // Edits targeting events outside the page can't be applied here (their
        // target isn't in this page build) and stay invisible, as before.
        val editByTarget = HashMap<String, Pair<RoomMessageEventContent, Long>>()
        for (te in events) {
            val content = te.content?.getOrNull() as? RoomMessageEventContent ?: continue
            val replace = content.relatesTo as? RelatesTo.Replace ?: continue
            val newContent = replace.newContent
            val nc = (newContent as? RoomMessageEventContent)
                ?.takeIf { it.body.isNotBlank() } ?: continue
            editByTarget.putIfAbsent(replace.eventId.full, nc to te.event.originTimestamp)
        }
        // Auto-download: the newest audio notes of the opened thread start
        // downloading in the background so the first play tap usually hits the
        // on-disk cache. No-ops for already-cached or
        // in-flight notes, so every 3 s poll costs nothing here.
        prefetchVoiceNotes(c, matrixRoomId, events)

        val result = mutableListOf<com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message>()
        val startIndex = if (keepCursor) 0 else 1 // drop the boundary cursor on older pages
        // Delivery status only matters for the newest page (what the user just
        // sent): the status events sit right after the message in the timeline.
        // Send statuses ride on EVERY page: a bridge FAIL on a
        // message that scrolled past the newest page showed as plain "sent"
        // — the honest "not delivered" marker must survive pagination. The
        // walk itself is cached per room (see [sendStatusesByEventIdCached]),
        // so older/fast pages cost one map read.
        val sendStatuses = sendStatusesByEventIdCached(c, matrixRoomId)
        // Same scoping for read receipts: only the newest page reports whether
        // the other party has read an outgoing message (receipts point at the
        // newest events; an older page's messages are always "read" in practice
        // but re-resolving each receipt per page isn't worth it).
        val readEventIds = if (beforeEventId == null && !fast) readReceiptsByEvent(c, matrixRoomId, events) else emptySet()
        // Reactions on EVERY page path: m.reaction sits after
        // its target, so a walk from the room head covers the in-window
        // messages of fast cold-open pages and scrolled-back older pages alike.
        // Plaintext walk, no decrypt wait; [reactionCache] keeps
        // repeated builds (older-page scrolls) off the raw chain. Ceiling:
        // messages deeper than SEND_STATUS_WINDOW events from the head show
        // no reactions — a deeper per-scroll walk would tax every pagination.
        // Full builds recompute UNCACHED (as before): they must see fresh
        // state the moment a sync lands — a reaction removed on another
        // device redacts the tag immediately, not one TTL later.
        val reactionsByEvent = if (beforeEventId == null && !fast) {
            reactionLabelsByEvent(c, matrixRoomId)
                ?: reactionLabelsByEventCached(c, matrixRoomId)
        } else {
            reactionLabelsByEventCached(c, matrixRoomId)
        }
        // A just-sent message's echo can sit in the timeline before its
        // decryption lands; the optimistic rows below represent it, so skip
        // the undecrypted "[Encrypted]" placeholder — a message the user just
        // sent from this device must never read "waiting for key".
        val pendingTxnIds = buildSet {
            pendingAudioEcho[roomId]?.keys?.let { addAll(it) }
            pendingTextEcho[roomId]?.keys?.let { addAll(it) }
            pendingImageEcho[roomId]?.keys?.let { addAll(it) }
        }
        // The room's bridge caps, fetched lazily on the first own row (see the
        // row loop below).
        var features: RoomFeatures? = null
        // The deepest event the row loop actually reached — handed back as
        // [MessagesPage.nextBeforeEventId]. It must be the last event READ, not
        // the walk's last event: the walk's tail can be invisible rows (blank
        // re-import copies) and the loop stops at [limit] long before reaching
        // it, so reporting the walk's deepest event would skip everything in
        // between on the next page.
        var deepestVisited = startIndex
        for (i in startIndex until events.size) {
            if (result.size >= limit) break
            deepestVisited = i
            val te = events[i]
            val txnId = txnIdOf(te)
            if (txnId != null && txnId in pendingTxnIds && te.content?.getOrNull() == null) continue
            // Tombstone: a redacted MESSAGE renders as a
            // quiet "Message unsent" row instead of silently vanishing (the
            // message type only — redacted reactions are timeline noise, not
            // rows). The projection's ingest walk skips these like any other
            // non-renderable event. In an
            // e2ee room the original type is unrecoverable — Trixnity stores
            // any redacted encrypted event as RedactedEventContent
            // ("m.room.encrypted"), whatever it was — so a redacted message
            // only identifies via our own unsend marker ([unsentMessageIds]);
            // trusting the bare encrypted type would paint tombstones over the
            // bridge's key-rotation redactions.
            val resolvedContent = te.content?.getOrNull()
            if (resolvedContent is RedactedEventContent &&
                (
                    resolvedContent.eventType == "m.room.message" ||
                        resolvedContent.eventType == "m.room.encrypted" &&
                        te.event.id.full in unsentMessageIds
                    )
            ) {
                result.add(redactedRow(c, matrixRoomId, te))
                continue
            }
            val edit = editByTarget[te.event.id.full]
            messageFrom(
                c,
                matrixRoomId,
                te,
                sendStatuses[te.event.id.full],
                read = te.event.id.full in readEventIds,
                reactions = reactionsByEvent[te.event.id.full].orEmpty(),
                editedBody = (edit?.first as? RoomMessageEventContent.TextBased)?.body,
                editedContent = edit?.first,
                edited = edit != null,
                ownName = ownName,
            )?.let { row ->
                // Bridge caps: stamp canEdit/canUnsend on
                // OWN rows only — the fetch fires on the first own row, so a
                // received-only thread never pays the state GET.
                if (!row.isMine) {
                    result.add(row)
                } else {
                    if (features == null) features = roomFeatures(c, matrixRoomId)
                    val f = features!!
                    val ageMs = System.currentTimeMillis() - row.timestampMs
                    result.add(
                        row.copy(
                            canEdit = f.editSupported && row.contentType == "text" &&
                                (f.editMaxAgeMs == null || ageMs < f.editMaxAgeMs),
                            canUnsend = f.deleteSupported &&
                                (f.deleteMaxAgeMs == null || ageMs < f.deleteMaxAgeMs),
                        ),
                    )
                }
            }
        }
        // Optimistic rows for sends whose sync echo hasn't landed (voice notes
        // + text share the resolve/replace dance): a
        // just-sent message shows in every newest page (even a re-opened
        // thread) until its sync echo replaces it. Only a DECRYPTED echo
        // retires the row — the echo is re-read via the API (which triggers
        // decryption; a store decode can leave E2EE content unresolved), so a
        // just-sent note never flickers into "[Encrypted]".
        suspend fun insertPendingEchoes(
            pendings: List<PendingSend>,
            removeFrom: (String) -> Unit,
            rowFactory: suspend (PendingSend) -> com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message,
        ) {
            for (pending in pendings) {
                // The loop skips the echo only while its content is unresolved —
                // the in-page render below must fire only then, or the real row
                // is added twice (LazyColumn duplicate-key crash).
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
                            ownName = ownName,
                        )?.let { result.add(0, it) }
                    }
                } else if (beforeEventId == null && result.size < limit + 1) {
                    // Insert at index 0: [result] is newest-first, so index 0
                    // is the NEWEST slot — after the final ordering the row
                    // lands at the newest end. (The old append put it at the
                    // OLDEST end: the just-sent message surfaced at the TOP of
                    // the thread and dead-ended pagination via the local-row
                    // guard;.) Oldest-first iteration keeps
                    // rapid sends in chronological order.
                    result.add(0, rowFactory(pending))
                }
            }
        }
        // Voice-note rows first, then text, then photos — same order as the
        // three send loops, so rapid mixed sends keep their relative order.
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
                    cachedEventId = a.eventId,
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
                pendingEchoRow(
                    c, matrixRoomId, t.txnId, t.timestampMs, t.body,
                    replyToEventId = t.replyToEventId,
                )
            },
        )
        // Photo rows: same local-… dedup; the echo's real row replaces them.
        insertPendingEchoes(
            pendingImageEchoes(roomId),
            { pendingImageEcho[roomId]?.remove(it) },
            { p ->
                val i = p as PendingImageSend
                pendingEchoRow(c, matrixRoomId, i.txnId, i.timestampMs, i.fileName, contentType = "image")
            },
        )
        // Order the page by each message's real time (origin_server_ts), not the
        // timeline's topological chain order. Beeper bridges ingest messages
        // when they arrive but stamp them with the ORIGINAL send time, so the
        // chain can place a 1:43 PM message above a 1:41 PM one (the 1:41 was
        // ingested late) — the visible "wrong order" in bridged rooms.
        // The sort is stable, so equal
        // timestamps keep the chain order — a no-op for native (non-bridged)
        // rooms, whose homeserver stamps events at ingest. Only CONFIRMED rows
        // sort by timestamp: pending "local-…" rows carry device-clock times
        // that can lag the server clock, which would sort a just-sent message
        // into the middle of the thread; they are the newest
        // sends by definition, so they append last, in send order.
        val (pending, confirmed) = result.partition { it.id.startsWith(LOCAL_PENDING_ID_PREFIX) }
        // Sort the REVERSED (oldest-first) confirmed rows like the old code
        // did, so equal timestamps keep the old tie order; then append the
        // pending rows (also oldest-first) at the newest end.
        val oldestFirst = confirmed.reversed().sortedWith(compareBy { it.timestampMs }) + pending.reversed()
        // An encrypted room whose stored events all stayed undecryptable builds
        // zero rows — say WHY (the tool shows the decryption notice) instead of
        // letting the empty page read as "No messages yet.". Only the newest
        // page carries the flag; a genuinely empty room (no events at all)
        // stays a plain empty page.
        val roomEncrypted = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).firstOrNull()?.encrypted
        } == true
        val undecryptable = roomEncrypted && beforeEventId == null && events.isNotEmpty() && oldestFirst.isEmpty()
        return MessagesPage(
            messages = oldestFirst,
            hasMore = hasMore,
            encrypted = undecryptable,
            nextBeforeEventId = events.getOrNull(deepestVisited)?.event?.id?.full,
        )
    }

    /** Type of Beeper's per-room bridge-capability state event:
     * `edit`/`delete` support levels + `edit_max_age` /
     *  `delete_max_age` windows the bridge enforces (e.g. WhatsApp: edit 15
     *  min, unsend 2 days). The page build reads it to offer/hide the tool's
     *  EDIT/UNSEND actions on own rows. */
    private const val BEEPER_ROOM_FEATURES_EVENT_TYPE = "com.beeper.room_features"

    /**
     * A room's bridge capabilities (the [BEEPER_ROOM_FEATURES_EVENT_TYPE]
     * content, verified against mautrix's connector capabilities): the
     * support levels are ints (-2 rejected … 2 fully supported) and the ages
     * are JSON **seconds**. Field present ⇒ supported iff ≥ 1; field absent
     * ⇒ supported (Telegram-style bridges leave it out — wrongly hiding the
     * action is worse than a tap-time error); age present and > 0 ⇒ window,
     * else no limit.
     */
    private data class RoomFeatures(
        val editSupported: Boolean,
        val editMaxAgeMs: Long?,
        val deleteSupported: Boolean,
        val deleteMaxAgeMs: Long?,
    )

    /** Ungated room — no [BEEPER_ROOM_FEATURES_EVENT_TYPE] state event (native
     *  Matrix rooms, caps-less bridges): both actions always offered. */
    private val UNGATED_ROOM_FEATURES = RoomFeatures(true, null, true, null)

    /** Room → bridge caps, fetched at most once per room per process (negative
     *  results cached too, so a caps-less room never refetches — the caps
     *  don't change within a session, and the full-state GET is not free). */
    private val roomFeaturesCache = java.util.concurrent.ConcurrentHashMap<String, RoomFeatures>()

    /**
     * The room's bridge caps. The state event's
     * state_key is the bridge-info key (NOT ""), so a targeted
     * getStateEventContent can't hit it — a full-state GET filtered by type,
     * deserializing to [UnknownEventContent] with the raw JSON. Any failure
     * reads as ungated.
     */
    private suspend fun roomFeatures(c: MatrixClient, matrixRoomId: RoomId): RoomFeatures =
        roomFeaturesCache[matrixRoomId.full] ?: run {
            val features = runCatching {
                c.api.room.getState(matrixRoomId).getOrNull()
                    ?.firstOrNull {
                        (it.content as? UnknownEventContent)?.eventType ==
                            BEEPER_ROOM_FEATURES_EVENT_TYPE
                    }
                    ?.let { parseRoomFeatures(it.content as UnknownEventContent) }
            }.getOrNull() ?: UNGATED_ROOM_FEATURES
            roomFeaturesCache[matrixRoomId.full] = features
            features
        }

    /** Lenient [RoomFeatures] parse — unknown keys/malformed numbers read as
     *  "absent" (supported / no window), matching the fail-open rule. */
    private fun parseRoomFeatures(content: UnknownEventContent): RoomFeatures {
        fun supportOf(key: String): Boolean? =
            (content.raw[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { it >= 1 }

        fun ageMsOf(key: String): Long? =
            (content.raw[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?.takeIf { it > 0 }?.times(1000)

        return RoomFeatures(
            editSupported = supportOf("edit") ?: true,
            editMaxAgeMs = ageMsOf("edit_max_age"),
            deleteSupported = supportOf("delete") ?: true,
            deleteMaxAgeMs = ageMsOf("delete_max_age"),
        )
    }

    /** Type of Beeper's per-message delivery-state events (unencrypted, posted
     *  by the bridge right after the message). */
    private val BEEPER_SEND_STATUS_EVENT_TYPE = "com.beeper.message_send_status"

    /** Type of Beeper's per-room archive marker (account data): rooms the user
     *  archived on Beeper — hidden from the main room list, silent, reachable
     *  only via search VIEW ALL (chats). Beeper's REAL archive
     *  state is `com.beeper.inbox.done`, whose content is reset to `{}` on
     *  unarchive (never deleted). The type is `com.beeper.inbox.done` — NOT
     *  `com.beeper.chats.inbox.done` (the round-4 guess; the LP3's real
     *  `com.beeper.inbox.done` rows verified on-device: Tiki's
     *  archive + the `!updates` room, while the `.chats.` rows in the store
     *  were our own writes). `auto_archive` (`com.beeper.chats.auto_archive`)
     *  is NOT a write-only orphan: Beeper's desktop client actively reads it
     *  as room account data `{archive_at_ms, created_at_ms, archive_at_client,
     *  trigger}` and sets/clears the room's auto-archive from it (bundle
     *  analysis). The LP3's own auto_archive writes were ignored
     *  because of their shape, not the type — chats neither reads nor writes
     *  it. */
    private const val BEEPER_INBOX_DONE_EVENT_TYPE = "com.beeper.inbox.done"

    /** Beeper's archive account-data content: `{"at_order":…,"updated_ts":…}`
     *  = archived, `{}` = unarchived — row presence is NOT the flag, the
     *  content is. The canonical Beeper shape carries only `at_order` +
     *  `updated_ts` (the desktop client reads exactly those two; bundle
     *  analysis — Beeper never writes `at_ts`, the `at_ts` seen
     *  on-device was the LP3's own earlier writes). `atTs` stays as a
     *  lenient-deserialization field for those legacy rows. Registered in
     *  the event-content mappings (see [archiveMappingsModule]) so the
     *  store-backed typed read in [roomFlagsByRoom] works — the default
     *  mappings only decode it as [UnknownEventContent], whose fixed type is
     *  null and the typed store get rejects that. */
    data class BeeperInboxDoneContent(
        val atOrder: Long? = null,
        val atTs: Long? = null,
        val updatedTs: Long? = null,
    ) : RoomAccountDataEventContent

    /** Reads the three fields leniently (Beeper's shape is not contractual —
     *  unknown keys and malformed numbers are ignored) and writes back the
     *  same shape, so our archive/unarchive PUTs mirror Beeper's own. */
    private object BeeperInboxDoneContentSerializer : KSerializer<BeeperInboxDoneContent> {
        override val descriptor = buildClassSerialDescriptor(BEEPER_INBOX_DONE_EVENT_TYPE)
        override fun deserialize(decoder: Decoder): BeeperInboxDoneContent {
            val obj = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: return BeeperInboxDoneContent()
            fun longOf(key: String): Long? = (obj[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            return BeeperInboxDoneContent(longOf("at_order"), longOf("at_ts"), longOf("updated_ts"))
        }
        override fun serialize(encoder: Encoder, value: BeeperInboxDoneContent) {
            val enc = (encoder as? JsonEncoder) ?: return
            enc.encodeJsonElement(buildJsonObject {
                value.atOrder?.let { put("at_order", it) }
                value.atTs?.let { put("at_ts", it) }
                value.updatedTs?.let { put("updated_ts", it) }
            })
        }
    }

    /** Registers the Beeper archive marker with the client's event-content
     *  mappings (defaults + this one; the override pattern mirrors
     *  [plaintextVerificationModule]). Koin registers module definitions in
     *  order, so this last module wins over the default mappings single. */
    private fun archiveMappingsModule() = module {
        single<EventContentSerializerMappings> {
            EventContentSerializerMappings.default + EventContentSerializerMappings {
                roomAccountDataOf(BEEPER_INBOX_DONE_EVENT_TYPE, BeeperInboxDoneContentSerializer)
                // Registering the send-status type exists ONLY to land it in
                // the sync filter's timeline whitelist — Trixnity's
                // applyDefaultFilter builds that whitelist from the registered
                // message mappings, and an unregistered type is stripped by
                // spec-compliant servers (Synapse). Beeper's own server
                // ignores filters, which is why the LP3 receives statuses
                // while the emulator never did. The UnknownEventContent serializer
                // keeps events parsing exactly as before, so
                // [sendStatusByEventId]'s raw walk is untouched.
                messageOf(BEEPER_SEND_STATUS_EVENT_TYPE, UnknownEventContentSerializer(BEEPER_SEND_STATUS_EVENT_TYPE))
            }
        }
    }

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

    /** Room → (fetched-at elapsedRealtime, status map), TTL-cached so every
     *  page build (fast warm, pagination, refresh) reads one map instead of
     *  re-walking the room's status window per call. */
    private val sendStatusCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<String, String>>>()
    /** Per-room reaction-tag maps behind the same TTL ([SEND_STATUS_CACHE_TTL_MS])
     *  as [sendStatusCache] — every page path now carries reactions, and each build would otherwise re-walk the raw chain.
     *  Invalidated in [sendReaction]/[unsendReaction] so a toggle never reads
     *  its own stale map. */
    private val reactionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<String, List<String>>>>()

    /** Value in a room-keyed TTL cache ([key] → fetchedAt elapsedRealtime to
     *  value) when its fetch is within [ttlMs] of [now], else null. */
    private fun <V> java.util.concurrent.ConcurrentHashMap<String, Pair<Long, V>>.fresh(
        key: String,
        ttlMs: Long,
        now: Long,
    ): V? = this[key]?.takeIf { (fetchedAt, _) -> now - fetchedAt < ttlMs }?.second

    /** [sendStatusByEventId] with a per-room TTL cache — statuses must reach
     *  messages on ANY page (the FAIL marker disappearing once a message
     *  scrolled past the newest page read as "sent but not delivered"),
     *  and the 250-event walk must not run per page build. */
    private suspend fun sendStatusesByEventIdCached(
        c: MatrixClient,
        matrixRoomId: RoomId,
    ): Map<String, String> {
        val key = matrixRoomId.full
        val now = android.os.SystemClock.elapsedRealtime()
        sendStatusCache.fresh(key, SEND_STATUS_CACHE_TTL_MS, now)?.let { return it }
        val map = sendStatusByEventId(c, matrixRoomId)
        sendStatusCache[key] = now to map
        return map
    }

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
        // delivered", ; the page build abandoned that API for
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
            // first breach in a room fail.
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
     * Reaction tags per message event id, from the room's recent `m.reaction`
     * events (the newest window, like [sendStatusByEventId]). Reactions are
     * never encrypted (per the Matrix spec), so the raw event content reads
     * directly — no decrypt wait. A reaction's `m.relates_to` carries the
     * target event id + the reaction key (an emoji).
     */
    private suspend fun reactionLabelsByEvent(
        c: MatrixClient,
        matrixRoomId: RoomId,
    ): Map<String, List<String>>? {
        // Walk the RAW chain (like [sendStatusByEventId] and the page build),
        // not the handler's getLastTimelineEvents view: that view can miss
        // events after a chain gap — the Sophie room's existing reaction
        // never reached the served page's reactions map. Reactions are
        // never encrypted, so the fast walk is enough. Null = the head read
        // timed out — the
        // caller falls back to the last known map instead of serving/caching
        // an EMPTY one, which made every tag vanish for a TTL window.
        val start = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).firstOrNull()?.lastEventId?.full
        } ?: return null
        val collected = collectRelevantTimelineEvents(c, matrixRoomId, start, SEND_STATUS_WINDOW, fast = true).first
        return reactionTagsForEvents(c, matrixRoomId, collected)
    }

    /**
     * [reactionLabelsByEvent] behind the same per-room TTL as the send
     * statuses. Serves every page path — the fast cold-open page and
     * scrolled-back older pages included, which both served no tags before.
     */
    private suspend fun reactionLabelsByEventCached(
        c: MatrixClient,
        matrixRoomId: RoomId,
    ): Map<String, List<String>> {
        val key = matrixRoomId.full
        val now = android.os.SystemClock.elapsedRealtime()
        reactionCache.fresh(key, SEND_STATUS_CACHE_TTL_MS, now)?.let { return it }
        val map = reactionLabelsByEvent(c, matrixRoomId)
        if (map == null) {
            // Head read timed out — stale tags beat no tags (they self-heal on
            // the next build); never cache an empty map as if it were truth.
            return reactionCache[key]?.second ?: emptyMap()
        }
        reactionCache[key] = now to map
        return map
    }

    /**
     * Reaction tags per target message id from a list of timeline events (the
     * newest window, or the sync delta). Each entry is a display string —
     * "Name reacted ❤️" (own reactions read "You reacted ❤️") —
     * deduped per SENDER (one reaction per person per message, Beeper
     * semantics — LP3: fire then heart showed both), the
     * person's newest reaction kept, ordered chronologically so the first tag
     * is the FIRST person to react (the name a summary keeps;).
     * The tag map comes from a bounded walk off the room head
     * ([reactionLabelsByEventCached], SEND_STATUS_WINDOW events), so messages
     * deeper than that window report none even on older pages.
     */
    private suspend fun reactionTagsForEvents(
        c: MatrixClient,
        matrixRoomId: RoomId,
        events: List<TimelineEvent>,
    ): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<ReactionEntry>>()
        val seen = HashMap<String, ReactionEntry>() // "target|sender" → entry (latest kept)
        for (te in events) {
            val content = te.content?.getOrNull() ?: (te.event.content as? ReactionEventContent)
            if (content !is ReactionEventContent) continue
            val relates = content.relatesTo as? RelatesTo.Annotation ?: continue
            val targetId = relates.eventId.full
            val key = relates.key?.takeIf { it.isNotBlank() } ?: continue
            val sender = te.event.sender
            val dedupeKey = "$targetId|${sender.full}"
            val existing = seen[dedupeKey]
            if (existing != null) {
                // A person's newer reaction replaces their older one — the
                // display shows the reaction they currently hold.
                if (te.event.originTimestamp >= existing.timestampMs) {
                    existing.timestampMs = te.event.originTimestamp
                    existing.key = key
                }
                continue
            }
            val who = if (sender == c.userId) "You" else senderNameOf(c, matrixRoomId, sender)
            val entry = ReactionEntry(te.event.originTimestamp, who, key)
            seen[dedupeKey] = entry
            result.getOrPut(targetId) { mutableListOf() }.add(entry)
        }
        return result.mapValues { (_, entries) ->
            entries.sortedBy { it.timestampMs }.map { "${it.who} reacted ${it.key}" }
        }
    }

    /** A reaction as it will display: who reacted, with which key, and when
     *  (the person's latest reaction survives dedupe). */
    private class ReactionEntry(var timestampMs: Long, val who: String, var key: String)

    /** Keeps a message's reaction tags ("Name reacted ❤️") to at most two
     *  lines: two or fewer stay as-is; more collapse into one compact summary
     *  — the FIRST (earliest) reactor by name, everyone else folded into "and
     *  others", the distinct emoji listed once each, space-separated:
     *  "Sophie and others reacted ❤️ 😂". One person stacking several emoji isn't a crowd, so it
     *  keeps the per-reaction lines. Tags never exceed two after this, so the
     *  tool renders the list unchanged. */
    private fun collapseReactionTags(tags: List<String>): List<String> {
        if (tags.size <= 2) return tags
        val reactions = tags.map { tag ->
            val at = tag.indexOf(" reacted ")
            if (at <= 0) return tags // not our label shape — leave untouched
            tag.substring(0, at) to tag.substring(at + " reacted ".length)
        }
        if (reactions.map { it.first }.distinct().size < 2) return tags
        val emojis = reactions.map { it.second }.distinct()
        return listOf("${reactions.first().first} and others reacted ${emojis.joinToString(" ")}")
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
     *  on a room's first newest-page build and memoized per room.
     *  Beeper flags its bot via m.bridge ("bridgebot"); the
     *  fallbacks cover older bridges (uk.half-shot.bridge) and rooms without
     *  bridge state (functional_members "service_members" — which misses the
     *  Instagram DM, whose @instagramgobot posts receipts too). Callers must be
     *  inside a store transaction (Room-backed repo reads need one). */
    /**
     * NOTE: the m.bridge channel's `fi.mau.receiver` looks like
     * the contact's number but is the USER'S OWN WhatsApp number — it repeats
     * across every LID DM. A contact-phone source was explored here and
     * reverted; see the comment at contactIdentifierOf/contactIdentifier in
     * the app. LID contacts carry no number in the room data (Beeper
     * resolves LIDs server-side).
     */
    context(transaction: ReadTransaction)
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
            val txManager = c.di.get<StoreTransactionManager>(StoreTransactionManager::class)
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

    /** Whether this device counts as trusted for E2EE (TOFU): any device
     *  cross-signed by the account's user-signing key is trusted — Beeper
     *  requires no per-device SAS (the local `verified` flag is NOT required). */
    private suspend fun isDeviceVerified(c: MatrixClient): Boolean {
        // A null read (timeout / trust store not warm) must NOT read as
        // "unverified": the encrypted-room fast path then fires intermittently
        // on a verified device, emptying the thread mid-use. A genuinely unverified device's read succeeds and
        // returns a non-CrossSigned trust level.
        val trust = withTimeoutOrNull(KEY_BACKUP_VERIFY_TIMEOUT_MS) {
            c.key.getTrustLevel(c.userId, c.deviceId).firstOrNull()
        } ?: return true
        return trust is de.connect2x.trixnity.crypto.key.DeviceTrustLevel.CrossSigned
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
        val sessionToEvents = HashMap<String, MutableList<TimelineEvent>>()
        val sessionIds = events.mapNotNull { te ->
            // Only events that failed to decrypt need a key-backup restore:
            // already-resolved events have their megolm session in the local
            // store. Loading every session id of the page from the backup was
            // the slow path — a network round-trip (up to
            // KEY_BACKUP_LOAD_TIMEOUT_MS each) on every open, even when
            // nothing needed restoring.
            val content = te.content
            if (content?.getOrNull() != null) null
            else (te.event.content as? EncryptedMessageEventContent.MegolmEncryptedMessageEventContent)
                ?.sessionId?.also { sid ->
                    sessionToEvents.getOrPut(sid) { mutableListOf() }.add(te)
                }
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
        val timelineStore = c.di.get<RoomTimelineStore>()
        val tm = c.di.get<StoreTransactionManager>()
        val encryptionService = c.di.get<RoomEventEncryptionService>(
            org.koin.core.qualifier.named<MegolmRoomEventEncryptionService>(),
        )
        // Local-first pass: a row whose megolm session is already
        // in the olm store but whose stored content is a cached failure only
        // needs a re-decrypt — getTimelineEvent never retries (drive 5), and
        // the futile-restore cooldown was parking exactly these rooms (Fen and
        // Friends: messageIndex 2 of a session holding indexes 0,1,3 — key
        // present, row stuck). Free and local: no backup round-trip, no
        // cooldown. The cooldown keeps guarding the network part below.
        val olmStore = c.di.get<OlmCryptoStore>(OlmCryptoStore::class)
        var resolvedLocal = 0
        val backupSessionIds = sessionIds.filter { sessionId ->
            if (olmStore.getInboundMegolmSession(sessionId, matrixRoomId).firstOrNull() != null) {
                resolvedLocal += reDecryptSessionEvents(
                    matrixRoomId, sessionId, stuckRowsForSession(c, matrixRoomId, sessionId)
                        .ifEmpty { sessionToEvents[sessionId].orEmpty() },
                    timelineStore, tm, encryptionService,
                )
                false
            } else true
        }
        if (backupSessionIds.isEmpty()) {
            android.util.Log.d(TAG, "restoreRoomSessions: $matrixRoomId — all ${sessionIds.size} session(s) local, re-decrypted $resolvedLocal event(s), no backup needed")
            return resolvedLocal
        }
        // allowed() is true when NOT parked (CooldownMap) — the backup
        // round-trip must run only then (polarity was inverted by the
        // cooldown refactor 70e0702, so it ran exactly while parked).
        if (!decryptRestoreCooldown.allowed(matrixRoomId.full)) return resolvedLocal
        backupSessionIds.forEach { sessionId ->
            try {
                val ok = withTimeoutOrNull(KEY_BACKUP_LOAD_TIMEOUT_MS) {
                    keyBackup.loadMegolmSession(matrixRoomId, sessionId)
                }
                if (ok != null) {
                    loaded++
                    // Re-decrypt + re-persist: getTimelineEvent only reads the
                    // store's cached result, so a freshly loaded session never
                    // reaches the stored rows through it.
                    resolvedLocal += reDecryptSessionEvents(
                        matrixRoomId, sessionId, stuckRowsForSession(c, matrixRoomId, sessionId)
                            .ifEmpty { sessionToEvents[sessionId].orEmpty() },
                        timelineStore, tm, encryptionService,
                    )
                    android.util.Log.d(
                        TAG,
                        "restoreRoomSessions: loaded session $sessionId for $matrixRoomId (${sessionToEvents[sessionId]?.size ?: 0} event(s))",
                    )
                } else {
                    android.util.Log.w(TAG, "restoreRoomSessions: loadMegolmSession timed out for $matrixRoomId / $sessionId")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "restoreRoomSessions: loadMegolmSession failed for $matrixRoomId / $sessionId: ${e.message}")
            }
        }
        android.util.Log.d(TAG, "restoreRoomSessions: loaded $loaded/${sessionIds.size} sessions for $matrixRoomId")
        return resolvedLocal
    }

    /** All timeline rows of [matrixRoomId] encrypted with [sessionId] whose
     *  stored content is still unresolved (null or failure). The re-decrypt
     *  passes used to cover only the collected page window, so the older rows
     *  of an already-loaded session stayed stuck after a fresh login (the
     *  room's oldest messages). Plain LIKE scan — bounded by the
     *  room's row count, only runs while unresolved rows exist. */
    private suspend fun stuckRowsForSession(
        c: MatrixClient,
        matrixRoomId: RoomId,
        sessionId: String,
    ): List<TimelineEvent> {
        val db = runCatching { c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class) }.getOrNull()
            ?: return emptyList()
        val json = runCatching { c.di.get<Json>() }.getOrNull() ?: return emptyList()
        return chainDb("stuckRowsForSession") {
            withContext(Dispatchers.IO) {
                runCatching {
                    db.openHelper.writableDatabase.query(
                        "SELECT value FROM TimelineEvent WHERE roomId = ? AND value LIKE ?",
                        arrayOf<Any>(matrixRoomId.full, "%$sessionId%"),
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val value = cursor.getString(0) ?: continue
                                val te = runCatching { json.decodeFromString<TimelineEvent>(value) }.getOrNull() ?: continue
                                val content = te.event.content as? EncryptedMessageEventContent.MegolmEncryptedMessageEventContent
                                if (content?.sessionId != sessionId) continue
                                if (te.content?.getOrNull() != null) continue // already resolved
                                add(te)
                            }
                        }
                    }
                }.getOrDefault(emptyList())
            }
        }
    }

    /** Re-decrypts [events] with the megolm service and persists the plaintext
     *  back into the timeline store — the shared tail of the local-first and
     *  backup-restored passes of [restoreRoomSessions]. Returns the count of
     *  events that decrypted. */
    private suspend fun reDecryptSessionEvents(
        matrixRoomId: RoomId,
        sessionId: String,
        sessionEvents: List<TimelineEvent>,
        timelineStore: RoomTimelineStore,
        tm: StoreTransactionManager,
        encryptionService: RoomEventEncryptionService,
    ): Int {
        var resolved = 0
        sessionEvents.forEach { te ->
            val messageEvent = te.event as? ClientEvent.RoomEvent.MessageEvent<*>
            if (messageEvent == null) return@forEach
            val decrypted = runCatching { encryptionService.decrypt(messageEvent) }
                .getOrNull()?.getOrNull() ?: return@forEach
            tm.writeTransaction {
                timelineStore.update(te.event.id, matrixRoomId) { old ->
                    old?.copy(content = Result.success(decrypted))
                }
            }
            resolved++
        }
        android.util.Log.d(TAG, "restoreRoomSessions: re-decrypted $resolved/${sessionEvents.size} events for $matrixRoomId / $sessionId")
        return resolved
    }

    /** Asks our own other devices for the megolm sessions of undecryptable
     *  events. Nothing in Trixnity 5.8 triggers a key request automatically,
     *  and the stock [OutgoingRoomKeyRequestEventHandler] refuses to request
     *  from unverified devices, so the interface resolves to our permissive
     *  override (PermissiveOutgoingRoomKeyRequestEventHandler). Requests are
     *  deduped inside the handler, so calling this on every page build costs
     *  one store read per missing session. Runs ungated (not behind the
     *  futile-restore cooldown or the `fast` flag): a cooldown that skips the
     *  key-backup restore must not also suppress the cheap request.
     */
    private suspend fun requestMissingRoomKeys(c: MatrixClient, matrixRoomId: RoomId, events: List<TimelineEvent>) {
        val missing = events.mapNotNull { te ->
            if (te.content?.getOrNull() != null) null
            else (te.event.content as? EncryptedMessageEventContent.MegolmEncryptedMessageEventContent)
                ?.let { it.sessionId to te.event.id.full }
        }
        if (missing.isEmpty()) return
        val outgoing = runCatching { c.di.get<OutgoingRoomKeyRequestEventHandler>() }.getOrNull() ?: return
        // One request per missing session, logged with the event ids that
        // produced it — the handler logs the request itself; the event ids tie
        // a request back to the stuck events.
        missing.groupBy({ it.first }, { it.second }).forEach { (sessionId, eventIds) ->
            android.util.Log.d(
                TAG,
                "requestMissingRoomKeys: $matrixRoomId session $sessionId from events ${eventIds.distinct()}",
            )
            outgoing.requestRoomKeys(matrixRoomId, sessionId)
        }
    }

    /** An own send leaves its echo waiting on the server; without a wake,
     *  the outbox drain + echo are gated on the sync cycle — the long-poll
     *  can hold up to 30s, the slow rounds 5/30 min — so the just-sent
     *  message sat on "SENDING" and the room panel kept the pre-send preview.
     * A queued syncOnce ABORTS an
     *  in-flight long-poll immediately (SyncApiClient selects it away), so
     *  run ONE in both modes: the round drains the outbox (the message
     *  reaches the server now) and the follow-up long-poll returns the echo
     *  in ~1-2s — same ~640ms cost as a push-wake round, only on a user
     *  send. Also wakes the room-list resolver so the pending-row bump below
     *  publishes immediately (the echo dirties the list for the real pass).
     *  Skipped when sync is paused by the user.
     */
    private fun wakeAfterSend(roomId: String) {
        // No roomListDirty here: a pre-echo dirty pass always ran
        // on STALE data (the echo hadn't landed), and the echo's collectors in
        // [observeNotifications] publish the row themselves — so
        // this cost a second full pass per send on a big account. The resolver
        // wake below is kept for the pending-row bump's immediate publish.
        wakeRoomList()
        // The in-flight send's optimistic row is injected into SERVED pages —
        // bump the page revision so the thread's gating poll fetches and shows
        // it now instead of waiting for the server echo.
        bumpMessagePageRevision(roomId)
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
                is PendingImageSend -> "Photo"
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
        // Battery saver skips only the dark-screen wake — a send with the
        // screen on is foreground work and gets its catch-up sync.
        if (!syncEnabled && !isScreenInteractive()) return
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

    // --- Megolm session self-heal -----------------------------
    // The bridge-key bug window left some rooms with an outbound
    // megolm session created while the bridge device was absent from the
    // key-share set; since per-send rotation was removed the
    // stale session is reused forever and the bridge reports an encryption
    // issue for every send. One-shot self-heal: when a FAIL send-status for
    // one of our own messages cites encryption/session problems, rotate the
    // outbound session once and never check the room again.

    /**
     * Room → last-rotation elapsedRealtime for the outbound megolm session.
     * Re-armed: once-per-run gave a broken room exactly one
     * rotation — the Hannah room redacted the new key after one message, so
     * the next FAIL burst had no second attempt. Now rotation is allowed again
     * after [MEGOLM_RE_ROTATE_MIN_MS], letting repeated FAIL bursts recover.
     */
    private val megolmRotatedRooms = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Room → (checked-at elapsedRealtime, stale verdict) — the check walks
     *  the room's newest events, so it's cached for [MEGOLM_STALE_CHECK_TTL_MS]
     *  instead of running before every send. */
    private val encryptionFailCheck = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** The FAIL reason that implicates the outbound session:
     * `com.beeper.undecryptable_event` on every Anni send
     *  (device DB). Only this exact marker rotates; other FAIL reasons
     *  (delivery failures etc.) must not churn the session. */
    private val MEGOLM_STALE_KEYWORDS = listOf("undecryptable")

    /** Minimum gap between outbound-megolm rotations (heal re-arm, 2026-08-23). */
    private val MEGOLM_RE_ROTATE_MIN_MS = 60_000L

    private fun canRotateMegolm(roomKey: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = megolmRotatedRooms[roomKey] ?: 0L
        return now - last >= MEGOLM_RE_ROTATE_MIN_MS
    }

    /** Rotates a stale outbound megolm session (re-armed: at most every
     *  [MEGOLM_RE_ROTATE_MIN_MS] per room). Called before every send; the scan
     *  is TTL-cached, and a parse failure only logs — the send must never fail
     *  because of the check. */
    private suspend fun rotateStaleMegolmIfNeeded(c: MatrixClient, matrixRoomId: RoomId) {
        if (!canRotateMegolm(matrixRoomId.full)) return
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
     * froze the composer for seconds. The scan only
     * acts on FAILs the bridge already posted, so an async heal costs at most
     * one more failed send in an already-broken room; the verdict is
     * TTL-cached after the first scan, and [rotateStaleMegolmIfNeeded] is
     * otherwise a cheap map read.
     */
    private fun healStaleMegolmIfNeeded(c: MatrixClient, matrixRoomId: RoomId) {
        scope.launch { rotateStaleMegolmIfNeeded(c, matrixRoomId) }
    }

    /**
     * Pre-warms the megolm send path when a room is opened (Beeper's
     * prepareSendMessage): the first encrypted send in a room otherwise pays
     * the /members fetch + outbound-session creation + /keys/claim inline in
     * the outbox drain, and the just-tapped send waits seconds behind it.
     * Encrypting a throwaway payload runs exactly that path (member load,
     * session creation, key share) once per process per room, in the
     * background — the ciphertext is discarded. Plaintext rooms no-op fast
     * (encrypt returns null for them).
     */
    private val megolmWarmedRooms = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun warmRoomMegolm(roomId: String) {
        val c = client ?: return
        if (megolmWarmedRooms.putIfAbsent(roomId, true) != null) return
        val matrixRoomId = RoomId(roomId)
        scope.launch {
            runCatching {
                c.di.getAll<RoomEventEncryptionService>().forEach { service ->
                    runCatching {
                        service.encrypt(RoomMessageEventContent.TextBased.Text(body = ""), matrixRoomId)
                    }
                }
            }.onFailure { e ->
                android.util.Log.w(TAG, "megolm pre-warm failed for $roomId: ${e.message}")
            }
        }
    }

    /**
     * Rotation triggered directly by a bridge FAIL in the status walk
     * ([sendStatusByEventId], which runs on every newest-page build): once per
     * room per process run, fire-and-forget. The next send creates a fresh
     * session whose key goes to all currently-known devices — including the
     * bridge's current identity.
     */
    private fun rotateOnBridgeUndecryptable(c: MatrixClient, matrixRoomId: RoomId, reason: String) {
        if (!canRotateMegolm(matrixRoomId.full)) return
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
            // v5: outbound-megolm updates are transaction-bound (context(StoreWriteTransaction)).
            c.di.get<StoreTransactionManager>(StoreTransactionManager::class).writeTransaction {
                c.di.get<OlmCryptoStore>(OlmCryptoStore::class).updateOutboundMegolmSession(matrixRoomId) { null }
            }
        }.onSuccess {
            megolmRotatedRooms[matrixRoomId.full] = android.os.SystemClock.elapsedRealtime()
            android.util.Log.d(TAG, "rotated stale megolm session for $matrixRoomId (bridge FAIL: $reason)")
        }
    }

    suspend fun sendMessage(
        roomId: String,
        body: String,
        replyToEventId: String?,
    ): com.thelightphone.sdk.shared.LightServiceMethod.SendMessage.Response {
        try {
        val c = client ?: error("not logged in")
        val sendT0 = android.os.SystemClock.elapsedRealtime()
        Diagnostics.record("send ${Diagnostics.short(roomId)} start")
        val matrixRoomId = RoomId(roomId)
        // One-shot self-heal for the bridge-key bug window (see the
        // megolm section above) — before enqueueing, never per-send, and never
        // blocking: the first scan per room walks ~250 events and froze the
        // composer for seconds.
        healStaleMegolmIfNeeded(c, matrixRoomId)
        // No per-send megolm rotation: a fresh session per message made a burst
        // of sends race — a retried/late event encrypted with an older session
        // arrived after the newer session's room key, and Beeper flagged it
        // "sent using an outdated encryption session". The
        // rotation was a stopgap for the bridge-key bug; that root
        // cause (broken /keys/claim deserialization) is fixed, and Trixnity
        // re-shares the room key to new devices on every send with the existing
        // session, so the bridge keeps getting keys without per-message churn.
        // Markdown is always on for outgoing text (Beeper behavior); the
        // formatted variant is only produced when the text actually contains a
        // construct, so plain bodies pass through byte-for-byte. The pending
        // echo keeps the plain body until the real echo syncs.
        val (markdownBody, markdownHtml) = MarkdownConverter.toMatrixContent(body)
        val txnId = c.room.sendMessage(matrixRoomId) {
            if (replyToEventId != null) {
                // Relation set directly on the builder, NOT via Trixnity's
                // reply() helper: it re-fetches the target event and waits
                // indefinitely for decrypted content (firstWithContent) — an
                // undecryptable/missing target hung the send forever (LP3
                // feedback 2026-09-09). The lookup below is budgeted AND
                // best-effort: Trixnity's default config is
                // fetchTimeout/decryptionTimeout = INFINITE, and its
                // gap-fill/decrypt branches can throw — an escaping throw
                // aborted the whole send, so every reply failed with
                // "couldn't send" (LP3 feedback 2026-09-12). mentions are
                // optional; the reply itself always goes out.
                relatesTo = RelatesTo.Reply(RelatesTo.ReplyTo(EventId(replyToEventId)))
                runCatching {
                    c.room.getTimelineEvent(matrixRoomId, EventId(replyToEventId)) {
                        allowReplaceContent = false
                        fetchTimeout = FETCH_TIMEOUT_SECONDS.seconds
                        decryptionTimeout = FETCH_TIMEOUT_SECONDS.seconds
                    }.firstOrNull()
                }.getOrNull()?.let { replyEvent ->
                    mentions = Mentions(users = setOf(replyEvent.event.sender))
                }
            }
            if (markdownHtml != null) {
                text(
                    body = markdownBody,
                    format = "org.matrix.custom.html",
                    formattedBody = markdownHtml,
                )
            } else if (replyToEventId != null) {
                // A reply ALWAYS ships as format=html: Trixnity's builder
                // hardcodes that for RelatesTo.Reply and passes formattedBody
                // straight through, so a plain-text reply went out as
                // `format="org.matrix.custom.html"` with NO formatted_body — a
                // malformed content the Beeper bridge would not relay (LP3
                // feedback 2026-09-12: every reply failed while plain sends and
                // markdown-carrying replies went through). Give the forced HTML
                // format its matching body instead of letting the plain branch
                // emit the bare format.
                text(
                    body = body,
                    format = "org.matrix.custom.html",
                    formattedBody = MarkdownConverter.toPlainHtml(body),
                )
            } else {
                text(body = body)
            }
        }
        // Record the optimistic echo server-side — the row survives leaving
        // the thread, and the sync echo (matched by txn id) replaces it in
        // [computeMessagesPage].
        val roomPending = pendingTextEcho.computeIfAbsent(matrixRoomId.full) { java.util.concurrent.ConcurrentHashMap() }
        roomPending[txnId] = PendingTextSend(txnId, System.currentTimeMillis(), markdownBody, replyToEventId)
        // Keep the served store page — re-opening the thread serves it
        // instantly with the optimistic row injected ([injectPendingEchoes])
        // until the sync echo lands (the ingest writer's revision bump
        // re-serves the page with the real row).
        // Fetch the echo + refresh the panel even in slow-sync mode (screen off).
        // Fire-and-forget like Beeper's send worker: the composer RPC covers
        // only the outbox insert — the room-list bump, the wake round and the
        // ack watch all run in the background.
        scope.launch { wakeAfterSend(matrixRoomId.full) }
        // NO-SEAM: the thread no longer polls — it reacts to page
        // bumps. The send-time bump fires BEFORE the homeserver ack, so the row
        // sat "SENDING" until the sync echo's re-serve landed — starved for
        // ~95 s under the post-attach crawl. Watch the outbox row and bump at the
        // ack: the serve-time [pendingEchoRow] then renders the real event id
        // (sent) from the same served page — no rebuild needed.
        scope.launch {
            var lastAcked = false
            var lastKickAt = android.os.SystemClock.elapsedRealtime()
            repeat(120) {
                delay(250)
                val om = runCatching {
                    c.room.getOutbox(matrixRoomId, txnId).first()
                }.getOrNull() ?: return@launch
                val acked = om.eventId != null || om.sendError != null
                if (acked != lastAcked) {
                    lastAcked = acked
                    bumpMessagePageRevision(matrixRoomId.full)
                }
                if (acked) return@launch
                // Sync-stall kicker: a send that threw (offline blip) parks in
                // the outbox drain's retry sleep — 100ms*2^n capped at 5 min
                // while sync is errored/stopped, and the sleep only ends on a
                // sync-state change. A successful round sets state RUNNING
                // (SyncApiClient), which interrupts that sleep immediately, so
                // re-fire the send-wake round every 5 s while the ack is
                // missing. Battery saver + dark screen skips the send-wake
                // (see [wakeAfterSend]) — stay consistent there.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastKickAt >= SEND_KICK_INTERVAL_MS && (syncEnabled || isScreenInteractive())) {
                    lastKickAt = now
                    timedSyncOnce(c, "send-retry")
                        .onFailure { android.util.Log.w(TAG, "send-retry sync failed: ${it.message}") }
                }
            }
        }
        android.util.Log.d(TAG, "SendMessage: room=$roomId txn=$txnId body=$body")
        // No composer hold for the homeserver ack: the thread's pending-row
        // machinery (optimistic injection via bumpMessagePageRevision + the
        // pending override in the room list) renders the send instantly, and
        // the ack/sync echo confirm it in the background. Holding the RPC up
        // to SEND_ACK_WAIT_MS blocked the composer for seconds on a loaded DB
        // — Beeper's outbox
        // model renders the echo immediately, network ack invisibly later.
        // Event id is null here; the sync echo replaces the optimistic row
        // (matched by txn id) ~1-3 s later.
        return com.thelightphone.sdk.shared.LightServiceMethod.SendMessage.Response(
            transactionId = txnId,
            eventId = null,
        ).also {
            Diagnostics.record("send ${Diagnostics.short(roomId)} done ${android.os.SystemClock.elapsedRealtime() - sendT0}ms")
        }
        } catch (e: Exception) {
            // A send that dies before enqueueing used to be invisible: the RPC
            // failure maps to null on the tool side and the resend looked like
            // a no-op. Log the full stack so the
            // next resend attempt is diagnosable.
            android.util.Log.e(TAG, "SendMessage FAILED room=$roomId body=$body", e)
            Diagnostics.record("send ${Diagnostics.short(roomId)} failed ${Diagnostics.err(e)}")
            throw e
        }
    }

    /**
     * Waits (bounded) for Trixnity's outbox to record the homeserver ack of
     * [txnId]: the /send 200 sets the outbox row's event id while the sync
     * echo is still in flight (the outbox drain runs on the wake round from
     * [wakeAfterSend]). Returns the real event id, or null when the send
     * failed locally (the outbox row carries a send error) or no ack landed
     * within [SEND_ACK_WAIT_MS].
     */
    private suspend fun awaitOutboxAck(
        c: MatrixClient,
        matrixRoomId: RoomId,
        txnId: String,
    ): String? {
        val deadline = System.currentTimeMillis() + SEND_ACK_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val outbox = withTimeoutOrNull(OUTBOX_READ_TIMEOUT_MS) {
                c.room.getOutbox(matrixRoomId, txnId).first()
            }
            outbox?.eventId?.full?.let { return it }
            if (outbox?.sendError != null) return null
            delay(SEND_ACK_POLL_INTERVAL_MS)
        }
        return null
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

    /**
     * Sends a reaction: an m.reaction annotation on [eventId].
     * Reactions are NOT in [RoomService]'s MessageBuilder DSL —
     * the raw API call is the path (same shapes verified against Trixnity
     * 5.8.0). Throws on failure; the dispatch maps it to a tool-side error.
     */
    suspend fun sendReaction(roomId: String, eventId: String, key: String) {
        val c = client ?: error("not logged in")
        c.api.room.sendMessageEvent(
            RoomId(roomId),
            ReactionEventContent(relatesTo = RelatesTo.Annotation(EventId(eventId), key)),
        ).getOrThrow()
        // The reaction's tag lands when the ingest writer writes the side row
        // (its revision bump re-serves the thread); the label cache must drop
        // now, or the reload re-reads its own stale map.
        reactionCache.remove(roomId)
        bumpMessagePageRevision(roomId)
    }

    /**
     * Unsends the signed-in user's reaction with [key] on [eventId]: finds
     * the reaction event id(s) in the newest timeline window — covers
     * reactions sent from other clients too — and redacts each (a
     * re-reaction leaves several; the display dedupe keeps the earliest).
     * False when none of the user's is in the window (nothing to unsend).
     */
    suspend fun unsendReaction(roomId: String, eventId: String, key: String): Boolean {
        val c = client ?: return false
        val matrixRoomId = RoomId(roomId)
        // Walk the RAW chain (like [reactionLabelsByEvent] and the page build),
        // not the handler's getLastTimelineEvents view: that view can serve a
        // partial subset — a reaction sent from another device (Beeper) wasn't
        // in it, so the unsend found nothing, the RPC failed and the like
        // "didn't go". Reactions are plaintext, so
        // the fast walk is enough.
        val start = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).firstOrNull()?.lastEventId?.full
        } ?: return false
        val window = collectRelevantTimelineEvents(
            c, matrixRoomId, start, SEND_STATUS_WINDOW, fast = true,
        ).first
        // Reaction keys are compared VS16-normalized: bridges map native
        // reactions back with inconsistent variation selectors (a WhatsApp ❤
        // may round-trip as U+2764 while we sent U+2764+FE0F), and an exact
        // match then finds nothing to unsend.
        fun normalizeKey(k: String) = k.replace("\uFE0F", "")
        val wantKey = normalizeKey(key)
        val ownIds = window.mapNotNull { te ->
            val content = te.content?.getOrNull() ?: (te.event.content as? ReactionEventContent)
            if (content !is ReactionEventContent) return@mapNotNull null
            val relates = content.relatesTo as? RelatesTo.Annotation ?: return@mapNotNull null
            if (te.event.sender != c.userId) return@mapNotNull null
            if (relates.eventId.full != eventId ||
                normalizeKey(relates.key ?: return@mapNotNull null) != wantKey
            ) {
                return@mapNotNull null
            }
            te.event.id.full
        }
        if (ownIds.isEmpty()) return false
        ownIds.forEach { id ->
            c.api.room.redactEvent(matrixRoomId, EventId(id)).getOrThrow()
        }
        reactionCache.remove(roomId)
        bumpMessagePageRevision(roomId)
        return true
    }

    /**
     * Edits an own text message: an m.replace edit —
     * sent through the SAME encrypted outbox DSL as [sendMessage], via the
     * canonical [replace] + [text] builders. The raw plaintext API this used
     * before is rejected by Beeper's homeserver in bridged rooms ("cloud
     * bridges aren't allowed to send unencrypted messages").
     * [text] routes through [roomMessageBuilder], the ONLY DSL path that
     * actually attaches the relation: a bare `content(Text(...))` +
     * `relatesTo =...` silently DROPS the relation (the `content` lambda
     * ignores the builder's ContentBuilderInfo), producing a relation-less
     * "* <text>" message — the bridge then posts it to Instagram as a NEW
     * message and the original never edits.
     * [text] composes the spec's "* " fallback body plus
     * RelatesTo.Replace(eventId, m.new_content) itself; the Megolm encryptor
     * then hoists rel_type/event_id outside the ciphertext so the bridge can
     * aggregate without decrypting. Throws on failure; the dispatch maps it
     * to a tool-side error, which the composer now displays.
     */
    suspend fun editMessage(roomId: String, eventId: String, newBody: String) {
        if (newBody.isBlank()) error("empty edit body")
        val c = client ?: error("not logged in")
        val matrixRoomId = RoomId(roomId)
        // The user edits the plain text they see; markdown they type
        // re-formats (same conversion as [sendMessage]).
        val (markdownBody, markdownHtml) = MarkdownConverter.toMatrixContent(newBody)
        val txnId = c.room.sendMessage(matrixRoomId) {
            replace(EventId(eventId))
            if (markdownHtml != null) {
                text(
                    body = markdownBody,
                    format = "org.matrix.custom.html",
                    formattedBody = markdownHtml,
                )
            } else {
                text(newBody)
            }
        }
        // No ack hold (matching sendMessage): the edit is already enqueued —
        // the 500 ms wait timed out unconfirmed under crawl load and the throw
        // showed the user "failed" for an edit that landed seconds later.
        // Failures surface as the edit never appearing (the
        // outbox keeps retrying); the echo applies it on the ingest writer's
        // re-serve.
        wakeAfterSend(matrixRoomId.full)
    }

    /**
     * Unsends an own message for everyone: a plain
     * Matrix redaction — the identical call [unsendReaction] uses, pointed at
     * the message event instead of a reaction. The redaction lands through
     * the ingest writer (a side row over the target), whose revision bump
     * re-serves the thread with the tombstone. Throws on failure; the
     * dispatch maps it to a tool-side error.
     */
    suspend fun unsendMessage(roomId: String, eventId: String) {
        val c = client ?: error("not logged in")
        // The marker goes down BEFORE the redaction call: the re-serve this
        // triggers can beat the redaction's sync echo into the store, and the
        // tombstone must survive both orderings (e2ee rooms can't recognize a
        // redacted message by type — see [unsentMessageIds]).
        rememberUnsentMessage(eventId)
        c.api.room.redactEvent(RoomId(roomId), EventId(eventId)).getOrThrow()
        bumpMessagePageRevision(roomId)
    }

    // --- Photos --------------------------------------------------

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
        // One-shot self-heal for the bridge-key bug window (see the
        // megolm section above) — before enqueueing, never blocking (first
        // scan per room is a ~250-event walk; async, LP3 ).
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
        // Optimistic row: show the photo (file name + SENDING) immediately,
        // before the sync echo lands. Same pattern as
        // [sendMessage]; the echo (matched by txn id) replaces it.
        val roomPending = pendingImageEcho.computeIfAbsent(matrixRoomId.full) { java.util.concurrent.ConcurrentHashMap() }
        roomPending[txnId] = PendingImageSend(txnId, System.currentTimeMillis(), payload.fileName)
        wakeAfterSend(matrixRoomId.full)
        return true
    }

    // --- Media HTTP self-heal ----------------------------------
    // LP3: the newest voice notes in a room stopped playing — every tap timed
    // out at MEDIA_BUDGET_MS while /sync (a long-lived request on the SAME
    // shared engine) kept delivering and host/device curl fetched the same
    // files in <1s. New requests starved while already-running ones survived:
    // an in-process wedge in the shared OkHttp engine. Only a process restart
    // cleared it. These counters + rebuild make the stack self-heal instead.

    /** Consecutive media download timeouts (voice notes and images) while the
     *  network is up. */
    @Volatile private var consecutiveMediaStalls = 0
    /** A self-heal is armed/pending — new plays wait for it before fetching. */
    @Volatile private var mediaStackSick = false
    @Volatile private var mediaHealInFlight = false
    @Volatile private var lastMediaHealAtMs = 0L

    /** A media fetch (voice note or image) timed out: count it, and heal the
     *  HTTP stack once the pattern (consecutive timeouts, network up) says it
     *  is wedged. */
    private fun noteMediaFetchTimeout() {
        if (!networkIsUp()) return
        val stalls = ++consecutiveMediaStalls
        if (stalls < MEDIA_STALL_HEAL_THRESHOLD || mediaHealInFlight) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMediaHealAtMs < MEDIA_STALL_HEAL_MIN_INTERVAL_MS) return
        lastMediaHealAtMs = now
        mediaStackSick = true
        android.util.Log.w(
            TAG,
            "media stack wedged: $stalls consecutive download timeouts with the network up — self-healing the HTTP stack",
        )
        scope.launch { runCatching { selfHealHttpStack() } }
    }

    /** A fetch completed (success or fast failure) — the stack is responsive. */
    private fun noteMediaFetchSuccess() {
        consecutiveMediaStalls = 0
    }

    /** Whether a validated default network exists (heal gate — a genuinely
     *  dead/slow link must not look like an engine wedge). */
    private fun networkIsUp(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /**
     * In-process restart-equivalent for the HTTP stack: stops the current
     * client, builds a FRESH OkHttp engine, restores the session from the Room
     * store (the same path a process boot takes — no fresh auth needed), and
     * re-arms sync per screen state. Runs only after
     * [MEDIA_STALL_HEAL_THRESHOLD] consecutive media timeouts with the network
     * up, so rebuild churn on slow links is bounded by the cooldown. Session
     * state (room list, e2ee cache, pending sends) is deliberately kept — the
     * store is untouched, only the client/engine are replaced.
     */
    private suspend fun selfHealHttpStack() {
        val ctx = appContext ?: return
        if (!mediaHealInFlight) initMutex.withLock {
            if (mediaHealInFlight) return@withLock
            val old = client
            if (old == null) return@withLock
            mediaHealInFlight = true
            try {
                runCatching { old.stopSync() }
                inProcessSyncJob?.cancel()
                inProcessSyncJob = null
                inProcessSyncRunning = false
                slowSyncJob?.cancel()
                slowSyncJob = null
                screenOffJob?.cancel()
                screenOffJob = null
                syncMode = SyncMode.ACTIVE
                PushChannel.stop()
                // A fresh engine: the old one is the wedged layer, and every
                // ktor client captures the engine at MatrixClient.create time
                // (see [clientConfiguration]), so new clients must be built
                // against the new engine.
                httpClientEngine = buildHttpClientEngine()
                val restored = runCatching {
                    MatrixClient.create(
                        repositoriesModule = RepositoriesModule.room(databaseBuilder(ctx)),
                        mediaStoreModule = MediaStoreModule.okio(mediaDir(ctx)),
                        cryptoDriverModule = CryptoDriverModule.libOlm(),
                        authProviderData = null, // restore from the store
                        configuration = clientConfiguration("chats"),
                    ).getOrThrow()
                }.onFailure { e ->
                    android.util.Log.w(TAG, "self-heal: session restore failed: $e")
                }.getOrNull()
                if (restored == null) {
                    // Restore failed — keep the old client; it may still serve.
                    // (The store row is intact, so a later ensureClient / boot
                    // restore succeeds.) The engine var already points at a
                    // fresh engine for that next create.
                    return@withLock
                }
                client = restored
                observeClient(restored)
                // Close the wedged stack now that its replacement is live
                // (bounded: closing an engine with stalled in-flight calls
                // must not hang the heal and hold initMutex).
                withTimeoutOrNull(10_000L) { runCatching { old.closeSuspending() } }
                android.util.Log.w(TAG, "self-heal: HTTP stack rebuilt for ${restored.userId.full}")
                if (syncEnabled) PushChannel.start(ctx, restored)
                // Re-kick the service on the new client REGARDLESS of the
                // sync toggle: the FGS keep-alive survived the heal, but its
                // sync loop AND watchdog belong to the closed old client —
                // enterActiveSync's guard trusts ChatSyncService.isRunning
                // and would bail, leaving no long-poll and no push wakes
                // (onPushDelivered skips in ACTIVE mode) → a stuck
                // "Can't reach server" banner (seen 2026-09-08 under
                // battery saver: the old syncEnabled gate skipped the
                // re-kick, but toggle off now means only "no sync while
                // dark" — the service's own onStartCommand guard applies
                // the dark/battery-saver policy).
                runCatching {
                    ctx.startForegroundService(Intent(ctx, ChatSyncService::class.java))
                }.onFailure { applySyncModeForScreenState() }
            } finally {
                mediaHealInFlight = false
                mediaStackSick = false
            }
        }
    }

    // --- Voice notes ---------------------------------------------

    /** The [EncryptedFile, mxc url] fetch source for file-based message
     *  content, or null when the event carries no fetchable media uri (the
     *  common failure mode: a client posts an m.image with an empty url). */
    private fun mediaSourceOf(
        content: RoomMessageEventContent.FileBased,
    ): Pair<EncryptedFile?, String?>? {
        val file = content.file?.takeIf { !it.url.isNullOrBlank() }
        val url = content.url?.takeIf { it.isNotBlank() }
        return if (file == null && url == null) null else file to url
    }

    /** The shared single-shot media fetch (encrypted when the timeline
     *  carries an [EncryptedFile], plain otherwise), bounded by
     *  [MEDIA_BUDGET_MS]; a failure is logged under [logLabel]. Wears the same
     *  wedge self-heal hooks as [fetchMediaRetrying]: a timeout means the shared
     *  HTTP engine may be wedged. The image path never fed the stall counter
     *  before, so a wedged stack could only be cleared by a process restart
     *  (LP3 feedback 2026-09-13 — the #32 "not loading photos" lead). A fast
     *  failure still counts as a responsive stack, matching the voice path. */
    private suspend fun fetchMedia(
        c: MatrixClient,
        file: EncryptedFile?,
        url: String?,
        saveToCache: Boolean,
        logLabel: String,
        eventId: String,
    ): ByteArray? {
        val encFile = file
        val mediaUrl = url
        if (encFile == null && mediaUrl == null) return null
        val result = withTimeoutOrNull(MEDIA_BUDGET_MS) {
            val mediaService = c.di.get<MediaService>(MediaService::class)
            when {
                encFile != null -> mediaService.getEncryptedMedia(encFile, maxSize = null, saveToCache = saveToCache)
                mediaUrl != null -> mediaService.getMedia(mediaUrl, maxSize = null, saveToCache = saveToCache)
                else -> null
            }
        }
        if (result == null) {
            noteMediaFetchTimeout()
            android.util.Log.w(TAG, "$logLabel: fetch timed out for $eventId after ${MEDIA_BUDGET_MS}ms")
            return null
        }
        if (result.isFailure) {
            noteMediaFetchSuccess()
            android.util.Log.w(TAG, "$logLabel: fetch failed for $eventId", result.exceptionOrNull())
            return null
        }
        noteMediaFetchSuccess()
        return result.getOrNull()?.toByteArray()
    }

    /** [playVoiceNote]/[downloadVoiceNoteToCache]'s two-attempt fetch with the
     *  wedge self-heal hooks ([noteMediaFetchTimeout]/[noteMediaFetchSuccess]);
     *  the per-attempt logging stays at the call sites. Null bytes when both
     *  attempts fail. */
    private suspend fun fetchMediaRetrying(
        c: MatrixClient,
        file: EncryptedFile?,
        url: String?,
        eventId: String,
        timeoutLog: (attempt: Int) -> Unit = {},
        failureLog: (attempt: Int, exception: Throwable?) -> Unit,
    ): ByteArray? {
        val mediaService = c.di.get<MediaService>(MediaService::class)
        var download: Result<de.connect2x.trixnity.client.media.PlatformMedia>? = null
        for (attempt in 1..2) {
            val result = withTimeoutOrNull(MEDIA_BUDGET_MS) {
                when {
                    file != null -> mediaService.getEncryptedMedia(file, maxSize = null, saveToCache = false)
                    url != null -> mediaService.getMedia(url, maxSize = null, saveToCache = false)
                    else -> return@withTimeoutOrNull null
                }
            }
            if (result == null) {
                timeoutLog(attempt)
                noteMediaFetchTimeout()
                continue
            }
            if (result.isFailure) {
                failureLog(attempt, result.exceptionOrNull())
                noteMediaFetchSuccess()
                continue
            }
            noteMediaFetchSuccess()
            download = result
            break
        }
        return download?.getOrNull()?.toByteArray()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Toggles playback of an m.audio message: stops any current playback and
     * plays [eventId], or stops it if it is already the one playing. Downloads
     * the audio (decrypting when the room is encrypted — the timeline content
     * carries the EncryptedFile) to a cache file and plays it with a plain
     * [android.media.MediaPlayer]; playback lives in the companion because the
     * tool runtime forbids media APIs. @return (playing, error).
     */
    suspend fun playVoiceNote(roomId: String, eventId: String): Pair<Boolean, String?> {
        var c = client ?: return false to "not logged in"
        // Tap the playing row again → PAUSE (keeps the position; the next tap
        // on the same row resumes from there).
        if (playingAudioEventId == eventId) {
            runCatching { audioPlayer?.pause() }
            playingAudioEventId = null
            pausedAudioEventId = eventId
            android.util.Log.d(TAG, "playVoiceNote: paused $eventId")
            return false to null
        }
        // Tap the PAUSED row again → RESUME from the pause point (no re-download,
        // no position reset).
        if (pausedAudioEventId == eventId && audioPlayer != null) {
            runCatching { audioPlayer?.start() }
            pausedAudioEventId = null
            playingAudioEventId = eventId
            android.util.Log.d(TAG, "playVoiceNote: resumed $eventId")
            return true to null
        }
        stopAudioPlayback()
        // A still-pending send: the echoed event isn't in the store yet, but
        // the server kept a copy of the recorded file when the note was sent
        // ([sendVoiceNote]) — play that instead of resolving by event id.
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
                        ctx, roomId, eventId, tmp,
                        successDetail = "playing local pending audio (id=$eventId, ${tmp.length()} bytes)",
                        failureDetail = "for local pending audio (id=$eventId, ${tmp.length()} bytes)",
                    )
                }
            }
            // Missing copy → fall through to the store path's error handling.
        }
        // A previously-downloaded note plays from the cache — no network, no
        // "failed to download" on a note that played before.
        val ctx = appContext ?: return false to "no context"
        voiceCacheFile(eventId)?.let { cached ->
            if (cached.exists()) {
                val tmp = java.io.File(ctx.cacheDir, "voice_play_$eventId")
                if (runCatching { cached.copyTo(tmp, overwrite = true) }.isSuccess) {
                    android.util.Log.d(TAG, "playVoiceNote: playing cached audio (id=$eventId)")
                    return playLocalAudioFile(
                        ctx, roomId, eventId, tmp,
                        successDetail = "playing cached audio (id=$eventId, ${tmp.length()} bytes)",
                        failureDetail = "for cached audio (id=$eventId)",
                    )
                }
            }
        }
        // A media-stack self-heal is in flight (consecutive download timeouts
        // while the network is up —): wait for it so THIS tap uses
        // the fresh engine instead of timing out again.
        if (mediaStackSick) {
            val deadline = android.os.SystemClock.elapsedRealtime() + MEDIA_HEAL_WAIT_MS
            while (mediaStackSick && android.os.SystemClock.elapsedRealtime() < deadline) delay(100)
            if (!mediaStackSick) {
                android.util.Log.d(TAG, "playVoiceNote: waited out the HTTP self-heal (id=$eventId)")
            }
            // The heal replaced the client mid-wait — fetch on the fresh one
            // (the pre-wait reference belongs to the closed stack).
            val current = client
            if (current != null && current !== c) c = current
        }
        val matrixRoomId = RoomId(roomId)
        val te = resolvedTimelineEvent(c, matrixRoomId, eventId) { event ->
            // An older note's megolm session is often not in the local
            // store (sessions load lazily per room, mostly via getMessages)
            // — the content stays encrypted and the note silently "doesn't
            // play". Pull the session from the key
            // backup, then re-read so decryption can land. Retried on every
            // iteration (unless parked): a session the backup index hadn't
            // caught up with on the first try may be there a moment later.
            // EXPLICIT play bypasses the futile-restore park: a
            // freshly-arrived note whose session isn't cached yet would
            // otherwise be unrecoverable for 4h. Parking
            // still fires on failure, so repeated taps on a genuinely
            // undecryptable note keep backing off (other paths honor it).
            if (event?.content?.isFailure == true) {
                if (restoreRoomSessions(c, matrixRoomId, listOf(event)) == 0) {
                    decryptRestoreCooldown.park(matrixRoomId.full, DECRYPT_RESTORE_COOLDOWN_MS)
                }
            }
        }
        val content = te?.content?.getOrNull()
        if (content == null && te?.content?.isFailure == true) {
            // An UNDECRYPTABLE note is not a non-audio row — the session
            // restore above ran; say what actually happened so a re-tap after
            // the session lands can play it.
            android.util.Log.d(TAG, "playVoiceNote: undecryptable, restoring sessions (id=$eventId)")
            return false to "Couldn't play — couldn't decrypt audio"
        }
        if (content !is RoomMessageEventContent.FileBased.Audio) {
            // Diagnosis hook for the LP3: which content
            // type is actually on the timeline when the row reads as a voice
            // note — a bridge sending a different type would land here.
            android.util.Log.w(
                TAG,
                "playVoiceNote: content is ${content?.javaClass?.simpleName ?: "null"}, not FileBased.Audio",
            )
            return false to "not an audio message"
        }
        val (file, url) = mediaSourceOf(content) ?: return false to "no audio file"
        // One retry: a single flaky fetch failing once shouldn't fail playback
        // outright — the first attempt can hit a slow window.
        // Each attempt is logged separately so a silent tap maps to one cause.
        val bytes = fetchMediaRetrying(
            c, file, url, eventId,
            timeoutLog = { attempt ->
                // withTimeoutOrNull fired — the fetch itself exceeded the
                // budget (MEDIA_BUDGET_MS). Kept separate from the failure log
                // so a silent tap maps to exactly one cause.
                android.util.Log.w(
                    TAG,
                    "playVoiceNote: download timed out for $eventId after ${MEDIA_BUDGET_MS}ms " +
                        "(attempt $attempt/2, encrypted=${file != null}, url=${url ?: "null"})",
                )
                // A timeout is the wedge signature: count it and
                // self-heal once consecutive stalls + a healthy network say the
                // shared HTTP engine is stuck (see [noteMediaFetchTimeout]).
            },
            failureLog = { attempt, exception ->
                // Diagnosis hook for the LP3: this stage was silent — the common failure (the
                // media fetch) must log what actually went wrong (network
                // error, missing sha256 on the EncryptedFile →
                // MediaValidationException, …).
                android.util.Log.w(
                    TAG,
                    "playVoiceNote: download failed for $eventId (attempt $attempt/2, encrypted=${file != null}, " +
                        "url=${url ?: "null"}, size=${content.info?.size})",
                    exception,
                )
                // A fast failure proves the engine answers — not a wedge.
            },
        ) ?: return false to "audio download failed"
        // The temp file must carry the ACTUAL format: MediaPlayer's file-source
        // path uses the extension as an extractor hint, and Beeper/WhatsApp
        // audio files (ogg/opus, mp3, aac…) mislabeled ".m4a" fail to prepare.
        // Incoming notes often lack a usable mimetype (our own sends always set
        // "audio/ogg; codecs=opus", so only THEY played — ),
        // so sniff the container from the magic bytes and fall back to the
        // mimetype label.
        val mime = content.info?.mimeType?.lowercase().orEmpty()
        val ext = sniffAudioExtension(bytes, mime)
        // WhatsApp/bridge quirk: the identification page is written with
        // header-type 0x02 (continuation) instead of 0x01 (BOS) — WhatsApp's
        // own decoder ignores the flag, but Android's OggExtractor requires
        // BOS to identify the codec, so prepare fails on the LP3's stricter
        // media stack. Repair before writing so MediaPlayer never sees the
        // broken stream.
        val repaired = repairOgg(bytes)
        if (repaired !== bytes) {
            android.util.Log.w(
                TAG,
                "repairOgg: first page missing BOS — fixed (head=${repaired.take(8).joinToString("") { "%02x".format(it) }})",
            )
        }
        val playBytes = repaired
        // Cache the downloaded note on disk so re-plays are instant and survive
        // a bad network.
        voiceCacheDir()?.let { dir ->
            val cacheFile = java.io.File(dir, "voice_$eventId.$ext")
            runCatching { cacheFile.writeBytes(playBytes) }
            trimVoiceCache(dir)
        }
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
            ctx, roomId, eventId, tmp,
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
     * cache dir, so a path source hits "Permission denied" on the LP3.
     * The file is deleted on stop/failure (see
     * [stopAudioPlayback]). @return (playing, error).
     */
    private fun playLocalAudioFile(
        ctx: Context,
        roomId: String,
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
        // notes to a stream the volume buttons don't touch.
        val mediaAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        player.setAudioAttributes(mediaAttributes)
        player.setOnCompletionListener {
            // Natural end: release the player but KEEP the audio focus, so the
            // app we transiently paused (a podcast, music) stays paused — a
            // finishing note must not resume it. Then
            // auto-play the next voice note in the room when there is one
            // immediately after.
            val finishedId = playingAudioEventId
            val finishedRoom = playingAudioRoomId
            releaseFinishedPlayback()
            if (finishedId != null && finishedRoom != null) {
                scope.launch { autoPlayNextAudio(finishedRoom, finishedId) }
            }
        }
        player.setOnErrorListener { _, _, _ -> stopAudioPlayback(); true }
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val focusRequest = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )
            .setAudioAttributes(mediaAttributes)
            .setOnAudioFocusChangeListener { change ->
                // Focus loss (another app / a call) stops the note; the row
                // state follows via the poll's audioPlayingEventId. A PAUSED
                // note survives the loss untouched (the recorder's own focus
                // take must not kill its pause point). The held focus is KEPT
                // on a loss-stop — abandoning it here would hand GAIN down
                // the stack to the app we had paused, which auto-resumes on
                // GAIN (feedback 2026-09-08: recording a voice note restarted
                // paused background audio). Same contract as natural
                // completion: the next [stopAudioPlayback] releases it.
                if (change != android.media.AudioManager.AUDIOFOCUS_GAIN) {
                    mainHandler.post {
                        if (playingAudioEventId != null) stopAudioPlayback(releaseFocus = false)
                    }
                }
            }
            .build()
        audioManager.requestAudioFocus(focusRequest)
        audioFocusRequest = focusRequest
        runCatching {
            // Pass the FILE DESCRIPTOR, not the path: the media server is a
            // different uid and can't traverse the app's private cache dir —
            // a path source hits "Permission denied" on the LP3 (verified:
            // FileSource 'Failed to open file … (Permission
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
            // Diagnosis hook for the LP3: the failing
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
        playingAudioRoomId = roomId
        playingAudioEventId = eventId
        // Record the measured length — the idle row shows it for bridged notes
        // whose m.audio event lacks info.duration (Signal).
        player.duration.takeIf { it > 0 }?.let { voiceDurationMsByEvent[eventId] = it.toLong() }
        android.util.Log.d(TAG, "playVoiceNote: $successDetail")
        return true to null
    }

    /**
     * Natural-completion cleanup: release the player + file + state, but KEEP
     * the audio focus held — the transient-focus pause must not bounce the
     * other app (podcast/music) back on when a note ends.
     * The focus is released by the next [stopAudioPlayback] (a new note, an
     * explicit stop, or another app taking focus — the focus-loss listener
     * calls [stopAudioPlayback]).
     */
    private fun releaseFinishedPlayback() {
        stopAudioPlayback(releaseFocus = false)
    }

    /**
     * Container extension for a downloaded voice note: sniffed from the magic
     * bytes first (bridged audio is often mislabeled or carries no mimetype),
     * the mimetype label as the fallback. MediaPlayer's file-source path uses
     * the extension as its extractor hint, so the right one matters — a real
     * ogg named ".m4a" fails prepare.
     */
    private fun sniffAudioExtension(bytes: ByteArray, mime: String): String {
        fun has(s: String, at: Int) = bytes.size >= at + s.length &&
            s.indices.all { i -> bytes[at + i] == s[i].code.toByte() }
        return when {
            has("OggS", 0) -> "ogg"
            has("fLaC", 0) -> "flac"
            has("ID3", 0) -> "mp3"
            // Matroska/WebM (EBML 1A 45 DF A3) — some bridges serve voice
            // notes as webm/opus.
            bytes.size >= 4 && bytes[0].toInt() == 0x1A && bytes[1].toInt() == 0x45 &&
                bytes[2].toInt() == 0xDF && bytes[3].toInt() == 0xA3 -> "webm"
            // ADTS AAC (sync 0xFFF, layer bits 00) — must be checked BEFORE
            // the MPEG sync test below, which would mislabel it ".mp3" and
            // fail prepare.
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
            // the mislabel that broke playback on the ).
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
     * 0x04 = EOS.
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

    /** Stops any in-flight voice-note playback and clears its state. The audio
     *  focus is kept when [releaseFocus] is false (natural completion — see
     *  [releaseFinishedPlayback]). */
    private fun stopAudioPlayback(releaseFocus: Boolean = true) {
        playingAudioEventId = null
        pausedAudioEventId = null
        playingAudioRoomId = null
        runCatching { audioPlayer?.stop() }
        runCatching { audioPlayer?.release() }
        audioPlayer = null
        audioPlayerFile?.delete()
        audioPlayerFile = null
        if (!releaseFocus) return
        audioFocusRequest?.let { focus ->
            runCatching {
                (appContext?.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                    .abandonAudioFocusRequest(focus)
            }
            audioFocusRequest = null
        }
    }

    // --- Voice-note download cache + auto-advance -----
    // Notes are kept on disk after their first download so re-plays are
    // instant and survive a bad network ("played this morning, failed to
    // download now"); the newest notes of an opened thread prefetch while its
    // page builds, so the first tap usually plays without a fetch. A note
    // that ENDS auto-plays the next note in the same room when one follows
    // immediately (the "multiple voice notes one after another" flow).

    /** Bounded voice-note cache dir (eventId-keyed files, LRU by mtime). */
    private fun voiceCacheDir(): java.io.File? =
        appContext?.let { java.io.File(it.cacheDir, "voice_cache").apply { mkdirs() } }

    /** The cached file for [eventId], or null when the cache dir can't exist.
     *  The extension is unknown until the first download, so a file that
     *  exists is matched by prefix. */
    private fun voiceCacheFile(eventId: String): java.io.File? =
        voiceCacheDir()?.let { dir ->
            dir.listFiles()?.firstOrNull { it.name == "voice_$eventId" || it.name.startsWith("voice_${eventId}.") }
        }

    /** Drops the oldest cached notes past [VOICE_CACHE_MAX_FILES]. */
    private fun trimVoiceCache(dir: java.io.File) {
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(VOICE_CACHE_MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }

    /**
     * Downloads [eventId]'s audio into the on-disk cache and returns the
     * cached file. Shares [playVoiceNote]'s fetch tail via
     * [fetchMediaRetrying] so the prefetch and the first-tap path match.
     */
    private suspend fun downloadVoiceNoteToCache(
        c: MatrixClient,
        matrixRoomId: RoomId,
        eventId: String,
        content: RoomMessageEventContent.FileBased.Audio,
    ): java.io.File? {
        val ctx = appContext ?: return null
        val (file, url) = mediaSourceOf(content) ?: return null
        val bytes = fetchMediaRetrying(
            c, file, url, eventId,
            failureLog = { attempt, exception ->
                android.util.Log.w(
                    TAG,
                    "voice download failed for $eventId (attempt $attempt/2)",
                    exception,
                )
            },
        ) ?: return null
        val repaired = repairOgg(bytes)
        val dir = voiceCacheDir() ?: return null
        val ext = sniffAudioExtension(repaired, content.info?.mimeType?.lowercase().orEmpty())
        val cacheFile = java.io.File(
            dir,
            if (ext.isEmpty()) "voice_$eventId" else "voice_$eventId.$ext",
        )
        runCatching { cacheFile.writeBytes(repaired) }.getOrElse { return null }
        trimVoiceCache(dir)
        return cacheFile
    }

    /** Prefetch set — one download per event id per process run. */
    private val voicePrefetchInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Measured lengths (ms) of voice notes whose m.audio event carries no
     *  info.duration (bridged notes — Signal sends none; ).
     *  Filled by the prefetch/play paths; [messageFrom] falls back to it so the
     *  idle row shows the length instead of the "Voice note" body text. */
    private val voiceDurationMsByEvent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Starts background downloads of the newest uncached audio notes in
     * [events] (newest-first), so tapping play on a fresh note usually hits
     * the cache. Called from the page
     * builds; the exists/in-flight guards make repeated calls no-ops.
     */
    private fun prefetchVoiceNotes(c: MatrixClient, matrixRoomId: RoomId, events: List<TimelineEvent>) {
        val ctx = appContext ?: return
        for (te in events.asSequence()
            .filter { it.content?.getOrNull() is RoomMessageEventContent.FileBased.Audio }
            .take(VOICE_PREFETCH_COUNT)
        ) {
            val eventId = te.event.id.full
            val content = te.content?.getOrNull() as? RoomMessageEventContent.FileBased.Audio ?: continue
            if (!voicePrefetchInFlight.add(eventId)) continue
            scope.launch {
                try {
                    val cached = voiceCacheFile(eventId)
                    val file = cached?.takeIf { it.exists() }
                        ?: downloadVoiceNoteToCache(c, matrixRoomId, eventId, content)
                    // Length probe for bridged notes without info.duration
                    // (Signal — ): one prepare per note per
                    // process; the map hit skips it on later page builds.
                    if (file != null && !voiceDurationMsByEvent.containsKey(eventId)) {
                        probeVoiceDurationMs(file)?.let { voiceDurationMsByEvent[eventId] = it }
                    }
                } finally {
                    voicePrefetchInFlight.remove(eventId)
                }
            }
        }
    }

    /** Measures a cached voice note's length with a throwaway MediaPlayer (fd
     *  data source — the media server can't traverse the app-private cache dir
     *  by path, same constraint as playback). Null on a probe failure; the
     *  caller falls back to the body text. */
    private fun probeVoiceDurationMs(file: java.io.File): Long? {
        if (file.length() == 0L) return null
        file.setReadable(true, false)
        val player = android.media.MediaPlayer()
        return try {
            java.io.FileInputStream(file).use { input -> player.setDataSource(input.fd) }
            player.prepare()
            player.duration.takeIf { it > 0 }?.toLong()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "voice duration probe failed for ${file.name}", e)
            null
        } finally {
            runCatching { player.release() }
        }
    }

    /**
     * Auto-advance: after a note ends, play the NEXT audio note in the room
     * when one follows immediately. "Immediately"
     * = the first audio event newer than the finished one, within
     * [VOICE_AUTO_ADVANCE_WINDOW_MS] of it — a note hours later stays
     * unplayed.
     */
    private suspend fun autoPlayNextAudio(roomId: String, finishedEventId: String) {
        val c = client ?: return
        val matrixRoomId = RoomId(roomId)
        // The room's authoritative newest event, then the same store-backed
        // chain walk the message pages use. The previous getLastTimelineEvents
        // read serves the handler's partial in-memory view (see the
        // computeMessagesPage note), which silently dropped the following note
        // — no auto-play for two notes sent one after the other.
        val lastEventId = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).firstOrNull()?.lastEventId?.full
        } ?: return
        val events = collectRelevantTimelineEvents(
            c, matrixRoomId, lastEventId, VOICE_AUTO_ADVANCE_WINDOW,
        ).first
        // [events] is newest-first; the finished note's position splits it —
        // everything before it (indices < idx) is NEWER. Reversed, that is
        // chronological order: the first audio event there is the next note.
        val idx = events.indexOfFirst { it.event.id.full == finishedEventId }
        if (idx <= 0) return
        val finishedTs = events[idx].event.originTimestamp
        for (te in events.subList(0, idx).asReversed()) {
            if (te.content?.getOrNull() !is RoomMessageEventContent.FileBased.Audio) continue
            if (te.event.originTimestamp - finishedTs > VOICE_AUTO_ADVANCE_WINDOW_MS) return
            android.util.Log.d(TAG, "autoPlayNextAudio: $finishedEventId -> ${te.event.id.full}")
            playVoiceNote(roomId, te.event.id.full)
            return
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
     * present, otherwise WhatsApp shows a plain audio file. Trixnity's typed audio DSL has no extension slot, so the
     * upload is done here (same encrypted/plain split the DSL performs).
     * `audio/ogg; codecs=opus` is the MSC3245 canonical voice-message
     * mimetype — every Matrix client and the mautrix bridges treat it as a
     * voice message, and it is ~2-3× smaller than the old AAC/m4a notes.
     * @return true when the send was enqueued.
     */
    suspend fun sendVoiceNote(roomId: String, file: java.io.File): Boolean {
        val c = client ?: return false
        val matrixRoomId = RoomId(roomId)
        // One-shot self-heal for the bridge-key bug window (see the
        // megolm section above) — before enqueueing, never blocking (first
        // scan per room is a ~250-event walk; async, LP3 ).
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
            // after uploadMedia uploads the bytes. Trixnity's typed outbox
            // uploader does that upload + URI rewrite for the image/audio
            // DSL content, but this event is hand-built (the msc3245 voice
            // marker has no DSL slot), so the upload must happen here or the
            // note ships with an unreachable upload:// url and the media never
            // reaches the server — other clients see the note but can't play
            // it.
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
                // — voice notes carry no caption.
                content(content)
            }
        }.getOrNull() ?: return false
        android.util.Log.d(TAG, "SendVoiceNote: room=$roomId txn=$txnId bytes=${bytes.size} duration=${durationMs}ms voice=m.audio+msc3245")
        // Keep the served store page — re-opening serves it instantly
        // with the optimistic "Voice note" row injected ([injectPendingEchoes])
        // until the sync echo lands (the ingest writer's revision bump then
        // re-serves the page with the real event). The send previously dropped
        // the cache, so a re-open recomputed from scratch (slow — "Loading
        // messages…") and could show the note as missing.
        val roomPending = pendingAudioEcho.computeIfAbsent(matrixRoomId.full) { java.util.concurrent.ConcurrentHashMap() }
        // Keep a copy of the recorded file for the pending row: the activity
        // deletes the original as soon as this RPC returns, and the row must
        // stay playable until the sync echo replaces it. Best-effort: a failed copy
        // just leaves the pending row unplayable, the send is unaffected.
        val localFile = runCatching {
            java.io.File(appContext?.cacheDir ?: return@runCatching null, "voice_pending_$txnId.ogg")
                .also { file -> file.writeBytes(bytes) }
        }.getOrNull()
        roomPending[txnId] = PendingAudioSend(txnId, System.currentTimeMillis(), durationMs, localFile)
        wakeAfterSend(matrixRoomId.full)
        // Text sends resolve their row the moment the /send 200 ack lands (the
        // RPC returns the real event id and the composer swaps it in); a voice
        // send's row is served from the pending map, whose id fell back to
        // "local-…" (→ SENDING) once Trixnity removed the outbox row at echo
        // processing — and the active room's page isn't re-served on the echo,
        // so the row stuck until a re-entry. Cache the acked id on
        // the pending + bump the page: the next re-serve shows the row with its
        // real id (~1-2 s after send) instead of SENDING. Holds the RPC up to
        // [SEND_ACK_WAIT_MS] like [sendMessage] does (the recording activity
        // shows its "sending" state meanwhile).
        val ackedEventId = awaitOutboxAck(c, matrixRoomId, txnId)
        if (ackedEventId != null) {
            roomPending[txnId]?.let { roomPending[txnId] = it.copy(eventId = ackedEventId) }
            bumpMessagePageRevision(matrixRoomId.full)
        }
        return true
    }

    /**
     * Re-reads an event until its content resolves (the decrypt is async on
     * encrypted rooms — the first read can lag) or [MEDIA_CONTENT_RETRIES]
     * attempts pass, bounded by [MEDIA_BUDGET_MS]. [onStillEncrypted] runs per
     * unresolved attempt (playback uses it to pull the megolm session from the
     * key backup); the last (possibly unresolved) event is returned.
     */
    private suspend fun resolvedTimelineEvent(
        c: MatrixClient,
        matrixRoomId: RoomId,
        eventId: String,
        onStillEncrypted: suspend (TimelineEvent?) -> Unit = {},
    ): TimelineEvent? = withTimeoutOrNull(MEDIA_BUDGET_MS) {
        var event: TimelineEvent? = null
        repeat(MEDIA_CONTENT_RETRIES) {
            event = c.room.getTimelineEvent(matrixRoomId, EventId(eventId)).firstOrNull()
            if (event?.content?.getOrNull() != null) return@withTimeoutOrNull event
            onStillEncrypted(event)
            delay(MEDIA_CONTENT_RETRY_DELAY_MS)
        }
        event
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
        // A still-in-flight send ("local-…" pending row) has no real event yet
        // — nothing to fetch; the row keeps its file-name fallback until the
        // sync echo lands.
        if (eventId.startsWith(LOCAL_PENDING_ID_PREFIX)) return null
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
        val te = resolvedTimelineEvent(c, matrixRoomId, eventId)
        val content = when (val raw = te?.content?.getOrNull()) {
            // Beeper's RCS bridge sends direct photos as m.file with an image/*
            // mimetype instead of m.image — accept both,
            // or RCS image attachments stay on their text fallback forever.
            is RoomMessageEventContent.FileBased.Image -> raw
            // Video rows fetch a server-side thumbnail frame ([videoThumbnail] —
            // there's no playback, the SDK has no video primitive).
            is RoomMessageEventContent.FileBased.Video -> raw
            is RoomMessageEventContent.FileBased.File ->
                if (raw.info?.mimeType?.startsWith("image/", ignoreCase = true) == true) raw else null
            else -> null
        } ?: return null
        // Some clients post m.image events with an empty url (a failed upload,
        // or a bot artifact) — those have no media to fetch, ever. Blanking the
        // uri here keeps the fetch branch below from calling
        // MediaService.getMedia("") (IllegalArgumentException, which used to
        // fail the whole row instead of falling back to its text).
        val source = mediaSourceOf(content)
        if (source == null) {
            android.util.Log.d(
                TAG,
                "getMessageMedia: $eventId has no media uri " +
                    "(url=${content.url?.takeIf { it.isNotBlank() }}) — unfetchable",
            )
            return null
        }
        val (file, url) = source
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
        val bytes = fetchMedia(
            c, file, url,
            saveToCache = true,
            logLabel = "getMessageMedia",
            eventId = eventId,
        )?.takeIf { it.isNotEmpty() } ?: return null
        val mime = content.info?.mimeType?.lowercase()
        val display = when {
            // Videos: WhatsApp GIFs arrive as silent short mp4 loops (mautrix
            // bridges, no distinguishing flag) — those fetch as a frame
            // flipbook ([videoFlipbook]) the viewer animates; everything else
            // fetches as a JPEG thumbnail frame ([videoThumbnail]). The SDK
            // has no video primitive, so there's no real playback either way.
            mime?.startsWith("video/") == true -> videoFlipbook(bytes) ?: videoThumbnail(bytes)
            // GIFs keep their original bytes so the fullscreen viewer can
            // animate them (ImageDecoder); BitmapFactory renders the first
            // frame in the thread row. No JPEG recompress — it would freeze
            // the animation.
            mime == "image/gif" -> bytes
            else -> compressImage(bytes, DISPLAY_MAX_DIMENSION, DISPLAY_JPEG_QUALITY)
        } ?: return null
        mediaCache[cacheKey] = display
        return display
    }

    /**
     * Saves an image message's original bytes to the device's Pictures/Chats
     * album (photo viewer save button). The original is re-fetched
     * (Trixnity's media cache hits after a view) rather than saving the
     * viewer's downscaled display JPEG. App-contributed media needs no storage
     * permission on API 29+.
     */
    suspend fun saveMessageImage(roomId: String, eventId: String): Boolean {
        val c = client ?: return false
        if (eventId.startsWith(LOCAL_PENDING_ID_PREFIX)) return false
        val ctx = appContext ?: return false
        val matrixRoomId = RoomId(roomId)
        val te = resolvedTimelineEvent(c, matrixRoomId, eventId)
        val content = when (val raw = te?.content?.getOrNull()) {
            is RoomMessageEventContent.FileBased.Image -> raw
            is RoomMessageEventContent.FileBased.File ->
                if (raw.info?.mimeType?.startsWith("image/", ignoreCase = true) == true) raw else null
            else -> null
        } ?: return false
        val (file, url) = mediaSourceOf(content) ?: return false
        val bytes = fetchMedia(
            c, file, url,
            saveToCache = true,
            logLabel = "saveMessageImage",
            eventId = eventId,
        )?.takeIf { it.isNotEmpty() } ?: return false

        val mime = content.info?.mimeType ?: "image/jpeg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val name = "chats-" +
            eventId.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" +
            System.currentTimeMillis() + "." + ext
        val values = ContentValues().apply {
            put(AndroidMediaStore.Images.Media.DISPLAY_NAME, name)
            put(AndroidMediaStore.Images.Media.MIME_TYPE, mime)
            put(AndroidMediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Chats")
            put(AndroidMediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(AndroidMediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                resolver.delete(uri, null, null)
                return false
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "saveMessageImage: write failed for $eventId", e)
            runCatching { resolver.delete(uri, null, null) }
            return false
        } finally {
            values.clear()
            values.put(AndroidMediaStore.Images.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
        }
        return true
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

    /**
     * Frame flipbook for an animated-video attachment: WhatsApp GIFs arrive
     * as silent short mp4 loops (mautrix bridges) with no distinguishing
     * flag, so the heuristic is content-shaped — mp4, ≤15 s, no audio track
     * — and everything else falls back to the static [videoThumbnail].
     * Extracts ~10fps frames at [FLIPBOOK_MAX_DIMENSION], JPEGs them, and
     * packs the container the tool's [com.lightphone.chats.screens.chatsFlipbook]
     * parses: "FLIP" magic, version 0, frame count, ms/frame, then per frame
     * a 4-byte big-endian length + JPEG bytes.
     */
    private fun videoFlipbook(bytes: ByteArray): ByteArray? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mediaDataSourceOf(bytes))
            val durationMs = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            val hasAudio = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (hasAudio || durationMs !in 1..FLIPBOOK_MAX_DURATION_MS) return null
            val count = (durationMs / 100).toInt().coerceIn(2, FLIPBOOK_MAX_FRAMES)
            val frames = ArrayList<ByteArray>(count)
            val stepUs = durationMs * 1000 / count
            for (i in 0 until count) {
                val frame = retriever.getFrameAtTime(
                    i * stepUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                frames += scaledJpeg(frame, FLIPBOOK_MAX_DIMENSION)
            }
            if (frames.size < 2) return null
            val out = java.io.ByteArrayOutputStream()
            out.write("FLIP".toByteArray(Charsets.US_ASCII))
            out.write(0) // version
            out.write(frames.size)
            out.write((durationMs / frames.size).toInt().beBytes())
            frames.forEach { frame ->
                out.write(frame.size.beBytes())
                out.write(frame)
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun mediaDataSourceOf(bytes: ByteArray) = object : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = bytes.size.toLong()
        override fun close() {}
    }

    private fun scaledJpeg(bitmap: Bitmap, maxDimension: Int): ByteArray {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxDim > maxDimension) Bitmap.createScaledBitmap(
            bitmap,
            bitmap.width * maxDimension / maxDim,
            bitmap.height * maxDimension / maxDim,
            true,
        ) else bitmap
        val output = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, DISPLAY_JPEG_QUALITY, output)
        return output.toByteArray()
    }

    private fun Int.beBytes(): ByteArray = byteArrayOf(
        (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte(),
    )

    /**
     * First-frame thumbnail for a video attachment — the SDK has no video
     * primitive, so videos render as a still. [MediaMetadataRetriever] reads
     * the (already decrypted) bytes through an in-memory [android.media.MediaDataSource];
     * the frame downscales + JPEGs on the same [compressImage] budget.
     */
    private fun videoThumbnail(bytes: ByteArray): ByteArray? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mediaDataSourceOf(bytes))
            val frame = retriever.getFrameAtTime(0L) ?: return null
            val maxDim = maxOf(frame.width, frame.height)
            var sample = 1
            while (maxDim / (sample * 2) >= DISPLAY_MAX_DIMENSION) sample *= 2
            val scaled = if (sample > 1) Bitmap.createScaledBitmap(
                frame, frame.width / sample, frame.height / sample, true
            ) else frame
            val output = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, DISPLAY_JPEG_QUALITY, output)
            output.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    suspend fun markRead(roomId: String, eventId: String) {
        val c = client ?: run {
            android.util.Log.w(TAG, "markRead: room=$roomId — no client, dropped")
            return
        }
        val matrixRoomId = RoomId(roomId)
        // Mark at the event the tool asked for — the newest message it actually
        // rendered. The old behavior bumped the marker to the store's current
        // head so the badge always cleared, but the head can hold a message
        // that arrived between the page fetch and this call (or while the
        // thread sat open before the poll rendered it): the receiver never saw
        // it, yet their receipt covered it and the sender's "seen" tag landed
        // on it. A
        // behind-the-head marker leaves the room honestly unread — the
        // thread's quiet poll re-marks at the real newest once the page (and
        // the user's screen) catch up. Only a marker that IS the room's head
        // message keeps the optimistic badge clear below.
        var room = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).firstOrNull()
        }
        // Cold-start race: getById can return the room before Trixnity has
        // loaded its timeline view — lastRelevantEventId null. Give the
        // head a short bounded window to resolve before deciding.
        var headResolveWaits = 0
        while (room?.lastRelevantEventId == null &&
            headResolveWaits < HEAD_RESOLVE_RETRIES
        ) {
            kotlinx.coroutines.delay(HEAD_RESOLVE_RETRY_MS)
            room = withTimeoutOrNull(ROOM_BUDGET_MS) {
                c.room.getById(matrixRoomId).firstOrNull()
            }
            headResolveWaits++
        }
        val headId = room?.lastRelevantEventId
        var atHead = eventId == headId?.full
        var markerId = eventId
        // Head that never resolves at all: this room's Trixnity timeline view
        // doesn't load (Rewrites, — null even 18 s into a fresh
        // process, across restarts, while every other room resolves instantly).
        // The receipt still goes out at the rendered row; without the clear the
        // badge could never clear by design. Clear optimistically — the
        // projection row is zeroed below, so nothing newer is ever silently
        // marked seen.
        if (headId == null) {
            android.util.Log.w(
                TAG,
                "markRead: room=$matrixRoomId — head unresolved after $headResolveWaits retries; clearing badge optimistically",
            )
            atHead = true
        }
        // A head that can never render a row (blank re-import copy, edit,
        // still-young pending decrypt) pins the receipt one row behind the
        // head forever: the tool marks read at the newest rendered row and
        // the quiet poll never advances (the rendered id never changes),
        // while both our atHead check and Trixnity's notification clear need
        // an exact head match. Snap the receipt to the head in that case —
        // nothing rendered sits above the marker, so no message is marked
        // seen that the user could have read.
        if (!atHead && headId != null && headNeverRenders(c, matrixRoomId, headId)) {
            markerId = headId.full
            atHead = true
        }
        // Ungated on purpose: one line per thread open, and the debugLog flag
        // keeps being off exactly when this path needs debugging (LP3 2026-09-06).
        android.util.Log.d(
            TAG,
            "markRead: room=$matrixRoomId at=$eventId head=${headId?.full} marker=$markerId atHead=$atHead",
        )
        try {
            c.api.room.setReadMarkers(
                roomId = matrixRoomId,
                fullyRead = EventId(markerId),
                read = EventId(markerId),
            )
        } catch (e: Exception) {
            // The tool's runCatching would swallow this and the badge would
            // clear on an echo that never comes — skip the optimistic clear
            // (the projection row + the served list) so the badge honestly
            // stays up.
            if (debugLogging()) {
                android.util.Log.w(
                    TAG,
                    "markRead: room=$matrixRoomId — setReadMarkers failed, badge stays up: $e",
                )
            }
            return
        }
        // Opening the thread makes the room's notification moot.
        appContext?.let { ChatNotifier.cancelRoom(it, roomId) }
        recordReadMarker(roomId, markerId)
        projectionMarkRead(roomId)
        if (atHead) {
            // Optimistically clear the room's unread in the served list — the
            // notification count only drops after the read-marker echo
            // round-trips through sync (a full tick on a big account), which
            // used to leave the badge up long after the thread was opened.
            // The projection row was already zeroed above ([projectionMarkRead]);
            // the echo round's recompute confirms it.
            roomListCache[roomId]?.let { entry ->
                if (entry.room.unreadCount > 0) {
                    val cleared = entry.copy(room = entry.room.copy(unreadCount = 0))
                    roomListCache[roomId] = cleared
                    _roomList.value = _roomList.value.map { if (it.id == roomId) cleared.room else it }
                }
            }
            // Publish the optimistic clear through the choke point so the
            // tool's revision poll sees it now — the resolver doesn't sweep
            // (INGEST-DERIVED-PLAN Phase C); the receipt echo's per-row
            // publish re-derives the row.
            publishRoomList()
        }
    }

    /** True when the head event can never produce a rendered row (i.e.
     *  [messageFrom] returns null for it however often it is fetched):
     *  m.replace edits, blank-body re-import copies, and pending decryptions
     *  still inside the no-flash skip window. Used by [markRead] to decide
     *  whether the receipt may snap to the head (see the call site). */
    private suspend fun headNeverRenders(
        c: MatrixClient,
        matrixRoomId: RoomId,
        headId: EventId,
    ): Boolean {
        val te = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getTimelineEvent(matrixRoomId, headId).firstOrNull()
        } ?: return false
        // A JUST-arrived undecrypted message must not be snapped over: its
        // placeholder clears once it decrypts, and marking it read would hide
        // a message the user never saw. Older undecryptables fall through to
        // the render check below.
        val content = te.content?.getOrNull()
        if (content == null &&
            te.event.content is EncryptedMessageEventContent &&
            System.currentTimeMillis() - te.event.originTimestamp <=
            DECRYPT_PENDING_PLACEHOLDER_AFTER_MS
        ) return false
        // General rule: a head that produces no rendered row —
        // bridge delivery-status events, reactions, polls the tool can't
        // render, edits, blank re-import copies — can never receive the
        // receipt marker, so the badge stays up forever (LP3: the 1€ FILM
        // rooms' heads are reactions / an unrenderable poll). Snap the
        // receipt to the head instead. The sentinel distinguishes "renders
        // nothing" from a budget timeout (timeout = keep the old marker).
        val notRendered = Any()
        val row = runCatching {
            withTimeoutOrNull(ROOM_BUDGET_MS) { messageFrom(c, matrixRoomId, te) ?: notRendered }
        }.getOrNull()
        return row === notRendered
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

    /**
     * Pins or unpins a room (m.favourite tag, synced to Beeper):
     * pinned rooms sort to the top of the room list (recency among pins) and
     * their rows drop the latest timestamp. Optimistic — the flags cache
     * updates immediately, the sync echo confirms on the next rebuild.
     */
    suspend fun setRoomPinned(roomId: String, pinned: Boolean) {
        val c = client ?: return
        updateRoomFlagsLocal(roomId) { it.copy(pinned = pinned) }
        val result = if (pinned) {
            c.api.room.setTag(c.userId, RoomId(roomId), "m.favourite", TagEventContent.Tag(order = 1.0))
        } else {
            c.api.room.deleteTag(c.userId, RoomId(roomId), "m.favourite")
        }
        if (result.isFailure) {
            // The server rejected the write — don't leave the optimistic flip
            // as a phantom (Beeper's writes are slow; the failure surfaces
            // only after the PUT/GET, but the UI already flipped).
            updateRoomFlagsLocal(roomId) { it.copy(pinned = !pinned) }
            android.util.Log.w(TAG, "setRoomPinned: server rejected room=$roomId pinned=$pinned: ${result.exceptionOrNull()?.message}")
        } else {
            android.util.Log.d(TAG, "setRoomPinned: room=$roomId pinned=$pinned")
        }
    }

    /**
     * Mutes or unmutes a room's notifications (tool contact panel,
     * synced via Matrix push rules — a global ROOM
     * dont_notify rule per room, matching Beeper's own representation). The
     * room list and unread badge keep updating, only [notifyForEvent] is
     * gated. Optimistic like [setRoomPinned].
     */
    suspend fun setRoomMuted(roomId: String, muted: Boolean) {
        val c = client ?: return
        updateRoomFlagsLocal(roomId) { it.copy(muted = muted) }
        val result = if (muted) {
            c.api.push.setPushRule(
                "global", PushRuleKind.ROOM, roomId,
                SetPushRule.Request(actions = setOf(PushAction.Unknown("dont_notify", JsonPrimitive("dont_notify")))),
            )
        } else {
            c.api.push.deletePushRule("global", PushRuleKind.ROOM, roomId)
        }
        if (result.isFailure) {
            updateRoomFlagsLocal(roomId) { it.copy(muted = !muted) }
            android.util.Log.w(TAG, "setRoomMuted: server rejected room=$roomId muted=$muted: ${result.exceptionOrNull()?.message}")
        } else {
            android.util.Log.d(TAG, "setRoomMuted: room=$roomId muted=$muted")
        }
    }

    /**
     * Archives or unarchives a room (Beeper's `com.beeper.inbox.done`
     * room account data, synced): archived rooms hide from the
     * main list and go silent, reachable only via search VIEW ALL. Mirrors
     * Beeper's own writes (canonical shape, bundle analysis):
     * archive PUTs `{"at_order":…,"updated_ts":…}`, unarchive PUTs `{}` —
     * Beeper never DELETEs the row (the DELETE route 405s on Beeper's
     * server), so neither do we. Trixnity 4.22.7's typed `setAccountData`
     * PUTs to the legacy `/rooms/{roomId}/account_data` path, which modern
     * Synapse no longer serves (M_UNRECOGNIZED, swallowed in a Result —
     * silent failure), so the current
     * `/user/{userId}/rooms/{roomId}/account_data` path is issued raw
     * through the client's own ktor HttpClient and the bearer token rides
     * along. Optimistic like [setRoomPinned].
     */
    suspend fun setRoomArchived(roomId: String, archived: Boolean) {
        val c = client ?: return
        updateRoomFlagsLocal(roomId) { it.copy(archived = archived) }
        val url = accountDataUrl(c, roomId, BEEPER_INBOX_DONE_EVENT_TYPE)
        val now = System.currentTimeMillis()
        val body = if (archived) {
            buildJsonObject {
                put("at_order", now)
                put("updated_ts", now)
            }
        } else {
            JsonObject(emptyMap())
        }
        val status = try {
            c.api.baseClient.baseClient.put(url) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.status.value
        } catch (e: Exception) {
            android.util.Log.w(TAG, "setRoomArchived: put failed room=$roomId archived=$archived: ${e.message}")
            updateRoomFlagsLocal(roomId) { it.copy(archived = !archived) }
            return
        }
        if (status in 200..299) {
            android.util.Log.d(TAG, "setRoomArchived: room=$roomId archived=$archived status=$status")
        } else {
            // Honest: the server rejected the write — don't leave the
            // optimistic flip as a phantom.
            android.util.Log.w(TAG, "setRoomArchived: server rejected room=$roomId archived=$archived status=$status")
            updateRoomFlagsLocal(roomId) { it.copy(archived = !archived) }
        }
    }

    /** The current per-room account-data path: `/user/{userId}/rooms/{roomId}/account_data/{type}`. */
    private fun accountDataUrl(c: MatrixClient, roomId: String, type: String): String =
        c.baseUrl.toString().trimEnd('/') +
            "/_matrix/client/v3/user/" + c.userId.full.encodeURLPathPart() +
            "/rooms/" + roomId.encodeURLPathPart() +
            "/account_data/" + type

    /**
     * Server truth for Beeper's inbox.done marker: GET 200 whose content
     * carries any canonical field (`at_order`/`updated_ts` — Beeper's
     * shape, bundle analysis; `at_ts` tolerated for the LP3's
     * legacy rows) → archived; 200 with `{}`, or 404 → not archived;
     * other/error → null (unknown — keep the store claim rather than unhide
     * on a blip). Needed because Trixnity's sync store never clears removed
     * room account data.
     */
    private suspend fun isRoomArchivedOnServer(c: MatrixClient, roomId: String): Boolean? =
        try {
            val resp = c.api.baseClient.baseClient.get(accountDataUrl(c, roomId, BEEPER_INBOX_DONE_EVENT_TYPE))
            val status = resp.status.value
            android.util.Log.d(TAG, "archive verify: room=$roomId status=$status")
            when {
                status == 200 -> {
                    val obj = runCatching {
                        pushQueueJson.parseToJsonElement(resp.bodyAsText()).jsonObject
                    }.getOrNull()
                    // Content is the body directly (spec); tolerate a wrap.
                    val content = (obj ?: JsonObject(emptyMap())).let {
                        (it["content"] as? JsonObject)?.takeIf { c -> c.isNotEmpty() } ?: it
                    }
                    listOf("at_order", "at_ts", "updated_ts").any { content[it] != null }
                }
                status == 404 -> false
                else -> null
            }
        } catch (e: de.connect2x.trixnity.core.MatrixServerException) {
            // Trixnity's HttpCallValidator turns non-2xx into this (before
            // ktor's own throw); a 404 is the definitive "not archived"
            // answer, other statuses keep the store claim.
            android.util.Log.d(TAG, "archive verify: room=$roomId status=${e.statusCode.value}")
            if (e.statusCode.value == 404) false else null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "archive verify failed for $roomId: ${e.message}")
            null
        }

    /** The room's effective pinned/muted/archived flags (optimistic writes win;
     *  the store collectors keep the cache fresh within seconds of a
     *  Beeper-side change — the flag loops wait on the flags revision and
     *  refetch this). */
    suspend fun getRoomFlags(roomId: String): RoomFlags =
        roomFlagsOverlay[roomId] ?: roomFlagsCache[roomId] ?: RoomFlags()

    /**
     * Applies one optimistic flag write to both the cache and the overlay
     * (which re-applies it on top of any rebuild until the server echo
     * confirms), then wakes the room-list resolver so the change reaches the
     * tool's next list read.
     */
    private fun updateRoomFlagsLocal(roomId: String, transform: (RoomFlags) -> RoomFlags) {
        val base = roomFlagsOverlay[roomId] ?: roomFlagsCache[roomId] ?: RoomFlags()
        val updated = transform(base)
        roomFlagsCache = roomFlagsCache + (roomId to updated)
        roomFlagsOverlay = roomFlagsOverlay + (roomId to updated)
        bumpRoomFlagsRevision() // the flag-wait loops refetch immediately
        flagsOnlyWake = true
        markRoomListDirty()
        wakeRoomList()
    }

    // --- Notifications --------------------------------------------

    /**
     * Records the room the tool is currently showing. New-message
     * notifications for it are suppressed, and any standing notification for
     * it is removed (opening the thread marks it read). null = no room on
     * screen (list/settings/tool backgrounded).
     */
    fun setActiveRoom(roomId: String?) {
        activeRoomId = roomId
        // The tool just showed the list (null = list/settings/background) —
        // end the resolver's idle sleep so its next pass publishes promptly
        // instead of waiting out the screen-off 60 s breather.
        if (roomId == null) wakeRoomList()
        val ctx = appContext ?: return
        if (roomId != null) ChatNotifier.cancelRoom(ctx, roomId)
    }

    /** Screen truth for the speculative-work gates. */
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

    /** Last event id the notification watcher alerted for [roomKey], persisted
     *  across process restarts ([KEY_LAST_NOTIFIED_PREFIX]); null = nothing
     *  alerted yet (fresh install / new room). */
    private fun lastNotifiedEventId(roomKey: String): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_LAST_NOTIFIED_PREFIX + roomKey, null)

    /** Records that [eventId] was alerted in [roomKey] so a later watcher
     *  registration (new process) does not re-alert it. */
    private fun recordNotifiedEvent(roomKey: String, eventId: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_LAST_NOTIFIED_PREFIX + roomKey, eventId)?.apply()
    }

    /** Read marker this device last sent for [roomKey] ([markRead]), persisted
     *  across process restarts. The background sync filter drops m.receipt
     *  echoes, so the store's own receipt can't prove "already read" at a later
     *  watcher registration — this persisted marker can. */
    private fun lastReadMarkerId(roomKey: String): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_LAST_READ_PREFIX + roomKey, null)

    private fun recordReadMarker(roomKey: String, eventId: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_LAST_READ_PREFIX + roomKey, eventId)?.apply()
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
        // The per-collector scaffold: launch, swallow+log the collector's end
        // (a dead flow must not kill the watcher), register the job.
        fun watch(name: String, block: suspend () -> Unit) {
            scope.launch {
                try {
                    block()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "$name: ${e.message}")
                }
            }.also { notificationWatcherJobs.add(it) }
        }
        val watcher = scope.launch {
            // Flag-change watcher: a global push-rule
            // change (any device toggled mute) re-reads the flags cache so the
            // room list / contact panel reflect it within seconds instead of on
            // the next TTL rebuild. The first emission is the baseline.
            watch("flag watcher: push-rule collector ended") {
                c.di.get<GlobalAccountDataStore>(GlobalAccountDataStore::class)
                    .get(PushRulesEventContent::class).collect { invalidateAllRoomFlags() }
            }
            // Settle flags (first-message ping drop fix): a room whose
            // newest message is already unread when its collector starts — or whose
            // first message arrives right after (it registered empty) — may notify
            // instead of being baselined as history. "Settled" means the account is
            // past (or never had) an initial-sync backfill: a fresh login streams
            // every unread room through INITIAL_SYNC, and notifying then would
            // replay old history as a notification storm. Warm restarts (stored
            // session) never run INITIAL_SYNC, so the first STARTED/RUNNING
            // emission settles immediately and live arrivals right after launch
            // still notify. Atomic: written by this state collector, read by the
            // per-room collectors below on other threads.
            val seenInitialSync = java.util.concurrent.atomic.AtomicBoolean(false)
            val settled = java.util.concurrent.atomic.AtomicBoolean(false)
            watch("notification watcher: sync-state collector ended") {
                c.syncState.collect { state ->
                    when (state) {
                        SyncState.INITIAL_SYNC -> seenInitialSync.set(true)
                        SyncState.RUNNING -> settled.set(true)
                        SyncState.STARTED -> if (!seenInitialSync.get()) settled.set(true)
                        else -> {}
                    }
                }
            }
            // Server unread counts: the sync response carries the
            // server-computed per-room unread_notifications.notification_count.
            // It's the fallback badge while a room has no projection row yet
            // (pre-backfill) — the projection's count is the truth once the
            // room is projected. Incremental syncs only include changed rooms,
            // which is exactly when a count can move.
            watch("server-unread collector ended") {
                c.api.sync.subscribeAsFlow().collect { syncEvents ->
                    val join = syncEvents.syncResponse.room?.join ?: return@collect
                    // Contention probe (issue #33): every joined room below
                    // launches a row resolve, all queuing on [chainDb] — so the
                    // round's fan-out is the number to correlate against the
                    // send-wake round's permit waits.
                    if (debugLogging()) {
                        android.util.Log.d(TAG, "sync round: ${join.size} joined room(s) → ${join.size} row resolves")
                    }
                    for ((roomId, joinedRoom) in join) {
                        val count = joinedRoom.unreadNotifications?.notificationCount?.toInt() ?: 0
                        if (serverUnreadCounts[roomId.full] != count) {
                            serverUnreadCounts[roomId.full] = count
                        }
                        // The join map IS the invalidation list: every room in
                        // it changed somehow (timeline, state, receipts,
                        // counts). publishRoomRowNow is burst-deduped, so a
                        // whole-account round costs one resolve per changed
                        // room. The store reads inside are safe
                        // post-ingest: subscribeAsFlow runs at DEFAULT
                        // priority, after the STORE_EVENTS (receipts) /
                        // ROOM_LIST (summary) / STORE_TIMELINE_EVENTS
                        // (timeline) subscribers have fully persisted this
                        // response — no pre-ingest race.
                        scope.launch {
                            val room = withTimeoutOrNull(ROOM_BUDGET_MS) {
                                c.room.getById(roomId).firstOrNull()
                            } ?: return@launch
                            publishRoomRowNow(c, roomId, room)
                        }
                    }
                }
            }
            try {
                // roomId.full -> last relevant event id seen so far ("" = none yet).
                val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
                // roomId.full -> collector launched (dedup against map re-emissions).
                val registered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                var watched = 0
                // Rooms already in the store when the watcher attaches were seen
                // by an earlier process: unread there means messages arrived while
                // the app was down, and they MUST notify at registration. Rooms
                // that appear later are new — those need the settle gate below
                // (a cold login's first emission can race the wipe and be non-empty).
                var knownAtStart: Set<String>? = null
                c.room.getAll().collect { rooms ->
                    val knownRooms = knownAtStart
                        ?: rooms.keys.mapTo(java.util.HashSet()) { it.full }.also { knownAtStart = it }
                    for ((roomId, roomFlow) in rooms) {
                        val key = roomId.full
                        if (!registered.add(key)) continue
                        val job = scope.launch {
                            try {
                                // First-message ping drop:
                                    // a room whose newest message is ALREADY in the
                                    // store when its collector starts — the room was
                                    // created by that first message (bridge/RCS first
                                    // contact), or the app was down when it arrived —
                                    // used to be baselined as "history" below and never
                                    // notified until a second message came. Notify once
                                    // for the newest event when the room is genuinely
                                    // unread: either it predates this process (known —
                                    // unread = arrived while we were down) or the account
                                    // has settled past its initial-sync backfill (new
                                    // post-settle rooms are live arrivals). `seen` is
                                    // pre-seeded with the notified event so the collector
                                    // below doesn't notify it a second time. A room with
                                    // NO relevant event yet (registered empty) has
                                    // nothing to pre-seed — instead `emptyAtReg` lets the
                                    // collector's baseline branch below notify its first
                                    // arriving event (the app was down for the first
                                    // message in a brand-new room).
                                    val regRoom = roomFlow.filterNotNull().firstOrNull()
                                    val regLastId = regRoom?.lastRelevantEventId?.full
                                    val emptyAtReg = regLastId == null
                                    if (regRoom != null && regLastId != null &&
                                        regRoom.membership == Membership.JOIN
                                    ) {
                                        seen[key] = regLastId
                                        if (key in knownRooms || settled.get()) {
                                            // The user's own m.read receipt (sent when the
                                            // thread was last opened, [markRead]) is the
                                            // ground truth for "already seen" — the
                                            // NotificationService count (getCount) never
                                            // tracks messages in this app, so a getCount
                                            // gate stays 0 and eats the first message.
                                            // A receipt behind the
                                            // newest event — or none at all (thread never
                                            // opened) — means genuinely unread: notify
                                            // once — and not again on every launch: an
                                            // event this watcher already alerted in an
                                            // earlier process ([recordNotifiedEvent]) is
                                            // not re-dinged (ghost bursts). The receipt
                                            // alone can't be the whole story though: the
                                            // background sync filter drops m.receipt
                                            // echoes, so a marker [markRead] already sent
                                            // (persisted [lastReadMarkerId]) counts as
                                            // read here too — else every re-registration
                                            // re-dings the rooms it marked (LP3 09-14
                                            // overnight burst).
                                            // P2 (PLAN.md 2026-09-14): the
                                            // projection ALONE decides — its head
                                            // is the newest real message and its
                                            // count already excludes junk
                                            // (stale-undecryptable storm
                                            // copies, future-stamped, edits,
                                            // reactions, own), so registration
                                            // can't re-ding a junk head
                                            // (the 09-14 overnight bursts).
                                            // The derive-era summary-id
                                            // fallback is deleted; a room
                                            // without a row (pre-backfill)
                                            // stays quiet until the backfill
                                            // writes it.
                                            val proj = projectionRow(c, key)
                                            val projHead = proj?.lastRealEventId
                                            if (proj != null && projHead != null) {
                                                val alreadyAlerted = lastNotifiedEventId(key) == projHead
                                                val readHere = lastReadMarkerId(key) == projHead
                                                if (proj.unreadCount > 0 && !alreadyAlerted && !readHere) {
                                                    android.util.Log.d(
                                                        TAG,
                                                        "notification watcher: $key registered with unread newest " +
                                                            "(${if (key in knownRooms) "known" else "new post-settle"}, " +
                                                            "projection unread=${proj.unreadCount}) — notifying",
                                                    )
                                                    recordNotifiedEvent(key, projHead)
                                                    notifyForEvent(c, roomId, projHead, regRoom)
                                                }
                                            }
                                        }
                                    }
                                    roomFlow.filterNotNull().collect { updated ->
                                        // Any room-state change (message,
                                        // membership) publishes the room's row
                                        // NOW (see [publishRoomRowNow]) — the
                                        // resolver has no steady-state sweep
                                        // (INGEST-DERIVED-PLAN Phase C); unread
                                        // changes come via the count collector
                                        // above. No signature gate: the row
                                        // resolve is a projection read now, and
                                        // publishRoomRowNow burst-dedupes.
                                        publishRoomRowNow(c, roomId, updated)
                                        val lastId = updated.lastRelevantEventId?.full ?: return@collect
                                        if (updated.membership != Membership.JOIN) return@collect
                                        val prev = seen[key]
                                        if (prev == null) {
                                            // Baseline: the first loaded state is
                                            // already-synced history, never notified —
                                            // EXCEPT a room that registered empty
                                            // ([emptyAtReg], see above): once the account
                                            // has settled past its initial-sync backfill,
                                            // that first event is a live arrival, not
                                            // replayed history — fall through to the
                                            // notify path below (the app was down when a
                                            // brand-new room's first message arrived).
                                            if (!(emptyAtReg && settled.get())) {
                                                seen[key] = lastId
                                                return@collect
                                            }
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
                        // Flag-change collectors for this room: the m.favourite tag (pin) and Beeper
                        // inbox.done account data (archive) change on any
                        // device's toggle — re-read the flags cache so the
                        // change reaches the tool within seconds. The first
                        // emission per room is the baseline (just one early
                        // rebuild).
                        val tagJob = scope.launch {
                            try {
                                c.room.getAccountData(roomId, TagEventContent::class, "").collect {
                                    invalidateRoomFlags(key)
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "flag watcher: tag collector ended for $key: ${e.message}")
                            }
                        }
                        notificationWatcherJobs.add(tagJob)
                        val archiveJob = scope.launch {
                            try {
                                c.room.getAccountData(roomId, BeeperInboxDoneContent::class, "").collect {
                                    invalidateRoomFlags(key)
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "flag watcher: archive collector ended for $key: ${e.message}")
                            }
                        }
                        notificationWatcherJobs.add(archiveJob)
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
        // the tool via the SDK flow): skip the whole chain —
        // the decrypt wait, flood/ghost walk and page warm built a preview the
        // OS drops. The room list still updates (separate resolver path).
        // Archived room got a message — mirror the other Beeper clients:
        // unarchive (PUT {} to inbox.done). updateRoomFlagsLocal marks the
        // room list dirty, so the row reappears in the main list, and the
        // message below notifies like any other (muted still silences).
        val flags = roomFlagsCache[roomId.full]
        if (flags?.archived == true) {
            android.util.Log.d(TAG, "notifyForEvent: archived room $roomId got a message — unarchiving")
            setRoomArchived(roomId.full, false)
        }
        if (!ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        if (activeRoomId == roomId.full) return
        if (room.membership != Membership.JOIN) return
        // Muted room (chats /): stop notifying; the unread badge and the room
        // list stay (muted). Checked before the decrypt wait so a muted room
        // costs nothing per message.
        if (flags?.muted == true) {
            android.util.Log.d(TAG, "notifyForEvent: skipping muted room $roomId")
            return
        }
        // Cold-process miss (the whole-cache build waits on the room-list
        // resolver): mute must not wait — the install-restart notified a
        // MUTED room in this window. The miss path
        // reads the push rules directly — one account-data store read, and
        // it warms nothing (the resolver rebuild supersedes it). Archived
        // still falls through to notify here: resolving it costs a network
        // GET per event, and an archived room's unread event is rare.
        if (flags == null && isRoomMutedByPushRule(c, roomId.full)) {
            android.util.Log.d(TAG, "notifyForEvent: skipping muted (cold-cache read) room $roomId")
            return
        }
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
        // Bridge clock skew: re-delivered events stamped minutes-to-hours into
        // the FUTURE (09-14: 1€ Doc Chat head at 17:41 vs phone clock 11:20)
        // decrypt fine and sail past every undecryptable guard — notify anyway?
        // No: a real message is stamped at send time; nothing notify-worthy
        // arrives from the future. (The unread resolver applies the same rule.)
        if (te.event.originTimestamp > System.currentTimeMillis() + UNREAD_FUTURE_SKEW_MS) {
            android.util.Log.d(TAG, "notifyForEvent: skipping future-stamped event $eventId in $roomId")
            return
        }
        // Bridge re-import floods (the 7am wall) must not notify — a real
        // conversation almost never reaches 30 messages per minute, so the
        // density fallback skips the flood without touching real messages.
        if (isFloodGhost(c, te, ghostContext(c, roomId))) {
            android.util.Log.d(TAG, "notifyForEvent: skipping bridge-flood event $eventId in $roomId")
            return
        }
        // A new message means the user may open this thread. Bump the page
        // revision so the open thread re-serves immediately (the store page
        // the ingest writer wrote is already there or lands with its bump).
        bumpMessagePageRevision(roomId.full)
        if (te.event.sender == c.userId) {
            // Own account — no notification whether it was sent from THIS
            // device (outbox echo) or from another Beeper/WhatsApp device:
            // a message the user sent themselves needs no alert.
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
            // note-to-self message needs no "FENN:" prefix). Channel/broadcast
            // rooms (≤2 members, e.g. a Telegram channel + its account) are
            // treated the same way — every message comes from the channel.
            senderName = if (room.isDirect || (room.joinedMemberCount ?: 0L) <= 2L ||
                te.event.sender == c.userId
            ) null else senderNameOf(c, roomId, te.event.sender),
            preview = preview,
            direct = room.isDirect,
            // The projection's count (junk rules already applied at ingest);
            // the sync's server count until the room is projected.
            unreadCount = projectionRow(c, roomId.full)?.unreadCount
                ?: (serverUnreadCounts[roomId.full]?.toLong() ?: 0L),
        )
        // Persist "alerted this event" so a later watcher registration (new
        // process) doesn't re-alert it — the registration-time notify gate.
        recordNotifiedEvent(roomId.full, eventId)
    }

    // --- Room-list cache ------------------------------------------
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
    )

    private val roomListCache = java.util.concurrent.ConcurrentHashMap<String, RoomListEntry>()
    private val _roomList = MutableStateFlow<List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room>>(emptyList())

    /**
     * The live room census (newest-first) as a flow (NO-SEAM):
     * the tool's list/search/contacts collect this instead of polling the
     * binder. Same truth [getRooms]/[getAllRooms] read.
     */
    val roomList: StateFlow<List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room>> =
        _roomList.asStateFlow()

    /**
     * Monotonic revision of the published room list: bumped on
     * every [publishRoomList] and on [resetRoomList]. The tool polls
     * [roomListRevision] (a Long, cheap) instead of re-fetching the whole
     * 400-room [getRooms] payload every 5 s — the binder transfer happens only
     * when the list actually moved. 0 = never published (cold start).
     */
    @Volatile
    private var roomListRevision = 0L

    /** The current room-list revision for the tool's gating poll. */
    fun roomListRevision(): Long = roomListRevision

    /**
     * Wake signal for [waitForChange] — emitted beside every revision bump so
     * the tool's long-poll wait returns in milliseconds instead of waiting
     * for its next fixed tick. tryEmit into a DROP_OLDEST buffer: the wait's
     * fast-path revision re-check makes a dropped signal harmless (worst case
     * it lapses to the timeout).
     */
    private val changeSignal = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Monotonic revision of a room's served page: bumped by the ingest
     * writer whenever new rows are written through (new/edited events,
     * reactions, send-statuses), plus the send/notify paths' immediate
     * nudges. The thread's 3s poll reads
     * this instead of pulling a full [getMessages] page while nothing moved.
     * 0 = no page served for the room yet.
     */
    private val messagePageRevision =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The cached-page revision for [roomId] (0 = never computed). */
    fun messagePageRevision(roomId: String): Long = messagePageRevision[roomId] ?: 0L

    private fun bumpMessagePageRevision(roomId: String) {
        messagePageRevision[roomId] = (messagePageRevision[roomId] ?: 0L) + 1
        changeSignal.tryEmit(Unit)
        pageChangeSignal.tryEmit(roomId)
    }

    /**
     * Per-room newest-page change signal (NO-SEAM): emitted beside
     * every [bumpMessagePageRevision] so the thread collects it instead of
     * long-polling the revision. A signal, not a page payload: the served page
     * shape (pending echoes, audio state — [getMessages]) stays in exactly one
     * place, and the tool re-reads it on each signal. DROP_OLDEST: a dropped
     * signal costs one tick, same as the old poll's worst case.
     */
    private val pageChangeSignal = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** The per-room page-change signal the thread collects. */
    val pageChanges: kotlinx.coroutines.flow.SharedFlow<String> = pageChangeSignal.asSharedFlow()

    /**
     * Monotonic revision of the room-flags cache:
     * bumped where the flags fact commits — optimistic writes
     * ([updateRoomFlagsLocal]) and store-fresh rebuilds ([roomFlagsByRoom]).
     * The thread/contact-panel flag loops wait on it ("flags" scope) instead
     * of polling [getRoomFlags] on a timer.
     */
    @Volatile
    private var roomFlagsRevision = 0L

    /**
     * The live per-room flags (pinned/muted/archived, optimistic overlay
     * applied) as a flow (NO-SEAM): the thread/contact panels
     * collect this instead of long-polling the flags revision.
     */
    private val _roomFlags = MutableStateFlow<Map<String, RoomFlags>>(emptyMap())
    val roomFlags: StateFlow<Map<String, RoomFlags>> = _roomFlags.asStateFlow()

    private fun bumpRoomFlagsRevision() {
        roomFlagsRevision++
        _roomFlags.value = roomFlagsCache + roomFlagsOverlay
        changeSignal.tryEmit(Unit)
    }

    /**
     * Monotonic revision of the tool-visible account/connection/verification
     * status facts: bumped beside every
     * [_connectionState] and [_verification] commit. The Account/Verification/
     * Settings screens wait on it ("status" scope) instead of polling
     * [accountState]/[connectionState]/[verificationState] on a timer.
     */
    @Volatile
    private var statusRevision = 0L

    private fun bumpStatusRevision() {
        statusRevision++
        changeSignal.tryEmit(Unit)
    }

    /** Commits [_connectionState] and wakes the status waiters. */
    private fun setConnectionState(state: ChatConnectionState) {
        _connectionState.value = state
        bumpStatusRevision()
    }

    /** Commits [_verification] and wakes the status waiters. */
    private fun setVerificationUi(ui: VerificationUi) {
        _verification.value = ui
        bumpStatusRevision()
    }

    /**
     * Holds the caller until a watched revision moves past [lastSeen] or
     * [timeoutMs] elapses (the server half of [LightServiceMethod.WaitForChange]).
     * Returns the current revision either
     * way; the caller compares and refetches. Fast path first: an already-
     * moved revision (raced signal) returns immediately.
     */
    suspend fun waitForChange(watch: String, roomId: String?, lastSeen: Long, timeoutMs: Long): Long {
        fun current(): Long = when (watch) {
            "rooms" -> roomListRevision
            "flags" -> roomFlagsRevision
            "status" -> statusRevision
            else -> messagePageRevision[roomId] ?: 0L
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val now = current()
            if (now != lastSeen) return now
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return now
            withTimeoutOrNull(remaining) { changeSignal.first() }
        }
    }

    @Volatile
    private var roomListJob: Job? = null

    /** Wake flag for the resolver's FLAGS-ONLY pass (local PIN/MUTE/ARCHIVE
     *  writes, verification-state changes): that pass re-stamps cached rows
     *  with fresh flags and publishes without a crawl. Sync-derived changes
     *  never set it — they publish their own row ([publishRoomRowNow];
     *  INGEST-DERIVED-PLAN Phase C). */
    @Volatile
    private var roomListDirty = true

    /**
     * latency timers (SYNC-PERF-SPEC.md): elapsed-realtime
     * of the FIRST dirty set since the last publish ("store→publish" start)
     * and of the last publish ("revision→RPC" start for getRooms). Stamping is
     * unconditional and cheap (volatile writes); the logs are debugLog-gated.
     * 0 = nothing pending.
     */
    @Volatile
    private var roomListDirtyAt = 0L

    @Volatile
    private var roomListPublishedAt = 0L

    // --- sync-ingest gate (SYNC-PERF-SPEC §Phase 1) --------------

    /** Bounds for [yieldToSyncIngest]: heavy in-process work waits at most this
     *  long for a running sync ingest before proceeding anyway (a wedged round
     *  must not starve the resolver forever). Generous by design — an 8 s cap
     *  let gated consumers barge in cycles during the 22:59 cold-start catchup
     *  and starved a 44-event ingest for 175 s; only a wedged
     *  round outlives 60 s. */
    private const val SYNC_INGEST_YIELD_MAX_MS = 60_000L
    private const val SYNC_INGEST_YIELD_STEP_MS = 100L

    /**
     * Wall-clock bookkeeping of the last sync round's INGEST window (post-HTTP
     * parse/decrypt/store): [syncRoundStartedAt] is stamped by the /sync
     * interceptor when a response lands, [syncRoundEndedAt] when the sync loop
     * issues the NEXT request or a [timedSyncOnce] round finishes — Trixnity
     * processes rounds sequentially, so either proves the previous ingest
     * finished (the timedSyncOnce stamp matters in slow mode, where no next
     * request follows for minutes). All syncs (long-poll, syncOnce) share one
     * OkHttp engine, so the interceptor covers both. Unconditional volatile
     * stamps; the reads are the soft gate below.
     */
    @Volatile
    private var syncRoundStartedAt = 0L

    @Volatile
    private var syncRoundEndedAt = 0L

    private fun syncIngestInFlight(): Boolean = syncRoundStartedAt > syncRoundEndedAt

    /**
     * Yields to a running sync ingest: heavy in-process work (resolver passes,
     * page rebuilds, ghost walks) shares the Room DB + CPU with Trixnity's
     * parse/decrypt/store, and that contention is the suspected multiplier
     * that turns a sub-second ingest into 17-30 s on the live 1284-room
     * account (SYNC-PERF-SPEC §Phase 1 lever 1). Bounded by [maxMs]; a no-op
     * when no ingest is in flight.
     */
    private suspend fun yieldToSyncIngest(maxMs: Long = SYNC_INGEST_YIELD_MAX_MS) {
        if (!syncIngestInFlight()) return
        val deadline = android.os.SystemClock.elapsedRealtime() + maxMs
        while (syncIngestInFlight() && android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(SYNC_INGEST_YIELD_STEP_MS)
        }
    }

    private fun markRoomListDirty() {
        if (roomListDirtyAt == 0L) roomListDirtyAt = android.os.SystemClock.elapsedRealtime()
        roomListDirty = true
    }

    /** Rooms with a single-room publish in flight (see [publishRoomRowNow]) —
     *  bursts collapse; the full pass (already dirtied) covers any dropped tail. */
    private val singleRoomPublishInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Per the SYNC-PERF-SPEC: resolve and publish ONE room's row
     * immediately when its notification-watcher collector sees a change, instead
     * of waiting for a resolver pass — the dominant measured tail. Uses the
     * same bounded per-room reads the pass does; this IS the row's refresh
     * path in steady state — the resolver has no sweep (Phase C). Skipped
     * during the initial crawl — the back-to-back startup passes already
     * publish, and one single-room resolve per room there would double the
     * crawl's work.
     */
    private fun publishRoomRowNow(c: MatrixClient, roomId: RoomId, room: MatrixRoom) {
        if (roomListDirtyAt == 0L) roomListDirtyAt = android.os.SystemClock.elapsedRealtime()
        if (!initialRoomCrawlDone) return
        val key = roomId.full
        if (!singleRoomPublishInFlight.add(key)) return
        scope.launch {
            try {
                runCatching {
                    resolveRoomListEntry(
                        c, roomId, room, HashMap(),
                        verified = isDeviceVerified(c),
                        // The network maps are TTL caches built by the pass over the
                        // FULL room map — never call networkByRoom with a single-room
                        // map here (it would overwrite the cache). Steady state the
                        // cache is warm; before it is, the row keeps its previous
                        // (disk-preloaded) label until the next pass.
                        networks = singleValueMap(key, networkByRoomCache[key] ?: roomListCache[key]?.room?.network),
                        communities = singleValueMap(key, communityByRoomCache[key] ?: roomListCache[key]?.room?.community),
                        flags = mapOf(key to singleRoomFlags(c, key)),
                    )
                    publishRoomList()
                }.onFailure {
                    android.util.Log.w(TAG, "single-room publish failed for $key: ${it.message}")
                }
            } finally {
                singleRoomPublishInFlight.remove(key)
            }
        }
    }

    private fun singleValueMap(key: String, value: String?): Map<String, String> =
        if (value == null) emptyMap() else mapOf(key to value)

    /** One room's flags, read straight from the store ([roomFlagsByRoom]'s
     *  per-room body). Deliberately not [roomFlagsByRoom]: a call here must not
     *  consume the shared invalidation set or re-stamp the build TTL — that
     *  would race the resolver's own rebuild of the OTHER invalidated rooms. */
    private suspend fun singleRoomFlags(c: MatrixClient, key: String): RoomFlags {
        val read = withTimeoutOrNull(ROOM_FLAGS_ROOM_BUDGET_MS) {
            val tags = c.room.getAccountData(RoomId(key), TagEventContent::class, "").first()
            val inboxDone = c.room.getAccountData(RoomId(key), BeeperInboxDoneContent::class, "").firstOrNull()
            val archivedClaim = inboxDone?.atOrder != null || inboxDone?.atTs != null || inboxDone?.updatedTs != null
            RoomFlags(
                pinned = tags?.tags?.containsKey(TagEventContent.TagName.Favourite) == true,
                archived = if (archivedClaim) isRoomArchivedOnServer(c, key) ?: true else false,
                muted = isRoomMutedByPushRule(c, key),
            )
        } ?: roomFlagsCache[key] ?: RoomFlags()
        return roomFlagsOverlay[key] ?: read
    }

    /** Last full room map the resolver collected — the flags-only fast path
     *  re-stamps cached rows from it without re-collecting (see
     *  [flagsOnlyWake]). */
    private var lastRoomsMap: Map<RoomId, Flow<MatrixRoom?>>? = null

    /** Next pass's iteration offset into the room map: the
     *  resolver used to start every pass at the map's front, so rooms past the
     *  per-pass budget were never collected/seeded — their cache rows kept the
     *  initial timestamp (or none) and dropped out of the main list's 200-room
     *  window despite being recent ("Jeff" missing). Rotating
     *  the offset spreads the full map across consecutive passes, so every
     *  room is eventually collected + resolved. Single-threaded: only the
     *  resolver coroutine reads/writes it. */
    private var roomIterationCursor = 0

    /** True once the resolver has collected the whole room map (cursor wrapped
     *  back to 0). Until then the idle gate lets consecutive passes run, so the
     *  full account gets seeded even with no incoming messages. */
    private var initialRoomCrawlDone = false

    /** Set when a PIN/MUTE/ARCHIVE write lands locally ([updateRoomFlagsLocal],
     *  also verification-state changes): the resolver re-stamps the cached
     *  rows with the fresh flags and publishes immediately, then stops —
     *  no crawl follows (INGEST-DERIVED-PLAN Phase C). */
    @Volatile
    private var flagsOnlyWake = false

    /** Wakes the resolver's idle sleep immediately (screen-on, push-wake, the
     *  tool opening the list). Without it, a message that arrived while the
     *  screen was off left the panel stale for the rest of the screen-off
     *  sleep (60 s) after the user woke the phone. Conflated: many signals collapse to one wake.
     */
    private val roomListWake = Channel<Unit>(Channel.CONFLATED)

    private fun wakeRoomList() {
        roomListWake.trySend(Unit)
    }

    /**
     * Server-computed per-room unread notification counts, captured straight
     * from the sync response (see the collector in [observeNotifications]).
     * Trixnity's own badge pipeline is empty after a fresh login, so this —
     * the account-wide truth every other Beeper client shows — is the primary
     * badge source; the local receipt comparison is the last fallback.
     */
    private val serverUnreadCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The ts of the account's own latest m.read receipt, read from the store.
     *  Null when no receipt has synced yet or the store is unreadable.
     *  Used by the projection walk ([projectRoom]) as the unread cursor. */
    private suspend fun ownReceiptTs(c: MatrixClient, roomId: String): Long? {
        val db = runCatching { c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class) }.getOrNull()
            ?: return null
        return chainDb("ownReceiptTs") {
            withContext(Dispatchers.IO) {
                runCatching<Long?> {
                    db.openHelper.writableDatabase.query(
                        "SELECT MAX(je.value) FROM RoomUserReceipts, " +
                            "json_tree(RoomUserReceipts.value) je " +
                            "WHERE RoomUserReceipts.roomId=? AND RoomUserReceipts.userId=? " +
                            "AND je.key='ts' AND je.type='integer'",
                        arrayOf(roomId, c.userId.full),
                    ).use { cur -> if (cur.moveToFirst() && !cur.isNull(0)) cur.getLong(0) else null }
                }.getOrNull()
            }
        }
    }

    private fun resetRoomList() {
        roomListCache.clear()
        bridgeBotByRoom.clear()
        serverUnreadCounts.clear()
        _roomList.value = emptyList()
        roomListJob?.cancel()
        roomListJob = null
        messagePageRevision.clear()
        flagsOnlyWake = false
        lastRoomsMap = null
        // A new account needs its own initial crawl (see [startRoomListResolver]).
        roomIterationCursor = 0
        initialRoomCrawlDone = false
        roomListRevision++ // a reset IS a list change — the tool must re-fetch
        changeSignal.tryEmit(Unit)
    }

    /**
     * Background resolver for the room list. COLD-START ONLY
     * (INGEST-DERIVED-PLAN Phase C): until [initialRoomCrawlDone] it seeds
     * every room with a placeholder row first (so the list shows instantly),
     * then resolves names newest-first within a per-pass time budget,
     * publishing the snapshot after each pass. After the wrap it runs only
     * for the flags-only re-stamp — row facts are published per-room at
     * ingest ([publishRoomRowNow]); there is NO steady-state sweep. A missed
     * trigger shows as a stale row and is fixed at the trigger, never swept
     * under.
     */

    private fun startRoomListResolver(c: MatrixClient) {
        roomListJob?.cancel()
        markRoomListDirty()
        roomListJob = scope.launch {
            android.util.Log.d(TAG, "room list resolver starting for ${c.userId.full}")
            // Hero-name memo: the profile store is warm, but resolving names for
            // every room sequentially still benefits from not re-reading the
            // same hero (WhatsApp DMs reuse the same few profiles).
            val nameMemo = HashMap<String, String>()
            while (true) {
                // Skip the pass unless a local flags write is waiting (the
                // flags-only fast path) or the initial crawl hasn't wrapped
                // yet (see [initialRoomCrawlDone]). No steady-state sweep:
                // sync-derived changes publish their own row
                // ([publishRoomRowNow]).
                if (!roomListDirty && initialRoomCrawlDone) {
                    // Idle sleep, interruptible: [wakeRoomList] (screen-on,
                    // push-wake, list re-opened) ends it early so a message
                    // that landed while the screen was dark is served the
                    // moment the user looks at the list.
                    val sleepMs =
                        if (isScreenInteractive()) ROOM_LIST_REFRESH_DELAY_MS else SLOW_RESOLVER_DELAY_MS
                    withTimeoutOrNull(sleepMs) { roomListWake.receive() }
                    continue
                }
                roomListDirty = false
                if (debugLogging()) {
                    android.util.Log.d(TAG, "room list resolver pass (flagsOnly=$flagsOnlyWake)")
                }
                // Sync-ingest gate: don't crawl the store against a running
                // sync ingest — the pass's reads stretch the round (SYNC-PERF-
                // SPEC §Phase 1). Bounded, so a wedged round can't stall us.
                yieldToSyncIngest()
                // Flags-only fast path: a local PIN/MUTE/ARCHIVE write doesn't
                // need the full room collect + preview pass (up to 15 s each on
                // a big account) before the tool sees it — re-stamp the cached
                // rows with the fresh flags and publish now. After the initial
                // crawl the pass STOPS here (no crawl confirms; the flags
                // overlay already guards rebuilds — INGEST-DERIVED-PLAN C).
                if (flagsOnlyWake && lastRoomsMap != null && roomListCache.isNotEmpty()) {
                    flagsOnlyWake = false
                    runCatching {
                        val fastFlags = roomFlagsByRoom(c, lastRoomsMap!!)
                        for ((key, entry) in roomListCache) {
                            val f = fastFlags[key] ?: continue
                            roomListCache[key] = entry.copy(
                                room = entry.room.copy(
                                    pinned = f.pinned,
                                    archived = f.archived,
                                    muted = f.muted,
                                ),
                            )
                        }
                        publishRoomList()
                    }
                }
                // After the initial crawl the flags-only re-stamp above is the
                // whole pass — no steady-state crawl (INGEST-DERIVED-PLAN
                // Phase C).
                if (initialRoomCrawlDone) {
                    withTimeoutOrNull(
                        if (isScreenInteractive()) ROOM_LIST_REFRESH_DELAY_MS else SLOW_RESOLVER_DELAY_MS
                    ) {
                        roomListWake.receive()
                    }
                    continue
                }
                try {
                    val rooms = withTimeoutOrNull(ROOMS_BUDGET_MS) { c.room.getAll().first() }
                        ?: emptyMap()
                    lastRoomsMap = rooms
                    val passDeadline = android.os.SystemClock.elapsedRealtime() + ROOM_LIST_PASS_BUDGET_MS
                    // Collect each room's current state (the store cache is warm,
                    // so these emit immediately) newest-first; rooms still loading
                    // are retried on the next pass.
                    val loaded = mutableListOf<Pair<RoomId, MatrixRoom>>()
                    // Rotating coverage: start this pass's collect at
                    // [roomIterationCursor] instead of the map's front, so rooms
                    // past the budget on one pass are visited on the next.
                    val entries = rooms.entries.toList()
                    val rotated = entries.drop(roomIterationCursor) + entries.take(roomIterationCursor)
                    var visited = 0
                    for ((roomId, roomFlow) in rotated) {
                        visited++
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
                    roomIterationCursor = if (entries.isEmpty()) 0 else (roomIterationCursor + visited) % entries.size
                    if (roomIterationCursor == 0) initialRoomCrawlDone = true
                    loaded.sortByDescending { it.second.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L }
                    // An unverified device can't decrypt incoming messages, so the
                    // server-computed unread counts are meaningless there (those
                    // events never even notify). Suppress them until verified.
                    val verified = isDeviceVerified(c)
                    // Per-network labels from Beeper's spaces (m.space.child).
                    // Built from the FULL room map (cached) — the budget-bound
                    // loaded subset may not include the (older) space rooms.
                    val (networks, communities) = networkByRoom(c, rooms)
                    // Pinned/archived/muted per room (m.favourite tag, Beeper
                    // inbox.done account data, global push rules) — cached
                    // like the network map.
                    val flags = roomFlagsByRoom(c, rooms)
                    // Bridge contact lists (Beeper provision API): pre-fetch
                    // per bridge so the row resolve below is a pure cache hit —
                    // a network fetch can't sit inside the per-room deadline
                    // (the pass would stall on the first room of each bridge).
                    // Lazy + TTL-cached, skipped when the deadline is near.
                    for (bridgeId in loaded.mapNotNull { contactIdOf(it.second)?.let(::bridgeIdOf) }.distinct()) {
                        if (android.os.SystemClock.elapsedRealtime() >= passDeadline) break
                        bridgeContacts(c, bridgeId)
                    }
                    seedRoomList(loaded, verified, networks, communities, flags)
                    // Every joined room gets a preview attempt (rooms beyond the
                    // preview window showed no latest message at all).
                    // The per-pass budget + the encrypted-room retry backoff keep
                    // it cheap: decrypted reads are milliseconds, still-encrypted
                    // rooms back off for a minute, and each pass stops at the
                    // deadline — the rest finish on the next pass.
                    for ((roomId, room) in loaded) {
                        if (android.os.SystemClock.elapsedRealtime() >= passDeadline) break
                        // Sync-ingest gate: the per-room preview resolves are
                        // the pass's heaviest store work — yield mid-pass too
                        // (the pass deadline still bounds the crawl overall).
                        yieldToSyncIngest()
                        resolveRoomListEntry(
                            c, roomId, room, nameMemo,
                            verified = verified,
                            networks = networks,
                            communities = communities,
                            flags = flags,
                        )
                    }
                    // Publish the resolved list — the store serves every page,
                    // so there is no eager message-page precompute anymore.
                    publishRoomList()
                } catch (e: Exception) {
                    android.util.Log.w(
                        TAG,
                        "room list resolver pass failed: ${e.javaClass.simpleName}: ${e.message}" +
                            "\n${e.stackTraceToString().lineSequence().take(6).joinToString("\n")}",
                    )
                }
                // Battery: while the screen is off, coalesce the
                // resolver's dirty-loop — the room list only needs to be fresh
                // for the next wake, not sub-minute (the slow-sync rounds kept
                // it dirty on the live account, so the old 2s breather meant
                // near-continuous passes).
                // Wakeable: a send/push/list-open wake ends the
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
    private suspend fun seedRoomList(
        rooms: List<Pair<RoomId, MatrixRoom>>,
        verified: Boolean,
        networks: Map<String, String>,
        communities: Map<String, String>,
        flags: Map<String, RoomFlags>,
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
                    unreadCount = if (verified || !room.encrypted) {
                        serverUnreadCounts[key]?.toLong() ?: 0L
                    } else 0,
                    lastTimestampMs = room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L,
                    lastEventId = room.lastRelevantEventId?.full,
                    // Beeper never writes m.direct for self-rooms (Note-to-Self
                    // would read as a group), and Telegram CHANNEL rooms (you +
                    // the channel account) aren't marked direct either — but
                    // every message there comes from the channel, so a per-row
                    // sender name is redundant noise. Treating both as direct
                    // hides it. [isDirectRoom] also
                    // counts Beeper 1:1s whose bridge bot inflates
                    // joinedMemberCount past 2.
                    isDirect = isDirectRoom(room),
                    contactId = contactIdOf(room),
                    network = networks[key],
                    community = communities[key],
                    archived = flags[key]?.archived ?: false,
                    pinned = flags[key]?.pinned ?: false,
                    muted = flags[key]?.muted ?: false,
                ),
                nameResolved = false,
                previewResolved = false,
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
        verified: Boolean,
        networks: Map<String, String>,
        communities: Map<String, String>,
        flags: Map<String, RoomFlags>,
    ) {
        val key = roomId.full
        // Rooms the user left drop out of the list live (membership filter).
        if (room.membership != Membership.JOIN) {
            roomListCache.remove(key)
            return
        }
        val prev = roomListCache[key]
        // P2 (PLAN.md 2026-09-14): the ingest-time projection is the row's
        // ONLY source of truth — head/time/preview/unread come from the table
        // the sync hook materializes. The derive-at-read machinery
        // (summary-gap walks, effectiveLastEvent, ghost walks, preview retry
        // parks) is deleted: a room without a row (fresh login pre-backfill,
        // brand-new room pre-ingest) serves the room summary as-is and the
        // backfill + ingest hook write the true row within one round.
        val projection = projectionRow(c, key)
        val (lastEventId, ts) = if (projection != null) {
            projection.lastRealEventId to projection.lastRealTs
        } else {
            room.lastRelevantEventId?.full to
                (room.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L)
        }
        // Own send in flight (echo not yet in the store): the row must bump to
        // the top NOW with the send's preview + time — the panel must not keep
        // the pre-send state while the user's own message is on its way. The pending's values (send time + body) match the echo's
        // closely, so the swap when the echo lands is invisible; once the
        // pending is gone (echo processed) the normal store row takes over.
        val pending = newestPending(key)
        val rowTs = when (val p = pending) {
            is PendingTextSend -> maxOf(ts, p.timestampMs)
            is PendingAudioSend -> maxOf(ts, p.timestampMs)
            is PendingImageSend -> maxOf(ts, p.timestampMs)
            null -> ts
        }
        // Unread: the projection's count (the hook applied the junk rules at
        // ingest). No row yet → the sync's server count (a free map read, no
        // store walk), suppressed for encrypted rooms on an unverified device.
        val unread = if (projection != null) {
            projection.unreadCount
        } else if (verified || !room.encrypted) {
            serverUnreadCounts[key]?.toLong() ?: 0L
        } else 0
        val stateChanged = prev == null ||
            prev.room.lastEventId != lastEventId ||
            prev.room.unreadCount != unread ||
            prev.room.lastTimestampMs != rowTs

        val nameResolved = prev?.nameResolved == true && !stateChanged
        val freshName = if (nameResolved) {
            prev.room.name
        } else {
            resolveRoomName(c, roomId, room, nameMemo)
        }
        // Flash guard: on a cold start the first passes run before
        // the sync has delivered member state — resolveRoomName falls through
        // to the "Chat" placeholder, and publishing it overwrote the disk
        // cache's previously resolved names (the user-visible name flash when
        // the list opens). Keep the previous resolved name and stay unresolved;
        // a later pass with real heroes replaces it.
        val name = if (freshName == ROOM_NAME_FALLBACK && prev?.nameResolved == true) {
            prev.room.name
        } else {
            freshName
        }

        val preview: String
        val previewResolved: Boolean
        when {
            pending != null -> {
                // A send in flight: the preview is the sent body ("Voice note"
                // for audio), named like the room list would name it.
                preview = pending.let { p ->
                    when (p) {
                        is PendingTextSend ->
                            if (room.isDirect) p.body else "You: ${p.body}"
                        is PendingAudioSend -> "Voice note"
                        is PendingImageSend -> "Photo"
                    }
                }
                previewResolved = true
            }
            projection != null -> {
                preview = projection.preview
                previewResolved = projection.previewResolved
            }
            prev != null && prev.previewResolved && !stateChanged -> {
                preview = prev.room.lastMessage
                previewResolved = true
            }
            else -> {
                // No projection row yet: no preview until the hook writes one —
                // never re-derive at read (P2).
                preview = ""
                previewResolved = true
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
                // Beeper never writes m.direct for self-rooms (Note-to-Self
                // would read as a group), and Telegram CHANNEL rooms (you +
                // the channel account) aren't marked direct either — but
                // every message there comes from the channel, so a per-row
                // sender name is redundant noise. Treating both as direct
                // hides it. [isDirectRoom] also counts
                // Beeper 1:1s whose bridge bot inflates joinedMemberCount
                // past 2.
                isDirect = isDirectRoom(room),
                contactId = contactIdOf(room),
                // Resolved here (not in the seed pass — this one has a client
                // for the member-state read). Null for groups/seed rows. The
                // room data first (cheap, exact for @whatsapp_<number> ghosts),
                // then the bridge's own contact list — the authoritative
                // source for numbers the room data never carries (LID heroes,
                // Instagram usernames; see [bridgeContactIdentifier]).
                contactPhone = contactIdOf(room)?.let { contactId ->
                    contactPhoneOf(c, roomId, contactId)
                        ?: bridgeContactIdentifier(c, contactId)
                },
                network = networks[key],
                community = communities[key],
                archived = flags[key]?.archived ?: false,
                pinned = flags[key]?.pinned ?: false,
                muted = flags[key]?.muted ?: false,
            ),
            // The fallback isn't a resolution: heroes may still be on their way
            // (the full initial sync lands after the slim-phase rooms did) —
            // keep re-resolving so the row upgrades when they arrive.
            nameResolved = name != ROOM_NAME_FALLBACK,
            previewResolved = previewResolved,
        )
    }

    /** Network-map cache (see [networkByRoom]): rebuilt at most every TTL. */
    @Volatile
    private var networkByRoomCache: Map<String, String> = emptyMap()
    /** Community-name map (same rebuild pass — sub-space explicitNames). */
    @Volatile
    private var communityByRoomCache: Map<String, String> = emptyMap()
    @Volatile
    private var networkByRoomBuiltAtMs = 0L

    /**
     * Maps each room id to its bridged-network label, from Beeper's
     * per-network spaces: a space's `m.space.child` state (stateKey = child
     * room id) lists its rooms, and the space's explicit name carries the
     * network ("WhatsApp (+61420460590)" → "WhatsApp"). Rooms outside any
     * account space (Beeper-internal, e.g. Note to self) stay ungrouped.
     * Only ACCOUNT spaces are mapped (see [isAccountSpace]): Beeper also
     * creates spaces for WhatsApp group/community chats, named after the group
     * itself — those must not become selectable accounts.
     * Also returns the community map (room id → the community sub-space's own
     * name, e.g. "1 euro film"): Beeper nests WhatsApp community groups under
     * their own space, a child of the account space — the sub-space's explicit
     * name is the community the user sees in Beeper.
     * Reads the FULL room map (not the budget-bound newest subset — the space
     * rooms are older than the room activity) and caches the result, since
     * space membership changes rarely.
     */
    private suspend fun networkByRoom(
        c: MatrixClient,
        rooms: Map<RoomId, Flow<MatrixRoom?>>,
    ): Pair<Map<String, String>, Map<String, String>> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (networkByRoomCache.isNotEmpty() && now - networkByRoomBuiltAtMs < NETWORK_MAP_TTL_MS) {
            return networkByRoomCache to communityByRoomCache
        }
        val result = mutableMapOf<String, String>()
        val communities = mutableMapOf<String, String>()
        // Community/group sub-spaces of account spaces inherit the account's
        // label (see below) — their ids are collected on the first pass.
        val groupSpaceChildren = mutableMapOf<String, Pair<String, String>>()
        withTimeoutOrNull(NETWORK_MAP_BUDGET_MS) {
            // Every space id (account spaces AND community/group spaces): an
            // account space's child that is itself a space is a sub-space
            // whose own children still belong to the same network. The single
            // pass below also collects the spaces for the labeling pass.
            val spaceIds = HashSet<String>()
            val spaceNameBySpaceId = HashMap<String, String>()
            val spaces = ArrayList<Pair<RoomId, MatrixRoom>>()
            for ((spaceId, spaceFlow) in rooms) {
                val space = spaceFlow.filterNotNull().firstOrNull() ?: continue
                if (space.createEventContent?.type is CreateEventContent.RoomType.Space) {
                    spaceIds += spaceId.full
                    spaceNameBySpaceId[spaceId.full] = space.name?.explicitName.orEmpty()
                    spaces += spaceId to space
                }
            }
            for ((spaceId, space) in spaces) {
                val spaceName = space.name?.explicitName.orEmpty()
                if (!isAccountSpace(spaceName, space.name?.heroes.orEmpty())) continue
                val label = networkLabelOf(spaceName)
                if (label.isBlank()) continue
                val childIds = c.room.getAllState(spaceId, ChildEventContent::class).first().keys
                for (childId in childIds) {
                    result[childId] = label
                    // Beeper puts WhatsApp community groups under their own
                    // space ("1 euro film", "crocs 2026 squad"), a child of the
                    // account space — those rooms show no network and vanish
                    // from the network filter. Record
                    // the sub-space so the second pass labels its rooms.
                    if (childId in spaceIds) {
                        groupSpaceChildren[childId] = label to spaceNameBySpaceId[childId].orEmpty()
                    }
                }
            }
            // Second pass: the rooms inside each community/group sub-space
            // inherit the account's label (putIfAbsent — a direct account
            // child already labeled wins) and the community's name.
            for ((subSpaceId, pair) in groupSpaceChildren) {
                val (label, communityName) = pair
                val subChildIds = c.room.getAllState(RoomId(subSpaceId), ChildEventContent::class).first().keys
                for (childId in subChildIds) {
                    result.putIfAbsent(childId, label)
                    communities.putIfAbsent(childId, communityName)
                }
            }
        }
        networkByRoomCache = result
        communityByRoomCache = communities
        networkByRoomBuiltAtMs = now
        return result to communities
    }

    /** Pinned/archived/muted flags for one room, from synced Matrix/Beeper
     *  state (chats, 2026-08-28). */
    data class RoomFlags(
        val pinned: Boolean = false,
        val archived: Boolean = false,
        val muted: Boolean = false,
    )

    /** Flags-cache (see [roomFlagsByRoom]): rebuilt at most every TTL, like the
     *  network map. Our own PIN/MUTE/ARCHIVE toggles mutate it optimistically;
     *  external Beeper changes invalidate it via the store collectors in
     *  [observeNotifications], so they land within seconds — not on the next
     *  TTL rebuild. */
    @Volatile private var roomFlagsCache: Map<String, RoomFlags> = emptyMap()
    @Volatile private var roomFlagsBuiltAtMs = 0L

    /** Optimistic local writes (PIN/MUTE/ARCHIVE taps): re-applied on top of
     *  every rebuild so a TTL-expiry rebuild can't clobber them with a stale
     *  store read (the sync echo of our own write hasn't landed yet), and
     *  dropped once a rebuild reads the confirmed server value. This is what
     *  makes pin/unpin reorder the list instantly. */
    @Volatile private var roomFlagsOverlay: Map<String, RoomFlags> = emptyMap()

    /** Rooms whose tag / inbox.done / push-rule state changed on the server
     *  (the store collectors saw it) — the cache must be re-read. Consumed
     *  and cleared by the next [roomFlagsByRoom] build. */
    @Volatile private var roomFlagsInvalidated: Set<String> = emptySet()
    @Volatile private var roomFlagsInvalidatedAll = false
    private val flagsLock = Any()
    private val ROOM_FLAGS_TTL_MS = NETWORK_MAP_TTL_MS // reuse the same TTL

    /** Marks one room's flags as changed on the server (tag / inbox.done
     *  collectors in [observeNotifications]) and wakes the room-list resolver
     *  so the change reaches the tool's next list read. [flagsOnlyWake] routes
     *  it through the fast re-stamp path — a remote toggle lands in the list
     *  within a second instead of waiting for the full pass. */
    private fun invalidateRoomFlags(roomId: String) {
        synchronized(flagsLock) {
            roomFlagsInvalidated = roomFlagsInvalidated + roomId
        }
        flagsOnlyWake = true
        markRoomListDirty()
        wakeRoomList()
    }

    /** Marks every room's flags as possibly changed (global push rules). */
    private fun invalidateAllRoomFlags() {
        synchronized(flagsLock) { roomFlagsInvalidatedAll = true }
        flagsOnlyWake = true
        markRoomListDirty()
        wakeRoomList()
    }

    /** The room's mute flag straight from the synced global ROOM push rules:
     *  a `dont_notify` rule with the room id (dont_notify has no PushAction
     *  constant — compare by name; raw equality is JsonElement-sensitive).
     *  One account-data store read — used per-room by the cache build and
     *  directly by the notify gate's cold-cache miss path. */
    private suspend fun isRoomMutedByPushRule(c: MatrixClient, roomId: String): Boolean = try {
        c.di.get<GlobalAccountDataStore>(GlobalAccountDataStore::class)
            .get(PushRulesEventContent::class).first()?.content?.global?.room
            .orEmpty()
            .any { it.ruleId == roomId && it.actions.any { action -> action.name == "dont_notify" } }
    } catch (_: Exception) {
        false // unreadable rules: behave like before (no mute known)
    }

    /**
     * Pinned/archived/muted per room, from synced state: the room's m.favourite
     * tag (pinned), Beeper's `com.beeper.inbox.done` account data
     * (archived), and a global ROOM dont_notify push rule (muted). TTL +
     * budget mirrors [networkByRoom]; a failed build keeps the previous cache.
     * Rebuilds early when a server-side change was observed (store collectors
     * in [observeNotifications]); our own optimistic writes ([roomFlagsOverlay])
     * survive the rebuild until the server echo confirms them.
     */
    private suspend fun roomFlagsByRoom(
        c: MatrixClient,
        rooms: Map<RoomId, Flow<MatrixRoom?>>,
    ): Map<String, RoomFlags> {
        val now = android.os.SystemClock.elapsedRealtime()
        val changed: Boolean
        synchronized(flagsLock) {
            changed = roomFlagsInvalidated.isNotEmpty() || roomFlagsInvalidatedAll
            roomFlagsInvalidated = emptySet()
            roomFlagsInvalidatedAll = false
        }
        if (roomFlagsCache.isNotEmpty() && now - roomFlagsBuiltAtMs < ROOM_FLAGS_TTL_MS && !changed) {
            return roomFlagsCache + roomFlagsOverlay
        }
        // Seed from the last-known flags so a room that times out KEEPS its
        // previous state instead of silently unpinning. The old whole-loop
        // budget cut the 296-room walk partway and cached the partial prefix —
        // the LP3 served exactly 1 pinned room though the store held 3.
        val result = roomFlagsCache.toMutableMap()
        for ((roomId, _) in rooms) {
            val key = roomId.full
            withTimeoutOrNull(ROOM_FLAGS_ROOM_BUDGET_MS) {
                val tags = c.room.getAccountData(roomId, TagEventContent::class, "").first()
                // The store never clears removed account data, so a stale row
                // can be a phantom (unarchived in Beeper long ago) — confirm
                // the claim on the network (only archived rooms cost a GET;
                // unknown → keep the store claim). Archived = content carries
                // any canonical field (at_order/updated_ts — Beeper's shape,
                // bundle analysis; at_ts tolerated for the LP3's
                // legacy rows): Beeper's unarchive resets the content to {}
                // rather than deleting the row, so row presence is not the
                // flag.
                val inboxDone = c.room.getAccountData(roomId, BeeperInboxDoneContent::class, "").firstOrNull()
                val archivedClaim = inboxDone?.atOrder != null || inboxDone?.atTs != null || inboxDone?.updatedTs != null
                val archived = if (archivedClaim) isRoomArchivedOnServer(c, key) ?: true else false
                result[key] = RoomFlags(
                    pinned = tags?.tags?.containsKey(TagEventContent.TagName.Favourite) == true,
                    archived = archived,
                    muted = isRoomMutedByPushRule(c, key),
                )
                // Our optimistic write now matches the server — drop the
                // overlay entry (confirmed). Not-yet-echoed writes stay.
                val overlay = roomFlagsOverlay[key]
                if (overlay != null && overlay == result[key]) {
                    synchronized(flagsLock) { roomFlagsOverlay = roomFlagsOverlay - key }
                }
            }
        }
        result.putAll(roomFlagsOverlay)
        roomFlagsCache = result
        roomFlagsBuiltAtMs = now
        // Only reached on a real rebuild (the TTL fast path returned early) —
        // the fresh flags just committed; wake the flag-wait loops. A rebuild
        // without flag changes wakes them into a same-value refetch: harmless.
        bumpRoomFlagsRevision()
        return roomFlagsCache + roomFlagsOverlay
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
        return ROOM_NAME_FALLBACK
    }

    /** A hero's display name, or its localpart when the user lookup times out. */
    private suspend fun heroName(c: MatrixClient, roomId: RoomId, hero: UserId): String {
        val storeName = withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.user.getById(roomId, hero).firstOrNull()?.name
        }?.takeIf { it.isNotBlank() }
        val bridgeId = bridgeIdOf(hero.full)
        val bridgeName = bridgeId?.let { bridgeContactsCache[it]?.get(hero.full)?.name }
            ?.takeIf { it.isNotBlank() }
        // Diagnostics for the LID-migration title regression:
        // "whatsapp_lid-…" titles mean store name null AND bridge cache miss.
        if (debugLogging() && storeName == null && bridgeName == null &&
            hero.localpart.contains("_lid-")
        ) {
            android.util.Log.d(
                TAG,
                "heroName: $hero — no store displayname, bridge cache miss " +
                    "(bridge=$bridgeId, cached=${bridgeContactsCache[bridgeId]?.size ?: 0})",
            )
        }
        return storeName ?: bridgeName ?: hero.localpart
    }

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
            val txManager = c.di.get<StoreTransactionManager>(StoreTransactionManager::class)
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
     * username) for display.
     */
    /** Direct (1:1) classification for the served room rows. Beyond Trixnity's
     *  own flag: a room with ≤2 joined members is direct (self-rooms, small
     *  chats), and so is a room with exactly one non-bot hero — Beeper DMs
     *  carry the bridge bot as a member, so joinedMemberCount reads 3 for a
     *  plain 1:1 and those rooms would otherwise land in the contacts panel's
     *  Group tab. Bridge ghosts (@whatsapp_*, @instagramgo_*,
     *  @telegram_*, *bot) are plumbing, not people: a room whose heroes are
     *  ALL whatsapp_lid-* linked identities is one contact's accounts (e.g.
     *  "+15052308756, Amy" = Amy's old + current LIDs), a 1:1 that Beeper
     *  names by every linked id — direct, not a group. */
    private fun isDirectRoom(room: MatrixRoom): Boolean {
        if (room.isDirect || (room.joinedMemberCount ?: 0L) <= 2L) return true
        val heroes = room.name?.heroes?.filterNot { it.localpart.endsWith("bot", ignoreCase = true) }.orEmpty()
        if (heroes.isEmpty()) return false
        val people = heroes.count { !it.localpart.contains('_') }
        if (people >= 1) return people == 1
        // No real-user heroes — all bridge ghosts: an all-lid room is one
        // contact's linked identities (a 1:1); anything else is a group of
        // bridge members (phone-number ghosts).
        return heroes.all { it.localpart.contains("_lid-") }
    }

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
     * `UnsignedStateEventData` carries it.
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

    // ── Bridge contact resolution (Beeper provision API) ─────────────────────
    // Beeper resolves bridged identifiers server-side: the room data only ever
    // carries a number for @whatsapp_<number> ghosts — WhatsApp's privacy-LID
    // migration (whatsapp_lid-…), Instagram usernames, etc. are invisible to
    // it. The bridge's own provision endpoint serves the authoritative list:
    // GET {hs}/_matrix/client/unstable/com.beeper.bridge/{bridgeId}/_matrix/provision/v3/contacts?user_id={mxid} →
    // {contacts: [{id, name, avatar_url, identifiers, mxid, dm_room_mxid}]}.
    // Fetched lazily per bridge (see [bridgeContacts]) and cached; no polling.
    private val BRIDGE_KEYS = setOf(
        "whatsapp", "instagramgo", "signal", "telegram", "imessagego", "gmessages",
        "discordgo", "slackgo", "twitter", "linkedin", "facebookgo", "googlechat",
        "line", "tumblrdms",
    )

    private data class BridgeContact(
        val id: String? = null,
        val name: String? = null,
        val identifiers: List<String> = emptyList(),
        val mxid: String? = null,
    )

    private val bridgeJson = Json { ignoreUnknownKeys = true }

    /** Per-bridge contact index: bridge key → (contact mxid → contact). */
    @Volatile
    private var bridgeContactsCache: Map<String, Map<String, BridgeContact>> = emptyMap()
    /** When each bridge's list was fetched (elapsedRealtime), for the TTL. */
    @Volatile
    private var bridgeContactsFetchedAtMs: Map<String, Long> = emptyMap()
    /** Last failed fetch per bridge (elapsedRealtime), for the retry backoff. */
    @Volatile
    private var bridgeContactsFailedAtMs: Map<String, Long> = emptyMap()

    /** Per-contact resolve cache: contact mxid → (entry), for bridges that
     *  serve no contact list (e.g. instagramgo). Same TTL as the list. */
    private data class ResolveEntry(val fetchedAtMs: Long, val contact: BridgeContact?)
    @Volatile
    private var resolvedContactsCache: Map<String, ResolveEntry> = emptyMap()

    /** In-flight provision requests — the caches are only written after the
     *  response, so without dedup a burst of unknown DM ghosts (observed: ~12
     *  concurrent GETs in one second on room-list open) each fired their own
     *  HTTP GET for the same id. Concurrent callers share one Deferred; the
     *  winner writes the cache as before. */
    private val bridgeContactsInFlight =
        java.util.concurrent.ConcurrentHashMap<String, Deferred<Map<String, BridgeContact>?>>()
    private val resolveIdentifierInFlight =
        java.util.concurrent.ConcurrentHashMap<String, Deferred<String?>>()
    /** Caps concurrent provision HTTP rounds ([BRIDGE_HTTP_CONCURRENCY]) so a
     *  burst of new ghosts trickles instead of stampeding the bridge. */
    private val bridgeHttpPermits = Semaphore(BRIDGE_HTTP_CONCURRENCY)

    /** The bridge key from a bridged contact's Matrix id — the localpart
     *  prefix IS the provision bridgeId ("whatsapp_lid-…" → "whatsapp",
     *  "instagramgo-…" → "instagramgo"). Whitelisted so ordinary user ids
     *  (whose localpart may contain "-"/"_") never hit the provision API. */
    private fun bridgeIdOf(contactId: String): String? {
        val localpart = contactId.substringAfter("@").substringBefore(":")
        val key = localpart.substringBefore("_").substringBefore("-")
        return key.takeIf { it in BRIDGE_KEYS }
    }

    /** Parses the provision contacts body ({contacts: [{id, name, avatar_url,
     *  identifiers, mxid, dm_room_mxid}]}). Tolerant of both identifier shapes
     *  — plain strings and {value} objects (the element type didn't decompile
     *  in Beeper 4.55.1) — and of missing/unknown fields. Identifiers come with
     *  a scheme prefix ("tel:+49…", "telegram:karin3na") which is stripped.
     *  Contacts without an mxid are dropped (the index is keyed by it). Null on
     *  malformed input. */
    private fun parseBridgeContacts(body: String): Map<String, BridgeContact>? {
        val contacts = runCatching {
            bridgeJson.parseToJsonElement(body).jsonObject["contacts"]?.jsonArray
        }.getOrNull() ?: return null
        return contacts.mapNotNull { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val mxid = obj["mxid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val identifiers = obj["identifiers"]?.jsonArray?.mapNotNull { idEl ->
                val raw = when (idEl) {
                    is JsonPrimitive -> idEl.contentOrNull
                    else -> runCatching { idEl.jsonObject["value"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                }
                // Identifiers carry a scheme prefix ("tel:+49…", "telegram:karin3na");
                // strip it so [isPhoneNumber] and the username fallback see the bare value.
                raw?.substringAfter(':', raw)
            }.orEmpty()
            mxid to BridgeContact(
                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                name = obj["name"]?.jsonPrimitive?.contentOrNull,
                identifiers = identifiers,
                mxid = mxid,
            )
        }.toMap()
    }

    /** The session access token, persisted at login ([KEY_ACCESS_TOKEN]).
     *  Trixnity's raw ktor client only attaches the bearer on its typed
     *  request path — raw GETs (here, and [setRoomArchived]'s PUT) ride bare,
     *  and Beeper's provision API 404s without it (curl-verified:
     *  no-auth → 404 M_UNRECOGNIZED, with bearer → 200). Sessions restored
     *  from before this key existed read it once from Trixnity's own
     *  `Authentication` table (Room, `value` JSON) and cache it in prefs. */
    private fun accessToken(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ACCESS_TOKEN, null)?.let { return it }
        val token = runCatching {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                ctx.getDatabasePath(DB_NAME).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            try {
                db.rawQuery("SELECT value FROM Authentication LIMIT 1", null).use { cur ->
                    if (!cur.moveToFirst()) return null
                    val raw = cur.getString(0) ?: return null
                    val providerData = bridgeJson.parseToJsonElement(raw)
                        .jsonObject["providerData"]?.jsonPrimitive?.contentOrNull ?: return null
                    bridgeJson.parseToJsonElement(providerData)
                        .jsonObject["accessToken"]?.jsonPrimitive?.contentOrNull
                }
            } finally {
                db.close()
            }
        }.getOrNull()
        if (token != null) prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
        return token
    }

    /** Runs [block] once per [key]: concurrent callers share one in-flight
     *  job ([Deferred.await]) instead of each firing their own request —
     *  the winner's result feeds the cache writes exactly as a solo call
     *  would. The job removes itself on completion; the double-checked
     *  cache checks in each fetch cover the tiny gap between completion
     *  and removal. */
    private suspend fun <T> shareInFlight(
        map: java.util.concurrent.ConcurrentHashMap<String, Deferred<T?>>,
        key: String,
        block: suspend () -> T?,
    ): T? {
        map[key]?.let { return it.await() }
        val deferred = scope.async(start = CoroutineStart.LAZY) { block() }
        val winner = map.putIfAbsent(key, deferred)
        if (winner != null) {
            deferred.cancel()
            return winner.await()
        }
        deferred.invokeOnCompletion { map.remove(key, deferred) }
        deferred.start()
        return deferred.await()
    }

    /** The bridge's contact list (mxid → contact), fetched once per TTL. Null
     *  when the fetch failed (retried after [BRIDGE_CONTACTS_RETRY_MS]); an
     *  empty map when the bridge serves none (cached like a real list). The
     *  provision call rides Trixnity's own ktor client, but the raw client
     *  does not add the bearer — [accessToken] goes on explicitly. */
    private suspend fun bridgeContacts(c: MatrixClient, bridgeId: String): Map<String, BridgeContact>? {
        val now = android.os.SystemClock.elapsedRealtime()
        bridgeContactsCache[bridgeId]?.let {
            if (now - (bridgeContactsFetchedAtMs[bridgeId] ?: 0L) < BRIDGE_CONTACTS_TTL_MS) return it
        }
        if (now - (bridgeContactsFailedAtMs[bridgeId] ?: 0L) < BRIDGE_CONTACTS_RETRY_MS) return null
        return shareInFlight(bridgeContactsInFlight, bridgeId) { fetchBridgeContacts(c, bridgeId) }
    }

    /** The actual provision-list fetch behind [bridgeContacts] — the TTL +
     *  backoff checks re-run inside the shared job, so a caller that raced
     *  another's completed request still gets the fresh cache instead of a
     *  duplicate fetch. */
    private suspend fun fetchBridgeContacts(c: MatrixClient, bridgeId: String): Map<String, BridgeContact>? {
        val now = android.os.SystemClock.elapsedRealtime()
        bridgeContactsCache[bridgeId]?.let {
            if (now - (bridgeContactsFetchedAtMs[bridgeId] ?: 0L) < BRIDGE_CONTACTS_TTL_MS) return it
        }
        if (now - (bridgeContactsFailedAtMs[bridgeId] ?: 0L) < BRIDGE_CONTACTS_RETRY_MS) return null
        val url = "$BEEPER_HOMESERVER/_matrix/client/unstable/com.beeper.bridge/$bridgeId/_matrix/provision/v3/contacts"
        val token = appContext?.let { accessToken(it) }
        val outcome = bridgeHttpPermits.withPermit {
            withTimeoutOrNull(BRIDGE_CONTACTS_BUDGET_MS) {
                try {
                    val resp = c.api.baseClient.baseClient.get(url) {
                        parameter("user_id", c.userId.full)
                        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    }
                    resp.status.value to resp.bodyAsText()
                } catch (e: MatrixServerException) {
                    // A 404 from the list endpoint is deterministic (the bridge
                    // serves no contact list, e.g. instagramgo) — not a transient
                    // failure, so don't count it for the retry backoff.
                    if (e.statusCode.value == 404) 404 to "" else {
                        android.util.Log.w(TAG, "bridge contacts: $bridgeId request failed", e)
                        null
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "bridge contacts: $bridgeId request failed", e)
                    null
                }
            }
        }
        val contacts = when {
            outcome == null -> null
            outcome.first !in 200..299 -> null
            else -> parseBridgeContacts(outcome.second)
        }
        if (contacts == null && outcome?.first == 404) {
            // Bridge serves no contact list — cache empty so the 404 stops
            // retrying; its identifiers come from [resolveBridgeIdentifier].
            bridgeContactsCache = bridgeContactsCache + (bridgeId to emptyMap())
            bridgeContactsFetchedAtMs = bridgeContactsFetchedAtMs + (bridgeId to now)
            bridgeContactsFailedAtMs = bridgeContactsFailedAtMs - bridgeId
            android.util.Log.d(TAG, "bridge contacts: $bridgeId → no contact list (per-contact resolve)")
            return emptyMap()
        }
        if (contacts == null) {
            bridgeContactsFailedAtMs = bridgeContactsFailedAtMs + (bridgeId to now)
            android.util.Log.w(TAG, "bridge contacts: fetch failed for $bridgeId status=${outcome?.first}")
            return null
        }
        val sample = contacts.keys.take(3).joinToString(", ") { it.substringAfter("@").substringBefore(":") }
        android.util.Log.d(TAG, "bridge contacts: $bridgeId → ${contacts.size} contacts (e.g. $sample)")
        bridgeContactsCache = bridgeContactsCache + (bridgeId to contacts)
        bridgeContactsFetchedAtMs = bridgeContactsFetchedAtMs + (bridgeId to now)
        bridgeContactsFailedAtMs = bridgeContactsFailedAtMs - bridgeId
        return contacts
    }

    /** The contact's real identifier (phone number incl. LID-resolved, or
     *  username) from the bridge's contact list, keyed by the contact's full
     *  Matrix id. Bridges that serve no list (instagramgo) fall back to the
     *  per-contact resolve_identifier endpoint (Beeper's own client does the
     *  same — its Start New Chat picker uses the list, DMs resolve per ghost).
     *  Null when the id isn't a known bridge ghost or nothing resolves — the
     *  caller keeps its fallback. */
    private suspend fun bridgeContactIdentifier(c: MatrixClient, contactId: String): String? {
        val bridgeId = bridgeIdOf(contactId) ?: return null
        val list = bridgeContacts(c, bridgeId)
        list?.get(contactId)?.let { return it.identifier() }
        // The list lookup missed (Beeper's list is partial — not every DM
        // ghost is in it; WhatsApp LID heroes like mo/Hannah were dropping
        // out) or the bridge serves no list at all (instagramgo's
        // deterministic 404) → resolve per contact, which is what Beeper's
        // own client does for DMs (curl-verified: telegram →
        // tel/username, instagramgo → username). A transient list FAILURE
        // (null) returns null — the 60s retry covers it without per-contact
        // hammering.
        if (list != null) return resolveBridgeIdentifier(c, bridgeId, contactId)
        return null
    }

    /** The display identifier from a bridge contact: phone number first,
     *  else the first identifier (username), else the bridge id. */
    private fun BridgeContact.identifier(): String? =
        (identifiers.firstOrNull { it.isPhoneNumber() }
            ?: identifiers.firstOrNull()
            ?: id)?.takeIf { it.isNotBlank() }

    /** Resolves one ghost's identifier via the provision resolve_identifier
     *  endpoint — GET {hs}/_matrix/client/unstable/com.beeper.bridge/{bridgeId}
     *  /_matrix/provision/v3/resolve_identifier/{bridgeId-relative id}?user_id=
     *  (decompiled Beeper BridgeApi.retrieveContactList/resolveIdentifier,
     *  response shape identical to a contact). The id is the ghost mxid's
     *  localpart minus the "{bridgeKey}_" prefix — numeric for instagramgo
     *  and telegram (curl-verified: instagramgo → identifiers
     *  ["instagram:animus.film"], telegram → ["tel:+49…","telegram:karin3na"]).
     *  Cached per contact like the list; same TTL. */
    private suspend fun resolveBridgeIdentifier(c: MatrixClient, bridgeId: String, contactId: String): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        resolvedContactsCache[contactId]?.let {
            if (now - it.fetchedAtMs < BRIDGE_CONTACTS_TTL_MS) return it.contact?.identifier()
        }
        val localpart = contactId.substringAfter("@").substringBefore(":")
        val id = localpart.removePrefix("${bridgeId}_")
        if (id == localpart || id.isBlank()) return null
        return shareInFlight(resolveIdentifierInFlight, contactId) {
            fetchResolvedBridgeIdentifier(c, bridgeId, contactId, id)
        }
    }

    /** The actual resolve_identifier request behind [resolveBridgeIdentifier]
     *  — the TTL check re-runs inside the shared job for callers that raced
     *  another's completed resolve. */
    private suspend fun fetchResolvedBridgeIdentifier(
        c: MatrixClient,
        bridgeId: String,
        contactId: String,
        id: String,
    ): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        resolvedContactsCache[contactId]?.let {
            if (now - it.fetchedAtMs < BRIDGE_CONTACTS_TTL_MS) return it.contact?.identifier()
        }
        val url = "$BEEPER_HOMESERVER/_matrix/client/unstable/com.beeper.bridge/$bridgeId/" +
            "_matrix/provision/v3/resolve_identifier/${id.encodeURLPathPart()}"
        val token = appContext?.let { accessToken(it) }
        val outcome = bridgeHttpPermits.withPermit {
            withTimeoutOrNull(BRIDGE_CONTACTS_BUDGET_MS) {
                try {
                    val resp = c.api.baseClient.baseClient.get(url) {
                        parameter("user_id", c.userId.full)
                        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    }
                    resp.status.value to resp.bodyAsText()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "bridge resolve: $bridgeId/$id request failed", e)
                    null
                }
            }
        }
        val contact = when {
            outcome == null -> null
            outcome.first !in 200..299 -> null
            else -> runCatching { parseBridgeContacts("""{"contacts":[${outcome.second}]}""")?.values?.firstOrNull() }.getOrNull()
        }
        resolvedContactsCache = (resolvedContactsCache + (contactId to ResolveEntry(now, contact)))
            .filterValues { now - it.fetchedAtMs < BRIDGE_CONTACTS_TTL_MS }
        if (contact == null) {
            android.util.Log.w(TAG, "bridge resolve: $bridgeId/$id failed status=${outcome?.first}")
        }
        return contact?.identifier()
    }

    /**
     * Drops stale community-room duplicates: when the
     * WhatsApp number changed, Beeper re-created community groups under new
     * rooms; the old rooms linger outside the community sub-space (no
     * [community] label) with the same name as the live group, their timeline
     * undecryptable. A group room that carries no community is hidden when a
     * same-network, same-named room IS in a community — the live twin wins.
     * Directs are never hidden (DM duplicates from the number change stay —
     * distinct contacts can share a name).
     */
    private fun hideStaleCommunityDuplicates(
        rooms: List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room>,
    ): List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room> {
        val inCommunity = rooms
            .asSequence()
            .filter { it.community != null }
            .map { it.network to it.name.trim().lowercase() }
            .toHashSet()
        return rooms.filter { room ->
            val stale = !room.isDirect && room.community == null &&
                (room.network to room.name.trim().lowercase()) in inCommunity
            if (stale) android.util.Log.d(TAG, "room list: hidden stale duplicate '${room.name}' (${room.id})")
            !stale
        }
    }

    /** Publishes the cache as the sorted, SDK-shaped list (and persists it).
     *  Pinned rooms float to the top (Beeper convention — the m.favourite tag),
     *  then newest-first. */
    private fun publishRoomList() {
        val rooms = hideStaleCommunityDuplicates(roomListCache.values.map { it.room })
            .sortedWith(
                compareByDescending<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room> { it.pinned == true }
                    .thenByDescending { it.lastTimestampMs }
            )
        _roomList.value = rooms
        saveRoomListToDisk(rooms)
        roomListRevision++
        changeSignal.tryEmit(Unit)
        if (debugLogging() && roomListDirtyAt > 0) {
            android.util.Log.d(
                TAG,
                "store→publish: ${android.os.SystemClock.elapsedRealtime() - roomListDirtyAt}ms " +
                    "(revision $roomListRevision)",
            )
        }
        roomListDirtyAt = 0L
        roomListPublishedAt = android.os.SystemClock.elapsedRealtime()
    }

    // --- internals -----------------------------------------------------------

    /**
     * latency timer (SYNC-PERF-SPEC.md): elapsed-realtime of the last
     * /sync HTTP response completion — the start of the "sync processed"
     * ingest measurement at the sync event subscriber. Set unconditionally
     * (one volatile write per sync round); the log that reads it is
     * debugLog-gated.
     */
    @Volatile
    private var lastSyncResponseAt = 0L

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
        // cost is the battery metric for whether the sync is lean enough.
        val path = chain.request().url.encodedPath
        if (path.contains("/sync")) {
            // A new /sync request proves the previous round's ingest finished
            // (Trixnity processes rounds sequentially) — sync-ingest gate.
            syncRoundEndedAt = android.os.SystemClock.elapsedRealtime()
            // Ingest-gap attribution: the time
            // between the last response arriving and this next request is the
            // previous round's emit/ingest. Normally ≈ instant; when it blows
            // past one long-poll period the emit path (e.g. Trixnity's inline
            // OTK regen + /keys/upload) froze the whole loop — name it. Slow
            // mode's deliberate inter-round delay (300 s / 900 s) is not a
            // stall — exclude it (a 505 s "gap" was just a slow round, 09-14).
            if (lastSyncResponseAt > 0 && syncMode != SyncMode.SLOW) {
                val ingestGap = android.os.SystemClock.elapsedRealtime() - lastSyncResponseAt
                if (ingestGap > 35_000L) {
                    android.util.Log.w(TAG, "sync ingest gap: ${ingestGap}ms between last response and this request — emit path stalled the loop")
                    Diagnostics.record("sync ingest gap: ${ingestGap}ms — emit path stalled")
                }
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val response = chain.proceed(chain.request())
            val t1 = android.os.SystemClock.elapsedRealtime()
            lastSyncResponseAt = t1
            // Response in hand = parse/decrypt/store about to run; the gate
            // reads "in flight" until the next request starts (above) or the
            // syncOnce wrapper stamps the round's end (timedSyncOnce).
            syncRoundStartedAt = t1
            android.util.Log.d(
                TAG,
                "sync response: ${response.header("Content-Length") ?: "chunked"}B in ${t1 - t0}ms",
            )
            return@Interceptor response
        }
        // Off by default (efficiency ): the body buffering +
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

    @Volatile
    private var httpClientEngine = buildHttpClientEngine()

    /** A fresh engine — [selfHealHttpStack] swaps the wedged engine for a new
     *  one (each ktor client captures the engine at MatrixClient.create time,
     *  so a rebuild only helps clients created after the swap). */
    private fun buildHttpClientEngine() = OkHttp.create {
        addInterceptor(httpLoggingInterceptor())
        addInterceptor(claimFailuresFixInterceptor)
    }.also { android.util.Log.d(TAG, "HTTP-TRAFFIC: generic engine armed") }

    // Steady-state sync filters. Sync payload slimming (PLAN §8.1):
    // the default filter ships every presence update + a huge
    // per-room timeline on the 1284-room account (30-50 s CPU per /sync).
    // Presence is never displayed — set_presence=offline only stops OUR
    // updates, this filter stops receiving theirs — and the timeline limit
    // bounds each room's per-sync window. Trixnity's applyDefaultFilter
    // merges over it, keeping lazy-load members + the event-type whitelists.
    // Ephemeral slimming: applyDefaultFilter REPLACES types with
    // its own whitelist, so narrowing must go through notTypes, which
    // survives the merge. Nothing renders incoming typing (the composer only
    // sends it), and it is the noisiest per-sync element in active rooms —
    // both filters drop m.typing. The syncOnce filter (background rounds +
    // push wakes) used to also drop m.receipt — but reads on OTHER devices
    // only ever land through receipts, so dropping them left ownRead stale
    // all night: rooms read elsewhere re-registered as unread and re-dinged
    // old content on the next process start (09-14 ghost bursts + the
    // stuck "Anni" badge). Receipts are quiet compared to typing — a round
    // only carries rooms someone actually read. m.typing stays dropped.
    private val fullSyncFilters = Filters(
        presence = Filters.EventFilter(notTypes = setOf("*")),
        room = Filters.RoomFilter(
            timeline = Filters.RoomFilter.RoomEventFilter(limit = SYNC_TIMELINE_LIMIT),
            ephemeral = Filters.RoomFilter.RoomEventFilter(notTypes = setOf("m.typing")),
        ),
    )

    // SYNC-PERF-SPEC §Phase 1 lever 3 (without the room-state strip):
    // stripping room state (`state = notTypes="*"`) is only safe while the
    // DB already holds state from an earlier initial sync — but the initial
    // sync can run under THIS filter: the
    // verification-phase swap nulls the batch token, and a slow-sync round /
    // push wake can win the queue and consume the one-shot initial sync.
    // State
    // DELTAS in background rounds are rare (name/membership changes) — keep
    // state here so an initial sync is always correct. The ephemeral
    // slimming (m.typing/m.receipt) stays. Invites are unaffected:
    // rooms.invite carries stripped invite_state.
    private val fullSyncOnceFilters = Filters(
        presence = Filters.EventFilter(notTypes = setOf("*")),
        room = Filters.RoomFilter(
            timeline = Filters.RoomFilter.RoomEventFilter(limit = SYNC_TIMELINE_LIMIT_BACKGROUND),
            ephemeral = Filters.RoomFilter.RoomEventFilter(notTypes = setOf("m.typing")),
        ),
    )

    /**
     * Verification-phase variant of both sync filters (verification-first
     * sync): a fresh login that will run device verification
     * must not let the initial sync outrun the SAS emoji round-trips. Under
     * the full background filter the initial sync ingests 6902 events (301
     * rooms × 20 timeline + state) over ~180 s of serialized emit, and the
     * partner's accept — a to-device event — only lands on the sync round
     * AFTER the initial sync, so the emoji screen appeared ~3 min late.
     * Timeline limit 1 + no state/ephemeral/presence shrinks the same sync to
     * ~300 room-list bones; to-device events (the whole verification chain)
     * and the room-list bones still flow. [swapToFullSync] restores
     * [fullSyncFilters]/[fullSyncOnceFilters] once verification completes, is
     * skipped, or times out.
     */
    private val verificationSyncFilters = Filters(
        presence = Filters.EventFilter(notTypes = setOf("*")),
        room = Filters.RoomFilter(
            timeline = Filters.RoomFilter.RoomEventFilter(limit = 1L),
            ephemeral = Filters.RoomFilter.RoomEventFilter(notTypes = setOf("*")),
            state = Filters.RoomFilter.RoomEventFilter(notTypes = setOf("*")),
        ),
    )

    /** Client configuration, differing only in the client name (the Beeper
     *  profile identifies as "chats-beeper" so its device appears distinctly).
     *  Qualified `httpClientEngine`: inside the receiver lambda, the
     *  unqualified name would resolve to the receiver's own (null) property —
     *  a silent no-op that left the client on Ktor's default engine (no
     *  logging/claim interceptors). */
    private fun clientConfiguration(name: String): MatrixClientConfiguration.() -> Unit = {
        // Capture the configuration being built — its filter properties are
        // `var`, so [swapToFullSync] can switch them without a client rebuild.
        activeClientConfig = this
        this.name = name
        httpClientEngine = this@MatrixRepository.httpClientEngine
        modulesFactories = createTrixnityDefaultModuleFactories() + ::plaintextVerificationModule + ::archiveMappingsModule + ::permissiveKeyRequestModule
        // Verification-first sync: a login that will verify (see
        // [pendingVerificationPhase]) builds with the slim filters on BOTH the
        // long-poll and the background/initial filter — measured 6902-event /
        // 180 s initial ingest on a fresh install, with the
        // SAS emoji stuck behind it. The swap back to the full set happens in
        // [swapToFullSync] once the verification outcome is known.
        syncFilter = if (pendingVerificationPhase) verificationSyncFilters else fullSyncFilters
        syncOnceFilter = if (pendingVerificationPhase) verificationSyncFilters else fullSyncOnceFilters
        // Room "last relevant event" = actual messages only (reference-messenger
        // shape): m.replace edits (Beeper's re-import wall) and reactions no
        // longer advance lastRelevantEventId — so they don't wake the
        // notification watcher or bump the room list. The edits still sync and
        // store; the page-level isReplaceEdit checks stay (they serve
        // pages/previews, which this filter does not touch).
        lastRelevantEventFilter = { event ->
            val content = event.content
            val isReplace = content is MessageEventContent && content.relatesTo is RelatesTo.Replace
            val isMessage = content is RoomMessageEventContent || content is EncryptedMessageEventContent
            (!isReplace) && isMessage
        }
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

    /**
     * Registers our trust-gate-free room-key-request responder. Registered
     * alongside Trixnity's stock handler (the stock one is keyed by its
     * concrete type, so no override happens — it just stays inert because
     * nothing on our accounts is cross-signed). A named qualifier keeps the
     * key distinct from any other EventHandler bean; [MatrixClientImpl]
     * collects handlers via `getAll<EventHandler>()`, which ignores qualifiers.
     *
     * The outgoing side is a real override: [OutgoingRoomKeyRequestEventHandler]
     * is bound by the stock module to [PermissiveOutgoingRoomKeyRequestEventHandler]'s
     * sibling stock impl; this module is appended after the default modules, so
     * the unqualified interface lookup (the trigger in [restoreRoomSessions])
     * resolves to ours. The `bind<EventHandler>()` mirrors the stock module's,
     * so [MatrixClientImpl]'s `getAll<EventHandler>()` also STARTS ours — the
     * forwarded-key import subscription lives in `startInCoroutineScope`. The
     * stock impl still starts alongside (its 1-day stale-request cleanup), and
     * its verified-sender gate keeps it inert on our accounts.
     */
    private fun permissiveKeyRequestModule() = module {
        single<EventHandler>(named("permissiveKeyRequestHandler")) {
            PermissiveIncomingRoomKeyRequestEventHandler(
                userInfo = get(),
                api = get(),
                olmEventHandler = get(),
                olmEncryptionService = get(),
                accountStore = get(),
                olmStore = get(),
                driver = get(),
            )
        }
        singleOf(::PermissiveOutgoingRoomKeyRequestEventHandler) {
            bind<OutgoingRoomKeyRequestEventHandler>()
            bind<EventHandler>()
        }
    }

    private fun databaseBuilder(context: Context) =
        Room.databaseBuilder(context, TrixnityRoomDatabase::class.java, DB_NAME)

    private fun mediaDir(context: Context) = (context.cacheDir.absolutePath + "/$MEDIA_DIR").toPath()

    /** Attaches the sync-state + notification observers to a client, exactly once per instance. */
    private fun observeClient(c: MatrixClient) {
        if (observedClient === c) return
        observedClient = c
        // Part H fresh-login probe (SPEC §8): must land before the first sync
        // round writes rows — it's enqueued ahead of every observer below.
        // Fresh login = EMPTY database entirely (logout deletes it): both the
        // ThreadRow table AND Trixnity's own timeline store. An upgrade to
        // this build has a populated timeline store with a brand-new, empty
        // ThreadRow table — that must NOT read as fresh login and fire a
        // network bulk backfill (§7/§8: upgrades are Part G's lazy seed's
        // business, no network).
        scope.launch {
            threadBackfillStoreEmptyAtAttach = runCatching {
                !ThreadRowStore.anyRows(c) && !ThreadRowStore.timelineStoreHasEvents(c)
            }.getOrDefault(false)
        }
        notificationWatcherJobs.forEach { it.cancel() }
        notificationWatcherJobs.clear()
        observeSyncState(c)
        observeLoginState(c)
        observeNotifications(c)
        observeSyncKeyRequests(c)
        // Ingest-time projection (PLAN.md 2026-09-14, P0 shadow): materialize
        // RoomProjection rows at ingest; consumers still derive at read until
        // P1 flips them.
        observeProjectionIngest(c)
        // Ingest-time thread store (docs/THREAD-STORE-SPEC.md §2): the same
        // seam feeding the ThreadRow ingest writer + its 30 s decrypt recheck.
        observeThreadStoreIngest(c)
        startThreadStoreRecheckLoop(c)
        scope.launch {
            runCatching { backfillProjection(c) }
        }
        // The room-list resolver's first pass seeds + warms the full room map,
        // so the tool's getRooms (a pure cache read) returns instantly.
        startRoomListResolver(c)
        // Re-seed the in-memory pending maps from the outbox after a process
        // restart, so queued/acked-but-not-yet-echoed sends still show a row.
        scope.launch { reconstructOutboxPendings(c) }
    }

    private fun observeSyncState(c: MatrixClient) {
        scope.launch {
            c.syncState.collect { state ->
                setConnectionState(
                    when (state) {
                        SyncState.INITIAL_SYNC -> ChatConnectionState.Connecting
                        SyncState.STARTED, SyncState.RUNNING -> ChatConnectionState.Syncing
                        SyncState.ERROR, SyncState.TIMEOUT -> ChatConnectionState.Offline("sync $state")
                        SyncState.STOPPED -> when {
                            // Slow sync (screen off) stops the long-poll between
                            // periodic syncOnce rounds — that's still "syncing",
                            // not an outage.
                            isSlowSyncing -> ChatConnectionState.Syncing
                            // Battery saver is the source of truth while it
                            // has sync stopped — the restored client reports
                            // STOPPED until the screen comes back on, and
                            // that must read as "battery saver", not
                            // "stopped" (or, worse, the race with init's
                            // explicit assignment).
                            !syncEnabled -> ChatConnectionState.Offline("battery saver")
                            c.loginState.value == MatrixClient.LoginState.LOGGED_IN -> ChatConnectionState.Offline("sync stopped")
                            sessionExpired -> ChatConnectionState.Offline("session expired — sign in again")
                            else -> ChatConnectionState.LoggedOut
                        }
                    },
                )
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
                Diagnostics.record("session expired — sync stopped")
                setConnectionState(ChatConnectionState.Offline("session expired — sign in again"))
                scheduleSyncStop()
            }
        }
    }

    /**
     * Background key-request trigger: Beeper's
     * core asks its own devices for missing megolm sessions the moment a sync
     * round leaves an event undecryptable; we only requested on page open (see
     * [requestMissingRoomKeys]). Subscribes to the sync event emitter — the same
     * one the long-poll loop and every syncOnce round feed — and per round
     * requests the sessions of megolm-encrypted events we don't hold. The
     * handler dedupes pending requests and cancels them on import, so a key
     * shared in the same round (races this store check) costs one redundant
     * to-device event at most. The page-open trigger stays: it covers store
     * reads (pagination walks) the sync stream never delivered.
     */
    private fun observeSyncKeyRequests(c: MatrixClient) {
        val job = scope.launch {
            try {
                c.api.sync
                    .subscribeEventList<
                        EncryptedMessageEventContent.MegolmEncryptedMessageEventContent,
                        ClientEvent.RoomEvent<EncryptedMessageEventContent.MegolmEncryptedMessageEventContent>
                    > { events ->
                        runCatching {
                            // latency timer (SYNC-PERF-SPEC.md): HTTP response
                            // → our visibility of the round's decrypted events ≈ the
                            // ingest cost the 17–30 s model attributes to hop 1.
                            if (debugLogging()) {
                                android.util.Log.d(
                                    TAG,
                                    "sync processed: " +
                                        "${android.os.SystemClock.elapsedRealtime() - lastSyncResponseAt}ms " +
                                        "after HTTP response (${events.size} event(s))",
                                )
                            }
                            // Unverified device: no key-backup access, nothing
                            // to answer with — requesting per missing session
                            // per round churned hundreds of m.room_key_request
                            // to-device events (LP3 fresh login). Same gate as
                            // the unread-count suppression.
                            if (!isDeviceVerified(c)) return@runCatching
                            val outgoing = c.di.get<OutgoingRoomKeyRequestEventHandler>()
                            val olmStore = c.di.get<OlmCryptoStore>()
                            val missing = events.mapNotNull { e ->
                                val sessionId = e.content.sessionId
                                if (olmStore.getInboundMegolmSession(sessionId, e.roomId).firstOrNull() != null) null
                                else e.roomId to sessionId
                            }.distinct()
                            if (missing.isNotEmpty()) {
                                android.util.Log.d(
                                    TAG,
                                    "sync key-request: ${missing.size} missing session(s) (${missing.first().second} …)",
                                )
                                // Hand the sends off: the emitter runs subscribers
                                // serially, and a to-device round trip per missing
                                // session must not hold up the round's other
                                // subscribers (store writes, notification watchers).
                                scope.launch {
                                    missing.forEach { (roomId, sessionId) ->
                                        outgoing.requestRoomKeys(roomId, sessionId)
                                    }
                                }
                            }
                        }.onFailure { e ->
                            android.util.Log.w(TAG, "sync key-request: round scan failed: ${e.message}")
                        }
                    }
                    .unsubscribeOnCompletion(this)
                awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "sync key-request observer ended: ${e.message}")
            }
        }
        notificationWatcherJobs.add(job)
    }

    // ---- Ingest-time projection (PLAN.md 2026-09-14, P0 shadow) -------------
    // One materialized row per room, decided once per event at ingest by
    // [ProjectionPredicate]; consumers still derive at read (P1 flips them).
    // The table lives in Trixnity's DB (raw CREATE TABLE IF NOT EXISTS — the
    // migrateSyncFilterIfNeeded idiom; Room ignores foreign tables), so it
    // resets with logout's deleteDatabase like every other store.

    @Volatile
    private var projectionTableReady = false

    private const val PROJECTION_WALK_MAX = 400

    /** Single decrypt-retry wait for a round's pending encrypted events. */
    private const val PROJECTION_RECHECK_MS = 30_000L

    /** ThreadRow placeholder recheck cadence (SPEC §2/§4) — same wait as the
     *  projection's [PROJECTION_RECHECK_MS]. */
    private const val THREAD_ROW_RECHECK_MS = 30_000L

    /** Per-recheck-pass placeholder cap (SPEC §2: bounded per pass). */
    private const val THREAD_ROW_RECHECK_LIMIT = 50

    /** Rooms served per recheck pass — one [restoreRoomSessions] call each. */
    private const val THREAD_ROW_RECHECK_ROOMS = 8

    /** Recheck pass number — the rotating scan start ([recheckThreadStorePlaceholders]).
     *  In-memory is enough: within a process run the rotation wraps over the
     *  whole pending set, so no placeholder is starved. */
    @Volatile
    private var recheckPass = 0L

    private data class ProjectionRow(
        val roomId: String,
        val lastRealEventId: String?,
        val lastRealTs: Long,
        val unreadCount: Long,
        val preview: String,
        val previewResolved: Boolean,
    )

    private fun ensureProjectionTable(db: TrixnityRoomDatabase) {
        if (projectionTableReady) return
        runCatching {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TABLE IF NOT EXISTS RoomProjection (" +
                    "roomId TEXT NOT NULL PRIMARY KEY," +
                    "lastRealEventId TEXT," +
                    "lastRealTs INTEGER NOT NULL DEFAULT 0," +
                    "unreadCount INTEGER NOT NULL DEFAULT 0," +
                    "preview TEXT NOT NULL DEFAULT ''," +
                    "previewResolved INTEGER NOT NULL DEFAULT 0)",
            )
        }.onSuccess { projectionTableReady = true }
            .onFailure { android.util.Log.w(TAG, "projection: table create failed: ${it.message}") }
    }

    /** The serial sync-event seam: the join map IS the changed-room list of
     *  one sync round (timeline, receipts, state, counts). Same Flow the
     *  notification watcher collects, run post-store-persist (DEFAULT
     *  priority). One batched recompute+write per round, O(changed rooms). */
    private fun observeProjectionIngest(c: MatrixClient) {
        scope.launch {
            try {
                c.api.sync.subscribeAsFlow().collect { syncEvents ->
                    val join = syncEvents.syncResponse.room?.join ?: return@collect
                    val changed = join.keys.toList()
                    if (changed.isEmpty()) return@collect
                    scope.launch {
                        yieldToSyncIngest()
                        recomputeProjectionRows(c, changed)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "projection ingest observer ended: ${e.message}")
            }
        }.also { notificationWatcherJobs.add(it) }
    }

    /** One-time seed: every joined room through the same recompute as the
     *  ingest hook. Flag lives in PREFS (cleared at logout together with the
     *  DB). Retried a few times — a fresh login's room store may not be
     *  populated until the initial sync lands. */
    private suspend fun backfillProjection(c: MatrixClient, attempt: Int = 0) {
        // Mark the table ready immediately: the rows persist in the DB across
        // restarts, and the notification gate registers ~10 s in — before the
        // first sync round would ensure the table. Without this, every
        // restart's first registrations fall back to the summary-id gate
        // (the junk-head path this plan removes).
        runCatching {
            ensureProjectionTable(c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class))
        }
        yieldToSyncIngest()
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        if (prefs.getBoolean("projection_backfilled", false)) {
            // Restore/restart: the projection is done, but a Part H backfill
            // pass interrupted by process death / rate-limit / reboot resumes
            // from its bookmarks (SPEC §8).
            startThreadBackfill(c)
            return
        }
        // The room map often isn't surfaced yet on a cold login (the initial
        // sync takes longer than the budget on a 358-room account) — a silent
        // return here killed the whole backfill on the LP3 (09-15). Retry.
        val rooms = runCatching {
            withTimeoutOrNull(ROOMS_BUDGET_MS) { c.room.getAll().first() }
        }.getOrNull()
        if (rooms == null) {
            if (attempt < 5) {
                scope.launch { delay(30_000L); backfillProjection(c, attempt + 1) }
            } else {
                android.util.Log.w(TAG, "projection: backfill gave up — room map never surfaced")
            }
            return
        }
        // JOIN rooms only: the projection describes the joined set — a left
        // room's row would linger forever (no sync events ever recompute it)
        // and drift the Account "x of n rooms" stat.
        val roomIds = rooms.keys.mapNotNull { id ->
            withTimeoutOrNull(ROOM_LIST_ROOM_BUDGET_MS) {
                rooms[id]?.filterNotNull()?.firstOrNull()
            }?.takeIf { it.membership == Membership.JOIN }?.let { id }
        }
        android.util.Log.d(TAG, "projection: backfill starting (${roomIds.size} rooms)")
        // Reconcile: drop rows for rooms no longer joined (left, or pruned
        // from the store entirely). Once per login, not a sweep.
        if (roomIds.isNotEmpty()) {
            val db = runCatching { c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class) }.getOrNull()
            if (db != null && projectionTableReady) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val holes = roomIds.joinToString(",") { "?" }
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM RoomProjection WHERE roomId NOT IN ($holes)",
                            roomIds.map { it.full as Any }.toTypedArray(),
                        )
                    }
                }
            }
        }
        val written = recomputeProjectionRows(c, roomIds)
        // An empty store (fresh login, initial sync not landed yet) is not
        // "done" — retry until rooms exist or the attempt cap hits.
        if ((written < roomIds.size || roomIds.isEmpty()) && attempt < 3) {
            scope.launch {
                delay(60_000L)
                backfillProjection(c, attempt + 1)
            }
            return
        }
        prefs.edit().putBoolean("projection_backfilled", true).apply()
        android.util.Log.d(TAG, "projection: backfilled ${written}/${roomIds.size} rooms")
        // Fresh login: initial sync done, room list known — start the Part H
        // bounded ThreadRow backfill (SPEC §8).
        startThreadBackfill(c)
    }

    private suspend fun recomputeProjectionRows(c: MatrixClient, roomIds: List<RoomId>): Int {
        val db = runCatching { c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class) }.getOrNull()
            ?: return 0
        ensureProjectionTable(db)
        if (!projectionTableReady) return 0
        val rows = ArrayList<ProjectionRow>(roomIds.size)
        val pendingDecrypt = ArrayList<RoomId>()
        for (roomId in roomIds) {
            val projected = runCatching { projectRoom(c, roomId) }.getOrNull() ?: continue
            if (projected.row != null) rows += projected.row
            if (projected.pendingDecryption) pendingDecrypt += roomId
        }
        if (rows.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val sq = db.openHelper.writableDatabase
                    sq.beginTransaction()
                    try {
                        for (r in rows) {
                            sq.execSQL(
                                "INSERT OR REPLACE INTO RoomProjection" +
                                    "(roomId,lastRealEventId,lastRealTs,unreadCount,preview,previewResolved) " +
                                    "VALUES(?,?,?,?,?,?)",
                                arrayOf<Any?>(
                                    r.roomId,
                                    r.lastRealEventId,
                                    r.lastRealTs,
                                    r.unreadCount,
                                    r.preview,
                                    if (r.previewResolved) 1 else 0,
                                ),
                            )
                        }
                        sq.setTransactionSuccessful()
                    } finally {
                        sq.endTransaction()
                    }
                }.onFailure { android.util.Log.w(TAG, "projection: write failed: ${it.message}") }
            }
            if (debugLogging()) {
                android.util.Log.d(TAG, "projection: ${rows.size} row(s) updated")
            }
        }
        // One decrypt-retry wait feeding the predicate: pending encrypted
        // events aren't admitted yet; a real message decrypts within seconds.
        // No loop — a room that stays pending re-enters via its next sync
        // round, and once stale the predicate drops it for good.
        if (pendingDecrypt.isNotEmpty()) {
            scope.launch {
                delay(PROJECTION_RECHECK_MS)
                yieldToSyncIngest()
                recomputeProjectionRows(c, pendingDecrypt)
            }
        }
        return rows.size
    }

    // -------------------------------------------------------------------------
    // ThreadRow ingest writer (docs/THREAD-STORE-SPEC.md §2) — the message-page
    // counterpart of the projection above: sync-round events materialize into
    // the ThreadRow store at ingest (rules in ThreadRowLogic, SQL in
    // ThreadRowStore), so a room open becomes a paged SELECT (Task 4) instead
    // of a read-time recompute.

    /** The serial sync-event seam, the exact flow + DEFAULT priority of
     *  [observeProjectionIngest]: changed rooms = `room.join` keys, one
     *  yield-gated ingest launch per round. */
    private fun observeThreadStoreIngest(c: MatrixClient) {
        scope.launch {
            try {
                c.api.sync.subscribeAsFlow().collect { syncEvents ->
                    val join = syncEvents.syncResponse.room?.join ?: return@collect
                    if (join.isEmpty()) return@collect
                    scope.launch {
                        yieldToSyncIngest()
                        ingestThreadStoreRound(c, join)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "thread-store ingest observer ended: ${e.message}")
            }
        }.also { notificationWatcherJobs.add(it) }
    }

    /** One sync round → ThreadRow rows per changed room. Round events are read
     *  back out of the persisted `TimelineEvent` JSON (persisted before the
     *  DEFAULT-priority flow emits): the raw JSON yields the type string,
     *  pre-v11 top-level `redacts` and the resolved content JSON
     *  ([RawEventInput.contentJson]); the decoded [TimelineEvent] yields the
     *  typed resolved content for the render-ready body/media derivation —
     *  [messageFrom], the same fields the current read path serves. An event
     *  whose content is still unresolved writes a placeholder row (encrypted=1,
     *  body null, SPEC §4); an event the page would not render ([messageFrom]
     *  null: blank texts, replace edits, non-renderable content) writes
     *  nothing — replace edits classify as their own edit side row. */
    private suspend fun ingestThreadStoreRound(
        c: MatrixClient,
        join: Map<RoomId, Sync.Response.Rooms.JoinedRoom>,
    ) {
        for ((roomId, joinedRoom) in join) {
            // Receipts are sync-ephemeral: a receipts-only round carries no
            // timeline events, so without this check it never bumped the
            // revision and an open thread froze its "seen" flags. The serve
            // path reads RoomUserReceipts fresh ([serveFromStore]), so a bump
            // is all the re-serve needs.
            val hasReceipts = joinedRoom.ephemeral?.events
                ?.any { it.content is ReceiptEventContent } == true
            val eventIds = joinedRoom.timeline?.events?.map { it.id.full }.orEmpty()
            if (eventIds.isEmpty()) {
                if (hasReceipts) bumpMessagePageRevision(roomId.full)
                continue
            }
            val rows = threadRowsFromRound(c, roomId, eventIds)
            if (rows.isEmpty()) {
                if (hasReceipts) bumpMessagePageRevision(roomId.full)
                continue
            }
            // Seeded pseudo reaction retirement + write — the shared
            // write-through seam ([writeThreadRowsThrough]): the first REAL
            // reaction row a round writes for a target retires the target's
            // `seed:`-pseudo reaction rows (label senders) — seeded tags must
            // not outlive the truth.
            val (inserted, retired) = writeThreadRowsThrough(c, roomId.full, rows)
            // A 0-row round (every event skipped as a re-delivery) must not
            // re-serve the tool spuriously — but a retirement removed rows,
            // and receipts changed the served seen flags: both bump.
            if (inserted > 0 || retired || hasReceipts) bumpMessagePageRevision(roomId.full)
            if (inserted > 0 && debugLogging()) {
                android.util.Log.d(
                    TAG,
                    "thread-store: ${roomId.full.takeLast(12)} wrote $inserted row(s) " +
                        "(${rows.count { it.encrypted == 1 }} pending decrypt)",
                )
            }
        }
    }

    /** [ThreadRowLogic.buildRows] inputs + rendered overrides for one room's
     *  round events (see [ingestThreadStoreRound]). */
    private suspend fun threadRowsFromRound(
        c: MatrixClient,
        roomId: RoomId,
        eventIds: List<String>,
    ): List<ThreadRowValues> {
        val stored = readStoredTimelineEvents(c, roomId, eventIds)
        if (stored.isEmpty()) return emptyList()
        // Broadcast own-name: stored bodies are PRE-STRIP (the same value the
        // read path uses), so store-served pages render raw with parity.
        val ownName = broadcastOwnNameOf(c, roomId)
        val rendered = HashMap<String, LightServiceMethod.GetMessages.Message>(stored.size)
        val inputs = ArrayList<RawEventInput>(stored.size)
        for (storedEvent in stored) {
            val eventId = storedEvent.event.event.id.full
            val resolved = storedEvent.event.content?.getOrNull()
            if (resolved != null) messageFrom(c, roomId, storedEvent.event, ownName = ownName)?.let {
                rendered[eventId] = it
            }
            val type = storedEvent.type ?: continue
            inputs += RawEventInput(
                eventId = eventId,
                type = type,
                sender = storedEvent.event.event.sender.full,
                originTs = storedEvent.event.event.originTimestamp,
                contentJson = storedEvent.contentJson,
                // Non-null means "content resolved" (ThreadRowLogic's
                // undecrypted check keys off this for m.room.encrypted) — the
                // real render body rides on [rendered].
                decryptedBody = resolved?.let { (it as? RoomMessageEventContent)?.body ?: "" },
                formattedBody = null,
                prevEventId = storedEvent.event.previousEventId?.full,
                batchBefore = storedEvent.event.gap?.batchBefore,
                redactsTopLevel = storedEvent.redactsTopLevel,
            )
        }
        val built = ThreadRowLogic.buildRows(roomId.full, 0, inputs)
        val storedByEvent = stored.associateBy { it.event.event.id.full }
        val out = ArrayList<ThreadRowValues>(built.size)
        for (row in built) {
            if (row.kind == RowKind.EDIT.wire) {
                out += editRowForStore(row, storedByEvent[row.eventId], ownName)
                continue
            }
            // The user's OWN just-sent message whose sync echo landed before
            // Trixnity persisted the decrypted content: skipping (not writing
            // an encrypted placeholder) is the documented design (PLAN.md
            // "undecrypted pending-echo events are skipped in the page — the
            // optimistic send row covers display until the real row lands",
            // 2026-08-14 fix) — on re-entering the room a placeholder read as
            // "[Encrypted message]" for the user's own send. Other users'
            // placeholders are kept — the recheck fills them when the key
            // lands.
            if (row.kind == RowKind.MESSAGE.wire && row.encrypted == 1 && row.sender == c.userId.full) {
                continue
            }
            if (row.kind != RowKind.MESSAGE.wire || row.encrypted == 1) {
                out += row
                continue
            }
            val msg = rendered[row.eventId] ?: continue
            out += row.copy(
                body = msg.body,
                formattedHtml = msg.formattedHtml,
                contentType = msg.contentType,
                mediaMeta = mediaMetaJsonOf(msg),
            )
        }
        return out
    }

    /**
     * The edit side row as the store keeps it. [ThreadRowLogic.buildRows]
     * carries the raw `m.new_content` body in [ThreadRowValues.payload]; the
     * writer rebases it onto the exact body today's read path serves for an
     * edited row (reply-quote / forward-header / own-prefix strip) and parks
     * the edit's own formatted HTML on `mediaMeta` — the store has no edit
     * html column, and today's page rewrites formattedHtml from the edit
     * (or nulls it when the edit is plain), which the serving path mirrors.
     */
    private fun editRowForStore(
        row: ThreadRowValues,
        storedEvent: StoredTimelineEvent?,
        ownName: String?,
    ): ThreadRowValues {
        val newContent = storedEvent?.event?.content?.getOrNull()
            ?.let { content -> (content as? RoomMessageEventContent)?.relatesTo as? RelatesTo.Replace }
            ?.newContent as? RoomMessageEventContent.TextBased
            ?: return row
        val stripped = stripOwnPrefix(stripForwardHeader(stripReplyQuote(newContent.body)).first, ownName)
        val html = newContent.takeIf { it.format == "org.matrix.custom.html" }
            ?.formattedBody
            ?.takeIf { it.isNotBlank() }
            ?.let { MX_REPLY_REGEX.replace(it, "").trim() }
            ?.takeIf { it.isNotBlank() }
            ?.let { stripForwardHeaderFromHtml(it).first.takeIf { h -> !h.isNullOrBlank() } }
        return row.copy(payload = stripped, mediaMeta = html)
    }

    /** One persisted `TimelineEvent` row: the decoded event plus the raw
     *  stored JSON facts the typed model doesn't surface — the event type
     *  string, pre-v11 top-level `redacts`, and the resolved content as raw
     *  JSON (Trixnity re-persists the decrypted payload under the
     *  `{type, value}` `content` wrapper; unencrypted events keep their parsed
     *  content inside the event JSON). */
    private data class StoredTimelineEvent(
        val event: TimelineEvent,
        val type: String?,
        val redactsTopLevel: String?,
        val contentJson: String?,
    )

    /** Reads [eventIds]' rows straight out of the `TimelineEvent` table (the
     *  store decode [readTimelineChainFromDb] uses), preserving the given
     *  order — the sync round's oldest-first timeline order. Events not in
     *  the store yet are skipped. */
    private suspend fun readStoredTimelineEvents(
        c: MatrixClient,
        roomId: RoomId,
        eventIds: List<String>,
    ): List<StoredTimelineEvent> {
        val db = runCatching { c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class) }.getOrNull()
            ?: return emptyList()
        val json = runCatching { c.di.get<Json>() }.getOrNull() ?: return emptyList()
        val values = withContext(Dispatchers.IO) {
            runCatching {
                val out = HashMap<String, String>(eventIds.size)
                val sq = db.openHelper.writableDatabase
                eventIds.chunked(100).forEach { chunk ->
                    val holes = chunk.joinToString(",") { "?" }
                    sq.query(
                        "SELECT eventId, value FROM TimelineEvent WHERE roomId=? AND eventId IN ($holes)",
                        listOf<Any?>(roomId.full).plus(chunk).toTypedArray(),
                    ).use { cur ->
                        while (cur.moveToNext()) {
                            val id = cur.getString(0) ?: continue
                            val value = cur.getString(1) ?: continue
                            out[id] = value
                        }
                    }
                }
                out
            }.getOrDefault(emptyMap())
        }
        return eventIds.mapNotNull { id ->
            val value = values[id] ?: return@mapNotNull null
            val event = runCatching { json.decodeFromString<TimelineEvent>(value) }.getOrNull()
                ?: return@mapNotNull null
            val root = runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull()
                ?: return@mapNotNull null
            val eventJson = root["event"] as? JsonObject
            val type = eventJson?.get("type")?.jsonPrimitive?.contentOrNull
            StoredTimelineEvent(
                event = event,
                type = type,
                redactsTopLevel = eventJson?.get("redacts")?.jsonPrimitive?.contentOrNull,
                // An encrypted event without Trixnity's re-persisted
                // {type, value} wrapper is still ciphertext — contentJson
                // must be null then (RawEventInput's resolved-content
                // contract), never the m.room.encrypted payload.
                contentJson = ((root["content"] as? JsonObject)?.get("value")
                    ?: if (type == "m.room.encrypted") null else eventJson?.get("content"))
                    ?.let { runCatching { json.encodeToString(it) }.getOrNull() },
            )
        }
    }

    /** The store's mediaMeta JSON (SPEC §1): the media fields the current page
     *  rows serve ([LightServiceMethod.GetMessages.Message.durationMs /
     *  caption / forwarded]), derived by [messageFrom] exactly as the read
     *  path derives them. */
    private fun mediaMetaJsonOf(msg: LightServiceMethod.GetMessages.Message): String? {
        val json = buildJsonObject {
            msg.durationMs?.let { put("durationMs", it) }
            msg.caption?.let { put("caption", it) }
            if (msg.forwarded) put("forwarded", true)
        }
        return json.toString().takeIf { it != "{}" }
    }

    /** Placeholder recheck loop (SPEC §2/§4): a 30 s tick over rooms holding
     *  `encrypted=1` rows, bounded per pass ([THREAD_ROW_RECHECK_LIMIT] rows /
     *  [THREAD_ROW_RECHECK_ROOMS] rooms). The key-backup restore
     *  ([restoreRoomSessions]) runs HERE instead of on every room open — its
     *  re-decrypt re-persists decrypted content into the `TimelineEvent`
     *  rows, the pass then fills the placeholder rows in place (the store's
     *  re-delivery path keeps the original ingestSeq) and bumps the page
     *  revision so an open thread repaints. The loop ends when the store has
     *  no placeholders left; new ones arrive via the ingest hook. */
    private fun startThreadStoreRecheckLoop(c: MatrixClient) {
        scope.launch {
            while (isActive) {
                delay(THREAD_ROW_RECHECK_MS)
                yieldToSyncIngest()
                runCatching { recheckThreadStorePlaceholders(c) }
                    .onFailure { android.util.Log.w(TAG, "thread-store recheck failed: ${it.message}") }
            }
        }.also { notificationWatcherJobs.add(it) }
    }

    /** One recheck pass over the store's placeholder rows (see
     *  [startThreadStoreRecheckLoop]). The scan start rotates each pass
     *  ([recheckPass]): the fixed `ORDER BY roomId, ingestSeq` head let >8
     *  permanently-stuck rooms occupy the [THREAD_ROW_RECHECK_ROOMS] window
     *  forever and starve every room after them — the recheck is the only
     *  healer left (the read-path restore was retired), so every placeholder
     *  must eventually get a pass. */
    private suspend fun recheckThreadStorePlaceholders(c: MatrixClient) {
        val total = ThreadRowStore.pendingCount(c)
        if (total == 0) return
        val pass = recheckPass++
        val offset = ((pass * THREAD_ROW_RECHECK_LIMIT) % total).toInt()
        val pending = ThreadRowStore.pendingRows(c, THREAD_ROW_RECHECK_LIMIT, offset)
        if (pending.isEmpty()) return
        var filled = 0
        var dropped = 0
        for ((roomId, roomRows) in pending.groupBy { it.roomId }.entries.take(THREAD_ROW_RECHECK_ROOMS)) {
            val matrixRoomId = RoomId(roomId)
            // Same pre-strip convention as the live ingest: filled bodies are
            // what the read path serves.
            val ownName = broadcastOwnNameOf(c, matrixRoomId)
            val eventIds = roomRows.map { it.eventId }
            // Key-backup restore, bounded: only the pass's placeholder events,
            // same park-on-zero dance as the read path (the park gates the
            // backup network round-trip inside restoreRoomSessions).
            val stuck = readStoredTimelineEvents(c, matrixRoomId, eventIds).map { it.event }
            if (stuck.isNotEmpty() &&
                runCatching { restoreRoomSessions(c, matrixRoomId, stuck) }.getOrDefault(0) == 0
            ) {
                decryptRestoreCooldown.park(roomId, DECRYPT_RESTORE_COOLDOWN_MS)
            }
            val fills = ArrayList<ThreadRowValues>(roomRows.size)
            var roomDropped = 0
            for (stored in readStoredTimelineEvents(c, matrixRoomId, eventIds)) {
                val row = roomRows.first { it.eventId == stored.event.event.id.full }
                if (stored.event.content?.getOrNull() == null) continue // still pending — next pass
                val msg = messageFrom(c, matrixRoomId, stored.event, ownName = ownName)
                if (msg == null) {
                    // Decrypted to something the page would not render (blank
                    // text, tombstone) — drop the placeholder, same rule the
                    // read path applies to such events.
                    ThreadRowStore.deleteRow(c, roomId, row.eventId)
                    roomDropped++
                    dropped++
                    continue
                }
                fills += row.copy(
                    body = msg.body,
                    formattedHtml = msg.formattedHtml,
                    contentType = msg.contentType,
                    mediaMeta = mediaMetaJsonOf(msg),
                    encrypted = 0,
                )
            }
            if (fills.isNotEmpty()) {
                ThreadRowStore.writeRows(c, fills)
                filled += fills.size
            }
            // Any pass change (fill or drop) repaints an open thread.
            if (fills.isNotEmpty() || roomDropped > 0) bumpMessagePageRevision(roomId)
        }
        if (debugLogging() && (filled > 0 || dropped > 0)) {
            android.util.Log.d(TAG, "thread-store recheck: $filled filled, $dropped dropped")
        }
    }

    private data class ProjectedRoom(val row: ProjectionRow?, val pendingDecryption: Boolean)

    /** Newest-first chain walk through the predicate: first admitted event is
     *  the projection head, admitted events after the own receipt cursor are
     *  the unread count. The walked window doubles as the flood-ghost
     *  context (no second store walk). */
    private suspend fun projectRoom(c: MatrixClient, roomId: RoomId): ProjectedRoom? {
        val room = runCatching {
            withTimeoutOrNull(ROOM_BUDGET_MS) { c.room.getById(roomId).firstOrNull() }
        }.getOrNull() ?: return null
        val startId = room?.lastEventId?.full ?: return ProjectedRoom(null, false)
        val chain = readTimelineChainFromDb(c, roomId, startId, PROJECTION_WALK_MAX)
            ?: return ProjectedRoom(null, false)
        val events = chain.first
        if (events.isEmpty()) return ProjectedRoom(null, false)
        val own = c.userId.full
        val ownTs = ownReceiptTs(c, roomId.full)
        val now = System.currentTimeMillis()
        var lastId: String? = null
        var lastTs = 0L
        var lastPreview = ""
        var lastPreviewResolved = false
        var unread = 0L
        var pendingDecryption = false
        // Existing row's head ts, fetched lazily: only a room whose walk meets
        // a `batch/` txn event (bridge history re-import) pays the one indexed
        // SELECT — the replay rule compares against row existence (null =
        // no row yet).
        var existingHeadTs: Long? = null
        var fetchedExistingRow = false
        for (te in events) {
            val raw = te.event.content
            val isEncrypted = raw is EncryptedMessageEventContent
            val decryptedOk = te.content?.getOrNull() != null
            val messageClass = isEncrypted || raw is RoomMessageEventContent
            val originTs = te.event.originTimestamp
            val sender = te.event.sender.full
            val txn = txnIdOf(te)
            var isBatchReplay = false
            if (txn != null && txn.startsWith(ProjectionPredicate.BATCH_TXN_PREFIX)) {
                if (existingHeadTs == null && !fetchedExistingRow) {
                    existingHeadTs = projectionRow(c, roomId.full)?.lastRealTs
                    fetchedExistingRow = true
                }
                isBatchReplay = ProjectionPredicate.batchReplay(txn, existingHeadTs)
            }
            val renders = ProjectionPredicate.renders(
                messageClass = messageClass,
                isReplaceEdit = isReplaceEdit(te),
                originTs = originTs,
                now = now,
                isEncrypted = isEncrypted,
                decryptedOk = decryptedOk,
                isFlood = isFloodGhost(c, te, events),
                isBatchReplay = isBatchReplay,
            )
            if (isEncrypted && !decryptedOk && !ProjectionPredicate.encryptedStale(originTs, now)) {
                pendingDecryption = true
            }
            if (renders) {
                if (sender != own && (ownTs == null || originTs > ownTs)) unread++
                if (lastId == null) {
                    lastId = te.event.id.full
                    lastTs = originTs
                    lastPreview = previewText(te) ?: ""
                    lastPreviewResolved = lastPreview.isNotBlank()
                }
            }
            if (lastId != null && ownTs != null && originTs < ownTs) break
        }
        val row = ProjectionRow(
            roomId = roomId.full,
            lastRealEventId = lastId,
            lastRealTs = lastTs,
            // No own receipt row yet (fresh room): the cursor has no ground
            // truth — count everything admitted in the window (bounded by
            // PROJECTION_WALK_MAX), the receipt lands on a later round.
            unreadCount = unread,
            preview = lastPreview,
            previewResolved = lastPreviewResolved,
        )
        return ProjectedRoom(row, pendingDecryption)
    }

    /** The projection row for [roomId], or null when the table isn't ready /
     *  the room isn't projected yet (pre-backfill) — consumers fall back to
     *  the derive-at-read path for those. */
    private suspend fun projectionRow(c: MatrixClient, roomId: String): ProjectionRow? {
        val db = runCatching { c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class) }.getOrNull()
            ?: return null
        if (!projectionTableReady) return null
        return chainDb("projectionRow") {
            withContext(Dispatchers.IO) {
                runCatching {
                    db.openHelper.writableDatabase.query(
                        "SELECT lastRealEventId,lastRealTs,unreadCount,preview,previewResolved " +
                            "FROM RoomProjection WHERE roomId=?",
                        arrayOf(roomId),
                    ).use { cur ->
                        if (cur.moveToFirst() && !cur.isNull(1)) ProjectionRow(
                            roomId = roomId,
                            lastRealEventId = if (cur.isNull(0)) null else cur.getString(0),
                            lastRealTs = cur.getLong(1),
                            unreadCount = cur.getLong(2),
                            preview = cur.getString(3) ?: "",
                            previewResolved = cur.getInt(4) != 0,
                        ) else null
                    }
                }.getOrNull()
            }
        }
    }

    /** Optimistic badge clear at [markRead]: zero the row now; the receipt
     *  echo round recomputes to the same value. */
    private fun projectionMarkRead(roomId: String) {
        scope.launch {
            val c = client ?: return@launch
            val db = runCatching { c.di.get<TrixnityRoomDatabase>(TrixnityRoomDatabase::class) }.getOrNull()
                ?: return@launch
            if (!projectionTableReady) return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE RoomProjection SET unreadCount=0 WHERE roomId=?",
                        arrayOf<Any?>(roomId),
                    )
                }
            }
        }
    }
    // ---- end ingest-time projection -----------------------------------------

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

    /**
     * The "[Message unsent]" tombstone row for a redacted message event (Phase
     * C): id/sender/name/time preserved so the row stays in its
     * conversation slot, rendered like a normal text row. Reactions/status/read never apply to a redacted event.
     */
    private suspend fun redactedRow(
        c: MatrixClient,
        roomId: RoomId,
        te: TimelineEvent,
    ): com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message {
        val sender = te.event.sender
        return com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message(
            id = te.event.id.full,
            sender = sender.full,
            senderName = senderNameOf(c, roomId, sender),
            body = "[Message unsent]",
            timestampMs = te.event.originTimestamp,
            isMine = sender == c.userId,
            contentType = "redacted",
        )
    }

    private suspend fun messageFrom(
        c: MatrixClient,
        roomId: RoomId,
        te: TimelineEvent,
        sendStatus: String? = null,
        read: Boolean = false,
        reactions: List<String> = emptyList(),
        editedBody: String? = null,
        editedContent: RoomMessageEventContent? = null,
        edited: Boolean = false,
        ownName: String? = null,
    ): com.thelightphone.sdk.shared.LightServiceMethod.GetMessages.Message? {
        // The resolved content decides the row type: a decrypted image event
        // renders as an image row (its bytes fetched via GetMessageMedia), an
        // audio event as a playable voice-note row (played via PlayVoiceNote);
        // every other showable event renders as text. Text bodies are the full
        // message — the 80-char preview cap is only for the room list.
        val content = te.content?.getOrNull()?.let { orig ->
            // A bridge media edit (m.replace) rewrites a pending m.notice into
            // the real m.image/m.video/m.audio — classify the row from the
            // edit's new content, or it stays a system line wearing the edited
            // file name (gmessages). Text edits keep the original
            // classification (their body swap rides on [editedBody]).
            (editedContent as? RoomMessageEventContent.FileBased) ?: orig
        }
        // Edit events (m.replace) never become a row — they REPLACE their
        // target, and the target row is rebuilt with the edited body + the
        // edited flag by [computeMessagesPage]. Beeper's
        // re-import edits target originals we never sync (whatsapp.com bridge
        // rooms), so un-matched edits still render as nothing here.
        if (isReplaceEdit(te)) return null
        // A WhatsApp forward's "↷ Forwarded" header (stripForwardHeader) is
        // lifted out of the body; the flag makes the tool render it as a small
        // chip above the content. Text rows report it here; media
        // rows carry it on their caption (a forwarded photo's caption IS the
        // bare header).
        var forwarded = false
        val (body, formattedHtml, contentType) = when (content) {
            is RoomMessageEventContent.FileBased.Image ->
                // The body is the row's fallback label, not the file name — a
                // bare "IMG_0312.JPG" renders like a message (gmessages).
                // Rows with no media uri at all can never fetch
                // ("[Photo — unavailable]"); the rest get "[Photo]" when their
                // bytes are missing.
                Triple(
                    if (content.url.isNullOrBlank() && content.file?.url.isNullOrBlank())
                        "[Photo — unavailable]" else "[Photo]",
                    null,
                    "image",
                )
            is RoomMessageEventContent.FileBased.Audio ->
                Triple(
                    content.fileName?.takeIf { it.isNotBlank() } ?: "Voice note",
                    null,
                    "audio",
                )
            is RoomMessageEventContent.FileBased.Video ->
                // A video (incl. WhatsApp animated GIFs, which arrive as
                // m.video) renders as an image row fed by a server-side
                // thumbnail frame ([videoThumbnail] via GetMessageMedia — no
                // playback, the SDK has no video primitive); the tool tags
                // the thumbnail "[Video]". The fallback label when the
                // thumbnail can't fetch stays the body below.
                Triple("[Video]", null, "video")
            is RoomMessageEventContent.FileBased.File ->
                // RCS direct photos arrive as m.file with an image/* mimetype
                // — render those as image rows so the
                // media actually fetches; other m.file stays a "[File]" row.
                if (content.info?.mimeType?.startsWith("image/", ignoreCase = true) == true)
                    Triple(
                        if (content.url.isNullOrBlank() && content.file?.url.isNullOrBlank())
                            "[Photo — unavailable]" else "[Photo]",
                        null,
                        "image",
                    )
                else Triple("[File]", null, "text")
            is RoomMessageEventContent.TextBased -> {
                // m.notice = bridge system messages ("Turned off disappearing
                // messages", timer-set notices… — the mautrix bridge sends them
                // as notices from the contact's own ghost, so sender can't
                // distinguish them). The tool renders them as a small centered
                // system line instead of a normal message.
                // A BLANK body renders nothing — the bridge's re-import copies
                // (09:08 wall after a WhatsApp number change) resolve to empty
                // text, and an empty bubble reads as "no messages" (LP3: the
                // Lillian room). Drop them so the real conversation shows.
                // The edited body (the m.new_content of an m.replace) replaces
                // the original when the target is in the page.
                // Broadcast channels bake the user's own name into echoed posts
                // ("FENN: post") — stripped when ownName is set.
                val (stripped, fwd) = stripForwardHeader(stripReplyQuote(editedBody ?: content.body))
                forwarded = fwd
                val text = stripOwnPrefix(stripped, ownName)
                if (text.isBlank()) return null
                // Formatted incoming (chats markdown render): only when the
                // event carries the custom-html format — the <mx-reply> block
                // (Beeper's reply fallback) is stripped; the remainder feeds
                // the tool's AnnotatedString converter. An edited text uses
                // the edit's own content when it has one.
                val rawHtml = (editedContent as? RoomMessageEventContent.TextBased ?: content)
                    .takeIf { it.format == "org.matrix.custom.html" }
                    ?.formattedBody
                    ?.takeIf { it.isNotBlank() }
                    ?.let { MX_REPLY_REGEX.replace(it, "").trim().takeIf { h -> h.isNotBlank() } }
                // The forward header lives in the formatted variant too (the
                // plain body's was stripped above) — otherwise the row renders
                // "↷ Forwarded" twice (LP3 feedback 2026-09-09). mautrix marks
                // its header paragraph with `data-mx-forwarded-notice`, which
                // the plain flag may miss, so the HTML strips on its own match
                // and reports the flag back (LP3 feedback 2026-09-13).
                val (strippedHtml, forwardedByHtml) =
                    rawHtml?.let { stripForwardHeaderFromHtml(it) } ?: (null to false)
                forwarded = forwarded || forwardedByHtml
                val formattedHtml = strippedHtml?.takeIf { it.isNotBlank() }
                Triple(
                    text,
                    formattedHtml,
                    if (content is RoomMessageEventContent.TextBased.Notice) "notice" else "text",
                )
            }
            else -> Triple(previewText(te)?.takeIf { it.isNotBlank() } ?: return null, null, "text")
        }
        // The media caption (the m.image / m.video body — most clients put the
        // caption there, separate from the file name). A caption that equals
        // the file name is not a caption; neither
        // is a bare file name — Signal's m.image body IS "image.jpg" with no
        // caption. A forwarded photo's caption is the
        // bare "↷ Forwarded" header (or the header + a real caption) —
        // stripped here, and the forwarded flag carries the header to the
        // tool's chip.
        val (caption, forwardedByCaption) = when (content) {
            is RoomMessageEventContent.FileBased.Image ->
                captionOf(content)?.let { stripForwardHeader(it) } ?: (null to false)
            is RoomMessageEventContent.FileBased.Video ->
                captionOf(content)?.let { stripForwardHeader(it) } ?: (null to false)
            else -> null to false
        }
        val sender = te.event.sender
        // Reply context (m.in_reply_to): the relation sits on the ORIGINAL
        // event (edits replace the body, the reply fields ride along), so
        // an edited reply still shows its excerpt header. The target event
        // comes from the same local timeline lookup the send path uses
        // (a store read, no network); sender/excerpt stay null when the
        // target is gone or undecryptable.
        val replyTargetId = (content as? RoomMessageEventContent)?.relatesTo
            ?.let { it as? RelatesTo.Reply }?.replyTo?.eventId?.full
        var replyToSender: String? = null
        var replyToExcerpt: String? = null
        if (replyTargetId != null) {
            withTimeoutOrNull(ROOM_BUDGET_MS) {
                c.room.getTimelineEvent(roomId, EventId(replyTargetId)).firstOrNull()
            }?.let { target ->
                replyToSender = senderNameOf(c, roomId, target.event.sender)
                replyToExcerpt = replyExcerptFor(target.content?.getOrNull() as? RoomMessageEventContent)
            }
        }
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
            // Collapsed here (and at the cached-page merge) so rows never carry
            // more than two reaction tags (feedback 2026-09-02).
            reactions = collapseReactionTags(reactions),
            // The voice-note row shows the length + playing progress. Bridged
            // notes often carry no info.duration (Signal — ):
            // fall back to the length measured at prefetch/play time.
            durationMs = (content as? RoomMessageEventContent.FileBased.Audio)?.let { audio ->
                audio.info?.duration ?: voiceDurationMsByEvent[te.event.id.full]
            },
            caption = caption?.takeIf { it.isNotBlank() },
            forwarded = forwarded || forwardedByCaption,
            edited = edited && contentType == "text",
            formattedHtml = if (contentType == "text") formattedHtml else null,
            replyToId = replyTargetId,
            replyToSender = replyToSender,
            replyToExcerpt = replyToExcerpt,
        )
    }

    /** Excerpt cap for a reply header (the original message's first line). */
    private const val REPLY_EXCERPT_MAX = 80

    /** One-line excerpt of a reply target's body: the reply fallback quote
     *  stripped (a reply to a reply), the first non-empty line, capped with an
     *  ellipsis. */
    private fun replyExcerptOf(body: String): String? =
        stripReplyQuote(body).lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > REPLY_EXCERPT_MAX) it.take(REPLY_EXCERPT_MAX - 1) + "…" else it }

    /** One-line excerpt of a reply TARGET of any kind — a text target's first
     *  line, otherwise the media row's own label ("[Photo]" / "[Video]" / the
     *  voice note's file name). A reply to a photo or voice note previously
     *  quoted nothing (the excerpt was read from text targets only), so the
     *  reply header rendered empty (LP3 feedback 2026-09-12). The labels match
     *  the body the media row itself shows, so the tool's optimistic reply
     *  (`replyExcerptOf(target.body)`) and this resolved excerpt agree. */
    private fun replyExcerptFor(content: RoomMessageEventContent?): String? = when (content) {
        is RoomMessageEventContent.TextBased -> replyExcerptOf(content.body)
        is RoomMessageEventContent.FileBased.Image -> "[Photo]"
        is RoomMessageEventContent.FileBased.Video -> "[Video]"
        is RoomMessageEventContent.FileBased.Audio ->
            content.fileName?.takeIf { it.isNotBlank() } ?: "Voice note"
        is RoomMessageEventContent.FileBased.File ->
            if (content.info?.mimeType?.startsWith("image/", ignoreCase = true) == true) "[Photo]"
            else "[File]"
        else -> null
    }

    private suspend fun senderNameOf(c: MatrixClient, roomId: RoomId, sender: UserId): String =
        withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.user.getById(roomId, sender).firstOrNull()?.name
        } ?: sender.localpart

    /** The sender's caption for a media message — the m.image / m.video body
     *  (most clients put the caption there, separate from the file name). A
     *  caption that equals the file name is not a caption; neither is a bare file name — Signal's m.image body IS
     *  "image.jpg" with no caption. Inline markdown is stripped
     *  ([MarkdownConverter.plainInline]): Instagram/Telegram captions arrive as
     *  "[clean-url](url-with-tracking)" and the row renders plain text, so the
     *  label is what shows (LP3 feedback 2026-09-12 — the raw URL pair was). */
    private fun captionOf(file: RoomMessageEventContent.FileBased): String? =
        file.body.takeIf {
            it.isNotBlank() && it != file.fileName && !isBareFilename(it)
        }?.let(MarkdownConverter::plainInline)

    /** A bare media file name ("image.jpg", "VID_2024.mp4") is not a caption —
     *  no whitespace, ends with a common image/video extension. */
    private fun isBareFilename(body: String): Boolean {
        if (body.any { it.isWhitespace() }) return false
        val dot = body.lastIndexOf('.')
        if (dot <= 0 || dot == body.length - 1) return false
        val ext = body.substring(dot + 1)
        return ext.length <= 5 && ext.all { it.isLetter() } &&
            ext.lowercase() in BARE_MEDIA_EXTENSIONS
    }

    private val BARE_MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "avif",
        "mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp",
    )

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
                is RoomMessageEventContent.TextBased ->
                    stripForwardHeader(stripReplyQuote(content.body)).first.take(MAX_PREVIEW_LENGTH)
                is RoomMessageEventContent.FileBased -> when (content) {
                    is RoomMessageEventContent.FileBased.Image ->
                        // A caption beats the generic "[Photo]" placeholder in
                        // the room list (feedback round 2026-08-19).
                        content.body.takeIf { it.isNotBlank() && it != content.fileName }
                            ?.take(MAX_PREVIEW_LENGTH) ?: "[Photo]"
                    // RCS direct photos are m.file + image/* (feedback 2026-09-01).
                    is RoomMessageEventContent.FileBased.File ->
                        if (content.info?.mimeType?.startsWith("image/", ignoreCase = true) == true) "[Photo]"
                        else "[File]"
                    is RoomMessageEventContent.FileBased.Video -> "[Video]"
                    is RoomMessageEventContent.FileBased.Audio -> "[Audio]"
                    else -> "[File]"
                }
                else -> null
            }
        }
        return when {
            // A genuinely undecryptable message renders as a single calm
            // placeholder.
            // Two render paths: a real decrypt FAILURE shows it outright; a
            // still-PENDING decrypt (content unresolved, raw content still
            // m.room.encrypted) skips the row only while the event is young —
            // new messages never flash the placeholder.
            // Past [DECRYPT_PENDING_PLACEHOLDER_AFTER_MS] pending means stuck:
            // skipping the row froze older-page pagination and markRead behind
            // the head (unread flag stuck — LP3 window), so the
            // placeholder renders and the row exists; a late-arriving key
            // re-renders it as real content.
            te.content?.isFailure == true -> ThreadRowLogic.ENCRYPTED_PLACEHOLDER_BODY
            else ->
                if (System.currentTimeMillis() - te.event.originTimestamp >
                    DECRYPT_PENDING_PLACEHOLDER_AFTER_MS &&
                    te.event.content is EncryptedMessageEventContent
                ) ThreadRowLogic.ENCRYPTED_PLACEHOLDER_BODY else null
        }
    }

    /** Removes the leading quoted block of a Matrix reply so only the new text
     *  shows. Returns the full text — callers cap it where a preview is wanted. */
    private fun stripReplyQuote(body: String): String {
        if (!body.startsWith(">")) return body
        val index = body.indexOf("\n\n")
        return if (index != -1) body.substring(index + 2).trimStart() else body
    }

    /** The <mx-reply> fallback block Beeper/Element bake into a formatted
     *  reply's `formatted_body` — stripped from the HTML the tool renders
     *  (same precedent as [stripReplyQuote] on the plain body). */
    private val MX_REPLY_REGEX = Regex("<mx-reply>[\\s\\S]*?</mx-reply>")

    // The forward-header strip moved to ForwardHeaderStrip.kt (top-level, same
    // package) so it can be unit-tested without initializing this object.

    /** In broadcast rooms (you + the channel ghost) Beeper echoes your own
     *  channel posts back with your display name baked into the body ("FENN:
     *  post" — Telegram channels). Strip that redundant
     *  prefix; ownName is null outside broadcast rooms, so it's a no-op there.
     *  Not applied unconditionally: a group member writing "FENN: good point"
     *  must keep their words. */
    private fun stripOwnPrefix(text: String, ownName: String?): String =
        if (ownName != null && text.startsWith("$ownName: ")) text.removePrefix("$ownName: ") else text

    /**
     * The broadcast-channel own name (the ≤2-member rule): the display name
     * whose "FENN: " prefix the channel's echo bakes into own posts — resolved
     * once per page/round so [messageFrom] can strip it. Null outside
     * broadcast rooms = no stripping. The ingest writer passes the same value
     * so the ThreadRow store keeps pre-strip bodies and the store-served path
     * renders them raw (read-path parity).
     */
    private suspend fun broadcastOwnNameOf(c: MatrixClient, matrixRoomId: RoomId): String? =
        if (withTimeoutOrNull(ROOM_BUDGET_MS) {
            c.room.getById(matrixRoomId).firstOrNull()?.joinedMemberCount
        }?.let { it <= 2L } == true) {
            senderNameOf(c, matrixRoomId, c.userId)
        } else null

    private const val MAX_PREVIEW_LENGTH = 80
    /** How many recent timeline events to scan for Beeper send-status events. */
    private const val SEND_STATUS_WINDOW = 250
    /** Per-room [sendStatusesByEventIdCached] TTL: the statuses only change
     *  when the bridge posts a new one, so a short stale window is invisible
     *  (aligned with the thread's 3 s poll). */
    private const val SEND_STATUS_CACHE_TTL_MS = 15_000L
    /** Flood-context read TTL (see [ghostContext]): the density verdict can't
     *  change within seconds, so a message burst reuses the walk instead of
     *  re-reading 250 events per event. */
    private const val FLOOD_CONTEXT_TTL_MS = 10_000L
    /** Rebuild the network map at most this often (space membership is stable). */
    private const val NETWORK_MAP_TTL_MS = 300_000L
    /** Bound for a full network-map build (600+ room flows on a big account). */
    private const val NETWORK_MAP_BUDGET_MS = 15_000L
    /** Re-fetch a failed bridge contact list no sooner than this (a failure is
     *  usually transient; the backoff stops the room-list pass hammering an
     *  unreachable/auth-rejected endpoint every pass). */
    private const val BRIDGE_CONTACTS_RETRY_MS = 60_000L
    /** Concurrent provision HTTP rounds cap (list fetches + per-contact
     *  resolves share it — see [bridgeHttpPermits]). */
    private const val BRIDGE_HTTP_CONCURRENCY = 3
    /** Rows served by the cold-open fast first page (heavy aux work skipped),
     *  while the full page seeds the store in the background. */
    private const val FAST_FIRST_PAGE_ROWS = 6
    /** Rows of the background cold-open seed page ([getMessages]). */
    private const val THREAD_SEED_PAGE_ROWS = 20
    /** Rebuild a bridge's contact list at most this often (the bridge's own
     *  address book — real numbers incl. LID-resolved, usernames; stable
     *  between changes; battery: one fetch per bridge per hour, only when a
     *  room on that bridge is in the list pass). */
    private const val BRIDGE_CONTACTS_TTL_MS = 3_600_000L
    /** Bound for one bridge contacts fetch (the provision API can be slow). */
    private const val BRIDGE_CONTACTS_BUDGET_MS = 5_000L
    /** Per-room budget for the flags walk ([roomFlagsByRoom]): one room's store
     *  reads + the archive network GET fit in this; a room that exceeds it
     *  keeps its last-known flags instead of being dropped. */
    private const val ROOM_FLAGS_ROOM_BUDGET_MS = 2_000L
    /** Bump to force a fresh /sync filter upload + full initial sync once per
     *  account (see [migrateSyncFilterIfNeeded]) — e.g. when a new room
     *  account-data type joins the filter's whitelist and existing clients'
     *  cached filters would strip it. */
    private const val SYNC_FILTER_MAPPINGS_VERSION = 7
    private const val ROOMS_BUDGET_MS = 15_000L
    private const val ROOM_BUDGET_MS = 3_000L
    private const val MESSAGES_BUDGET_MS = 15_000L
    /** Restore-scan cadence: at most one full crawl per day. */
    private const val RESTORE_INTERVAL_MS = 86_400_000L
    /** Per-room budget + window for the restore scan (the shared
     *  [MESSAGES_BUDGET_MS] / 100-event window is fine for reads, wasteful for
     *  the preemptive crawl). */
    private const val RESTORE_ROOM_BUDGET_MS = 6_000L
    private const val RESTORE_ROOM_EVENTS = 40L
    private const val FETCH_TIMEOUT_SECONDS = 5L
    /** Max extra chain walks an older page may take to skip a run of dropped
     *  events (the m.replace edit wall) before giving up — bounded so a
     *  pathological chain can't turn one page read into a long walk. The
     *  steps are big ([OLDER_PAGE_SKIP_STEP]); re-import walls run 100+
     *  (the Crocs room has 168 consecutive edits). */
    private const val OLDER_PAGE_SKIP_WALKS = 10
    /** Events per guard walk when skipping a dropped-event wall. */
    private const val OLDER_PAGE_SKIP_STEP = 100
    private const val TYPING_TIMEOUT_MS = 30_000L
    private const val DECRYPT_RETRIES = 3
    private const val DECRYPT_RETRY_DELAY_MS = 1_500L
    /** How long a room with a futile key-backup restore stays parked: after a
     *  restore finds nothing to load (0 sessions in the backup), every retry
     *  path stops re-attempting for this long. Long on purpose — these rooms
     *  (pre-verification bridged history) essentially never get their sessions,
     *  and the short retries (60 s preview / 120 s ghost walk) burned ~3 cores
     *  continuously. In-band sync decryption is
     *  unaffected: a session arriving mid-park decrypts the room fresh. */
    private const val DECRYPT_RESTORE_COOLDOWN_MS = 14_400_000L
    private const val DECRYPT_WAIT_MS = 3_000L
    /** Peek budget for events after the first one failed to decrypt. */
    private const val QUICK_DECRYPT_WAIT_MS = 100L
    /** Older than this at render time, a still-PENDING decrypt (content never
     *  resolved) is stuck, not transient: the row renders as "[Encrypted
     *  message]" instead of being skipped. Skipped rows freeze older-page
     *  pagination (the fetch returns the window, no row can render, the next
     *  scroll-up re-requests the same window) and park markRead behind the
     *  head, so the room's unread flag never clears.
     *  Younger events keep the skip — live traffic never flashes the
     *  placeholder. */
    private const val DECRYPT_PENDING_PLACEHOLDER_AFTER_MS = 60_000L
    /** markRead cold-start race: retries while Trixnity's room view loads its
     *  timeline (lastRelevantEventId null) before deciding the receipt. */
    private const val HEAD_RESOLVE_RETRIES = 6
    private const val HEAD_RESOLVE_RETRY_MS = 500L
    /** Marker store cap (see [unsentMessageIds]) — a marker only matters while
     *  the redacted event sits in a page window. */
    private const val UNSENT_MARKER_MAX = 256
    /** Local outbox read for the pending-row state (event id / send error). */
    private const val OUTBOX_READ_TIMEOUT_MS = 500L
    /** Bounded wait for the homeserver ack of a send ([awaitOutboxAck]) —
     *  kept for voice sends only: the recording activity's "sending" state
     *  holds until the RPC returns, and caching the acked id keeps the
     *  pending row from flickering back to SENDING. Text sends
     *  no longer wait. */
    private const val SEND_ACK_WAIT_MS = 500L
    /** Poll cadence on the outbox row while awaiting the ack. */
    private const val SEND_ACK_POLL_INTERVAL_MS = 100L

    /** Gap between send-stall kicker rounds while a send waits for its ack. */
    private const val SEND_KICK_INTERVAL_MS = 5_000L
    /** Whole-outbox read for the restart pending reconstruction (one query;
     *  empty outbox → instant). */
    private const val OUTBOX_RECONSTRUCT_BUDGET_MS = 5_000L
    /** Id prefix of optimistic pending rows (see [pendingEchoRow]) — the row
     *  id matches the tool's "local-<txn>" id so the tool's own row dedupes. */
    private const val LOCAL_PENDING_ID_PREFIX = "local-"

    // Room-list resolver (Phase 5).
    private const val ROOM_NAME_PLACEHOLDER = "…"
    /** [resolveRoomName]'s dead-end fallback — a row showing this is NOT a
     *  resolved name and must be re-resolved on a later pass (heroes land
     *  with the full initial sync, after the slim-phase pass stamped "Chat"
     *  as resolved forever). */
    private const val ROOM_NAME_FALLBACK = "Chat"
    /** Per-pass time budget; the resolver loops until the list is settled. */
    private const val ROOM_LIST_PASS_BUDGET_MS = 12_000L
    /** Breather between passes; a settled pass itself takes milliseconds. */
    private const val ROOM_LIST_REFRESH_DELAY_MS = 2_000L
    /** Resolver breather while the screen is off: the
     *  live bridged account keeps the list dirty, so the 2s breather meant
     *  near-continuous passes overnight; the list only needs freshness for
     *  the next wake. */
    private const val SLOW_RESOLVER_DELAY_MS = 60_000L
    /** Bounded decrypt-wait per room preview. */
    private const val PREVIEW_BUDGET_MS = 1_500L
    /** Per-room state collect in the resolver (the store cache emits instantly). */
    private const val ROOM_LIST_ROOM_BUDGET_MS = 500L

    // Gap-marker backfill (PLAN §8): a `limited=true` sync stores a
    // gap marker whose missing window Trixnity never fills — events created
    // during the missed window are silently absent from the store. The fill is
    // triggered by the page walk and bounded below so it can't burn battery.
    /** Events fetched per gap fill (one windowed GET /rooms/{id}/messages). */
    private const val GAP_BACKFILL_LIMIT = 30L

    /** Part F top-up: how deep the LOCAL chain walk goes per scroll before
     *  handing off to the network gap backfill. The walk is cheap raw SQL over
     *  history Trixnity already has — the 30-event network-page bound made a
     *  549-event room need dozens of scrolls to absorb history that was local
     *  all along (LP3, 2026-09-19). 250 ≈ the projection walk cap. */
    private const val THREAD_TOPUP_WALK_MAX = 250
    /** A fill must complete within this (the sync-aware retry can back off long). */
    private const val GAP_BACKFILL_BUDGET_MS = 8_000L
    /** Wi-Fi fill budget — cold round-trips regularly exceed the cellular one. */
    private const val GAP_BACKFILL_BUDGET_WIFI_MS = 20_000L
    /** After a failed/blocked fill, back off this long before retrying. */
    private const val GAP_BACKFILL_COOLDOWN_MS = 300_000L
    /** Delay before stopping the sync service after an expiry detection. */
    private const val SYNC_STOP_DELAY_MS = 3_000L
    /** Bound for the device-verification check before key-backup work. */
    private const val KEY_BACKUP_VERIFY_TIMEOUT_MS = 1_000L
    /** Bound for a single megolm-session load (can hang waiting for a key). */
    private const val KEY_BACKUP_LOAD_TIMEOUT_MS = 2_000L
    /** How long an interactive device verification may stay open before the
     *  timer cancels it (Trixnity's own timeout events are not surfaced — see
     *  onVerificationState). */
    private const val VERIFICATION_TIMEOUT_MS = 10 * 60_000L

    /** Verification-first sync: delay before the single [swapToFullSync] retry. */
    private const val SYNC_SWAP_RETRY_MS = 5_000L

    /** Verification-first sync: how long [swapToFullSync] waits for the client
     *  init coroutine to finish its one-time filter upload (`started` flag). */
    private const val SYNC_SWAP_INIT_WAIT_MS = 30_000L

    /** Grace window after their SAS start in which an m.unexpected_message
     *  cancel is read as a fan-out collision (two devices answered at once),
     *  not a real cancel — see the Cancel branch in onVerificationState. */
    private const val SAS_COLLISION_GRACE_MS = 10_000L
    /** How long the daily crawl waits for the key-backup version to actually
     *  arrive before concluding there is no server-side backup (the flow emits
     *  null until the service's first fetch — a cold-start null read as "no
     *  backup" stuck the account un-decryptable for a day). */
    private const val KEY_BACKUP_VERSION_BUDGET_MS = 5_000L
    /** How long to wait for an m.secret.send answer after re-requesting the
     *  missing SSSS secrets (process-death recovery, LP3 2026-09-06). */
    private const val SECRET_RE_REQUEST_BUDGET_MS = 15_000L
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
    /** Density-fallback window: a txn-id event inside this many of
     *  [ProjectionPredicate.FLOOD_THRESHOLD] others is a flood. */
    private const val GHOST_BURST_WINDOW_MS = 60_000L

    // Media / photos.
    /**
     * Longest side (px) of the display JPEG served to the tool. Sized so the
     * base64-encoded payload stays comfortably inside the ~1 MB binder
     * transaction limit even for detailed photos — a 1280 px / q82 JPEG could
     * reach 600-900 KB, which base64 inflated past the limit and made image
     * rows fail to load (TransactionTooLargeException → text fallback forever).
     */
    const val DISPLAY_MAX_DIMENSION = 1024
    const val DISPLAY_JPEG_QUALITY = 78
    private const val FLIPBOOK_MAX_DURATION_MS = 15_000L
    private const val FLIPBOOK_MAX_FRAMES = 10
    private const val FLIPBOOK_MAX_DIMENSION = 640
    /** Longest side (px) of the compressed photo uploaded to the room. */
    const val SENT_PHOTO_MAX_DIMENSION = 2048
    const val SENT_PHOTO_JPEG_QUALITY = 85
    /** Bound for a single media download / decode / upload. */
    private const val MEDIA_BUDGET_MS = 10_000L
    /** Re-reads of the event while its content is still decrypting. */
    private const val MEDIA_CONTENT_RETRIES = 4
    private const val MEDIA_CONTENT_RETRY_DELAY_MS = 1_500L
    /** Consecutive media download timeouts (network up) that mark the
     *  shared HTTP engine wedged and trigger the in-process self-heal.
     */
    private const val MEDIA_STALL_HEAL_THRESHOLD = 3
    /** Cooldown between HTTP-stack self-heals (a slow link must not churn). */
    private const val MEDIA_STALL_HEAL_MIN_INTERVAL_MS = 300_000L
    /** How long a play waits for an in-flight self-heal before fetching anyway. */
    private const val MEDIA_HEAL_WAIT_MS = 6_000L
    /** How many display JPEGs the LRU keeps (each ~100-300 KB). */
    private const val MAX_MEDIA_CACHE_ENTRIES = 24
    /** Voice-note cache bound (each file ~30-200 KB at 32 kbps Opus). */
    private const val VOICE_CACHE_MAX_FILES = 30
    /** Newest audio notes to prefetch when a thread page is built. */
    private const val VOICE_PREFETCH_COUNT = 4
    /** Events scanned for the auto-advance search (a room's newest window). */
    private const val VOICE_AUTO_ADVANCE_WINDOW = 60
    /** A following note auto-plays only within this gap of the finished one
     *  ("immediately after" — feedback 2026-08-27). */
    private const val VOICE_AUTO_ADVANCE_WINDOW_MS = 60_000L
    /** The tool's photo-picker activity, flattened for the tool to launch. The
     *  package is the TOOL's own id — the single-APK merge made
     *  the former companion a library inside com.lightphone.chats, so the old
     *  com.lightphone.chats.server package no longer resolves. */
    private const val PHOTO_PICKER_ACTIVITY = "com.lightphone.chats/.server.PhotoSendActivity"
    /** The tool's voice-note recording activity, flattened for the tool. */
    private const val VOICE_NOTE_ACTIVITY = "com.lightphone.chats/.server.VoiceNoteActivity"

    // Disk cache.
    // Versioned so a stale pre-ghost-filter cache (pages/lists polluted by the
    // bridge re-import) is never served after an upgrade.
    private const val DISK_CACHE_DIR = "chats_cache_v3"
    private const val DISK_ROOM_LIST_FILE = "room_list.json"
    /** Minimum gap between disk writes per key (the refresher runs every 2 s). */
    private const val DISK_WRITE_THROTTLE_MS = 10_000L

    private const val TAG = "MatrixRepository"
}
