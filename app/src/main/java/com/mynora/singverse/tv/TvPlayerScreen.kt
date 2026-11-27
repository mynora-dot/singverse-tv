@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.os.SystemClock
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
    val controlsVisible = app.controlVisible
    val playback = app.snapshot.optJSONObject("playback")
    val currentId = playback?.optString("songId").orEmpty()
    val song = app.currentSong?.takeIf { it.optString("id") == currentId }
        ?: app.snapshot.optJSONArray("queue")?.objects()?.firstOrNull { it.optString("songId") == currentId || it.optJSONObject("song")?.optString("id") == currentId }?.optJSONObject("song")

    LaunchedEffect(currentId) {
        if (currentId.isNotBlank()) {
            lastInteraction = SystemClock.elapsedRealtime()
            app.controlVisible = true
        }
    }
    LaunchedEffect(controlsVisible, audioOnly, currentId) {
        withFrameNanos { }; withFrameNanos { }
        if (memory.activeDialogs == 0) {
            if (controlsVisible) {
                if (memory.last[page] == "player" || !memory.restore(page)) {
                    if (!runCatching { primary.requestFocus() }.getOrDefault(false)) {
                        listOf("restart", "queue", "favorite", "output", "more").firstOrNull { key ->
                            memory.targets["$page:action:player:$key"]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
                        }
                    }
                }
            } else surfaceFocus.requestFocus()
        }
    }
    LaunchedEffect(controlsVisible, app.playerState) {
        if (controlsVisible && memory.activeDialogs == 0 && app.playerState in listOf("IDLE", "ENDED")) {
            withFrameNanos { }; withFrameNanos { }
            if (memory.activeDialogs == 0 && memory.focusedTargets.none { it.startsWith("$page:") }) {
                if (!runCatching { primary.requestFocus() }.getOrDefault(false)) {
                    listOf("restart", "queue", "favorite", "output", "more").firstOrNull { key ->
                        memory.targets["$page:action:player:$key"]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
                    }
                }
            }
        }
    }
    LaunchedEffect(controlsVisible, audioOnly, app.playing, app.foreground, lastInteraction, memory.activeDialogs) {
        if (controlsVisible && app.playing && app.foreground && memory.activeDialogs == 0) {
            delay(3500)
            app.controlVisible = false
        }
    }
    BackHandler(enabled = memory.activeDialogs == 0 && app.pendingConfirm == null && app.error.isBlank()) {
        if (controlsVisible) app.controlVisible = false else app.navigate("首页")
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(if (audioOnly) Ink else Color.Black)
            .semantics { stateDescription = if (audioOnly) "音频播放器" else "视频播放器" }
            .onPreviewKeyEvent {
                lastInteraction = SystemClock.elapsedRealtime()
                if (!controlsVisible && it.type == KeyEventType.KeyUp && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight)) {
                    app.controlVisible = true
                    true
                } else false
            },
    ) {
        if (audioOnly) AudioPlayerScene(app, song, controlsVisible) else VideoPlayerSurface(app)
        Box(Modifier.fillMaxSize().focusRequester(surfaceFocus).tvFocusTarget("player").focusable(enabled = !controlsVisible))
        PlayerHeader(app, audioOnly, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp))
        AnimatedVisibility(
            visible = audioOnly || !cleanVideo,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 32.dp),
            enter = fadeIn(tween(260)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(220)) { it / 3 },
        ) {
            PlayerSideRail(app, audioOnly)
        }
        AnimatedVisibility(
            visible = audioOnly && !controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 42.dp, vertical = 22.dp),
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(160)),
        ) {
            AudioMinimalProgress(app)
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 3 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it / 3 },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (audioOnly) Brush.verticalGradient(listOf(Color.Transparent, Color(0xBD080A0E)))
                        else Brush.verticalGradient(listOf(Color.Transparent, Color(0xD806080C)))
                    )
                    .padding(horizontal = 32.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlayerControls(
                    app = app,
                    audioOnly = audioOnly,
                    song = song,
                    onQueue = { queueOpen = true },
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
    val showEmbedded = app.hasConfirmedPlayback && app.lyrics == TvLyrics.Embedded
    val subtitleBottomPadding = if (app.controlVisible) 134.dp else 8.dp
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> PlayerView(context).apply { player = app.player; useController = false; isFocusable = false } },
            modifier = Modifier.fillMaxSize(),
            update = { it.subtitleView?.visibility = if (showEmbedded) View.VISIBLE else View.INVISIBLE },
            onRelease = { it.player = null },
        )
        VideoPlayerLyrics(app, Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = subtitleBottomPadding))
    }
}

