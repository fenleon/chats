package com.lightphone.chats.screens

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.ChatSettings
import com.lightphone.chats.VolumePanelOverlay
import com.lightphone.chats.VolumePanelState
import com.lightphone.chats.contactIdentifier
import com.lightphone.chats.dayOf
import com.lightphone.chats.formatMessageTime
import com.lightphone.chats.server.MatrixRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.scaledForScreenHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Newest image messages whose bytes start downloading on page arrival. */
private const val MEDIA_PREFETCH_COUNT = 4

/**
 * Process-wide display-JPEG cache, shared by every ThreadViewModel: a photo
 * fetched once renders instantly in later opens (same or other room) with no
 * re-fetch RPC. Keyed by event id, which is globally
 * unique.
 */
private val chatsMediaCache = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

/**
 * Decoded display bitmaps per image event id. The BYTES live in
 * [chatsMediaCache] and survive navigation; the decode does not — returning
 * from the fullscreen viewer re-enters the row's composition, and the
 * re-decode started from a null frame, flashing the "[Photo]" fallback before
 * the image popped back in. Bounded: full-res photos decode
 * to tens of MB, so only the most recent few are kept (eviction is
 * insertion-order, fine for a handful of photos; ponytail: LRU if the count
 * ever matters).
 */
internal object chatsBitmapCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
    private const val MAX_ENTRIES = 4

    fun get(eventId: String): ImageBitmap? = map[eventId]

    fun put(eventId: String, bitmap: ImageBitmap) {
        if (map.size >= MAX_ENTRIES && !map.containsKey(eventId)) {
            map.remove(map.keys.first())
        }
        map[eventId] = bitmap
    }
}

/**
 * Loaded message pages + last scroll position per room id. The thread's
 * [ThreadViewModel] is recreated on every open, so paged-in history and the
 * scroll position were lost on exit — re-opening re-fetched the newest page
 * and re-paged from the server ("exit and come back, it has to load again").
 * Kept process-wide (like [chatsMediaCache]) so a re-open
 * renders the already-loaded history instantly and restores the position.
 * Room ids are server-scoped, so a different account can't collide.
 */
internal object threadStateCache {
    private val messageFlows = mutableMapOf<String, MutableStateFlow<List<LightServiceMethod.GetMessages.Message>>>()
    private val scroll = mutableMapOf<String, Pair<Int, Int>>()

    /** The room's message list — the SAME flow across re-opens of the room. */
    fun messagesFlow(roomId: String): MutableStateFlow<List<LightServiceMethod.GetMessages.Message>> =
        messageFlows.getOrPut(roomId) { MutableStateFlow(emptyList()) }

    fun saveScroll(roomId: String, index: Int, offset: Int) {
        scroll[roomId] = index to offset
    }

    fun takeScroll(roomId: String): Pair<Int, Int>? = scroll[roomId]
}

/**
 * Process-wide optimistic edit/unsend overlays: survives the
 * thread's ViewModel so an exit + re-enter inside the page-cache refresh
 * window keeps showing the edited body (see [ThreadViewModel]'s overlay doc).
 */
internal object editEchoCache {
    val edits = MutableStateFlow<Map<String, String>>(emptyMap())
    val unsent = MutableStateFlow<Set<String>>(emptySet())
}

/**
 * Resend-attempt counter per message id: "not delivered. tap to
 * resend" caps at [MAX_RESEND_ATTEMPTS] — each tap sends the body as a NEW
 * message, so an endless loop would spam duplicates. The count propagates to
 * the resend's new row (the chain shares one number), so tapping the original
 * OR its resends consumes the same budget. In-memory per process run: a fresh
 * app start resets the counter (the rows offer resend again — acceptable; the
 * cap is about duplicate spam within a session).
 */
internal object resendChainState {
    private val attempts = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun observe() = attempts

    fun attemptsFor(messageId: String): Int = attempts.value[messageId] ?: 0

    /** Records one resend: [messageId]'s chain increments and [newRowId] joins it. */
    fun recordResend(messageId: String, newRowId: String) {
        val n = attemptsFor(messageId) + 1
        attempts.value = attempts.value + (messageId to n) + (newRowId to n)
    }
}

