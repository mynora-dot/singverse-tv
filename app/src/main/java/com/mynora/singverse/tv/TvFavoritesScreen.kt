package com.mynora.singverse.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.json.JSONObject

@Composable internal fun FavoritesContent(app: TvApp, modifier: Modifier = Modifier) {
    var removing by remember { mutableStateOf<JSONObject?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (app.favoritesReady) "NAS 共享收藏 · ${app.favoriteSongs().size} 首" else "NAS 共享收藏",
                    color = Paper,
                    fontSize = 21.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("与此 NAS 的后台、手机共用", color = Muted, fontSize = 12.5.sp, lineHeight = 18.sp)
            }
            Action(if (app.favoritesLoading) "正在读取…" else "刷新收藏", enabled = !app.favoritesLoading && !app.favoriteSaving) { app.refreshFavorites() }
        }
        if (app.favoritesError.isNotBlank()) Text("收藏读取或保存失败：${app.favoritesError}。当前内容可能尚未更新。", color = Muted, fontSize = 13.sp)
        val favorites = app.favoriteSongs()
        if (favorites.isEmpty()) EmptyState(
            when { app.favoritesLoading -> "正在读取收藏"; !app.favoritesReady -> "暂时无法读取收藏"; else -> "还没有收藏歌曲" },
            if (app.favoritesReady) "在手机、后台添加，或播放时按「收藏」。" else "请检查 NAS 连接后刷新。",
        ) else LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(6.dp),
        ) {
            items(favorites, key = { it.optString("id") }) { favorite ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(TvShape.row, intensity = 0.85f, base = Glass.surfaceCard, elevation = 0.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SongArtwork(app, favorite, Modifier.size(42.dp), shape = RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(favorite.optString("title"), color = Paper, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(favorite.artists() + if (!favorite.optBoolean("available", true)) " · 暂不可用" else "", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Action(
                        "加入已点",
                        modifier = Modifier.height(32.dp),
                        selected = true,
                        enabled = favorite.optBoolean("available", true),
                        focusKey = "favorite:${favorite.optString("id")}",
                        textSize = 12.5.sp,
                        shape = GlassShape.pill,
                    ) { app.command("ADD", songId = favorite.optString("id")) }
                    Action(
                        "取消收藏",
                        modifier = Modifier.height(32.dp),
                        enabled = !app.favoriteSaving,
                        quiet = true,
                        focusKey = "favorite-remove:${favorite.optString("id")}",
                        textSize = 12.5.sp,
                    ) { removing = favorite }
                }
            }
        }
    }
    removing?.let { favorite ->
        Modal("取消收藏？", "手机和后台也会取消「${favorite.optString("title")}」的收藏，歌曲文件和练唱记录会保留。", dismiss = { if (!app.favoriteSaving) removing = null }) {
            Action("保留收藏", enabled = !app.favoriteSaving) { removing = null }
            Action(if (app.favoriteSaving) "正在保存…" else "确认取消", enabled = !app.favoriteSaving, danger = true) { app.setFavorite(favorite.optString("id"), false) }
            if (app.favoritesError.isNotBlank()) Text(app.favoritesError, color = Muted, fontSize = 14.sp)
            LaunchedEffect(app.isFavorite(favorite.optString("id")), app.favoriteSaving) {
                if (!app.favoriteSaving && !app.isFavorite(favorite.optString("id"))) removing = null
            }
        }
    }
}
