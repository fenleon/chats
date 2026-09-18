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
    fun `cap stops the room once 1000 events are fetched and keeps the bookmark`() {
        var s = running(listOf("!a", "!b"))
        repeat(33) { round ->
            val a = ThreadBackfill.advance(s, "!a", 30, "tok$round")
            if (round < 33) {
                assertFalse(a.roomDone)
                assertEquals("tok$round", a.persistCursor)
                s = a.state
            }
        }
        assertEquals(990, s.fetched)
        val a = ThreadBackfill.advance(s, "!a", 30, "tok33")
        assertTrue(a.roomDone)
        // Capped, NOT chain end — the bookmark stays so a later pass resumes
        // deeper instead of restarting (SPEC §8 resumable).
        assertEquals("tok33", a.persistCursor)
        assertEquals(1020, a.state.fetched)
    }

    // --- per-room stop: chain end ---

    @Test
    fun `chain end stops the room below the cap and clears the bookmark`() {
        val s = running(listOf("!a", "!b"))
        val a = ThreadBackfill.advance(s, "!a", 30, null)
        assertTrue(a.roomDone)
        assertNull(a.persistCursor)
    }

    // --- resume: interrupted room continues from its bookmark ---

    @Test
    fun `resume continues an interrupted room from its bookmark with a fresh budget`() {
        var s = running(listOf("!a", "!b"))
        s = ThreadBackfill.resume(s, "!a", "bookmark-token")
        assertEquals("bookmark-token", s.batchBefore)
        assertEquals(0, s.fetched)
        // The cap counts events fetched from the bookmark on — the walk
        // resumes, it does not restart (SPEC §8).
        val a = ThreadBackfill.advance(s, "!a", ThreadBackfill.THREAD_BACKFILL_MAX_EVENTS, "next")
        assertTrue(a.roomDone)
        assertEquals("next", a.persistCursor)
    }

    @Test
    fun `resume without a bookmark leaves the room fresh`() {
        val s = ThreadBackfill.resume(running(listOf("!a", "!b")), "!a", null)
        assertNull(s.batchBefore)
        assertEquals(0, s.fetched)
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
        assertFalse(a.roomDone)
    }
}
