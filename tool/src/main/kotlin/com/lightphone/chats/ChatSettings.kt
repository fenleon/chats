package com.lightphone.chats

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Tool-local settings, persisted in the SDK's DataStore
 * ([SealedLightContext.dataStore]). All are plain on/off toggles (default
 * ON) shown under Settings → Features; the screens read the flows.
 */
object ChatSettings {

    /** Whether the thread shows "seen" under outgoing messages. */
    val showReadStatus = MutableStateFlow(true)

    /** Data Saver Mode: true = media downloads restricted to Wi-Fi. The Settings
     *  toggle shows the inverse of this flag (checked = saver ON). Defaults to
     *  mobile-allowed (feedback 2026-08-19: "Data Saver Mode … default OFF"). */
    val downloadOverMobile = MutableStateFlow(true)

    /** Reactions: the "… reacted" tags and the LIKE/REACT actions. */
    val showReactions = MutableStateFlow(true)

    /** Latest-message timestamps on the main room list. */
    val showTimestamps = MutableStateFlow(true)

    /** Render formatted (markdown) message bodies as bold/italic/bullets. */
    val showMarkdown = MutableStateFlow(true)

    private val KEYS: Map<MutableStateFlow<Boolean>, Preferences.Key<Boolean>> = mapOf(
        showReadStatus to booleanPreferencesKey("chats.show_read_status"),
        downloadOverMobile to booleanPreferencesKey("chats.download_over_mobile"),
        showReactions to booleanPreferencesKey("chats.show_reactions"),
        showTimestamps to booleanPreferencesKey("chats.show_timestamps"),
        showMarkdown to booleanPreferencesKey("chats.show_markdown"),
    )

    private var loaded = false

    /** Loads the persisted values once (idempotent); call from any screen's scope. */
    suspend fun load(lightContext: SealedLightContext) {
        if (loaded) return
        loaded = true
        runCatching {
            val prefs = lightContext.dataStore.data.first()
            for ((flow, key) in KEYS) flow.value = prefs[key] ?: true
        }
    }

    /** Persists and publishes a toggle value. */
    suspend fun set(lightContext: SealedLightContext, flow: MutableStateFlow<Boolean>, value: Boolean) {
        flow.value = value
        runCatching {
            lightContext.dataStore.edit { it[KEYS.getValue(flow)] = value }
        }
    }
}
