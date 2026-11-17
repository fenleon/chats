package com.lightphone.chats.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProjectionResult<K>(val written: Int, val pending: List<K>)

/** One session's projection reads/writes, dirty-room queue and decrypt-retry timer. */
internal class ProjectionWork<K>(
    parent: CoroutineScope,
    private val retryDelayMs: Long,
    private val onFailure: (Exception) -> Unit,
    private val recompute: suspend (List<K>) -> ProjectionResult<K>,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutex = Mutex()
    private val lock = Any()
    private val dirty = linkedSetOf<K>()
    private val pending = linkedSetOf<K>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var retryJob: Job? = null

    init {
        require(retryDelayMs > 0)
        scope.launch {
            for (signal in wake) {
                val rooms = synchronized(lock) { dirty.toList().also { dirty.clear() } }
                if (rooms.isNotEmpty()) {
                    try {
                        refresh(rooms)
                    } catch (e: CancellationException) {
                        // A cancelled read must not kill an otherwise-live queue.
                        currentCoroutineContext().ensureActive()
                        updateRetries(rooms, rooms)
                    }
                }
            }
        }
    }

    fun enqueue(rooms: Collection<K>) {
        synchronized(lock) {
            if (!job.isActive || rooms.isEmpty()) return
            dirty.addAll(rooms)
            wake.trySend(Unit)
        }
    }

    /** Direct callers also use the session job and the same read/write serialization. */
    suspend fun refresh(rooms: List<K>): Int {
        if (rooms.isEmpty()) return 0
        val request = scope.async {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val distinctRooms = rooms.distinct()
                val result = try {
                    recompute(distinctRooms)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e)
                    ProjectionResult(0, distinctRooms)
                }
                currentCoroutineContext().ensureActive()
                updateRetries(distinctRooms, result.pending)
                result.written
            }
        }
        return try { request.await() } finally { request.cancel() }
    }

    private fun updateRetries(attempted: List<K>, unresolved: List<K>) {
        synchronized(lock) {
            if (!job.isActive) return
            pending.removeAll(attempted.toSet())
            pending.addAll(unresolved)
            if (pending.isEmpty()) {
                retryJob?.cancel()
                retryJob = null
            } else if (retryJob == null) {
                val timer = scope.launch(start = CoroutineStart.LAZY) {
                    val self = currentCoroutineContext()[Job]
                    delay(retryDelayMs)
                    val rooms = synchronized(lock) {
                        // A cancelled timer must not drain a replacement timer's rooms.
                        if (retryJob !== self) return@synchronized emptyList<K>()
                        retryJob = null
                        pending.toList().also { pending.clear() }
                    }
                    enqueue(rooms)
                }
                retryJob = timer
                timer.start()
            }
        }
    }

    fun cancel() {
        job.cancel()
        synchronized(lock) {
            dirty.clear()
            pending.clear()
            retryJob = null
            wake.close()
        }
    }

    suspend fun stop() {
        cancel()
        job.join()
    }
}