class ThreadViewModel(
    private val room: LightServiceMethod.GetRooms.Room,
) : LightViewModel<Unit>() {

    /**
     * Oldest-first page of messages; older pages are prepended by [loadOlder].
     * Process-wide per room ([threadStateCache]) — re-opening the thread keeps
     * the already-loaded history instead of re-fetching it.
     */
    val messages = threadStateCache.messagesFlow(room.id)
    val loading = MutableStateFlow(true)
    val loadingMore = MutableStateFlow(false)
    val hasMore = MutableStateFlow(false)
    /** True until the newest page has been shown scrolled to the bottom. */
    val jumpToBottom = MutableStateFlow(true)
    /** Whether this device is E2EE-verified (false = encrypted rooms can't decrypt yet). */
    val e2eeVerified = MutableStateFlow<Boolean?>(null)
    /** Whether the room needs decryption (set from the first getMessages response). */
    val roomEncrypted = MutableStateFlow(false)

    /**
     * Mute state for the contact panel: starts from the room
     * list's value and updates locally on toggle — the list re-fetch keeps the
     * server copy in sync, this keeps the panel honest within the session.
     */
    val muted = MutableStateFlow(room.muted)

    /** Toggles [muted] locally and persists it server-side (notifications only). */
    fun toggleMuted() =
        viewModelScope.toggleAndPersist(muted, { room.id }, ChatClient::setRoomMuted)

    /**
     * Pin state for the contact panel: starts from the room
     * list's value and updates locally on toggle — same honest-within-session
     * pattern as [muted].
     */
    val pinned = MutableStateFlow(room.pinned)

    /** Toggles [pinned] locally and persists it server-side (m.favourite tag). */
    fun togglePinned() =
        viewModelScope.toggleAndPersist(pinned, { room.id }, ChatClient::setRoomPinned)

    /**
     * Archive state for the contact panel: starts from the room
     * list's value and updates locally on toggle — same honest-within-session
     * pattern as [muted]. Archived rooms hide from the main list and go
     * silent, reachable only via search VIEW ALL.
     */
    val archived = MutableStateFlow(room.archived)

    /** Toggles [archived] locally and persists it server-side (Beeper inbox.done). */
    fun toggleArchived() =
        viewModelScope.toggleAndPersist(archived, { room.id }, ChatClient::setRoomArchived)

    /**
     * Keeps the contact panel honest with OTHER devices: collects the repository's roomFlags flow for this room
     * (NO-SEAM — replaces the flags-revision wait + refetch),
     * updating [muted]/[pinned]/[archived] live (items 1/5). Runs while the
     * thread — and the contact panel over it — is on screen (started on
     * [onScreenShow], NOT stopped on [onScreenHide]); stops when the app
     * backgrounds and dies with the ViewModel on back-navigation.
     */
    private var flagSyncJob: Job? = null

    fun startFlagSync() {
        if (flagSyncJob?.isActive == true) return
        flagSyncJob = viewModelScope.launch {
            MatrixRepository.roomFlags
                .map { it[room.id] }
                .distinctUntilChanged()
                .collect { flags ->
                    if (flags != null) {
                        muted.value = flags.muted
                        pinned.value = flags.pinned
                        archived.value = flags.archived
                    }
                }
        }
    }

    fun stopFlagSync() {
        flagSyncJob?.cancel()
        flagSyncJob = null
    }

    /**
     * Display JPEG bytes per image-message event id. This is a view
     * of the process-wide [chatsMediaCache] (event ids are globally unique), so
     * a photo already fetched in any thread renders instantly on re-open — no
     * re-fetch RPC — and each thread's poll just adds the new arrivals.
     */
    val mediaBytes = chatsMediaCache

    /**
     * Component name of the companion's photo-picker activity to launch, set
     * by [attachPhoto]; the screen launches it and calls [consumeAttachComponent].
     */
    val pendingAttachComponent = MutableStateFlow<String?>(null)

    /**
     * Component name of the companion's voice-note recording activity, set by
     * [attachVoiceNote]; the screen launches it and calls [consumeVoiceComponent].
     */
    val pendingVoiceComponent = MutableStateFlow<String?>(null)

    /**
     * Event id of the voice note playing in the companion. Fed by
     * the poll's `audioPlayingEventId` and the local PlayVoiceNote response,
     * so the audio row shows its playing state without extra RPCs.
     */
    val playingEventId = MutableStateFlow<String?>(null)

    /**
     * Playback position (ms) + the elapsedRealtime it was sampled at, from the
     * poll's `audioPositionMs` — the row interpolates between polls so the
     * counter runs smoothly.
     */
    val playingPositionMs = MutableStateFlow<Long?>(null)
    val playingPositionAtMs = MutableStateFlow(0L)

    /**
     * True until the poll anchors the counter after a play/resume tap: the
     * label holds the seeded position (pause point / 0:00) instead of running
     * on wall clock, which ran AHEAD of the companion while it spun the player
     * up, then snapped back on the next poll — the "timer goes back" jump.
     * Cleared when the poll reports a live position.
     */
    val counterPending = MutableStateFlow(true)

    /**
     * Event id + position of a voice note PAUSED in the companion. The paused row keeps showing the pause point instead of the
     * full length, and resume starts from it — no 00:00 flash, no bounce back
     * up when the next poll lands. The server holds the real pause state;
     * this is the tool's display mirror.
     */
    val pausedEventId = MutableStateFlow<String?>(null)
    val pausedPositionMs = MutableStateFlow<Long?>(null)

    /**
     * (eventId, message) of a voice-note playback that failed to fetch/play —
     * the row shows the error briefly instead of a silent no-op. Cleared after a few seconds.
     */
    val voiceError = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * Phase B reactions. Per-event overlay over the served
     * reaction tags — reaction key → true = own reaction added, false =
     * suppressed — so the tag appears/disappears instantly instead of waiting
     * on the 3 s poll. Entries drop once a served page reflects the action; a
     * failed RPC reverts (removed) and surfaces [reactionError], the
     * voice-error pattern. The overlay dies with the ViewModel (the thread).
     */
    private val reactionOverlays = MutableStateFlow<Map<String, Map<String, Boolean>>>(emptyMap())

    /** (eventId, message) of a failed reaction toggle — cleared after a few seconds. */
    val reactionError = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * Phase C message overlays, same pattern as the reaction
     * overlays: per-event optimistic edits (eventId → new body) and unsends
     * (eventId set → tombstone), applied after the page maps in so the row
     * reacts instantly instead of waiting on the 3 s poll. Entries drop once
     * a served page reflects the action; unsend failures revert and surface
     * [reactionError] (edit failures keep the composer open instead — the
     * text survives for a retry). Process-wide: the page cache
     * can serve a pre-echo page after the ViewModel died (exit + re-enter),
     * which reverted the row to its unedited body until the rebuild landed —
     * the overlays now survive the round-trip like [threadStateCache].
     */
    private val editOverlays get() = editEchoCache.edits
    private val unsentOverlays get() = editEchoCache.unsent

    /**
     * In-app volume panel state (null = hidden), the shared LightOS replica
     * replica: while a voice note is playing/paused the volume
     * rocker shows this panel instead of LightOS's (which is ringer-only for
     * third-party tools). The bar level is cached and stepped locally; the
     * server adjusts the real media stream (see ServerBootstrapProvider).
     */
    val volumePanel = MutableStateFlow<VolumePanelState?>(null)
    private var mediaVolumeLevel: Int? = null
    private var mediaVolumeMax: Int = 0

    fun dismissVolumePanel() {
        volumePanel.value = null
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val volumeKey = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        val voiceActive = playingEventId.value != null || pausedEventId.value != null
        if (volumeKey && voiceActive && event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            if (mediaVolumeLevel == null) {
                // Cold start: seed the cache first, then show the new level.
                viewModelScope.launch {
                    ChatClient.volumeLevel()?.let { (level, max) ->
                        mediaVolumeLevel = level
                        mediaVolumeMax = max
                        showVolumePanel(keyCode)
                    }
                }
            } else {
                showVolumePanel(keyCode)
            }
            // Not handled here: the key falls through to the companion, which
            // adjusts the media stream (one step per press — repeats are
            // filtered above, and the server ignores them too).
            return false
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showVolumePanel(keyCode: Int) {
        val current = mediaVolumeLevel ?: return
        val newLevel = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> (current + 1).coerceAtMost(mediaVolumeMax.coerceAtLeast(1))
            else -> (current - 1).coerceAtLeast(0)
        }
        mediaVolumeLevel = newLevel
        volumePanel.value = VolumePanelState.Media(newLevel, mediaVolumeMax)
    }

    private fun refreshVolumeLevel() {
        viewModelScope.launch {
            ChatClient.volumeLevel()?.let { (level, max) ->
                mediaVolumeLevel = level
                mediaVolumeMax = max
            }
        }
    }

    /**
     * Optimistic rows from a send, waiting for their sync echo (feedback
     * pass). The poll replaces them with the real events as they land.
     */
    private val pendingMessages = mutableListOf<LightServiceMethod.GetMessages.Message>()

    /**
     * Thread scroll position, saved continuously by the screen and restored on
     * show — returning from the fullscreen photo viewer must land where the
     * photo was, not the newest messages. Persisted
     * process-wide ([threadStateCache]) so a full re-open of the thread also
     * lands where the user left off.
     */
    private var savedScrollIndex = threadStateCache.takeScroll(room.id)?.first ?: 0
    private var savedScrollOffset = threadStateCache.takeScroll(room.id)?.second ?: 0
    private var scrollToRestore: Pair<Int, Int>? = null
    /** Newest event id already marked read — dedup for the poll's re-mark. */
    private var lastMarkedId: String? = null

    fun saveScroll(index: Int, offset: Int) {
        savedScrollIndex = index
        savedScrollOffset = offset
        threadStateCache.saveScroll(room.id, index, offset)
    }

    /** The position to restore on show (consume-once), or null when at the newest. */
    fun takeScrollToRestore(): Pair<Int, Int>? =
        scrollToRestore.also { scrollToRestore = null }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // While this room is on screen the companion suppresses its
        // new-message notifications.
        viewModelScope.launch { ChatClient.setActiveRoom(room.id) }
        // Returning from the fullscreen photo viewer restores the scroll
        // position instead of bouncing to the newest; a fresh open jumps to
        // the newest as before.
        val restore = if (savedScrollIndex > 0 || savedScrollOffset > 0) {
            savedScrollIndex to savedScrollOffset
        } else null
        if (restore != null) {
            scrollToRestore = restore
            jumpToBottom.value = false
        }
        // Messages load first — don't gate them behind the e2ee check (that
        // only drives the decryption notice).
        loadNewest(restoreScroll = restore != null)
        viewModelScope.launch {
            e2eeVerified.value = ChatClient.e2eeState()?.verified
        }
        refreshVolumeLevel()
        startPolling()
        startFlagSync()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        // Message polling stops when covered (e.g. the contact panel), but the
        // flag sync deliberately keeps running — the panel needs Beeper-side
        // pin/mute/archive changes live.
        stopPolling()
    }

    override fun onAppPause() {
        super.onAppPause()
        // The tool is no longer visible (standby/another app); messages in
        // this room may notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        stopPolling()
        stopFlagSync()
    }

    /**
     * Newest-page updates while the thread stays visible, driven by the
     * repository (NO-SEAM — the page-revision long-poll is gone):
     * every server-side page bump (new/edited/unsent events, receipt patches,
     * pending echoes) emits [MatrixRepository.pageChanges] and the collector
     * re-reads the served page in-process. No binder serialization, no poll
     * delay — the merge is the same quiet [loadNewest] path the poll used.
     * A playing voice note keeps a tick: its position advances without any
     * page change, and only a fetch reads it back.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            launch {
                MatrixRepository.pageChanges.collect { roomId ->
                    if (roomId == room.id) loadNewest(quiet = true)
                }
            }
            launch {
                while (true) {
                    playingEventId.first { it != null } // wait for playback to start
                    while (playingEventId.value != null) {
                        delay(THREAD_POLL_MS)
                        loadNewest(quiet = true)
                    }
                    // The fetch that cleared playingEventId already synced the
                    // ended state; loop back to waiting.
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun loadNewest(quiet: Boolean = false, restoreScroll: Boolean = false) {
        // Serialize: rapid sends + the 3 s poll can otherwise interleave
        // read-modify-write merges on [messages] — a cluster showed rows
        // duplicating and jumping until every send had echoed. A newer call supersedes an in-flight one.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!quiet) loading.value = true
            var loaded: List<LightServiceMethod.GetMessages.Message>
            try {
                val page = ChatClient.getMessages(room.id, null, PAGE_SIZE)
                // Reaction overlay: drop entries the served page now
                // reflects, then ride the rest on top — the heart (dis)appears
                // instantly after a toggle instead of waiting on this poll.
                var fetched = page?.messages.orEmpty()
                dropReflectedOverlays(fetched)
                fetched = applyReactionOverlays(fetched)
                loaded = fetched
                roomEncrypted.value = page?.encrypted ?: false
                playingEventId.value = page?.audioPlayingEventId
                if (page?.audioPositionMs != null) {
                    playingPositionMs.value = page.audioPositionMs
                    playingPositionAtMs.value = android.os.SystemClock.elapsedRealtime()
                    counterPending.value = false
                }
                // Merge, don't replace: the fresh newest page updates the tail while
                // older pages the user scrolled into (via [loadOlder]) stay put. A
                // full replace every poll shrank the list back to the newest page
                // and yanked the scroll down (and made top rows flicker in/out).
                if (loaded.isNotEmpty() || messages.value.isEmpty()) {
                    // Message overlays (edits/unsends) ride on AFTER the merge,
                    // so rows in older paged history get them too.
                    messages.value = applyMessageOverlays(
                        mergeWithPending(mergeNewestPage(loaded, messages.value)),
                    )
                    hasMore.value = page?.hasMore ?: false
                }
            } finally {
                // A binder exception mid-fetch must not leave the thread stuck on
                // "Loading messages…" — the flag always clears.
                if (!quiet) loading.value = false
            }
            if (!quiet && !restoreScroll) {
                jumpToBottom.value = true
                // Opening the thread marks it read up to the newest event; the
                // room list's unread count drops on its next refresh. Optimistic
                // "local-…" rows are skipped — a receipt at a fake id never
                // confirms and leaves the badge logic flapping.
                val markEventId = loaded.lastOrNull {
                    !it.id.startsWith(LOCAL_ROW_PREFIX)
                }?.id ?: room.lastEventId ?: return@launch
                ChatClient.markRead(room.id, markEventId)
                lastMarkedId = markEventId
            }
            // The quiet poll re-marks when the newest message changes: the
            // open-time mark covers the page served at open, and a message
            // arriving later (or the page catching up to the store's real
            // newest) would otherwise leave the room list's unread asterisk
            // up. Deduped via [lastMarkedId] — no RPC on ticks where nothing
            // changed. Local rows skipped (see above).
            if (quiet) {
                val newestId = loaded.lastOrNull {
                    !it.id.startsWith(LOCAL_ROW_PREFIX)
                }?.id
                if (newestId != null && newestId != lastMarkedId) {
                    lastMarkedId = newestId
                    ChatClient.markRead(room.id, newestId)
                }
            }
        }
    }

    /**
     * Replaces the newest-page tail of [current] with the fresh [page] (both
     * oldest-first). Everything before the fresh page's oldest message is older
     * history the user scrolled into — it is kept; a fresh copy of a message
     * wins over a stale one in the old tail.
     */
    private fun mergeNewestPage(
        page: List<LightServiceMethod.GetMessages.Message>,
        current: List<LightServiceMethod.GetMessages.Message>,
    ): List<LightServiceMethod.GetMessages.Message> {
        if (current.isEmpty()) return page
        if (page.isEmpty()) return current
        val freshIds = page.mapTo(HashSet()) { it.id }
        val olderPrefix = current.takeWhile { it.id != page.first().id }
        return olderPrefix.filterNot { it.id in freshIds } + page
    }

    /**
     * Merges a freshly loaded newest page with the optimistic rows that
     * haven't echoed yet. A real event replaces its optimistic row by id; a
     * "local-…" row (no event id known at send time) is dropped when a real
     * message with the same body and a close timestamp appears.
     * The result is deduped by id, then confirmed rows are time-sorted while
     * still-pending rows stay newest in send order, so a cluster of sends
     * stays in order while the echoes land one poll at a time — without it,
     * an echo arriving out of order (or a fast-page → full-page transition
     * leaving a stale optimistic copy at the oldest end) visibly shuffled and
     * duplicated the rows until every send had echoed.
     */
    private fun mergeWithPending(loaded: List<LightServiceMethod.GetMessages.Message>):
        List<LightServiceMethod.GetMessages.Message> {
        if (pendingMessages.isEmpty()) return loaded
        val realIds = loaded.mapTo(HashSet()) { it.id }
        val result = loaded.toMutableList()
        val iterator = pendingMessages.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (pending.id in realIds) {
                iterator.remove() // the server's injected row already represents it
                continue
            }
            val echoed = loaded.any {
                it.isMine && it.body == pending.body &&
                    kotlin.math.abs(it.timestampMs - pending.timestampMs) < OPTIMISTIC_MATCH_WINDOW_MS
            }
            if (echoed) {
                iterator.remove()
            } else {
                // Still newest; stays at the end. Drop any duplicate copies the
                // page merge left behind first (fast-page → full-page swap).
                result.removeAll { it.id == pending.id }
                result += pending
            }
        }
        // Pending rows (still in [pendingMessages]) keep their send order and
        // stay newest: their timestamps are device-clock, while the confirmed
        // echoes carry server-clock stamps — mixing them sorted the first
        // message of a burst below its own echoes.
        // "local-…" rows are always pending; a fast-acked optimistic row with
        // a real event id is too, until the served page replaces it. The
        // confirmed sort is stable, so equal timestamps keep their order.
        val distinct = result.distinctBy { it.id }
        val (pending, confirmed) = distinct.partition { it in pendingMessages }
        return confirmed.sortedBy { it.timestampMs } + pending
    }

    /** Inserts the just-sent message immediately (optimistic echo). */
    fun addOptimistic(message: LightServiceMethod.GetMessages.Message) {
        pendingMessages += message
        messages.value = messages.value + message
    }

    /**
     * Starts the attach-a-photo flow: asks the companion for its photo-picker
     * activity's component name (recording the room), which the screen then
     * launches — the tool runtime forbids startActivity itself.
     */
    fun attachPhoto() {
        if (pendingAttachComponent.value != null) return
        viewModelScope.launch {
            val component = ChatClient.startPhotoSend(room.id) ?: return@launch
            pendingAttachComponent.value = component
        }
    }

    fun consumeAttachComponent() {
        pendingAttachComponent.value = null
    }

    /**
     * Starts the record-a-voice-note flow: asks the companion for its
     * recording activity's component name (recording the room), which the
     * screen then launches — the tool runtime forbids startActivity itself.
     */
    fun attachVoiceNote() {
        if (pendingVoiceComponent.value != null) return
        viewModelScope.launch {
            val component = ChatClient.startVoiceNoteSend(room.id) ?: return@launch
            pendingVoiceComponent.value = component
        }
    }

    fun consumeVoiceComponent() {
        pendingVoiceComponent.value = null
    }

    /**
     * Toggles playback of a voice note in the companion. The local
     * state flips immediately so the row reacts; the response and the poll's
     * `audioPlayingEventId` keep it accurate as playback finishes. A fetch or
     * playback failure surfaces on the row instead of a silent no-op.
     */
    fun playVoiceNote(eventId: String) {
        val toggling = playingEventId.value == eventId
        val resuming = !toggling && pausedEventId.value == eventId
        when {
            // Tap the playing row → PAUSE: hold the interpolated position so
            // the row shows the pause point while paused, not the full length.
            toggling -> {
                pausedEventId.value = eventId
                // While the counter is unanchored (player still spinning up),
                // the frozen seed IS the position — extrapolating over a
                // player that hasn't started would run the estimate ahead.
                pausedPositionMs.value = if (counterPending.value) {
                    playingPositionMs.value ?: 0L
                } else {
                    (playingPositionMs.value ?: 0L) +
                        (android.os.SystemClock.elapsedRealtime() - playingPositionAtMs.value)
                }
                playingEventId.value = null
            }
            // Tap the paused row → RESUME from the pause point: seed the
            // counter there instead of 00:00, so it neither flashes nor
            // bounces when the next poll reports the real position. The
            // counter stays FROZEN at the seed until the poll anchors it —
            // counting on wall clock before the companion's player is up runs
            // ahead, and the poll then jumps the label BACK.
            resuming -> {
                val resumeFrom = pausedPositionMs.value
                pausedEventId.value = null
                pausedPositionMs.value = null
                playingPositionMs.value = resumeFrom
                playingPositionAtMs.value = android.os.SystemClock.elapsedRealtime()
                counterPending.value = true
                playingEventId.value = eventId
            }
            // A NEW note starts at 0:00 — the position state still holds the
            // previous note's last polled value, which otherwise showed a
            // stale "random" position for a beat until the first poll landed
            // and snapped it to 0. Frozen at 0:00 until
            // the poll anchors, like resume.
            else -> {
                pausedEventId.value = null
                pausedPositionMs.value = null
                playingPositionMs.value = 0L
                playingPositionAtMs.value = android.os.SystemClock.elapsedRealtime()
                counterPending.value = true
                playingEventId.value = eventId
            }
        }
        viewModelScope.launch {
            val (playing, error) = ChatClient.playVoiceNote(room.id, eventId)
            playingEventId.value = if (playing) eventId else null
            if (!playing && error != null) {
                showRowError(voiceError, eventId, error)
            }
        }
    }

    /**
     * Re-sends a locally-failed message (tap on the "failed to send" row): the
     * companion clears the outbox error for the row's "local-…" txn id and
     * Trixnity re-sends it; the poll replaces the row with the echo when it
     * lands, or the label stays if the retry can't succeed.
     */
    fun retrySend(message: LightServiceMethod.GetMessages.Message) {
        if (!message.id.startsWith(LOCAL_ROW_PREFIX)) return
        viewModelScope.launch {
            ChatClient.retrySend(room.id, message.id.removePrefix(LOCAL_ROW_PREFIX))
        }
    }

    /**
     * Double-tap like: Beeper semantics — double-tap
     * REPLACES the user's current reaction with the ❤️ (not add alongside,
     * it); a second double-tap removes it. Own rows
     * never react (nothing to like back — restraint).
     */
    fun toggleLike(message: LightServiceMethod.GetMessages.Message) {
        if (message.isMine || message.id.startsWith(LOCAL_ROW_PREFIX)) return
        if (LIKE_KEY in effectiveOwnKeys(message)) removeReaction(message)
        else setReaction(message, LIKE_KEY)
    }

    /**
     * Toggles the signed-in user's [key] reaction on a RECEIVED message
     * (Phase B — the context window's emoji grid): send when
     * absent, unsend when present. The optimistic overlay flips the tag
     * immediately (the 3 s poll is too slow); a failed RPC reverts it and
     * shows the quiet row error (the voice-note error pattern). Own rows
     * never react.
     */
    fun toggleReaction(message: LightServiceMethod.GetMessages.Message, key: String) {
        if (message.isMine || message.id.startsWith(LOCAL_ROW_PREFIX)) return
        // The toggle must see its own optimistic overlay: a quick re-tap before
        // the poll catches up reads the overlay's target state, not the stale
        // served page.
        val overlayKeys = reactionOverlays.value[message.id]
        val ownPresent = if (overlayKeys != null && key in overlayKeys) {
            overlayKeys.getValue(key)
        } else {
            OWN_REACTION_TAG_PREFIX + key in message.reactions
        }
        val adding = !ownPresent
        reactionOverlays.value = reactionOverlays.value +
            (message.id to ((reactionOverlays.value[message.id] ?: emptyMap()) + (key to adding)))
        // The tag flips this frame, not on the next poll tick or when the
        // re-fetch lands — re-render with the overlay applied right away.
        messages.value = applyReactionOverlays(messages.value)
        loadNewest(quiet = true)
        viewModelScope.launch {
            val ok = if (adding) {
                ChatClient.sendReaction(room.id, message.id, key)
            } else {
                ChatClient.unsendReaction(room.id, message.id, key)
            }
            if (!ok) {
                revertReactionOverlay(message.id, key)
                showRowError(reactionError, message.id, "reaction failed")
            }
        }
    }

    /**
     * Surfaces the quiet row error tag on [slot] and clears it after the
     * dismiss delay — unless a newer error replaced it first.
     */
    private suspend fun showRowError(
        slot: MutableStateFlow<Pair<String, String>?>,
        eventId: String,
        text: String,
    ) {
        slot.value = eventId to text
        delay(VOICE_ERROR_DISMISS_MS)
        if (slot.value?.first == eventId) slot.value = null
    }

    /** Rolls back one key's optimistic overlay entry after a failed RPC. */
    private fun revertReactionOverlay(eventId: String, key: String) {
        val keys = reactionOverlays.value[eventId] ?: return
        val remaining = keys - key
        reactionOverlays.value =
            if (remaining.isEmpty()) reactionOverlays.value - eventId
            else reactionOverlays.value + (eventId to remaining)
    }

    /**
     * Sets the signed-in user's sole reaction on a RECEIVED message to [key]
     * (Phase B — the context window's LIKE MESSAGE / REACT /
     * EDIT REACTION rows, one own reaction at a time, replace semantics):
     * unsends any existing own reaction(s) and sends [key]. Optimistic
     * overlay for both directions; only a failed SEND rolls the new key back
     * and surfaces the quiet row error — a failed unsend is best-effort (the
     * new reaction wins display-side, so it must not fail the change). When
     * the send fails after the unsend succeeded, the reaction is lost
     * server-side — the error tag says so implicitly and the user can re-react.
     */
    fun setReaction(message: LightServiceMethod.GetMessages.Message, key: String) {
        if (message.isMine || message.id.startsWith(LOCAL_ROW_PREFIX)) return
        val own = effectiveOwnKeys(message)
        if (own == listOf(key)) return // already this exact reaction
        val updates = own.associateWith { false } + (key to true)
        reactionOverlays.value = reactionOverlays.value +
            (message.id to ((reactionOverlays.value[message.id] ?: emptyMap()) + updates))
        messages.value = applyReactionOverlays(messages.value)
        loadNewest(quiet = true)
        viewModelScope.launch {
            // Unsend best-effort: a miss (old reaction beyond the head window,
            // or a transient failure) must NOT fail the whole change — the new
            // reaction is what the display keeps (per-sender newest-wins), and
            // the bridge replaces remotely anyway (reaction_count=1).
            for (old in own) {
                ChatClient.unsendReaction(room.id, message.id, old)
            }
            val ok = ChatClient.sendReaction(room.id, message.id, key)
            if (!ok) {
                // Only the new key reverts — the old keys' removal entries
                // self-heal when a served page reflects them.
                revertReactionOverlay(message.id, key)
                showRowError(reactionError, message.id, "reaction failed")
            }
        }
    }

    /**
     * Unsends the user's own reaction(s) on a RECEIVED message (the context
     * window's REMOVE REACTION row). Optimistic overlay, revert + quiet row
     * error on failure — the [toggleReaction] pattern.
     */
    fun removeReaction(message: LightServiceMethod.GetMessages.Message) {
        if (message.isMine || message.id.startsWith(LOCAL_ROW_PREFIX)) return
        val own = effectiveOwnKeys(message)
        if (own.isEmpty()) return
        reactionOverlays.value = reactionOverlays.value +
            (message.id to ((reactionOverlays.value[message.id] ?: emptyMap()) + own.associateWith { false }))
        messages.value = applyReactionOverlays(messages.value)
        loadNewest(quiet = true)
        viewModelScope.launch {
            var ok = true
            for (old in own) {
                ok = ChatClient.unsendReaction(room.id, message.id, old) && ok
            }
            if (!ok) {
                own.forEach { revertReactionOverlay(message.id, it) }
                showRowError(reactionError, message.id, "reaction failed")
            }
        }
    }

    /**
     * The user's current reaction keys on [message]: the served tags plus the
     * optimistic overlay — a reaction just tapped (or just removed) that the
     * poll hasn't reflected yet still counts, so a second toggle acts on the
     * real state instead of the stale page (the [toggleReaction] overlay read,
     * generalized for the replace semantics of [toggleLike]/[setReaction]).
     */
    private fun effectiveOwnKeys(message: LightServiceMethod.GetMessages.Message): List<String> {
        val keys = ownReactionKeys(message).toMutableList()
        reactionOverlays.value[message.id]?.forEach { (key, added) ->
            if (added) {
                if (key !in keys) keys.add(key)
            } else {
                keys.remove(key)
            }
        }
        return keys
    }

    /**
     * Applies a completed edit optimistically: the row
     * shows the new body at once, the "edited" tag arrives with the echo. The
     * overlay drops when a served page carries the edited body
     * ([dropReflectedOverlays]). An edit RPC that fails never reaches here —
     * the composer stays open with the text, which IS the revert.
     */
    fun applyEditEcho(eventId: String, body: String) {
        if (body.isBlank() || eventId.startsWith(LOCAL_ROW_PREFIX)) return
        editOverlays.value = editOverlays.value + (eventId to body)
    }

    /**
     * Unsends an own message (Phase C — the context window's
     * UNSEND row, behind the confirm panel): the row becomes the
     * tombstone instantly via the overlay; a failed RPC reverts it and
     * surfaces the quiet row error (the reaction pattern). The overlay drops
     * once a served page shows the row redacted.
     */
    fun unsendMessage(message: LightServiceMethod.GetMessages.Message) {
        if (!message.isMine || message.id.startsWith(LOCAL_ROW_PREFIX)) return
        unsentOverlays.value = unsentOverlays.value + message.id
        // An unsend supersedes any pending edit overlay on the same row.
        editOverlays.value = editOverlays.value - message.id
        loadNewest(quiet = true)
        viewModelScope.launch {
            val ok = ChatClient.unsendMessage(room.id, message.id)
            if (!ok) {
                unsentOverlays.value = unsentOverlays.value - message.id
                showRowError(reactionError, message.id, "unsend failed")
            }
        }
    }

    /**
     * Applies the reaction overlay to a fetched newest page (after the fetch,
     * before the merge — the overlay holds until the served page reflects the
     * action, which [dropReflectedOverlays] handles first).
     */
    private fun applyReactionOverlays(
        page: List<LightServiceMethod.GetMessages.Message>,
    ): List<LightServiceMethod.GetMessages.Message> {
        val overlays = reactionOverlays.value
        if (overlays.isEmpty()) return page
        return page.map { m ->
            val keys = overlays[m.id] ?: return@map m
            var reactions = m.reactions
            keys.forEach { (key, added) ->
                val tag = OWN_REACTION_TAG_PREFIX + key
                reactions = if (added) {
                    if (tag in reactions) reactions else reactions + tag
                } else {
                    reactions - tag
                }
            }
            if (reactions != m.reactions) m.copy(reactions = reactions) else m
        }
    }

    /**
     * Applies the Phase C message overlays (after the merge, so rows in older
     * paged history get them too): an edit's overlay swaps the row's body;
     * an unsend's overlay turns the row into the "redacted" tombstone —
     * keeping the row visible until the served rebuild replaces it, which
     * also holds the context panel's target stable.
     */
    private fun applyMessageOverlays(
        page: List<LightServiceMethod.GetMessages.Message>,
    ): List<LightServiceMethod.GetMessages.Message> {
        val edits = editOverlays.value
        val unsent = unsentOverlays.value
        if (edits.isEmpty() && unsent.isEmpty()) return page
        return page.map { m ->
            var row = m
            // The edit overlay also stamps the "edited" tag — the body swap
            // alone made the tag wait for the echo (LP3 feedback 2026-09-03).
            edits[row.id]?.let { body -> row = row.copy(body = body, edited = true) }
            if (row.id in unsent) {
                row = row.copy(
                    contentType = "redacted",
                    body = "[Message unsent]",
                    reactions = emptyList(),
                    sendStatus = null,
                    read = false,
                    caption = null,
                    durationMs = null,
                    forwarded = false,
                    edited = false,
                )
            }
            row
        }
    }

    /** Drops overlay entries a fetched page now reflects (the poll caught up
     *  with the RPC; the overlay only bridges the gap). Covers the reaction,
     *  edit, and unsend overlays (Phase A/C). */
    private fun dropReflectedOverlays(
        page: List<LightServiceMethod.GetMessages.Message>,
    ) {
        reactionOverlays.value = reactionOverlays.value.mapNotNull { (eventId, keys) ->
            val served = page.firstOrNull { it.id == eventId } ?: return@mapNotNull eventId to keys
            val remaining = keys.filterNot { (key, added) ->
                val tag = OWN_REACTION_TAG_PREFIX + key
                if (added) tag in served.reactions else tag !in served.reactions
            }
            if (remaining.isEmpty()) null else eventId to remaining
        }.toMap()
        // Edits: the served row now carries the overlaid body (or was unsent —
        // a redacted row never matches a body).
        editOverlays.value = editOverlays.value.filterNot { (eventId, body) ->
            page.any { it.id == eventId && (it.body == body || it.contentType == "redacted") }
        }
        // Unsends: the served row is the tombstone (same id).
        unsentOverlays.value = unsentOverlays.value.filterNot { eventId ->
            page.any { it.id == eventId && it.contentType == "redacted" }
        }.toSet()
    }

    /**
     * Re-sends a bridge-reported delivery failure as a NEW message (tap on a
     * "not delivered. tap to resend" row): the event already left
     * the device, so there's no txn to retry — the same body goes out through
     * the normal send path (like the composer) and the poll swaps in the echo.
     * Capped at [MAX_RESEND_ATTEMPTS] per chain — each resend is a new
     * message, so an endless loop would spam duplicates.
     */
    fun resendAsNew(message: LightServiceMethod.GetMessages.Message) {
        if (message.body.isBlank()) return
        if (resendChainState.attemptsFor(message.id) >= MAX_RESEND_ATTEMPTS) return
        viewModelScope.launch {
            val response = ChatClient.sendMessage(room.id, message.body) ?: return@launch
            resendChainState.recordResend(message.id, response.eventId ?: "local-${response.transactionId}")
            addOptimistic(
                LightServiceMethod.GetMessages.Message(
                    id = response.eventId ?: "local-${response.transactionId}",
                    sender = "",
                    senderName = "",
                    body = message.body,
                    timestampMs = System.currentTimeMillis(),
                    isMine = true,
                ),
            )
            loadNewest(quiet = true)
        }
    }

    /** Fetches an image message's display bytes if they aren't cached yet. */
    fun ensureMedia(eventId: String, allowMobileData: Boolean) {        if (mediaBytes.value.containsKey(eventId)) return
        viewModelScope.launch {
            // Retry a few times: the first read can hit a still-decrypting
            // event or a transient download failure, and a null result is not
            // cached — the row would otherwise stay on its text fallback.
            var bytes: ByteArray? = null
            repeat(MEDIA_RETRIES) {
                bytes = ChatClient.getMessageMedia(room.id, eventId, allowMobileData)
                if (bytes != null) return@repeat
                delay(MEDIA_RETRY_DELAY_MS)
            }
            if (bytes != null) {
                mediaBytes.value = mediaBytes.value + (eventId to bytes)
            }
        }
    }

    /** Prepends the page of messages older than the oldest one currently shown. */
    fun loadOlder() {
        val oldest = messages.value.firstOrNull() ?: return
        // An optimistic "local-…" row is not a real event — paging from it
        // returns nothing and would dead-end pagination (feedback 2026-08-15).
        if (oldest.id.startsWith(LOCAL_ROW_PREFIX)) return
        if (loadingMore.value || !hasMore.value) return
        viewModelScope.launch {
            loadingMore.value = true
            try {
                val page = ChatClient.getMessages(room.id, oldest.id, OLDER_PAGE_SIZE)
                val older = page?.messages.orEmpty()
                if (older.isNotEmpty()) {
                    // distinctBy guards the page boundary: if the timeline changed
                    // between calls, the cursor event can appear at both edges.
                    // No sort here — pagination cursors and merge boundaries are
                    // position-based, and in rooms with non-monotonic timestamps
                    // (re-import batches) a ts-sort moved the oldest row off the
                    // chain edge, dead-ending older-page fetches.
                    // Overlays ride on the merged list too — a reaction tapped
                    // on a row that only exists in paged history must flip the
                    // tag here, not only on the newest page.
                    val merged = (older + messages.value).distinctBy { it.id }
                    dropReflectedOverlays(merged)
                    messages.value = applyMessageOverlays(applyReactionOverlays(merged))
                }
                hasMore.value = page?.hasMore ?: hasMore.value
            } finally {
                // A binder failure must not wedge pagination.
                loadingMore.value = false
            }
        }
    }

    private var pollJob: Job? = null
    /** Coalescing guard for [loadNewest] — one in-flight fetch/merge at a time. */
    private var loadJob: Job? = null

    private companion object {
        /** Newest-page size (matches the server's THREAD_PAGE_SIZE). */
        const val PAGE_SIZE = 20
        /** Older-page size: 6 ≈ one screenful per scroll-up load (2026-08-23). */
        const val OLDER_PAGE_SIZE = 6
        /** Poll cadence while a voice note plays: its position advances
         *  without any page change, and only a poll reads it back. */
        const val THREAD_POLL_MS = 1_500L
        /** How close (ms) a real echo's timestamp must be to a "local-…" row. */
        const val OPTIMISTIC_MATCH_WINDOW_MS = 5 * 60 * 1000L
        /** Media fetch retries when the first read comes back null. */
        const val MEDIA_RETRIES = 3
        const val MEDIA_RETRY_DELAY_MS = 2_000L
        /** How long a failed voice-note play error stays on the row. */
        const val VOICE_ERROR_DISMISS_MS = 3_000L
    }
}

