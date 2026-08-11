package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.formatTimestamp
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ChatListViewModel : LightViewModel<Unit>() {

    val rooms = MutableStateFlow<List<LightServiceMethod.GetRooms.Room>>(emptyList())
    val loading = MutableStateFlow(true)
    val account = MutableStateFlow<LightServiceMethod.GetAccountState.Response?>(null)
    /**
     * Room to open after a notification tap (set from the companion's
     * pending-notify-room handoff); consumed by the screen.
     */
    val openRoom = MutableStateFlow<LightServiceMethod.GetRooms.Room?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // No thread is on screen here; let the companion notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            var account = ChatClient.accountState()
            var result = ChatClient.getRooms()
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
                        (account.userId == null && result.isEmpty())
                    )
                if (settled) return@repeat
                delay(REFRESH_RETRY_DELAY_MS)
                account = ChatClient.accountState()
                if (account?.loggedIn == true) result = ChatClient.getRooms()
            }
            this@ChatListViewModel.account.value = account
            rooms.value = result
            // A notification tap leaves a pending room with the companion; open
            // its thread when it matches a room on the list.
            val notifyRoomId = ChatClient.takeNotifyRoom()
            if (notifyRoomId != null) {
                openRoom.value = result.firstOrNull { it.id == notifyRoomId }
            }
            loading.value = false
        }
    }

    private companion object {
        // Wide enough to outlast a cold session restore (1284 Beeper rooms),
        // which can take several seconds after a fresh boot/reinstall.
        const val REFRESH_RETRIES = 10
        const val REFRESH_RETRY_DELAY_MS = 1_000L
    }
}

@InitialScreen
class ChatListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ChatListViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChatListViewModel>
        get() = ChatListViewModel::class.java

    override fun createViewModel(): ChatListViewModel = ChatListViewModel()

    @Composable
    override fun Content() {
        val rooms by viewModel.rooms.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val account by viewModel.account.collectAsState()
        val pendingRoom by viewModel.openRoom.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        // A notification tap lands here; open the pending room's thread.
        LaunchedEffect(pendingRoom) {
            val room = pendingRoom ?: return@LaunchedEffect
            viewModel.openRoom.value = null
            openThread(room)
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading && rooms.isEmpty() -> StatusText("Loading…")
                        rooms.isNotEmpty() -> LightLazyScrollView(
                            // Rows are ~70dp; a uniform estimate keeps the lazy
                            // scrollbar sane (the SDK computes it per-item).
                            uniformItemHeightGridUnits = 4.6f,
                        ) {
                            items(rooms, key = { it.id }) { room ->
                                RoomRow(
                                    room = room,
                                    onOpen = { openThread(room) },
                                )
                            }
                        }
                        account?.loggedIn != true -> StatusText(
                            "No account. Open Settings to sign in with Beeper or a Matrix homeserver.",
                        )
                        else -> StatusText("No conversations.")
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        null,
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { openSettings() },
                            contentDescription = "Settings",
                        ),
                        null,
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
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(
                    text = room.name,
                    variant = LightTextVariant.Copy,
                )
                if (room.lastMessage.isNotBlank()) {
                    LightText(
                        text = room.lastMessage,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                LightText(
                    text = formatTimestamp(room.lastTimestampMs),
                    variant = LightTextVariant.Fine,
                    lighten = true,
                )
                if (room.unreadCount > 0) {
                    LightText(
                        text = room.unreadCount.toString(),
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier.padding(24.dp),
    )
}
