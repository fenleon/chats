package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : LightViewModel<Unit>() {

    // Login form; credentials live only on the companion, these are transient.
    // "beeper" is the v1 login (WhatsApp via a Beeper account); "homeserver"
    // stays in the UI as dev/test tooling (the emulator runs against Synapse).
    val beeperMode = MutableStateFlow(true)
    val beeperEmail = MutableStateFlow("")
    val beeperCode = MutableStateFlow("")
    val codeStatus = MutableStateFlow<String?>(null)

    val homeserver = MutableStateFlow("")
    val user = MutableStateFlow("")
    val password = MutableStateFlow("")
    val tokenLogin = MutableStateFlow(false)

    val account = MutableStateFlow<LightServiceMethod.GetAccountState.Response?>(null)
    val connection = MutableStateFlow<LightServiceMethod.GetConnectionState.Response?>(null)
    val e2ee = MutableStateFlow<LightServiceMethod.GetE2eeState.Response?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // No thread is on screen here; let the companion notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val state = ChatClient.accountState()
            account.value = state
            connection.value = ChatClient.connectionState()
            e2ee.value = ChatClient.e2eeState()
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

    /** Beeper login, step 1: asks the companion to email [beeperEmail] a code. */
    fun requestCode() {
        if (busy.value) return
        val email = beeperEmail.value.trim()
        if (email.isBlank()) {
            error.value = "Enter your Beeper email first."
            return
        }
        viewModelScope.launch {
            busy.value = true
            error.value = null
            codeStatus.value = null
            val failure = ChatClient.beeperRequestCode(email)
            busy.value = false
            if (failure != null) {
                error.value = failure
            } else {
                codeStatus.value = "Code sent to $email — check your email"
            }
        }
    }

    fun login() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
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
            busy.value = false
            if (failure == null) {
                password.value = ""
                beeperCode.value = ""
                codeStatus.value = null
                refreshStatus()
            } else {
                error.value = failure
            }
        }
    }

    fun logout() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            ChatClient.logout()
            busy.value = false
            password.value = ""
            beeperCode.value = ""
            codeStatus.value = null
            refreshStatus()
        }
    }

    fun toggleTokenLogin() {
        tokenLogin.value = !tokenLogin.value
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel = SettingsViewModel()

    @Composable
    override fun Content() {
        val beeperMode by viewModel.beeperMode.collectAsState()
        val beeperEmail by viewModel.beeperEmail.collectAsState()
        val beeperCode by viewModel.beeperCode.collectAsState()
        val codeStatus by viewModel.codeStatus.collectAsState()
        val homeserver by viewModel.homeserver.collectAsState()
        val user by viewModel.user.collectAsState()
        val password by viewModel.password.collectAsState()
        val tokenLogin by viewModel.tokenLogin.collectAsState()
        val account by viewModel.account.collectAsState()
        val connection by viewModel.connection.collectAsState()
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
                        contentDescription = "Back to chats",
                    ),
                    center = LightTopBarCenter.Text("Settings"),
                )
                Box(modifier = Modifier.weight(1f)) {
                    LightScrollView {
                        LoginModeRow(
                            beeperMode = beeperMode,
                            onSelect = viewModel::setLoginMode,
                        )
                        if (beeperMode) {
                            LightTextField(
                                label = "Beeper email:",
                                value = beeperEmail,
                                placeholder = "you@example.com",
                                onClick = { editField("Beeper email", viewModel.beeperEmail) },
                                modifier = Modifier.padding(
                                    horizontal = 1f.gridUnitsAsDp(),
                                    vertical = 0.75f.gridUnitsAsDp(),
                                ),
                            )
                            RequestCodeRow(
                                enabled = !busy,
                                status = codeStatus,
                                onRequest = viewModel::requestCode,
                            )
                            if (codeStatus != null || beeperCode.isNotEmpty()) {
                                LightTextField(
                                    label = "Code:",
                                    value = beeperCode,
                                    placeholder = "6-digit code",
                                    onClick = { editField("Code", viewModel.beeperCode) },
                                    modifier = Modifier.padding(
                                        horizontal = 1f.gridUnitsAsDp(),
                                        vertical = 0.75f.gridUnitsAsDp(),
                                    ),
                                )
                            }
                        } else {
                            LightTextField(
                                label = "Homeserver:",
                                value = homeserver,
                                placeholder = "matrix.example.org",
                                onClick = { editField("Homeserver", viewModel.homeserver) },
                                modifier = Modifier.padding(
                                    horizontal = 1f.gridUnitsAsDp(),
                                    vertical = 0.75f.gridUnitsAsDp(),
                                ),
                            )
                            LightTextField(
                                label = "Username:",
                                value = user,
                                placeholder = "@user:server",
                                onClick = { editField("Username", viewModel.user) },
                                modifier = Modifier.padding(
                                    horizontal = 1f.gridUnitsAsDp(),
                                    vertical = 0.75f.gridUnitsAsDp(),
                                ),
                            )
                            LightTextField(
                                label = if (tokenLogin) "Access token:" else "Password:",
                                value = password,
                                placeholder = if (tokenLogin) "syt_…" else "password",
                                onClick = { editField("Password", viewModel.password) },
                                modifier = Modifier.padding(
                                    horizontal = 1f.gridUnitsAsDp(),
                                    vertical = 0.75f.gridUnitsAsDp(),
                                ),
                            )
                            TokenToggleRow(
                                tokenLogin = tokenLogin,
                                onToggle = viewModel::toggleTokenLogin,
                            )
                        }
                        account?.takeIf { it.loggedIn }?.let { state ->
                            AccountStatus(
                                userId = state.userId,
                                connection = connection,
                            )
                            EncryptionRow(
                                e2ee = e2ee,
                                onClick = {
                                    navigateTo(screenFactory = { VerificationScreen(it) })
                                },
                            )
                        }
                        error?.let { message ->
                            LightText(
                                text = message,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        LightText(
                            text = "Sign in with your Beeper account for WhatsApp and other networks, " +
                                "or with a Matrix homeserver.",
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                        )
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        if (account?.loggedIn == true) {
                            LightBarButton.Text(
                                text = if (busy) "…" else "LOG OUT",
                                onClick = if (busy) null else viewModel::logout,
                            )
                        } else {
                            LightBarButton.Text(
                                text = if (busy) "…" else "LOG IN",
                                onClick = if (busy || (beeperMode && beeperCode.isBlank())) {
                                    null
                                } else {
                                    viewModel::login
                                },
                            )
                        },
                    ),
                )
            }
        }
    }

    /** Opens the LP3 keyboard editor for one login field; the trimmed result
     *  replaces the field, an explicit back keeps the old value. */
    private fun editField(
        title: String,
        field: MutableStateFlow<String>,
    ) {
        navigateTo(screenFactory = {
            FieldEditorScreen(it, title, field.value)
        }) { value -> if (value != null) field.value = value }
    }
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(
                icon = if (tokenLogin) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 1.5f,
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
                variant = LightTextVariant.Copy,
            )
            LightText(
                text = "instead of password",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Login path selector: Beeper account (v1) vs Matrix homeserver (dev/test). */
@Composable
private fun LoginModeRow(
    beeperMode: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    ) {
        ModeRow(
            title = "Beeper account",
            subtitle = "WhatsApp & other networks",
            active = beeperMode,
            onClick = { onSelect(true) },
        )
        ModeRow(
            title = "Matrix homeserver",
            subtitle = "for a self-hosted server",
            active = !beeperMode,
            onClick = { onSelect(false) },
        )
    }
}

