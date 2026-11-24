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
    var historyFavoriteOrigin: String? = null
        private set
    var bottomOrigin: String? = null
        private set
    var shelfDivider: Float? = null

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
            val returningFromBottom = previous?.startsWith("bar:") == true && shelf.contains(bottomOrigin)
            if (shelf == HomeShelf.HISTORY && !shelf.contains(previous) && !returningFromBottom) {
                historyFavoriteOrigin = previous?.takeIf { HomeShelf.FAVORITES.isSong(it) }
            }
        } else if (key.startsWith("bar:") && previous?.startsWith("bar:") != true) {
            bottomOrigin = previous?.takeIf { HomeShelf.HISTORY.contains(it) || HomeShelf.POPULAR.contains(it) }
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
    private val lowerKeys = playable.getValue(HomeShelf.HISTORY) + playable.getValue(HomeShelf.POPULAR)

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

    fun navigationEntry() = rememberedSong(HomeShelf.FAVORITES) ?: favoriteKeys.firstOrNull() ?: "search"

    fun unavailableSong(key: String?) = HomeShelf.entries.any { it.isSong(key) } && availability[key] != true

    fun collectionHeading() = links(
        left = { if (hasRetry(HomeShelf.FAVORITES)) HomeShelf.FAVORITES.retry else null },
        right = { "phone" },
        up = { "nav:首页" },
        down = { rememberedSong(HomeShelf.FAVORITES) ?: favoriteKeys.lastOrNull() ?: "search" },
    )

    fun favoriteSong(key: String) = links(
        left = { neighbor(favoriteKeys, key, -1) },
        right = { neighbor(favoriteKeys, key, 1) ?: "search" },
        up = { HomeShelf.FAVORITES.all },
        down = { belowFavorite(key) },
    )

    fun phone() = links(left = { HomeShelf.FAVORITES.all }, up = { "nav:首页" }, down = { "search" })

    fun search() = links(
        left = { favoriteKeys.lastOrNull() ?: HomeShelf.FAVORITES.all },
        up = { "phone" },
        down = { HomeShelf.POPULAR.all },
    )

    fun activityHeading(shelf: HomeShelf) = links(
        left = { HomeShelf.HISTORY.all.takeIf { shelf == HomeShelf.POPULAR } },
        right = { HomeShelf.POPULAR.all.takeIf { shelf == HomeShelf.HISTORY } },
        up = {
            if (shelf == HomeShelf.POPULAR) {
                "search"
            } else {
                home.historyFavoriteOrigin?.takeIf { it in favoriteKeys }
                    ?: nearest(HomeShelf.FAVORITES, home.center(shelf.all))
                    ?: "nav:首页"
            }
        },
        down = {
            rememberedSong(shelf) ?: nearest(shelf, home.center(shelf.all))
                ?: if (hasRetry(shelf)) shelf.retry else "bar:queue"
        },
    )

    fun activitySong(shelf: HomeShelf, key: String) = links(
        left = { neighbor(lowerKeys, key, -1) },
        right = { neighbor(lowerKeys, key, 1) },
        up = { shelf.all },
        down = { "bar:queue" },
    )

    fun retry(shelf: HomeShelf) = links(
        right = { "search".takeIf { shelf == HomeShelf.FAVORITES } },
        up = { shelf.all },
        down = { if (shelf == HomeShelf.FAVORITES) belowFavorite(shelf.retry) else "bar:queue" },
    )

    fun bottom(keys: List<String>, key: String) = links(
        left = { neighbor(keys, key, -1) },
        right = { neighbor(keys, key, 1) },
        up = {
            home.bottomOrigin?.let { restoredEntry(it) }
                ?: playable.getValue(HomeShelf.POPULAR).lastOrNull()
                ?: HomeShelf.POPULAR.all
        },
    )

    fun restoredEntry(key: String): String? {
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
        if (center == null) return keys.lastOrNull()
        return keys.minByOrNull { key -> home.center(key)?.let { abs(it - center) } ?: Float.MAX_VALUE }
    }

    private fun belowFavorite(key: String): String {
        val center = home.center(key)
        val shelf = if (center != null && center >= (home.shelfDivider ?: Float.MAX_VALUE)) HomeShelf.POPULAR else HomeShelf.HISTORY
        return nearest(shelf, center) ?: shelf.all
    }

    private fun hasRetry(shelf: HomeShelf) = memory.targets.containsKey("首页:${shelf.retry}")

    private fun neighbor(keys: List<String>, key: String, offset: Int): String? {
        val index = keys.indexOf(key)
        return if (index < 0) null else keys.getOrNull(index + offset)
    }
}
