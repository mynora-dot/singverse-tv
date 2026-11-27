package com.mynora.singverse.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import org.json.JSONObject
import kotlin.math.abs

internal enum class HomeShelf(private val songPrefix: String, sectionName: String = songPrefix) {
    FAVORITES("favorite", "favorites"),
    HISTORY("history"),
    POPULAR("popular");

    val all = "$sectionName:all"
    val retry = "$sectionName:retry"
    fun song(id: String) = "$songPrefix:$id"
    fun isSong(key: String?) = key?.startsWith("$songPrefix:") == true && key != all && key != retry
    fun contains(key: String?) = isSong(key) || key == all || key == retry
}

internal class TvHomeFocusMemory {
    data class Song(val key: String, val center: Float?)

    private val centers = mutableMapOf<String, Float>()
    val lastSong = mutableMapOf<HomeShelf, Song>()
    var activeLowerShelf: HomeShelf = HomeShelf.HISTORY
    var bottomOrigin: String? = null
        private set

    fun center(key: String) = centers[key]

    fun position(key: String, center: Float) {
        centers[key] = center
        val shelf = HomeShelf.entries.firstOrNull { it.isSong(key) } ?: return
        if (lastSong[shelf]?.key == key) lastSong[shelf] = Song(key, center)
    }

    fun forgetPosition(key: String) {
        centers.remove(key)
    }

    fun focused(key: String, previous: String?) {
        val shelf = HomeShelf.entries.firstOrNull { it.contains(key) }
        if (shelf != null) {
            if (shelf.isSong(key)) lastSong[shelf] = Song(key, center(key))
            if (shelf == HomeShelf.HISTORY || shelf == HomeShelf.POPULAR) activeLowerShelf = shelf
        } else if (key == "tab:history") {
            activeLowerShelf = HomeShelf.HISTORY
        } else if (key == "tab:popular") {
            activeLowerShelf = HomeShelf.POPULAR
        } else if (key.startsWith("bar:") && previous?.startsWith("bar:") != true) {
            bottomOrigin = previous?.takeIf {
                HomeShelf.HISTORY.contains(it) || HomeShelf.POPULAR.contains(it) || it == "phone"
            }
        }
    }
}

