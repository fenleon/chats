package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.formatRelativeTimestamp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Grow the list slice when the last visible row is within this many of the end. */
private const val REVEAL_THRESHOLD = 4

/**
 * Launch-intent extra carrying the room a notification tap should open
 * (matches the companion's ChatNotifier.EXTRA_NOTIFY_ROOM; the app cannot
 * reference the server's class).
 */
private const val EXTRA_NOTIFY_ROOM = "chats.notifyRoomId"

class ChatListViewModel : LightViewModel<Unit>() {

    val rooms = MutableStateFlow<List<LightServiceMethod.GetRooms.Room>>(emptyList())
    val loading = MutableStateFlow(true)
    val account = MutableStateFlow<LightServiceMethod.GetAccountState.Response?>(null)
    val connection = MutableStateFlow<LightServiceMethod.GetConnectionState.Response?>(null)
    /**
     * How many rooms the list currently shows. Starts small (the user only ever
     * reads the newest few) and grows as the list is scrolled to the bottom.
     */
    val visibleCount = MutableStateFlow(INITIAL_VISIBLE_COUNT)
    /**
     * Room to open after a notification tap (set from the screen's
     * notifyWillShow, which reads the launch-intent extra); consumed by the
     * view model once the room is loaded.
     */
    val openRoom = MutableStateFlow<LightServiceMethod.GetRooms.Room?>(null)
    /**
     * Room id a notification tap asked to open (from the launch-intent extra —
     * consume-once). Only a real tap carries it: returning from a thread or a
     * plain list refresh never sets it, so the list never auto-opens a room.
     */
    var pendingNotifyRoomId: String? = null
    /** Unread-only filter (bottom-bar toggle, Phase 7): shows only rooms with unreadCount > 0. */
    val unreadOnly = MutableStateFlow(false)
    /** Selected bridged-network label (Phase 7); null = all networks. */
    val networkFilter = MutableStateFlow<String?>(null)

    private var refreshJob: Job? = null
    private var pollJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // No thread is on screen here; let the companion notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        refresh()
        startPolling()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        stopPolling()
    }

    override fun onAppPause() {
        super.onAppPause()
        stopPolling()
    }

    /**
     * Re-fetches the list while it stays visible (the companion's room-list
     * cache fills in placeholders + updates live rooms in the background, so a
     * periodic quiet refresh keeps the list current without user action).
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                refresh(quiet = true)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh(quiet: Boolean = false) {
        // A new refresh supersedes an in-flight one (show + poll can overlap).
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!quiet) loading.value = true
            var account = ChatClient.accountState()
            var result = ChatClient.getRooms()
            var connection = ChatClient.connectionState()
            // A cold start can bind before the companion's service is ready (or
            // before it has restored a stored session), so the first calls fail
            // (null account / empty rooms / logged-out-while-restoring). Retry
            // until the account answers, rooms arrive, or it's a genuine
            // logged-out account (no stored session) — don't flash a wrong
            // empty state while the session is still restoring.
            repeat(REFRESH_RETRIES) {
                // Settled = really logged in with rooms, or a genuine
                // logged-out account (no stored session). Anything else (null
                // account, logged-out-while-restoring, or logged-in but the
                // cold room cache hasn't warmed — the first getRooms can return
                // empty) keeps refetching.
                val settled = account != null && (
                    (account.loggedIn == true && result.isNotEmpty()) ||
                        (account.userId == null && result.isEmpty()) ||
                        // An expired session is a settled state, not a transient
                        // restore — don't keep refetching for 10s.
                        (account.loggedIn == false && account.userId != null && connection?.state == "offline")
                    )
                if (settled) return@repeat
                delay(REFRESH_RETRY_DELAY_MS)
                account = ChatClient.accountState()
                connection = ChatClient.connectionState()
                if (account?.loggedIn == true) result = ChatClient.getRooms()
            }
            this@ChatListViewModel.account.value = account
            rooms.value = result
            this@ChatListViewModel.connection.value = connection
            // A notification tap asked for a thread; open it once its room is
            // loaded (a cold start may have to wait for the first room-list pass).
            consumeNotifyRoom(result)
            if (!quiet) loading.value = false
        }
    }

    /**
     * Opens the thread a notification tap requested, when the room is loaded.
     * Waits for a settled list (rooms arrived, or a genuine logged-out state)
     * so a cold start doesn't drop the request while rooms are still restoring.
     */
    fun consumeNotifyRoom(rooms: List<LightServiceMethod.GetRooms.Room>) {
        val pending = pendingNotifyRoomId ?: return
        val settled = account.value?.let {
            (it.loggedIn == true && rooms.isNotEmpty()) ||
                (it.userId == null) ||
                (it.loggedIn == false && it.userId != null && connection.value?.state == "offline")
        } ?: false
        if (!settled) return
        pendingNotifyRoomId = null
        openRoom.value = rooms.firstOrNull { it.id == pending }
    }

    /** Grows the visible slice as the user scrolls toward the list's end. */
    fun showMore() {
        visibleCount.value = (visibleCount.value + REVEAL_STEP)
            .coerceAtMost(rooms.value.size.coerceAtLeast(INITIAL_VISIBLE_COUNT))
    }

    private companion object {
        // Wide enough to outlast a cold session restore (1284 Beeper rooms),
        // which can take several seconds after a fresh boot/reinstall.
        const val REFRESH_RETRIES = 10
        const val REFRESH_RETRY_DELAY_MS = 1_000L
        const val POLL_INTERVAL_MS = 5_000L
        const val INITIAL_VISIBLE_COUNT = 20
        const val REVEAL_STEP = 20
    }
}

