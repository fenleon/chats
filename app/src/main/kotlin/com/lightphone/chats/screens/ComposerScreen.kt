package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
) : LightViewModel<ComposerResult>() {

    val busy = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<ComposerResult>) {
        super.onScreenShow(screen)
        // The composer is the only place the tool types; announce it for the
        // whole time the screen is up, until the message is sent or dismissed.
        viewModelScope.launch { ChatClient.setTyping(roomId, true) }
    }

    override fun onScreenHide(screen: SimpleLightScreen<ComposerResult>) {
        super.onScreenHide(screen)
        viewModelScope.launch { ChatClient.setTyping(roomId, false) }
    }

    /** Sends the draft; pops back with a [ComposerResult] on success, stays on
     *  failure so the text survives for a retry. */
    fun send(text: CharSequence, screen: SimpleLightScreen<ComposerResult>) {
        val body = text.toString().trim()
        if (body.isEmpty() || busy.value) return
        viewModelScope.launch {
            busy.value = true
            val response = ChatClient.sendMessage(roomId, body)
            ChatClient.setTyping(roomId, false)
            busy.value = false
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
    }
}

class ComposerScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
    private val roomName: String,
) : LightScreen<ComposerResult, ComposerViewModel>(sealedActivity) {

    override val viewModelClass: Class<ComposerViewModel>
        get() = ComposerViewModel::class.java

    override fun createViewModel(): ComposerViewModel = ComposerViewModel(roomId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        // The LP3 keyboard's mic key is handled inside the closed light-keyboard
        // library (it needs a speech-recognition service, which LightOS doesn't
        // ship) — it does nothing here. Voice notes have their own button on
        // the thread (Phase 14), so hide the dead key instead of showing a
        // control that can't act.
        val keyboardOptionsFlow = remember {
            MutableStateFlow(defaultKeyboardOptions().copy(displayVoice = false))
        }
        val textState = rememberTextFieldState("")

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = roomName,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { viewModel.send(it, this@ComposerScreen) },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "Send",
                submitIcon = LightIcons.SEND,
                // Notes-style entry (feedback pass): small wrapping text
                // anchored at the bottom, growing upward, keyboard flush at
                // the bottom; return still sends (multi-line display, no
                // newlines in the message). Send lives in the top-right bar.
                singleLine = false,
                submitOnReturn = true,
                bottomAligned = true,
                submitInTopBar = true,
                topBarSubmitIcon = LightIcons.SEND,
            )
        }
    }
}
