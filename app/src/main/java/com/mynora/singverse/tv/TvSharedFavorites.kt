package com.mynora.singverse.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

internal data class FavoritePage<T>(val items: List<T>, val total: Int, val revision: Long)

/** In-memory server state only. Identity includes the connection generation,
 * so an A -> B -> A switch cannot accept a response from the first A session. */
internal class TvSharedFavorites<T>(
    private val connection: () -> String,
    private val read: suspend (Int) -> FavoritePage<T>,
    private val write: suspend (List<String>, Boolean) -> Set<String>,
    private val id: (T) -> String,
) {
    var songs by mutableStateOf(emptyList<T>()); private set
    var ids by mutableStateOf(emptySet<String>()); private set
    var loading by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    private var identity = ""
    private var generation = 0L
    private var writeGeneration = 0L

    fun reset() {
        generation++; writeGeneration++; identity = connection(); songs = emptyList(); ids = emptySet()
        loading = false; saving = false; ready = false; error = ""
    }
    private fun current(expected: String, ticket: Long) = expected == connection() && ticket == generation

    suspend fun load() {
        if (identity != connection()) reset()
        if (saving) return
        val expected = connection(); val ticket = ++generation
        loading = true
        try {
            repeat(2) {
                val next = mutableListOf<T>()
                var revision: Long? = null
                var page = 0
                var changed = false
                while (true) {
                    if (!current(expected, ticket)) return
                    val response = read(page++)
                    if (!current(expected, ticket)) return
                    if (revision == null) revision = response.revision
                    if (revision != response.revision) { changed = true; break }
                    next += response.items
                    if (next.size >= response.total) break
                    check(response.items.isNotEmpty()) { "收藏列表读取不完整，请重试" }
                }
                if (!changed) {
                    songs = next; ids = next.map(id).toSet(); ready = true; error = ""
                    return
                }
            }
            error = "收藏正在其他终端更新，请重新读取"
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (current(expected, ticket)) error = failure.message ?: "收藏读取失败，请重试"
        } finally {
            if (current(expected, ticket)) loading = false
        }
    }

    suspend fun set(songId: String, selected: Boolean): Boolean {
        if (identity != connection()) reset()
        if (saving || songId.isBlank()) return false
        val expected = connection()
        if (!ready) load()
        if (!ready || expected != connection()) return false
        val ticket = ++generation
        val writeTicket = ++writeGeneration
        saving = true; loading = false; error = ""
        try {
            val result = write(listOf(songId), selected)
            if (!current(expected, ticket)) return false
            ids = result; songs = songs.filter { id(it) in result }
            saving = false
            load()
            return true
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (current(expected, ticket)) error = failure.message ?: "收藏保存失败，请重试"
            return false
        } finally {
            if (expected == connection() && writeTicket == writeGeneration) saving = false
        }
    }
}
