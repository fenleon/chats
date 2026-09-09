package com.lightphone.chats.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.chats.ChatClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Unsent composer drafts, keyed by room id: leaving the
 * composer mid-draft restores the text on return — thread → list → thread —
 * until it's sent or cleared. Process-scoped; a restart starts empty.
 */
internal val composerDrafts = mutableMapOf<String, String>()

/**
 * What the thread needs to show the sent message immediately (optimistic
 * echo): the text, the timeline event id once the homeserver acked (a
 * "local-…" fallback until then), and a timestamp.
 */
data class ComposerResult(
    val body: String,
    val id: String,
    val timestampMs: Long,
)

class ComposerViewModel(
    private val roomId: String,
    /** When set, the composer EDITS this message:
     *  SEND routes to [ChatClient.editMessage] and pops back with the edit
     *  result instead of sending a new message. */
    private val editTarget: LightServiceMethod.GetMessages.Message? = null,
    /** The message SEND replies to (m.in_reply_to); mutable state so the
     *  header's tap cancels the reply back to a plain compose. */
    replyTarget: LightServiceMethod.GetMessages.Message? = null,
) : LightViewModel<ComposerResult>() {

    val busy = MutableStateFlow(false)

    /** Failure message from the last send/edit (null = none) — displayed so a
     *  rejected send reads as an error, not a silent "still sending". */
    val error = MutableStateFlow<String?>(null)

    /** Event id the send goes out as a reply to; null once cancelled. */
    val replyToEventId = MutableStateFlow(replyTarget?.id)

    fun cancelReply() {
        replyToEventId.value = null
    }

    override fun onScreenShow(screen: SimpleLightScreen<ComposerResult>) {
        super.onScreenShow(screen)
        // The composer is the only place the tool types; announce it for the
        // whole time the screen is up, until the message is sent or dismissed.
        // An edit is not typing — no indicator for the contact.
        if (editTarget == null) {
            viewModelScope.launch { ChatClient.setTyping(roomId, true) }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<ComposerResult>) {
        super.onScreenHide(screen)
        if (editTarget == null) {
            viewModelScope.launch { ChatClient.setTyping(roomId, false) }
        }
    }

    /** Sends the draft (or applies the edit); pops back with a
     *  [ComposerResult] on success, stays on failure so the text survives for
     *  a retry. */
    fun send(text: CharSequence, screen: SimpleLightScreen<ComposerResult>) {
        val body = text.toString().trim()
        if (body.isEmpty() || busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                if (editTarget != null) {
                    val editError = ChatClient.editMessage(roomId, editTarget.id, body)
                    if (editError == null) {
                        // Same event id — the thread's optimistic edit overlay
                        // keys off it.
                        screen.goBack(ComposerResult(body, editTarget.id, editTarget.timestampMs))
                    } else {
                        error.value = editError
                    }
                } else {
                    val response = ChatClient.sendMessage(roomId, body, replyToEventId.value)
                    if (response != null) {
                        screen.goBack(
                            ComposerResult(
                                body = body,
                                id = response.eventId ?: "local-${response.transactionId}",
                                timestampMs = System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        error.value = "couldn't send — check connection and try again"
                    }
                }
            } finally {
                // A failed/exception RPC must not leave the busy flag set —
                // every later press would silently return and Send would look
                // dead.
                ChatClient.setTyping(roomId, false)
                busy.value = false
            }
        }
    }
}

class ComposerScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
    private val roomName: String,
    /** When set, the composer prefills this message's body and SEND edits it
     *  (Phase C, 2026-09-03, opened from the thread's context window). */
    private val editTarget: LightServiceMethod.GetMessages.Message? = null,
    /** When set (and not editing), SEND sends this draft as a reply to the
     *  message (m.in_reply_to); the one-line header above the input cancels
     *  on tap. */
    private val replyTarget: LightServiceMethod.GetMessages.Message? = null,
) : LightScreen<ComposerResult, ComposerViewModel>(sealedActivity) {

    override val viewModelClass: Class<ComposerViewModel>
        get() = ComposerViewModel::class.java

    override fun createViewModel(): ComposerViewModel =
        ComposerViewModel(roomId, editTarget, replyTarget)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        // The LP3 keyboard's mic key is handled inside the closed light-keyboard
        // library (it needs a speech-recognition service, which LightOS doesn't
        // ship) — it does nothing here. Voice notes have their own button on
        // the thread, so hide the dead key instead of showing a
        // control that can't act. The return key stays —
        // it inserts a newline rather than sending (submitOnReturn = false):
        // messages may span lines, and SEND lives in the top bar.
        val keyboardOptionsFlow = remember {
            MutableStateFlow(defaultKeyboardOptions().copy(displayVoice = false, displayReturn = true))
        }
        // Restore the room's unsent draft; the composer
        // saves every change back to [composerDrafts] so leaving mid-draft
        // keeps the text until it's sent or cleared. An edit
        // prefills the row's body instead and never touches the draft — a
        // cancelled edit must not leak into the next normal composer.
        val textState = rememberTextFieldState(editTarget?.body ?: composerDrafts[roomId] ?: "")
        LaunchedEffect(textState.text) {
            if (editTarget != null) return@LaunchedEffect
            val text = textState.text.toString()
            if (text.isEmpty()) composerDrafts.remove(roomId) else composerDrafts[roomId] = text
        }
        // Half-panel COPY/PASTE (feedback 2026-09-09): holding in the composer
        // opens the black panel — COPY takes the whole draft, PASTE inserts the
        // clipboard at the cursor/selection. The clipboard is read once per
        // panel open (a paste needs no live tracking). The panel covers the
        // bottom half only, so the text above stays tappable for cursor moves.
        var showActions by remember { mutableStateOf(false) }
        var clipboardText by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(showActions) {
            if (showActions) clipboardText = ChatClient.clipboardText()
        }
        // COPY confirmation flash — shared with the thread's context window.
        var copyFlash by remember { mutableStateOf(false) }
        // Reply state (title + toptag; the tap on the toptag cancels).
        val replyingTo by viewModel.replyToEventId.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Hold anywhere in the composer → the COPY/PASTE panel.
                        // The text field sees events first; a long-press it
                        // consumes (native selection) opens nothing here.
                        detectTapGestures(onLongPress = { showActions = true })
                    },
            ) {
                LightTextInputEditor(
                    // An edit announces itself in the title slot (the room
                    // name's place); back (below) cancels it. A reply reads
                    // "Replying To" (feedback 2026-09-09) — the quoted text
                    // rides on the toptag above the input.
                    title = when {
                        editTarget != null -> "Editing Message"
                        replyingTo != null -> "Replying To"
                        else -> roomName
                    },
                    state = textState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = { viewModel.send(it, this@ComposerScreen) },
                    onBack = { goBack() },
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                    submitLabel = "Send",
                    submitIcon = LightIcons.SEND,
                    // Notes-style entry (feedback pass): small wrapping text
                    // anchored at the bottom, growing upward, keyboard flush at
                    // the bottom; the return key makes newlines, not sends.
                    // Send lives in the top-right bar.
                    // The keyboard opens in caps mode — a new message starts
                    // with a capital letter like the native composer.
                    singleLine = false,
                    submitOnReturn = false,
                    bottomAligned = true,
                    submitInTopBar = true,
                    topBarSubmitIcon = LightIcons.SEND,
                    initialCaps = true,
                )
                // Reply toptag (feedback 2026-09-09, the Radio hint grammar):
                // the quoted text sits directly above the input line — one
                // Superfine line "sender · excerpt", a tap cancels the reply.
                if (replyingTo != null) {
                    // The full target rides on the constructor param (the VM
                    // tracks only the id); blank-sender own rows render the
                    // excerpt alone.
                    val header = replyTarget?.let { m ->
                        val excerpt = m.body.lineSequence()
                            .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                        listOfNotNull(
                            m.senderName.takeIf { it.isNotBlank() },
                            excerpt.takeIf { it.isNotEmpty() },
                        ).joinToString(" · ")
                    }
                    LightText(
                        text = header ?: "replying",
                        variant = LightTextVariant.Superfine,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .imePadding()
                            .padding(start = 1f.gridUnitsAsDp(), bottom = 8f.gridUnitsAsDp())
                            .lightClickable { viewModel.cancelReply() },
                    )
                }
                // Half-panel COPY/PASTE over the keyboard zone; the composer
                // text above it stays interactive (cursor moves are taps on the
                // text, and PASTE inserts at the cursor/selection).
                if (showActions) {
                    ComposerActionsOverlay(
                        canCopy = textState.text.isNotEmpty(),
                        canPaste = clipboardText != null,
                        onCopy = {
                            ChatClient.copyToClipboard(textState.text.toString())
                            showActions = false
                            copyFlash = true
                        },
                        onPaste = {
                            clipboardText?.let { clip ->
                                textState.edit {
                                    val start = selection.min
                                    val end = selection.max
                                    replace(start, end, clip)
                                    selection = TextRange(start + clip.length)
                                }
                            }
                            showActions = false
                        },
                        onDismiss = { showActions = false },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                CopyFlashOverlay(visible = copyFlash, onDismiss = { copyFlash = false })
                // Quiet failure line (same grammar as the thread's row error):
                // a rejected send/edit shows here instead of reading as an
                // eternal "sending". Cleared on the next send attempt.
                viewModel.error.collectAsState().value?.let { message ->
                    LightText(
                        text = message,
                        variant = LightTextVariant.Superfine,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .imePadding()
                            .padding(start = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
                    )
                }
                // Clear-draft X, bottom-right corner of the screen. The composer keyboard
                // (submitInTopBar) reserves the 5-gu bottom-bar row below the
                // keys, so the X sits in that row at the far right, vertically
                // centered like a native bottom-bar icon. Always visible
                // while the composer is open; with an
                // empty draft it's a harmless no-op.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // Keeps the X above the keyboard when the system IME
                        // is in use (no-op with the embedded keyboard — the
                        // IME never shows, so imePadding is 0).
                        .imePadding()
                        .padding(end = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp())
                        .lightClickable {
                            textState.edit { replace(0, length, "") }
                        }
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightIcon(
                        icon = LightIcons.CLOSE,
                        // Same size as the bottom-row icons (2 gu — the SDK's
                        // bar-button icon size; feedback 2026-08-21: was 1.5f).
                        size = 2f,
                        contentDescription = "Clear draft",
                    )
                }
            }
        }
    }
}

