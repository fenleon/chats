package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.formatTimestamp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ThreadViewModel(
    private val room: LightServiceMethod.GetRooms.Room,
) : LightViewModel<Unit>() {

    /** Oldest-first page of messages; older pages are prepended by [loadOlder]. */
    val messages = MutableStateFlow<List<LightServiceMethod.GetMessages.Message>>(emptyList())
    val loading = MutableStateFlow(true)
    val loadingMore = MutableStateFlow(false)
    val hasMore = MutableStateFlow(false)
    /** True until the newest page has been shown scrolled to the bottom. */
    val jumpToBottom = MutableStateFlow(true)
    /** Whether this device is E2EE-verified (false = encrypted rooms can't decrypt yet). */
    val e2eeVerified = MutableStateFlow<Boolean?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // While this room is on screen the companion suppresses its
        // new-message notifications.
        viewModelScope.launch { ChatClient.setActiveRoom(room.id) }
        viewModelScope.launch {
            e2eeVerified.value = ChatClient.e2eeState()?.verified
        }
        loadNewest()
    }

    override fun onAppPause() {
        super.onAppPause()
        // The tool is no longer visible (standby/another app); messages in
        // this room may notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
    }

    fun loadNewest() {
        viewModelScope.launch {
            loading.value = true
            val page = ChatClient.getMessages(room.id, null, PAGE_SIZE)
            val loaded = page?.messages.orEmpty()
            // A failed reload (e.g. brief disconnect) must not wipe what's shown.
            if (loaded.isNotEmpty() || messages.value.isEmpty()) {
                messages.value = loaded
                hasMore.value = page?.hasMore ?: false
            }
            loading.value = false
            jumpToBottom.value = true
            // Opening the thread marks it read up to the newest event; the room
            // list's unread count drops on its next refresh.
            val markEventId = loaded.lastOrNull()?.id ?: room.lastEventId ?: return@launch
            ChatClient.markRead(room.id, markEventId)
        }
    }

    /** Prepends the page of messages older than the oldest one currently shown. */
    fun loadOlder() {
        val oldest = messages.value.firstOrNull() ?: return
        if (loadingMore.value || !hasMore.value) return
        viewModelScope.launch {
            loadingMore.value = true
            val page = ChatClient.getMessages(room.id, oldest.id, PAGE_SIZE)
            val older = page?.messages.orEmpty()
            if (older.isNotEmpty()) {
                // distinctBy guards the page boundary: if the timeline changed
                // between calls, the cursor event can appear at both edges.
                messages.value = (older + messages.value).distinctBy { it.id }
            }
            hasMore.value = page?.hasMore ?: hasMore.value
            loadingMore.value = false
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}

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
        val loadingMore by viewModel.loadingMore.collectAsState()
        val hasMore by viewModel.hasMore.collectAsState()
        val jumpToBottom by viewModel.jumpToBottom.collectAsState()
        val e2eeVerified by viewModel.e2eeVerified.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val scrollState = rememberScrollState()

        // An unverified device cannot decrypt this room's messages — say so
        // plainly instead of leaving the user staring at "[Encrypted]".
        val needsDecryptionNotice = e2eeVerified == false &&
            messages.any { it.body.startsWith("[Encrypted") }

        LightTheme(colors = themeColors) {
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
                        messages.isEmpty() -> StatusText("No messages yet.")
                        else -> LightScrollView(scrollState = scrollState) {
                            if (needsDecryptionNotice) {
                                DecryptionNotice()
                            }
                            if (hasMore) {
                                LoadEarlierRow(
                                    loadingMore = loadingMore,
                                    onLoadEarlier = viewModel::loadOlder,
                                )
                            }
                            messages.forEach { message ->
                                MessageRow(message)
                            }
                        }
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        null,
                        null,
                        LightBarButton.LightIcon(
                            icon = LightIcons.COMPOSE_MESSAGE,
                            onClick = { openComposer() },
                            contentDescription = "New message",
                        ),
                    ),
                )
            }
        }

        // Show the newest messages on open (and after sending); not on older
        // pages, which arrive while the user is reading further up.
        LaunchedEffect(jumpToBottom, messages.size) {
            if (jumpToBottom && messages.isNotEmpty()) {
                withFrameNanos { }
                scrollState.scrollTo(scrollState.maxValue)
                viewModel.jumpToBottom.value = false
            }
        }
    }

    private fun openComposer() {
        navigateTo(screenFactory = { ComposerScreen(it, room.id, room.name) }) { sent ->
            if (sent == true) viewModel.loadNewest()
        }
    }
}

@Composable
private fun DecryptionNotice() {
    LightText(
        text = "Encrypted — verify this device to read messages (Settings → Encrypted messages)",
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun LoadEarlierRow(
    loadingMore: Boolean,
    onLoadEarlier: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(
                enabled = !loadingMore,
                onClick = onLoadEarlier,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = if (loadingMore) "Loading…" else "Earlier messages",
            variant = LightTextVariant.Fine,
            lighten = true,
        )
    }
}

@Composable
private fun MessageRow(message: LightServiceMethod.GetMessages.Message) {
    // Mirror the LP3 conversation rows: a timestamp line above the content,
    // outgoing messages aligned to the right, incoming to the left.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
    ) {
        if (!message.isMine && message.senderName.isNotBlank()) {
            LightText(
                text = message.senderName,
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
        LightText(
            text = formatTimestamp(message.timestampMs),
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 2.dp),
        )
        LightText(
            text = message.body,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 2.dp),
        )
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