/** Load the next older page when the topmost visible message is within this many of the end. */
private const val OLDER_LOAD_THRESHOLD = 3

/** Optimistic rows (not yet echoed by sync) carry this id prefix. */
private const val LOCAL_ROW_PREFIX = "local-"

/** The double-tap like: the reaction key (Phase A, 2026-09-03). */
private const val LIKE_KEY = "❤️"

/**
 * How the server tags the user's own reactions — the overlay strings must
 * match the served format exactly ("You reacted with $key").
 */
private const val OWN_REACTION_TAG_PREFIX = "You reacted with "

/**
 * The signed-in user's own reaction keys on [message], read from the served
 * tags (which already carry the optimistic overlay). Ceiling: the server's
 * collapseReactionTags can fold the own tag into an "X and others reacted
 * with …" merge, where this misses it — the same ceiling as the Phase A
 * toggle.
 */
private fun ownReactionKeys(message: LightServiceMethod.GetMessages.Message): List<String> =
    message.reactions
        .filter { it.startsWith(OWN_REACTION_TAG_PREFIX) }
        .map { it.removePrefix(OWN_REACTION_TAG_PREFIX) }

/**
 * Max tap-to-resend attempts per message chain: each resend is a
 * NEW message, so past this the row shows a static "failed to deliver" instead
 * of offering another duplicate.
 */
