package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.R
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The account panel: login setup, account status, and device verification —
 * moved out of Settings. Reachable from Settings → Account.
 */
class AccountViewModel : LightViewModel<Unit>() {

    // Login form; credentials live only on the companion, these are transient.
    // "beeper" is the v1 login (WhatsApp via a Beeper account); "homeserver"
    // stays in the UI as dev/test tooling (the emulator runs against Synapse).
    val beeperMode = MutableStateFlow(true)
    val beeperEmail = MutableStateFlow("")
    val beeperCode = MutableStateFlow("")
    val codeStatus = MutableStateFlow<String?>(null)
    /** True once REQUEST CODE succeeded — the bar button reads REQUEST AGAIN
     *  until a login (or logout) resets the flow (2026-08-29). */
    val codeRequested = MutableStateFlow(false)

    val homeserver = MutableStateFlow("")
    val user = MutableStateFlow("")
    val password = MutableStateFlow("")
    val tokenLogin = MutableStateFlow(false)

    val account = MutableStateFlow<LightServiceMethod.GetAccountState.Response?>(null)
    val connection = MutableStateFlow<LightServiceMethod.GetConnectionState.Response?>(null)
    val e2ee = MutableStateFlow<LightServiceMethod.GetE2eeState.Response?>(null)
    /**
     * Whether the e2ee verdict has settled. The first read on a cold trust
     * store can lag a poll behind the truth, so "Verify Device" only shows
     * after a verified read OR two consecutive unverified reads — until then
     * the row reads "Checking…" (feedback 2026-08-20: "not verified" flashed
     * on launch before loading to "verified").
     */
    val e2eeSettled = MutableStateFlow(false)
    /** The verification state machine's state string ("none" | "waiting" |
     *  "accept" | "start" | "verifying" | "compare" | …) — the Verify Device
     *  row reads it to show "Verifying" mid-process. */
    val verification = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // No thread is on screen here; let the companion notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        refreshStatus()
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

