package com.mynora.singverse.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Share one image acquisition while any visible consumer still needs it. */
internal class TvImageRequests<T>(private val cached: (String) -> T?) {
    private class Pending<T>(val result: Deferred<T?>, var readers: Int = 0)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = mutableMapOf<String, Pending<T>>()

    suspend fun load(key: String, fetch: suspend () -> T?): T? {
        val request = synchronized(lock) {
            cached(key)?.let { return it }
            val entry = pending[key] ?: Pending(scope.async(start = CoroutineStart.LAZY) { fetch() })
                .also { pending[key] = it }
            entry.readers++
            entry
        }
        try {
            request.result.start()
            return request.result.await()
        } finally {
            synchronized(lock) {
                request.readers--
                if (request.readers == 0 && pending[key] === request) {
                    pending.remove(key)
                    request.result.cancel()
                }
            }
        }
    }

    fun clear() {
        val requests = synchronized(lock) {
            pending.values.toList().also { pending.clear() }
        }
        requests.forEach { it.result.cancel() }
    }
}
