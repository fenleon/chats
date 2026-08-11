package com.lightphone.chats.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground host for the Matrix sync loop. Trixnity's [net.folivo.trixnity.client.MatrixClient.startSync]
 * long-polls the homeserver inside the client; this service keeps the process
 * alive so messages arrive while the tool is closed. The companion is a plain
 * app, so the tool runtime's service ban doesn't apply here (the whole point of
 * the server role).
 */
class ChatSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(this))
        serviceScope.launch {
            val c = MatrixRepository.ensureClient() ?: return@launch
            if (syncedClient !== c) {
                runCatching { syncedClient?.stopSync() }
                c.startSync()
                syncedClient = c
                Log.d(TAG, "sync loop started for ${c.userId.full}")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        syncedClient = null
        Log.d(TAG, "sync service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Chats synced with your homeserver"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "chats_sync"
        private const val NOTIFICATION_ID = 71
        private const val TAG = "ChatSyncService"

        /** The client instance the sync loop is currently running on. */
        @Volatile
        private var syncedClient: net.folivo.trixnity.client.MatrixClient? = null

        /** Starts the service; safe to call when it is already running. */
        fun start(context: Context) {
            val appContext = context.applicationContext
            try {
                appContext.startForegroundService(Intent(appContext, ChatSyncService::class.java))
            } catch (e: RuntimeException) {
                Log.e(TAG, "could not start sync service", e)
            }
        }

        private fun buildNotification(context: Context): Notification {
            val openStatus = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("Chats")
                .setContentText("Syncing messages")
                .setContentIntent(openStatus)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
        }
    }
}
