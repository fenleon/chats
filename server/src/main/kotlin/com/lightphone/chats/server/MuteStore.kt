package com.lightphone.chats.server

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-room mute flags (chats, 2026-08-23): a muted room's messages stop
 * notifying; the unread badge and the room list stay. Stored in the app's
 * SharedPreferences — a local device preference, not synced to other Beeper
 * clients (per-account sync via Matrix push rules is out of scope;
 * ponytail: revisit if muting on the Linux client should mute here too).
 */
object MuteStore {
    private const val PREFS = "chats_account"
    private const val KEY_MUTED = "muted_rooms"

    @Volatile private var prefs: SharedPreferences? = null

    /** Call once from [MatrixRepository.init]. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isMuted(roomId: String): Boolean =
        prefs?.getStringSet(KEY_MUTED, emptySet())?.contains(roomId) ?: false

    fun setMuted(roomId: String, muted: Boolean) {
        val p = prefs ?: return
        val set = (p.getStringSet(KEY_MUTED, emptySet()) ?: emptySet()).toMutableSet()
        if (muted) set.add(roomId) else set.remove(roomId)
        p.edit().putStringSet(KEY_MUTED, set).apply()
    }
}