    /** Keeps the status fresh while Account is visible (sync state, rooms). */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                refreshStatus()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val state = ChatClient.accountState()
            account.value = state
            connection.value = ChatClient.connectionState()
            val newE2ee = ChatClient.e2eeState()
            val prev = e2ee.value
            e2ee.value = newE2ee
            // A verified read settles immediately; an unverified one only after
            // two consecutive reads agree (the first read on a cold trust
            // store can lag — see [e2eeSettled]).
            e2eeSettled.value = newE2ee?.verified == true ||
                (prev != null && prev.verified == false && newE2ee?.verified == false)
            verification.value = ChatClient.verificationState()?.state
            // Keep the homeserver field in step with the account that's active.
            state?.homeserver?.takeIf { it.isNotBlank() }?.let {
                homeserver.value = it
            }
            state?.loginMode?.let { beeperMode.value = (it == "beeper") }
        }
    }

    fun setLoginMode(beeper: Boolean) {
        if (beeperMode.value == beeper) return
        beeperMode.value = beeper
        error.value = null
    }

    /** Beeper login, step 1: emails a code, then opens the code-entry panel via
     *  [onSuccess] (which receives the email, for the panel's "code sent"
     *  caption) once the request is confirmed — the feedback flow: the bottom
     *  bar's "request code" both sends the code and opens the code panel.
     *  Runs off-main: the in-process binder executes the server's handler on
     *  the caller's thread, and these are multi-second network calls (the
     *  login also starts the foreground sync service — a main-thread block
     *  past its 5 s window crashed the app, LP3 2026-08-19). */
    fun requestCode(onSuccess: (email: String) -> Unit) {
        if (busy.value) return
        val email = beeperEmail.value.trim()
        if (email.isBlank()) {
            error.value = "Enter your Beeper email first."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            error.value = null
            codeStatus.value = null
            try {
                val failure = ChatClient.beeperRequestCode(email)
                if (failure != null) {
                    error.value = failure
                } else {
                    codeRequested.value = true
                    codeStatus.value = "Code sent to $email — check your email"
                    onSuccess(email)
                }
            } finally {
                // A binder exception must not leave the button dead (feedback
                // 2026-08-19: the same stuck-flag class as the thread's
                // loading guard) — busy always clears.
                busy.value = false
            }
        }
    }

    fun login() {
        if (busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            error.value = null
            try {
                val failure = if (beeperMode.value) {
                    ChatClient.beeperLogin(beeperEmail.value.trim(), beeperCode.value.trim())
                } else {
                    val response = ChatClient.setAccount(
                        homeserver = homeserver.value.trim(),
                        user = user.value.trim(),
                        passwordOrToken = password.value,
                        tokenLogin = tokenLogin.value,
                    )
                    if (response != null) null
                    else "Couldn't log in. Check the homeserver and credentials."
                }
                if (failure == null) {
                    password.value = ""
                    beeperCode.value = ""
                    codeStatus.value = null
                    codeRequested.value = false
                    refreshStatus()
                } else {
                    error.value = failure
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun logout() {
        if (busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                ChatClient.logout()
                password.value = ""
                beeperCode.value = ""
                codeStatus.value = null
                codeRequested.value = false
                refreshStatus()
            } finally {
                busy.value = false
            }
        }
    }

    fun toggleTokenLogin() {
        tokenLogin.value = !tokenLogin.value
    }

    private var pollJob: Job? = null

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
    }
}

class AccountScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AccountViewModel>(sealedActivity) {

    override val viewModelClass: Class<AccountViewModel>
        get() = AccountViewModel::class.java

    override fun createViewModel(): AccountViewModel = AccountViewModel()

    /** Password/token display mask — the plaintext only lives in the editor
     *  once submitted (feedback 2026-08-19). */
    private companion object {
        const val MASKED_SECRET = "••••••••"

        /** Verification states that mean "not in progress" — everything else
         *  (waiting/accept/start/verifying/compare) reads "Verifying". */
        val VERIFICATION_TERMINAL_STATES = setOf("none", "done", "cancelled", "error")
    }

    @Composable
    override fun Content() {
        val beeperMode by viewModel.beeperMode.collectAsState()
        val beeperEmail by viewModel.beeperEmail.collectAsState()
        val beeperCode by viewModel.beeperCode.collectAsState()
        val codeStatus by viewModel.codeStatus.collectAsState()
        val codeRequested by viewModel.codeRequested.collectAsState()
        val homeserver by viewModel.homeserver.collectAsState()
        val user by viewModel.user.collectAsState()
        val password by viewModel.password.collectAsState()
        val tokenLogin by viewModel.tokenLogin.collectAsState()
        val account by viewModel.account.collectAsState()
        val connection by viewModel.connection.collectAsState()
        val e2ee by viewModel.e2ee.collectAsState()
        val e2eeSettled by viewModel.e2eeSettled.collectAsState()
        val verification by viewModel.verification.collectAsState()
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
                    center = LightTopBarCenter.Text("Account"),
                )
                Box(modifier = Modifier.weight(1f)) {
                    LightScrollView {
                        if (account?.loggedIn == true) {
                            // Logged in: just the account status + actions — the
                            // login form (email/code) would be dead weight here.
                            // The device-verification row leads the panel
                            // (feedback 2026-09-01); the account name is not
                            // shown, only the sync status.
                            EncryptionRow(
                                e2ee = e2ee,
                                settled = e2eeSettled,
                                verifying = verification != null &&
                                    verification !in VERIFICATION_TERMINAL_STATES,
                                onClick = if (e2ee?.verified == true || !e2eeSettled) null else {
                                    { navigateTo(screenFactory = { VerificationScreen(it) }) }
                                },
                            )
                            AccountStatus(connection = connection)
                        } else {
                            // Logged out: if the session just expired, say so
                            // instead of showing a bare login form (the user
                            // should re-login directly, not hunt for LOG OUT).
                            val conn = connection
                            if (conn?.state == "offline" &&
                                conn.detail?.startsWith("session expired") == true
                            ) {
                                LightText(
                                    text = "Your session expired — sign in again.",
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier.padding(
                                        horizontal = 2f.gridUnitsAsDp(),
                                        vertical = 0.5f.gridUnitsAsDp(),
                                    ),
                                )
                            }
                            // The Server row opens the picker (Beeper vs Matrix
                            // homeserver); the flow's fields render below it.
                            ServerRow(
                                beeperMode = beeperMode,
                                onClick = {
                                    navigateTo(screenFactory = { ServerScreen(it, beeperMode) }) { selected ->
                                        viewModel.setLoginMode(selected)
                                    }
                                },
                            )
                            if (beeperMode) {
                                FormField(
                                    label = "Beeper email:",
                                    value = beeperEmail,
                                    placeholder = "you@example.com",
                                    onClick = { editField("Beeper email", viewModel.beeperEmail) },
                                )
                                // The Enter code entry appears once a code has
                                // been requested (feedback 2026-08-19: the
                                // request-code overlay dismisses back here).
                                if (codeStatus != null || beeperCode.isNotEmpty()) {
                                    FormField(
                                        label = "Enter code:",
                                        value = beeperCode,
                                        placeholder = "6-digit code",
                                        onClick = {
                                            editField(
                                                title = "Enter code",
                                                field = viewModel.beeperCode,
                                                // The code editor submits (was
                                                // SAVE — feedback 2026-08-19).
                                                submitLabel = "SUBMIT",
                                            ) { code ->
                                                if (code.isNotBlank()) viewModel.login()
                                            }
                                        },
                                    )
                                }
                            } else {
                                FormField(
                                    label = "Homeserver:",
                                    value = homeserver,
                                    placeholder = "matrix.example.org",
                                    onClick = { editField("Homeserver", viewModel.homeserver) },
                                )
                                FormField(
                                    label = "Username:",
                                    value = user,
                                    placeholder = "@user:server",
                                    onClick = { editField("Username", viewModel.user) },
                                )
                                FormField(
                                    label = if (tokenLogin) "Access token:" else "Password:",
                                    // Masked once submitted — the plaintext only
                                    // lives in the editor (feedback 2026-08-19).
                                    value = if (password.isBlank()) password else MASKED_SECRET,
                                    placeholder = if (tokenLogin) "syt_…" else "password",
                                    onClick = { editField("Password", viewModel.password) },
                                )
                                TokenToggleRow(
                                    tokenLogin = tokenLogin,
                                    onToggle = viewModel::toggleTokenLogin,
                                )
                            }
                            error?.let { message -> StatusLine(message) }
                            codeStatus?.let { status -> StatusLine(status) }
                        }
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        if (account?.loggedIn == true) {
                            LightBarButton.Text(
                                text = if (busy) "…" else "LOG OUT",
                                onClick = if (busy) null else {
                                    {
                                        navigateTo(screenFactory = { LogoutConfirmPanel(it) }) { confirmed ->
                                            if (confirmed) viewModel.logout()
                                        }
                                    }
                                },
                            )
                        } else if (beeperMode) {
                            // Beeper flow: the bar's button sends the emailed
                            // code, then an overlay panel confirms it and the
                            // user enters the code via the Enter code field
                            // (feedback 2026-08-19).
                            LightBarButton.Text(
                                text = if (busy) "…" else if (codeRequested) "REQUEST AGAIN" else "REQUEST CODE",
                                onClick = if (busy) null else {
                                    {
                                        viewModel.requestCode { email ->
                                            navigateTo(screenFactory = {
                                                CodeSentPanel(it, email)
                                            })
                                        }
                                    }
                                },
                            )
                        } else {
                            LightBarButton.Text(
                                text = if (busy) "…" else "LOG IN",
                                onClick = if (busy) null else viewModel::login,
                            )
                        },
                    ),
                )
            }
        }
    }

    /** Opens the LP3 keyboard editor for one login field; the trimmed result
     *  replaces the field, an explicit back (no result) keeps the old value.
     *  [onResult] fires with the accepted value (e.g. the beeper login after
     *  the code). [submitLabel] — SAVE for field editors, SUBMIT for the code
     *  entry (feedback 2026-08-19). */
    private fun editField(
        title: String,
        field: MutableStateFlow<String>,
        submitLabel: String = "SAVE",
        onResult: (String) -> Unit = {},
    ) {
        navigateTo(screenFactory = {
            FieldEditorScreen(it, title, field.value, submitLabel)
        }) { value ->
            field.value = value
            onResult(value)
        }
    }
}

