package com.lightphone.chats.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Posts the companion's new-message notifications, in the calm Light style:
 * a plain system icon, the room name as title, the message preview as text,
 * no logos or colors. One notification per room, replaced as more messages
 * arrive; tapping one opens the Chats tool (the companion cannot read the
 * tool's UI state, so the pending-room handoff happens over the binder — see
 * [MatrixRepository.takeNotifyRoom]).
 *
 * Everything privileged lives here in the companion — the tool runtime bans
 * notifications, so the tool itself never touches this code path.
 */
object ChatNotifier {

    private const val CHANNEL_ID = "chats_messages"
    private const val CHANNEL_NAME = "Messages"

    /** The tool package hosting the SDK's LightActivity (see its merged manifest). */
    private const val TOOL_PACKAGE = "com.lightphone.chats"
    private const val TOOL_ACTIVITY = "com.thelightphone.sdk.LightActivity"

    /** Base offset so message ids never collide with the sync FGS notification (71). */
    private const val ID_BASE = 1000

    private val TAG = ChatNotifier::class.java.simpleName

    /** Lazy idempotent channel creation — safe to call before every post. */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New messages in your chats"
            },
        )
    }

    /**
     * Shows or replaces the notification for [roomId]'s newest message.
     * [preview] is the display text (the decrypted message body, "[Photo]",
     * etc.); [senderName] prefixes it for group rooms.
     */
    fun notifyMessage(
        context: Context,
        roomId: String,
        roomName: String,
        senderName: String?,
        preview: String,
        direct: Boolean,
        unreadCount: Long,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)

        val id = notificationId(roomId)
        val text = if (direct || senderName.isNullOrBlank()) preview else "$senderName: $preview"
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent().apply {
                setComponent(ComponentName(TOOL_PACKAGE, TOOL_ACTIVITY))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(roomName)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (unreadCount > 1) builder.setNumber(unreadCount.toInt())
        manager.notify(id, builder.build())

        // The tool asks for this on its next list show (see TakeNotifyRoom) so
        // tapping the notification can land on the right thread.
        MatrixRepository.pendingNotifyRoomId = roomId
        Log.d(TAG, "notify $roomName — $text")
    }

    /** Removes the notification for a room (thread opened / marked read). */
    fun cancelRoom(context: Context, roomId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(roomId))
    }

    /** Removes every message notification (logout). */
    fun clearAll(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    /** Stable per-room id; derived from the hash so updates replace the same notification. */
    private fun notificationId(roomId: String): Int =
        ID_BASE + Math.floorMod(roomId.hashCode(), 100_000)
}