@InitialScreen
class ChatListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ChatListViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChatListViewModel>
        get() = ChatListViewModel::class.java

    override fun createViewModel(): ChatListViewModel = ChatListViewModel()

    /** For consume-once launch-extras (notification-tap handoff). */
    private val activityRef = sealedActivity

    override fun willShow() {
        super.willShow()
        // A notification tap arrives with its room in the launch intent
        // (consume-once). Only a real tap carries the extra — returning from a
        // thread and plain list refreshes never set it, so the list never
        // auto-opens a room on its own.
        activityRef.takeLaunchExtra(EXTRA_NOTIFY_ROOM)?.let {
            viewModel.pendingNotifyRoomId = it
        }
    }

    @Composable
    override fun Content() {
        val rooms by viewModel.rooms.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val account by viewModel.account.collectAsState()
        val connection by viewModel.connection.collectAsState()
        val visibleCount by viewModel.visibleCount.collectAsState()
        val pendingRoom by viewModel.openRoom.collectAsState()
        val unreadOnly by viewModel.unreadOnly.collectAsState()
        val networkFilter by viewModel.networkFilter.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val listState = rememberLazyListState()

        // Phase 7 filters: the unread toggle and the network selector both
        // narrow the list; the full room set stays in the ViewModel.
        val filteredRooms = remember(rooms, unreadOnly, networkFilter) {
            rooms.filter { room ->
                (networkFilter == null || room.network == networkFilter) &&
                    (!unreadOnly || room.unreadCount > 0)
            }
        }
        // Network labels for the Networks panel, from the rooms the companion
        // tagged (Beeper account spaces only — group chats are excluded
        // server-side).
        val networks = remember(rooms) {
            rooms.mapNotNull { it.network }.distinct().sorted()
        }

        // A notification tap lands here; open the pending room's thread.
        LaunchedEffect(pendingRoom) {
            val room = pendingRoom ?: return@LaunchedEffect
            viewModel.openRoom.value = null
            openThread(room)
        }

        // Reveal more rooms when the user scrolls near the end of the current
        // slice — the list grows instead of showing all 1284 at once.
        LaunchedEffect(listState, visibleCount) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            }.distinctUntilChanged().collect { lastVisible ->
                if (lastVisible >= visibleCount - REVEAL_THRESHOLD) {
                    viewModel.showMore()
                }
            }
        }

        // Feedback pass: switching the filter (unread toggle / account
        // selection) returns the list to the top — the user expects the
        // newest conversations, not a stale scroll position from the previous
        // filter. requestScrollToItem is safe before the new list is composed
        // (e.g. an empty unread view); the scroll lands when the list appears.
        LaunchedEffect(unreadOnly, networkFilter) {
            listState.requestScrollToItem(0)
        }

        // Feedback pass: a new-message bump reorders the list; when the user
        // was at (or within a row of) the top, keep the newest conversation
        // pinned at index 0 — LazyColumn anchors by key, so the room that slid
        // down stays in view and the bumped room hides just above the
        // viewport without this (feedback 2026-08-17: "the room bumps to the
        // top, but the panel requires scrolling up to see it").
        LaunchedEffect(filteredRooms.firstOrNull()?.id) {
            if (listState.firstVisibleItemIndex <= 1) {
                listState.requestScrollToItem(0)
            }
        }

        // Offline: the list still shows cached rooms, with a status line on top
        // ("Can't reach server"); an expired session points at Settings instead.
        val offlineText = connection?.takeIf { it.state == "offline" }?.let { state ->
            if (state.detail?.startsWith("session expired") == true) {
                "Session expired — sign in again"
            } else {
                "Can't reach server"
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // Feedback pass: with a network filter active the list gets a
                // context top bar naming the network ("WhatsApp"); on "All"
                // it stays a bare list home (the 2-gu bar). A filtered list is
                // a standard top-bar screen — the top bar REPLACES the 2-gu
                // bar, so the header height matches every other titled screen
                // (feedback 2026-08-19). The null left/right slots render as
                // spacers, so the title stays centered.
                val activeAccount = networkFilter
                if (activeAccount != null) {
                    LightTopBar(
                        center = LightTopBarCenter.Text(activeAccount),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2f.gridUnitsAsDp()),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        offlineText?.let { OfflineBanner(it) }
                        Box(modifier = Modifier.weight(1f)) {
                            when {
                                // First login / restored session: the initial sync
                                // pulls the whole account (all rooms + history) and
                                // can take minutes on a large bridged account —
                                // say so instead of a blank "Loading…", and never
                                // flash "No conversations" while it's still running
                                // (the retry budget can exhaust before rooms land).
                                loading && rooms.isEmpty() -> StatusText(
                                    if (connection?.state == "connecting") {
                                        "Downloading your chat history…"
                                    } else {
                                        "Loading…"
                                    },
                                )
                                filteredRooms.isNotEmpty() -> LightLazyScrollView(
                                    // Rows are ~70dp; a uniform estimate keeps the lazy
                                    // scrollbar sane (the SDK computes it per-item).
                                    uniformItemHeightGridUnits = 4.6f,
                                    listState = listState,
                                ) {
                                    items(filteredRooms.take(visibleCount), key = { it.id }) { room ->
                                        RoomRow(
                                            room = room,
                                            onOpen = { openThread(room) },
                                        )
                                    }
                                }
                                rooms.isNotEmpty() -> StatusText("No conversations.")
                                account?.loggedIn != true -> StatusText(
                                    if (connection?.state == "offline") {
                                        "Sign in again — open Settings."
                                    } else {
                                        "No account. Open Settings to sign in with Beeper or a Matrix homeserver."
                                    },
                                )
                                connection?.state == "connecting" -> StatusText("Downloading your chat history…")
                                else -> StatusText("No conversations.")
                            }
                        }
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { openSettings() },
                            contentDescription = "Settings",
                        ),
                        // Phase 7: unread toggle — the label names the action
                        // it triggers, so it reads as the state too.
                        LightBarButton.Text(
                            text = if (unreadOnly) "ALL" else "VIEW UNREAD",
                            onClick = { viewModel.unreadOnly.value = !viewModel.unreadOnly.value },
                        ),
                        // Feedback pass: the network filter lives behind the
                        // bottom-right menu (3-dash) which opens the Networks
                        // panel; the active network shows in the context top bar.
                        LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            onClick = {
                                navigateTo(
                                    screenFactory = { AccountsScreen(it, networks, networkFilter) },
                                ) { choice ->
                                    viewModel.networkFilter.value = choice?.label
                                }
                            },
                            contentDescription = "Networks",
                        ),
                    ),
                )
            }
        }
    }

    private fun openThread(room: LightServiceMethod.GetRooms.Room) {
        navigateTo(screenFactory = { ThreadScreen(it, room) })
    }

    private fun openSettings() {
        navigateTo(screenFactory = { SettingsScreen(it) })
    }
}

