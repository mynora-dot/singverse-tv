package com.mynora.singverse.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import org.json.JSONObject

private val ConsoleShape = RoundedCornerShape(22.dp)
private val VideoConsoleBase = Color(0xDE0B0E14)

/** Unified Hi-Fi playback console shared by MV video and pure-audio modes. */
@Composable internal fun PlayerControls(
    app: TvApp,
    audioOnly: Boolean,
    song: JSONObject? = null,
    onQueue: () -> Unit,
    onOutput: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    primaryFocus: FocusRequester,
) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" }
    val waiting = queue.firstOrNull { it.optString("status") in listOf("WAITING", "HANDOFF") }
    val playbackId = app.snapshot.optJSONObject("playback")?.optString("songId").orEmpty()
    val songId = app.currentSong?.optString("id")?.takeIf { it.isNotBlank() && it == playbackId }.orEmpty()
    val hasSong = current != null || (playbackId.isNotBlank() && app.playerState in listOf("PLAYING", "PAUSED", "PREPARING", "BUFFERING", "ERROR"))
    val confirming = current == null && waiting != null && app.snapshot.optJSONObject("playback")?.optBoolean("confirmed") != true
    val canRestart = !confirming && playbackId.isNotBlank() && (current != null || songId.isNotBlank())
    val retrying = !confirming && app.playerState == "ERROR"
    val pause = app.playing
    val label = when {
        confirming -> "确认开唱"
        retrying -> "重试"
        pause -> "暂停"
        hasSong -> "继续"
        else -> "播放"
    }

    Column(
        modifier
            .fillMaxWidth()
            .glassSurface(
                ConsoleShape,
                highlighted = false,
                base = if (audioOnly) Glass.surfaceFloat else VideoConsoleBase,
                elevation = 10.dp,
                backdropBlur = audioOnly,
            )
            .padding(horizontal = 16.dp, vertical = if (audioOnly) 11.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (audioOnly) 8.dp else 9.dp),
    ) {
        if (!audioOnly) VideoTrackHeader(app, song)
        PlayerProgress(app)
        Row(
            Modifier.fillMaxWidth().height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlayerControlButton(
                label,
                if (pause) "pause" else if (retrying) "restart" else "play",
                "player:pause",
                Modifier.focusRequester(primaryFocus),
                primary = true,
                enabled = hasSong || confirming,
            ) {
                when {
                    confirming -> app.command("START")
                    retrying -> app.retryPlayback()
                    pause -> app.pauseLocal()
                    else -> app.command("RESUME")
                }
            }
            PlayerControlButton("重唱", "restart", "player:restart", enabled = canRestart) { app.command("RESTART") }
            PlayerControlButton("切歌", "next", "player:next", enabled = hasSong || waiting != null) { app.command("NEXT") }

            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(16.dp)
                    .background(Color(0x1AFFFFFF)),
            )

            PlayerControlButton("已点", "queue", "player:queue", onClick = onQueue)
            val favorite = songId.isNotBlank() && app.isFavorite(songId)
            PlayerControlButton(
                if (favorite) "已收藏" else "收藏",
                "favorite",
                "player:favorite",
                selected = favorite,
                enabled = songId.isNotBlank() && !app.favoriteSaving,
            ) { app.toggleFavorite() }

            Spacer(Modifier.weight(1f))

            val roleSwitchActive = app.playerState !in listOf("IDLE", "ENDED")
            val canOriginal = roleSwitchActive && app.canUseAudioRole(true)
            val canBacking = roleSwitchActive && app.canUseAudioRole(false)
            // Recessed obsidian segmented track for Vocal / Backing switch
            Row(
                Modifier
                    .clip(GlassShape.pill)
                    .background(Color(0x6605070C))
                    .border(0.75.dp, Color(0x1CFFFFFF), GlassShape.pill)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerControlButton(
                    "原唱",
                    "original",
                    "player:role:original",
                    selected = canOriginal && app.activeAudioRole == "original",
                    enabled = canOriginal,
                    segmented = true,
                ) { app.useAudioRole(true) }
                PlayerControlButton(
                    "伴奏",
                    "backing",
                    "player:role:backing",
                    selected = canBacking && app.activeAudioRole == "backing",
                    enabled = canBacking,
                    segmented = true,
                ) { app.useAudioRole(false) }
            }

            Spacer(Modifier.weight(1f))

            PlayerControlButton("声音输出", "output", "player:output", onClick = onOutput)
            PlayerControlButton(if (audioOnly) "更多" else "更多设置", "more", "player:more", onClick = onMore)
        }
    }
}

