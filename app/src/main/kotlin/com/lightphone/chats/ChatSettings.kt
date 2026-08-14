package com.lightphone.chats

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Tool-local settings, persisted in the SDK's DataStore
 * ([SealedLightContext.dataStore]). The seen/delivered marker preference lives
 * here: Settings toggles it, the thread reads the flow to decide whether
 * outgoing messages carry a "seen"/"delivered" line.
 */
object ChatSettings {

    private val KEY_SHOW_READ_STATUS = booleanPreferencesKey("chats.show_read_status")
    private val KEY_DOWNLOAD_OVER_MOBILE = booleanPreferencesKey("chats.download_over_mobile")

    /** Whether the thread shows "seen"/"delivered" under outgoing messages. Default on. */
    val showReadStatus = MutableStateFlow(true)

    /** Whether photo downloads are allowed on the mobile-data connection. Default off
     *  (Wi-Fi only — the data-conscious default). */
    val downloadOverMobile = MutableStateFlow(false)

    private var loaded = false

    /** Loads the persisted values once (idempotent); call from any screen's scope. */
    suspend fun load(lightContext: SealedLightContext) {
        if (loaded) return
        loaded = true
        runCatching {
            val prefs = lightContext.dataStore.data.first()
            showReadStatus.value = prefs[KEY_SHOW_READ_STATUS] ?: true
            downloadOverMobile.value = prefs[KEY_DOWNLOAD_OVER_MOBILE] ?: false
        }
    }

    /** Persists and publishes the show-read-status toggle value. */
    suspend fun setShowReadStatus(lightContext: SealedLightContext, value: Boolean) {
        showReadStatus.value = value
        runCatching {
            lightContext.dataStore.edit { it[KEY_SHOW_READ_STATUS] = value }
        }
    }

    /** Persists and publishes the mobile-data-downloads toggle value. */
    suspend fun setDownloadOverMobile(lightContext: SealedLightContext, value: Boolean) {
        downloadOverMobile.value = value
        runCatching {
            lightContext.dataStore.edit { it[KEY_DOWNLOAD_OVER_MOBILE] = value }
        }
    }
}