/** A login-form field row: the LightTextField with the standard row padding. */
@Composable
private fun FormField(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    LightTextField(
        label = label,
        value = value,
        placeholder = placeholder,
        onClick = onClick,
        modifier = Modifier.padding(
            horizontal = 2f.gridUnitsAsDp(),
            vertical = 0.75f.gridUnitsAsDp(),
        ),
    )
}

/** A Detail-sized status line under the form (login error / code status). */
@Composable
private fun StatusLine(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
private fun TokenToggleRow(
    tokenLogin: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onToggle)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        // The toggle sits immediately left of its action label, the row
        // top-aligned so it lines up with the main label (same as Audiobooks
        // Settings, feedback 2026-08-17).
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(
                icon = if (tokenLogin) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                // 2 gu ≈ the native switch pill (feedback 2026-08-19: the
                // 1.5-gu toggles were too small).
                size = 2f,
                contentDescription = if (tokenLogin) {
                    "Log in with an access token"
                } else {
                    "Log in with a password"
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            LightText(
                text = "Use access token",
                // A settings toggle row — Heading title like the Settings
                // page's toggles (feedback 2026-08-19).
                variant = LightTextVariant.Heading,
            )
            LightText(
                text = "instead of password",
                variant = LightTextVariant.Detail,
            )
        }
    }
}