private const val MAX_RESEND_ATTEMPTS = 2

/**
 * Failed messages older than this show a static "failed to deliver" — a stale
 * bridge FAIL isn't worth another duplicate send.
 */
private const val RESEND_MAX_AGE_MS = 4L * 60 * 60 * 1000

class ThreadScreen(
    sealedActivity: SealedLightActivity,
    private val room: LightServiceMethod.GetRooms.Room,
) : LightScreen<Unit, ThreadViewModel>(sealedActivity) {

    override val viewModelClass: Class<ThreadViewModel>
        get() = ThreadViewModel::class.java

    override fun createViewModel(): ThreadViewModel = ThreadViewModel(room)

    @Composable
    override fun Content() {
        val messages by viewModel.messages.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val jumpToBottom by viewModel.jumpToBottom.collectAsState()
        val e2eeVerified by viewModel.e2eeVerified.collectAsState()
        val roomEncrypted by viewModel.roomEncrypted.collectAsState()
        val mediaBytes by viewModel.mediaBytes.collectAsState()
        val attachComponent by viewModel.pendingAttachComponent.collectAsState()
        val voiceComponent by viewModel.pendingVoiceComponent.collectAsState()
        val playingEventId by viewModel.playingEventId.collectAsState()
        val playingPositionMs by viewModel.playingPositionMs.collectAsState()
        val playingPositionAtMs by viewModel.playingPositionAtMs.collectAsState()
        val counterPending by viewModel.counterPending.collectAsState()
        val pausedEventId by viewModel.pausedEventId.collectAsState()
        val pausedPositionMs by viewModel.pausedPositionMs.collectAsState()
        val voiceError by viewModel.voiceError.collectAsState()
        val reactionError by viewModel.reactionError.collectAsState()
        // Context window: the long-pressed message
        // (null = the panel is hidden). Own rows (EDIT / UNSEND) park their
        // target here for the confirm panel.
        var contextMessage by remember { mutableStateOf<LightServiceMethod.GetMessages.Message?>(null) }
        var unsendConfirm by remember { mutableStateOf<LightServiceMethod.GetMessages.Message?>(null) }
        // SAVE from the context window (image rows):
        // the same save + confirm flow as the fullscreen viewer's bottom bar.
        val contextScope = rememberCoroutineScope()
        var saveConfirm by remember { mutableStateOf<Boolean?>(null) }
        // The panel's target resolved against the freshest polled snapshot (so
        // own-reaction detection never runs on a stale page) — declared here,
        // before the list, because the in-list dismiss scrim gates on it.
        val contextTarget = contextMessage?.let { ctx ->
            messages.lastOrNull { it.id == ctx.id }
        }?.takeIf { it.contentType != "redacted" }
        val muted by viewModel.muted.collectAsState()
        val showReadStatus by ChatSettings.showReadStatus.collectAsState()
        val downloadOverMobile by ChatSettings.downloadOverMobile.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        // Restore the saved position at FIRST composition (the initial-params
        // overload): returning from the fullscreen photo viewer otherwise
        // created the list at the bottom and the post-composition scroll
        // landed a frame late, flashing the newest messages first. takeScrollToRestore is consume-once, so the restore
        // effect below no-ops on this path; a fresh open (0,0) keeps the
        // jump-to-bottom behavior.
        val initialRestore = viewModel.takeScrollToRestore()
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = initialRestore?.first ?: 0,
            initialFirstVisibleItemScrollOffset = initialRestore?.second ?: 0,
        )

        // Load the persisted read-status toggle once (idempotent).
        LaunchedEffect(Unit) { ChatSettings.load(lightContext) }

        // Prioritised media: the newest image messages' bytes start downloading
        // the moment the page lands, so visible rows render as soon as their
        // bytes arrive (instead of only when each row composes and fetches).
        LaunchedEffect(messages) {
            messages.asReversed()
                .filter { it.contentType == "image" }
                .take(MEDIA_PREFETCH_COUNT)
                .forEach { viewModel.ensureMedia(it.id, downloadOverMobile) }
        }

        // Attach-photo handoff: the companion's photo-picker activity was
        // requested; launch it (the tool runtime forbids startActivity, so the
        // foreground tool starts the companion's activity instead).
        LaunchedEffect(attachComponent) {
            val component = attachComponent ?: return@LaunchedEffect
            startServerActivity(component)
            viewModel.consumeAttachComponent()
        }

        // Voice-note handoff: same pattern for the recording activity.
        LaunchedEffect(voiceComponent) {
            val component = voiceComponent ?: return@LaunchedEffect
            startServerActivity(component)
            viewModel.consumeVoiceComponent()
        }

        // An empty page in an encrypted room means the stored events couldn't
        // be decrypted — unverified device, or a fresh login whose key requests
        // haven't landed. Say so instead of lying about being empty.
        val needsDecryptionNotice = roomEncrypted && messages.isEmpty()

        // Status tag under our newest send: "seen" under the
        // newest outgoing message the other party actually read (m.read
        // receipt or Beeper READ status), "delivered" under our newest send
        // carrying a real DELIVERED status (bridge SUCCESS +
        // delivered_to_users — MatrixRepository maps it; no synthetic
        // "delivered" off a stopped SENDING spinner). The tag tracks the LATEST message: delivered
        // there, flipped to "seen" once the read position reaches it. When
        // the read position is still behind the newest send, delivered wins —
        // the seen-behind position marker only survives as the
        // fallback when there's no delivery evidence (plain Matrix rooms).
        // The list is oldest-first, so the last match is the newest.
        // Placement rules: the contact's reply being the thread's newest
        // drops both tags — a reply itself says "read everything" — except
        // "seen" stays when the read position is behind our newest send
        // (marks how far they got). No evidence → no tag. Groups get no tag
        // at all (the isDirect gate at the call site).
        val statusTag = remember(messages) {
            val newestRead = messages.lastOrNull { it.isMine && (it.read || it.sendStatus == "READ") }
            val newestMine = messages.lastOrNull { it.isMine }
            val replyIsNewest = messages.lastOrNull()?.isMine == false
            val seen = if (newestRead != null && newestRead.id == newestMine?.id && replyIsNewest) null
                else newestRead?.id?.let { it to "seen" }
            val delivered = newestMine
                ?.takeIf { it.sendStatus == "DELIVERED" && !replyIsNewest }
                ?.let { it.id to "delivered" }
            val tag = if (newestRead?.id == newestMine?.id) seen ?: delivered else delivered ?: seen
            tag
        }

        // Infinite scroll + scroll-bar metrics, polled rather than
        // snapshotFlow-driven: in this Compose version reads of
        // LazyListState.layoutInfo don't invalidate snapshotFlow/derivedStateOf
        // on every scroll, so a snapshotFlow trigger
        // never fired — the list sat at its top with older messages one page
        // away and "older messages don't load". The poll reads the real layout
        // info each tick; the loadOlder condition is index-exact (the topmost
        // visible index vs the total), so rows prepended above the viewport
        // don't re-trigger it.
        val heightSampler = remember { HeightSampler() }
        var scrollMetrics by remember { mutableStateOf(listState.threadListMetrics(heightSampler)) }
        LaunchedEffect(listState) {
            while (true) {
                val info = listState.layoutInfo
                val topIndex = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
                val total = info.totalItemsCount
                if (total > 0 && topIndex >= total - OLDER_LOAD_THRESHOLD) {
                    viewModel.loadOlder()
                }
                scrollMetrics = listState.threadListMetrics(heightSampler)
                delay(if (listState.isScrollInProgress) 50L else 300L)
            }
        }

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        // Pop with a Unit result so the caller's navigateTo
                        // callback fires — a thread opened via search then
                        // closes the search on back, landing on the main list.
                        // Callers without a callback
                        // are no-ops.
                        onClick = { goBack(Unit) },
                        contentDescription = "Back to chats",
                    ),
                    // Tapping the room name opens the contact overlay
                    // in a 1:1 it's the other party's
                    // identifier (their Matrix ID localpart = the bridge UID:
                    // a phone number on WhatsApp, a username on Instagram).
                    center = LightTopBarCenter.Text(
                        room.name,
                        onClick = { openContact() },
                    ),
                    rightButton = null,
                )
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading && messages.isEmpty() -> StatusText("Loading messages…")
                        // An encrypted room whose content can't be decrypted
                        // returns an empty page — say why instead of "No
                        // messages yet." (the text differs: unverified device
                        // vs. verified device still waiting for keys).
                        needsDecryptionNotice && messages.isEmpty() -> StatusText(
                            if (e2eeVerified == false) DECRYPTION_NOTICE else DECRYPTION_NOTICE_KEYS_PENDING
                        )
                        messages.isEmpty() -> StatusText("No messages yet.")
                        else -> Column(modifier = Modifier.fillMaxSize()) {
                            if (needsDecryptionNotice) DecryptionNotice(
                                if (e2eeVerified == false) DECRYPTION_NOTICE else DECRYPTION_NOTICE_KEYS_PENDING
                            )
                            // Messages grouped by time gap / sender / day (the
                            // day tag lives on the group-start timestamp —
                            // the centered day dividers
                            // were removed); display order is newest-first
                            // because reverseLayout puts index 0 at the bottom.
                            val rows = remember(messages) { buildThreadRows(messages) }
                            Box(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    reverseLayout = true,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(rows, key = { it.key }) { row ->
                                        MessageRow(
                                            row.message,
                                            // In a 1:1 the other person's name is
                                            // redundant — the thread is the
                                            // conversation with them. In groups,
                                            // the name shows at the start of each
                                            // sender's group (same rows that carry
                                            // the timestamp).
                                            showSender = !room.isDirect && row.showTime,
                                            showTime = row.showTime,
                                            statusTag = statusTag
                                                ?.takeIf { row.message.id == it.first && room.isDirect }
                                                // Show read status only gates "seen" —
                                                // "delivered" shows regardless (2026-09-06).
                                                ?.takeIf { it.second != "seen" || showReadStatus }
                                                ?.second,
                                            mediaBytes = mediaBytes,
                                            allowMobile = downloadOverMobile,
                                            playing = row.message.id == playingEventId,
                                            playingPositionMs = playingPositionMs,
                                            playingPositionAtMs = playingPositionAtMs,
                                            counterPending = counterPending,
                                            paused = row.message.id == pausedEventId,
                                            pausedPositionMs = pausedPositionMs,
                                            voiceError = voiceError,
                                            reactionError = reactionError,
                                            onEnsureMedia = viewModel::ensureMedia,
                                            onPlayVoiceNote = viewModel::playVoiceNote,
                                            onRetrySend = viewModel::retrySend,
                                            onResendAsNew = viewModel::resendAsNew,
                                            onToggleLike = viewModel::toggleLike,
                                            onOpenContext = { contextMessage = it },
                                            onOpenImage = { bytes ->
                                                navigateTo(screenFactory = {
                                                    FullscreenImageScreen(it, room.id, row.message.id, bytes)
                                                })
                                            },
                                        )
                                    }
                                }
                                // Thread rows vary in height, so the SDK's
                                // uniform-height LightLazyScrollView can't drive the
                                // thumb — ThreadScrollBar estimates from the real
                                // lazy layout (same rail + thumb look as the SDK
                                // bar). The metrics loop above feeds it.
                                if (scrollMetrics.overflows) {
                                    val scope = rememberCoroutineScope()
                                    ThreadScrollBar(
                                        contentScrollOffsetPx = scrollMetrics.displayScrollPx,
                                        maxContentScrollOffsetPx = scrollMetrics.maxScrollPx,
                                        onScrollTo = { targetPx ->
                                            val m = scrollMetrics
                                            if (m.maxScrollPx > 0f && m.avgItemHeightPx > 0f) {
                                                // targetPx is in flipped (display)
                                                // space — convert back to list space.
                                                val target = (m.maxScrollPx - targetPx)
                                                    .coerceIn(0f, m.maxScrollPx)
                                                val itemCount = listState.layoutInfo.totalItemsCount
                                                if (itemCount > 0) {
                                                    val index = (target / m.avgItemHeightPx)
                                                        .toInt().coerceIn(0, itemCount - 1)
                                                    val offset = (target - index * m.avgItemHeightPx)
                                                        .roundToInt()
                                                    scope.launch { listState.scrollToItem(index, offset) }
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    }
                    // A tap on the free area between the top bar and the panel
                    // dismisses it — an invisible
                    // scrim over that band only (last child of this Box, drawn
                    // over the rows): top-padded clear of the top bar (back +
                    // room name stay tappable) and ending at the panel's top
                    // edge, so taps on the panel itself never dismiss.
                    if (contextTarget != null) {
                        // Tap-away dismiss buzzes like every other panel
                        // dismissal, gated by the
                        // same LocalHapticsEnabled the rows read.
                        val scrimBuzz = rememberHapticBuzz()
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(0.5f)
                                .padding(top = 3f.gridUnitsAsDp())
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = {
                                        scrimBuzz()
                                        contextMessage = null
                                    })
                                },
                        )
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        // Record a voice note — bottom left, like the built-in
                        // Messages app's layout. Opens the
                        // companion's recording activity.
                        LightBarButton.LightIcon(
                            icon = LightIcons.MICROPHONE,
                            onClick = { viewModel.attachVoiceNote() },
                            contentDescription = "Record voice note",
                        ),
                        // Attach a photo — bottom middle, like the built-in
                        // Messages app's add slot. Opens the system photo
                        // picker via the companion.
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            onClick = { viewModel.attachPhoto() },
                            contentDescription = "Attach photo",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.COMPOSE_MESSAGE,
                            onClick = { openComposer() },
                            contentDescription = "New message",
                        ),
                    ),
                )
            }
            // Context window: the long-pressed message's
            // panel over the bottom half — received rows get LIKE MESSAGE /
            // REACT (+ EDIT/REMOVE REACTION once a reaction exists); own rows
            // get EDIT / UNSEND (gated per row by
            // the bridge caps). Actions act on the freshest polled snapshot
            // so own-reaction detection never runs on a stale page. Rendered
            // before the volume panel so the volume panel stays on top of
            // everything.
            ContextWindowOverlay(
                message = contextTarget,
                ownReaction = contextTarget?.let(::ownReactionKeys)?.firstOrNull(),
                onLike = { contextTarget?.let { viewModel.setReaction(it, LIKE_KEY) } },
                onReact = { key -> contextTarget?.let { viewModel.setReaction(it, key) } },
                onRemoveReaction = { contextTarget?.let { viewModel.removeReaction(it) } },
                onEdit = { contextTarget?.let { openComposer(it) } },
                onUnsend = { contextTarget?.let { unsendConfirm = it } },
                // SAVE (image rows only — the context window's image addition,
                // addition): the same server-side save the
                // fullscreen viewer's SAVE PHOTO runs, confirming with the
                // same panel (below).
                onSave = contextTarget
                    ?.takeIf { it.contentType == "image" }
                    ?.let { target ->
                        {
                            contextScope.launch {
                                // The panel stays up while the save round-trips; the
                                // confirm replaces it, and clearing the target
                                // then drops the panel behind the modal.
                                saveConfirm = ChatClient.saveMessageImage(room.id, target.id)
                                contextMessage = null
                            }
                        }
                    },
                onDismiss = { contextMessage = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            }
            // The save confirmation: the same
            // fullscreen-panel grammar as the viewer's, auto-dismissing after
            // 2 s so a tap isn't needed.
            saveConfirm?.let { ok ->
                LaunchedEffect(ok) {
                    delay(2_000)
                    saveConfirm = null
                }
                LightFullscreenModal(
                    message = if (ok) "Photo saved" else "Couldn't save photo",
                    onClose = { saveConfirm = null },
                )
            }
            // Unsend confirmation: one accidental
            // long-press must not nuke a message — the same fullscreen-panel
            // grammar as the photo viewer's save confirm.
            unsendConfirm?.let { target ->
                UnsendConfirmPanel(
                    target = target,
                    onConfirm = {
                        viewModel.unsendMessage(target)
                        unsendConfirm = null
                    },
                    onDismiss = { unsendConfirm = null },
                )
            }
            // The in-app volume panel replica (feedback 2026-08-30): the volume
            // rocker shows it over the thread while a voice note plays/pauses.
            val volumePanel by viewModel.volumePanel.collectAsState()
            VolumePanelOverlay(
                state = volumePanel,
                onDismiss = { viewModel.dismissVolumePanel() },
            )
        }

        // Show the newest messages on open (and after sending); not on older
        // pages, which arrive while the user is reading further up. Returning
        // from the fullscreen photo viewer restores the position at first
        // composition (the list state's initial params above) — the
        // takeScrollToRestore here is the consume-once guard, so that path
        // never re-scrolls or jumps to the bottom; the jumpToBottom key keeps the effect re-firing when
        // the flag flips after the load.
        LaunchedEffect(jumpToBottom, messages.size) {
            if (messages.isEmpty()) return@LaunchedEffect
            viewModel.takeScrollToRestore()?.let { (index, offset) ->
                listState.scrollToItem(index, offset)
                return@LaunchedEffect
            }
            if (jumpToBottom) {
                listState.scrollToItem(0) // index 0 = the bottom in reverseLayout
                viewModel.jumpToBottom.value = false
            }
        }

        // Save the thread's scroll position continuously (the ViewModel holds
        // it across the photo-viewer navigation; see takeScrollToRestore).
        // The small delay skips the fresh composition's pre-scroll snapshot —
        // index 0 before the jump/restore scroll lands.
        LaunchedEffect(listState) {
            delay(100)
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }.distinctUntilChanged().collect { (index, offset) ->
                viewModel.saveScroll(index, offset)
            }
        }
    }

    private fun openComposer(edit: LightServiceMethod.GetMessages.Message? = null) {
        navigateTo(screenFactory = { ComposerScreen(it, room.id, room.name, edit) }) { result ->
            if (result != null) {
                if (edit == null) {
                    // The message went out — drop the restored draft so the
                    // next open starts clean (feedback 2026-08-22).
                    composerDrafts.remove(room.id)
                    // Show the sent message immediately (optimistic echo); the
                    // poll replaces the row with the real event once sync lands.
                    viewModel.addOptimistic(
                        LightServiceMethod.GetMessages.Message(
                            id = result.id,
                            sender = "",
                            senderName = "",
                            body = result.body,
                            timestampMs = result.timestampMs,
                            isMine = true,
                        ),
                    )
                } else {
                    // Edit: the row shows the new body instantly via
                    // the overlay; the poll's served page drops it once the
                    // edit echo lands.
                    viewModel.applyEditEcho(result.id, result.body)
                }
                viewModel.loadNewest(quiet = true)
            }
        }
    }

    /**
     * The contact overlay: the identifier line is the
     * bridge UID when it IS the number/username — see [contactIdentifier].
     */
    private fun openContact() {
        navigateTo(screenFactory = {
            ContactScreen(
                it,
                room.id,
                room.name,
                room.network,
                community = room.community,
                contactIdentifier(room.contactId, room.name, room.contactPhone),
                room.contactPhone,
                muted = viewModel.muted,
                onToggleMute = viewModel::toggleMuted,
                pinned = viewModel.pinned,
                onTogglePin = viewModel::togglePinned,
                archived = viewModel.archived,
                onToggleArchive = viewModel::toggleArchived,
            )
        })
    }
}

