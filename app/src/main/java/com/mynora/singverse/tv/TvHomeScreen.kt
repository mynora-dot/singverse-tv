package com.mynora.singverse.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
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
@Composable internal fun TvHomeScreen(app: TvApp, onSearch: () -> Unit, onPhone: () -> Unit) {
    val favorites = app.favoriteSongs().take(4)
    val memory = LocalTvFocusMemory.current
    val page = LocalTvFocusPage.current
    val lastFavorite = favorites.lastOrNull { it.optBoolean("available", true) }?.optString("id")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val historyTextHeight = with(LocalDensity.current) { 19.sp.toDp() + 16.sp.toDp() }
        val historyNoticeHeight = if (app.homeHistoryError.isNotBlank() && app.homeHistorySongs.isNotEmpty()) {
            with(LocalDensity.current) { 14.sp.toDp() } + 2.dp
        } else 0.dp
        val lowerHeight = maxOf(
            (maxHeight - 20.dp) * .40f,
            34.dp + (historyTextHeight + 6.dp) * 3 + historyNoticeHeight,
        )
        val upperHeight = (maxHeight - lowerHeight - 20.dp).coerceAtLeast(180.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.height(upperHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeSectionHeading("我的收藏", "全部收藏", "favorites:all", titleSize = 18.sp) { app.navigate("收藏") }
                    if (favorites.isEmpty()) HomeSectionState(
                        Modifier.weight(1f), app.favoritesLoading, app.favoritesReady, app.favoritesError,
                        "还没有收藏歌曲", "在手机、后台添加，或播放时按「收藏」。", "favorites:retry", app::refreshFavorites,
                    ) else {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val landscape = favorites.size <= 2
                            val columns = if (landscape) favorites.size else 4
                            val width = (maxWidth - 14.dp * (columns - 1)) / columns
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                favorites.forEach { song ->
                                    val id = song.getString("id")
                                    key(id) {
                                        HomeFavoriteCard(
                                            app,
                                            song,
                                            landscape,
                                            Modifier
                                                .width(width)
                                                .fillMaxHeight()
                                                .focusProperties {
                                                    if (id == lastFavorite) right = memory.targets["$page:phone"] ?: FocusRequester.Default
                                                },
                                        )
                                    }
                                }
                            }
                        }
                        if (app.favoritesError.isNotBlank()) HomeStaleNotice("收藏更新失败，可在全部收藏中重试")
                    }
                }
                Column(Modifier.width(214.dp).fillMaxHeight().padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhoneConnectionCard(
                        app,
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusProperties {
                                left = memory.targets["$page:favorite:$lastFavorite"] ?: FocusRequester.Default
                                down = memory.targets["$page:search"] ?: FocusRequester.Default
                            },
                        onPhone,
                    )
                    HomeTile(
                        Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .tvFocusTarget("search")
                            .focusProperties {
                                up = memory.targets["$page:phone"] ?: FocusRequester.Default
                                left = memory.targets["$page:favorite:$lastFavorite"] ?: FocusRequester.Default
                            },
                        shape = GlassShape.pill,
                        onClick = onSearch,
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            HomeGlyph("search", Modifier.size(15.dp), Brand)
                            Text("搜索歌曲", Modifier.weight(1f), color = Paper, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            HomeGlyph("chevron", Modifier.size(11.dp), Muted)
                        }
                    }
                }
            }
            Row(Modifier.height(lowerHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(.44f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HomeSectionHeading("历史记录", "查看全部", "history:all", onClick = { app.navigate("历史记录") })
                    if (app.homeHistorySongs.isEmpty()) HomeSectionState(
                        Modifier.weight(1f), app.homeHistoryLoading, app.homeHistoryReady, app.homeHistoryError,
                        "还没有播放记录", "唱过的歌曲会出现在这里。", "history:retry", app::refreshHomeHistory,
                    ) else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        app.homeHistorySongs.take(3).forEach { song ->
                            HomeCompactSong(
                                app, song, "history:${song.optString("id")}", Modifier.weight(1f),
                                trailing = homeHistoryDate(song.optString("lastPlayedAt")),
                            )
                        }
                        if (app.homeHistoryError.isNotBlank()) HomeStaleNotice("记录更新失败，可查看全部后重试")
                    }
                }
                Column(Modifier.weight(.56f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HomeSectionHeading(
                        "热门歌曲", "查看全部", "popular:all",
                        onClick = { app.navigate("热门歌曲") },
                    )
                    if (app.homePopularSongs.isEmpty()) HomeSectionState(
                        Modifier.weight(1f), app.homePopularLoading, app.homePopularReady, app.homePopularError,
                        "近 30 天暂无热门歌曲", "有播放记录后，按曲库热度排序。", "popular:retry", app::refreshHomePopular,
                    ) else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        app.homePopularSongs.take(4).chunked(2).forEachIndexed { rowIndex, songs ->
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                songs.forEachIndexed { index, song ->
                                    HomeCompactSong(
                                        app, song, "popular:${song.optString("id")}", Modifier.weight(1f).fillMaxHeight(),
                                        rank = rowIndex * 2 + index + 1,
                                    )
                                }
                                if (songs.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (app.homePopularError.isNotBlank()) HomeStaleNotice("热门更新失败，可查看全部后重试")
                    }
                }
            }
        }
    }
}

