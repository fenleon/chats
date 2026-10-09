package com.lightphone.chats.server

import com.thelightphone.sdk.SealedLightContext

/**
 * App-clipboard access for copy/paste. Bound at bootstrap ([ChatClient.bootstrap]
 * → [bind]) with the screen's [SealedLightContext] — the SDK's LightClipboard
 * wrap is what actually touches the system service (the tool plugin bans
 * getSystemService in tool code). Clipboard ops are fast; the calls are plain
 * functions, not suspend.
 *
 * COPY does NOT touch the system clipboard: Android 13+ pops an unsuppressible
 * system bubble on every setPrimaryClip (only the default IME is exempt), and
 * the LP3 feedback wants the copy confirmed by the tool's own flash panel
 * alone (2026-09-09). The copy lives in [copied] instead; PASTE reads it
 * first and falls back to the system clipboard so content copied in OTHER
 * apps still pastes. Ceiling: a chats copy is not pasteable into other apps —
 * re-add the system write (and accept the bubble) if that is ever missed.
 */
object ChatsClipboard {

    @Volatile private var lightContext: SealedLightContext? = null

    @Volatile private var copied: String? = null

    /** Call once at bootstrap. */
    fun bind(slc: SealedLightContext) {
        lightContext = slc
        // A copy from another app invalidates our shadowed copy (the system
        // clip changed → the newest copy is theirs, not ours).
        slc.clipboard.addChangedListener { copied = null }
    }

    /** The clipboard's text, or null when it holds no text. */
    fun getText(): String? {
        copied?.takeIf { it.isNotEmpty() }?.let { return it }
        return lightContext?.clipboard?.text
    }

    fun setText(text: String) {
        copied = text
    }
}
