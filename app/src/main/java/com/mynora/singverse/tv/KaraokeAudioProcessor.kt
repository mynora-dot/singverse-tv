@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Device-side PCM channel selection; source files remain unchanged. */
class KaraokeAudioProcessor : BaseAudioProcessor() {
    @Volatile var channelMode = "STEREO"
    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(input)
        return input
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if(!inputBuffer.hasRemaining())return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (inputAudioFormat.channelCount == 2 && channelMode != "STEREO") {
            while (inputBuffer.remaining() >= 4) {
                val left = inputBuffer.short; val right = inputBuffer.short
                val sample = when (channelMode) { "LEFT" -> left; "RIGHT" -> right; else -> ((left.toInt() + right.toInt()) / 2).toShort() }
                output.putShort(sample); output.putShort(sample)
            }
        } else output.put(inputBuffer)
        output.flip()
    }
}