/** Login path row: a single "Server" entry whose value is the active
 *  selection (Beeper or Matrix homeserver); tapping opens [ServerScreen].
 *  Value-row anatomy — "Server" is the Copy-sized top text, the selection the
 *  Heading-sized main text, flush-left (DESIGN.md §6, feedback 2026-08-19). */
@Composable
private fun ServerRow(
    beeperMode: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            LightText(
                text = "Server",
                // Value-row top-text label — Detail-sized (DESIGN.md §6,
                // feedback 2026-08-19).
                variant = LightTextVariant.Detail,
            )
            LightText(
                text = if (beeperMode) "Beeper" else "Matrix homeserver",
                // The value sits almost touching the label — pulled up into the
                // label's descender space (feedback 2026-08-19).
                variant = LightTextVariant.Heading,
                modifier = Modifier.offset(y = (-3).dp),
            )
        }
    }
}

/** The Server picker (feedback 2026-08-19): "Beeper" vs "Matrix homeserver",
 *  the current selection underlined. Result: the chosen [AccountViewModel.beeperMode]. */
class ServerScreen(
    sealedActivity: SealedLightActivity,
    private val beeperMode: Boolean,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
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
                        contentDescription = "Back to account",
                    ),
                    center = LightTopBarCenter.Text("Server"),
                )
                LightScrollView {
                    ServerOptionRow(
                        label = "Beeper",
                        active = beeperMode,
                        onClick = { goBack(true) },
                    )
                    ServerOptionRow(
                        label = "Matrix homeserver",
                        active = !beeperMode,
                        onClick = { goBack(false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerOptionRow(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    LightText(
        text = label,
        variant = LightTextVariant.Heading,
        // Every row full color; the selected one is underlined — selection is
        // conveyed by underline, not color (design rule, feedback 2026-08-21).
        underline = active,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    )
}

/** The request-code confirmation overlay (feedback 2026-08-19): centered
 *  "A code has been sent to <email>." / "Check your email." on separate lines
 *  (2026-08-29) with an X dismiss in the bottom centre; dismissing returns to
 *  the account panel, where the Enter code field now appears. */
class CodeSentPanel(
    sealedActivity: SealedLightActivity,
    private val email: String,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val confirmX = painterResource(R.drawable.ic_lp3_confirm_x)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 3f.gridUnitsAsDp()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LightText(
                            text = "A code has been sent to $email.",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                        )
                        LightText(
                            text = "Check your email.",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                        )
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        null,
                        LightBarButton.Icon(
                            painter = confirmX,
                            onClick = { goBack() },
                            contentDescription = "Dismiss",
                        ),
                        null,
                    ),
                )
            }
        }
    }
}

/** The logout confirmation overlay (feedback 2026-08-19): centered
 *  "Do you want to log out of your account?" with the LP3 X (dismiss) and
 *  triangle (confirm) — same panel grammar as the verify confirm. Result:
 *  true = log out. */
