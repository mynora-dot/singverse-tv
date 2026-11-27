@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.util.TypedValue
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text

private val AudioLyricSize = 28.sp
private val AudioSubtitleStyle = with(CaptionStyleCompat.DEFAULT) {
    CaptionStyleCompat(Glass.brandBlue.toArgb(), backgroundColor, windowColor, edgeType, edgeColor, typeface)
}

@Composable internal fun AudioPlayerLyrics(app: TvApp, modifier: Modifier = Modifier) {
    when (val lyrics = app.lyrics) {
        TvLyrics.Loading -> AudioLyricStatus("正在读取歌词…", modifier)
        TvLyrics.Embedded -> AudioPlayerSubtitles(app, modifier)
        is TvLyrics.Timed -> app.timedLyricPreview?.let { TimedAudioLyrics(it, modifier) }
        is TvLyrics.Plain -> {
            val preview = remember(lyrics) { lyrics.text.lyricPreview(5) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("未同步歌词", color = Paper.copy(alpha = 0.52f), fontSize = 14.sp)
                Text(preview, color = Paper, fontSize = 22.sp, lineHeight = 30.sp,
                    maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        null -> AudioLyricStatus("暂无歌词", modifier)
    }
}

@Composable private fun TimedAudioLyrics(preview: LyricPreview, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        preview.current?.let { text ->
            Text(
                text,
                modifier = Modifier.semantics { stateDescription = "当前歌词" },
                color = Glass.brandBlue,
                fontSize = AudioLyricSize,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        preview.upcoming.forEach { text ->
            Text(
                text,
                modifier = Modifier.semantics { stateDescription = "后续歌词" },
                color = Paper.copy(alpha = 0.76f),
                fontSize = 22.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable internal fun VideoPlayerLyrics(app: TvApp, modifier: Modifier) {
    if (!app.hasConfirmedPlayback) return
    val text = when (val lyrics = app.lyrics) {
        is TvLyrics.Timed -> app.timedLyricPreview?.current
        is TvLyrics.Plain -> remember(lyrics) { "未同步歌词\n${lyrics.text.lyricPreview(2)}" }
        else -> null
    } ?: return
    val cues = remember(text) { listOf(Cue.Builder().setText(text).build()) }
    PlayerSubtitleView(cues, modifier)
}

private fun String.lyricPreview(lines: Int): String = lineSequence().filter(String::isNotBlank).take(lines).joinToString("\n")

@Composable private fun AudioLyricStatus(text: String, modifier: Modifier) {
    Text(text, modifier = modifier, color = Paper.copy(alpha = 0.52f), fontSize = 22.sp,
        lineHeight = 30.sp, fontWeight = FontWeight.Medium)
}

@Composable private fun AudioPlayerSubtitles(app: TvApp, modifier: Modifier) {
    val cues = app.embeddedCues
    if (cues.isEmpty() && app.playbackOptions.tracks.none { it.type == C.TRACK_TYPE_TEXT }) {
        AudioLyricStatus("暂无歌词", modifier)
        return
    }

    val textSize = with(LocalDensity.current) { AudioLyricSize.toPx() }
    PlayerSubtitleView(cues, modifier.height(120.dp), textSize, AudioSubtitleStyle)
}

@Composable private fun PlayerSubtitleView(
    cues: List<Cue>,
    modifier: Modifier,
    textSizePx: Float? = null,
    style: CaptionStyleCompat? = null,
) {
    val description = remember(cues) { cues.mapNotNull { it.text }.joinToString("\n").takeIf(String::isNotBlank) }
    AndroidView(
        factory = { context ->
            SubtitleView(context).apply {
                if (style != null) {
                    setApplyEmbeddedStyles(false)
                    setStyle(style)
                }
            }
        },
        update = {
            if (textSizePx != null) it.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            it.setCues(cues)
            it.contentDescription = description
            it.importantForAccessibility = if (description == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        },
        modifier = modifier,
    )
}