@Composable private fun VideoTrackHeader(app: TvApp, song: JSONObject?) {
    val coverShape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            Text(
                song?.optString("title").orEmpty().ifBlank { "等待演唱" },
                color = Paper,
                fontSize = 15.5.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val artist = song?.artists().orEmpty()
            if (artist.isNotBlank()) {
                Text(
                    artist,
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ConsoleStatusBadge(app)
    }
}

@Composable private fun ConsoleStatusBadge(app: TvApp) {
    val state = app.playerState
    val isError = state == "ERROR"
    val isPaused = state == "PAUSED"
    val isLoading = state in listOf("PREPARING", "BUFFERING")
    val textColor = when {
        isError -> Danger
        app.playing -> Paper.copy(alpha = 0.90f)
        else -> Paper.copy(alpha = 0.72f)
    }
    Row(
        Modifier
            .clip(GlassShape.pill)
            .background(if (isError) Color(0x24FF453A) else Color(0x12FFFFFF))
            .border(0.75.dp, if (isError) Danger.copy(alpha = 0.36f) else Color(0x18FFFFFF), GlassShape.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            app.playing -> LiveEqualizer(playing = true, color = Brand)
            isPaused -> Canvas(Modifier.size(width = 8.dp, height = 9.dp)) {
                val barW = 2.dp.toPx()
                val r = CornerRadius(barW / 2f, barW / 2f)
                val tint = Paper.copy(alpha = 0.56f)
                drawRoundRect(tint, Offset(0f, 0f), Size(barW, size.height), r)
                drawRoundRect(tint, Offset(size.width - barW, 0f), Size(barW, size.height), r)
            }
            else -> Box(
                Modifier
                    .size(6.dp)
                    .background(
                        when {
                            isError -> Danger
                            isLoading -> Accent
                            else -> Paper.copy(alpha = 0.42f)
                        },
                        CircleShape,
                    ),
            )
        }
        Text(
            stateLabel(state),
            color = textColor,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Left/right previews only. Actual seeking requires confirmation, and Back/focus loss cancels. */
@Composable private fun PlayerProgress(app: TvApp) {
    val songId = app.snapshot.optJSONObject("playback")?.optString("songId").orEmpty()
    var preview by remember(songId) { mutableStateOf<Long?>(null) }
    var focused by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val isLit = focused && windowFocused
    val duration = app.duration.coerceAtLeast(0)
    val position = (preview ?: app.position).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val trackHeight by animateDpAsState(targetValue = if (isLit) 6.dp else 4.dp, label = "progressTrackHeight")
    val thumbSize by animateDpAsState(targetValue = if (isLit) 13.dp else 9.dp, label = "progressThumbSize")

    BackHandler(enabled = focused && preview != null) { preview = null }
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .tvFocusTarget("player:progress")
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) preview = null }
            .tvSpringFocus(isLit, scaleTarget = 1.008f, liftDp = 0.5.dp)
            .then(
                if (isLit) {
                    Modifier
                        .clip(GlassShape.pill)
                        .background(Color(0x18FFFFFF))
                        .tvFocus(true, GlassShape.pill)
                } else Modifier
            )
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown && duration > 0) {
                            val offset = if (event.key == Key.DirectionLeft) -10_000L else 10_000L
                            preview = ((preview ?: app.position) + offset).coerceIn(0, duration)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyDown) {
                            preview?.let { app.seekAbsolute(it.coerceIn(0, duration)); preview = null }
                        }
                        true
                    }
                    else -> false
                }
            }
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                stateDescription = if (preview != null) "预览 ${controlTime(position)}，确认跳转，返回取消" else "${controlTime(position)} / ${controlTime(duration)}，左右预览"
            }
            .focusable()
            .padding(horizontal = if (isLit) 10.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            controlTime(position),
            color = when {
                preview != null -> Glass.brandCoral
                isLit -> Color.White
                else -> Paper.copy(alpha = 0.88f)
            },
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(min = 32.dp),
        )
        if (preview != null) {
            Text(
                "确认跳转 · 返回取消",
                color = Glass.brandCoral,
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(GlassShape.pill)
                    .background(Color(0x24E85668))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Box(Modifier.weight(1f).height(14.dp), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .background(Color(0x24FFFFFF), GlassShape.pill),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(
                            if (preview != null) Brush.horizontalGradient(listOf(Brand, Glass.brandCoral))
                            else Brush.horizontalGradient(listOf(Glass.brandVivid, Glass.brandCoral)),
                            GlassShape.pill,
                        ),
                )
            }
            Box(
                Modifier.fillMaxWidth(fraction.coerceAtLeast(0.006f)).fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .requiredSize(thumbSize)
                        .shadow(if (isLit) 4.dp else 2.dp, CircleShape, clip = false)
                        .background(Color.White, CircleShape),
                )
            }
        }
        Text(
            controlTime(duration),
            color = if (isLit) Paper.copy(alpha = 0.82f) else Muted,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 32.dp),
        )
    }
}