internal class TvHomeFocusRoutes(
    private val memory: TvFocusMemory,
    val favorites: List<JSONObject>,
    val history: List<JSONObject>,
    val popular: List<JSONObject>,
) {
    private val home = memory.home
    val availability = buildMap {
        mapOf(HomeShelf.FAVORITES to favorites, HomeShelf.HISTORY to history, HomeShelf.POPULAR to popular)
            .forEach { (shelf, songs) ->
                songs.forEach { put(shelf.song(it.optString("id")), it.optBoolean("available", true)) }
            }
    }
    private val playable = HomeShelf.entries.associateWith { shelf ->
        availability.keys.filter { shelf.isSong(it) && availability[it] == true }
    }
    private val favoriteKeys = playable.getValue(HomeShelf.FAVORITES)

    fun target(key: String?) = key?.let { memory.targets["首页:$it"] } ?: FocusRequester.Cancel

    fun links(
        left: () -> String? = { null },
        right: () -> String? = { null },
        up: () -> String? = { null },
        down: () -> String? = { null },
    ): Modifier = Modifier.focusProperties {
        this.left = target(left())
        this.right = target(right())
        this.up = target(up())
        this.down = target(down())
    }

    fun navigationEntry() = rememberedSong(HomeShelf.FAVORITES)
        ?: favoriteKeys.firstOrNull()
        ?: if (hasRetry(HomeShelf.FAVORITES)) HomeShelf.FAVORITES.retry else "phone"

    fun unavailableSong(key: String?) = HomeShelf.entries.any { it.isSong(key) } && availability[key] != true

    private fun activeLowerShelf(): HomeShelf =
        if (home.activeLowerShelf == HomeShelf.POPULAR) HomeShelf.POPULAR else HomeShelf.HISTORY

    private fun activeTabKey(): String =
        if (activeLowerShelf() == HomeShelf.POPULAR) "tab:popular" else "tab:history"

    fun favoriteSong(key: String) = links(
        left = { neighbor(favoriteKeys, key, -1) },
        right = { neighbor(favoriteKeys, key, 1) ?: "search" },
        up = { "nav:首页" },
        down = {
            val idx = favoriteKeys.indexOf(key)
            when {
                idx <= 1 -> activeTabKey()
                idx == 2 -> "tab:popular"
                else -> activeLowerShelf().all
            }
        },
    )

    fun search() = links(
        left = { favoriteKeys.lastOrNull() ?: if (hasRetry(HomeShelf.FAVORITES)) HomeShelf.FAVORITES.retry else null },
        up = { "nav:首页" },
        down = { HomeShelf.FAVORITES.all },
    )

    fun collectionDock() = links(
        left = { favoriteKeys.lastOrNull() ?: if (hasRetry(HomeShelf.FAVORITES)) HomeShelf.FAVORITES.retry else null },
        up = { "search" },
        down = { "phone" },
    )

    fun phone() = links(
        left = {
            val shelf = activeLowerShelf()
            val keys = playable.getValue(shelf)
            keys.getOrNull(3) ?: keys.getOrNull(1) ?: keys.lastOrNull() ?: if (hasRetry(shelf)) shelf.retry else shelf.all
        },
        up = { HomeShelf.FAVORITES.all },
        down = { "bar:queue" },
    )

    fun lowerTab(shelf: HomeShelf) = links(
        left = { if (shelf == HomeShelf.POPULAR) "tab:history" else null },
        right = { if (shelf == HomeShelf.HISTORY) "tab:popular" else activeLowerShelf().all },
        up = {
            rememberedSong(HomeShelf.FAVORITES)
                ?: favoriteKeys.firstOrNull()
                ?: if (hasRetry(HomeShelf.FAVORITES)) HomeShelf.FAVORITES.retry else "nav:首页"
        },
        down = {
            val active = activeLowerShelf()
            val keys = playable.getValue(active)
            keys.firstOrNull() ?: if (hasRetry(active)) active.retry else "bar:queue"
        },
    )

    fun activityHeading(shelf: HomeShelf) = links(
        left = { "tab:popular" },
        right = { "phone" },
        up = { favoriteKeys.lastOrNull() ?: rememberedSong(HomeShelf.FAVORITES) ?: "nav:首页" },
        down = {
            val keys = playable.getValue(shelf)
            keys.getOrNull(1) ?: keys.firstOrNull() ?: if (hasRetry(shelf)) shelf.retry else "bar:queue"
        },
    )

    fun activitySong(shelf: HomeShelf, key: String) = links(
        left = {
            val keys = playable.getValue(shelf)
            val idx = keys.indexOf(key)
            if (idx == 1 || idx == 3) keys.getOrNull(idx - 1) else null
        },
        right = {
            val keys = playable.getValue(shelf)
            val idx = keys.indexOf(key)
            when (idx) {
                0 -> keys.getOrNull(1) ?: "phone"
                1 -> "phone"
                2 -> keys.getOrNull(3) ?: "phone"
                else -> "phone"
            }
        },
        up = {
            val keys = playable.getValue(shelf)
            val idx = keys.indexOf(key)
            when (idx) {
                0 -> activeTabKey()
                1 -> shelf.all
                2 -> keys.getOrNull(0) ?: activeTabKey()
                3 -> keys.getOrNull(1) ?: shelf.all
                else -> activeTabKey()
            }
        },
        down = {
            val keys = playable.getValue(shelf)
            val idx = keys.indexOf(key)
            when (idx) {
                0 -> keys.getOrNull(2) ?: "bar:queue"
                1 -> keys.getOrNull(3) ?: keys.getOrNull(2) ?: "bar:queue"
                else -> "bar:queue"
            }
        },
    )

    fun retry(shelf: HomeShelf) = links(
        right = { if (shelf == HomeShelf.FAVORITES) "phone" else HomeShelf.FAVORITES.all },
        up = { if (shelf == HomeShelf.FAVORITES) "nav:首页" else activeTabKey() },
        down = { if (shelf == HomeShelf.FAVORITES) activeTabKey() else "bar:queue" },
    )

    fun bottom(keys: List<String>, key: String) = links(
        left = { neighbor(keys, key, -1) },
        right = { neighbor(keys, key, 1) },
        up = {
            home.bottomOrigin?.let { restoredEntry(it) }
                ?: playable.getValue(activeLowerShelf()).lastOrNull()
                ?: HomeShelf.FAVORITES.all
        },
    )

    fun restoredEntry(key: String): String? {
        if (key == "phone" || key == HomeShelf.FAVORITES.all) return key
        val shelf = HomeShelf.entries.firstOrNull { it.contains(key) }
        return when {
            shelf?.isSong(key) == true ->
                key.takeIf { availability[it] == true } ?: rememberedSong(shelf) ?: shelf.all
            shelf != null -> key.takeIf { memory.targets.containsKey("首页:$it") } ?: shelf.all
            key.startsWith("bar:") -> "bar:queue"
            else -> null
        }
    }

    private fun rememberedSong(shelf: HomeShelf): String? {
        val song = home.lastSong[shelf] ?: return null
        return song.key.takeIf { availability[it] == true } ?: nearest(shelf, song.center)
    }

    private fun nearest(shelf: HomeShelf, center: Float?): String? {
        val keys = playable.getValue(shelf)
        if (center == null) return keys.firstOrNull()
        return keys.minByOrNull { key -> home.center(key)?.let { abs(it - center) } ?: Float.MAX_VALUE }
    }

    private fun hasRetry(shelf: HomeShelf) = memory.targets.containsKey("首页:${shelf.retry}")

    private fun neighbor(keys: List<String>, key: String, offset: Int): String? {
        val index = keys.indexOf(key)
        return if (index < 0) null else keys.getOrNull(index + offset)
    }
}
