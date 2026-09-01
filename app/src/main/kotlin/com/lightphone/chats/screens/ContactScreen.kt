package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
/**
 * The contact overlay (feedback 2026-08-21): tapping the thread's top-bar
 * room name opens a minimal contact page — Name, the other party's
 * identifier (same size as the name, second line; 2026-09-01), and the
 * network underneath as the small subtext (WhatsApp / Instagram). No top
 * bar; the bottom bar carries only an X in the middle to dismiss. The
 * identity block stays centered in the upper area; the PIN / MUTE / ARCHIVE
 * toggles stack below it, bottom-anchored just above the bottom bar (LP3
 * feedback 2026-08-28).
 *
 * Data source: the identifier is the contact's real number/username,
 * resolved by the companion from the bridge's contact API when the room
 * data doesn't carry it (WhatsApp LID heroes, Instagram usernames —
 * 2026-09-01); the room-data localpart heuristic is the fallback. The user
 * offered the LP3's phone contact panel as a design reference; revisit the
 * layout when it's shared.
 */
class ContactScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
    private val name: String,
    private val network: String?,
    /**
     * For rooms inside a bridged community (WhatsApp community groups): the
     * community's own name. Renders as the second Heading line when the room
     * has no identifier — the group fills the slot the id occupies for 1:1s
     * (feedback 2026-09-01).
     */
    private val community: String? = null,
    private val identifier: String?,
    /**
     * The contact's identifier, resolved by the companion from the room data
     * (a @whatsapp_<number> ghost, the number the bridge used as the
     * displayname before syncing the profile name) or the bridge's contact
     * API (LID-resolved numbers, Instagram usernames). Renders as the second
     * Heading line under the name; the identifier wins when it IS the number
     * (phone-ghost contacts), otherwise the companion-resolved phone.
     */
    private val phone: String? = null,
    /**
     * Mute state (2026-08-23): the MUTE button under the network line reads
     * MUTE / UNMUTE; muting stops notifications for the room while the unread
     * badge stays. A StateFlow — the thread keeps it in sync with other
     * devices while the panel is open (LP3 feedback 2026-08-28).
     */
    private val muted: StateFlow<Boolean> = MutableStateFlow(false),
    /** Flips the room's mute server-side; the panel mirrors the new state locally. */
    private val onToggleMute: () -> Unit = {},
    /**
     * Pin state (2026-08-28): the PIN button reads PIN / UNPIN; pinned chats
     * sort to the top of the room list and their rows drop the latest
     * timestamp. StateFlow like [muted].
     */
    private val pinned: StateFlow<Boolean> = MutableStateFlow(false),
    /** Flips the room's pin server-side (m.favourite tag); the panel mirrors locally. */
    private val onTogglePin: () -> Unit = {},
    /**
     * Archive state (2026-08-28): the ARCHIVE button reads ARCHIVE /
     * UNARCHIVE; archived rooms hide from the main list and go silent,
     * reachable only via search VIEW ALL. StateFlow like [muted].
     */
    private val archived: StateFlow<Boolean> = MutableStateFlow(false),
    /** Flips the room's archive server-side (Beeper inbox.done); the panel mirrors locally. */
    private val onToggleArchive: () -> Unit = {},
) : SimpleLightScreen<Unit>(sealedActivity) {

    /** One full-width toggle button in the stacked block (LP3 feedback 2026-08-28). */
    @Composable
    private fun ToggleButton(label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 0.5f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = label, variant = LightTextVariant.Button)
        }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val isMuted by muted.collectAsState()
        val isPinned by pinned.collectAsState()
        val isArchived by archived.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // Identity block centered in the upper area — the toggles live
                // in their own lower block, so the name stays put (LP3
                // feedback 2026-08-28: adding buttons had pushed it up). The
                // 1.12:1 weight ratio puts the toggle group's center at the
                // midpoint between the X and the name (measured 2026-08-29).
                Box(
                    modifier = Modifier
                        .weight(1.12f)
                        .fillMaxWidth(),
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
                        // The id (number/username) as the second line, the same
                        // size as the name; the network tag stays small
                        // underneath (feedback 2026-09-01: the id came back off
                        // the network line onto its own Heading line, the
                        // network stays the Detail subtext). Groups with no id
                        // show the community name in this slot — except the
                        // community's own room, where the community IS the name
                        // (LP3 2026-09-01).
                        (identifier ?: phone ?: community?.takeUnless {
                            it.equals(name, ignoreCase = true)
                        })?.takeIf { it.isNotBlank() }?.let {
                            LightText(
                                text = it,
                                variant = LightTextVariant.Heading,
                                align = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        network?.takeIf { it.isNotBlank() }?.let {
                            LightText(
                                text = it,
                                variant = LightTextVariant.Detail,
                                // Solid white — the network line reads like the
                                // name above it, not dimmed (feedback
                                // 2026-08-27).
                                align = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
                // Toggle block (LP3 feedback 2026-08-28): three full-width
                // buttons stacked on top of each other, top-aligned in the
                // lower half so the group sits right under the identity block —
                // that lands the buttons' center at the midpoint between the
                // X and the name text ("always centered between the X and the
                // text above the network subtext"), with a tight 0.25gu gap
                // between buttons. Identity and toggles both weight 1f, so the
                // split is stable whatever the button labels or screen size.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.25f.gridUnitsAsDp()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ToggleButton(if (isPinned) "UNPIN" else "PIN", onTogglePin)
                        ToggleButton(if (isMuted) "UNMUTE" else "MUTE", onToggleMute)
                        ToggleButton(if (isArchived) "UNARCHIVE" else "ARCHIVE", onToggleArchive)
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        null,
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            // Pop with a Unit result so the caller's navigateTo
                            // callback fires — the room list's panel stops its
                            // flag poll on dismissal (ThreadScreen pattern, 2026-08-29).
                            onClick = { goBack(Unit) },
                            contentDescription = "Close contact",
                        ),
                        null,
                    ),
                )
            }
        }
    }
}
