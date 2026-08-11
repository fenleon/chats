package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ComposerViewModel(
    private val roomId: String,
) : LightViewModel<Boolean>() {

    val busy = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Boolean>) {
        super.onScreenShow(screen)
        // The composer is the only place the tool types; announce it for the
        // whole time the screen is up, until the message is sent or dismissed.
        viewModelScope.launch { ChatClient.setTyping(roomId, true) }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Boolean>) {
        super.onScreenHide(screen)
        viewModelScope.launch { ChatClient.setTyping(roomId, false) }
    }

    /** Sends the draft; pops back with `true` on success, stays on failure so
     *  the text survives for a retry. */
    fun send(text: CharSequence, screen: SimpleLightScreen<Boolean>) {
        val body = text.toString().trim()
        if (body.isEmpty() || busy.value) return
        viewModelScope.launch {
            busy.value = true
            val ok = ChatClient.sendMessage(roomId, body)
            ChatClient.setTyping(roomId, false)
            busy.value = false
            if (ok) screen.goBack(true)
        }
    }
}

class ComposerScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
    private val roomName: String,
) : LightScreen<Boolean, ComposerViewModel>(sealedActivity) {

    override val viewModelClass: Class<ComposerViewModel>
        get() = ComposerViewModel::class.java

    override fun createViewModel(): ComposerViewModel = ComposerViewModel(roomId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()
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
                singleLine = true,
            )
        }
    }
}