@Composable private fun AudioPlayerScene(app: TvApp, song: JSONObject?, controlsVisible: Boolean) {
    val songId = song?.optString("id").orEmpty()
    val coverPath = song?.optString("coverPath").orEmpty()
    val revision = song.artworkVersion()
    val scope = app.client.artworkScope
    var ambientPair by remember(scope, songId, coverPath, revision) { mutableStateOf(fallbackAmbientColors(songId)) }
    var stageBackdrop by remember(scope, coverPath, revision) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(scope, songId, coverPath, revision) {
        if (coverPath.isNotBlank()) {
            val bmp = try {
                app.client.image(coverPath, revision)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (bmp != null) {
                val extracted = withContext(Dispatchers.Default) { cachedAmbientColors("$scope:$songId:$revision", bmp) }
                if (extracted != null) ambientPair = extracted
                stageBackdrop = if (GlassTier.current.supportsBackdropBlur) bmp
                else withContext(Dispatchers.Default) { softenedArtistBackground(bmp) }
            } else stageBackdrop = null
        } else stageBackdrop = null
    }

    val isLive = app.playing && app.foreground
    val bottomClearance by animateDpAsState(
        targetValue = if (controlsVisible) 118.dp else 56.dp,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "stageBottomClearance",
    )

    Box(Modifier.fillMaxSize()) {
        // 1) Living Liquid-Silk Album Backdrop (drifting blurred cover art + orbiting color mesh)
        LivingAlbumBackdrop(
            backdropBitmap = stageBackdrop,
            primaryGlow = ambientPair.first,
            secondaryGlow = ambientPair.second,
            animate = isLive,
            modifier = Modifier.fillMaxSize().tvHazeSource(LocalTvHazeState.current),
        )

        // 2) Apple Music Sing × Hi-Fi Turntable Two-Column Stage
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 224.dp, top = 66.dp, bottom = bottomClearance),
            horizontalArrangement = Arrangement.spacedBy(42.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left Column: Living Spinning Vinyl + Floating Album Cover + Track Identity
            Column(
                Modifier.width(308.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                LivingVinylCover(
                    app = app,
                    song = song,
                    accentGlow = ambientPair.first,
                    playing = isLive,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        song?.optString("title").orEmpty().ifBlank { "等待歌曲" },
                        color = Color.White,
                        fontSize = 23.sp,
                        lineHeight = 29.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    song?.let {
                        Text(
                            it.artists(),
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val labels = app.audioQualityLabels.take(3)
                if (labels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.forEach { badge ->
                            Text(
                                badge,
                                color = Color.White.copy(alpha = 0.80f),
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .glassSurface(GlassShape.pill, intensity = 0.65f, base = Color(0x28FFFFFF), elevation = 0.dp)
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            Box(
                Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (app.hasConfirmedPlayback) {
                    AudioPlayerLyrics(app, Modifier.fillMaxWidth())
                } else {
                    Text(
                        if (app.playerState == "ERROR") "播放失败，可在下方重试或切歌" else "确认开唱后显示歌词",
                        color = if (app.playerState == "ERROR") Danger else Color.White.copy(alpha = 0.56f),
                        fontSize = 22.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Living Apple Music Sing Liquid-Silk Backdrop:
 * Slowly drifting blurred album art + orbiting dual-tone ambient light mesh so Liquid Glass
 * controls genuinely refract moving color while keeping white lyrics razor-sharp.
 */
@Composable private fun LivingAlbumBackdrop(
    backdropBitmap: android.graphics.Bitmap?,
    primaryGlow: Color,
    secondaryGlow: Color,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val tier = GlassTier.current
    val transition = rememberInfiniteTransition(label = "livingBackdrop")
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * kotlin.math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "backdropOrbit",
    )
    val driftX = if (animate) kotlin.math.cos(orbit) * 34f else 0f
    val driftY = if (animate) kotlin.math.sin(orbit * 0.7f) * 24f else 0f
    val slowRot = if (animate) kotlin.math.sin(orbit * 0.5f) * 4.5f else 0f

    Box(modifier.background(Color(0xFF07090D))) {
        if (backdropBitmap != null) {
            val blurMod = if (tier.supportsBackdropBlur) Modifier.blur(64.dp) else Modifier
            Image(
                bitmap = backdropBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.56f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.32f
                        scaleY = 1.32f
                        translationX = driftX
                        translationY = driftY
                        rotationZ = slowRot
                    }
                    .then(blurMod),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx1 = w * 0.24f + driftX * 2.2f
            val cy1 = h * 0.42f + driftY * 1.8f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryGlow.copy(alpha = 0.58f), Color.Transparent),
                    center = Offset(cx1, cy1),
                    radius = h * 0.92f,
                ),
                radius = h * 0.92f,
                center = Offset(cx1, cy1),
            )
            val cx2 = w * 0.78f - driftX * 1.8f
            val cy2 = h * 0.58f - driftY * 1.6f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondaryGlow.copy(alpha = 0.46f), Color.Transparent),
                    center = Offset(cx2, cy2),
                    radius = h * 0.88f,
                ),
                radius = h * 0.88f,
                center = Offset(cx2, cy2),
            )
            // Balanced cinematic scrim: keeps living album color rich while ensuring >10:1 lyric readability
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color(0x7806080C),
                        0.45f to Color(0x6407090E),
                        1f to Color(0xB805070B),
                    )
                )
            )
        }
    }
}

/**
 * Non-static Living Album Sleeve + Spinning Hi-Fi Vinyl Record:
 * - Vinyl glides smoothly out from behind the sleeve and rotates continuously while playing.
 * - Album cover breathes subtly with a color-matched ambient halo and Liquid Glass specular edge.
 */
@Composable private fun LivingVinylCover(
    app: TvApp,
    song: JSONObject?,
    accentGlow: Color,
    playing: Boolean,
) {
    val vinylOffset by animateDpAsState(
        targetValue = if (playing) 74.dp else 22.dp,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "vinylPeek",
    )
    val coverBaseScale by animateFloatAsState(
        targetValue = if (playing) 1.0f else 0.94f,
        animationSpec = TvMotion.focusSpring,
        label = "coverPlayScale",
    )
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                val start = rotation.value
                rotation.animateTo(
                    targetValue = start + 360f,
                    animationSpec = tween(durationMillis = 16000, easing = LinearEasing),
                )
                rotation.snapTo(rotation.value % 360f)
            }
        }
    }
    val breathTransition = rememberInfiniteTransition(label = "coverBreath")
    val breath by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.018f,
        animationSpec = infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "coverBreathScale",
    )
    val finalCoverScale = coverBaseScale * (if (playing) breath else 1f)
    val coverShape = RoundedCornerShape(22.dp)

    Box(
        Modifier.width(308.dp).height(240.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Soft color-matched ambient aura behind the sleeve
        Canvas(Modifier.size(256.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accentGlow.copy(alpha = if (playing) 0.52f else 0.24f), Color.Transparent),
                    center = center,
                    radius = size.minDimension * 0.68f,
                ),
                radius = size.minDimension * 0.68f,
                center = center,
            )
        }
        // Spinning Hi-Fi Vinyl Record emerging from behind the sleeve
        Box(
            Modifier
                .offset(x = vinylOffset)
                .size(220.dp)
                .shadow(18.dp, CircleShape, ambientColor = Color(0xAA000000), spotColor = Color(0xAA000000), clip = false)
                .graphicsLayer { rotationZ = rotation.value },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                // Deep obsidian vinyl body
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color(0xFF1A1D24),
                            0.36f to Color(0xFF0B0D12),
                            0.72f to Color(0xFF141820),
                            0.97f to Color(0xFF0A0C10),
                            1f to Color(0xFF2B313D),
                        ),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                )
                // Diagonal studio specular light sweep on the spinning vinyl
                drawCircle(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.08f to Color.White.copy(alpha = 0.14f),
                            0.18f to Color.Transparent,
                            0.52f to Color.Transparent,
                            0.60f to Color.White.copy(alpha = 0.11f),
                            0.70f to Color.Transparent,
                            1.0f to Color.Transparent,
                        ),
                        center = center,
                    ),
                    radius = r * 0.96f,
                )
                // Concentric micro-grooves
                listOf(0.86f, 0.76f, 0.66f, 0.56f, 0.46f).forEach { factor ->
                    drawCircle(Color.White.copy(alpha = 0.06f), radius = r * factor, style = Stroke(width = 1.dp.toPx()))
                }
                // Center record label tinted with album's color
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentGlow.copy(alpha = 0.92f), accentGlow.copy(alpha = 0.55f)),
                        center = center,
                        radius = r * 0.30f,
                    ),
                    radius = r * 0.30f,
                )
                drawCircle(Color.White.copy(alpha = 0.25f), radius = r * 0.30f, style = Stroke(width = 1.dp.toPx()))
                // Spindle hole
                drawCircle(Color(0xFF090B0E), radius = r * 0.07f)
            }
        }
        // Floating Living Album Sleeve with Liquid Glass specular edge
        Box(
            Modifier
                .size(234.dp)
                .graphicsLayer { scaleX = finalCoverScale; scaleY = finalCoverScale }
                .shadow(24.dp, coverShape, ambientColor = Color(0xB8000000), spotColor = Color(0xB8000000), clip = false)
                .clip(coverShape)
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f))
                    ),
                    coverShape,
                ),
        ) {
            SongArtwork(
                app,
                song,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                shape = coverShape,
            )
        }
    }
}

