package com.lightphone.chats.screens

import com.lightphone.chats.RetainedThreads
import com.lightphone.chats.server.BoundedCache
import com.lightphone.chats.server.MatrixRepository
import com.thelightphone.sdk.shared.LightServiceMethod
import kotlinx.coroutines.flow.MutableStateFlow

/** Each session gets separate objects: old jobs cannot populate the next account's caches. */
internal class ThreadCaches(val generation: Long) {
    @Volatile private var valid = true
    fun isCurrent(): Boolean = valid && generation == MatrixRepository.cacheSessionGeneration

    val media = BoundedCache<ByteArray>(8L * 1024 * 1024) { it.size.toLong() }
    val bitmaps = BoundedCache<DecodedBitmap>(24L * 1024 * 1024) {
        it.bitmap.width.toLong() * it.bitmap.height * 4
    }
    val threads = RetainedThreads<LightServiceMethod.GetMessages.Message>()
    val edits = MutableStateFlow<Map<String, String>>(emptyMap())
    val unsent = MutableStateFlow<Set<String>>(emptySet())
    val resends = ResendChainState()

    fun clear() {
        valid = false
        media.invalidate()
        bitmaps.invalidate()
        threads.invalidate()
        edits.value = emptyMap()
        unsent.value = emptySet()
        resends.clear()
    }
}

private var retainedSession: ThreadCaches? = null

@Synchronized
internal fun threadCaches(): ThreadCaches {
    val generation = MatrixRepository.cacheSessionGeneration
    return retainedSession?.takeIf { it.generation == generation && it.isCurrent() }
        ?: ThreadCaches(generation).also {
            retainedSession?.clear()
            retainedSession = it
        }
}

@Synchronized
internal fun clearThreadCaches() {
    retainedSession?.clear()
    retainedSession = null
}
