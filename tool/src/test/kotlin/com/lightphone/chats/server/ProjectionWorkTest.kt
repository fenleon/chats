package com.lightphone.chats.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

class ProjectionWorkTest {
    @Test
    fun cancelledReadDoesNotKillAnActiveSessionQueue() = runBlocking {
        withTimeout(3_000) {
            var calls = 0
            val recovered = CompletableDeferred<Unit>()
            val work = ProjectionWork<String>(this, 20, { throw AssertionError(it) }) { rooms ->
                if (++calls == 1) throw CancellationException("one read cancelled")
                recovered.complete(Unit)
                ProjectionResult(rooms.size, emptyList())
            }
            try {
                work.enqueue(listOf("a"))
                recovered.await()
                assertEquals(2, calls)
            } finally { work.stop() }
        }
    }

    @Test
    fun repeatedDirtyRoomsCoalesceWhileTheCurrentBatchRuns() = runBlocking {
        withTimeout(3_000) {
            val batches = Channel<List<String>>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val work = ProjectionWork<String>(this, 100, { throw AssertionError(it) }) { rooms ->
                batches.send(rooms)
                if (++calls == 1) release.await()
                ProjectionResult(rooms.size, emptyList())
            }
            try {
                work.enqueue(listOf("a"))
                assertEquals(listOf("a"), batches.receive())
                repeat(100) { work.enqueue(listOf("a", "b", "b")) }
                release.complete(Unit)
                assertEquals(listOf("a", "b"), batches.receive())
                assertNull(withTimeoutOrNull(100) { batches.receive() })
            } finally { work.stop() }
        }
    }

    @Test
    fun directAndQueuedRefreshesCannotOverwriteEachOtherOutOfOrder() = runBlocking {
        withTimeout(3_000) {
            val started = Channel<List<String>>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            var active = 0
            var maxActive = 0
            val work = ProjectionWork<String>(this, 100, { throw AssertionError(it) }) { rooms ->
                maxActive = maxOf(maxActive, ++active)
                started.send(rooms)
                if (rooms == listOf("old")) release.await()
                active--
                ProjectionResult(rooms.size, emptyList())
            }
            try {
                val first = async { work.refresh(listOf("old")) }
                assertEquals(listOf("old"), started.receive())
                val second = async { work.refresh(listOf("new", "new")) }
                work.enqueue(listOf("queued"))
                yield()
                assertTrue(started.tryReceive().isFailure)
                release.complete(Unit)
                assertEquals(1, first.await())
                assertEquals(1, second.await())
                assertEquals(setOf("new", "queued"), (started.receive() + started.receive()).toSet())
                assertEquals(1, maxActive)
            } finally { work.stop() }
        }
    }

    @Test
    fun onlyUnresolvedRoomsRetryAndSuccessDisarmsTheTimer() = runBlocking {
        withTimeout(3_000) {
            val batches = Channel<List<String>>(Channel.UNLIMITED)
            var ready = false
            val work = ProjectionWork<String>(this, 40, { throw AssertionError(it) }) { rooms ->
                batches.send(rooms)
                ProjectionResult(rooms.size, if (ready) emptyList() else rooms)
            }
            try {
                work.refresh(listOf("a", "b"))
                assertEquals(listOf("a", "b"), batches.receive())
                ready = true
                work.refresh(listOf("a"))
                assertEquals(listOf("a"), batches.receive())
                assertEquals(listOf("b"), batches.receive())
                assertNull(withTimeoutOrNull(150) { batches.receive() })
            } finally { work.stop() }
        }
    }

    @Test
    fun stopWaitsForRunningWorkAndCancelsPendingRetries() = runBlocking {
        withTimeout(3_000) {
            val entered = CompletableDeferred<Unit>()
            val unwinding = CompletableDeferred<Unit>()
            var writes = 0
            val work = ProjectionWork<String>(this, 20, { throw AssertionError(it) }) {
                entered.complete(Unit)
                try { awaitCancellation() } catch (_: CancellationException) {
                    unwinding.complete(Unit)
                }
                // Repository write paths check cancellation even if a read swallowed it.
                currentCoroutineContext().ensureActive()
                writes++
                ProjectionResult(1, listOf("a"))
            }
            val caller = async { work.refresh(listOf("a")) }
            entered.await()
            work.stop()
            assertTrue(unwinding.isCompleted)
            assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
            work.enqueue(listOf("late"))
            delay(60)
            assertEquals(0, writes)
        }
    }

    @Test
    fun stoppingDuringRetryDelayPreventsTheOldSessionFromRunningAgain() = runBlocking {
        withTimeout(3_000) {
            var oldCalls = 0
            val old = ProjectionWork<String>(this, 30, { throw AssertionError(it) }) { rooms ->
                oldCalls++
                ProjectionResult(rooms.size, rooms)
            }
            old.refresh(listOf("same-room"))
            old.stop()
            var freshCalls = 0
            val fresh = ProjectionWork<String>(this, 30, { throw AssertionError(it) }) { rooms ->
                freshCalls++
                ProjectionResult(rooms.size, emptyList())
            }
            try {
                fresh.refresh(listOf("same-room"))
                delay(100)
                assertEquals(1, oldCalls)
                assertEquals(1, freshCalls)
            } finally { fresh.stop() }
        }
    }

    @Test
    fun aFailedRefreshRetriesWithoutKillingTheWorker() = runBlocking {
        withTimeout(3_000) {
            var failures = 0
            var calls = 0
            val recovered = CompletableDeferred<Unit>()
            val work = ProjectionWork<String>(this, 20, { failures++ }) { rooms ->
                if (++calls == 1) error("transient store failure")
                recovered.complete(Unit)
                ProjectionResult(rooms.size, emptyList())
            }
            try {
                assertEquals(0, work.refresh(listOf("a")))
                recovered.await()
                delay(60)
                assertEquals(1, failures)
                assertEquals(2, calls)
            } finally { work.stop() }
        }
    }
}
