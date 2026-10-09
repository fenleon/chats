package com.lightphone.chats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetainedThreadsTest {
    @Test
    fun boundsHistoryWithoutTrimmingActiveMessages() {
        val cache = RetainedThreads<Int>(maxRooms = 2, maxMessages = 3)
        val active = mutableListOf(1, 2, 3, 4, 5)
        cache.save("room", active, 4 to 12)
        assertEquals(listOf(3, 4, 5), cache.get("room")!!.messages)
        assertEquals(0 to 0, cache.get("room")!!.scroll)
        assertEquals(listOf(1, 2, 3, 4, 5), active)
        active.add(6)
        assertEquals(listOf(3, 4, 5), cache.get("room")!!.messages)
    }

    @Test
    fun keepsRecentRoomsAndTheirValidScrollPositions() {
        val cache = RetainedThreads<Int>(maxRooms = 2, maxMessages = 3)
        cache.save("a", listOf(1, 2), 1 to 12)
        cache.save("b", listOf(3), 0 to 0)
        assertEquals(1 to 12, cache.get("a")!!.scroll)
        cache.save("c", listOf(4), 0 to 0)
        assertNull(cache.get("b"))
        assertEquals(listOf(1, 2), cache.get("a")!!.messages)
    }

    @Test
    fun retiredSessionCannotSaveLateSnapshots() {
        val old = RetainedThreads<Int>()
        val fresh = RetainedThreads<Int>()
        old.save("room", listOf(1), 0 to 0)
        old.invalidate()
        old.save("room", listOf(2), 0 to 0)
        fresh.save("room", listOf(3), 0 to 0)
        assertNull(old.get("room"))
        assertEquals(listOf(3), fresh.get("room")!!.messages)
    }
}
