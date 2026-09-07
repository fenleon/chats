package com.lightphone.chats.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.lightphone.chats.server.MatrixRepository
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The app's gesture buzz for row taps and long-press context panels: a
 * LongPress-type click (the app's standard click feel), gated by the same
 * LocalHapticsEnabled the SDK's lightClickable reads. (The SDK's context-based
 * haptic helper is plugin-banned in tool code, so this goes through Compose's
 * haptic API.) The state stays fresh across recompositions, so a gesture
 * handler can just call the returned lambda.
 */
@Composable
fun rememberHapticBuzz(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val enabled = rememberUpdatedState(LocalHapticsEnabled.current)
    return remember(haptic) {
        {
            if (enabled.value) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

/**
 * The live room census both list screens poll (NO-SEAM): collects the
 * repository's roomList into [rooms] trimmed to the old GetAllRooms row shape
 * (no preview/unread — those never render on list rows); an empty census never
 * wipes the first frame's seed (or an already-loaded list) during a cold
 * start. Returns the job for the caller's on-screen-hide cancel.
 */
fun collectRoomCensus(
    scope: CoroutineScope,
    rooms: MutableStateFlow<List<com.thelightphone.sdk.shared.LightServiceMethod.GetRooms.Room>>,
): Job = scope.launch {
    MatrixRepository.roomList.collect { census ->
        census.map { it.copy(lastMessage = "", unreadCount = 0, lastEventId = null) }
            .takeIf { it.isNotEmpty() || rooms.value.isEmpty() }
            ?.let { rooms.value = it }
    }
}

/**
 * The contact-panel flag toggle shared by both screens: flips [flow] locally
 * and persists the new value server-side. [roomId] is re-read per toggle
 * (the chat list's panel room changes); a null room skips the persist.
 */
fun CoroutineScope.toggleAndPersist(
    flow: MutableStateFlow<Boolean>,
    roomId: () -> String?,
    persist: suspend (String, Boolean) -> Unit,
) {
    val next = !flow.value
    flow.value = next
    roomId()?.let { id -> launch { persist(id, next) } }
}
