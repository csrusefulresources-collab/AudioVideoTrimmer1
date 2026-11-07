package com.example.audiovideotrimmer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Echo for saved files (same maths as [EchoProcessor]). 16-bit PCM only. */
@OptIn(UnstableApi::class)
class EchoAudioProcessor(private val delayMs: Int, level: String) : BaseAudioProcessor() {
    private val gain = when (level) {
        "low" -> 0.25f
        "high" -> 0.65f
        else -> 0.45f
    }
    private var history = ShortArray(1)
    private var pos = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val frames = (inputAudioFormat.sampleRate.toLong() * delayMs.coerceIn(20, 2000) / 1000L).toInt().coerceAtLeast(1)
        history = ShortArray(frames * inputAudioFormat.channelCount)
        pos = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        while (inputBuffer.remaining() >= 2) {
            val x = inputBuffer.getShort().toInt()
            val y = (x + gain * history[pos]).toInt().coerceIn(-32768, 32767)
            history[pos] = y.toShort()
            pos++
            if (pos == history.size) pos = 0
            out.putShort(y.toShort())
        }
        out.flip()
    }
}

/** Fade in at the start and fade out at the end of one item. 16-bit PCM only. */
@OptIn(UnstableApi::class)
class FadeAudioProcessor(
    private val fadeInMs: Int,
    private val fadeOutMs: Int,
    private val totalMs: Long
) : BaseAudioProcessor() {
    private var channels = 2
    private var inFrames = 0L
    private var outFrames = 0L
    private var totalFrames = 0L
    private var done = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate.toLong()
        inFrames = fadeInMs * rate / 1000L
        outFrames = fadeOutMs * rate / 1000L
        totalFrames = totalMs * rate / 1000L
        done = 0L
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        while (inputBuffer.remaining() >= 2 * channels) {
            var g = 1f
            if (inFrames > 0L && done < inFrames) g = done.toFloat() / inFrames
            if (outFrames > 0L && totalFrames > 0L) {
                val left = totalFrames - done
                if (left < outFrames) g = minOf(g, (left.coerceAtLeast(0L)).toFloat() / outFrames)
            }
            for (c in 0 until channels) {
                out.putShort((inputBuffer.getShort() * g).toInt().coerceIn(-32768, 32767).toShort())
            }
            done++
        }
        out.flip()
    }
}

/** Mixes all channels down to one (smaller files). 16-bit PCM only. */
@OptIn(UnstableApi::class)
class MonoAudioProcessor : BaseAudioProcessor() {
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 1, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channels)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += inputBuffer.getShort().toInt()
            out.putShort((sum / channels).toShort())
        }
        out.flip()
    }
}