@Composable
private fun RoomRow(
    room: LightServiceMethod.GetRooms.Room,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            // Left matches the built-in messaging margin; the right leaves the
            // time clear of the scrollbar.
            .padding(start = 2f.gridUnitsAsDp(), end = 0.5f.gridUnitsAsDp(), top = 14.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // Centered: the right-side time lines up with the room name's
            // vertical center (feedback 2026-08-17: was top-aligned to the name).
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The unread marker is a large asterisk inline at the start of the
            // name, not a reserved column of its own (feedback 2026-08-17).
            Column(modifier = Modifier.weight(1f)) {
                LightText(
                    text = if (room.unreadCount > 0) "* ${room.name}" else room.name,
                    // Native Messages list names are ~80 px ink — the Heading
                    // variant (feedback 2026-08-17).
                    variant = LightTextVariant.Heading,
                    // One line, like the built-in titles — no wrapping.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The latest-message time sits at the row's right, on the name
            // line like the built-in list, with the short hand format
            // (feedback 2026-08-17: back on the right after the under-name
            // Detail date; the unread count was removed at the same time).
            LightText(
                text = formatRelativeTimestamp(room.lastTimestampMs),
                variant = LightTextVariant.Fine,
                lighten = true,
            )
        }
    }
}

@Composable
private fun OfflineBanner(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 10.dp),
    )
}

@Composable
private fun StatusText(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 24.dp),
    )
}