/**
 * The composer's half-panel COPY/PASTE (feedback 2026-09-09): the context
 * window's panel grammar — a black panel over the bottom half — for the
 * composer's own text. COPY takes the whole draft, PASTE inserts the
 * clipboard at the cursor/selection; rows render only when they can act. The
 * composer text above the panel stays interactive (cursor moves).
 * Raw black/white is deliberate: same system-panel replica as
 * [ContextWindowOverlay].
 */
@Composable
private fun ComposerActionsOverlay(
    canCopy: Boolean,
    canPaste: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .background(Color.Black)
            // Swallow every event in the panel's area (taps under it — the
            // clear-draft X, the error line — must not fire through).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 38.dp), // chevron zone, as in the context window
            verticalArrangement = Arrangement.Center,
        ) {
            if (canCopy) OverlayActionRow("COPY", onCopy)
            if (canPaste) OverlayActionRow("PASTE", onPaste)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .height(38.dp)
                .lightClickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(com.lightphone.chats.R.drawable.ic_lp3_chevron_down),
                contentDescription = "Close",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.size(width = 17.dp, height = 10.dp),
            )
        }
    }
}

/** One centered panel row (the context window's row grammar). */
@Composable
private fun OverlayActionRow(label: String, action: () -> Unit) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.lightClickable(onClick = action),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = label, variant = LightTextVariant.Button, maxLines = 1)
        }
    }
}
