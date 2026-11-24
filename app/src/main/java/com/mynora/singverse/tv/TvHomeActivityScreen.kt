package com.mynora.singverse.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import org.json.JSONObject

/** Dedicated lists keep playback activity separate from the editable song search. */
@Composable internal fun TvHomeActivityScreen(
    app: TvApp,
    popular: Boolean,
    historyState: LazyListState,
    popularState: LazyListState,
) {
    val loading = if (popular) app.homePopularLoading else app.homeHistoryLoading
    val ready = if (popular) app.homePopularReady else app.homeHistoryReady
    val total = if (popular) app.homePopularTotal else app.homeHistoryTotal
    val prefix = if (popular) "popular" else "history"
    val refresh = if (popular) app::refreshHomePopular else app::refreshHomeHistory
    val memory = LocalTvFocusMemory.current
    fun switchActivity(targetPopular: Boolean) {
        if (popular == targetPopular) return
        val dest = if (targetPopular) "热门歌曲" else "历史记录"
        val key = if (targetPopular) "action:activity:tab:popular" else "action:activity:tab:history"
        memory.last[dest] = key
        app.navigate(dest)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Single-row clean header: segmented switcher + count pill + subtle actions
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier
                    .clip(GlassShape.pill)
                    .background(Color(0x0FFFFFFF))
                    .border(1.dp, Color(0x14FFFFFF), GlassShape.pill)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActivityHeaderTab(
                    "历史记录",
                    selected = !popular,
                    focusKey = "activity:tab:history",
                    onFocusSelect = {
                        if (memory.directionalInput && !memory.restoring && memory.activeDialogs == 0) switchActivity(false)
                    },
                ) { switchActivity(false) }
                ActivityHeaderTab(
                    "热门歌曲",
                    selected = popular,
                    focusKey = "activity:tab:popular",
                    onFocusSelect = {
                        if (memory.directionalInput && !memory.restoring && memory.activeDialogs == 0) switchActivity(true)
                    },
                ) { switchActivity(true) }
            }
            if (ready && total > 0) {
                Text(
                    "$total 首",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .clip(GlassShape.pill)
                        .background(Color(0x12FFFFFF))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Action(if (loading) "读取中…" else "刷新", enabled = !loading, focusKey = "$prefix:refresh", shape = GlassShape.pill, textSize = 13.sp, onClick = refresh)
            Action("返回首页", shape = GlassShape.pill, textSize = 13.sp) { app.navigate("首页") }
        }
        AnimatedContent(
            targetState = popular,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                fadeIn(tween(160, delayMillis = 30, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(90, easing = FastOutSlowInEasing))
            },
            label = "activity-content",
        ) { targetPopular ->
            val isCurrent = targetPopular == popular
            val targetSongs = if (targetPopular) app.homePopularSongs else app.homeHistorySongs
            val targetLoading = if (targetPopular) app.homePopularLoading else app.homeHistoryLoading
            val targetReady = if (targetPopular) app.homePopularReady else app.homeHistoryReady
            val targetError = if (targetPopular) app.homePopularError else app.homeHistoryError
            val targetHasMore = if (targetPopular) app.homePopularHasMore else app.homeHistoryHasMore
            val targetPrefix = if (targetPopular) "popular" else "history"
            val targetRefresh = if (targetPopular) app::refreshHomePopular else app::refreshHomeHistory
            val targetLoadMore = if (targetPopular) app::loadMoreHomePopular else app::loadMoreHomeHistory
            val targetRows = remember(targetSongs) { targetSongs.chunked(2) }
            val focusPage = LocalTvFocusPage.current
            val activeTabKey = "$focusPage:action:activity:tab:$targetPrefix"
            Column(
                if (isCurrent) Modifier.fillMaxSize() else Modifier.fillMaxSize().focusProperties { canFocus = false },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (targetError.isNotBlank() && targetSongs.isNotEmpty()) {
                    Text("更新失败，当前显示上次读取的内容，请刷新重试。", color = Muted, fontSize = 12.sp)
                }
                if (targetSongs.isEmpty()) {
                    HomeSectionState(
                        Modifier.weight(1f), targetLoading, targetReady, targetError,
                        if (targetPopular) "近 30 天暂无热门歌曲" else "还没有播放记录",
                        if (targetPopular) "有播放记录后，按曲库热度排序。" else "唱过的歌曲会出现在这里。",
                        "$targetPrefix:retry",
                        targetRefresh,
                    )
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = if (targetPopular) popularState else historyState,
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(targetRows, key = { idx, pair -> "${targetPrefix}_row_${idx}_${pair.first().optString("id")}" }) { rowIndex, pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEachIndexed { colIndex, song ->
                                    val songIndex = rowIndex * 2 + colIndex
                                    ActivitySongTile(
                                        app = app,
                                        song = song,
                                        focusKey = "$targetPrefix:${song.optString("id")}",
                                        rank = songIndex + 1,
                                        trailing = if (targetPopular) "" else homeHistoryDate(song.optString("lastPlayedAt")),
                                        modifier = Modifier.weight(1f).then(
                                            if (rowIndex == 0) Modifier.focusProperties {
                                                memory.targets[activeTabKey]?.let { up = it }
                                            } else Modifier
                                        ),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (targetHasMore) {
                            item(key = "load-more") {
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
                                    Action(if (targetLoading) "正在读取…" else "加载更多", enabled = !targetLoading, focusKey = "$targetPrefix:more", shape = GlassShape.pill, onClick = targetLoadMore)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ActivityHeaderTab(
    label: String,
    selected: Boolean,
    focusKey: String,
    onFocusSelect: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = GlassShape.pill
    Button(
        onClick = onClick,
        modifier = Modifier
            .height(32.dp)
            .tvFocusTarget("action:$focusKey")
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocusSelect()
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Paper else Glass.inkSecondary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
    ) {
        Text(
            label,
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable private fun ActivitySongTile(
    app: TvApp,
    song: JSONObject,
    focusKey: String,
    rank: Int?,
    trailing: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val available = song.optBoolean("available", true)
    val shape = TvShape.control
    Button(
        onClick = { app.openSong(song) },
        enabled = available,
        modifier = modifier
            .height(58.dp)
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused && available, scaleTarget = 1.02f, liftDp = 0.8.dp)
            .then(
                if (focused) Modifier.tvPlatinumFocus(true, shape)
                else Modifier.clip(shape).background(Color(0x0DFFFFFF))
            ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
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
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (rank != null) {
                Text(
                    rank.toString().padStart(2, '0'),
                    color = when {
                        focused -> Glass.focusInk
                        rank == 1 -> Brand
                        else -> Muted
                    },
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(22.dp),
                )
            }
            SongArtwork(app, song, Modifier.size(42.dp).alpha(if (available) 1f else 0.45f), shape = RoundedCornerShape(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    song.optString("title"),
                    color = if (focused) Glass.focusInk else if (available) Paper else Muted,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artists(),
                    color = if (focused) Glass.focusInkMuted else Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing.isNotBlank()) {
                Text(
                    trailing,
                    color = if (focused) Glass.focusInkMuted else Muted.copy(alpha = 0.80f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(GlassShape.pill)
                        .background(if (focused) Glass.focusInk.copy(alpha = 0.08f) else Color(0x0EFFFFFF))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            HomeGlyph("chevron", Modifier.size(11.dp), if (focused) Glass.focusInkMuted else Muted.copy(alpha = 0.55f))
        }
    }
}
