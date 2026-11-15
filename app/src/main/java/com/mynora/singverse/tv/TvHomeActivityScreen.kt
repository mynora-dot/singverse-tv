package com.mynora.singverse.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Dedicated lists keep playback activity separate from the editable song search. */
@Composable internal fun TvHomeActivityScreen(app: TvApp, popular: Boolean, state: LazyListState) {
    val songs = if (popular) app.homePopularSongs else app.homeHistorySongs
    val loading = if (popular) app.homePopularLoading else app.homeHistoryLoading
    val ready = if (popular) app.homePopularReady else app.homeHistoryReady
    val error = if (popular) app.homePopularError else app.homeHistoryError
    val hasMore = if (popular) app.homePopularHasMore else app.homeHistoryHasMore
    val total = if (popular) app.homePopularTotal else app.homeHistoryTotal
    val prefix = if (popular) "popular" else "history"
    val refresh = if (popular) app::refreshHomePopular else app::refreshHomeHistory
    val loadMore = if (popular) app::loadMoreHomePopular else app::loadMoreHomeHistory
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (popular) "热门歌曲" else "历史记录", color = Paper, fontSize = 24.sp)
                Text((if (popular) "此 NAS 近 30 天的播放热度" else "此 NAS 的播放记录，最近播放优先") + if (ready) " · $total 首" else "",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Action("返回首页") { app.navigate("首页") }
            Action(if (loading) "正在读取…" else "刷新", enabled = !loading, focusKey = "$prefix:refresh", onClick = refresh)
        }
        if (error.isNotBlank() && songs.isNotEmpty()) Text("更新失败，当前显示上次读取的内容。请刷新重试。", color = Muted, fontSize = 13.sp)
        if (songs.isEmpty()) HomeSectionState(
            Modifier.weight(1f), loading, ready, error,
            if (popular) "近 30 天暂无热门歌曲" else "还没有播放记录",
            if (popular) "有播放记录后，按曲库热度排序。" else "唱过的歌曲会出现在这里。", "$prefix:retry", refresh,
        ) else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state,
            contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(songs, key = { _, song -> song.optString("id") }) { index, song ->
                HomeCompactSong(app, song, "$prefix:${song.optString("id")}", Modifier.height(62.dp),
                    rank = if (popular) index + 1 else null,
                    trailing = if (popular) "" else homeHistoryDate(song.optString("lastPlayedAt")))
            }
            if (hasMore) item(key = "load-more") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Action(if (loading) "正在读取…" else "加载更多", enabled = !loading, focusKey = "$prefix:more", onClick = loadMore)
                }
            }
        }
    }
}
