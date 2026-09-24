package com.lightphone.chats

import com.lightphone.chats.server.BoundedCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Prefetch and visible rows await one view-model-owned request per event/policy. */
internal class MediaLoader(
    private val cache: BoundedCache<ByteArray>,
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val fetch: suspend (String, Boolean) -> ByteArray?,
) {
    private val loading = ConcurrentHashMap<Pair<String, Boolean>, Deferred<ByteArray?>>()

    suspend fun load(eventId: String, allowMobileData: Boolean): ByteArray? {
        if (!isCurrent()) return null
        cache[eventId]?.let { return it }
        val key = eventId to allowMobileData
        loading[key]?.let { return it.await()?.takeIf { isCurrent() } }
        val request = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@async null
                cache[eventId]?.let { return@async it }
                val bytes = fetch(eventId, allowMobileData)
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@async null
                if (bytes != null) {
                    cache.put(eventId, bytes)
                    return@async bytes
                }
                if (attempt < 2) delay(2_000)
            }
            null
        }
        val winner = loading.putIfAbsent(key, request)
        if (winner != null) {
            request.cancel()
            return winner.await()?.takeIf { isCurrent() }
        }
        request.invokeOnCompletion { loading.remove(key, request) }
        return request.await()?.takeIf { isCurrent() }
    }
}
