package com.lightphone.chats

import com.lightphone.chats.server.BoundedCache
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MediaLoaderTest {
    private fun cache(maxBytes: Long = 1_024) = BoundedCache<ByteArray>(maxBytes) { it.size.toLong() }

    @Test
    fun successStopsRetriesAndCachedMediaSkipsFetch() = runBlocking {
        val cache = cache()
        val calls = AtomicInteger()
        val loader = MediaLoader(cache, this, { true }) { _, _ -> calls.incrementAndGet(); byteArrayOf(1) }
        loader.load("photo", false)
        loader.load("photo", false)
        assertEquals(1, calls.get())
        assertTrue(cache["photo"]!!.contentEquals(byteArrayOf(1)))
    }

    @Test
    fun overlappingCallsAwaitTheSameLoad() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val loader = MediaLoader(cache, this, { true }) { _, _ ->
                calls.incrementAndGet(); entered.complete(Unit); release.await(); byteArrayOf(1)
            }
            val first = async { loader.load("photo", false) }
            entered.await()
            val others = (1..20).map { async { loader.load("photo", false) } }
            release.complete(Unit)
            (others + first).awaitAll().forEach { assertTrue(it!!.contentEquals(byteArrayOf(1))) }
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun mobilePermissionCanChangeWhileAnotherRequestWaits() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loader = MediaLoader(cache(), this, { true }) { _, mobile ->
                if (!mobile) { entered.complete(Unit); release.await() }
                byteArrayOf(1)
            }
            val blocked = async { loader.load("photo", false) }
            entered.await()
            assertTrue(loader.load("photo", true)!!.contentEquals(byteArrayOf(1)))
            release.complete(Unit)
            assertTrue(blocked.await() != null)
        }
    }

    @Test
    fun nullResultsRetryThreeTimesAndExhaustionAllowsAnotherLoad() = runBlocking {
        withTimeout(12_000) {
            val cache = cache()
            val calls = AtomicInteger()
            val loader = MediaLoader(cache, this, { true }) { _, _ ->
                if (calls.incrementAndGet() <= 3) null else byteArrayOf(1)
            }
            assertNull(loader.load("photo", false))
            assertEquals(3, calls.get())
            assertNull(cache["photo"])
            loader.load("photo", false)
            assertEquals(4, calls.get())
            assertTrue(cache["photo"] != null)

            val transientCalls = AtomicInteger()
            MediaLoader(cache, this, { true }) { _, _ ->
                if (transientCalls.incrementAndGet() < 3) null else byteArrayOf(2)
            }.load("transient", false)
            assertEquals(3, transientCalls.get())
            assertTrue(cache["transient"] != null)
        }
    }

    @Test
    fun cancellingOneRowDoesNotCancelSharedPrefetch() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loader = MediaLoader(cache(), this, { true }) { _, _ ->
                entered.complete(Unit); release.await(); byteArrayOf(1)
            }
            val row = async { loader.load("photo", false) }
            entered.await()
            row.cancelAndJoin()
            val nextRow = async { loader.load("photo", false) }
            release.complete(Unit)
            assertTrue(nextRow.await()!!.contentEquals(byteArrayOf(1)))
        }
    }

    @Test
    fun cancellingOwnerDoesNotPublishSwallowedCancellation() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val owner = SupervisorJob()
            val entered = CompletableDeferred<Unit>()
            val loader = MediaLoader(cache, CoroutineScope(coroutineContext + owner), { true }) { _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } catch (_: CancellationException) {}
                byteArrayOf(1)
            }
            val caller = async { loader.load("photo", false) }
            entered.await()
            owner.cancelAndJoin()
            assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
            assertNull(cache["photo"])
        }
    }

    @Test
    fun oldSessionCompletionCannotRepopulateCaches() = runBlocking {
        withTimeout(5_000) {
            val oldCache = cache()
            val newCache = cache()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val generation = AtomicInteger(1)
            val oldLoader = MediaLoader(oldCache, this, { generation.get() == 1 }) { _, _ ->
                entered.complete(Unit); release.await(); byteArrayOf(1)
            }
            val old = async { oldLoader.load("same-event", false) }
            entered.await()
            generation.set(2)
            oldCache.invalidate()
            val fresh = MediaLoader(newCache, this, { generation.get() == 2 }) { _, _ -> byteArrayOf(2) }
            fresh.load("same-event", false)
            release.complete(Unit)
            assertNull(old.await())
            assertNull(oldCache["same-event"])
            assertTrue(newCache["same-event"]!!.contentEquals(byteArrayOf(2)))
        }
    }

    @Test
    fun oversizedMediaIsReturnedButNotRetained() = runBlocking {
        val cache = cache(2)
        val loader = MediaLoader(cache, this, { true }) { _, _ -> ByteArray(10) }
        assertEquals(10, loader.load("large", false)!!.size)
        assertNull(cache["large"])
    }

    @Test
    fun concurrentCompletionsKeepEveryEntryWithinBudget() = runBlocking {
        withTimeout(5_000) {
            val cache = cache()
            val release = CompletableDeferred<Unit>()
            val loader = MediaLoader(cache, this, { true }) { _, _ -> release.await(); byteArrayOf(1) }
            val requests = (1..100).map { id -> async { loader.load("photo-$id", false) } }
            release.complete(Unit)
            requests.awaitAll()
            assertEquals(100, (1..100).count { cache["photo-$it"] != null })
        }
    }
}