@Composable private fun HomeSectionHeading(
    title: String,
    more: String,
    focusKey: String,
    icon: String? = null,
    subtitle: String = "",
    titleSize: TextUnit = 16.5.sp,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) HomeGlyph(icon, Modifier.size(14.dp), Brand)
        Text(title, color = Paper, fontSize = titleSize, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(Modifier.weight(1f))
        var focused by remember { mutableStateOf(false) }
        Button(
            onClick,
            Modifier
                .height(24.dp)
                .tvFocusTarget(focusKey)
                .onFocusChanged { focused = it.isFocused }
                .tvSpringFocus(focused, scaleTarget = 1.04f, liftDp = 0.5.dp)
                .then(
                    if (focused) Modifier.glassSurface(GlassShape.pill, highlighted = true, base = Glass.surfaceFocus)
                    else Modifier
                )
                .tvFocus(focused, GlassShape.pill),
            scale = ButtonDefaults.scale(focusedScale = 1f),
            shape = ButtonDefaults.shape(GlassShape.pill),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Muted.copy(alpha = 0.72f),
                focusedContainerColor = Color.Transparent,
                focusedContentColor = Paper,
            ),
        ) {
            Text(more, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(3.dp))
            HomeGlyph("chevron", Modifier.size(10.dp), if (focused) Paper else Muted.copy(alpha = 0.72f))
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
            .tvFocusTarget("favorite:${song.getString("id")}")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused && available, scaleTarget = 1.04f)
            .then(
                when {
                    focused -> Modifier.glassSurface(shape, highlighted = true, base = Glass.surfaceFocus, elevation = 14.dp)
                    landscape -> Modifier.glassSurface(shape, highlighted = false, base = Glass.surfaceFloat, elevation = 8.dp)
                    else -> Modifier
                }
            )
            .tvFocus(focused, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(if (landscape) 12.dp else 7.dp),
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
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val coverSize = minOf(maxWidth, maxHeight - 40.dp).coerceAtLeast(56.dp)
                Column(Modifier.width(coverSize).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    Box(Modifier.size(coverSize).alpha(if (available) 1f else .45f)) {
                        SongArtwork(app, song, Modifier.fillMaxSize(), shape = TvShape.card)
                        if (isPlaying) HomePlayingBadge(app.playing, Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                    HomeFavoriteText(song, available, landscape = false, modifier = Modifier.fillMaxWidth())
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
        if (!loading && (error.isNotBlank() || !ready)) HomeButton("重新读取", focusKey = retryKey, onClick = retry)
    }
}

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
    val statusTone = if (!app.online || app.invitationError.isNotEmpty()) Danger else Success
    val status = when {
        app.snapshot.optString("status") == "ENDED" -> "聚会已结束"
        !app.online -> "网络异常"
        app.invitationError.isNotEmpty() -> "邀请异常"
        app.invitationLoading -> "正在生成"
        !app.participantsKnown -> "正在同步"
        app.participantCount > 0 -> "${app.participantCount} 台已加入"
        else -> "等待加入"
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .tvFocusTarget("phone")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.032f)
            .glassSurface(
                shape,
                highlighted = focused,
                base = if (focused) Glass.surfaceFocus else Glass.surfaceFloat,
                elevation = if (focused) 6.dp else 3.dp,
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
        Row(
            Modifier.fillMaxSize().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (app.invitation.isNotEmpty() && app.online) Qr(app.invitation, Modifier.fillMaxSize())
                else Text(
                    if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成中…" else "确认以重试",
                    color = Paper,
                    fontSize = 10.sp,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("手机点歌", color = Paper, fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(5.5.dp).background(statusTone, CircleShape))
                    Text(status, color = statusTone, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable internal fun TvNavigation(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = GlassShape.pill
    val container by animateColorAsState(
        targetValue = when {
            focused -> Glass.surfaceFocus
            selected -> Color(0xEAFFFFFF)
            else -> Color.Transparent
        },
        animationSpec = tween(180),
        label = "navPillBg",
    )
    Button(
        onClick,
        modifier
            .height(32.dp)
            .semantics { this.selected = selected; stateDescription = if (selected) "当前页面" else "" }
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.035f, liftDp = 0.5.dp)
            .then(
                if (selected && !focused) Modifier
                    .glassSurface(shape, highlighted = false, intensity = 0.75f, base = Color(0xF0FFFFFF), elevation = 2.dp)
                else Modifier
            )
            .tvFocus(focused, shape),
        contentPadding = PaddingValues(horizontal = 17.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = if (selected && !focused) Color.Transparent else container,
            contentColor = if (selected) Paper else Muted,
            focusedContainerColor = Glass.surfaceFocus,
            focusedContentColor = Paper,
        ),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
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
    val textColor = if (primary) OnBrand else Paper
    Button(
        onClick,
        modifier
            .height(32.dp)
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.035f, liftDp = 0.8.dp)
            .then(
                if (primary) Modifier.brandGlassSurface(shape, focused = focused)
                else Modifier.glassSurface(shape, highlighted = focused, base = if (focused) Glass.surfaceFocus else Glass.surfaceFloat, elevation = if (focused) 5.dp else 2.dp)
            )
            .tvFocus(focused, shape),
        contentPadding = PaddingValues(horizontal = if (label in listOf("‹", "›")) 0.dp else 14.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = textColor,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = textColor,
        ),
    ) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun HomeTile(
    modifier: Modifier,
    shape: Shape = TvShape.control,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick,
        modifier
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.032f)
            .glassSurface(shape, highlighted = focused, base = if (focused) Glass.surfaceFocus else Glass.surfaceFloat, elevation = if (focused) 6.dp else 3.dp)
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
        content = content,
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
