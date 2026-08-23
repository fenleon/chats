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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.R
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

/** Cancelled/failed panels dismiss themselves back to the main panel. */
private const val CANCELLED_AUTO_DISMISS_MS = 4_000L

/** Shown between Continue and the other device accepting (or while waiting). */
private const val WAITING_TEXT = "Waiting for your other device to accept..."

/**
 * Interactive (SAS/emoji) device verification: proves this device to the
 * account's other device (e.g. the Beeper app), which unlocks encrypted
 * message keys. The state machine runs in the companion (Trixnity); this
 * screen polls it over the binder and forwards the user's choices.
 *
 * The flow renders as full-screen panels (feedback 2026-08-19): a local
 * confirmation panel before the request is sent, then the server's states —
 * waiting / accept / compare / cancelled — each with the X-cancel affordance
 * in the bottom bar.
 */
class VerificationViewModel : LightViewModel<Unit>() {

    val state = MutableStateFlow<LightServiceMethod.GetVerificationState.Response?>(null)
    val e2ee = MutableStateFlow<LightServiceMethod.GetE2eeState.Response?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Local pre-start confirmation panel ("send a request … Continue?"). The
     *  server stays idle ("none") until the user confirms. */
    val confirmOpen = MutableStateFlow(false)

    /** True between Continue and the server reporting a non-idle verification
     *  state — the waiting panel shows immediately, no main-panel flash
     *  (feedback 2026-08-19). */
    val starting = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Poll while the screen is up; the companion's state machine updates as
        // the other device answers (and the recovery key updates e2ee state).
        viewModelScope.launch {
            // verificationState stays at 1 s; e2eeState is throttled to every
            // ~10 s — it includes a server round-trip (network), and the
            // recovery-key/accept actions refresh it directly anyway
            // (feedback 2026-08-23).
            var tick = 0
            while (true) {
                state.value = ChatClient.verificationState()
                if (tick % E2EE_POLL_TICKS == 0) {
                    e2ee.value = ChatClient.e2eeState()
                }
                if (starting.value && state.value?.state != "none") starting.value = false
                tick++
                delay(POLL_MS)
            }
        }
    }

    fun openConfirm() {
        confirmOpen.value = true
    }

    fun dismissConfirm() {
        confirmOpen.value = false
    }

    /** Continue from the confirmation panel: kick the server-side verification. */
    fun confirmStart() {
        confirmOpen.value = false
        starting.value = true
        start()
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
                starting.value = false
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
        /** e2eeState is fetched once per this many 1 s polls (~10 s). */
        const val E2EE_POLL_TICKS = 10
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
        val confirmOpen by viewModel.confirmOpen.collectAsState()
        val starting by viewModel.starting.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        // The confirm panel's X + triangle replicate the LP3 reboot-confirmation
        // icons (captured 2026-08-19); the play icon is the LP3's restart glyph.
        val confirmX = painterResource(R.drawable.ic_lp3_confirm_x)
        val confirmTriangle = painterResource(R.drawable.ic_lp3_confirm_triangle)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // Cancelled/failed panels dismiss themselves back to the main
                // panel after a few seconds (feedback 2026-08-19).
                LaunchedEffect(state?.state) {
                    if (state?.state == "cancelled" || state?.state == "error") {
                        delay(CANCELLED_AUTO_DISMISS_MS)
                        viewModel.act("reset")
                    }
                }

                // Done: a bare overlay — no top bar, no back, just the centered
                // confirmation and DONE (feedback 2026-08-19).
                val verified = e2ee?.verified == true || state?.state == "done"
                if (verified) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "Verified. Encrypted messages can now decrypt.",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 3f.gridUnitsAsDp()),
                        )
                    }
                    LightBottomBar(
                        modifier = Modifier.navigationBarsPadding(),
                        items = listOf(
                            null,
                            LightBarButton.Text(
                                text = "DONE",
                                onClick = { goBack() },
                            ),
                            null,
                        ),
                    )
                    return@Column
                }

                LightTopBar(
                    leftButton = if (confirmOpen) {
                        // The confirm panel's X (bottom-left) is the only exit —
                        // no top-bar back (feedback 2026-08-19).
                        null
                    } else {
                        LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back to settings",
                        )
                    },
                    center = LightTopBarCenter.Text(
                        if (state?.state == "compare" && !confirmOpen) "Check your other device" else "Verify",
                    ),
                )

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        confirmOpen -> CenteredPanel(
                            "This will send a verification request to your other Beeper device. Continue?",
                        )

                        // Between Continue and the server's first non-idle
                        // state, show the waiting panel directly — no flash of
                        // the main panel (feedback 2026-08-19).
                        starting && state?.state == "none" -> CenteredPanel(WAITING_TEXT)

                        else -> when (state?.state) {
                            "none" -> MainPanel(
                                busy = busy,
                                error = error,
                                onStartVerification = viewModel::openConfirm,
                                onUseRecoveryKey = { openRecoveryEditor() },
                            )

                            "waiting" -> CenteredPanel(WAITING_TEXT)
                            "accept", "start" -> CenteredPanel("Your other device wants to verify. Accept?")
                            "verifying" -> CenteredPanel("Verifying…")
                            "compare" -> ComparePanel(state?.emoji.orEmpty())
                            "cancelled" -> CenteredPanel("Verification was cancelled or failed.")
                            "error" -> CenteredPanel(state?.detail ?: "Verification failed.")
                            else -> CenteredPanel("Checking…")
                        }
                    }
                }

                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = bottomBarItems(
                        confirmOpen,
                        state?.state,
                        starting,
                        confirmX,
                        confirmTriangle,
                    ),
                )
            }
        }
    }

    /** The bottom-bar action set for the current panel (feedback 2026-08-19:
     *  X dismiss/cancel; the panel's action; the compare page has no X, the
     *  waiting and cancelled panels' X sits centered). */
    private fun bottomBarItems(
        confirmOpen: Boolean,
        state: String?,
        starting: Boolean,
        confirmX: Painter,
        confirmTriangle: Painter,
    ): List<LightBarButton?> = when {
        confirmOpen -> listOf(
            LightBarButton.Icon(
                painter = confirmX,
                onClick = viewModel::dismissConfirm,
                contentDescription = "Cancel",
            ),
            null,
            LightBarButton.Icon(
                painter = confirmTriangle,
                onClick = viewModel::confirmStart,
                contentDescription = "Continue",
            ),
        )

        else -> when {
            starting && state == "none" -> listOf(null, cancelButton(), null)
            state == "waiting" -> listOf(null, cancelButton(), null)
            state == "accept" || state == "start" -> listOf(
                // Both panels route through "accept" — the server prefers the
                // pending SAS-accept over starting the SAS itself (the states
                // churn fast, LP3 2026-08-19).
                cancelButton(),
                null,
                LightBarButton.Text(
                    text = "ACCEPT",
                    onClick = { viewModel.act("accept") },
                ),
            )
            state == "verifying" -> listOf(null, cancelButton(), null)
            state == "compare" -> listOf(
                // X (cancel) left + THEY MATCH center — the two text buttons
                // crowded each other; "they don't match" is covered by the X
                // (feedback 2026-08-19).
                cancelButton(),
                LightBarButton.Text(
                    text = "THEY MATCH",
                    onClick = { viewModel.act("match") },
                ),
                null,
            )
            state == "cancelled" || state == "error" -> listOf(
                null,
                LightBarButton.Icon(
                    painter = confirmX,
                    onClick = { viewModel.act("reset") },
                    contentDescription = "Dismiss",
                ),
                null,
            )
            else -> listOf(null, null, null)
        }
    }

    private fun cancelButton(): LightBarButton = LightBarButton.LightIcon(
        icon = LightIcons.CLOSE,
        onClick = { viewModel.act("cancel") },
        contentDescription = "Cancel",
    )

    private fun openRecoveryEditor() {
        navigateTo(screenFactory = {
            RecoveryKeyEditorScreen(it)
        }) { key ->
            if (key.isNotBlank()) viewModel.recover(key)
        }
    }
}