@Composable
private fun ModeRow(
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(
                icon = if (active) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 1.5f,
                contentDescription = title,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            LightText(
                text = title,
                variant = LightTextVariant.Copy,
            )
            LightText(
                text = subtitle,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "Request code" row for the Beeper login, with the emailed-code status below. */
@Composable
private fun RequestCodeRow(
    enabled: Boolean,
    status: String?,
    onRequest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(enabled = enabled, onClick = onRequest)
                .padding(vertical = 0.75f.gridUnitsAsDp()),
        ) {
            LightText(
                text = if (enabled) "Request code" else "…",
                variant = LightTextVariant.Copy,
            )
        }
        status?.let {
            LightText(
                text = it,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun AccountStatus(
    userId: String?,
    connection: LightServiceMethod.GetConnectionState.Response?,
) {
    Column(
        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = userId ?: "Logged in",
            variant = LightTextVariant.Copy,
        )
        connection?.let { state ->
            LightText(
                text = state.state.replaceFirstChar { it.uppercase() } +
                    state.detail?.let { " — $it" }.orEmpty(),
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 2.dp),
            )
            // Sync progress: how many rooms have been synced/resolved so far.
            if (state.roomsTotal > 0) {
                LightText(
                    text = if (state.roomsResolved >= state.roomsTotal) {
                        "${state.roomsTotal} rooms"
                    } else {
                        "${state.roomsResolved} of ${state.roomsTotal} rooms"
                    },
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** "Encrypted messages" row; opens the device-verification screen. */
@Composable
private fun EncryptionRow(
    e2ee: LightServiceMethod.GetE2eeState.Response?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(
                icon = if (e2ee?.verified == true) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 1.5f,
                contentDescription = "Encrypted messages",
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            LightText(
                text = "Encrypted messages",
                variant = LightTextVariant.Copy,
            )
            LightText(
                text = if (e2ee?.verified == true) {
                    "verified"
                } else {
                    "not verified — unlock to read"
                },
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The LP3 keyboard editor for a single settings field. Result: the edited
 *  text (trimmed; "" clears the field). */
class FieldEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val textState = rememberTextFieldState(initial)

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = title,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { result -> goBack(result.toString().trim()) },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
