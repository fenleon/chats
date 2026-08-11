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
import net.folivo.trixnity.clientserverapi.client.SyncState
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
            if (watchdogClient !== c) {
                watchdogClient = c
                startSyncWatchdog(c)
            }
        }
        return START_STICKY
    }

    /**
     * Trixnity's sync loop already retries internally after a drop, but a
     * watchdog still guards against a loop that has died without resuming:
     * if the client stays in ERROR/TIMEOUT for a while (no RUNNING in
     * between), stop and restart the loop. Skipped for an expired session
     * (login state != LOGGED_IN) — retrying a dead token would just hammer
     * the server; that case is handled by [MatrixRepository].
     */
    private suspend fun startSyncWatchdog(c: net.folivo.trixnity.client.MatrixClient) {
        var stuckSinceMs = 0L
        c.syncState.collect { state ->
            val running = state == SyncState.RUNNING || state == SyncState.STARTED ||
                state == SyncState.INITIAL_SYNC
            val now = android.os.SystemClock.elapsedRealtime()
            if (running || c.loginState.value != net.folivo.trixnity.client.MatrixClient.LoginState.LOGGED_IN) {
                stuckSinceMs = 0L
                return@collect
            }
            if (stuckSinceMs == 0L) stuckSinceMs = now
            if (syncedClient === c && now - stuckSinceMs >= SYNC_RESTART_AFTER_MS) {
                Log.w(TAG, "sync stuck in $state for ${SYNC_RESTART_AFTER_MS / 1000}s — restarting sync loop")
                runCatching { c.stopSync() }
                c.startSync()
                stuckSinceMs = 0L
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        syncedClient = null
        watchdogClient = null
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

        /** Restart the sync loop after this long stuck in ERROR/TIMEOUT. */
        private const val SYNC_RESTART_AFTER_MS = 120_000L

        /** The client instance the sync loop is currently running on. */
        @Volatile
        private var syncedClient: net.folivo.trixnity.client.MatrixClient? = null

        /** The client the sync watchdog is currently attached to. */
        @Volatile
        private var watchdogClient: net.folivo.trixnity.client.MatrixClient? = null

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