private const val DECRYPTION_NOTICE =
    "Encrypted — verify this device to read messages (Settings → Account → Verify Device)"

/** Verified device, but the megolm sessions for this room's history haven't
 *  arrived (fresh login without a key backup): the events are stored, the keys
 *  are pending from the account's other devices. */
private const val DECRYPTION_NOTICE_KEYS_PENDING =
    "Encrypted — history can't be read yet, keys from your other devices are pending (Settings → Account → Verify Device)"

/**
 * The unsend confirmation. LP3: the
 * question reads big, the message previews under it (3 lines, ellipsized), the
 * destructive action sits alone in the bottom bar, and back (<, top left)
 * cancels — the composer's navigation grammar, not a modal.
 */
@Composable
private fun UnsendConfirmPanel(
    target: LightServiceMethod.GetMessages.Message,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onDismiss,
                contentDescription = "Back",
            ),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightText(
                    text = "Are you sure you want to unsend this message?",
                    variant = LightTextVariant.Subheading,
                    align = TextAlign.Center,
                )
                LightText(
                    text = target.body.ifBlank { "[Message]" },
                    variant = LightTextVariant.Paragraph,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(
                LightBarButton.Text(
                    text = "CONFIRM",
                    onClick = onConfirm,
                ),
            ),
        )
    }
}

