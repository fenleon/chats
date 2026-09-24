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

    /** The account's push rules decided this event must not alert
     *  (dont_notify, mention-only without a mention, …). */
    fun shouldSuppress(actions: Set<PushAction>): Boolean = PushAction.Notify !in actions

    /** True when the sync chain looks dead: never succeeded (0), the clock
     *  went backwards (negative elapsed), or the last success is more than
     *  [STALL_MULTIPLIER]× the current expected cadence ago. */
    fun isStalled(lastOkAtMs: Long, nowMs: Long, expectedIntervalMs: Long): Boolean {
        if (lastOkAtMs == 0L) return true
        val elapsed = nowMs - lastOkAtMs
        if (elapsed < 0) return true
        return elapsed > STALL_MULTIPLIER * expectedIntervalMs
    }

    /** The one Account-screen line: last successful round age, consecutive
     *  failures when any, push-channel state. */
    fun healthLine(lastOkAtMs: Long, failures: Int, pushConnected: Boolean, nowMs: Long): String {
        val push = if (pushConnected) "push connected" else "push disconnected"
        return when {
            lastOkAtMs == 0L -> "No sync round yet · $push"
            else -> {
                val failuresPart = if (failures > 0) " · $failures failed" else ""
                "Sync ${ago(nowMs - lastOkAtMs)} ago$failuresPart · $push"
            }
        }
    }

    private fun ago(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            else -> "${minutes / (60 * 24)}d"
        }
    }
}
