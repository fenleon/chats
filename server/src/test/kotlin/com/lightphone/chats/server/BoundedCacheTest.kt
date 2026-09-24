package com.lightphone.chats.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedCacheTest {
    @Test
    fun evictsByWeightAndRecency() {
        val cache = BoundedCache<String>(6) { it.length.toLong() }
        cache.put("a", "aaa")
        cache.put("b", "bbb")
        assertEquals("aaa", cache["a"])
        cache.put("c", "ccc")
        assertNull(cache["b"])
        assertEquals("aaa", cache["a"])
        assertEquals("ccc", cache["c"])
    }

    @Test
    fun replacementAndOversizedEntriesHaveCorrectAccounting() {
        val cache = BoundedCache<String>(6) { it.length.toLong() }
        cache.put("a", "aaaa")
        cache.put("a", "a")
        cache.put("b", "bbbbb")
        assertEquals("a", cache["a"])
        cache.put("a", "too-large")
        assertNull(cache["a"])
        assertEquals("bbbbb", cache["b"])
        cache.clear()
        cache.put("c", "cccccc")
        assertNull(cache["b"])
        assertEquals("cccccc", cache["c"])
    }

    @Test
    fun invalidatedCacheRejectsLateWrites() {
        val cache = BoundedCache<String>(6) { it.length.toLong() }
        cache.put("a", "a")
        cache.invalidate()
        cache.put("late", "late")
        assertNull(cache["a"])
        assertNull(cache["late"])
    }
}
