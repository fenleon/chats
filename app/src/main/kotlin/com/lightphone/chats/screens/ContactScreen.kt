package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
/**
 * The contact overlay (feedback 2026-08-21): tapping the thread's top-bar
 * room name opens a minimal contact page — Name, network (WhatsApp /
 * Instagram), and the other party's identifier. No top bar; the bottom bar
 * carries only an X in the middle to dismiss.
 *
 * Data source is a first pass, not settled: the identifier is the other
 * party's Matrix ID localpart, which on Beeper bridges is usually the bridge
 * UID (a phone number for WhatsApp, a username for Instagram) — the closest
 * available data without bridge-API access. The user offered the LP3's phone
 * contact panel as a design reference; revisit the layout when it's shared.
 */
class ContactScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
    private val name: String,
    private val network: String?,
    private val identifier: String?,
    /**
     * The contact's phone number, resolved by the companion from the room
     * data (a @whatsapp_<number> ghost, or the number the bridge used as the
     * displayname before syncing the profile name). Shown when the identifier
     * line has nothing better (feedback 2026-08-23).
     */
    private val phone: String? = null,
    /**
     * Whether the room is muted (2026-08-23): the MUTE button under the
     * network line reads MUTE / UNMUTE; muting stops notifications for the
     * room while the unread badge stays.
     */
    private val muted: Boolean = false,
    /** Flips the room's mute server-side; the panel mirrors the new state locally. */
    private val onToggleMute: () -> Unit = {},
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var isMuted by remember { mutableStateOf(muted) }

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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LightText(
                            text = name,
                            variant = LightTextVariant.Heading,
                            align = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The phone number sits directly under the name at the
                        // same size (feedback 2026-08-22: only the contact
                        // overlay shows it, under the name, Heading like the
                        // name — not the small Fine line below the network).
                        // The identifier wins when it IS the number (phone-ghost
                        // contacts); otherwise the companion-resolved phone.
                        (identifier ?: phone)?.let {
                            LightText(
                                text = it,
                                variant = LightTextVariant.Heading,
                                align = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        network?.let {
                            LightText(
                                text = it,
                                variant = LightTextVariant.Detail,
                                // Solid white — the network line reads like the
                                // name above it, not dimmed (feedback
                                // 2026-08-27).
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        // Mute toggle (2026-08-23): under the network line at
                        // Settings-panel spacing; UNMUTE replaces MUTE once on.
                        // Notifications stop, the unread badge stays. Button
                        // variant = the bottom-bar text size (feedback
                        // 2026-08-23: "as big as the text in the bottom bar").
                        // Sits lower on the panel (feedback 2026-08-27).
                        LightText(
                            text = if (isMuted) "UNMUTE" else "MUTE",
                            variant = LightTextVariant.Button,
                            modifier = Modifier
                                .padding(top = 4f.gridUnitsAsDp())
                                .lightClickable(onClick = {
                                    isMuted = !isMuted
                                    onToggleMute()
                                })
                                .padding(
                                    horizontal = 2f.gridUnitsAsDp(),
                                    vertical = 0.75f.gridUnitsAsDp(),
                                ),
                        )
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        null,
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            onClick = { goBack() },
                            contentDescription = "Close contact",
                        ),
                        null,
                    ),
                )
            }
        }
    }
}
