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
import de.connect2x.trixnity.clientserverapi.client.SyncState
import de.connect2x.trixnity.core.model.events.m.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground host for the Matrix sync loop. Trixnity's [de.connect2x.trixnity.client.MatrixClient.startSync]
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
        // Settings → Sync pause (audit 2026-08-14): never start the loop or
        // hold the FGS while paused — a sticky restart must not defeat the
        // user's choice.
        if (!MatrixRepository.isSyncEnabled) {
            Log.d(TAG, "sync disabled by user — not starting")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startForeground(NOTIFICATION_ID, buildNotification(this))
        serviceScope.launch {
            val c = MatrixRepository.ensureClient() ?: return@launch
            // Screen-state-aware sync start (battery 2026-08-19 audit): a
            // service restart while the screen is dark must not long-poll —
            // apply the screen → cadence decision first, and only start a
            // long-poll here when the screen is actually on (slow-sync grace
            // owns sync while dark; the shared entry point re-engages it).
            MatrixRepository.applySyncModeForScreenState()
            if (syncedClient !== c && !MatrixRepository.isInProcessSyncRunning &&
                !MatrixRepository.isSlowSyncing && MatrixRepository.isScreenOn
            ) {
                runCatching { syncedClient?.stopSync() }
                c.startSync(Presence.OFFLINE)
                syncedClient = c
                Log.d(
                    TAG,
                    "sync loop started for ${c.userId.full} " +
                        "(screen ${if (MatrixRepository.isScreenOn) "on" else "off"}, " +
                        "mode ${if (MatrixRepository.isSlowSyncing) "slow" else "active"})",
                )
            } else if (syncedClient !== c) {
                Log.d(
                    TAG,
                    if (MatrixRepository.isScreenOn) {
                        "in-process/slow sync loop already running for ${c.userId.full} — service is keep-alive only"
                    } else {
                        "screen off — service stays keep-alive; slow-sync grace owns sync"
                    },
                )
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
    private suspend fun startSyncWatchdog(c: de.connect2x.trixnity.client.MatrixClient) {
        var stuckSinceMs = 0L
        var lastState: SyncState? = null
        c.syncState.collect { state ->
            // One line per state CHANGE (rare — a handful per night), so a
            // silently dead loop leaves a visible last-state trail (audit
            // 2026-08-23: long-polls stopped for hours with no logged cause).
            if (state != lastState) {
                lastState = state
                Log.d(
                    TAG,
                    "syncState: $state (screen ${if (MatrixRepository.isScreenOn) "on" else "off"}, " +
                        "mode ${if (MatrixRepository.isSlowSyncing) "slow" else "active"})",
                )
            }
            // Slow sync owns sync while it runs (STOPPED between rounds) —
            // never restart a long-poll then; MatrixRepository does on wake.
            if (MatrixRepository.isSlowSyncing) {
                stuckSinceMs = 0L
                return@collect
            }
            val running = state == SyncState.RUNNING || state == SyncState.STARTED ||
                state == SyncState.INITIAL_SYNC
            val now = android.os.SystemClock.elapsedRealtime()
            if (running || c.loginState.value != de.connect2x.trixnity.client.MatrixClient.LoginState.LOGGED_IN) {
                stuckSinceMs = 0L
                return@collect
            }
            if (stuckSinceMs == 0L) stuckSinceMs = now
            // Never restart the long-poll while the screen is dark (battery
            // 2026-08-19 audit): slow-sync owns sync then, and a restart would
            // defeat the whole screen → cadence gate.
            if (syncedClient === c && now - stuckSinceMs >= SYNC_RESTART_AFTER_MS && MatrixRepository.isScreenOn) {
                Log.w(TAG, "sync stuck in $state for ${SYNC_RESTART_AFTER_MS / 1000}s — restarting sync loop")
                runCatching { c.stopSync() }
                c.startSync(Presence.OFFLINE)
                stuckSinceMs = 0L
            }
        }
    }

    override fun onDestroy() {
        running = false
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
        private var syncedClient: de.connect2x.trixnity.client.MatrixClient? = null

        /** The client the sync watchdog is currently attached to. */
        @Volatile
        private var watchdogClient: de.connect2x.trixnity.client.MatrixClient? = null

        /** True while this service is running. */
        @Volatile
        private var running = false

        val isRunning: Boolean get() = running

        /** Starts the service; returns false if Android blocked the start
         *  (background FGS restriction — [MatrixRepository.startSyncLoop]
         *  retries with backoff and runs an in-process loop meanwhile). */
        fun tryStart(context: Context): Boolean = try {
            context.applicationContext.startForegroundService(
                android.content.Intent(context.applicationContext, ChatSyncService::class.java),
            )
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "sync service start blocked (background FGS restriction): ${e.message}")
            false
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
