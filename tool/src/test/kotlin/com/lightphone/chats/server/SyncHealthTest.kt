package com.lightphone.chats.server

import de.connect2x.trixnity.core.model.push.PushAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncHealthTest {

    // --- shouldSuppress (push-rule decision, direct evaluation in notifyForEvent) ---

    @Test
    fun `no Notify action means the rules suppress the event`() {
        assertTrue(SyncHealth.shouldSuppress(emptySet()))
    }

    @Test
    fun `Notify action means the event may alert`() {
        assertFalse(SyncHealth.shouldSuppress(setOf(PushAction.Notify)))
    }

    @Test
    fun `null result means the rules say don't alert`() {
        assertTrue(SyncHealth.shouldSuppress(null))
    }

    // --- isStalled (screen-on stall repair, BrightChat PollAlarm heuristic) ---

    @Test
    fun `no successful round ever reads as stalled`() {
        assertTrue(SyncHealth.isStalled(lastOkAtMs = 0L, nowMs = 1_000L, expectedIntervalMs = 300_000L))
    }

    @Test
    fun `clock that went backwards reads as stalled`() {
        assertTrue(SyncHealth.isStalled(lastOkAtMs = 2_000L, nowMs = 1_000L, expectedIntervalMs = 300_000L))
    }

    @Test
    fun `success within three intervals is healthy`() {
        assertFalse(
            SyncHealth.isStalled(lastOkAtMs = 1_000_000L, nowMs = 1_000_000L + 299_999L, expectedIntervalMs = 300_000L),
        )
    }

    @Test
    fun `exactly three intervals is still healthy (strict inequality)`() {
        assertFalse(
            SyncHealth.isStalled(lastOkAtMs = 1_000_000L, nowMs = 1_000_000L + 900_000L, expectedIntervalMs = 300_000L),
        )
    }

    @Test
    fun `beyond three intervals is stalled`() {
        assertTrue(
            SyncHealth.isStalled(lastOkAtMs = 1_000_000L, nowMs = 1_000_000L + 900_001L, expectedIntervalMs = 300_000L),
        )
    }

    // --- healthLine (Account screen status line) ---

    @Test
    fun `no round yet says so`() {
        assertEquals(
            "No sync round yet",
            SyncHealth.healthLine(lastOkAtMs = 0L, failures = 0, nowMs = 1_000L),
        )
    }

    @Test
    fun `healthy line is last-round age`() {
        assertEquals(
            "2m ago",
            SyncHealth.healthLine(lastOkAtMs = 1_000L, failures = 0, nowMs = 1_000L + 120_000L),
        )
    }

    @Test
    fun `failures appear only when above zero`() {
        assertEquals(
            "14m ago · 3 failed",
            SyncHealth.healthLine(lastOkAtMs = 1_000L, failures = 3, nowMs = 1_000L + 14 * 60_000L),
        )
        assertEquals(
            "14m ago",
            SyncHealth.healthLine(lastOkAtMs = 1_000L, failures = 0, nowMs = 1_000L + 14 * 60_000L),
        )
    }

    @Test
    fun `under a minute reads as just now`() {
        assertEquals(
            "just now",
            SyncHealth.healthLine(lastOkAtMs = 1_000L, failures = 0, nowMs = 1_000L + 5_000L),
        )
    }
}
