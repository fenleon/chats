package com.lightphone.chats.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reboot recovery (docs/WAKE-COMPARISON.md #1). A rebooted LP3 has no
 * process, so the FGS (and the sync loop it holds) stays dead until the user
 * opens the app — messages stay silent. BOOT_COMPLETED fires after first
 * unlock, so credential-protected storage (the session store) is readable;
 * [ServerBootstrapProvider] has already run [MatrixRepository.init] by then,
 * and [ChatSyncService] re-applies the screen → cadence decision (a boot
 * with the screen dark → slow-sync rounds; screen on → long-poll).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MatrixRepository.isSyncEnabled) {
            Log.d(TAG, "boot completed — sync disabled by user, not restarting")
            return
        }
        Log.i(TAG, "boot completed — restarting sync service")
        ChatSyncService.tryStart(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