class LogoutConfirmPanel(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val confirmX = painterResource(R.drawable.ic_lp3_confirm_x)
        val confirmTriangle = painterResource(R.drawable.ic_lp3_confirm_triangle)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "Do you want to log out of your account?",
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 3f.gridUnitsAsDp()),
                    )
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        LightBarButton.Icon(
                            painter = confirmX,
                            onClick = { goBack(false) },
                            contentDescription = "Cancel",
                        ),
                        null,
                        LightBarButton.Icon(
                            painter = confirmTriangle,
                            onClick = { goBack(true) },
                            contentDescription = "Log out",
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun AccountStatus(
    connection: LightServiceMethod.GetConnectionState.Response?,
) {
    Column(
        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        connection?.let { state ->
            val allSynced = state.state == "syncing" &&
                state.roomsTotal > 0 && state.roomsResolved >= state.roomsTotal
            // Feedback 2026-08-19: the status line reads plainly — "sync
            // paused" when the toggle is off, "offline" when there's simply
            // no connection. The thread count shares the same line
            // (2026-08-29): "Syncing · 34 of 52 threads".
            val statusText = when {
                allSynced -> "Synced"
                state.state == "syncing" -> "Syncing"
                !state.syncEnabled -> "sync paused"
                state.state == "offline" -> "offline"
                state.state == "connecting" -> "connecting"
                else -> state.state.replaceFirstChar { it.uppercase() }
            }
            val countText = when {
                state.roomsTotal <= 0 -> null
                state.roomsResolved >= state.roomsTotal -> pluralThreads(state.roomsTotal)
                else -> "${state.roomsResolved} of ${pluralThreads(state.roomsTotal)}"
            }
            LightText(
                text = countText?.let { "$statusText · $it" } ?: statusText,
                variant = LightTextVariant.Detail,
            )
            // Key-backup restore crawl (2026-08-29): "Recovering… x of y
            // rooms" while the daily restore runs; "All messages restored"
            // once it finished AND sync has fully caught up.
            if (state.restoreScanning && state.restoreRoomsTotal > 0) {
                LightText(
                    text = "Recovering… ${state.restoreScanned} of ${state.restoreRoomsTotal} rooms",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.padding(top = 1.dp),
                )
            } else if (state.restoreCompleted && allSynced) {
                LightText(
                    text = "All messages restored",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** "1 thread" / "N threads". */
private fun pluralThreads(count: Int): String =
    if (count == 1) "1 thread" else "$count threads"

/** Device-verification row (2026-08-29): the action reads "Verify Device"
 *  while unverified, and is a status-only row once verified. No toggle — the
 *  state reads "Device Verified" / "Verifying" (mid-verification, so a
 *  back-out keeps the process visible) / "Verify Device". While the verdict
 *  hasn't settled (a cold trust store can lag a poll — feedback 2026-08-20)
 *  it reads "Checking…" and is not tappable, so launch never claims a false
 *  "Verify Device". The old "Encrypted messages" label is gone. */
@Composable
private fun EncryptionRow(
    e2ee: LightServiceMethod.GetE2eeState.Response?,
    settled: Boolean,
    verifying: Boolean,
    onClick: (() -> Unit)?,
) {
    LightText(
        text = when {
            e2ee?.verified == true -> "Device Verified"
            verifying -> "Verifying"
            !settled -> "Checking…"
            else -> "Verify Device"
        },
        variant = LightTextVariant.Heading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    )
}

/** The LP3 keyboard editor for a single settings field. Result: the edited
 *  text (trimmed; "" clears the field). The keyboard is stripped — no emoji,
 *  return, or voice keys (the passes code-entry style, feedback 2026-08-19);
 *  the input centers vertically between the top bar and the keyboard. The
 *  submit label defaults to SAVE (field editors); the code entry passes
 *  SUBMIT (feedback 2026-08-19); an optional [submitIcon] renders the
 *  submit as an icon instead of the label button (contacts search,
 *  2026-08-30). */
class FieldEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val submitLabel: String = "SAVE",
    private val submitIcon: LightIconConfiguration? = null,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        // Fixed options — no remote fetch, so the mic/emoji/return keys stay
        // off even when the platform server would enable them.
        val keyboardOptionsFlow = remember {
            MutableStateFlow(
                KeyboardOptions(
                    emojis = emptyList(),
                    displayReturn = false,
                    displayVoice = false,
                    enableKeyAnimation = true,
                    swipeEnabled = false,
                ),
            )
        }
        val textState = rememberTextFieldState(initial)

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = title,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { result -> goBack(result.toString().trim()) },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                centered = true,
                submitLabel = submitLabel,
                submitIcon = submitIcon,
            )
        }
    }
}
