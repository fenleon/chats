package com.lightphone.chats.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.ChatSettings
import com.lightphone.chats.VolumePanelOverlay
import com.lightphone.chats.dayDividerLabel
import com.lightphone.chats.dayOf
import com.lightphone.chats.formatMessageTime
import com.thelightphone.sdk.LightScreen
import com.lightphone.chats.ChatLightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Newest image messages whose bytes start downloading on page arrival. */
private const val MEDIA_PREFETCH_COUNT = 4

class ThreadViewModel(
    private val room: LightServiceMethod.GetRooms.Room,
) : ChatLightViewModel<Unit>() {

    /** Oldest-first page of messages; older pages are prepended by [loadOlder]. */
    val messages = MutableStateFlow<List<LightServiceMethod.GetMessages.Message>>(emptyList())
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
     * Display JPEG bytes per image-message event id (Phase 13). Fetched once
     * via [ensureMedia] and reused across polls — the thread poll replaces the
     * list but the event ids stay stable.
     */
    val mediaBytes = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

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
     * Event id of the voice note playing in the companion (Phase 14). Fed by
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
     * (eventId, message) of a voice-note playback that failed to fetch/play —
     * the row shows the error briefly instead of a silent no-op (feedback
     * 2026-08-19). Cleared after a few seconds.
     */
    val voiceError = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * Optimistic rows from a send, waiting for their sync echo (feedback
     * pass). The poll replaces them with the real events as they land.
     */
    private val pendingMessages = mutableListOf<LightServiceMethod.GetMessages.Message>()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // While this room is on screen the companion suppresses its
        // new-message notifications.
        viewModelScope.launch { ChatClient.setActiveRoom(room.id) }
        // Messages load first — don't gate them behind the e2ee check (that
        // only drives the decryption notice).
        loadNewest()
        viewModelScope.launch {
            e2eeVerified.value = ChatClient.e2eeState()?.verified
        }
        startPolling()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        stopPolling()
    }

    override fun onAppPause() {
        super.onAppPause()
        // The tool is no longer visible (standby/another app); messages in
        // this room may notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        stopPolling()
    }

    /**
     * Quietly re-fetches the newest page while the thread stays visible
     * (feedback pass): picks up the send echo and Beeper send-status events
     * within a few seconds, with no spinner and no scroll jump. The server
     * serves the cached newest page for the active room, so each poll is cheap.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(THREAD_POLL_MS)
                loadNewest(quiet = true)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun loadNewest(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) loading.value = true
            val loaded: List<LightServiceMethod.GetMessages.Message>
            try {
                val page = ChatClient.getMessages(room.id, null, PAGE_SIZE)
                loaded = page?.messages.orEmpty()
                roomEncrypted.value = page?.encrypted ?: false
                playingEventId.value = page?.audioPlayingEventId
                if (page?.audioPositionMs != null) {
                    playingPositionMs.value = page.audioPositionMs
                    playingPositionAtMs.value = android.os.SystemClock.elapsedRealtime()
                }
                // Merge, don't replace: the fresh newest page updates the tail while
                // older pages the user scrolled into (via [loadOlder]) stay put. A
                // full replace every poll shrank the list back to the newest page
                // and yanked the scroll down (and made top rows flicker in/out).
                if (loaded.isNotEmpty() || messages.value.isEmpty()) {
                    messages.value = mergeWithPending(mergeNewestPage(loaded, messages.value))
                    hasMore.value = page?.hasMore ?: false
                }
            } finally {
                // A binder exception mid-fetch must not leave the thread stuck on
                // "Loading messages…" (feedback 2026-08-19: a send + quick exit +
                // re-enter could wedge it) — the flag always clears.
                if (!quiet) loading.value = false
            }
            if (!quiet) {
                jumpToBottom.value = true
                // Opening the thread marks it read up to the newest event; the
                // room list's unread count drops on its next refresh.
                val markEventId = loaded.lastOrNull()?.id ?: room.lastEventId ?: return@launch
                ChatClient.markRead(room.id, markEventId)
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
                iterator.remove() // echoed — the real event replaces it
                continue
            }
            val echoed = loaded.any {
                it.isMine && it.body == pending.body &&
                    kotlin.math.abs(it.timestampMs - pending.timestampMs) < OPTIMISTIC_MATCH_WINDOW_MS
            }
            if (echoed) {
                iterator.remove()
            } else {
                result += pending // still newest; stays at the end
            }
        }
        return result
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
     * Toggles playback of a voice note in the companion (Phase 14). The local
     * state flips immediately so the row reacts; the response and the poll's
     * `audioPlayingEventId` keep it accurate as playback finishes. A fetch or
     * playback failure surfaces on the row instead of a silent no-op
     * (feedback 2026-08-19).
     */
    fun playVoiceNote(eventId: String) {
        playingEventId.value = if (playingEventId.value == eventId) null else eventId
        viewModelScope.launch {
            val (playing, error) = ChatClient.playVoiceNote(room.id, eventId)
            playingEventId.value = if (playing) eventId else null
            if (!playing && error != null) {
                voiceError.value = eventId to error
                delay(VOICE_ERROR_DISMISS_MS)
                if (voiceError.value?.first == eventId) voiceError.value = null
            }
        }
    }

    /** Fetches an image message's display bytes if they aren't cached yet. */
    fun ensureMedia(eventId: String, allowMobileData: Boolean) {
        if (mediaBytes.value.containsKey(eventId)) return
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
                val page = ChatClient.getMessages(room.id, oldest.id, PAGE_SIZE)
                val older = page?.messages.orEmpty()
                if (older.isNotEmpty()) {
                    // distinctBy guards the page boundary: if the timeline changed
                    // between calls, the cursor event can appear at both edges.
                    messages.value = (older + messages.value).distinctBy { it.id }
                }
                hasMore.value = page?.hasMore ?: hasMore.value
            } finally {
                // A binder failure must not wedge pagination (feedback
                // 2026-08-19: an exception here left loadingMore stuck, so
                // older messages never loaded again).
                loadingMore.value = false
            }
        }
    }

    private var pollJob: Job? = null

    private companion object {
        const val PAGE_SIZE = 20
        /** Poll cadence while the thread is on screen (feedback pass). */
        const val THREAD_POLL_MS = 3_000L
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
        val voiceError by viewModel.voiceError.collectAsState()
        val showReadStatus by ChatSettings.showReadStatus.collectAsState()
        val downloadOverMobile by ChatSettings.downloadOverMobile.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val volumePanel by viewModel.volumePanel.collectAsState()
        val listState = rememberLazyListState()

        // Load the persisted read-status toggle once (idempotent).
        LaunchedEffect(Unit) { ChatSettings.load(lightContext) }

        // Prioritised media: the newest image messages' bytes start downloading
        // the moment the page lands, so visible rows render as soon as their
        // bytes arrive (instead of only when each row composes and fetches).
        LaunchedEffect(messages) {
            messages.orEmpty().asReversed()
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

        // An unverified device cannot decrypt an encrypted room — say so
        // plainly (and immediately, no spinner) instead of "Loading…" forever.
        val needsDecryptionNotice = e2eeVerified == false && roomEncrypted

        // The newest message in the thread: the only one that carries the
        // seen/delivered tag (older messages show a marker only when a send
        // failed). The list is oldest-first, so the last item is the newest.
        // Read status only makes sense in a 1:1 — groups get no tag at all.
        val latestMessageId = remember(messages) { messages.lastOrNull()?.id }

        // Infinite scroll, polled rather than snapshotFlow-driven: in this
        // Compose version reads of LazyListState.layoutInfo don't invalidate
        // snapshotFlow/derivedStateOf on every scroll (verified 2026-08-15), so
        // a snapshotFlow trigger never fired — the list sat at its top with
        // older messages one page away and "older messages don't load". The
        // poll reads the real layout info each tick; the loadOlder condition
        // is index-exact (the topmost visible index vs the total), so rows
        // prepended above the viewport don't re-trigger it.
        LaunchedEffect(listState) {
            while (true) {
                val info = listState.layoutInfo
                val topIndex = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
                val total = info.totalItemsCount
                if (total > 0 && topIndex >= total - OLDER_LOAD_THRESHOLD) {
                    viewModel.loadOlder()
                }
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
                        onClick = { goBack() },
                        contentDescription = "Back to chats",
                    ),
                    center = LightTopBarCenter.Text(room.name),
                    rightButton = null,
                )
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading && messages.isEmpty() -> StatusText("Loading messages…")
                        // An encrypted room on an unverified device returns an
                        // empty page — say why instead of "No messages yet."
                        needsDecryptionNotice && messages.isEmpty() -> StatusText(DECRYPTION_NOTICE)
                        messages.isEmpty() -> StatusText("No messages yet.")
                        else -> Column(modifier = Modifier.fillMaxSize()) {
                            if (needsDecryptionNotice) DecryptionNotice()
                            // Messages with a centered date divider between days
                            // (Phase 9); display order is newest-first because
                            // reverseLayout puts index 0 at the bottom.
                            val rows = remember(messages) { buildThreadRows(messages) }
                            Box(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    reverseLayout = true,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(rows, key = { it.key }) { row ->
                                        when (row) {
                                            is ThreadRow.Message -> MessageRow(
                                                row.message,
                                                // In a 1:1 the other person's name is
                                                // redundant — the thread is the
                                                // conversation with them. In groups,
                                                // the name shows at the start of each
                                                // sender's group (same rows that carry
                                                // the timestamp).
                                                showSender = !room.isDirect && row.showTime,
                                                showTime = row.showTime,
                                                showReadStatus = showReadStatus,
                                                showDeliveryTag = row.message.id == latestMessageId && room.isDirect,
                                                mediaBytes = mediaBytes,
                                                allowMobile = downloadOverMobile,
                                                playing = row.message.id == playingEventId,
                                                playingPositionMs = playingPositionMs,
                                                playingPositionAtMs = playingPositionAtMs,
                                                voiceError = voiceError,
                                                onEnsureMedia = viewModel::ensureMedia,
                                                onPlayVoiceNote = viewModel::playVoiceNote,
                                                onOpenImage = { bytes ->
                                                    navigateTo(screenFactory = { FullscreenImageScreen(it, bytes) })
                                                },
                                            )
                                            is ThreadRow.DayDivider -> DayDivider(row.label)
                                        }
                                    }
                                }
                                // Thread rows vary in height, so the SDK's
                                // uniform-height LightLazyScrollView can't drive the
                                // thumb — ThreadScrollBar estimates from the real
                                // lazy layout (same rail + thumb look as the SDK
                                // bar). Polled rather than snapshot-driven: reads of
                                // LazyListState.layoutInfo don't invalidate on every
                                // scroll in this Compose version (verified on-device),
                                // so derivedStateOf/snapshotFlow go stale.
                                val heightSampler = remember { HeightSampler() }
                                var scrollMetrics by remember {
                                    mutableStateOf(listState.threadListMetrics(heightSampler))
                                }
                                LaunchedEffect(listState) {
                                    while (true) {
                                        scrollMetrics = listState.threadListMetrics(heightSampler)
                                        delay(
                                            if (listState.isScrollInProgress) 50L else 300L,
                                        )
                                    }
                                }
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
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        // Record a voice note — bottom left, like the built-in
                        // Messages app's layout (Phase 14). Opens the
                        // companion's recording activity.
                        LightBarButton.LightIcon(
                            icon = LightIcons.MICROPHONE,
                            onClick = { viewModel.attachVoiceNote() },
                            contentDescription = "Record voice note",
                        ),
                        // Attach a photo — bottom middle, like the built-in
                        // Messages app's add slot. Opens the system photo
                        // picker via the companion (Phase 13).
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
            // The in-app volume panel (the LP3 rocker replica) draws over the
            // whole screen while shown.
            VolumePanelOverlay(
                state = volumePanel,
                onDismiss = { viewModel.dismissVolumePanel() },
            )
            }
        }

        // Show the newest messages on open (and after sending); not on older
        // pages, which arrive while the user is reading further up.
        LaunchedEffect(jumpToBottom, messages.size) {
            if (jumpToBottom && messages.isNotEmpty()) {
                listState.scrollToItem(0) // index 0 = the bottom in reverseLayout
                viewModel.jumpToBottom.value = false
            }
        }
    }

    private fun openComposer() {
        navigateTo(screenFactory = { ComposerScreen(it, room.id, room.name) }) { result ->
            if (result != null) {
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
                viewModel.loadNewest(quiet = true)
            }
        }
    }
}

