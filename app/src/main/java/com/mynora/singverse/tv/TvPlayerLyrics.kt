@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.util.TypedValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
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

/** Uses the existing parsed, offset-adjusted Media3 timeline rather than a lyric timer. */
@Composable internal fun AudioPlayerLyrics(app: TvApp, modifier: Modifier = Modifier) {
    val options = app.playbackOptions
    val current = options.activeLine
    val next = options.nextLine
    options.plainLyrics.firstOrNull { it.first == options.textSelection }?.let { (_, text) ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("未同步歌词", color = Muted, fontSize = 12.sp)
            Text(
                text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(2).joinToString("\n"),
                color = Paper,
                fontSize = 22.sp,
                lineHeight = 29.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    if (options.textSelection == "内嵌字幕" && app.embeddedCues.isNotEmpty()) {
        val textSize = with(LocalDensity.current) { 24.sp.toPx() }
        AndroidView(
            factory = { SubtitleView(it) },
            update = {
                it.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                it.setCues(app.embeddedCues)
            },
            modifier = modifier.height(90.dp).semantics {
                contentDescription = app.embeddedCues.mapNotNull { it.text?.toString() }.joinToString("\n")
            },
        )
        return
    }
    var settled by remember(current?.start) { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (settled) 1f else .52f, tween(260, easing = FastOutSlowInEasing), label = "lyricAlpha")
    val lineScale by animateFloatAsState(if (settled) 1.02f else 0.97f, TvMotion.subtleSpring, label = "lyricScale")
    val slideY by animateFloatAsState(if (settled) 0f else 10f, tween(260, easing = FastOutSlowInEasing), label = "lyricSlide")
    LaunchedEffect(current?.start) { settled = true }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current != null) {
            val text = buildAnnotatedString {
                if (!current.hasCompleteWords) append(current.text)
                else current.words.forEach { word ->
                    withStyle(
                        SpanStyle(
                            color = if (word.at <= options.lyricPosition) Paper else Paper.copy(alpha = 0.30f),
                        )
                    ) { append(word.text) }
                }
            }
            Text(
                text,
                color = Paper,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .alpha(opacity)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = lineScale
                        scaleY = lineScale
                        translationY = slideY
                    },
            )
        } else Text(
            when {
                options.textSelection == "关闭" -> "歌词已关闭"
                options.textLoading -> "正在读取歌词…"
                options.textLoadFailed -> "歌词读取失败，可在歌词设置重试"
                options.textSelection == "内嵌字幕" && options.tracks.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT } -> "等待内嵌歌词 / 字幕"
                next != null -> "等待下一句"
                options.sidecars.isNotEmpty() -> "间奏"
                options.textWarnings.isNotEmpty() -> "歌词暂不可用 · ${options.textWarnings.first()}"
                else -> "暂无歌词"
            },
            color = Muted,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        )
        next?.let {
            Text(
                it.text,
                color = Paper.copy(alpha = .48f),
                fontSize = 19.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
