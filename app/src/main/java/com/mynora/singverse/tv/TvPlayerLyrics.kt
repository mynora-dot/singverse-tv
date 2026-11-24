@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.util.TypedValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/**
 * Apple Music Sing-inspired multi-line synchronized karaoke lyrics stage.
 * Uses the existing parsed, offset-adjusted Media3 timeline in PlaybackOptions.
 */
@Composable internal fun AudioPlayerLyrics(app: TvApp, modifier: Modifier = Modifier) {
    val options = app.playbackOptions
    val current = options.activeLine
    val next = options.nextLine

    // 1) Plain unsynchronized lyrics fallback
    options.plainLyrics.firstOrNull { it.first == options.textSelection }?.let { (_, text) ->
        val lines = remember(text) {
            text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(5).toList()
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("纯文本歌词", color = Paper.copy(alpha = 0.45f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            lines.forEachIndexed { idx, line ->
                Text(
                    line,
                    color = Paper.copy(alpha = (0.92f - idx * 0.14f).coerceAtLeast(0.32f)),
                    fontSize = if (idx == 0) 26.sp else 21.sp,
                    lineHeight = if (idx == 0) 34.sp else 28.sp,
                    fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    // 2) Embedded Media3 subtitle track
    if (options.textSelection == "内嵌字幕" && app.embeddedCues.isNotEmpty()) {
        val textSize = with(LocalDensity.current) { 28.sp.toPx() }
        AndroidView(
            factory = { SubtitleView(it) },
            update = {
                it.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                it.setCues(app.embeddedCues)
            },
            modifier = modifier.height(120.dp).semantics {
                contentDescription = app.embeddedCues.mapNotNull { it.text?.toString() }.joinToString("\n")
            },
        )
        return
    }

    // 3) Full multi-line synchronized karaoke window (Apple Music Sing feel)
    val allLines = remember(options.sidecars, options.textSelection) {
        options.sidecars.firstOrNull { it.first == options.textSelection }?.second.orEmpty()
    }
    val activeIndex = remember(allLines, current) {
        if (current == null || allLines.isEmpty()) -1
        else allLines.indexOfFirst { it.start == current.start && it.text == current.text }
    }
    val nextIndex = remember(allLines, activeIndex, next) {
        when {
            activeIndex >= 0 -> activeIndex + 1
            next != null -> allLines.indexOfFirst { it.start == next.start && it.text == next.text }
            else -> -1
        }
    }

    if (current == null && next == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when {
                    options.textSelection == "关闭" -> "歌词已关闭"
                    options.textLoading -> "正在读取歌词…"
                    options.textLoadFailed -> "歌词读取失败，可在歌词设置重试"
                    options.textSelection == "内嵌字幕" && options.tracks.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT } -> "等待内嵌歌词 / 字幕"
                    options.sidecars.isNotEmpty() -> "间奏"
                    options.textWarnings.isNotEmpty() -> "歌词暂不可用 · ${options.textWarnings.first()}"
                    else -> "暂无歌词"
                },
                color = Paper.copy(alpha = 0.52f),
                fontSize = 22.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        return
    }

    val prevLine = if (activeIndex > 0) allLines.getOrNull(activeIndex - 1) else null
    val upcoming1 = if (nextIndex >= 0) allLines.getOrNull(nextIndex) ?: next else next
    val upcoming2 = if (nextIndex >= 0) allLines.getOrNull(nextIndex + 1) else null
    val upcoming3 = if (nextIndex >= 0) allLines.getOrNull(nextIndex + 2) else null

    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Previous line (softly receded)
        if (prevLine != null && prevLine.text.isNotBlank()) {
            Text(
                prevLine.text,
                color = Color.White.copy(alpha = 0.24f),
                fontSize = 19.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Active karaoke line (hero 36sp bold with smooth vertical transition & luminous glow)
        if (current != null) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    (fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it / 3 })
                        .togetherWith(
                            fadeOut(tween(180)) +
                                slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 4 }
                        )
                },
                label = "activeLyricLine",
            ) { active ->
                val text = buildAnnotatedString {
                    if (!active.hasCompleteWords) {
                        append(active.text)
                    } else {
                        active.words.forEach { word ->
                            val sung = word.at <= options.lyricPosition
                            withStyle(
                                SpanStyle(
                                    color = if (sung) Color.White else Color.White.copy(alpha = 0.38f),
                                    shadow = if (sung) Shadow(Color.White.copy(alpha = 0.32f), Offset.Zero, 14f) else null,
                                )
                            ) { append(word.text) }
                        }
                    }
                }
                Text(
                    text,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 35.sp,
                        lineHeight = 44.sp,
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(Color.White.copy(alpha = 0.22f), Offset.Zero, 16f),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // Instrumental intro / interlude breathing dots before next line
            InterludeDots(app.playing && app.foreground)
        }

        // Upcoming lines (progressive Apple Music Sing opacity falloff)
        upcoming1?.takeIf { it.text.isNotBlank() }?.let { line ->
            Text(
                line.text,
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        upcoming2?.takeIf { it.text.isNotBlank() }?.let { line ->
            Text(
                line.text,
                color = Color.White.copy(alpha = 0.30f),
                fontSize = 20.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        upcoming3?.takeIf { it.text.isNotBlank() }?.let { line ->
            Text(
                line.text,
                color = Color.White.copy(alpha = 0.16f),
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable private fun InterludeDots(playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "interlude")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "interludePhase",
    )
    Row(
        Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val active = playing && phase >= index && phase < index + 1.35f
            val alpha by animateFloatAsState(if (active) 0.92f else 0.28f, tween(280), label = "dot$index")
            val scale by animateFloatAsState(if (active) 1.25f else 0.9f, tween(280), label = "dotScale$index")
            Box(
                Modifier
                    .size(9.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .alpha(alpha)
                    .background(Color.White, CircleShape)
            )
        }
    }
}

/** A low-cost playback status animation. It does not read or imply an audio spectrum. */
@Composable internal fun PlaybackAmbience(playing: Boolean, modifier: Modifier = Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (playing) { delay(90); phase += .18f }
    }
    Canvas(modifier.semantics { contentDescription = "播放状态动画，非实时频谱" }) {
        val count = 48
        val stride = size.width / count
        val barWidth = (stride * 0.44f).coerceAtLeast(2f)
        val brush = Brush.horizontalGradient(
            listOf(
                Glass.brand.copy(alpha = if (playing) 0.48f else 0.16f),
                Paper.copy(alpha = if (playing) 0.32f else 0.12f),
                Glass.brandCoral.copy(alpha = if (playing) 0.48f else 0.16f),
            )
        )
        repeat(count) { index ->
            val envelope = .18f + .82f * abs(sin(index * .16f))
            val pulse = if (playing) .28f + .72f * abs(sin(index * .58f + phase)) else .14f
            val height = (size.height * envelope * pulse).coerceAtLeast(3f)
            val top = (size.height - height) / 2f
            drawRoundRect(
                brush = brush,
                topLeft = Offset(index * stride, top),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