@Composable private fun PlayerControlButton(
    label: String,
    icon: String,
    focusKey: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    segmented: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = GlassShape.pill
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val isVisuallyFocused = focused && windowFocused

    val surfaceModifier = when {
        !enabled -> if (segmented) Modifier else Modifier.clip(shape).background(Color(0x08FFFFFF))
        isVisuallyFocused -> Modifier.tvPlatinumFocus(true, shape)
        primary -> Modifier
            .clip(shape)
            .background(Color(0x1CFFFFFF))
            .border(0.75.dp, Color(0x2EFFFFFF), shape)
        selected -> Modifier
            .clip(shape)
            .background(if (segmented) Color(0x22FFFFFF) else Color(0x1AFFFFFF))
            .border(0.75.dp, Color(0x2CFFFFFF), shape)
        segmented -> Modifier
        else -> Modifier.clip(shape).background(Color(0x0EFFFFFF))
    }

    val restingTextColor = when {
        !enabled -> Muted.copy(alpha = 0.36f)
        primary || selected -> Paper
        segmented -> Muted
        else -> Paper.copy(alpha = 0.88f)
    }
    val iconColor = when {
        !enabled -> Muted.copy(alpha = 0.34f)
        isVisuallyFocused -> Glass.focusInk
        selected -> Accent
        primary -> Glass.brandCoral
        segmented -> Muted
        else -> Paper.copy(alpha = 0.76f)
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(if (segmented) 32.dp else 36.dp)
            .tvFocusTarget("action:$focusKey")
            .focusProperties { if (!enabled) canFocus = false }
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(isVisuallyFocused && enabled, scaleTarget = 1.045f, liftDp = 0.8.dp)
            .then(surfaceModifier)
            .semantics { this.selected = selected },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = restingTextColor,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Muted.copy(alpha = 0.36f),
        ),
        contentPadding = PaddingValues(
            horizontal = when {
                primary -> 14.dp
                segmented -> 11.dp
                else -> 12.dp
            },
            vertical = 4.dp,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (selected) {
                HomeGlyph("check", Modifier.size(12.5.dp), iconColor)
            } else {
                PlayerControlGlyph(icon, iconColor)
            }
            Text(
                label,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                fontWeight = if (primary || selected || isVisuallyFocused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable private fun PlayerControlGlyph(kind: String, color: Color) {
    Canvas(Modifier.size(13.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * .108f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), stroke.width, StrokeCap.Round)
        when (kind) {
            "pause" -> {
                val barW = w * .18f
                val r = CornerRadius(barW / 2f, barW / 2f)
                drawRoundRect(color, Offset(w * .22f, h * .18f), Size(barW, h * .64f), r)
                drawRoundRect(color, Offset(w * .60f, h * .18f), Size(barW, h * .64f), r)
            }
            "play" -> drawPath(
                Path().apply {
                    moveTo(w * .28f, h * .16f)
                    lineTo(w * .84f, h * .50f)
                    lineTo(w * .28f, h * .84f)
                    close()
                },
                color,
            )
            "restart" -> {
                drawArc(color, -45f, 285f, false, Offset(w * .16f, h * .16f), Size(w * .68f, h * .68f), style = stroke)
                line(.74f, .08f, .74f, .32f)
                line(.74f, .32f, .5f, .32f)
            }
            "next" -> {
                drawPath(
                    Path().apply {
                        moveTo(w * .16f, h * .20f)
                        lineTo(w * .66f, h * .50f)
                        lineTo(w * .16f, h * .80f)
                        close()
                    },
                    color,
                )
                line(.80f, .20f, .80f, .80f)
            }
            "queue" -> {
                repeat(3) { i ->
                    val y = .24f + i * .26f
                    drawCircle(color, w * .055f, Offset(w * .15f, h * y))
                    line(.34f, y, .88f, y)
                }
            }
            "favorite" -> drawPath(
                Path().apply {
                    moveTo(w * .5f, h * .86f)
                    cubicTo(w * -.16f, h * .38f, w * .14f, h * -.04f, w * .5f, h * .28f)
                    cubicTo(w * .86f, h * -.04f, w * 1.16f, h * .38f, w * .5f, h * .86f)
                    close()
                },
                color,
                style = stroke,
            )
            "original" -> {
                drawRoundRect(color, Offset(w * .36f, h * .08f), Size(w * .28f, h * .5f), CornerRadius(w * .14f), style = stroke)
                drawArc(color, 0f, 180f, false, Offset(w * .2f, h * .32f), Size(w * .6f, h * .4f), style = stroke)
                line(.5f, .72f, .5f, .91f)
                line(.34f, .92f, .66f, .92f)
            }
            "backing" -> {
                line(.39f, .16f, .39f, .77f)
                line(.39f, .16f, .82f, .08f)
                line(.82f, .08f, .82f, .68f)
                drawOval(color, Offset(w * .12f, h * .68f), Size(w * .27f, h * .2f), style = stroke)
                drawOval(color, Offset(w * .55f, h * .6f), Size(w * .27f, h * .2f), style = stroke)
            }
            "output" -> {
                drawPath(
                    Path().apply {
                        moveTo(w * .1f, h * .36f)
                        lineTo(w * .31f, h * .36f)
                        lineTo(w * .52f, h * .18f)
                        lineTo(w * .52f, h * .82f)
                        lineTo(w * .31f, h * .64f)
                        lineTo(w * .1f, h * .64f)
                        close()
                    },
                    color,
                    style = stroke,
                )
                drawArc(color, -52f, 104f, false, Offset(w * .50f, h * .22f), Size(w * .38f, h * .56f), style = stroke)
            }
            else -> repeat(3) { i ->
                drawCircle(color, w * .085f, Offset(w * (.20f + i * .30f), h * .5f))
            }
        }
    }
}

private fun controlTime(ms: Long): String = "${ms.coerceAtLeast(0) / 60_000}:${(ms.coerceAtLeast(0) / 1_000 % 60).toString().padStart(2, '0')}"
