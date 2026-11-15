package com.mynora.singverse.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val HOME_ACTIVITY_PAGE_SIZE = 20

internal data class HomeActivityPage<T>(val items: List<T>, val total: Long, val page: Int, val size: Int)

/** Server-owned, paged activity. Each list owns its requests, so a slow history
 * request cannot block popularity or overwrite another NAS connection. */
internal class TvHomeActivity<T>(
    private val connection: () -> String,
    private val read: suspend (Int) -> HomeActivityPage<T>,
    private val id: (T) -> String,
) {
    var songs by mutableStateOf(emptyList<T>()); private set
    var total by mutableStateOf(0L); private set
    var loading by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var hasMore by mutableStateOf(false); private set
    private var identity = ""
    private var generation = 0L
    private var nextPage = 0

    fun reset() {
        generation++
        identity = connection()
        songs = emptyList(); total = 0; loading = false; ready = false; error = ""; hasMore = false; nextPage = 0
    }

    private fun current(expected: String, ticket: Long) = expected == connection() && generation == ticket

    suspend fun load(append: Boolean = false) {
        if (identity != connection()) reset()
        if (append && (loading || !ready || !hasMore)) return
        val requestedPage = if (append) nextPage else 0
        val pagesToRead = if (append) 1 else nextPage.coerceAtLeast(1)
        val expected = connection()
        val ticket = ++generation
        loading = true; error = ""
        try {
            val incoming = mutableListOf<T>()
            var page = requestedPage
            var last: HomeActivityPage<T>? = null
            while (page < requestedPage + pagesToRead) {
                currentCoroutineContext().ensureActive()
                if (!current(expected, ticket)) return
                val result = read(page)
                currentCoroutineContext().ensureActive()
                if (!current(expected, ticket)) return
                check(result.page == page && result.size in 1..100 && result.total >= 0 && result.items.size <= result.size &&
                    (last == null || result.size == last.size)) {
                    "歌曲列表响应无效，请重试"
                }
                check(result.items.isNotEmpty() || page.toLong() * result.size >= result.total) {
                    "歌曲列表读取不完整，请重试"
                }
                incoming += result.items
                last = result
                page++
                if (page.toLong() * result.size >= result.total) break
            }
            val result = last ?: return
            currentCoroutineContext().ensureActive()
            if (!current(expected, ticket)) return
            // Live history may move an already loaded song across page boundaries.
            // Refresh the entire loaded window atomically so returning from the
            // background cannot remove a focused song from a later loaded page.
            songs = if (append) (songs + incoming).distinctBy(id) else incoming.distinctBy(id)
            total = result.total
            nextPage = page
            hasMore = nextPage.toLong() * result.size < result.total
            ready = true
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (current(expected, ticket)) error = failure.message ?: "歌曲列表读取失败，请重试"
        } finally {
            if (current(expected, ticket)) loading = false
        }
    }
}
