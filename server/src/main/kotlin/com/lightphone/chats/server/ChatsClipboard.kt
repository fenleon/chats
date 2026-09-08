package com.lightphone.chats.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * App-clipboard access for copy/paste. Bound at bootstrap like
 * [PlatformRelay] — the tool module can't touch Context/ClipboardManager
 * (plugin-scanned), so the server holds the context and serves reads/writes.
 * Clipboard ops are fast; the calls are plain functions, not suspend.
 */
object ChatsClipboard {

    @Volatile private var appContext: Context? = null

    /** Call once at server bootstrap. */
    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    /** The clipboard's text, or null when it holds no text. */
    fun getText(): String? {
        val manager = appContext
            ?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        return manager.primaryClip?.getItemAt(0)
            ?.coerceToText(appContext)?.toString()
            ?.takeIf { it.isNotEmpty() }
    }

    fun setText(text: String) {
        val manager = appContext
            ?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        manager.setPrimaryClip(ClipData.newPlainText("chats", text))
    }
}
