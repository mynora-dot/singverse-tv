@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.tv
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
class KaraokeAudioProcessorTest {
    private fun input(vararg values: Short)=ByteBuffer.allocateDirect(values.size*2).order(ByteOrder.nativeOrder()).apply { values.forEach { putShort(it) };flip() }
    @Test fun channelSelectionEmitsEachFrameImmediately() {
        val processor = KaraokeAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(1000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())

        val expectedFrames = mapOf(
            "STEREO" to (100 to -200),
            "LEFT" to (100 to 100),
            "RIGHT" to (-200 to -200),
            "MONO" to (-50 to -50),
        )
        for ((channel, expected) in expectedFrames) {
            processor.channelMode = channel
            processor.queueInput(input(100, -200))
            val frame = processor.output
            assertEquals(expected.first.toShort(), frame.short)
            assertEquals(expected.second.toShort(), frame.short)
            assertFalse(frame.hasRemaining())
        }
        processor.queueEndOfStream()
        assertFalse(processor.output.hasRemaining())
    }
}
