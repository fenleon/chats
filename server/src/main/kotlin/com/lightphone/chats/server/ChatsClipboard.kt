package com.lightphone.chats.server

import android.content.ClipboardManager
import android.content.Context

/**
 * App-clipboard access for copy/paste. Bound at bootstrap like
 * [PlatformRelay] — the tool module can't touch Context/ClipboardManager
 * (plugin-scanned), so the server holds the context and serves reads/writes.
 * Clipboard ops are fast; the calls are plain functions, not suspend.
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

    @Volatile private var appContext: Context? = null

    @Volatile private var copied: String? = null

    /** Call once at server bootstrap. */
    fun bind(context: Context) {
        val app = context.applicationContext
        appContext = app
        // A copy from another app invalidates our shadowed copy (the system
        // clip changed → the newest copy is theirs, not ours).
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.addPrimaryClipChangedListener { copied = null }
    }

    /** The clipboard's text, or null when it holds no text. */
    fun getText(): String? {
        copied?.takeIf { it.isNotEmpty() }?.let { return it }
        val manager = appContext
            ?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        return manager.primaryClip?.getItemAt(0)
            ?.coerceToText(appContext)?.toString()
            ?.takeIf { it.isNotEmpty() }
    }

    fun setText(text: String) {
        copied = text
    }
}