private const val DECRYPTION_NOTICE =
    "Encrypted — verify this device to read messages (Settings → Account → Encrypted messages)"

@Composable
private fun DecryptionNotice() {
    LightText(
        text = DECRYPTION_NOTICE,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 12.dp),
    )
}

/** One row of the thread: a message, or a centered per-day divider (Phase 9). */
private sealed interface ThreadRow {
    val key: String

    /**
     * [showTime]: whether this message starts a group (a new day, a different
     * sender, or a gap of [GROUP_WINDOW_MS] from the previous message) — the
     * only messages that carry a timestamp (feedback pass).
     */
    data class Message(
        val message: LightServiceMethod.GetMessages.Message,
        val showTime: Boolean,
    ) : ThreadRow {
        override val key get() = message.id
    }

    data class DayDivider(val label: String, val date: LocalDate) : ThreadRow {
        override val key get() = "day-$date"
    }
}

/**
 * Messages with a centered date divider inserted between days, in display order
 * (newest first — the LazyColumn is reverseLayout, so index 0 sits at the
 * bottom). A single-day thread has no divider; each day boundary shows the
 * newer day's label above the older day's messages. Consecutive same-sender
 * messages within [GROUP_WINDOW_MS] form a group that shows its timestamp only
 * on the first message (the day-divider labels follow the chat list's
 * delineation: today / yesterday / weekday / "Month XX").
 */
