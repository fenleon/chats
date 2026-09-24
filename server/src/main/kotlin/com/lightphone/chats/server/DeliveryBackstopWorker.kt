package com.lightphone.chats.server

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * A failure-mode-independent backstop under the FGS + push channel
 * (BrightChat DeliveryWorker): periodic work lives in JobScheduler's own
 * store — it survives process death and an app update, restores after a
 * reboot without a receiver, and re-enqueues without the app running. Not
 * prompt (15-minute minimum, Doze maintenance windows): it is the thing that
 * notices the live chain is gone and rebuilds it, not the delivery mechanism.
 *
 * Three jobs, most-likely-broken first: restart the sync service, run one
 * catch-up round, drain the durable push queue.
 */
class DeliveryBackstopWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        // Dead session: nothing to deliver, nothing to restart into.
        if (!MatrixRepository.isLoggedIn()) return Result.success()
        // The FGS keeps the process (and the push channel) alive. A background
        // start blocked by Android throws — tryStart catches and logs it.
        runCatching { ChatSyncService.tryStart(app) }
        runCatching { MatrixRepository.backstopCatchUp("delivery-backstop") }
        // Always success, even when the round failed. retry() looks right and
        // is a trap: its backoff is exponential from 30 s up to five hours and
        // only resets on success — a night with the homeserver down would push
        // the backstop out to hours exactly when it's most needed. The 15-min
        // period IS the retry.
        return Result.success()
    }

    companion object {
        private const val TAG = "DeliveryBackstop"
        private const val NAME = "chats-delivery-backstop"

        /** PeriodicWorkRequest's floor; exposed for the unit test. */
        const val PERIOD_MINUTES = 15L

        /**
         * Enqueues the periodic work. UPDATE, not KEEP/REPLACE (BrightChat):
         * REPLACE resets the period on every call and this runs at every
         * process start; KEEP freezes the spec at whatever the first install
         * enqueued. UPDATE keeps the running schedule and applies the current
         * spec — call it from every start path.
         *
         * WorkManager is initialized here, not by androidx.startup: the
         * InitializationProvider is merged into the manifest but never runs
         * in this process on LightOS, so [WorkManager.getInstance] throws
         * until [WorkManager.initialize] has been called — the probe below
         * makes the backstop self-sufficient (and idempotent: getInstance
         * succeeds on later calls, so initialize is reached once).
         */
        fun ensure(context: Context) {
            try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                WorkManager.initialize(
                    context.applicationContext,
                    Configuration.Builder().build(),
                )
            }
            val request = PeriodicWorkRequestBuilder<DeliveryBackstopWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    // The only constraint: no network, no point waking.
                    // Requiring idle or charging is how a backstop ends up
                    // never running.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }.onFailure { Log.w(TAG, "couldn't enqueue backstop: ${it.message}") }
        }
    }
}
