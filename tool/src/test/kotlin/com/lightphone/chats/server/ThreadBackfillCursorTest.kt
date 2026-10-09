package com.lightphone.chats.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure cursor state machine for the fresh-login ThreadRow backfill
 * (docs/THREAD-STORE-SPEC.md §8): trigger, per-room cap, chain end,
 * resume-from-bookmark, room-done transition. No Android, no I/O.
 */
class ThreadBackfillCursorTest {

    private fun running(roomIds: List<String>): ThreadBackfill.State {
        var s = ThreadBackfill.onTriggerProbed(
            ThreadBackfill.State(), storeEmpty = true, hasBookmarks = false,
        )
        s = ThreadBackfill.startPass(s, roomIds)
        s = ThreadBackfill.nextRoom(s)
        return s
    }

    // --- trigger: fresh-login detection (SPEC §8) ---

    @Test
    fun `empty store is a fresh login - pass triggers`() {
        val s = ThreadBackfill.onTriggerProbed(
            ThreadBackfill.State(), storeEmpty = true, hasBookmarks = false,
        )
        assertEquals(ThreadBackfill.Phase.FRESH, s.phase)
    }

    @Test
    fun `existing install with rows and no bookmarks does not run`() {
        val s = ThreadBackfill.onTriggerProbed(
            ThreadBackfill.State(), storeEmpty = false, hasBookmarks = false,
        )
        assertEquals(ThreadBackfill.Phase.DONE, s.phase)
    }

    @Test
    fun `bookmarks from an interrupted pass trigger a resume even with rows`() {
        val s = ThreadBackfill.onTriggerProbed(
            ThreadBackfill.State(), storeEmpty = false, hasBookmarks = true,
        )
        assertEquals(ThreadBackfill.Phase.FRESH, s.phase)
    }

    // --- room iteration: existing list order, one room at a time ---

    @Test
    fun `rooms are taken in the given list order`() {
        var s = running(listOf("!a", "!b", "!c"))
        assertEquals("!a", s.roomId)
        s = ThreadBackfill.nextRoom(s)
        assertEquals("!b", s.roomId)
        assertEquals(0, s.fetched)
        assertNull(s.batchBefore)
    }

    @Test
    fun `exhausting the room list finishes the pass`() {
        val s = running(listOf("!a"))
        val done = ThreadBackfill.nextRoom(s)
        assertEquals(ThreadBackfill.Phase.DONE, done.phase)
        assertNull(done.roomId)
    }

    @Test
    fun `trigger-probed idle state never starts a pass`() {
        val s = ThreadBackfill.onTriggerProbed(
            ThreadBackfill.State(), storeEmpty = false, hasBookmarks = false,
        )
        assertEquals(s, ThreadBackfill.startPass(s, listOf("!a")))
    }

    // --- per-room stop: cap at THREAD_BACKFILL_MAX_EVENTS ---

    @Test
    fun `cap stops the room once 1000 events are fetched and writes the sentinel`() {
        var s = running(listOf("!a", "!b"))
        repeat(33) { round ->
            val a = ThreadBackfill.advance(s, "!a", 30, "tok$round")
            assertFalse(a.roomDone)
            assertEquals(
                ThreadBackfill.CursorWrite.Token("tok$round", 30 * (round + 1)),
                a.write,
            )
            s = a.state
        }
        assertEquals(990, s.fetched)
        val a = ThreadBackfill.advance(s, "!a", 30, "tok33")
        assertTrue(a.roomDone)
        // Cap stop writes the SENTINEL (token NULL, count = accumulated) —
        // not a delete: the marker distinguishes "capped this login" from
        // "never visited" so resumed passes skip the room instead of
        // re-deepening it, while the pass trigger ignores the NULL token.
        assertEquals(ThreadBackfill.CursorWrite.Capped(1020), a.write)
        assertEquals(1020, a.state.fetched)
    }

    // --- per-room stop: chain end ---

    @Test
    fun `chain end stops the room below the cap and clears the bookmark`() {
        val s = running(listOf("!a", "!b"))
        val a = ThreadBackfill.advance(s, "!a", 30, null)
        assertTrue(a.roomDone)
        // Chain end (the room's creation) = the room is complete: the row
        // goes. Absence means done — the one stop that re-admits a walk.
        assertEquals(ThreadBackfill.CursorWrite.Clear, a.write)
    }

    // --- resume: interrupted room continues from its bookmark ---

    @Test
    fun `resume continues an interrupted room from its bookmark with its fetch count`() {
        var s = running(listOf("!a", "!b"))
        s = ThreadBackfill.resume(s, "!a", "bookmark-token", 640)
        assertEquals("bookmark-token", s.batchBefore)
        // A bookmark restart must NOT reset the counter (fix-round ruling):
        // the room's budget continues from the persisted count.
        assertEquals(640, s.fetched)
        val a = ThreadBackfill.advance(s, "!a", 400, "next")
        assertTrue(a.roomDone)
        assertEquals(ThreadBackfill.CursorWrite.Capped(1040), a.write)
        assertEquals(1040, a.state.fetched)
    }

    // --- cap sentinel: resumed passes skip capped rooms ---

    @Test
    fun `a cap sentinel's carried count marks the room capped on a resumed pass`() {
        var s = running(listOf("!a", "!b"))
        // Worker shape: sentinel row → resume with a NULL token but the
        // carried count → cappedAlready skips before any stepRoom call.
        s = ThreadBackfill.resume(s, "!a", null, 1020)
        assertNull(s.batchBefore)
        assertTrue(ThreadBackfill.cappedAlready(s))
    }

    @Test
    fun `a carried count below the cap is not capped`() {
        var s = running(listOf("!a", "!b"))
        s = ThreadBackfill.resume(s, "!a", "tok", 640)
        assertFalse(ThreadBackfill.cappedAlready(s))
    }

    @Test
    fun `cappedAlready is false outside a running room`() {
        assertFalse(ThreadBackfill.cappedAlready(ThreadBackfill.State()))
    }

    @Test
    fun `resume with no row leaves the room fresh`() {
        // No cursor row at all (chain end / never visited): the worker
        // passes count 0 — the room walks from its deepest row as usual.
        val s = ThreadBackfill.resume(running(listOf("!a", "!b")), "!a", null, 0)
        assertNull(s.batchBefore)
        assertEquals(0, s.fetched)
        assertFalse(ThreadBackfill.cappedAlready(s))
    }

    // --- room-done transition ---

    @Test
    fun `room done advances to the next room with counters reset`() {
        val s = running(listOf("!a", "!b"))
        val a = ThreadBackfill.advance(s, "!a", 30, null)
        assertTrue(a.roomDone)
        val next = ThreadBackfill.nextRoom(a.state)
        assertEquals("!b", next.roomId)
        assertEquals(0, next.fetched)
        assertNull(next.batchBefore)
        assertEquals(ThreadBackfill.Phase.RUNNING, next.phase)
    }

    // --- hygiene ---

    @Test
    fun `a round for a room other than the current one is ignored`() {
        val s = running(listOf("!a", "!b"))
        val a = ThreadBackfill.advance(s, "!b", 30, "tok")
        assertEquals(s, a.state)
        assertNull(a.write)
        assertFalse(a.roomDone)
    }
}
