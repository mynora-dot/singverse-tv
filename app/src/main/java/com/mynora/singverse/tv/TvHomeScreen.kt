package com.mynora.singverse.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import org.json.JSONObject

/** The home shelves show NAS data; song selection keeps the existing detail/queue flow. */
@Composable internal fun TvHomeScreen(app: TvApp, focus: TvHomeFocusRoutes, onSearch: () -> Unit, onPhone: () -> Unit) {
    val favorites = focus.favorites
    val memory = LocalTvFocusMemory.current
    var lowerShelf by remember { mutableStateOf(memory.home.activeLowerShelf.takeIf { it == HomeShelf.POPULAR } ?: HomeShelf.HISTORY) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lowerHeight = 160.dp
        val upperHeight = (maxHeight - lowerHeight - 14.dp).coerceAtLeast(196.dp)
        val rightWidth = 160.dp

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // UPPER BAND: 4 Monumental Square Favorite Covers + Pixel-Aligned Search & All Favorites
            Row(Modifier.height(upperHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("我的收藏", color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    if (favorites.isEmpty()) HomeSectionState(
                        Modifier.weight(1f), app.favoritesLoading, app.favoritesReady, app.favoritesError,
                        "还没有收藏歌曲", "在手机、后台添加，或播放时按「收藏」。", HomeShelf.FAVORITES.retry,
                        app::refreshFavorites, retryModifier = focus.retry(HomeShelf.FAVORITES),
                    ) else {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val landscape = favorites.size <= 2
                            val columns = if (landscape) favorites.size else 4
                            val gap = 14.dp
                            val slotWidth = (maxWidth - gap * (columns - 1)) / columns
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                favorites.forEach { song ->
                                    val id = song.getString("id")
                                    key(id) {
                                        HomeFavoriteCard(
                                            app,
                                            song,
                                            landscape,
                                            Modifier
                                                .width(slotWidth)
                                                .fillMaxHeight()
                                                .then(focus.favoriteSong(HomeShelf.FAVORITES.song(id))),
                                        )
                                    }
                                }
                            }
                        }
                        if (app.favoritesError.isNotBlank()) HomeStaleNotice("收藏更新失败，可在全部收藏中重试")
                    }
                }
                // Upper-Right Action Group: top & bottom aligned pixel-for-pixel with the 4 album covers
                Column(
                    Modifier
                        .width(rightWidth)
                        .fillMaxHeight()
                        .padding(top = 35.dp, bottom = 44.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HomeDockPill(
                        icon = "search",
                        label = "搜索歌曲",
                        subtitle = "歌名 · 歌手 · 拼音",
                        accent = Brand,
                        focusKey = "search",
                        modifier = Modifier.weight(1f).then(focus.search()),
                        onClick = onSearch,
                    )
                    HomeDockPill(
                        icon = "playlist",
                        label = "全部收藏",
                        subtitle = if (app.favoriteSongs().isNotEmpty()) "${app.favoriteSongs().size} 首收藏歌曲" else "查看收藏列表",
                        accent = Accent,
                        focusKey = HomeShelf.FAVORITES.all,
                        modifier = Modifier.weight(1f).then(focus.collectionDock()),
                        onClick = { app.navigate("收藏") },
                    )
                }
            }

            // LOWER BAND: 2x2 Horizontal Song Cards + Exact 1:1 Pure Square QR Card
            Row(Modifier.height(lowerHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            Modifier
                                .clip(GlassShape.pill)
                                .background(Color(0x0FFFFFFF))
                                .border(1.dp, Color(0x14FFFFFF), GlassShape.pill)
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HomeShelfTab(
                                label = "历史记录",
                                focusKey = "tab:history",
                                selected = lowerShelf == HomeShelf.HISTORY,
                                modifier = focus.lowerTab(HomeShelf.HISTORY),
                                onFocus = {
                                    if (memory.directionalInput && memory.activeDialogs == 0) {
                                        lowerShelf = HomeShelf.HISTORY
                                        memory.home.activeLowerShelf = HomeShelf.HISTORY
                                    }
                                },
                            ) {
                                lowerShelf = HomeShelf.HISTORY
                                memory.home.activeLowerShelf = HomeShelf.HISTORY
                            }
                            HomeShelfTab(
                                label = "热门歌曲",
                                focusKey = "tab:popular",
                                selected = lowerShelf == HomeShelf.POPULAR,
                                modifier = focus.lowerTab(HomeShelf.POPULAR),
                                onFocus = {
                                    if (memory.directionalInput && memory.activeDialogs == 0) {
                                        lowerShelf = HomeShelf.POPULAR
                                        memory.home.activeLowerShelf = HomeShelf.POPULAR
                                    }
                                },
                            ) {
                                lowerShelf = HomeShelf.POPULAR
                                memory.home.activeLowerShelf = HomeShelf.POPULAR
                            }
                        }
                        HomeMorePill(
                            label = "查看全部",
                            focusKey = lowerShelf.all,
                            modifier = focus.activityHeading(lowerShelf),
                        ) {
                            app.navigate(if (lowerShelf == HomeShelf.POPULAR) "热门歌曲" else "历史记录")
                        }
                    }
                    AnimatedContent(
                        targetState = lowerShelf,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            fadeIn(tween(160, delayMillis = 30, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(90, easing = FastOutSlowInEasing))
                        },
                        label = "home-lower-shelf",
                    ) { targetShelf ->
                        val isCurrent = targetShelf == lowerShelf
                        val shelfSongs = if (targetShelf == HomeShelf.POPULAR) focus.popular else focus.history
                        val containerMod = if (isCurrent) Modifier.fillMaxSize() else Modifier.fillMaxSize().focusProperties { canFocus = false }
                        CompositionLocalProvider(LocalTvFocusPage provides if (isCurrent) LocalTvFocusPage.current else "__exiting__:${targetShelf.name}") {
                            Box(containerMod) {
                                if (shelfSongs.isEmpty()) {
                                    if (targetShelf == HomeShelf.POPULAR) HomeSectionState(
                                        Modifier.fillMaxSize(), app.homePopularLoading, app.homePopularReady, app.homePopularError,
                                        "近 30 天暂无热门歌曲", "有播放记录后，按曲库热度排序。", HomeShelf.POPULAR.retry,
                                        app::refreshHomePopular, retryModifier = focus.retry(HomeShelf.POPULAR),
                                    ) else HomeSectionState(
                                        Modifier.fillMaxSize(), app.homeHistoryLoading, app.homeHistoryReady, app.homeHistoryError,
                                        "还没有播放记录", "唱过的歌曲会出现在这里。", HomeShelf.HISTORY.retry,
                                        app::refreshHomeHistory, retryModifier = focus.retry(HomeShelf.HISTORY),
                                    )
                                } else {
                                    HomeActivityGrid(app, shelfSongs, targetShelf, focus, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
                // Lower-Right Pure Square QR Card (top flush with tab bar, bottom flush with Row 2B)
                PhoneConnectionCard(
                    app,
                    Modifier
                        .size(rightWidth, lowerHeight)
                        .then(focus.phone()),
                    onPhone,
                )
            }
        }
    }
}

@Composable private fun HomeActivityGrid(
    app: TvApp,
    songs: List<JSONObject>,
    shelf: HomeShelf,
    focus: TvHomeFocusRoutes,
    modifier: Modifier = Modifier,
) {
    val row1 = songs.take(2)
    val row2 = songs.drop(2).take(2)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row1.forEachIndexed { idx, song ->
                val id = song.optString("id")
                val focusKey = shelf.song(id)
                key(id) {
                    HomeHorizontalSongCard(
                        app = app,
                        song = song,
                        rank = idx + 1,
                        focusKey = focusKey,
                        modifier = Modifier.weight(1f).fillMaxHeight().then(focus.activitySong(shelf, focusKey)),
                    )
                }
            }
            if (row1.size == 1) Spacer(Modifier.weight(1f))
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row2.forEachIndexed { idx, song ->
                val id = song.optString("id")
                val focusKey = shelf.song(id)
                key(id) {
                    HomeHorizontalSongCard(
                        app = app,
                        song = song,
                        rank = idx + 3,
                        focusKey = focusKey,
                        modifier = Modifier.weight(1f).fillMaxHeight().then(focus.activitySong(shelf, focusKey)),
                    )
                }
            }
            if (row2.size == 1) Spacer(Modifier.weight(1f))
            if (row2.isEmpty()) Spacer(Modifier.fillMaxSize())
        }
    }
}

@Composable private fun HomeHorizontalSongCard(
    app: TvApp,
    song: JSONObject,
    rank: Int?,
    focusKey: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val available = song.optBoolean("available", true)
    val shape = TvShape.control
    Button(
        onClick = { app.openSong(song) },
        enabled = available,
        modifier = modifier
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused && available, scaleTarget = 1.022f, liftDp = 0.8.dp)
            .then(
                if (focused) Modifier.tvPlatinumFocus(true, shape)
                else Modifier.clip(shape).background(Color(0x0DFFFFFF))
            ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Muted,
        ),
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rank != null) {
                Text(
                    "0$rank",
                    color = when {
                        focused -> Glass.focusInk
                        rank == 1 -> Brand
                        else -> Muted
                    },
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(20.dp),
                )
            }
            SongArtwork(app, song, Modifier.size(38.dp).alpha(if (available) 1f else .45f), shape = RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    song.optString("title"),
                    color = if (focused) Glass.focusInk else if (available) Paper else Muted,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artists(),
                    color = if (focused) Glass.focusInkMuted else Muted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HomeGlyph("chevron", Modifier.size(11.dp), if (focused) Glass.focusInkMuted else Muted.copy(alpha = 0.55f))
        }
    }
}

