package com.lightphone.chats

import android.view.KeyEvent
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The tool's last-known media volume — seeded from the server and updated on
 *  every rocker press, so the volume panel bar moves instantly (the server's
 *  adjust lands a binder round-trip later). */
object ChatVolumeState {
    var level: Int? = null
    var max: Int = 0
}

/**
 * Base view model for the main Chats screens (list, thread, composer,
 * settings): the shared volume-panel behavior, mirroring Audiobooks' pattern.
 *
 * The SDK delivers the LP3's volume rocker to the top screen's view model
 * first ([LightViewModel.onKeyDown]); the in-app volume panel shows instantly
 * (level computed locally from [ChatVolumeState] — no binder round-trip), then
 * the key falls through to the server, which adjusts the media stream.
 */
abstract class ChatLightViewModel<T> : LightViewModel<T>() {

    /** The volume panel's state (null = hidden). Hosted by each screen's root. */
    val volumePanel = MutableStateFlow<VolumePanelState?>(null)

    private var volumeWatcherJob: Job? = null

    fun dismissVolumePanel() {
        volumePanel.value = null
    }

    override fun onScreenShow(screen: SimpleLightScreen<T>) {
        super.onScreenShow(screen)
        // Keep the volume cache fresh (cheap; lets the panel bar move without
        // a round-trip on the next press).
        refreshVolumeLevel()
        startVolumeWatcher()
    }

    override fun onScreenHide(screen: SimpleLightScreen<T>) {
        super.onScreenHide(screen)
        stopVolumeWatcher()
    }

    override fun onAppPause() {
        stopVolumeWatcher()
        super.onAppPause()
    }

    /** While this screen is showing, wait for external media-volume changes (a
     *  connected BT device's own volume buttons — AVRCP) and surface them in
     *  the panel immediately via the server's long-poll. Stops on hide/pause,
     *  so it costs nothing in the background. A request is outstanding at most
     *  one at a time (the server blocks it until the volume changes or its
     *  ~2 s timeout). */
    private fun startVolumeWatcher() {
        if (volumeWatcherJob?.isActive == true) return
        volumeWatcherJob = viewModelScope.launch {
            while (isActive) {
                // A null cache (cold start) uses -1 so the first request
                // returns the current level immediately and just seeds it.
                val known = ChatVolumeState.level ?: -1
                val response = ChatClient.waitForVolumeChange(known) ?: continue
                if (ChatVolumeState.level != null && response.level != ChatVolumeState.level) {
                    volumePanel.value = VolumePanelState.Media(response.level, response.max)
                }
                ChatVolumeState.level = response.level
                ChatVolumeState.max = response.max
            }
        }
    }

    private fun stopVolumeWatcher() {
        volumeWatcherJob?.cancel()
        volumeWatcherJob = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            if (ChatVolumeState.level == null) {
                // Cold start: seed the cache first, then show the new level.
                viewModelScope.launch {
                    ChatClient.volumeLevel()?.let { level ->
                        ChatVolumeState.level = level.level
                        ChatVolumeState.max = level.max
                        showVolumePanel(keyCode)
                    }
                }
            } else {
                showVolumePanel(keyCode)
            }
            // Not handled here: the SDK forwards the rocker to the server,
            // which adjusts the media stream (one step per press — repeats are
            // filtered above, and the server ignores them too).
            return false
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showVolumePanel(keyCode: Int) {
        val current = ChatVolumeState.level ?: return
        val newLevel = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> (current + 1).coerceAtMost(ChatVolumeState.max.coerceAtLeast(1))
            else -> (current - 1).coerceAtLeast(0)
        }
        ChatVolumeState.level = newLevel
        volumePanel.value = VolumePanelState.Media(newLevel, ChatVolumeState.max)
    }

    private fun refreshVolumeLevel() {
        viewModelScope.launch {
            ChatClient.volumeLevel()?.let { level ->
                ChatVolumeState.level = level.level
                ChatVolumeState.max = level.max
            }
        }
    }
}
