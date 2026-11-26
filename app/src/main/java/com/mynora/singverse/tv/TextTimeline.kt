package com.mynora.singverse.tv

internal sealed interface TvLyrics {
    data object Loading : TvLyrics
    data object Embedded : TvLyrics
    data class Timed(val lines: List<TextLine>) : TvLyrics
    data class Plain(val text: String) : TvLyrics
}

internal fun selectTvLyrics(tracks: Sequence<Pair<String, String>>): TvLyrics {
    var plain: TvLyrics.Plain? = null
    for ((format, raw) in tracks) {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) continue
        when (val type = format.trim().lowercase()) {
            "text" -> if (plain == null) plain = TvLyrics.Plain(text)
            "lrc", "srt", "vtt" -> {
                val lines = TextTimeline.parse(type, text)
                if (lines.any { it.text.isNotBlank() }) return TvLyrics.Timed(lines)
            }
        }
    }
    return plain ?: TvLyrics.Embedded
}

internal data class TextLine(val start: Long, val end: Long, val text: String)
internal data class LyricPreview(val current: String?, val upcoming: List<String>)

internal object TextTimeline {
    private val stamp = Regex("(?:(\\d{1,3}):)?(\\d{1,3}):(\\d{2})(?:[.,](\\d{1,3}))?")
    private val offsetStamp = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
    private val lineStamp = Regex("\\[(\\d{1,3}:\\d{2}(?:[.,]\\d{1,3})?)\\]")
    private val markup = Regex("<[^<>]*>")
    private val blockSeparator = Regex("\n[^\\S\n]*\n")

    private fun timestamp(raw: String): Long? {
        val (_, hours, minutes, seconds, fraction) = stamp.matchEntire(raw)?.groupValues ?: return null
        val second = seconds.toLong()
        val minute = minutes.toLong()
        if (second >= 60 || (hours.isNotEmpty() && minute >= 60)) return null
        val millis = fraction.padEnd(3, '0').toLong()
        return (hours.toLongOrNull() ?: 0) * 3_600_000 + minute * 60_000 + second * 1_000 + millis
    }

    fun parse(format: String, raw: String): List<TextLine> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        return if (format == "lrc") parseLrc(text) else parseSubtitles(text)
    }

    private fun parseLrc(text: String): List<TextLine> {
        val offset = offsetStamp.find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-600_000, 600_000) ?: 0
        val lines = mutableListOf<TextLine>()
        for (line in text.lineSequence()) {
            val stamps = lineStamp.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val content = line.substring(stamps.last().range.last + 1).replace(markup, "").trim()
            for (match in stamps) {
                val time = timestamp(match.groupValues[1]) ?: continue
                lines += TextLine((time + offset).coerceAtLeast(0), Long.MAX_VALUE, content)
            }
        }
        lines.sortBy { it.start }
        return lines.mapIndexed { index, line -> line.copy(end = lines.getOrNull(index + 1)?.start ?: Long.MAX_VALUE) }
    }

    private fun parseSubtitles(text: String): List<TextLine> = text.split(blockSeparator).mapNotNull { block ->
        val lines = block.lines()
        val index = lines.indexOfFirst { "-->" in it }
        if (index < 0) return@mapNotNull null
        val timing = lines[index]
        val start = timestamp(timing.substringBefore("-->").trim()) ?: return@mapNotNull null
        val end = timestamp(timing.substringAfter("-->").trimStart().takeWhile { !it.isWhitespace() }) ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        TextLine(start, end, lines.drop(index + 1).joinToString("\n").replace(markup, "").trim())
    }.sortedBy { it.start }

    fun at(lines: List<TextLine>, position: Long): TextLine? = lines.lastOrNull { position >= it.start && position < it.end }

    fun preview(lines: List<TextLine>, position: Long): LyricPreview {
        val current = at(lines, position)?.text?.takeIf(String::isNotBlank)
        val upcoming = lines.asSequence()
            .filter { it.start > position && it.end > it.start && it.text.isNotBlank() }
            .take(2)
            .map(TextLine::text)
            .toList()
        return LyricPreview(current, upcoming)
    }
}