private fun buildThreadRows(messages: List<LightServiceMethod.GetMessages.Message>): List<ThreadRow> {
    val rows = mutableListOf<ThreadRow>()
    var prevDay: LocalDate? = null
    var prevMessage: LightServiceMethod.GetMessages.Message? = null
    for (message in messages) { // oldest-first, as the view model stores them
        if (message.timestampMs <= 0) {
            rows += ThreadRow.Message(message, showTime = true)
            continue
        }
        val day = dayOf(message.timestampMs)
        val newDay = prevDay != null && day != prevDay
        if (newDay) {
            rows += ThreadRow.DayDivider(dayDividerLabel(day), day)
        }
        val showTime = newDay || prevMessage == null ||
            prevMessage.sender != message.sender ||
            message.timestampMs - prevMessage.timestampMs >= GROUP_WINDOW_MS
        rows += ThreadRow.Message(message, showTime)
        prevMessage = message
        prevDay = day
    }
    return rows.asReversed()
}

/** Consecutive same-sender messages closer than this share one timestamp. */
private const val GROUP_WINDOW_MS = 15 * 60 * 1000L

@Composable
private fun DayDivider(label: String) {
    LightText(
        text = label,
        variant = LightTextVariant.Detail,
        lighten = true,
        align = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    )
}

