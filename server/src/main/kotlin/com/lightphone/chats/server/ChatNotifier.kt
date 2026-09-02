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

    /** Low-key sync-warning channel (WAKE-COMPARISON.md #3) — no sound, no badge. */
    private const val PENDING_CHANNEL_ID = "chats_sync_pending"
    private const val PENDING_CHANNEL_NAME = "Sync warnings"

    /** Fixed id for the sync-pending signal (free: FGS uses 71, rooms start at 1000). */
    private const val PENDING_NOTIFICATION_ID = 72

    /** The tool package hosting the SDK's LightActivity (see its merged manifest). */
    private const val TOOL_PACKAGE = "com.lightphone.chats"
    private const val TOOL_ACTIVITY = "com.thelightphone.sdk.LightActivity"

    /** Launch-intent extra carrying the room a notification tap should open. */
    const val EXTRA_NOTIFY_ROOM = "chats.notifyRoomId"

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

    /** The tool's main activity, used by the message + sync-pending content intents. */
    private fun toolLaunchIntent(context: Context, roomId: String? = null): Intent =
        Intent().apply {
            setComponent(ComponentName(TOOL_PACKAGE, TOOL_ACTIVITY))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (roomId != null) putExtra(EXTRA_NOTIFY_ROOM, roomId)
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
            // The tool reads this once per launch (takeLaunchExtra) so a
            // notification tap can open the right thread — and only a tap
            // does: returning from a thread or a list poll never sees it.
            toolLaunchIntent(context, roomId),
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

    /**
     * Low-key "messages may be waiting" signal (docs/WAKE-COMPARISON.md #3):
     * a push-wake sync exhausted its retries, so a message that arrived may
     * not have been delivered — molly's "may have messages" equivalent.
     * Cleared on the next successful sync or when the app comes to the
     * foreground (MatrixRepository.timedSyncOnce / enterActiveSync).
     */
    fun notifySyncPending(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(PENDING_CHANNEL_ID, PENDING_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Warns when checking for messages failed"
                setShowBadge(false)
            },
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            PENDING_NOTIFICATION_ID,
            toolLaunchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            PENDING_NOTIFICATION_ID,
            Notification.Builder(context, PENDING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("Chats")
                .setContentText("Checking for messages failed — will retry")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build(),
        )
        Log.d(TAG, "sync-pending notification posted")
    }

    /** Clears the sync-pending signal (next successful sync / foreground). */
    fun clearSyncPending(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PENDING_NOTIFICATION_ID)
    }

    /** Removes every message notification (logout). */
    fun clearAll(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    /** Stable per-room id; derived from the hash so updates replace the same notification. */
    private fun notificationId(roomId: String): Int =
        ID_BASE + Math.floorMod(roomId.hashCode(), 100_000)
}
