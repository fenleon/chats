package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.chats.ChatSettings
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Features panel: the display toggles (feedback 2026-09-22 — "under
 * Settings create a new Features option, move Seen Status into it"). All
 * default ON; persisted via [ChatSettings].
 */
class FeaturesScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel = SettingsViewModel()

    @Composable
    override fun Content() {
        val showReactions by ChatSettings.showReactions.collectAsState()
        val showReadStatus by ChatSettings.showReadStatus.collectAsState()
        val showTimestamps by ChatSettings.showTimestamps.collectAsState()
        val showMarkdown by ChatSettings.showMarkdown.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LaunchedEffect(Unit) { ChatSettings.load(lightContext) }

        fun toggle(flow: MutableStateFlow<Boolean>, current: Boolean) {
            viewModel.setSetting(lightContext, flow, !current)
        }

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
                            contentDescription = "Back to settings",
                        ),
                        center = LightTopBarCenter.Text("Features"),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        LightScrollView {
                            Column(modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp())) {
                                ToggleRow(
                                    checked = showReactions,
                                    title = "Reactions",
                                    onToggle = { toggle(ChatSettings.showReactions, showReactions) },
                                )
                                ToggleRow(
                                    checked = showReadStatus,
                                    title = "Seen Status",
                                    onToggle = { toggle(ChatSettings.showReadStatus, showReadStatus) },
                                )
                                ToggleRow(
                                    checked = showTimestamps,
                                    title = "Timestamps",
                                    onToggle = { toggle(ChatSettings.showTimestamps, showTimestamps) },
                                )
                                ToggleRow(
                                    checked = showMarkdown,
                                    title = "Basic Markdown",
                                    onToggle = { toggle(ChatSettings.showMarkdown, showMarkdown) },
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
}