/**
 * Outgoing message body: left-aligned text in a block sized to the FIRST
 * line (measured at the message column's max width), so the top line's last
 * word always touches the right edge while the rest wraps normally — the
 * left buffer varies with the message (feedback 2026-08-17).
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
            constraints = Constraints(maxWidth = maxWidthPx),
        )
        // Round UP: the box must be at least as wide as line 1's last word —
        // a sub-pixel shortfall flips the wrap and drops the word to line 2,
        // leaving the gap on the top line (verified on-device 2026-08-17).
        val w = ceil(layout.getLineRight(0))
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
    showReadStatus: Boolean,
    showDeliveryTag: Boolean,
    mediaBytes: Map<String, ByteArray>,
    allowMobile: Boolean,
    playing: Boolean,
    playingPositionMs: Long?,
    playingPositionAtMs: Long,
    voiceError: Pair<String, String>?,
    onEnsureMedia: (String, Boolean) -> Unit,
    onPlayVoiceNote: (String) -> Unit,
    onOpenImage: (ByteArray) -> Unit,
) {
    // Phase 13: a buffer keeps message text off the far screen edge. Outgoing
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
        // The outgoing body's column cap (0.875 × the row content width):
        // outgoing text is measured against it so the first line's natural
        // width — the block width — keeps the top line's last word touching
        // the right edge (feedback 2026-08-17).
        val bodyMaxWidthPx = with(LocalDensity.current) {
            (maxWidth * MESSAGE_WIDTH_FRACTION).toPx().roundToInt()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth(MESSAGE_WIDTH_FRACTION)
                .align(if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart),
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
        ) {
            if (showSender && !message.isMine && message.senderName.isNotBlank()) {
                LightText(
                    text = message.senderName,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
            // Feedback pass: only the first message of a group carries the time —
            // consecutive same-sender messages within 15 minutes are combined
            // visually, while the sender/alignment stays on every message.
            // An IN-FLIGHT row shows "SENDING" even inside a group — the group
            // would otherwise hide the slot entirely, and the just-sent
            // message must still be visibly pending (feedback 2026-08-17).
            // When the server confirms it, the served page swaps in the real
            // row: grouped → it merges under the shared timestamp; otherwise
            // it carries its own. A failed send shows its time, not SENDING.
            val inFlight = message.id.startsWith(LOCAL_ROW_PREFIX)
            val failed = message.sendStatus?.startsWith("FAIL_") == true
            if (showTime || (inFlight && !failed)) {
                LightText(
                    text = if (inFlight && !failed) {
                        "SENDING"
                    } else {
                        formatMessageTime(message.timestampMs)
                    },
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (message.contentType == "image") {
                ImageMessageContent(message, mediaBytes, allowMobile, onEnsureMedia, onOpenImage)
            } else if (message.contentType == "audio") {
                AudioMessageContent(
                    message = message,
                    playing = playing,
                    playingPositionMs = playingPositionMs,
                    playingPositionAtMs = playingPositionAtMs,
                    error = voiceError?.takeIf { it.first == message.id }?.second,
                    onTogglePlay = { onPlayVoiceNote(message.id) },
                )
            } else {
                if (message.isMine) {
                    // Outgoing: block sized to the first line so the top line's
                    // last word always touches the right edge (see
                    // [OutgoingBodyText]).
                    OutgoingBodyText(message.body, bodyMaxWidthPx)
                } else {
                    LightText(
                        text = message.body,
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            // Phase 14: reactions, as a quiet tag under the message (same
            // grammar as the "! not delivered" marker). Each entry reads
            // "Name reacted with ❤️" (or "You reacted with …" for own) —
            // feedback 2026-08-14.
            if (message.reactions.isNotEmpty()) {
                LightText(
                    text = message.reactions.joinToString(" · "),
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            // Beeper reports failed deliveries with a com.beeper.message_send_status
            // event (Phase 10) — a quiet "!" marker beats a silent stall. The
            // thread poll surfaces it within seconds, no new send needed. It
            // shows on any message, old or new.
            if (message.isMine && message.sendStatus?.startsWith("FAIL_") == true) {
                LightText(
                    text = "! not delivered",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1.dp),
                )
            } else if (message.isMine && showReadStatus && showDeliveryTag) {
                // Phase 13: the seen/delivered marker (off via Settings →
                // Show read status) — only on the newest message of a 1:1
                // thread, so past messages and group chats stay quiet.
                // Only claim what is actually known (feedback 2026-08-17:
                // "seen" showed when only delivered, and fresh sends showed
                // "delivered" before any delivery evidence existed): "seen"
                // needs the other party's m.read receipt (or a Beeper READ
                // status); "delivered" needs a Beeper status that means
                // delivered. A message with no status event yet (still in
                // flight, or no bridge report) gets no tag.
                val tag = when {
                    message.read || message.sendStatus == "READ" -> "seen"
                    message.sendStatus == "DELIVERED" -> "delivered"
                    else -> null
                }
                if (tag != null) {
                    LightText(
                        text = tag,
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
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
) {
    // The toggle is part of the key: flipping "Mobile data downloads" (or
    // moving off cellular) re-attempts rows that were skipped as Wi-Fi-only.
    LaunchedEffect(message.id, allowMobile) { onEnsureMedia(message.id, allowMobile) }
    val bytes = mediaBytes[message.id]
    if (bytes == null) {
        // Still loading, or the media can't be fetched (e.g. still-encrypted):
        // fall back to the row text ("[Photo]" or the file name).
        LightText(
            text = message.body,
            variant = LightTextVariant.Paragraph,
            lighten = true,
            modifier = Modifier.padding(top = 1.dp),
        )
        return
    }
    val bitmap = remember(bytes, message.id) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    if (bitmap == null) {
        LightText(
            text = message.body,
            variant = LightTextVariant.Paragraph,
            lighten = true,
            modifier = Modifier.padding(top = 1.dp),
        )
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = message.body,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = MAX_IMAGE_HEIGHT_DP)
            .padding(top = 1.dp)
            .lightClickable(onClick = { onOpenImage(bytes) }),
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
 *  the companion (Phase 14). The playing row shows its state via the poll's
 *  `audioPlayingEventId`, so the highlight survives message-list refreshes.
 *  A fetch/playback failure ([error]) shows briefly under the label instead of
 *  a silent no-op (feedback 2026-08-19). */
