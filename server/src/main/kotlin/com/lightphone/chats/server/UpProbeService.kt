package com.lightphone.chats.server

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush receiver (dev probe → now wired, 2026-08-21): registered against
 * LightOS's distributor (`com.lightos`), which delivers pushes over Light's
 * cloud (production.lightphonecloud.com) with no Google services and no
 * phone-held socket. The relay (chats/push/relay.py) forwards the Matrix
 * notify body as the UP message; parsing + wake mirror PushChannel.
 */
class UpProbeService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "upEndpoint=$instance -> ${endpoint.url}")
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val json = runCatching { message.content.decodeToString() }.getOrNull() ?: return
        val notif = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: run { Log.w(TAG, "up payload not JSON: ${json.take(120)}"); return }
        val n = notif["notification"]?.jsonObject ?: notif
        val eventId = n["event_id"]?.jsonPrimitive?.contentOrNull
        val roomId = n["room_id"]?.jsonPrimitive?.contentOrNull
        if (eventId != null || roomId != null) {
            Log.i(TAG, "up push received: room=$roomId event=$eventId -> waking one sync")
        } else {
            Log.i(TAG, "up push received (counts-style, no ids) -> waking one sync")
        }
        val countsOnly = eventId == null && roomId == null
        CoroutineScope(Dispatchers.IO).launch {
            MatrixRepository.onPushDelivered(countsOnly = countsOnly)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "upRegistrationFailed=$instance ($reason)")
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "upUnregistered=$instance")
    }

    companion object {
        private const val TAG = "UpProbe"
    }
}
