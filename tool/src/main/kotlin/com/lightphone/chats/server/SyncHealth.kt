package com.lightphone.chats.server

import de.connect2x.trixnity.core.model.push.PushAction

/**
 * Pure delivery-health logic (push-rule suppression, stall detection, the
 * Account screen's health line) — separated from MatrixRepository so
 * kotlin-test exercises it without a client. Timestamps are wall-clock
 * (persisted across processes; the backwards-clock rule in [isStalled]
 * covers a reboot resetting the epoch's reference points).
 */
object SyncHealth {

    /** BrightChat PollAlarm.looksStalled: no proven success for 3× the
     *  expected cadence means a cheap catch-up is worth doing now. Generous
     *  on purpose — normal system deferral must not read as a fault. */
    const val STALL_MULTIPLIER = 3

    /** The account's push rules decided this event must not alert. `null`
     *  means no rule matched or the matching rule lacks Notify — per the
     *  Matrix spec both mean don't alert (mention-only without a mention,
     *  a room rule with `actions: []`, …). */
    fun shouldSuppress(actions: Set<PushAction>?): Boolean =
        actions == null || PushAction.Notify !in actions

    /** True when the sync chain looks dead: never succeeded (0), the clock
     *  went backwards (negative elapsed), or the last success is more than
     *  [STALL_MULTIPLIER]× the current expected cadence ago. */
    fun isStalled(lastOkAtMs: Long, nowMs: Long, expectedIntervalMs: Long): Boolean {
        if (lastOkAtMs == 0L) return true
        val elapsed = nowMs - lastOkAtMs
        if (elapsed < 0) return true
        return elapsed > STALL_MULTIPLIER * expectedIntervalMs
    }

    /** The one Account-screen status suffix: last successful round age and
     *  consecutive failures when any. Combined with the account status word
     *  on a single line ("Syncing · 2m ago"), so it carries no "Sync" prefix
     *  of its own. (The push half is gone: Rung 1 has no push at all, and
     *  the "No background sync available" wording read as noise — LP3
     *  feedback 2026-10-09.) */
    fun healthLine(lastOkAtMs: Long, failures: Int, nowMs: Long): String {
        return when {
            lastOkAtMs == 0L -> "No sync round yet"
            else -> {
                val failuresPart = if (failures > 0) " · $failures failed" else ""
                "${ago(nowMs - lastOkAtMs)} ago$failuresPart"
            }
        }
    }

    /** Compact elapsed label ("<1m"/"3m"/"2h"/"5d") — shared with the Account
     *  screen's Manual Sync Now subtitle. */
    internal fun ago(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            else -> "${minutes / (60 * 24)}d"
        }
    }
}
