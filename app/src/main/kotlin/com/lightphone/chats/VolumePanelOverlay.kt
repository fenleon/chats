package com.lightphone.chats

// SHARED RESOURCE (canonical copy): tools/volume-panel/VolumePanelOverlay.kt
// Apps receive this file via `tools/sync-volume-panel` (rewrites the package
// line only) — never edit an app's copy by hand; change this file and re-run
// the script. See tools/volume-panel/README.md.

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.delay

/** What the volume panel is showing. */
sealed interface VolumePanelState {
    /** Silent — "silent" label, small cross-speaker glyph where the bar would be. */
    data object Silent : VolumePanelState

    /** Vibrate-only — "vibrate only" label over the empty rail. */
    data object Vibrate : VolumePanelState

    /** Ringer at [level] (1..7) — "ringer" label, rail, and the first [level] steps filled. */
    data class Ringer(val level: Int) : VolumePanelState

    /** Media volume at [level] of [max] — "volume" label and the first [level] steps filled. */
    data class Media(val level: Int, val max: Int) : VolumePanelState
}

/**
 * Replica of the LightOS volume panel — the full-screen overlay shown when the
 * volume rocker is pressed. Measured from the real LP3 (1080x1240 @ 420 dpi):
 * white-on-black; the state label (lowercase, at the native's full heading
 * size — "ringer" / "vibrate only" / "silent" / "volume") centered at
 * y≈445–547; a thin 1.5dp rail at y≈250dp carrying the step bar (≈43.6dp per
 * ringer step, filled left→right; the media panel's bar scales by its max);
 * the "…" Notifications shortcut at y≈442dp on the ringer states. Silent shows
 * a small cross-speaker glyph (SDK `SPEAKER_MUTED`) where the bar would be.
 *
 * The LightOS-native panel is not reachable from SDK tools yet (no server
 * API), so this overlay is the in-app stand-in. Show it by setting [state]
 * non-null; it appears instantly (no fade — it is a system overlay, not an
 * app transition) and dismisses itself after [PANEL_DURATION_MS] via
 * [onDismiss].
 *
 * White/black + fixed dp are deliberate: this replicates a system overlay
 * (independent of the app theme), not an app screen.
 */
@Composable
fun VolumePanelOverlay(
    state: VolumePanelState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state != null) {
        LaunchedEffect(state) {
            delay(PANEL_DURATION_MS)
            onDismiss()
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(LabelTop))
                StateLabel(state)
                Spacer(modifier = Modifier.height(LabelToRailGap))
                RailZone(state)
                // The ringer states keep LightOS's "…" Notifications shortcut;
                // the media panel is simpler — just the label and the rail.
                if (state !is VolumePanelState.Media) {
                    Spacer(modifier = Modifier.height(RailToDotsGap))
                    // LightOS's "…" opens the native Notifications panel; not wired yet.
                    Image(
                        painter = painterResource(LightIcons.ELLIPSES.drawableResource),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.size(width = DotsWidth, height = DotsHeight),
                    )
                }
            }
        }
    }
}

/**
 * The state label — lowercase, at the native panel's full heading size (the
 * SDK's scaled Heading renders smaller than the native label; the panel is a
 * fixed replica, so the label uses the raw heading style).
 */
@Composable
private fun StateLabel(state: VolumePanelState) {
    Text(
        text = when (state) {
            is VolumePanelState.Ringer -> "ringer"
            VolumePanelState.Vibrate -> "vibrate only"
            VolumePanelState.Silent -> "silent"
            is VolumePanelState.Media -> "volume"
        },
        color = Color.White,
        style = LightThemeTokens.typography.heading.copy(fontSize = LabelFontSize),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The volume rail: thin full-width track; the 7-step bar (contiguous steps,
 * left-aligned) fills as the ringer level rises. Vibrate shows the empty
 * track; Silent swaps it for the small cross-speaker glyph.
 */
@Composable
private fun RailZone(state: VolumePanelState) {
    Box(
        modifier = Modifier
            .width(RailWidth)
            .height(RailZoneHeight),
    ) {
        if (state is VolumePanelState.Silent) {
            Image(
                painter = painterResource(LightIcons.SPEAKER_MUTED.drawableResource),
                contentDescription = null, // the "silent" label carries the state
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(width = SilentGlyphWidth, height = SilentGlyphHeight)
                    .align(Alignment.Center),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RailTrackHeight)
                    .align(Alignment.Center)
                    .background(Color.White),
            )
            if (state is VolumePanelState.Ringer) {
                val level = state.level.coerceIn(1, 7)
                if (level > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(level / 7f)
                            .height(RailHeight)
                            .align(Alignment.CenterStart)
                            .background(Color.White),
                    )
                }
            } else if (state is VolumePanelState.Media) {
                val level = state.level.coerceIn(0, state.max.coerceAtLeast(1))
                if (level > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(level.toFloat() / state.max.coerceAtLeast(1))
                            .height(RailHeight)
                            .align(Alignment.CenterStart)
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

// Geometry measured from the real LP3 panel (1080x1240 @ 420 dpi → px / 2.625).
private const val PANEL_DURATION_MS = 1_000L
/** The native label's full heading size (unscaled — matches the LP3 panel). */
private val LabelFontSize = 38.sp
private val LabelTop = 169.5.dp          // label box top ≈ 445 px
private val LabelToRailGap = 26.3.dp     // label line box bottom → rail zone top
private val RailZoneHeight = 27.4.dp     // tall enough for the silent glyph (72 px)
private val RailWidth = 305.dp           // 800 px (x 140-939)
private val RailHeight = 7.6.dp          // 20 px (filled steps)
private val RailTrackHeight = 1.5.dp     // 4 px (the thin rail line)
private val SilentGlyphWidth = 29.3.dp   // ~77 px
private val SilentGlyphHeight = 27.4.dp  // ~72 px
private val RailToDotsGap = 178.8.dp     // rail zone bottom → dots top (442 px)
private val DotsWidth = 29.dp            // ~77 px
private val DotsHeight = 8.dp            // ~20 px
