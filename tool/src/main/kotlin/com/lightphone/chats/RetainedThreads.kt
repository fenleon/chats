package com.lightphone.chats

import com.lightphone.chats.server.BoundedCache

/** Only reopening snapshots are limited; the open view model owns its full history. */
internal class RetainedThreads<T>(
    maxRooms: Int = 8,
    private val maxMessages: Int = 300,
) {
    init { require(maxMessages > 0) }

    data class Snapshot<T>(val messages: List<T>, val scroll: Pair<Int, Int>)

    private val rooms = BoundedCache<Snapshot<T>>(maxRooms.toLong()) { 1L }

    fun get(roomId: String): Snapshot<T>? = rooms[roomId]

    fun save(roomId: String, messages: List<T>, scroll: Pair<Int, Int>) {
        // A truncated list cannot safely restore an index into the old list.
        rooms.put(roomId, Snapshot(messages.takeLast(maxMessages),
            if (messages.size > maxMessages) 0 to 0 else scroll))
    }

    fun invalidate() = rooms.invalidate()
}
