package com.mynora.singverse.tv

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TvHomeActivityTest {
    @Test fun historyAndPopularityHaveIndependentRequestsAndFailureState() = runBlocking {
        val waiting = CompletableDeferred<HomeActivityPage<String>>()
        val history = TvHomeActivity({ "nas:1" }, { waiting.await() }, { it })
        val popular = TvHomeActivity({ "nas:1" }, { HomeActivityPage(listOf("popular"), 1, 0, 20) }, { it })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { history.load() }
        assertTrue(history.loading); assertFalse(history.ready)
        popular.load()
        assertEquals(listOf("popular"), popular.songs); assertTrue(popular.ready)
        waiting.completeExceptionally(IOException("历史读取失败")); pending.await()
        assertEquals("历史读取失败", history.error); assertFalse(history.ready)
        assertFalse(history.loading); assertEquals("", popular.error)
    }

    @Test fun aToBToANeverAcceptsTheFirstConnectionResponse() = runBlocking {
        var connection = "A:1"
        val oldResponse = CompletableDeferred<HomeActivityPage<String>>()
        val activity = TvHomeActivity(connection = { connection }, read = {
            if (connection == "A:1") oldResponse.await() else HomeActivityPage(listOf(connection), 1, 0, 20)
        }, id = { it })
        val old = async(start = CoroutineStart.UNDISPATCHED) { activity.load() }
        connection = "B:2"; activity.reset(); activity.load()
        assertEquals(listOf("B:2"), activity.songs)
        connection = "A:3"; activity.reset(); activity.load()
        oldResponse.complete(HomeActivityPage(listOf("stale A:1"), 1, 0, 20)); old.await()
        assertEquals(listOf("A:3"), activity.songs); assertEquals("", activity.error)
    }

    @Test fun switchingNasAutomaticallyClearsPreviousListBeforeRead() = runBlocking {
        var connection = "A:1"
        var response = HomeActivityPage(listOf("A"), 1, 0, 20)
        val waiting = CompletableDeferred<HomeActivityPage<String>>()
        val activity = TvHomeActivity({ connection }, { if (connection == "A:1") response else waiting.await() }, { it })
        activity.load()
        connection = "B:2"
        val pending = async(start = CoroutineStart.UNDISPATCHED) { activity.load() }
        assertTrue(activity.songs.isEmpty()); assertFalse(activity.ready); assertTrue(activity.loading)
        waiting.complete(HomeActivityPage(emptyList(), 0, 0, 20)); pending.await()
        assertTrue(activity.ready); assertEquals(0L, activity.total)
    }

    @Test fun staleFailureDoesNotClearNewerLoadingOrPublishAnError() = runBlocking {
        var requests = 0
        val oldResponse = CompletableDeferred<HomeActivityPage<String>>()
        val newResponse = CompletableDeferred<HomeActivityPage<String>>()
        val activity = TvHomeActivity({ "nas:1" }, { if (++requests == 1) oldResponse.await() else newResponse.await() }, { it })
        val old = async(start = CoroutineStart.UNDISPATCHED) { activity.load() }
        val newer = async(start = CoroutineStart.UNDISPATCHED) { activity.load() }
        oldResponse.completeExceptionally(IOException("old error")); old.await()
        assertTrue(activity.loading); assertEquals("", activity.error)
        newResponse.complete(HomeActivityPage(listOf("new"), 1, 0, 20)); newer.await()
        assertFalse(activity.loading); assertEquals(listOf("new"), activity.songs)
    }

    @Test fun refreshFailurePreservesServerDataAndCanRecover() = runBlocking {
        var offline = false
        val activity = TvHomeActivity({ "nas:1" }, {
            if (offline) throw IOException("offline")
            HomeActivityPage(listOf("song"), 1, 0, 20)
        }, { it })
        activity.load(); offline = true; activity.load()
        assertTrue(activity.ready); assertEquals(listOf("song"), activity.songs)
        assertEquals(1L, activity.total); assertEquals("offline", activity.error)
        offline = false; activity.load()
        assertEquals("", activity.error); assertFalse(activity.loading)
    }

    @Test fun paginationUsesServerOffsetsAndDeduplicatesMovingHistory() = runBlocking {
        val pages = mutableListOf<Int>()
        val activity = TvHomeActivity({ "nas:1" }, { page ->
            pages += page
            HomeActivityPage(when (page) { 0 -> listOf("a", "b"); 1 -> listOf("b", "c"); else -> listOf("d") }, 5, page, 2)
        }, { it })
        activity.load(); assertTrue(activity.hasMore)
        activity.load(append = true); assertTrue(activity.hasMore)
        activity.load(append = true); assertFalse(activity.hasMore)
        activity.load(append = true)
        assertEquals(listOf(0, 1, 2), pages)
        assertEquals(listOf("a", "b", "c", "d"), activity.songs)
    }

    @Test fun failedMorePageKeepsExistingSongsAndRetriesSameOffset() = runBlocking {
        var fail = true
        val pages = mutableListOf<Int>()
        val activity = TvHomeActivity({ "nas:1" }, { page ->
            pages += page
            if (page == 1 && fail) throw IOException("load more failed")
            HomeActivityPage(if (page == 0) listOf("a", "b") else listOf("c"), 3, page, 2)
        }, { it })
        activity.load(); activity.load(append = true)
        assertEquals(listOf("a", "b"), activity.songs); assertTrue(activity.hasMore)
        assertEquals("load more failed", activity.error)
        fail = false; activity.load(append = true)
        assertEquals(listOf(0, 1, 1), pages); assertEquals(listOf("a", "b", "c"), activity.songs)
        assertFalse(activity.hasMore); assertEquals("", activity.error)
    }

    @Test fun foregroundRefreshPreservesTheEntireLoadedWindowUntilEveryPageCompletes() = runBlocking {
        var refreshing = false
        val requests = mutableListOf<Int>()
        val secondPage = CompletableDeferred<HomeActivityPage<String>>()
        val activity = TvHomeActivity({ "nas:1" }, { page ->
            requests += page
            if (refreshing && page == 1) secondPage.await()
            else HomeActivityPage(if (page == 0) listOf(if (refreshing) "updated-a" else "a", "b") else listOf("c", "deep-focused-song"), 5, page, 2)
        }, { it })
        activity.load(); activity.load(append = true)
        val oldWindow = activity.songs
        refreshing = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { activity.load() }
        assertEquals("后续页完成前不部分发布第一页", oldWindow, activity.songs)
        assertTrue(activity.loading); assertTrue(activity.ready)
        secondPage.complete(HomeActivityPage(listOf("c", "deep-focused-song"), 5, 1, 2)); pending.await()
        assertEquals(listOf(0, 1, 0, 1), requests)
        assertEquals(listOf("updated-a", "b", "c", "deep-focused-song"), activity.songs)
        assertTrue(activity.hasMore); assertFalse(activity.loading)
    }

    @Test fun laterRefreshPageFailureKeepsTheOriginalWindowAndPagination() = runBlocking {
        var refreshing = false
        val requests = mutableListOf<Int>()
        val activity = TvHomeActivity({ "nas:1" }, { page ->
            requests += page
            if (refreshing && page == 1) throw IOException("第二页读取失败")
            HomeActivityPage(when (page) { 0 -> listOf(if (refreshing) "changed" else "a", "b"); 1 -> listOf("c", "d"); else -> listOf("e") }, 5, page, 2)
        }, { it })
        activity.load(); activity.load(append = true)
        refreshing = true; activity.load()
        assertEquals(listOf("a", "b", "c", "d"), activity.songs)
        assertEquals(5L, activity.total); assertTrue(activity.hasMore); assertTrue(activity.ready)
        assertEquals("第二页读取失败", activity.error)
        refreshing = false; activity.load(append = true)
        assertEquals(listOf(0, 1, 0, 1, 2), requests)
        assertEquals(listOf("a", "b", "c", "d", "e"), activity.songs)
    }

    @Test fun changingNasDuringWindowRefreshDiscardsAllPendingPages() = runBlocking {
        var connection = "A:1"
        var refreshing = false
        val pendingPage = CompletableDeferred<HomeActivityPage<String>>()
        val requests = mutableListOf<Pair<String, Int>>()
        val activity = TvHomeActivity({ connection }, { page ->
            requests += connection to page
            if (connection == "B:2") HomeActivityPage(listOf("new NAS"), 1, page, 2)
            else if (refreshing && page == 1) pendingPage.await()
            else HomeActivityPage(if (page == 0) listOf(if (refreshing) "changed A" else "a", "b") else listOf("c", "d"), 5, page, 2)
        }, { it })
        activity.load(); activity.load(append = true)
        refreshing = true
        val old = async(start = CoroutineStart.UNDISPATCHED) { activity.load() }
        connection = "B:2"; activity.reset(); activity.load()
        pendingPage.complete(HomeActivityPage(listOf("stale c", "stale d"), 5, 1, 2)); old.await()
        assertEquals(listOf("new NAS"), activity.songs); assertEquals(1L, activity.total)
        assertEquals("", activity.error); assertFalse(activity.loading); assertFalse(activity.hasMore)
        assertEquals(listOf("A:1" to 0, "A:1" to 1, "A:1" to 0, "A:1" to 1, "B:2" to 0), requests)
    }

    @Test fun smallerServerTotalStopsWindowRefreshBeforeUnneededPages() = runBlocking {
        var shrinking = false
        val requests = mutableListOf<Int>()
        val activity = TvHomeActivity({ "nas:1" }, { page ->
            requests += page
            if (shrinking) HomeActivityPage(listOf("remaining"), 1, page, 2)
            else HomeActivityPage(if (page == 0) listOf("a", "b") else listOf("c", "d"), 4, page, 2)
        }, { it })
        activity.load(); activity.load(append = true)
        shrinking = true; activity.load()
        assertEquals(listOf(0, 1, 0), requests); assertEquals(listOf("remaining"), activity.songs)
        assertEquals(1L, activity.total); assertFalse(activity.hasMore)
    }

    @Test fun cancellationDuringWindowRefreshKeepsThePreviousSuccessfulWindow() = runBlocking {
        var refreshing = false
        val secondPage = CompletableDeferred<HomeActivityPage<String>>()
        val activity = TvHomeActivity({ "nas:1" }, { page ->
            if (refreshing && page == 1) secondPage.await()
            else HomeActivityPage(if (page == 0) listOf(if (refreshing) "changed" else "a", "b") else listOf("c", "d"), 4, page, 2)
        }, { it })
        activity.load(); activity.load(append = true)
        refreshing = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { activity.load() }
        pending.cancelAndJoin()
        assertEquals(listOf("a", "b", "c", "d"), activity.songs)
        assertEquals(4L, activity.total); assertTrue(activity.ready); assertFalse(activity.hasMore)
        assertFalse(activity.loading); assertEquals("", activity.error)
    }

    @Test fun invalidPaginationIsNotMisrepresentedAsAnEmptySuccessfulList() = runBlocking {
        val activity = TvHomeActivity<String>({ "nas:1" }, { HomeActivityPage(emptyList(), 3, 0, 20) }, { it })
        activity.load()
        assertFalse(activity.ready); assertTrue(activity.error.isNotBlank()); assertFalse(activity.loading)
    }

    @Test fun cancellationReleasesLoadingAndNeverPublishesSuccess() = runBlocking {
        val waiting = CompletableDeferred<HomeActivityPage<String>>()
        val activity = TvHomeActivity({ "nas:1" }, { waiting.await() }, { it })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { activity.load() }
        pending.cancelAndJoin()
        assertFalse(activity.loading); assertFalse(activity.ready); assertTrue(activity.songs.isEmpty())
        assertEquals("", activity.error)
    }
}