@Composable
private fun DecryptionNotice(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 12.dp),
    )
}

/** One row of the thread: a message. [showTime]: whether this
 *  message starts a group (a new day, a different sender, or a gap of
 *  [GROUP_WINDOW_MS] from the previous message) — the only messages that
 *  carry a timestamp. The timestamp itself carries the day tag
 *  ("Yesterday", weekday, "Aug 12") when the message isn't from today
 */
private data class ThreadRow(
    val message: LightServiceMethod.GetMessages.Message,
    val showTime: Boolean,
) {
    val key: String get() = message.id
}

/**
 * Messages in display order (newest first — the LazyColumn is reverseLayout,
 * so index 0 sits at the bottom). Consecutive same-sender messages within
 * [GROUP_WINDOW_MS] form a group that shows its timestamp only on the first
 * message; each new day also starts a group, and that first message's
 * timestamp carries the day tag ("Yesterday", the weekday, or "Aug 12" —
 * never "Today").
 */
private fun buildThreadRows(messages: List<LightServiceMethod.GetMessages.Message>): List<ThreadRow> {
    val rows = mutableListOf<ThreadRow>()
    var prevDay: LocalDate? = null
    var prevMessage: LightServiceMethod.GetMessages.Message? = null
    for (message in messages) { // oldest-first, as the view model stores them
        if (message.timestampMs <= 0) {
        rows += ThreadRow(message, showTime = true)
            continue
        }
        val day = dayOf(message.timestampMs)
        val newDay = prevDay != null && day != prevDay
        val showTime = newDay || prevMessage == null ||
            prevMessage.sender != message.sender ||
            message.timestampMs - prevMessage.timestampMs >= GROUP_WINDOW_MS
        rows += ThreadRow(message, showTime)
        prevMessage = message
        prevDay = day
    }
    return rows.asReversed()
}

/** Consecutive same-sender messages closer than this share one timestamp. */
private const val GROUP_WINDOW_MS = 15 * 60 * 1000L

/**
 * Outgoing message body: left-aligned text in a block sized to the WIDEST
 * line (measured without a width cap, then clipped to the message column's
 * max width), so the block hugs the text instead of the full column — a long
 * unbreakable word (a URL, an email address) no longer collapses the block to
 * the width of the short line before it. The block never exceeds the column cap, so no line spans
 * edge to edge.
 */
@Composable
private fun OutgoingBodyText(body: String, maxWidthPx: Int) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val style = LightThemeTokens.typography.paragraph.scaledForScreenHeight()
    val widthDp = remember(body, maxWidthPx, density, style) {
        val layout = textMeasurer.measure(
            text = AnnotatedString(body),
            style = style,
            // No width cap: the widest natural line sizes the block (capped
            // below at the column max). Measuring with the cap would break the
            // first line before an overlong word and size the block to that
            // shortened line.
            constraints = Constraints(),
        )
        var widestPx = 0f
        for (i in 0 until layout.lineCount) {
            widestPx = maxOf(widestPx, layout.getLineRight(i))
        }
        // Round UP: the box must be at least as wide as a line's last word —
        // a sub-pixel shortfall flips the wrap and drops the word to line 2,
        // leaving the gap on the top line.
        val w = minOf(ceil(widestPx), maxWidthPx.toFloat())
        with(density) { w.toFloat().toDp() }
    }
    LightText(
        text = body,
        variant = LightTextVariant.Paragraph,
        modifier = Modifier
            .padding(top = 1.dp)
            .width(widthDp),
    )
}

