@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns only presentation. Media3, queue commands and original media timelines stay in TvApp. */
@Composable internal fun PlayerScreen(app: TvApp) {
    val audioOnly = app.playerMode == TvPlayerMode.AUDIO
    val memory = LocalTvFocusMemory.current
    val page = LocalTvFocusPage.current
    val primary = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    var queueOpen by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf<String?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var qualityOpen by remember { mutableStateOf(false) }
    var favoritesOpen by remember { mutableStateOf(false) }
    var cleanVideo by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val controlsVisible = audioOnly || app.controlVisible
    val playback = app.snapshot.optJSONObject("playback")
    val currentId = playback?.optString("songId").orEmpty()
    val song = app.currentSong?.takeIf { it.optString("id") == currentId }
        ?: app.snapshot.optJSONArray("queue")?.objects()?.firstOrNull { it.optString("songId") == currentId || it.optJSONObject("song")?.optString("id") == currentId }?.optJSONObject("song")

    LaunchedEffect(audioOnly) { if (audioOnly) app.controlVisible = true }
    LaunchedEffect(controlsVisible, audioOnly, currentId) {
        withFrameNanos { }; withFrameNanos { }
        if (memory.activeDialogs == 0) {
            if (controlsVisible) {
                if (!memory.restore(page) || memory.last[page] == "player") primary.requestFocus()
            } else surfaceFocus.requestFocus()
        }
    }
    LaunchedEffect(controlsVisible, audioOnly, app.playing, app.foreground, lastInteraction, memory.activeDialogs) {
        if (!audioOnly && controlsVisible && app.playing && app.foreground && memory.activeDialogs == 0) {
            delay(8000)
            app.controlVisible = false
        }
    }
    BackHandler(enabled = memory.activeDialogs == 0 && app.pendingConfirm == null && app.error.isBlank()) {
        if (!audioOnly && controlsVisible) app.controlVisible = false else app.navigate("首页")
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(if (audioOnly) Ink else Color.Black)
            .semantics { stateDescription = if (audioOnly) "音频播放器" else "视频播放器" }
            .onPreviewKeyEvent {
                lastInteraction = SystemClock.elapsedRealtime()
                if (!controlsVisible && it.type == KeyEventType.KeyUp && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.DirectionDown, Key.DirectionUp)) {
                    app.controlVisible = true
                    true
                } else false
            },
    ) {
        if (audioOnly) AudioPlayerScene(app, song) else VideoPlayerSurface(app)
        Box(Modifier.fillMaxSize().focusRequester(surfaceFocus).tvFocusTarget("player").focusable(enabled = !controlsVisible))
        PlayerHeader(app, audioOnly, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 15.dp))
        AnimatedVisibility(
            visible = audioOnly || !cleanVideo,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 24.dp).width(196.dp),
            enter = fadeIn(tween(260)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(220)) { it / 3 },
        ) {
            PlayerSideRail(app, Modifier.fillMaxWidth())
        }
        AnimatedVisibility(
            visible = !audioOnly && !controlsVisible && app.playerState != "IDLE",
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(280)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 3 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(200)) { it / 3 },
        ) {
            VideoExternalLyrics(app)
        }
        if (controlsVisible) {
            val dockEnter = remember(audioOnly) { Animatable(if (audioOnly) 1f else 0f) }
            LaunchedEffect(Unit) {
                if (dockEnter.value < 1f) dockEnter.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val p = dockEnter.value
                        alpha = p
                        translationY = (1f - p) * 24.dp.toPx()
                    }
                    .background(
                        if (audioOnly) Brush.verticalGradient(listOf(Color.Transparent, Color(0x66E7ECF6)))
                        else Brush.verticalGradient(listOf(Color.Transparent, Color(0x6606070B)))
                    )
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!audioOnly) CurrentVideoSong(app, song)
                PlayerControls(
                    app, audioOnly,
                    onQueue = { queueOpen = true },
                    onLyrics = { settings = "歌词字幕" },
                    onOutput = { settings = "声音输出" },
                    onMore = { moreOpen = true },
                    primaryFocus = primary,
                )
                if (app.favoritesError.isNotBlank()) Text("收藏：${app.favoritesError}", color = Muted, fontSize = 13.sp)
            }
        }
    }
    if (queueOpen) Modal("已点歌曲", "当前演唱保持独立，这里只管理接下来的歌曲。", dismiss = { queueOpen = false }, wide = true) {
        Action("完成") { queueOpen = false }
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 292.dp)) { Queue(app, compact = true) }
    }
    if (moreOpen) Modal("更多设置", "播放辅助与本机操作。", { moreOpen = false }) {
        Action("完成") { moreOpen = false }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 278.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(6.dp)) {
            item { Action("音轨设置", Modifier.fillMaxWidth()) { settings = "音轨" } }
            item { Action("同步设置", Modifier.fillMaxWidth()) { settings = "同步" } }
            item { Action("音质详情", Modifier.fillMaxWidth()) { qualityOpen = true } }
            item { Action("NAS 收藏 · ${app.favoriteSongs().size}", Modifier.fillMaxWidth()) { favoritesOpen = true; app.refreshFavorites() } }
            if (!audioOnly) item { Action(if (cleanVideo) "显示辅助卡片" else "纯净画面 · 隐藏辅助卡片", Modifier.fillMaxWidth(), selected = cleanVideo) { cleanVideo = !cleanVideo; moreOpen = false } }
            item { Action("刷新手机连接入口", Modifier.fillMaxWidth()) { if (!app.online) app.reconnect(); app.invite(); moreOpen = false } }
            item { Action("重试本机播放", Modifier.fillMaxWidth()) { app.retryPlayback(); moreOpen = false } }
            item { Action("停止本机播放", Modifier.fillMaxWidth(), danger = true) { app.stopLocal(); moreOpen = false } }
        }
    }
    settings?.let { tab ->
        Modal("播放设置", "更改只作用于当前播放链与本机设置。", dismiss = { settings = null }, wide = true) {
            Action("完成") { settings = null }
            PlaybackSettings(app, initialTab = tab, audioOnly = audioOnly, modifier = Modifier.weight(1f, fill = false))
        }
    }
    if (qualityOpen) Modal("音质详情", "依据当前实际解码音轨；未提供的参数不显示。", { qualityOpen = false }) {
        val labels = app.audioQualityLabels
        if (labels.isEmpty()) Text("正在读取音轨信息", color = Muted, fontSize = 16.sp, lineHeight = 22.sp)
        else labels.forEachIndexed { index, label ->
            Row(Modifier.fillMaxWidth().glassSurface(TvShape.control, base = Glass.surfaceFloat).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(label, color = if (index == 0) Brand else Paper, fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Action("完成") { qualityOpen = false }
    }
    if (favoritesOpen) Modal("NAS 共享收藏", "与此 NAS 的后台、手机共用，取消收藏不会删除歌曲文件。", { favoritesOpen = false }, wide = true) {
        Action("完成") { favoritesOpen = false }
        FavoritesContent(app, Modifier.weight(1f, fill = false).heightIn(max = 340.dp))
    }
}

@Composable private fun VideoPlayerSurface(app: TvApp) {
    AndroidView(
        factory = { context -> PlayerView(context).apply { player = app.player; useController = false; isFocusable = false } },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.player = null },
    )
}

@Composable private fun VideoExternalLyrics(app: TvApp) {
    val line = app.playbackOptions.activeLine ?: return
    val next = app.playbackOptions.nextLine
    Box(Modifier.fillMaxWidth().padding(bottom = 34.dp, start = 48.dp, end = 48.dp), contentAlignment = Alignment.BottomCenter) {
        val text = buildAnnotatedString {
            if (!line.hasCompleteWords) append(line.text)
            else line.words.forEach { word ->
                withStyle(SpanStyle(color = if (word.at <= app.playbackOptions.lyricPosition) Color(0xFFFFD8E0) else Color.White.copy(alpha = 0.56f))) {
                    append(word.text)
                }
            }
        }
        Column(
            Modifier
                .glassSurface(TvShape.card, intensity = 0.85f, base = Glass.mediaScrim, elevation = 12.dp)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text, color = Color.White, fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            next?.let {
                Text(it.text, color = Color.White.copy(alpha = 0.68f), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun AudioPlayerScene(app: TvApp, song: JSONObject?) {
    val songId = song?.optString("id").orEmpty()
    val coverPath = song?.optString("coverPath").orEmpty()
    val revision = song?.optString("revision").orEmpty()
    var ambientPair by remember(songId) { mutableStateOf(fallbackAmbientColors(songId)) }
    LaunchedEffect(app.client.base, songId, coverPath, revision) {
        ambientPair = fallbackAmbientColors(songId)
        if (coverPath.isNotBlank()) {
            val extracted = runCatching {
                val bmp = app.client.image(coverPath, revision)
                withContext(Dispatchers.Default) { extractAmbientColors(bmp) }
            }.getOrNull()
            if (extracted != null) ambientPair = extracted
        }
    }
    val coverScale by animateFloatAsState(
        targetValue = if (app.playing) 1.02f else 0.96f,
        animationSpec = TvMotion.focusSpring,
        label = "stageCoverScale",
    )
    Box(Modifier.fillMaxSize()) {
        AmbientStage(
            accent = true,
            primaryGlow = ambientPair.first,
            secondaryGlow = ambientPair.second,
            animateDrift = app.playing && app.foreground,
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 42.dp, end = 232.dp, top = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(34.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
                SongArtwork(
                    app,
                    song,
                    Modifier
                        .size(216.dp)
                        .graphicsLayer { scaleX = coverScale; scaleY = coverScale },
                    contentScale = ContentScale.Fit,
                    shape = TvShape.panel,
                )
                val labels = app.audioQualityLabels.take(3)
                if (labels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.forEach { badge ->
                            Text(
                                badge,
                                color = Paper.copy(alpha = 0.82f),
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .glassSurface(GlassShape.pill, intensity = 0.72f, base = Glass.surfaceFloat)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    song?.optString("title").orEmpty().ifBlank { "等待歌曲" },
                    color = Paper,
                    fontSize = 31.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                song?.let {
                    Text(it.artists(), color = Paper.copy(alpha = .74f), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SongMetadata(app, song)
                if (app.hasConfirmedPlayback) AudioPlayerLyrics(app, Modifier.fillMaxWidth().padding(top = 6.dp))
                else Text(
                    if (app.playerState == "ERROR") "播放失败，可在下方重试或切歌" else "确认开唱后显示歌词",
                    color = if (app.playerState == "ERROR") Danger else Muted,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                )
            }
        }
        PlaybackAmbience(
            app.playing && app.foreground,
            Modifier.align(Alignment.BottomCenter).padding(start = 216.dp, end = 216.dp, bottom = 142.dp).fillMaxWidth().height(18.dp),
        )
    }
}

@Composable private fun SongMetadata(app: TvApp, song: JSONObject?) {
    if (song == null) return
    val ids = song.optJSONObject("classification")?.optJSONObject("dimensions")?.optJSONObject("GENRE")?.optJSONArray("tagIds")
    val genreIds = ids?.let { (0 until it.length()).map { index -> it.optString(index) }.toSet() }.orEmpty()
    val genres = app.tags.filter { it.optString("id") in genreIds }.map { it.optString("name") }
    val metadata = tvSongMetadataLabels(song.optString("album"), song.optString("releaseDate"), genres, song.optLong("durationMs").takeIf { it > 0 } ?: app.duration)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        metadata.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (label, value) ->
                    Text(
                        "$label  $value",
                        color = Muted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable private fun CurrentVideoSong(app: TvApp, song: JSONObject?) {
    Row(
        Modifier
            .fillMaxWidth()
            .glassSurface(GlassShape.pill, intensity = 0.86f, base = Glass.mediaSafe, elevation = 10.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SongArtwork(app, song, Modifier.size(40.dp), shape = RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(song?.optString("title").orEmpty().ifBlank { "等待演唱" }, color = Paper, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            song?.let { Text(it.artists(), color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        StatusPill(stateLabel(app.playerState), if (app.playerState == "ERROR") Danger else Brand, pulse = app.playing)
    }
}

@Composable private fun PlayerHeader(app: TvApp, audioOnly: Boolean, modifier: Modifier) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(30000) } }
    val waiting = app.snapshot.optJSONObject("playback")?.optBoolean("confirmed") != true &&
        app.snapshot.optJSONArray("queue")?.objects()?.any { it.optString("status") == "HANDOFF" } == true
    val status = if (waiting) "等待确认开唱" else "${stateLabel(app.playerState)}${if (audioOnly) " · 音频" else ""}"
    val pillBase = if (audioOnly) Glass.surfaceFloat else Glass.mediaSafe
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            if (audioOnly) Modifier else Modifier.glassSurface(GlassShape.pill, base = Glass.mediaSafe, elevation = 6.dp).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeGlyph("brand", Modifier.size(24.dp), Paper)
            Text("SingVerse", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.glassSurface(GlassShape.pill, base = pillBase, elevation = 6.dp).padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            LiveEqualizer(app.playing, color = if (app.playerState == "ERROR" || !app.online) Danger else Brand)
            Text(status, color = if (app.playerState == "ERROR" || !app.online) Danger else Paper, fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        Row(
            if (audioOnly) Modifier else Modifier.glassSurface(GlassShape.pill, base = Glass.mediaSafe, elevation = 6.dp).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(clock, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            StatusPill(if (app.online) "NAS 已连接" else "网络异常", if (app.online) Success else Danger, pulse = app.online)
        }
    }
}

@Composable private fun PlayerSideRail(app: TvApp, modifier: Modifier) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val playback = app.snapshot.optJSONObject("playback")
    val current = if (playback?.optBoolean("confirmed") == true) playback.optString("itemId")
    else queue.firstOrNull { it.optString("status") == "PLAYING" }?.optString("id").orEmpty()
    val next = tvNextQueuedItem(queue, { it.optString("status") }, { it.optString("id") }, current)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.End) {
        if (next != null) NextSongCard(app, next, Modifier.fillMaxWidth()) else Spacer(Modifier.height(64.dp))
        PlayerPhoneCard(app, Modifier.width(136.dp))
    }
}

@Composable private fun NextSongCard(app: TvApp, item: JSONObject, modifier: Modifier = Modifier) {
    val song = item.optJSONObject("song")
    val ending = app.duration > 0 && app.duration - app.position in 0..30000
    Row(
        modifier
            .glassSurface(
                TvShape.card,
                highlighted = ending,
                base = Glass.mediaSafe,
                tintAccent = if (ending) Brand.copy(alpha = 0.14f) else null,
                elevation = 10.dp,
            )
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SongArtwork(app, song, Modifier.size(46.dp), shape = RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("即将播放", color = Brand, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(song?.optString("title").orEmpty(), color = Paper, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song?.artists().orEmpty(), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun PlayerPhoneCard(app: TvApp, modifier: Modifier = Modifier) {
    Column(
        modifier
            .glassSurface(TvShape.card, base = Glass.mediaSafe, elevation = 10.dp)
            .padding(11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HomeGlyph("phone", Modifier.size(12.dp), Brand)
            Text("手机点歌", color = Paper, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            Modifier.size(100.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (app.invitation.isNotBlank() && app.online) Qr(app.invitation, Modifier.fillMaxSize())
            else Text(
                if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成邀请中…" else "更多设置中重试",
                color = Paper,
                fontSize = 10.sp,
            )
        }
        val status = when {
            app.snapshot.optString("status") == "ENDED" -> "返回首页可新开场"
            !app.online -> "网络异常"
            app.invitationError.isNotBlank() -> "邀请暂不可用"
            app.invitationLoading -> "正在生成邀请"
            !app.participantsKnown -> "正在同步设备"
            app.participantCount == 0 -> "等待亲友加入"
            else -> "已加入 ${app.participantCount} 台"
        }
        Text(status, color = if (!app.online) Danger else if (app.participantCount > 0) Success else Muted, fontSize = 9.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("同一网络扫码连接", color = Muted.copy(alpha = .75f), fontSize = 8.5.sp, lineHeight = 11.sp)
    }
}
