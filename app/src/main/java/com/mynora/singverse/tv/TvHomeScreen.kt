package com.mynora.singverse.tv

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
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
        val historyTextHeight = with(LocalDensity.current) { 20.sp.toDp() + 16.sp.toDp() }
        val historyNoticeHeight = if (app.homeHistoryError.isNotBlank() && app.homeHistorySongs.isNotEmpty()) {
            with(LocalDensity.current) { 14.sp.toDp() } + 2.dp
        } else 0.dp
        val lowerHeight = maxOf((maxHeight - 12.dp) * .393f,
            41.dp + (historyTextHeight + 2.dp) * 3 + historyNoticeHeight)
        val upperHeight = maxHeight - lowerHeight - 12.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.height(upperHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeSectionHeading("我的收藏", "全部收藏", "favorites:all", titleSize = 22.sp) { app.navigate("收藏") }
                    if (favorites.isEmpty()) HomeSectionState(
                        Modifier.weight(1f), app.favoritesLoading, app.favoritesReady, app.favoritesError,
                        "还没有收藏歌曲", "在手机、后台添加，或播放时按「收藏」。", "favorites:retry", app::refreshFavorites,
                    ) else {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val landscape = favorites.size <= 2
                            val columns = if (landscape) favorites.size else 4
                            val width = (maxWidth - 12.dp * (columns - 1)) / columns
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                favorites.forEach { song ->
                                    val id = song.getString("id")
                                    key(id) {
                                        HomeFavoriteCard(app, song, landscape, Modifier.width(width).fillMaxHeight().focusProperties {
                                            if (id == lastFavorite) right = memory.targets["$page:phone"] ?: FocusRequester.Default
                                        })
                                    }
                                }
                            }
                        }
                        if (app.favoritesError.isNotBlank()) HomeStaleNotice("收藏更新失败，可在全部收藏中重试")
                    }
                }
                Column(Modifier.width(224.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PhoneConnectionCard(app, Modifier.fillMaxWidth().height(minOf(137.dp, upperHeight - 82.dp)).focusProperties {
                        left = memory.targets["$page:favorite:$lastFavorite"] ?: FocusRequester.Default
                        down = memory.targets["$page:search"] ?: FocusRequester.Default
                    }, onPhone)
                    HomeTile(Modifier.fillMaxWidth().height(56.dp).tvFocusTarget("search").focusProperties {
                        up = memory.targets["$page:phone"] ?: FocusRequester.Default
                        left = memory.targets["$page:favorite:$lastFavorite"] ?: FocusRequester.Default
                    }, onClick = onSearch) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeGlyph("search", Modifier.size(20.dp), Accent)
                            Text("搜索歌曲", Modifier.weight(1f), color = Paper, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                            HomeGlyph("chevron", Modifier.size(14.dp), Muted)
                        }
                    }
                }
            }
            Row(Modifier.height(lowerHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(.42f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    HomeSectionHeading("历史记录", "查看全部", "history:all", icon = "history", onClick = { app.navigate("历史记录") })
                    if (app.homeHistorySongs.isEmpty()) HomeSectionState(
                        Modifier.weight(1f), app.homeHistoryLoading, app.homeHistoryReady, app.homeHistoryError,
                        "还没有播放记录", "唱过的歌曲会出现在这里。", "history:retry", app::refreshHomeHistory,
                    ) else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        app.homeHistorySongs.take(3).forEach { song ->
                            HomeCompactSong(app, song, "history:${song.optString("id")}", Modifier.weight(1f),
                                trailing = homeHistoryDate(song.optString("lastPlayedAt")))
                        }
                        if (app.homeHistoryError.isNotBlank()) HomeStaleNotice("记录更新失败，可查看全部后重试")
                    }
                }
                Column(Modifier.weight(.58f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    HomeSectionHeading("热门歌曲", "查看全部", "popular:all", icon = "trending", subtitle = "近 30 天",
                        onClick = { app.navigate("热门歌曲") })
                    if (app.homePopularSongs.isEmpty()) HomeSectionState(
                        Modifier.weight(1f), app.homePopularLoading, app.homePopularReady, app.homePopularError,
                        "近 30 天暂无热门歌曲", "有播放记录后，按曲库热度排序。", "popular:retry", app::refreshHomePopular,
                    ) else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        app.homePopularSongs.take(4).chunked(2).forEachIndexed { rowIndex, songs ->
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                songs.forEachIndexed { index, song ->
                                    HomeCompactSong(app, song, "popular:${song.optString("id")}", Modifier.weight(1f).fillMaxHeight(),
                                        rank = rowIndex * 2 + index + 1)
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
    title: String, more: String, focusKey: String, icon: String? = null, subtitle: String = "",
    titleSize: TextUnit = 19.sp, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) HomeGlyph(icon, Modifier.size(16.dp), Muted)
        Text(title, color = Paper, fontSize = titleSize, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.weight(1f))
        var focused by remember { mutableStateOf(false) }
        Button(onClick, Modifier.height(28.dp).tvFocusTarget(focusKey).onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, GlassShape.pill), scale = ButtonDefaults.scale(focusedScale = 1f),
            shape = ButtonDefaults.shape(GlassShape.pill), contentPadding = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Muted,
                focusedContainerColor = Glass.surfaceHover, focusedContentColor = Paper)) {
            Text(more, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.width(4.dp))
            HomeGlyph("chevron", Modifier.size(11.dp), if (focused) Paper else Muted)
        }
    }
}

