package com.lightphone.chats.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightphone.chats.R
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable

/** What the context window is showing for the long-pressed message. */
private enum class ContextLevel {
    /** The stacked action rows (received: LIKE/REACT; own: EDIT/UNSEND). */
    Actions,

    /** The emoji grid the REACT row opens. */
    Reactions,
}

/**
 * The reaction keys, in the LP3 emoji panel's exact layout (3 rows x 8,
 * captured from the Phone tool's emoji panel 2026-09-03): row 1 faces, row 2
 * hands, row 3 symbols. The string is the Matrix reaction key — it must stay
 * byte-identical everywhere (send + unsend + the served tag match).
 */
private val REACTION_ROWS = listOf(
    listOf("😅", "😊", "🙄", "😍", "😜", "😂", "😭", "😎"),
    listOf("👏", "👍", "👎", "🤞", "✌️", "👌", "👋", "🙏"),
    listOf("✨", "🔥", "❤️", "💔", "🏆", "🎯", "👑", "👀"),
)

/**
 * The context window (Phase B, 2026-09-03): a black panel over the bottom
 * half of the screen for the long-pressed message — the Phone tool's overlay
 * panel presentation (measured from the LP3, 1080x1240 @ 480 dpi). Top level
 * stacks the action rows; REACT / EDIT REACTION open the 3x8 emoji grid; a
 * tap sets that reaction. Every completing action dismisses the panel. The
 * wide thin chevron at the very bottom center dismisses (any level).
 *
 * One own reaction at a time (replace semantics) on a RECEIVED message: no
 * own reaction shows LIKE MESSAGE + REACT; an existing one shows EDIT
 * REACTION + REMOVE REACTION. Own messages (Phase C, 2026-09-03) show EDIT
 * MESSAGE / UNSEND MESSAGE instead — each only when the row still allows it
 * ([LightServiceMethod.GetMessages.Message.canEdit] / `canUnsend`, the
 * bridge's capability gate).
 *
 * Raw black/white + fixed sizes are deliberate: this replicates a system
 * overlay panel (see [com.lightphone.chats.VolumePanelOverlay]), not themed
 * app UI. The panel covers the bottom bar while open — the Phone tool's does
 * the same.
 */
@Composable
fun ContextWindowOverlay(
    message: LightServiceMethod.GetMessages.Message?,
    ownReaction: String?,
    onLike: () -> Unit,
    onReact: (String) -> Unit,
    onRemoveReaction: () -> Unit,
    onEdit: () -> Unit,
    onUnsend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    // Declared after the null check: closing the panel drops this slot, so a
    // reopened window always starts at the action rows.
    var level by remember { mutableStateOf(ContextLevel.Actions) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .background(Color.Black),
    ) {
        when (level) {
            // LP3 feedback 2026-09-03: the action rows read like bottom-bar
            // text buttons — the [LightTextVariant.Button] label, centered in
            // the row, rows sharing the panel height (centered vertically),
            // instead of the left-aligned Heading list rows.
            ContextLevel.Actions -> {
                val rows: List<Pair<String, () -> Unit>> = when {
                    // Own message: the message controls, each only while the
                    // row still allows it (bridge caps / window).
                    message.isMine -> buildList {
                        if (message.canEdit) add("EDIT MESSAGE" to { onEdit(); onDismiss() })
                        if (message.canUnsend) add("UNSEND MESSAGE" to { onUnsend(); onDismiss() })
                    }
                    ownReaction == null -> listOf(
                        "LIKE" to { onLike(); onDismiss() },
                        "REACT" to { level = ContextLevel.Reactions },
                    )
                    else -> listOf(
                        "EDIT REACTION" to { level = ContextLevel.Reactions },
                        "REMOVE REACTION" to { onRemoveReaction(); onDismiss() },
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // The chevron zone stays clear so a row's tap target
                        // never sits under it.
                        .padding(bottom = ChevronZone),
                    // Bottom-bar-height rows, grouped at the panel's center —
                    // full-height weight(1f) rows sat too far apart (LP3
                    // feedback 2026-09-03).
                    verticalArrangement = Arrangement.Center,
                ) {
                    rows.forEach { (label, action) ->
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .fillMaxWidth()
                                .lightClickable(onClick = action),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(
                                text = label,
                                variant = LightTextVariant.Button,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            ContextLevel.Reactions -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                REACTION_ROWS.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().height(EmojiCell)) {
                        row.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .lightClickable(onClick = {
                                        onReact(key)
                                        onDismiss()
                                    }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = key, fontSize = EmojiFontSize)
                            }
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(48.dp, 38.dp)
                .lightClickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_lp3_chevron_down),
                contentDescription = "Close",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.size(width = 17.dp, height = 10.dp),
            )
        }
    }
}

// Geometry measured from the LP3 emoji panel (1080x1240 @ 480 dpi → px / 3).
/** Emoji cell height — row centers sit ~140 px (46.7 dp) apart. */
private val EmojiCell = 46.dp
/** The LP3 panel's glyphs at ~32 dp were too big in practice (LP3 feedback
 *  2026-09-03) — one size down. */
private val EmojiFontSize = 24.sp
/** The chevron's tap zone at the panel's bottom edge; action rows keep clear
 *  of it. The chevron itself touches the bottom edge (LP3 feedback
 *  2026-09-03) — no float above it. */
private val ChevronZone = 38.dp
