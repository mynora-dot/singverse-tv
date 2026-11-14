package com.mynora.singverse.tv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TvSharedFavoritesTest {
    @Test fun lateNasResponseCannotReplaceNewConnection() = runBlocking {
        var connection = "nas-a:1"
        val gate = CompletableDeferred<FavoritePage<String>>()
        val favorites = TvSharedFavorites(connection = { connection }, read = {
            if (connection == "nas-a:1") gate.await() else FavoritePage(listOf("B"), 1, 3)
        }, write = { _, _ -> emptySet() }, id = { it })
        val late = async(start = CoroutineStart.UNDISPATCHED) { favorites.load() }
        connection = "nas-b:2"; favorites.reset(); favorites.load()
        assertEquals(listOf("B"), favorites.songs)
        gate.complete(FavoritePage(listOf("A"), 1, 1)); late.await()
        assertEquals(listOf("B"), favorites.songs)
    }

    @Test fun failedWritesAndReadsPreserveLastSuccessfulServerState() = runBlocking {
        var offline = false
        val favorites = TvSharedFavorites(connection = { "nas-a:1" }, read = {
            if (offline) throw IOException("offline")
            FavoritePage(listOf("server"), 1, 1)
        }, write = { _, _ -> throw IOException("save failed") }, id = { it })
        favorites.load()
        assertFalse(favorites.set("server", false))
        assertEquals(setOf("server"), favorites.ids)
        assertEquals("save failed", favorites.error)
        offline = true; favorites.load()
        assertEquals(listOf("server"), favorites.songs)
        assertEquals("offline", favorites.error)
        assertFalse(favorites.loading); assertFalse(favorites.saving)
    }

    @Test fun paginationRetriesWhenCollectionRevisionChanges() = runBlocking {
        val requests = mutableListOf<Int>()
        val favorites = TvSharedFavorites(connection = { "nas" }, read = { page ->
            requests += page
            when (requests.size) {
                1 -> FavoritePage(listOf("old"), 2, 1)
                2 -> FavoritePage(listOf("changed"), 2, 2)
                else -> FavoritePage(listOf("final"), 1, 2)
            }
        }, write = { _, _ -> emptySet() }, id = { it })
        favorites.load()
        assertEquals(listOf(0, 1, 0), requests)
        assertEquals(listOf("final"), favorites.songs)
    }

    @Test fun writesUseDesiredStateAndDoNotPublishBeforeServerCommit() = runBlocking {
        var selected = setOf("song")
        val gate = CompletableDeferred<Unit>()
        val changes = mutableListOf<Pair<List<String>, Boolean>>()
        val favorites = TvSharedFavorites(connection = { "nas" }, read = { FavoritePage(selected.toList(), selected.size, 1) }, write = { ids, desired ->
            changes += ids to desired; gate.await(); selected = if (desired) ids.toSet() else emptySet(); selected
        }, id = { it })
        favorites.load()
        val saving = async(start = CoroutineStart.UNDISPATCHED) { favorites.set("song", false) }
        assertEquals(setOf("song"), favorites.ids)
        assertTrue(favorites.saving)
        assertFalse(favorites.set("song", true))
        gate.complete(Unit); assertTrue(saving.await())
        assertEquals(listOf(listOf("song") to false), changes)
        assertTrue(favorites.songs.isEmpty())
    }

    @Test fun switchingDuringPreloadNeverWritesToTheNextNas() = runBlocking {
        var connection = "A:1"
        var writes = 0
        val gate = CompletableDeferred<FavoritePage<String>>()
        val favorites = TvSharedFavorites(connection = { connection }, read = {
            if (connection == "A:1") gate.await() else FavoritePage(emptyList(), 0, 0)
        }, write = { _, _ -> writes++; emptySet() }, id = { it })
        val late = async(start = CoroutineStart.UNDISPATCHED) { favorites.set("A-song", true) }
        connection = "B:2"; favorites.reset(); favorites.load()
        gate.complete(FavoritePage(listOf("A-song"), 1, 1))
        assertFalse(late.await()); assertEquals(0, writes); assertTrue(favorites.ids.isEmpty())
    }
}