@Composable private fun HomeFavoriteCard(app: TvApp, song: JSONObject, landscape: Boolean, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val available = song.optBoolean("available", true)
    val shape = TvShape.control
    val surface = if (landscape) Glass.cardSurface else Color.Transparent
    val artworkModifier = Modifier.alpha(if (available) 1f else .45f).tvFocus(focused, shape)
    Button(
        onClick = { app.openSong(song) },
        enabled = available,
        modifier = modifier.tvFocusTarget("favorite:${song.getString("id")}").onFocusChanged { focused = it.isFocused },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(if (landscape) 12.dp else 6.dp),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = surface, focusedContainerColor = surface, disabledContainerColor = surface),
    ) {
        if (landscape) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cover = minOf(124.dp, maxWidth * .43f, maxHeight)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SongArtwork(app, song, Modifier.size(cover).then(artworkModifier), shape = shape)
                    HomeFavoriteText(song, available, landscape = true, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SongArtwork(app, song, Modifier.weight(1f, fill = false).aspectRatio(1f)
                    .align(Alignment.CenterHorizontally).then(artworkModifier), shape = shape)
                Spacer(Modifier.height(7.dp))
                HomeFavoriteText(song, available, landscape = false)
            }
        }
    }
}

@Composable private fun HomeFavoriteText(song: JSONObject, available: Boolean, landscape: Boolean, modifier: Modifier = Modifier) {
    val lines = if (landscape) 2 else 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 0.dp)) {
        Text(song.optString("title"), color = Paper, fontSize = if (landscape) 20.sp else 15.5.sp,
            lineHeight = if (landscape) 26.sp else 21.sp, fontWeight = FontWeight.Medium, maxLines = lines, overflow = TextOverflow.Ellipsis)
        Text(if (available) song.artists() else "${song.artists()} · 暂不可用",
            color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun HomeCompactSong(
    app: TvApp, song: JSONObject, focusKey: String, modifier: Modifier = Modifier, rank: Int? = null, trailing: String = "",
) {
    var focused by remember { mutableStateOf(false) }
    val available = song.optBoolean("available", true)
    val shape = TvShape.control
    Button(onClick = { app.openSong(song) }, modifier.fillMaxWidth().tvFocusTarget(focusKey)
        .onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape),
        enabled = available,
        contentPadding = PaddingValues(horizontal = if (rank == null) 6.dp else 10.dp, vertical = if (rank == null) 2.dp else 5.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = if (rank == null) Color.Transparent else Glass.surface,
            contentColor = Paper, focusedContainerColor = Glass.surfaceHover, focusedContentColor = Paper)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (rank != null) Text(rank.toString().padStart(2, '0'), color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.width(18.dp))
            BoxWithConstraints {
                SongArtwork(app, song, Modifier.size(minOf(if (rank == null) 32.dp else 40.dp, maxHeight)), shape = RoundedCornerShape(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(song.optString("title"), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists() + if (available) "" else " · 暂不可用", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing.isNotEmpty()) Text(trailing, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            HomeGlyph("chevron", Modifier.size(12.dp), Muted)
        }
    }
}

@Composable internal fun HomeSectionState(
    modifier: Modifier, loading: Boolean, ready: Boolean, error: String, emptyTitle: String, emptyDetail: String,
    retryKey: String, retry: () -> Unit,
) {
    Column(modifier.fillMaxWidth().glassSurface(TvShape.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(when { loading -> "正在读取…"; error.isNotBlank() || !ready -> "暂时无法读取"; else -> emptyTitle },
            color = Paper, fontSize = 13.sp, lineHeight = 19.sp)
        Text(if (error.isNotBlank() || (!ready && !loading)) "检查 NAS 连接后重试" else if (loading) "正在同步 NAS 曲库" else emptyDetail,
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
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
    HomeTile(modifier.tvFocusTarget("phone"), onClick = onClick) {
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (app.invitation.isNotEmpty() && app.online) Qr(app.invitation, Modifier.size(80.dp))
            else Box(Modifier.size(80.dp).background(Paper, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成中…" else "确认以重试", color = Ink, fontSize = 10.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("手机点歌", color = Paper, fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                Text("扫码加入", color = Paper.copy(alpha = .7f), fontSize = 12.sp, lineHeight = 16.sp)
                val status = when {
                    app.snapshot.optString("status") == "ENDED" -> "聚会已结束"
                    !app.online -> "网络异常"
                    app.invitationError.isNotEmpty() -> "邀请异常"
                    app.invitationLoading -> "正在生成"
                    !app.participantsKnown -> "正在同步"
                    app.participantCount > 0 -> "${app.participantCount} 台已加入"
                    else -> "等待加入"
                }
                Text(status, color = if (!app.online || app.invitationError.isNotEmpty()) Danger else Paper.copy(alpha = .66f),
                    fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun TvNavigation(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = GlassShape.pill
    Button(onClick, modifier.height(34.dp).semantics { this.selected = selected; stateDescription = if (selected) "当前页面" else "" }
        .onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape),
        contentPadding = PaddingValues(horizontal = 20.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = if (selected) Glass.surfaceStrong else Color.Transparent,
            contentColor = if (selected) Paper else Muted,
            focusedContainerColor = Glass.surfaceFocus, focusedContentColor = Paper)) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable internal fun HomeButton(label: String, modifier: Modifier = Modifier, primary: Boolean = false, focusKey: String = label, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick, modifier.height(34.dp).tvFocusTarget(focusKey).onFocusChanged { focused = it.isFocused }.tvFocus(focused, GlassShape.pill),
        contentPadding = PaddingValues(horizontal = if (label in listOf("‹", "›")) 0.dp else 16.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(GlassShape.pill),
        colors = ButtonDefaults.colors(containerColor = if (primary) Primary else Glass.surface, contentColor = if (primary) Ink else Paper,
            focusedContainerColor = if (primary) Glass.surfaceFocus else Glass.surfaceHover, focusedContentColor = Paper)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun HomeTile(
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.control
    Button(onClick, modifier.onFocusChanged { focused = it.isFocused }.tvFocus(focused, shape),
        contentPadding = PaddingValues(0.dp), scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Glass.cardSurface,
            contentColor = Paper,
            focusedContainerColor = Glass.surfaceFocus,
            focusedContentColor = Paper,
        ), content = content)
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
