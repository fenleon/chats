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
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Tool settings (Phase 13): the account panel (login setup + verification)
 * lives behind the Account row, and a toggle controls the thread's
 * "seen"/"delivered" markers. The status-heavy content — account state, sync
 * progress, encryption — moved to [AccountScreen].
 */
class SettingsViewModel : LightViewModel<Unit>() {

    val account = MutableStateFlow<LightServiceMethod.GetAccountState.Response?>(null)
    val connection = MutableStateFlow<LightServiceMethod.GetConnectionState.Response?>(null)

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
        }
    }

    /** Toggles the companion's sync loop (audit 2026-08-14 — battery escape hatch). */
    fun setSyncEnabled(value: Boolean) {
        viewModelScope.launch {
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

    /** Persists the mobile-data-downloads toggle (the screen supplies its DataStore). */
    fun setDownloadOverMobile(lightContext: SealedLightContext, value: Boolean) {
        viewModelScope.launch {
            ChatSettings.setDownloadOverMobile(lightContext, value)
        }
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
        val showReadStatus by ChatSettings.showReadStatus.collectAsState()
        val downloadOverMobile by ChatSettings.downloadOverMobile.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        // Load the persisted toggle once (idempotent) before rendering it.
        LaunchedEffect(Unit) { ChatSettings.load(lightContext) }

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
                        Column(modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp())) {
                            SettingsRow(
                                title = "Account",
                                subtitle = account?.let {
                                    if (it.loggedIn == true) it.userId ?: "Signed in"
                                    else "Not signed in"
                                } ?: "…",
                                onClick = { navigateTo(screenFactory = { AccountScreen(it) }) },
                            )
                            ToggleRow(
                                checked = connection?.syncEnabled ?: true,
                                title = "Sync",
                                subtitle = "pause background sync to save battery",
                                onToggle = {
                                    viewModel.setSyncEnabled(!(connection?.syncEnabled ?: true))
                                },
                            )
                            ToggleRow(
                                checked = showReadStatus,
                                title = "Show read status",
                                subtitle = "seen / delivered under my messages",
                                onToggle = {
                                    viewModel.setShowReadStatus(lightContext, !showReadStatus)
                                },
                            )
                            ToggleRow(
                                checked = downloadOverMobile,
                                title = "Mobile data downloads",
                                subtitle = "off — photos download on Wi-Fi only",
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
        }
    }
}

/** A plain settings row that opens something (the Account panel). */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(36.dp))
        Column {
            LightText(
                text = title,
                // Settings-row primary label — Heading per DESIGN.md.
                variant = LightTextVariant.Heading,
            )
            if (subtitle != null) {
                // Settings sub-caption — Detail (20 sp) per DESIGN.md §6-7
                // (feedback 2026-08-14: the previous Fine was too big).
                LightText(
                    text = subtitle,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 2.dp),
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
                size = 1.5f,
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
            // Settings sub-caption — Detail (20 sp) per DESIGN.md §6-7.
            LightText(
                text = subtitle,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