@Composable private fun HomeShelfTab(
    label: String,
    focusKey: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = GlassShape.pill
    Button(
        onClick = onClick,
        modifier = modifier
            .height(26.dp)
            .tvFocusTarget(focusKey)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .tvSpringFocus(focused, scaleTarget = 1.04f, liftDp = 0.5.dp)
            .then(
                when {
                    focused -> Modifier.tvPlatinumFocus(true, shape)
                    selected -> Modifier.clip(shape).background(Color(0x1EFFFFFF))
                    else -> Modifier
                }
            ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Paper else Glass.inkSecondary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable private fun HomeMorePill(
    label: String,
    focusKey: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = GlassShape.pill
    Button(
        onClick = onClick,
        modifier = modifier
            .height(26.dp)
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.04f, liftDp = 0.5.dp)
            .then(if (focused) Modifier.tvPlatinumFocus(true, shape) else Modifier),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Muted.copy(alpha = 0.80f),
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
    ) {
        Text(label, fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium)
        Spacer(Modifier.width(3.dp))
        HomeGlyph("chevron", Modifier.size(10.dp), if (focused) Glass.focusInk else Muted.copy(alpha = 0.80f))
    }
}

@Composable private fun HomeDockPill(
    icon: String,
    label: String,
    subtitle: String,
    accent: Color,
    focusKey: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.035f, liftDp = 1.dp)
            .then(
                if (focused) Modifier.tvPlatinumFocus(true, shape)
                else Modifier.glassSurface(shape, highlighted = false, base = Glass.surfaceCard, elevation = 3.dp)
            ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (focused) Glass.focusInk.copy(alpha = 0.10f) else accent.copy(alpha = 0.18f))
                    .border(
                        1.dp,
                        if (focused) Glass.focusInk.copy(alpha = 0.14f) else accent.copy(alpha = 0.30f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                HomeGlyph(icon, Modifier.size(15.dp), if (focused) Glass.focusInk else accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    color = if (focused) Glass.focusInk else Paper,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = if (focused) Glass.focusInkMuted else Muted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable private fun HomeFavoriteCard(app: TvApp, song: JSONObject, landscape: Boolean, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val available = song.optBoolean("available", true)
    val shape = TvShape.card
    val playingId = app.snapshot.optJSONArray("queue")?.objects()?.firstOrNull { it.optString("status") == "PLAYING" }?.optJSONObject("song")?.optString("id").orEmpty()
    val isPlaying = playingId.isNotBlank() && playingId == song.optString("id")
    Button(
        onClick = { app.openSong(song) },
        enabled = available,
        modifier = modifier
            .tvFocusTarget(HomeShelf.FAVORITES.song(song.getString("id")))
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused && available, scaleTarget = 1.05f, liftDp = 1.5.dp)
            .then(
                if (landscape) Modifier
                    .glassSurface(shape, highlighted = focused, base = if (focused) Glass.surfaceFocus else Glass.surfaceCard, elevation = if (focused) 8.dp else 4.dp)
                    .tvFocus(focused, shape)
                else Modifier
            ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(if (landscape) 12.dp else 3.dp),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Paper,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Muted,
        ),
    ) {
        if (landscape) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cover = minOf(124.dp, maxWidth * .42f, maxHeight)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(cover).alpha(if (available) 1f else .45f)) {
                        SongArtwork(app, song, Modifier.fillMaxSize(), shape = TvShape.control)
                        if (isPlaying) HomePlayingBadge(app.playing, Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                    HomeFavoriteText(song, available, landscape = true, modifier = Modifier.weight(1f))
                }
            }
        } else {
            val coverShape = RoundedCornerShape(14.dp)
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                val coverSize = minOf(maxWidth, maxHeight - 46.dp).coerceAtLeast(60.dp)
                Column(Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(coverSize).alpha(if (available) 1f else .45f).tvFocus(focused, coverShape)) {
                        SongArtwork(app, song, Modifier.fillMaxSize(), shape = coverShape)
                        if (isPlaying) HomePlayingBadge(app.playing, Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                    HomeFavoriteText(song, available, landscape = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable private fun HomeCoverShelfRow(
    app: TvApp,
    songs: List<JSONObject>,
    shelf: HomeShelf,
    focus: TvHomeFocusRoutes,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 14.dp
        val tileWidth = ((maxWidth - gap * 3) / 4f).coerceAtLeast(1.dp)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            songs.forEach { song ->
                val id = song.optString("id")
                val focusKey = shelf.song(id)
                key(id) {
                    HomeShelfCoverTile(
                        app, song, focusKey,
                        Modifier.width(tileWidth).fillMaxHeight().then(focus.activitySong(shelf, focusKey)),
                    )
                }
            }
        }
    }
}

@Composable private fun HomeShelfCoverTile(app: TvApp, song: JSONObject, focusKey: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val available = song.optBoolean("available", true)
    val coverShape = RoundedCornerShape(14.dp)
    Button(
        onClick = { app.openSong(song) },
        enabled = available,
        modifier = modifier
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused && available, scaleTarget = 1.05f, liftDp = 1.5.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(3.dp),
        shape = ButtonDefaults.shape(coverShape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Paper,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Muted,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            val coverSize = minOf(maxWidth, maxHeight - 44.dp).coerceAtLeast(56.dp)
            Column(Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(coverSize).alpha(if (available) 1f else .45f).tvFocus(focused, coverShape)) {
                    SongArtwork(app, song, Modifier.fillMaxSize(), shape = coverShape)
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        song.optString("title"),
                        color = if (available) Paper else Muted,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artists(),
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable private fun HomePlayingBadge(playing: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.brandGlassSurface(GlassShape.pill).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LiveEqualizer(playing, color = OnBrand)
        Text(if (playing) "演唱中" else "已暂停", color = OnBrand, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun HomeFavoriteText(song: JSONObject, available: Boolean, landscape: Boolean, modifier: Modifier = Modifier) {
    val lines = if (landscape) 2 else 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 1.dp)) {
        Text(
            song.optString("title"),
            color = Paper,
            fontSize = if (landscape) 19.sp else 13.5.sp,
            lineHeight = if (landscape) 24.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (available) song.artists() else "${song.artists()} · 暂不可用",
            color = Muted,
            fontSize = if (landscape) 12.5.sp else 11.sp,
            lineHeight = if (landscape) 18.sp else 16.sp,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable internal fun HomeCompactSong(
    app: TvApp,
    song: JSONObject,
    focusKey: String,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    trailing: String = "",
) {
    var focused by remember { mutableStateOf(false) }
    val available = song.optBoolean("available", true)
    val shape = TvShape.row
    val rankColor = when (rank) {
        1 -> Brand
        2 -> Glass.amber
        3 -> Accent
        else -> Glass.champagne
    }
    Button(
        onClick = { app.openSong(song) },
        modifier = modifier
            .fillMaxWidth()
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused && available, scaleTarget = 1.02f, liftDp = 0.8.dp)
            .then(
                if (focused) Modifier.glassSurface(shape, highlighted = true, intensity = 1f, base = Glass.surfaceFocus, elevation = 5.dp)
                else Modifier
            )
            .tvFocus(focused, shape),
        enabled = available,
        contentPadding = PaddingValues(horizontal = if (rank == null) 8.dp else 10.dp, vertical = 2.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Paper,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Muted,
        ),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (rank != null) {
                Text(
                    rank.toString().padStart(2, '0'),
                    color = rankColor,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(20.dp),
                )
            }
            BoxWithConstraints {
                SongArtwork(
                    app,
                    song,
                    Modifier.size(minOf(if (rank == null) 30.dp else 38.dp, maxHeight)).alpha(if (available) 1f else .45f),
                    shape = RoundedCornerShape(8.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    song.optString("title"),
                    color = if (available) Paper else Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artists() + if (available) "" else " · 暂不可用",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing.isNotEmpty()) Text(trailing, color = Muted, fontSize = 10.5.sp, lineHeight = 15.sp)
            HomeGlyph("chevron", Modifier.size(11.dp), if (focused) Paper else Muted)
        }
    }
}

@Composable internal fun HomeSectionState(
    modifier: Modifier,
    loading: Boolean,
    ready: Boolean,
    error: String,
    emptyTitle: String,
    emptyDetail: String,
    retryKey: String,
    retry: () -> Unit,
    retryModifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().glassSurface(TvShape.card, base = Glass.surfaceCard).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when { loading -> "正在读取…"; error.isNotBlank() || !ready -> "暂时无法读取"; else -> emptyTitle },
            color = Paper,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (error.isNotBlank() || (!ready && !loading)) "检查 NAS 连接后重试" else if (loading) "正在同步 NAS 曲库" else emptyDetail,
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        if (showsHomeRetry(loading, ready, error)) HomeButton("重新读取", retryModifier, focusKey = retryKey, onClick = retry)
    }
}

internal fun showsHomeRetry(loading: Boolean, ready: Boolean, error: String) = !loading && (error.isNotBlank() || !ready)

@Composable private fun HomeStaleNotice(message: String) {
    Text(message, color = Muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

internal fun homeHistoryDate(value: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String {
    val date = runCatching { java.time.OffsetDateTime.parse(value).atZoneSameInstant(now.zone).toLocalDate() }.getOrNull() ?: return "时间未知"
    return when (date) {
        now.toLocalDate() -> "今天"
        now.toLocalDate().minusDays(1) -> "昨天"
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern(if (date.year == now.year) "MM/dd" else "yyyy/MM/dd"))
    }
}

@Composable internal fun PhoneConnectionCard(app: TvApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.card
    Button(
        onClick = onClick,
        modifier = modifier
            .tvFocusTarget("phone")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.04f, liftDp = 1.5.dp)
            .glassSurface(
                shape,
                highlighted = focused,
                base = if (focused) Glass.surfaceFocus else Glass.surfaceCard,
                elevation = if (focused) 8.dp else 4.dp,
                backdropBlur = false,
            )
            .tvFocus(focused, shape),
        contentPadding = PaddingValues(0.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Paper,
        ),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val qrSize = minOf(maxWidth, maxHeight).coerceAtLeast(96.dp)
            Box(
                Modifier
                    .size(qrSize)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (app.invitation.isNotEmpty() && app.online) Qr(app.invitation, Modifier.fillMaxSize())
                else Text(
                    if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成中…" else "确认以重试",
                    color = Ink,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable internal fun TvNavigation(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val memory = LocalTvFocusMemory.current
    var focused by remember { mutableStateOf(false) }
    val showFocus = focused && !memory.restoring
    val shape = GlassShape.pill
    Button(
        onClick,
        modifier
            .height(32.dp)
            .semantics { this.selected = selected; stateDescription = if (selected) "当前页面" else "" }
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(showFocus, scaleTarget = 1.045f, liftDp = 0.5.dp)
            .then(
                when {
                    showFocus -> Modifier.tvPlatinumFocus(true, shape)
                    selected -> Modifier.clip(shape).background(Glass.surfaceSelected)
                    else -> Modifier
                }
            ),
        contentPadding = PaddingValues(horizontal = 17.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Paper else Muted,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = if (showFocus) Glass.focusInk else if (selected) Paper else Muted,
        ),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = if (selected || showFocus) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable internal fun HomeButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusKey: String = label,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = GlassShape.pill
    Button(
        onClick,
        modifier
            .height(32.dp)
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.04f, liftDp = 0.8.dp)
            .then(
                when {
                    primary -> Modifier.brandGlassSurface(shape, focused = focused).tvFocus(focused, shape)
                    focused -> Modifier.tvPlatinumFocus(true, shape)
                    else -> Modifier.glassSurface(shape, highlighted = false, base = Glass.surfaceFloat, elevation = 2.dp)
                }
            ),
        contentPadding = PaddingValues(horizontal = if (label in listOf("‹", "›")) 0.dp else 14.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (primary) OnBrand else Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = if (primary) OnBrand else Glass.focusInk,
        ),
    ) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun HomeTile(
    modifier: Modifier,
    shape: Shape = TvShape.control,
    onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick,
        modifier
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.045f, liftDp = 1.dp)
            .glassSurface(shape, highlighted = focused, base = if (focused) Glass.surfaceFocus else Glass.surfaceCard, elevation = if (focused) 8.dp else 3.dp, backdropBlur = false)
            .tvFocus(focused, shape),
        contentPadding = PaddingValues(0.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Paper,
        ),
        content = { content(focused) },
    )
}

@Composable internal fun HomeGlyph(kind: String, modifier: Modifier, color: Color) {
    if (kind == "brand") {
        Image(painterResource(R.drawable.singverse_logo_mark), contentDescription = null, modifier = modifier.alpha(color.alpha))
        return
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height; val s = 1.75.dp.toPx()
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x, h * y), Offset(w * x2, h * y2), s, StrokeCap.Round)
        when (kind) {
            "search" -> { drawCircle(color, w * .29f, Offset(w * .42f, h * .42f), style = Stroke(s)); line(.64f, .65f, .9f, .91f) }
            "mic" -> { drawRoundRect(color, Offset(w * .34f, 0f), Size(w * .32f, h * .6f), androidx.compose.ui.geometry.CornerRadius(w * .18f), style = Stroke(s)); drawArc(color, 0f, 180f, false, Offset(w * .19f, h * .25f), Size(w * .62f, h * .52f), style = Stroke(s)); line(.5f, .78f, .5f, 1f); line(.32f, 1f, .68f, 1f) }
            "phone" -> drawRoundRect(color, Offset(w * .23f, h * .04f), Size(w * .54f, h * .92f), androidx.compose.ui.geometry.CornerRadius(w * .1f), style = Stroke(s))
            "grid" -> for (x in 0..1) for (y in 0..1) drawRoundRect(color, Offset(w * (.08f + x * .51f), h * (.08f + y * .51f)), Size(w * .32f, h * .32f), androidx.compose.ui.geometry.CornerRadius(w * .07f), style = Stroke(s))
            "playlist" -> { for (i in 0..2) { drawCircle(color, s * .45f, Offset(w * .1f, h * (.2f + i * .28f))); line(.28f, .2f + i * .28f, .8f, .2f + i * .28f) }; line(.8f, .55f, .8f, .9f); drawOval(color, Offset(w * .56f, h * .78f), Size(w * .25f, h * .2f)) }
            "disc" -> { drawCircle(color, w * .43f, center, style = Stroke(s)); drawCircle(color, w * .12f, center, style = Stroke(s)); drawArc(color.copy(alpha = .5f), 210f, 90f, false, Offset(w * .2f, h * .2f), Size(w * .6f, h * .6f), style = Stroke(s)) }
            "chevron" -> { line(.38f, .22f, .64f, .5f); line(.64f, .5f, .38f, .78f) }
            "check" -> { line(.2f, .52f, .41f, .73f); line(.41f, .73f, .81f, .29f) }
            "history" -> { drawCircle(color, w * .42f, center, style = Stroke(s)); line(.5f, .25f, .5f, .51f); line(.5f, .51f, .68f, .62f) }
            "trending" -> { line(.08f, .77f, .38f, .43f); line(.38f, .43f, .58f, .6f); line(.58f, .6f, .91f, .19f); line(.64f, .19f, .91f, .19f); line(.91f, .19f, .91f, .47f) }
            else -> { line(.5f, .2f, .5f, .8f); line(.2f, .5f, .8f, .5f) }
        }
    }
}
