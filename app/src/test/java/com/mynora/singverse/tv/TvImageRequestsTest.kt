package com.mynora.singverse.tv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TvImageRequestsTest {
    @Test fun repeatedVisibleImagesShareAcquisitionAndCacheTheResult() = runBlocking {
        val cache = ConcurrentHashMap<String, String>()
        val requests = TvImageRequests(cache::get)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        suspend fun fetch(): String {
            calls.incrementAndGet(); started.complete(Unit); gate.await()
            return "bitmap".also { cache["nas:1:cover"] = it }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.load("nas:1:cover", ::fetch) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { requests.load("nas:1:cover", ::fetch) }
        gate.complete(Unit)
        assertEquals("bitmap", first.await()); assertEquals("bitmap", second.await())
        assertEquals("bitmap", requests.load("nas:1:cover", ::fetch))
        assertEquals(1, calls.get())
    }

    @Test fun scrollingOneConsumerAwayDoesNotCancelAnotherVisibleCover() = runBlocking {
        val requests = TvImageRequests<String> { null }
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            requests.load("cover") { started.complete(Unit); gate.await() }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { requests.load("cover") { error("duplicate") } }
        first.cancelAndJoin()
        gate.complete("shared")
        assertEquals("shared", withTimeout(2000) { second.await() })
    }

    @Test fun lastConsumerCancellationStopsWorkAndAllowsRetry() = runBlocking {
        val requests = TvImageRequests<String> { null }
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            requests.load("cover") {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        started.await(); reader.cancelAndJoin()
        withTimeout(2000) { stopped.await() }
        assertEquals("retry", requests.load("cover") { "retry" })
    }

    @Test fun clearedConnectionCannotRemoveTheNextConnectionsRequest() = runBlocking {
        val requests = TvImageRequests<String> { null }
        val started = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            requests.load("A:1") { started.complete(Unit); awaitCancellation() }
        }
        started.await(); requests.clear()
        val gate = CompletableDeferred<String>()
        val current = async(start = CoroutineStart.UNDISPATCHED) { requests.load("A:2") { gate.await() } }
        old.join()
        val another = async(start = CoroutineStart.UNDISPATCHED) { requests.load("A:2") { error("duplicate") } }
        gate.complete("current")
        assertEquals("current", current.await()); assertEquals("current", another.await())
    }

    @Test fun failedAndMissingImagesCanBeRetried() = runBlocking {
        val requests = TvImageRequests<String> { null }
        assertTrue(runCatching { requests.load("cover") { throw IOException("offline") } }.exceptionOrNull() is IOException)
        assertNull(requests.load("cover") { null })
        assertEquals("ready", requests.load("cover") { "ready" })
    }

    @Test fun differentRevisionsAndConnectionsNeverShareAcquisition() = runBlocking {
        val requests = TvImageRequests<String> { null }
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val readers = listOf("A:1:cover@1", "A:1:cover@2", "A:2:cover@1", "B:3:cover@1").map { key ->
            async(start = CoroutineStart.UNDISPATCHED) { requests.load(key) { calls.incrementAndGet(); gate.await(); key } }
        }
        gate.complete(Unit)
        assertEquals(4, readers.map { it.await() }.toSet().size)
        assertEquals(4, calls.get())
    }
}
