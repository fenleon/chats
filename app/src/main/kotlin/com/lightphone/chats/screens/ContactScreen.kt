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
    private val name: String,
    private val network: String?,
    private val identifier: String?,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

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
                        identifier?.let {
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
                                lighten = true,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
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