/** The main verify panel: the two options as settings-style rows (feedback
 *  2026-08-19 — no intro text, "Use Recovery Key" is a plain row now). */
@Composable
private fun MainPanel(
    busy: Boolean,
    error: String?,
    onStartVerification: () -> Unit,
    onUseRecoveryKey: () -> Unit,
) {
    LightScrollView {
        Column(
            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(enabled = !busy, onClick = onStartVerification)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = if (busy) "…" else "Start Verification",
                    variant = LightTextVariant.Heading,
                )
                LightText(
                    text = "with another Beeper device",
                    variant = LightTextVariant.Detail,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = onUseRecoveryKey)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = "Use Recovery Key",
                    variant = LightTextVariant.Heading,
                )
            }
            error?.let { message ->
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                )
            }
        }
    }
}

/** A black centered-text panel (the confirm/waiting/accept/cancelled states). */
@Composable
private fun CenteredPanel(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 3f.gridUnitsAsDp()),
        )
    }
}

/** The emoji-comparison panel: the SAS emojis, with the confirmation line
 *  centred underneath (feedback 2026-08-19). */
@Composable
private fun ComparePanel(emojis: List<String>) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // All seven on one line so the SAS set reads as a single
            // comparison (Heading keeps them small enough to fit).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                emojis.forEach { emoji ->
                    LightText(text = emoji, variant = LightTextVariant.Heading)
                }
            }
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            LightText(
                text = "Confirm the Emojis match the ones shown on your other device",
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 3f.gridUnitsAsDp()),
            )
        }
    }
}
