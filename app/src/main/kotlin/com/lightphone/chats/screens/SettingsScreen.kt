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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.lightphone.chats.ChatSettings
import com.lightphone.chats.VolumePanelOverlay
import com.thelightphone.sdk.LightScreen
import com.lightphone.chats.ChatLightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
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
 * Tool settings: the account panel (login setup + verification) lives behind
 * the Account row, the sync toggle pauses the companion's loop, and toggles
 * control the "seen"/"delivered" markers + data-saver media downloads. The
 * status-heavy content — account state, sync progress, encryption — moved to
 * [AccountScreen].
 */
class SettingsViewModel : ChatLightViewModel<Unit>() {

    val account = MutableStateFlow<LightServiceMethod.GetAccountState.Response?>(null)
    val connection = MutableStateFlow<LightServiceMethod.GetConnectionState.Response?>(null)

    /** True between the user turning sync on and the companion reporting "syncing". */
    val startingSync = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // No thread is on screen here; let the companion notify again.
        viewModelScope.launch { ChatClient.setActiveRoom(null) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            account.value = ChatClient.accountState()
            connection.value = ChatClient.connectionState()
            if (connection.value?.state == "syncing") startingSync.value = false
        }
    }

    /** Toggles the companion's sync loop (audit 2026-08-14 — battery escape hatch). */
    fun setSyncEnabled(value: Boolean) {
        viewModelScope.launch {
            if (value && connection.value?.syncEnabled != true) {
                startingSync.value = true
                // Safety net: clear even if "syncing" never arrives (offline…).
                launch {
                    delay(STARTING_SYNC_TIMEOUT_MS)
                    startingSync.value = false
                }
            }
            ChatClient.setSyncEnabled(value)
            refresh()
        }
    }

    /** Persists the show-read-status toggle (the screen supplies its DataStore). */
    fun setShowReadStatus(lightContext: SealedLightContext, value: Boolean) {
        viewModelScope.launch {
            ChatSettings.setShowReadStatus(lightContext, value)
        }
    }

    /** Persists the data-saver toggle (the screen supplies its DataStore). */
    fun setDownloadOverMobile(lightContext: SealedLightContext, value: Boolean) {
        viewModelScope.launch {
            ChatSettings.setDownloadOverMobile(lightContext, value)
        }
    }

    private companion object {
        const val STARTING_SYNC_TIMEOUT_MS = 10_000L
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel = SettingsViewModel()

    @Composable
    override fun Content() {
        val account by viewModel.account.collectAsState()
        val connection by viewModel.connection.collectAsState()
        val startingSync by viewModel.startingSync.collectAsState()
        val showReadStatus by ChatSettings.showReadStatus.collectAsState()
        val downloadOverMobile by ChatSettings.downloadOverMobile.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val volumePanel by viewModel.volumePanel.collectAsState()

        // Load the persisted toggle once (idempotent) before rendering it.
        LaunchedEffect(Unit) { ChatSettings.load(lightContext) }

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                        Column(modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp())) {
                            SettingsRow(
                                label = "Account",
                                // Main text: the @username once signed in, "Sign
                                // In" otherwise (feedback 2026-08-19).
                                value = account?.let {
                                    if (it.loggedIn == true) it.userId ?: "Signed in"
                                    else "Sign In"
                                } ?: "…",
                                onClick = { navigateTo(screenFactory = { AccountScreen(it) }) },
                            )
                            val syncEnabled = connection?.syncEnabled ?: true
                            ToggleRow(
                                checked = syncEnabled,
                                title = "Background Sync",
                                subtitle = when {
                                    !syncEnabled -> "Paused"
                                    startingSync -> "Initializing..."
                                    else -> "Syncing"
                                },
                                onToggle = {
                                    viewModel.setSyncEnabled(!syncEnabled)
                                },
                            )
                            ToggleRow(
                                checked = showReadStatus,
                                title = "Read Status",
                                subtitle = "visible under your messages",
                                onToggle = {
                                    viewModel.setShowReadStatus(lightContext, !showReadStatus)
                                },
                            )
                            ToggleRow(
                                checked = !downloadOverMobile,
                                title = "Data Saver Mode",
                                subtitle = "only use WiFi for downloading media",
                                onToggle = {
                                    viewModel.setDownloadOverMobile(lightContext, !downloadOverMobile)
                                },
                            )
                        }
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(null, null, null),
                )
                }
                // The in-app volume panel (the LP3 rocker replica) draws over
                // the whole screen while shown.
                VolumePanelOverlay(
                    state = volumePanel,
                    onDismiss = { viewModel.dismissVolumePanel() },
                )
            }
        }
    }
}

/** A plain settings row that opens something (the Account panel). Value-row
 *  anatomy (DESIGN.md §6): the label is the Copy-sized top text, the value the
 *  Heading-sized main text; flush-left (no icon gutter, unlike toggle rows). */
@Composable
private fun SettingsRow(
    label: String,
    value: String?,
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
                text = label,
                // Value-row top-text label — Detail-sized, as small as the
                // subtexts (DESIGN.md §6, feedback 2026-08-19).
                variant = LightTextVariant.Detail,
            )
            if (value != null) {
                LightText(
                    text = value,
                    // Value-row main text — Heading. Pulled up into the label's
                    // descender space so the two sit almost touching (the emulator
                    // letterboxes ~0.66×, so the ink gap renders ~1.5× on the
                    // LP3 — feedback 2026-08-19).
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.offset(y = (-3).dp),
                )
            }
        }
    }
}

/** A toggle row (the show-read-status switch). */
@Composable
private fun ToggleRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onToggle)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        // The toggle sits immediately left of its action label, the row
        // top-aligned so it lines up with the main label — not centered
        // between the label and the caption (same as Audiobooks Settings,
        // feedback 2026-08-17).
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(
                icon = if (checked) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                // 2 gu ≈ the native LP3 switch pill (59×23 px ink; was 1.5 gu
                // and read too small — feedback 2026-08-19).
                size = 2f,
                contentDescription = title,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            LightText(
                text = title,
                // Settings-row primary label — Heading per DESIGN.md.
                variant = LightTextVariant.Heading,
            )
            // Settings sub-caption — Detail (20 sp) per DESIGN.md §6-7; full
            // color like the labels, sitting almost touching the title.
            LightText(
                text = subtitle,
                variant = LightTextVariant.Detail,
            )
        }
    }
}
