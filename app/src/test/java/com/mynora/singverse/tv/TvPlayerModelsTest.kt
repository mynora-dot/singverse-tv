package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class TvPlayerModelsTest {
    @Test fun mediaModeUsesActualStreamsAndIgnoresAttachedCoverArt() {
        val audio = TvPlayerStream("audio", codec = "flac")
        val artwork = TvPlayerStream("video", codec = "mjpeg", attachedPicture = true)
        assertEquals(TvPlayerMode.AUDIO, tvPlayerMode(listOf(audio, artwork), observedVideo = true))
        assertEquals(TvPlayerMode.VIDEO, tvPlayerMode(listOf(audio, TvPlayerStream("video", codec = "h264"))))
        assertEquals(TvPlayerMode.UNKNOWN, tvPlayerMode(listOf(artwork)))
        assertEquals(TvPlayerMode.UNKNOWN, tvPlayerMode(emptyList()))
        assertEquals(TvPlayerMode.VIDEO, tvPlayerMode(emptyList(), observedVideo = true, observedAudio = true))
        assertEquals(TvPlayerMode.AUDIO, tvPlayerMode(emptyList(), observedAudio = true))
    }

    @Test fun audioQualityShowsOnlyReportedValuesWithoutInventingGrades() {
        assertEquals(listOf("FLAC", "44.1 kHz", "2 声道"), tvAudioQualityLabels("flac", TvPlayerStream("audio", "flac", 2, 44100)))
        assertEquals(listOf("MP4", "AAC", "320 kbps", "48 kHz", "6 声道"), tvAudioQualityLabels("mp4", TvPlayerStream("audio", "audio/mp4a-latm", 6, 48000, 320000)))
        assertEquals(listOf("128.125 kbps", "22.05 kHz"), tvAudioQualityLabels(stream = TvPlayerStream("audio", sampleRateHz = 22050, bitrateBps = 128125)))
        assertEquals(emptyList<String>(), tvAudioQualityLabels(stream = TvPlayerStream("audio", "null", -1, -1, -1)))
        assertEquals(emptyList<String>(), tvAudioQualityLabels(stream = TvPlayerStream("video", "h264", 2, 48000, 10000000)))
        assertEquals(listOf("AAC"), tvAudioQualityLabels(stream = TvPlayerStream("audio", "mp4a.40.2")))
        assertEquals(listOf("MP4A.6B"), tvAudioQualityLabels(stream = TvPlayerStream("audio", "mp4a.6b")))
    }

    @Test fun metadataHidesAbsentValuesAndDoesNotInventYearOrStyle() {
        assertEquals(emptyList<Pair<String, String>>(), tvSongMetadataLabels("null", "", listOf("", "null")))
        assertEquals(listOf("专辑" to "真实专辑", "风格" to "民谣 / 流行", "年份" to "2014", "时长" to "4:55"),
            tvSongMetadataLabels(" 真实专辑 ", "2014-09-23", listOf("民谣", "流行", "民谣"), 295000))
        assertEquals(listOf("发行" to "待考证"), tvSongMetadataLabels(releaseDate = "待考证"))
        assertEquals("1:01:01", tvPlayerDuration(3661000))
    }

    private data class Item(val id: String, val status: String, val songId: String = "same-song")

    @Test fun previewMatchesStartWhenWaitingItemWasMovedAboveHandoff() {
        val queue = listOf(Item("current", "PLAYING"), Item("new-top", "WAITING"), Item("handoff", "HANDOFF"), Item("finished", "COMPLETED"))
        assertEquals("handoff", tvNextQueuedItem(queue, Item::status, Item::id, "current")?.id)
        assertEquals("new-top", tvNextQueuedItem(queue, Item::status, Item::id, "handoff")?.id)
        assertNull(tvNextQueuedItem(listOf(Item("current", "HANDOFF")), Item::status, Item::id, "current"))
        // A later queue entry may legitimately repeat the currently singing song.
        assertEquals("repeat", tvNextQueuedItem(listOf(Item("current", "PLAYING"), Item("repeat", "WAITING")), Item::status, Item::id, "current")?.id)
    }
}
