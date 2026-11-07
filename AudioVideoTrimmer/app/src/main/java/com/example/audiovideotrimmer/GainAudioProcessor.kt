package com.example.audiovideotrimmer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Multiplies every audio sample by [gain] (volume change). Works with 16-bit and float PCM. */
@OptIn(UnstableApi::class)
class GainAudioProcessor(private val gain: Float) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) {
                out.putFloat((inputBuffer.getFloat() * gain).coerceIn(-1f, 1f))
            }
        } else {
            while (inputBuffer.remaining() >= 2) {
                val v = (inputBuffer.getShort() * gain).toInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        }
        out.flip()
    }
}