@Composable
private fun AudioMessageContent(
    message: LightServiceMethod.GetMessages.Message,
    playing: Boolean,
    playingPositionMs: Long?,
    playingPositionAtMs: Long,
    error: String?,
    onTogglePlay: () -> Unit,
) {
    // While playing, tick every 500 ms so the interpolated position counter
    // (sampled position + time since the last poll) keeps counting up.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(playing) {
        while (playing) {
            delay(500)
            tick++
        }
    }
    val durationMs = message.durationMs
    val label = when {
        playing -> {
            val base = playingPositionMs ?: 0L
            val pos = base + (android.os.SystemClock.elapsedRealtime() - playingPositionAtMs)
            // Just the running position while playing (the row already showed
            // its length when idle).
            formatDuration(durationMs?.let { pos.coerceAtMost(it) } ?: pos)
        }
        durationMs != null -> formatDuration(durationMs)
        else -> message.body
    }
    Row(
        modifier = Modifier
            .lightClickable(onClick = onTogglePlay)
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
            lighten = playing,
            modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
        )
    }
    // A failed fetch/play is a quiet one-line "couldn't play" under the row,
    // not a silent no-op (feedback 2026-08-19).
    if (error != null) {
        LightText(
            text = "Couldn't play — $error",
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** m:ss for a voice-note length/position. */
private fun formatDuration(ms: Long): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    return "${totalSecs / 60}:${(totalSecs % 60).toString().padStart(2, '0')}"
}

/** Cap for the message block — long text never spans the full row width.
 *  The far-side buffer (the empty band on the message's outer side) is half
 *  of what it was: 25 % → 12.5 % (feedback 2026-08-17). Incoming text fills
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
        lighten = true,
        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 24.dp),
    )
}
