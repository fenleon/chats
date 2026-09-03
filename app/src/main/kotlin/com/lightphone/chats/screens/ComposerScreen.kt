package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Unsent composer drafts, keyed by room id (feedback 2026-08-22): leaving the
 * composer mid-draft restores the text on return — thread → list → thread —
 * until it's sent or cleared. Process-scoped; a restart starts empty.
 */
internal val composerDrafts = mutableMapOf<String, String>()

/**
 * What the thread needs to show the sent message immediately (optimistic
 * echo): the text, the timeline event id once the homeserver acked (a
 * "local-…" fallback until then), and a timestamp.
 */
data class ComposerResult(
    val body: String,
    val id: String,
    val timestampMs: Long,
)

class ComposerViewModel(
    private val roomId: String,
    /** When set (Phase C, 2026-09-03), the composer EDITS this message:
     *  SEND routes to [ChatClient.editMessage] and pops back with the edit
     *  result instead of sending a new message. */
    private val editTarget: LightServiceMethod.GetMessages.Message? = null,
) : LightViewModel<ComposerResult>() {

    val busy = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<ComposerResult>) {
        super.onScreenShow(screen)
        // The composer is the only place the tool types; announce it for the
        // whole time the screen is up, until the message is sent or dismissed.
        // An edit is not typing — no indicator for the contact.
        if (editTarget == null) {
            viewModelScope.launch { ChatClient.setTyping(roomId, true) }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<ComposerResult>) {
        super.onScreenHide(screen)
        if (editTarget == null) {
            viewModelScope.launch { ChatClient.setTyping(roomId, false) }
        }
    }

    /** Sends the draft (or applies the edit); pops back with a
     *  [ComposerResult] on success, stays on failure so the text survives for
     *  a retry. */
    fun send(text: CharSequence, screen: SimpleLightScreen<ComposerResult>) {
        val body = text.toString().trim()
        if (body.isEmpty() || busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                if (editTarget != null) {
                    val ok = ChatClient.editMessage(roomId, editTarget.id, body)
                    if (ok) {
                        // Same event id — the thread's optimistic edit overlay
                        // keys off it.
                        screen.goBack(ComposerResult(body, editTarget.id, editTarget.timestampMs))
                    }
                } else {
                    val response = ChatClient.sendMessage(roomId, body)
                    if (response != null) {
                        screen.goBack(
                            ComposerResult(
                                body = body,
                                id = response.eventId ?: "local-${response.transactionId}",
                                timestampMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            } finally {
                // A failed/exception RPC must not leave the busy flag set —
                // every later press would silently return and Send would look
                // dead (feedback 2026-08-17: "send needs multiple presses").
                ChatClient.setTyping(roomId, false)
                busy.value = false
            }
        }
    }
}

class ComposerScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
    private val roomName: String,
    /** When set, the composer prefills this message's body and SEND edits it
     *  (Phase C, 2026-09-03, opened from the thread's context window). */
    private val editTarget: LightServiceMethod.GetMessages.Message? = null,
) : LightScreen<ComposerResult, ComposerViewModel>(sealedActivity) {

    override val viewModelClass: Class<ComposerViewModel>
        get() = ComposerViewModel::class.java

    override fun createViewModel(): ComposerViewModel = ComposerViewModel(roomId, editTarget)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        // The LP3 keyboard's mic key is handled inside the closed light-keyboard
        // library (it needs a speech-recognition service, which LightOS doesn't
        // ship) — it does nothing here. Voice notes have their own button on
        // the thread (Phase 14), so hide the dead key instead of showing a
        // control that can't act. The return key stays (feedback 2026-08-20:
        // it was removed at the send-round, then the user wanted it back) —
        // it inserts a newline rather than sending (submitOnReturn = false):
        // messages may span lines, and SEND lives in the top bar.
        val keyboardOptionsFlow = remember {
            MutableStateFlow(defaultKeyboardOptions().copy(displayVoice = false, displayReturn = true))
        }
        // Restore the room's unsent draft (feedback 2026-08-22); the composer
        // saves every change back to [composerDrafts] so leaving mid-draft
        // keeps the text until it's sent or cleared. An edit (Phase C)
        // prefills the row's body instead and never touches the draft — a
        // cancelled edit must not leak into the next normal composer.
        val textState = rememberTextFieldState(editTarget?.body ?: composerDrafts[roomId] ?: "")
        LaunchedEffect(textState.text) {
            if (editTarget != null) return@LaunchedEffect
            val text = textState.text.toString()
            if (text.isEmpty()) composerDrafts.remove(roomId) else composerDrafts[roomId] = text
        }

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                LightTextInputEditor(
                    // An edit announces itself in the title slot (the room
                    // name's place); back (below) cancels it.
                    title = if (editTarget != null) "Editing Message" else roomName,
                    state = textState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { viewModel.send(it, this@ComposerScreen) },
                    onBack = { goBack() },
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                    submitLabel = "Send",
                    submitIcon = LightIcons.SEND,
                    // Notes-style entry (feedback pass): small wrapping text
                    // anchored at the bottom, growing upward, keyboard flush at
                    // the bottom; the return key makes newlines, not sends
                    // (feedback 2026-08-20). Send lives in the top-right bar.
                    // The keyboard opens in caps mode — a new message starts
                    // with a capital letter like the native composer
                    // (feedback 2026-08-20: "compose … ensure the keyboard is
                    // capitalised mode").
                    singleLine = false,
                    submitOnReturn = false,
                    bottomAligned = true,
                    submitInTopBar = true,
                    topBarSubmitIcon = LightIcons.SEND,
                    initialCaps = true,
                )
                // Clear-draft X, bottom-right corner of the screen (feedback
                // 2026-08-21: the old 218 dp-above-keyboard position overlapped
                // the draft's last line; the first bottom-right attempt
                // overlapped the keyboard's bottom row). The composer keyboard
                // (submitInTopBar) reserves the 5-gu bottom-bar row below the
                // keys, so the X sits in that row at the far right, vertically
                // centered like a native bottom-bar icon (LP3-verified
                // 2026-08-22: the doubled 1-gu outer + 1-gu inner padding put
                // the icon 2 gu off the bottom, leaving a big buffer under it;
                // the inner padding is horizontal-only now, so the icon lands
                // at y 1120-1200 — the native bar-icon band). Always visible
                // while the composer is open (feedback 2026-08-21: the X was
                // gated on text, so it appeared only after typing); with an
                // empty draft it's a harmless no-op.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // Keeps the X above the keyboard when the system IME
                        // is in use (no-op with the embedded keyboard — the
                        // IME never shows, so imePadding is 0).
                        .imePadding()
                        .padding(end = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp())
                        .lightClickable {
                            textState.edit { replace(0, length, "") }
                        }
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightIcon(
                        icon = LightIcons.CLOSE,
                        // Same size as the bottom-row icons (2 gu — the SDK's
                        // bar-button icon size; feedback 2026-08-21: was 1.5f).
                        size = 2f,
                        contentDescription = "Clear draft",
                    )
                }
            }
        }
    }
}
