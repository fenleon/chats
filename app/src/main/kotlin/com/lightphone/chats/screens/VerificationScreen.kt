package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
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
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Interactive (SAS/emoji) device verification: proves this device to the
 * account's other device (e.g. the Beeper app), which unlocks encrypted
 * message keys. The state machine runs in the companion (Trixnity); this
 * screen polls it over the binder and forwards the user's choices.
 */
class VerificationViewModel : LightViewModel<Unit>() {

    val state = MutableStateFlow<LightServiceMethod.GetVerificationState.Response?>(null)
    val e2ee = MutableStateFlow<LightServiceMethod.GetE2eeState.Response?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Poll while the screen is up; the companion's state machine updates as
        // the other device answers (and the recovery key updates e2ee state).
        viewModelScope.launch {
            while (true) {
                state.value = ChatClient.verificationState()
                e2ee.value = ChatClient.e2eeState()
                delay(POLL_MS)
            }
        }
    }

    fun start() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val response = ChatClient.startDeviceVerification()
            busy.value = false
            if (response?.started != true) {
                error.value = response?.error ?: "couldn't start verification"
            } else {
                state.value = ChatClient.verificationState()
            }
        }
    }

    /** Non-interactive verification with the account's recovery key. */
    fun recover(recoveryKey: String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val failure = ChatClient.recoverWithKey(recoveryKey)
            busy.value = false
            if (failure != null) {
                error.value = failure
            } else {
                e2ee.value = ChatClient.e2eeState()
                state.value = ChatClient.verificationState()
            }
        }
    }

    fun act(action: String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val failure = ChatClient.verifyAction(action)
            busy.value = false
            if (failure != null) error.value = failure
            state.value = ChatClient.verificationState()
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
    }
}

class VerificationScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, VerificationViewModel>(sealedActivity) {

    override val viewModelClass: Class<VerificationViewModel>
        get() = VerificationViewModel::class.java

    override fun createViewModel(): VerificationViewModel = VerificationViewModel()

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val e2ee by viewModel.e2ee.collectAsState()
        val error by viewModel.error.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

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
                        contentDescription = "Back to settings",
                    ),
                    center = LightTopBarCenter.Text("Verify"),
                )
                // The scroll body sits in a weighted Box (like Settings) so the
                // greedy scroll view can't squeeze the bottom bar to zero height.
                Box(modifier = Modifier.weight(1f)) {
                    LightScrollView {
                        Column(
                            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                        ) {
                            when {
                            // The recovery key verifies without a state machine —
                            // the e2ee flag flipping to verified is the signal.
                            e2ee?.verified == true -> {
                                // The bottom bar carries DONE; no duplicate row here.
                                Body("Verified. Encrypted messages can now decrypt — open a conversation to load them.")
                            }

                            else -> when (state?.state) {
                                "none" -> {
                                    Body("This device isn't verified yet — encrypted messages stay locked. Verify with another Beeper device or your recovery key.")
                                    ActionRow(
                                        text = if (busy) "…" else "START VERIFICATION",
                                        enabled = !busy,
                                        onClick = viewModel::start,
                                    )
                                }

                                "waiting" -> {
                                    Body("Waiting for your other device… Open the Beeper app and accept the verification there.")
                                    ActionRow("CANCEL", enabled = !busy) { viewModel.act("cancel") }
                                }

                                "accept" -> {
                                    Body("Your other device wants to verify. Accept?")
                                    ActionRow("ACCEPT", enabled = !busy) { viewModel.act("accept") }
                                    ActionRow("CANCEL", enabled = !busy) { viewModel.act("cancel") }
                                }

                                "start" -> {
                                    Body("Both devices are ready. Start the verification.")
                                    ActionRow("START", enabled = !busy) { viewModel.act("start") }
                                    ActionRow("CANCEL", enabled = !busy) { viewModel.act("cancel") }
                                }

                                "compare" -> {
                                    Body("Compare the emojis on this device with the ones on your other device.")
                                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                                    // All seven on one line so the SAS set reads
                                    // as a single comparison (Heading keeps them
                                    // small enough to fit).
                                    state?.emoji.orEmpty().chunked(7).forEach { rowEmojis ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                        ) {
                                            rowEmojis.forEach { emoji ->
                                                LightText(text = emoji, variant = LightTextVariant.Heading)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                                    ActionRow("MATCH", enabled = !busy) { viewModel.act("match") }
                                    ActionRow("DON'T MATCH", enabled = !busy) { viewModel.act("no_match") }
                                    ActionRow("CANCEL", enabled = !busy) { viewModel.act("cancel") }
                                }

                                "done" -> {
                                    // The bottom bar carries DONE; no duplicate row here.
                                    Body("Verified. Encrypted messages can now decrypt — open a conversation to load them.")
                                }

                                "cancelled" -> {
                                    Body("Verification was cancelled or failed.")
                                    ActionRow("START OVER", enabled = !busy) { viewModel.act("reset") }
                                }

                                "error" -> {
                                    Body(state?.detail ?: "Verification failed.")
                                    ActionRow("START OVER", enabled = !busy) { viewModel.act("reset") }
                                }

                                else -> Body("Checking…")
                            }
                        }
                        error?.let { message ->
                            LightText(
                                text = message,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
                }
                // The bottom bar always stays visible with the screen's action
                // (design rule): the recovery key is the dependable unlock while
                // unverified; DONE once verified. Hidden during an active
                // verification — the flow's actions live in the body.
                val inVerification = state?.state in setOf("waiting", "accept", "start", "compare")
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        null,
                        when {
                            e2ee?.verified == true -> LightBarButton.Text(
                                text = "DONE",
                                onClick = { goBack() },
                            )
                            inVerification -> null
                            else -> LightBarButton.Text(
                                text = if (busy) "…" else "USE RECOVERY KEY",
                                onClick = if (busy) null else ({ openRecoveryEditor() }),
                            )
                        },
                        null,
                    ),
                )
            }
        }
    }

    private fun openRecoveryEditor() {
        navigateTo(screenFactory = {
            RecoveryKeyEditorScreen(it)
        }) { key ->
            if (key != null && key.isNotBlank()) viewModel.recover(key)
        }
    }
}

@Composable
private fun Body(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
private fun ActionRow(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            lighten = true,
        )
    }
}