@Composable
private fun MessageRow(
    message: LightServiceMethod.GetMessages.Message,
    showSender: Boolean,
    showTime: Boolean,
    statusTag: String?,
    mediaBytes: Map<String, ByteArray>,
    allowMobile: Boolean,
    playing: Boolean,
    playingPositionMs: Long?,
    playingPositionAtMs: Long,
    counterPending: Boolean,
    paused: Boolean,
    pausedPositionMs: Long?,
    voiceError: Pair<String, String>?,
    reactionError: Pair<String, String>?,
    onEnsureMedia: (String, Boolean) -> Unit,
    onPlayVoiceNote: (String) -> Unit,
    onRetrySend: (LightServiceMethod.GetMessages.Message) -> Unit,
    onResendAsNew: (LightServiceMethod.GetMessages.Message) -> Unit,
    onToggleLike: (LightServiceMethod.GetMessages.Message) -> Unit,
    onOpenContext: (LightServiceMethod.GetMessages.Message) -> Unit,
    onOpenImage: (ByteArray) -> Unit,
) {
    // A buffer keeps message text off the far screen edge. Outgoing
    // messages sit on the right and incoming on the left — the built-in Phone
    // app's layout — each capped at ~7/8 of the row width so long text never
    // spans edge to edge.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // A little extra air between senders: the group-start rows (which
            // carry the timestamp + name) get more top padding; same-sender
            // grouped rows stay tight.
            .padding(
                // Outgoing text gets a wider right buffer so it stays clear of
                // the thread scrollbar (feedback 2026-08-17).
                start = 1.5f.gridUnitsAsDp(),
                end = if (message.isMine) 2.5f.gridUnitsAsDp() else 1.5f.gridUnitsAsDp(),
                top = if (showTime) 8.dp else 3.dp,
                bottom = if (showTime) 8.dp else 3.dp,
            ),
    ) {
        // Bridge system messages (m.notice: "Turned off disappearing
        // messages", timer-set notices, …) are a quiet centered small line —
        // not a normal message from the contact. Solid white,
        // like the timestamps/labels: hierarchy from size, not dimming.
        if (message.contentType == "notice") {
            LightText(
                text = message.body,
                variant = LightTextVariant.Superfine,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            return@BoxWithConstraints
        }
        // Tombstones: the message was unsent (redacted).
        // Rendered like a normal text row — same size and sender alignment,
        // "[Message unsent]" body, no reactions/status/gestures (the event is
        // gone for everyone). The old centered Superfine placeholder read as
        // transient and out of place. The gesture
        // gate below requires contentType == "text", so tombstones stay inert.
        // The outgoing body's column cap (0.875 × the row content width):
        // outgoing text is measured against it so the block never spans the
        // full row; the block width itself comes from
        // [OutgoingBodyText] (the widest line, capped here).
        val bodyMaxWidthPx = with(LocalDensity.current) {
            (maxWidth * MESSAGE_WIDTH_FRACTION).toPx().roundToInt()
        }
        val inFlight = message.id.startsWith(LOCAL_ROW_PREFIX)
        val failed = message.sendStatus?.startsWith("FAIL_") == true
        // Locally-failed rows (the outbox recorded a send error, txn still
        // pending) are tappable — tap re-sends the same transaction
        // Bridge-reported FAIL_* text rows (real event id, no
        // txn to retry) re-send the same body as a NEW message instead
        // Non-text bridge failures stay a plain marker.
        val retryable = failed && message.sendStatus == "FAIL_LOCAL_SEND" && inFlight
        val retryableNew = failed && !inFlight && message.contentType == "text" && message.body.isNotBlank()
        // Resend budget per message chain: after
        // [MAX_RESEND_ATTEMPTS] — or once the message is older than
        // [RESEND_MAX_AGE_MS] — the row turns static "failed to deliver":
        // no more duplicate sends.
        val resendAttempts by resendChainState.observe().collectAsState()
        val resendExhausted = retryableNew && (
            (resendAttempts[message.id] ?: 0) >= MAX_RESEND_ATTEMPTS ||
                System.currentTimeMillis() - message.timestampMs >= RESEND_MAX_AGE_MS
            )
        Column(
            modifier = Modifier
                .fillMaxWidth(MESSAGE_WIDTH_FRACTION)
                .align(if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart)
                // A locally-failed send re-sends when tapped; the
                // tap target is the row's bubble area, like the image/audio
                // rows' own clickables.
                .then(
                    when {
                        retryable -> Modifier.lightClickable(onClick = { onRetrySend(message) })
                        retryableNew && !resendExhausted -> Modifier.lightClickable(onClick = { onResendAsNew(message) })
                        else -> Modifier
                    },
                )
                // Double-tap a received TEXT row to like/
                // unlike it; long-press opens the context
                // window over the bottom half; OWN text
                // rows open it too (EDIT / UNSEND), when the
                // bridge caps still allow either. `combinedClickable`/
                // `lightClickable` can't do double-tap. Media rows carry their
                // own gestures inside their content composables (image tap =
                // viewer, voice-note tap = play, long-press = context window)
                // — the text gate stays text-
                // only; failed / in-flight rows keep their retry tap.
                .then(
                    if (!failed && !inFlight && message.contentType == "text" &&
                        (!message.isMine || message.canEdit || message.canUnsend)
                    ) {
                        // pointerInput's block survives recomposition when the
                        // key (the stable event id) doesn't change — without
                        // rememberUpdatedState the callbacks captured the
                        // first-composed row snapshot, so a toggle computed
                        // against stale reactions re-SENT the like every time.
                        val gestureMessage by rememberUpdatedState(message)
                        // (the Phone tool's native
                        // half-panel grammar): NO vibration on finger-down —
                        // the haptic fires on actual gestures. A
                        // like double-tap buzzes on BOTH taps, so every clean
                        // tap buzzes (the same feel as the chat list's
                        // lightClickable) and the second tap inside the system
                        // double-tap window toggles the like. Compose's
                        // onDoubleTap swallows the first tap's haptic — hence
                        // the manual count. detectTapGestures reports taps only
                        // on a clean up (no movement past the touch slop), so
                        // scrolling never buzzes, same as the list. Gated by
                        // the same LocalHapticsEnabled the SDK's lightClickable
                        // reads; the SDK's context-based haptic helper is
                        // plugin-banned in tool code, so this goes through
                        // Compose's haptic API instead (see [rememberHapticBuzz]).
                        val buzz = rememberHapticBuzz()
                        Modifier
                            .pointerInput(message.id) {
                                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                                var lastTapMs = 0L
                                detectTapGestures(
                                    // The like guards isMine itself (own rows never
                                    // react) — one gesture block serves both sides.
                                    onTap = {
                                        buzz()
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapMs < doubleTapMs) {
                                            lastTapMs = 0L
                                            onToggleLike(gestureMessage)
                                        } else {
                                            lastTapMs = now
                                        }
                                    },
                                    onLongPress = {
                                        buzz()
                                        onOpenContext(gestureMessage)
                                    },
                                )
                            }
                    } else {
                        Modifier
                    },
                ),
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
        ) {
            if (showSender && !message.isMine && message.senderName.isNotBlank()) {
                LightText(
                    text = message.senderName,
                    variant = LightTextVariant.Detail,
                )
            }
            // Only the first message of a group carries the time —
            // consecutive same-sender messages within 15 minutes are combined
            // visually, while the sender/alignment stays on every message.
            // An IN-FLIGHT row shows "SENDING" even inside a group — the group
            // would otherwise hide the slot entirely, and the just-sent
            // message must still be visibly pending.
            // When the server confirms it, the served page swaps in the real
            // row: grouped → it merges under the shared timestamp; otherwise
            // it carries its own. A failed send shows its time, not SENDING.
            if (showTime || (inFlight && !failed)) {
                LightText(
                    text = if (inFlight && !failed) {
                        "SENDING"
                    } else {
                        formatMessageTime(message.timestampMs)
                    },
                    variant = LightTextVariant.Superfine,
                    // Solid white — timestamps read like the rest of the
                    // message, not dimmed (feedback 2026-08-21).
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            // A forwarded message carries a small "forwarded" tag — the
            // bridge's WhatsApp forward marker, lifted out of the body by the
            // companion and served as this flag. Outgoing TEXT rows lead with
            // the ↷ glyph (Subtitle) above the body with the word under it;
            // incoming text and MEDIA rows put the glyph beside the content
            // with the word under it. Tight, lowercase, consistent with the other tags.
            if (message.forwarded && message.contentType == "text" && message.isMine) {
                Row(
                    modifier = Modifier.padding(top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = "\u21B7",
                        variant = LightTextVariant.Subtitle,
                    )
                    LightText(
                        text = "forwarded",
                        variant = LightTextVariant.Superfine,
                        modifier = Modifier.padding(start = 0.25f.gridUnitsAsDp()),
                    )
                }
            }
            // Media rows open the context window on long-press — received rows always (LIKE/REACT [+ SAVE on
            // images]), own rows only while the bridge still allows an unsend
            // (canEdit never applies to media; otherwise no panel at all).
            val contextGesture = if (!message.isMine || message.canUnsend) {
                { onOpenContext(message) }
            } else null
            if (message.contentType == "image") {
                ForwardedMediaRow(message) {
                    ImageMessageContent(message, mediaBytes, allowMobile, onEnsureMedia, onOpenImage, contextGesture)
                }
            } else if (message.contentType == "audio") {
                ForwardedMediaRow(message) {
                    AudioMessageContent(
                        message = message,
                        playing = playing,
                        playingPositionMs = playingPositionMs,
                        playingPositionAtMs = playingPositionAtMs,
                        counterPending = counterPending,
                        paused = paused,
                        pausedPositionMs = pausedPositionMs,
                        error = voiceError?.takeIf { it.first == message.id }?.second,
                        onTogglePlay = { onPlayVoiceNote(message.id) },
                        onOpenContext = contextGesture,
                    )
                }
            } else {
                if (message.isMine) {
                    // Outgoing: block sized to the first line so the top line's
                    // last word always touches the right edge (see
                    // [OutgoingBodyText]).
                    OutgoingBodyText(message.body, bodyMaxWidthPx)
                } else if (message.forwarded) {
                    // Forwarded incoming text: the ↷ glyph anchors the left,
                    // beside the body like the call notices' phone icon. It
                    // renders at Paragraph — same line box as the body, so
                    // the row hugs the text — scaled ~2.1x (≈ the Subtitle
                    // ink size) about the box center, which keeps the arrow
                    // centered on the body line: Subtitle's far taller line
                    // box carried the ink low and opened gaps above/below
                    // The small "forwarded" word sits
                    // at the message's left edge under both.
                    Column(modifier = Modifier.padding(top = 1.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ForwardedArrowGlyph()
                            LightText(
                                text = message.body,
                                variant = LightTextVariant.Paragraph,
                                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        LightText(
                            text = "forwarded",
                            variant = LightTextVariant.Superfine,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                } else if (message.body.startsWith("Incoming call")) {
                    // Bridged call notices ("Incoming call. Use the WhatsApp
                    // app to answer." — Beeper's bridges can't relay calls, so
                    // the contact's ghost posts a plain m.text; the phone icon
                    // marks the row as a call, like the built-in Phone tool.
                    Row(
                        modifier = Modifier.padding(top = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LightIcon(
                            icon = LightIcons.CALL,
                            size = 1.5f,
                            contentDescription = null, // the text carries it
                        )
                        LightText(
                            text = message.body,
                            variant = LightTextVariant.Paragraph,
                            modifier = Modifier.padding(start = 0.75f.gridUnitsAsDp()),
                        )
                    }
                } else {
                    LightText(
                        text = message.body,
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                // A video row carries its caption under the "[Video]" marker —
                // the marker keeps the media context, the caption the content.
                message.caption?.let { caption ->
                    LightText(
                        text = caption,
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            // An edited message shows a quiet "edited" tag under the body
            // and it shares one line
            // with the delivery/status tag ("edited · delivered"), same
            // grammar as the reactions separator; either alone renders as
            // before.
            listOfNotNull(
                "edited".takeIf { message.edited },
                statusTag,
            ).joinToString(" · ")
                .takeIf { it.isNotEmpty() }
                ?.let { footer ->
                    LightText(
                        text = footer,
                        variant = LightTextVariant.Superfine,
                        // Solid white like the timestamps (feedback 2026-08-21).
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            // Reactions, as a quiet tag under the message (same
            // grammar as the "not delivered" marker). Each entry reads
            // "Name reacted with ❤️" (or "You reacted with …" for own) —
            if (message.reactions.isNotEmpty()) {
                LightText(
                    text = message.reactions.joinToString(" · "),
                    variant = LightTextVariant.Superfine,
                    // Solid white like the timestamps/delivery labels
                    // (feedback 2026-08-21).
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            // A reaction toggle that failed server-side reverted the
            // optimistic tag — the quiet row error says so (same grammar as the
            // voice-note error).
            reactionError?.takeIf { it.first == message.id }?.let { error ->
                LightText(
                    text = error.second,
                    variant = LightTextVariant.Superfine,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            // Beeper reports failed deliveries with a com.beeper.message_send_status
            // event — a quiet label beats a silent stall. The
            // thread poll surfaces it within seconds, no new send needed. It
            // shows on any message, old or new.
            if (message.isMine && failed) {
                LightText(
                    // A locally-failed row re-sends the same transaction when
                    // tapped; a bridge-reported text failure re-sends the body
                    // as a new message; non-text bridge failures have no resend
                    // path. A chain past [MAX_RESEND_ATTEMPTS]
                    // turns static "failed to deliver".
                    text = when {
                        retryable -> "failed to send. tap to resend"
                        retryableNew && !resendExhausted -> "not delivered. tap to resend"
                        retryableNew -> "failed to deliver"
                        else -> "not delivered"
                    },
                    variant = LightTextVariant.Superfine,
                    // Solid white like the timestamps — the delivery labels
                    // read like the rest of the message, not dimmed.
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** The ↷ forward glyph beside media/text content: Paragraph variant scaled
 *  ~2.1x (≈ the Subtitle ink size) about a pivot 0.72 down the line box —
 *  the ↷ falls back to Noto Sans Symbols, whose run sits low in the
 *  Paragraph line box; the pivot keeps the ink centered and the equal
 *  padding re-absorbs the scaled ink. */
@Composable
private fun ForwardedArrowGlyph() {
    LightText(
        text = "\u21B7",
        variant = LightTextVariant.Paragraph,
        modifier = Modifier
            .graphicsLayer {
                scaleX = 2.1f
                scaleY = 2.1f
                transformOrigin = TransformOrigin(0.5f, 0.72f)
            }
            .padding(2.5.dp),
    )
}

/** Forwarded MEDIA rows: the ↷ glyph beside the
 *  content, the small "forwarded" word under it — the same grammar as
 *  forwarded incoming text. */
@Composable
private fun ForwardedMediaRow(
    message: LightServiceMethod.GetMessages.Message,
    content: @Composable () -> Unit,
) {
    if (!message.forwarded) {
        content()
        return
    }
    Column(modifier = Modifier.padding(top = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForwardedArrowGlyph()
            content()
        }
        LightText(
            text = "forwarded",
            variant = LightTextVariant.Superfine,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** An image message row: fetches the display JPEG once, then renders it.
 *  Tapping the thumbnail opens the fullscreen viewer. */
@Composable
private fun ImageMessageContent(
    message: LightServiceMethod.GetMessages.Message,
    mediaBytes: Map<String, ByteArray>,
    allowMobile: Boolean,
    onEnsureMedia: (String, Boolean) -> Unit,
    onOpenImage: (ByteArray) -> Unit,
    onOpenContext: (() -> Unit)?,
) {
    // The toggle is part of the key: flipping "Mobile data downloads" (or
    // moving off cellular) re-attempts rows that were skipped as Wi-Fi-only.
    LaunchedEffect(message.id, allowMobile) { onEnsureMedia(message.id, allowMobile) }
    val bytes = mediaBytes[message.id]
    // Decode off the main thread: the in-composition decode blocked the UI
    // thread's first paint for every visible photo.
    // The text fallback below renders until the bitmap lands. Seeded from the
    // process-wide [chatsBitmapCache]: returning from the fullscreen viewer
    // re-enters this composition, and re-decoding started from a null frame
    // (a "[Photo]" flash before the image popped back in).
    val bitmap by produceState<ImageBitmap?>(chatsBitmapCache.get(message.id), bytes) {
        if (bytes != null && value == null) {
            value = withContext(Dispatchers.Default) {
                bytes.let { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() }
            }?.also { chatsBitmapCache.put(message.id, it) }
        }
    }
    val image = bitmap
    // Gesture callbacks stay fresh across recompositions (the text rows'
    // rememberUpdatedState lesson): the pointerInput keys below never change,
    // so without this the captured lambdas kept the first-composed snapshot.
    val currentOnOpenImage by rememberUpdatedState(onOpenImage)
    val currentOnOpenContext by rememberUpdatedState(onOpenContext)
    val buzz = rememberHapticBuzz()
    if (bytes == null || image == null) {
        // Still loading, or the media can't be fetched/decoded (e.g.
        // still-encrypted): fall back to the row text ("[Photo]" or the file
        // name). A failed/skipped fetch (no bytes) is worth one more tap —
        // re-run it without leaving the thread. Decode failures (bytes but no bitmap) stay
        // dead text; re-fetching would not help.
        val bodyModifier =
            if (bytes == null) Modifier.lightClickable(onClick = { onEnsureMedia(message.id, allowMobile) })
            else Modifier
        LightText(
            text = message.body,
            variant = LightTextVariant.Paragraph,
            modifier = bodyModifier.padding(top = 1.dp),
        )
        // The caption still shows under the placeholder — the text row is all
        // the context there is (feedback 2026-09-01).
        message.caption?.let { caption ->
            LightText(
                text = caption,
                variant = LightTextVariant.Paragraph,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        return
    }
    Image(
        bitmap = image,
        contentDescription = message.body,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            // Sized to the photo's own aspect rather than stretched to the row
            // width: a tall photo caps at MAX_IMAGE_HEIGHT_DP and hugs the
            // sender's edge via the row's End/Start alignment — no centered
            // side-bars beside portrait shots.
            .heightIn(max = MAX_IMAGE_HEIGHT_DP)
            .padding(top = 1.dp)
            // Tap opens the viewer; long-press opens the context window — the old lightClickable tap-only target
            // can't carry the long-press. The haptic fires on the trigger, not
            // on finger-down (the Phone tool's half-panel grammar).
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnOpenImage(bytes) },
                    // A long-press consumes the gesture — no viewer open.
                    onLongPress = {
                        buzz()
                        currentOnOpenContext?.invoke()
                    },
                )
            },
    )
    // Feedback round 2026-08-19: received photos with a caption (the m.image
    // body) show it under the thumbnail, like native messaging apps.
    val caption = message.caption
    if (caption != null) {
        LightText(
            text = caption,
            variant = LightTextVariant.Paragraph,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** A voice-note row: a play/pause icon + label, tapped to toggle playback in
 *  the companion. The playing row shows its state via the poll's
 *  `audioPlayingEventId`, so the highlight survives message-list refreshes.
 *  A fetch/playback failure ([error]) shows briefly under the label instead of
 *  a silent no-op. */
@Composable
private fun AudioMessageContent(
    message: LightServiceMethod.GetMessages.Message,
    playing: Boolean,
    playingPositionMs: Long?,
    playingPositionAtMs: Long,
    counterPending: Boolean,
    paused: Boolean,
    pausedPositionMs: Long?,
    error: String?,
    onTogglePlay: () -> Unit,
    /** Non-null: long-press opens the context window (LP3 feedback
     *  2026-09-03) — the old lightClickable played on any press length. */
    onOpenContext: (() -> Unit)?,
) {
    // While playing, refresh the interpolated position counter every second:
    // the position polls arrive every few seconds, and the label interpolates
    // between them.
    var nowMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing) {
        while (playing) {
            delay(1000)
            nowMs = android.os.SystemClock.elapsedRealtime()
        }
    }
    val durationMs = message.durationMs
    val label = when {
        playing -> {
            val base = playingPositionMs ?: 0L
            // Unanchored (player spinning up after a tap): hold the seeded
            // position — the pause point on resume, 0:00 on a new note — until
            // the poll confirms the companion's real position. Counting on
            // wall clock here ran ahead of the companion and the next poll
            // jumped the label BACK.
            val pos = if (counterPending) base else base + (nowMs - playingPositionAtMs)
            // Just the running position while playing (the row already showed
            // its length when idle).
            formatDuration(durationMs?.let { pos.coerceAtMost(it) } ?: pos)
        }
        // Paused: the persistent pause point, not the full length (feedback
        // 2026-08-27).
        paused && pausedPositionMs != null -> formatDuration(pausedPositionMs)
        durationMs != null -> formatDuration(durationMs)
        else -> message.body
    }
    val currentOnTogglePlay by rememberUpdatedState(onTogglePlay)
    val currentOnOpenContext by rememberUpdatedState(onOpenContext)
    val buzz = rememberHapticBuzz()
    Row(
        modifier = Modifier
            // Tap toggles playback; long-press opens the context window — detectTapGestures consumes the long-press
            // so it never falls through to a play. The haptic fires on the
            // trigger, not on finger-down (the Phone tool's half-panel
            // grammar).
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTogglePlay() },
                    onLongPress = {
                        buzz()
                        currentOnOpenContext?.invoke()
                    },
                )
            }
            .padding(top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY,
            size = 0.9f,
            contentDescription = if (playing) "Stop voice note" else "Play voice note",
        )
        LightText(
            text = label,
            variant = LightTextVariant.Paragraph,
            // Solid white while playing — the ticking position reads like
            // the rest of the message (feedback 2026-08-27).
            modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
        )
    }
    // A failed fetch/play is a quiet one-line "couldn't play" under the row,
    // not a silent no-op (feedback 2026-08-19).
    if (error != null) {
        LightText(
            text = "Couldn't play — $error",
            variant = LightTextVariant.Superfine,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** m:ss for a voice-note length/position. */
private fun formatDuration(ms: Long): String =
    DateUtils.formatElapsedTime((ms / 1000).coerceAtLeast(0))

/** Cap for the message block — long text never spans the full row width.
 *  The far-side buffer (the empty band on the message's outer side) is half
 *  of what it was: 25 % → 12.5 %. Incoming text fills
 *  this width; outgoing text is measured against it and blocks can be
 *  narrower — sized to the first line so the top line touches the right. */
private const val MESSAGE_WIDTH_FRACTION = 0.875f

/** Tallest an image row grows; tall photos letterbox inside. */
private val MAX_IMAGE_HEIGHT_DP = 240.dp

@Composable
private fun StatusText(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 24.dp),
    )
}
