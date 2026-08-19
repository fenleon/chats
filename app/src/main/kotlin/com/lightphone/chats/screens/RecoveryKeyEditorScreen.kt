package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.scaledForScreenHeight
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Recovery-key entry matching Beeper's format: letters/digits grouped four at
 * a time with dashes, three lines of four groups each (48 chars = 12 groups),
 * inserted live as the user types. The dashes/newlines are display-only
 * (recomputed on every change), so backspace never deletes one and a pasted
 * key in any format — spaces, dashes, raw — normalizes to the same shape. Case
 * is preserved (the key is case-sensitive). Submits the clean 48-character key
 * (letters/digits only).
 */
class RecoveryKeyEditorScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        // Fixed options — no remote fetch, so the mic/emoji/return keys stay
        // off (the passes code-entry style, feedback 2026-08-19).
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
        val textState = rememberTextFieldState("")
        // TextFieldCharSequence: snapshot-observed when read in composition
        // (not a State — it cannot be delegated with `by`).
        val text = textState.text

        // Reformat whenever the keyboard (or a paste) changes the text. The
        // canonical form is idempotent, so this settles after one pass.
        LaunchedEffect(text.toString()) {
            val raw = text.toString()
            val formatted = formatRecoveryKey(raw)
            if (formatted != raw) {
                val cursor = textState.selection.min
                textState.edit {
                    replace(0, length, formatted)
                    selection = TextRange(mapRecoveryCursor(raw, cursor, formatted))
                }
            }
        }

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = recoveryTitle(text.toString()),
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                // Notes-style entry: the key sits just above the keyboard in
                // small centered text, lines growing upward. The typography
                // tokens carry no color, so copy the active content color —
                // without it BasicText falls back to black-on-black on the dark
                // theme and the key is unreadable. Copy-sized (was paragraph) —
                // feedback 2026-08-19: slightly bigger, the 3-line key still
                // fits above the keyboard.
                inputTextStyle = LightThemeTokens.typography.copy
                    .copy(color = themeColors.content, textAlign = TextAlign.Center)
                    .scaledForScreenHeight(),
                onSubmit = { result ->
                    // Submit the clean key (case preserved, no separators).
                    goBack(result.toString().filter { it.isLetterOrDigit() })
                },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                // Feedback pass: the action lives in the bottom bar (SUBMIT —
                // bar text buttons are uppercase), the key stays bottom-anchored.
                bottomAligned = true,
            )
        }
    }

    /** "Recovery key" alone, then with a quiet group counter once typing starts. */
    private fun recoveryTitle(raw: String): String {
        val chars = raw.count { it.isLetterOrDigit() }.coerceAtMost(MAX_KEY_CHARS)
        return if (chars == 0) "Recovery key" else "Recovery key · ${chars / 4}/12"
    }

    companion object {
        private const val GROUPS_PER_LINE = 4
        private const val MAX_KEY_CHARS = 48

        /**
         * Letters/digits only, four per group, dash-separated, three lines of
         * four groups each (48 chars = 12 groups):
         *
         *     AAAA-BBBB-CCCC-DDDD
         *     EEEE-FFFF-GGGG-HHHH
         *     IIII-JJJJ-KKKK-LLLL
         *
         * Input and paste are capped at 48 characters, so the counter tops out
         * at 12/12 and the display never exceeds three lines.
         */
        fun formatRecoveryKey(raw: String): String =
            raw.filter { it.isLetterOrDigit() }
                .take(MAX_KEY_CHARS)
                .chunked(4)
                .chunked(GROUPS_PER_LINE)
                .joinToString("\n") { it.joinToString("-") }

        /** Maps a cursor position in [raw] to its position in [formatted] by
         *  counting surviving (letter/digit) characters before the cursor. */
        fun mapRecoveryCursor(raw: String, cursor: Int, formatted: String): Int {
            val survivorsBefore = raw.take(cursor).count { it.isLetterOrDigit() }
            var seen = 0
            formatted.forEachIndexed { index, c ->
                if (c.isLetterOrDigit()) {
                    if (seen == survivorsBefore) return index
                    seen++
                }
            }
            return formatted.length
        }
    }
}