@Composable private fun AudioMinimalProgress(app: TvApp) {
    val duration = app.duration.coerceAtLeast(0L)
    val position = app.position.coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x22FFFFFF), GlassShape.pill)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Paper.copy(alpha = 0.78f), GlassShape.pill)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(time(position), color = Paper.copy(alpha = 0.65f), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
            Text("按确认键唤出控制栏", color = Paper.copy(alpha = 0.36f), fontSize = 10.5.sp, lineHeight = 14.sp)
            Text(time(duration), color = Paper.copy(alpha = 0.50f), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable private fun PlayerHeader(app: TvApp, audioOnly: Boolean, modifier: Modifier) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(30000) } }
    val waiting = app.snapshot.optJSONObject("playback")?.optBoolean("confirmed") != true &&
        app.snapshot.optJSONArray("queue")?.objects()?.any { it.optString("status") == "HANDOFF" } == true
    val status = if (waiting) "等待确认开唱" else "${stateLabel(app.playerState)}${if (audioOnly) " · 音频" else ""}"
    val pillBase = if (audioOnly) Glass.surfaceFloat else Color(0xD80B0E14)
    val netTone = if (app.online) Success else Danger
    val dotAlpha = if (app.online) {
        val transition = rememberInfiniteTransition(label = "headerNetPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "headerDotAlpha",
        ).value
    } else 1f
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            if (audioOnly) Modifier else Modifier.glassSurface(GlassShape.pill, base = pillBase, elevation = 6.dp).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeGlyph("brand", Modifier.size(22.dp), Paper)
            Text("SingVerse", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.glassSurface(GlassShape.pill, base = pillBase, elevation = 6.dp, backdropBlur = audioOnly).padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            LiveEqualizer(app.playing, color = if (app.playerState == "ERROR" || !app.online) Danger else Brand)
            Text(status, color = if (app.playerState == "ERROR" || !app.online) Danger else Paper, fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.glassSurface(GlassShape.pill, base = pillBase, elevation = 6.dp, backdropBlur = audioOnly).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (clock.isNotEmpty()) {
                Text(clock, color = Paper, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.width(1.dp).height(11.dp).background(Color(0x22FFFFFF)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).background(netTone.copy(alpha = dotAlpha), CircleShape))
                Text(
                    if (app.online) "NAS 已连接" else "网络异常",
                    color = netTone,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable private fun PlayerSideRail(app: TvApp, audioOnly: Boolean, modifier: Modifier = Modifier) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val playback = app.snapshot.optJSONObject("playback")
    val current = if (playback?.optBoolean("confirmed") == true) playback.optString("itemId")
    else queue.firstOrNull { it.optString("status") == "PLAYING" }?.optString("id").orEmpty()
    val next = tvNextQueuedItem(queue, { it.optString("status") }, { it.optString("id") }, current)
    val waitingCount = queue.count { it.optString("status") == "WAITING" && it.optString("id") != current }
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (next != null) NextSongCard(app, next, waitingCount, audioOnly, Modifier.width(182.dp))
        PlayerPhoneCard(app, audioOnly)
    }
}

@Composable private fun NextSongCard(
    app: TvApp,
    item: JSONObject,
    waitingCount: Int,
    audioOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val song = item.optJSONObject("song")
    val ending = app.duration > 0 && app.duration - app.position in 0..30000
    val cardShape = RoundedCornerShape(15.dp)
    val coverShape = RoundedCornerShape(8.dp)
    val dotAlpha = if (ending) {
        val transition = rememberInfiniteTransition(label = "nextSongPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.38f,
            animationSpec = infiniteRepeatable(tween(760, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "nextDotAlpha",
        ).value
    } else 1f
    Row(
        modifier
            .glassSurface(
                cardShape,
                highlighted = ending,
                base = if (audioOnly) Glass.surfaceCard else Color(0xD80B0E14),
                tintAccent = if (ending) Brand.copy(alpha = 0.14f) else null,
                elevation = 8.dp,
                backdropBlur = audioOnly,
            )
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(coverShape)
                .border(0.75.dp, Color(0x22FFFFFF), coverShape),
        ) {
            SongArtwork(app, song, Modifier.fillMaxSize(), shape = coverShape)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier
                        .size(4.5.dp)
                        .background(
                            (if (ending) Glass.brandCoral else Accent).copy(alpha = dotAlpha),
                            CircleShape,
                        ),
                )
                Text(
                    "即将播放",
                    color = if (ending) Glass.brandCoral else Paper.copy(alpha = 0.62f),
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (waitingCount > 1) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "共${waitingCount}首",
                        color = Muted.copy(alpha = 0.72f),
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
            Text(
                song?.optString("title").orEmpty(),
                color = Paper,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val artist = song?.artists().orEmpty()
            if (artist.isNotBlank()) {
                Text(
                    artist,
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable private fun PlayerPhoneCard(app: TvApp, audioOnly: Boolean, modifier: Modifier = Modifier) {
    val outerShape = RoundedCornerShape(16.dp)
    val innerShape = RoundedCornerShape(10.dp)
    val ready = app.invitation.isNotBlank() && app.online
    Box(
        modifier
            .size(116.dp)
            .glassSurface(
                outerShape,
                base = if (audioOnly) Glass.surfaceCard else Color(0xD80B0E14),
                elevation = 8.dp,
                backdropBlur = audioOnly,
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (ready) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(innerShape)
                    .background(Color.White)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Qr(app.invitation, Modifier.fillMaxSize())
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(innerShape)
                    .background(Color(0x12FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成中…" else "暂不可用",
                    color = Muted,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
