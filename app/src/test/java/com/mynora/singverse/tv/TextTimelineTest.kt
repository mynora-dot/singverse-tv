package com.mynora.singverse.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextTimelineTest {
    @Test fun lrcUsesOffsetsRepeatedStampsAndBlankCues() {
        val lyrics = selectTvLyrics(sequenceOf(" LRC " to
            "[offset:100]\r\n[00:01.20]<00:01.20>你<00:01.50>好\r\n[00:02][00:03]重复\r\n[00:04]")) as TvLyrics.Timed

        assertNull(TextTimeline.at(lyrics.lines, 1200))
        assertEquals("你好", TextTimeline.at(lyrics.lines, 1300)?.text)
        assertEquals("重复", TextTimeline.at(lyrics.lines, 2100)?.text)
        assertEquals("重复", TextTimeline.at(lyrics.lines, 3100)?.text)
        assertEquals("", TextTimeline.at(lyrics.lines, 4100)?.text)
    }

    @Test fun timedTrackTakesPriorityOverPlainTextAndKeepsSubtitleGaps() {
        val lyrics = selectTvLyrics(sequenceOf(
            "text" to "纯文本",
            "lrc" to "[00:99]无效时间",
            "srt" to "1\n00:00:01,000 --> 00:00:02,500\n字幕",
        )) as TvLyrics.Timed

        assertEquals("字幕", TextTimeline.at(lyrics.lines, 1500)?.text)
        assertNull(TextTimeline.at(lyrics.lines, 2500))
    }

    @Test fun lyricPreviewUsesTwoRealUpcomingLinesAcrossIntroAndInterludes() {
        val lines = TextTimeline.parse("lrc", "[00:02]第一句\n[00:04]\n[00:06]第二句\n[00:08]第三句\n[00:10]第四句")

        assertEquals(LyricPreview(null, listOf("第一句", "第二句")), TextTimeline.preview(lines, 1999))
        assertEquals(LyricPreview("第一句", listOf("第二句", "第三句")), TextTimeline.preview(lines, 2000))
        assertEquals(LyricPreview(null, listOf("第二句", "第三句")), TextTimeline.preview(lines, 4000))
        assertEquals(LyricPreview("第二句", listOf("第三句", "第四句")), TextTimeline.preview(lines, 6000))
        assertEquals(LyricPreview("第四句", emptyList()), TextTimeline.preview(lines, 10000))
    }

    @Test fun vttParsesTimingAndCueSettings() {
        val lyrics = selectTvLyrics(sequenceOf("vtt" to
            "WEBVTT\n\n00:01.000 --> 00:02.000 align:center\n<i>字幕</i>")) as TvLyrics.Timed

        assertEquals(TextLine(1000, 2000, "字幕"), lyrics.lines.single())
    }

    @Test fun plainTextStillTakesPriorityOverEmbeddedSubtitles() {
        val lyrics = selectTvLyrics(sequenceOf("lrc" to "[00:02]", "text" to "\uFEFF未同步正文"))

        assertEquals(TvLyrics.Plain("未同步正文"), lyrics)
    }

    @Test fun missingOrUnusableLyricsFallBackToEmbeddedSubtitles() {
        assertEquals(TvLyrics.Embedded, selectTvLyrics(emptySequence()))
        assertEquals(TvLyrics.Embedded, selectTvLyrics(sequenceOf(
            "text" to "\uFEFF\n ", "lrc" to "[00:02]<i></i>", "vtt" to "缺少时间轴", "unknown" to "无效格式",
        )))
    }
}
