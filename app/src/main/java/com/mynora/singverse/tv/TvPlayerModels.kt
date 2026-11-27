package com.mynora.singverse.tv

import java.math.BigDecimal
import java.util.Locale

internal enum class TvPlayerMode { VIDEO, AUDIO, UNKNOWN }

/** Source streams or the selected Media3 audio format, with unknown values left at zero. */
internal data class TvPlayerStream(
    val kind: String,
    val codec: String = "",
    val channels: Int = 0,
    val sampleRateHz: Int = 0,
    val bitrateBps: Long = 0,
    val attachedPicture: Boolean = false,
)

/**
 * [streams] must describe the complete media, from response.media / execution.media.
 * audioRoles.streams is an audio-only list and cannot prove the absence of video.
 * A known probe takes precedence over observed tracks, so attached cover art is not an MV.
 * Without a probe, pass actual Media3 track types, including unsupported video tracks.
 */
internal fun tvPlayerMode(
    streams: List<TvPlayerStream>,
    observedVideo: Boolean = false,
    observedAudio: Boolean = false,
): TvPlayerMode {
    val content = streams.filterNot { it.attachedPicture }
    return when {
        content.any { it.kind.equals("video", ignoreCase = true) } -> TvPlayerMode.VIDEO
        content.any { it.kind.equals("audio", ignoreCase = true) } -> TvPlayerMode.AUDIO
        observedVideo -> TvPlayerMode.VIDEO
        observedAudio -> TvPlayerMode.AUDIO
        else -> TvPlayerMode.UNKNOWN
    }
}

private fun String.realPlayerValue(): String = trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()

private fun codecLabel(value: String): String = when (val codec = value.realPlayerValue().lowercase(Locale.ROOT)) {
    "" -> ""
    "audio/flac", "flac" -> "FLAC"
    "audio/alac", "alac" -> "ALAC"
    "audio/mp4a-latm", "aac" -> "AAC"
    "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> "MPEG Audio"
    "mp3" -> "MP3"
    "mp2" -> "MP2"
    "audio/opus", "opus" -> "Opus"
    "audio/vorbis", "vorbis" -> "Vorbis"
    "audio/ac3", "ac3" -> "AC-3"
    "audio/eac3", "eac3" -> "E-AC-3"
    "audio/vnd.dts", "dts" -> "DTS"
    "audio/raw" -> "PCM"
    else -> if (codec in listOf("mp4a.40.2", "mp4a.40.5", "mp4a.40.29")) "AAC" else value.realPlayerValue().uppercase(Locale.ROOT)
}

private fun scaledPlayerNumber(value: Long, divisor: Long): String =
    BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor)).stripTrailingZeros().toPlainString()

/** Actual values only: no lossless/HQ grade is inferred from a bitrate, extension or cover. */
internal fun tvAudioQualityLabels(container: String = "", stream: TvPlayerStream? = null): List<String> {
    val labels = mutableListOf<String>()
    container.realPlayerValue().takeIf { it.isNotBlank() }?.let { labels += it.uppercase(Locale.ROOT) }
    if (stream != null && stream.kind.equals("audio", ignoreCase = true) && !stream.attachedPicture) {
        codecLabel(stream.codec).takeIf { it.isNotBlank() }?.let { labels += it }
        if (stream.bitrateBps > 0) labels += "${scaledPlayerNumber(stream.bitrateBps, 1000)} kbps"
        if (stream.sampleRateHz > 0) labels += "${scaledPlayerNumber(stream.sampleRateHz.toLong(), 1000)} kHz"
        if (stream.channels > 0) labels += "${stream.channels} 声道"
    }
    return labels.distinctBy { it.lowercase(Locale.ROOT) }
}

/** Mirrors START: a handed-off item precedes waiting items, including after queue reordering. */
internal fun <T> tvNextQueuedItem(
    queue: List<T>,
    status: (T) -> String,
    id: (T) -> String,
    currentItemId: String = "",
): T? {
    fun eligible(item: T) = currentItemId.isBlank() || id(item) != currentItemId
    return queue.firstOrNull { eligible(it) && status(it) == "HANDOFF" }
        ?: queue.firstOrNull { eligible(it) && status(it) == "WAITING" }
}
