package com.example.audiovideotrimmer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HD audio recorder: AudioRecord → 16-bit PCM WAV (lossless).
 * Optional hardware/OS effects: noise suppression, echo cancellation, automatic gain.
 */
class AudioRecorder(
    private val cfg: Config,
    private val onLevel: (Float) -> Unit
) {
    data class Config(
        val sampleRate: Int,
        val channels: Int,
        val source: String,
        val noise: Boolean,
        val echo: Boolean,
        val gain: Boolean,
        val echoOn: Boolean = false,
        val echoDelayMs: Int = 250,
        val echoLevel: String = "medium"
    )

    @Volatile private var running = false
    @Volatile var paused = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var raf: RandomAccessFile? = null
    private var dataBytes = 0L
    private val effects = mutableListOf<AudioEffect>()
    private var echoFx: EchoProcessor? = null

    /** Things the user should know, e.g. "Echo cancellation is not available on this device." */
    val notes = mutableListOf<String>()
    var sampleRate = cfg.sampleRate
        private set
    var channels = cfg.channels
        private set

    @SuppressLint("MissingPermission")
    fun start(file: File): Boolean {
        val sourceId = when (cfg.source) {
            "voice" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "raw" -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.MIC
        }
        var rec: AudioRecord? = null
        val rateCandidates = listOf(cfg.sampleRate, 48000, 44100).distinct()
        val channelCandidates = if (cfg.channels == 2) listOf(2, 1) else listOf(1)
        loop@ for (rate in rateCandidates) {
            for (ch in channelCandidates) {
                for (src in listOf(sourceId, MediaRecorder.AudioSource.MIC).distinct()) {
                    rec = tryCreate(src, rate, ch)
                    if (rec != null) {
                        sampleRate = rate
                        channels = ch
                        if (src != sourceId) notes.add("The selected microphone mode is not supported. Using the standard microphone.")
                        break@loop
                    }
                }
            }
        }
        if (rec == null) return false
        val r0: AudioRecord = rec
        if (sampleRate != cfg.sampleRate) notes.add("This device cannot record at ${cfg.sampleRate / 1000.0} kHz. Using ${sampleRate / 1000.0} kHz.")
        if (channels != cfg.channels) notes.add("Stereo is not supported on this device. Recording in mono.")
        record = r0

        attachEffects(r0.audioSessionId)

        try {
            val r = RandomAccessFile(file, "rw")
            r.setLength(0)
            r.write(wavHeader(0L))
            raf = r
        } catch (e: Exception) {
            release()
            return false
        }

        try {
            r0.startRecording()
        } catch (e: Exception) {
            release()
            return false
        }
        running = true
        paused = false
        if (cfg.echoOn) {
            echoFx = EchoProcessor(sampleRate, channels, cfg.echoDelayMs, cfg.echoLevel)
            notes.add("Echo effect is on: ${cfg.echoDelayMs} milliseconds, ${cfg.echoLevel} strength.")
        }
        val chunk = (sampleRate * channels * 2 / 20).coerceAtLeast(1024) // 50 ms
        thread = Thread({
            val buf = ByteArray(chunk)
            while (running) {
                val n = r0.read(buf, 0, buf.size)
                if (n > 0) {
                    onLevel(peak(buf, n))
                    if (!paused) {
                        try {
                            echoFx?.process(buf, n)
                            raf?.write(buf, 0, n)
                            dataBytes += n
                        } catch (_: Exception) {
                            running = false
                        }
                    }
                } else if (n < 0) {
                    running = false
                }
            }
        }, "AudioRecorder").also { it.start() }
        return true
    }

    /** Stops recording and finalizes the WAV header. Returns true when audio was captured. */
    fun stop(): Boolean {
        running = false
        try {
            thread?.join(2000L)
        } catch (_: InterruptedException) {
        }
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            raf?.let {
                it.seek(0L)
                it.write(wavHeader(dataBytes))
                it.close()
            }
        } catch (_: Exception) {
        }
        raf = null
        release()
        return dataBytes > 0L
    }

    private fun release() {
        effects.forEach {
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        effects.clear()
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
    }

    @SuppressLint("MissingPermission")
    private fun tryCreate(source: Int, rate: Int, ch: Int): AudioRecord? {
        return try {
            val channelConfig = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val min = AudioRecord.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val size = maxOf(min * 2, rate * ch * 2 / 5)
            val r = AudioRecord(source, rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, size)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                null
            } else r
        } catch (e: Exception) {
            null
        }
    }

    private fun attachEffects(session: Int) {
        if (cfg.noise) {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(session)?.let { it.enabled = true; effects.add(it) }
            } else notes.add("Noise reduction is not available on this device.")
        }
        if (cfg.echo) {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(session)?.let { it.enabled = true; effects.add(it) }
            } else notes.add("Echo cancellation is not available on this device.")
        }
        if (cfg.gain) {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(session)?.let { it.enabled = true; effects.add(it) }
            } else notes.add("Automatic gain is not available on this device.")
        }
    }

    private fun peak(buf: ByteArray, n: Int): Float {
        var max = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > max) max = a
            i += 2
        }
        return (max / 32768f).coerceIn(0f, 1f)
    }

    private fun wavHeader(dataLen: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt((36L + dataLen).toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * 2).toShort())
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataLen.toInt())
        return bb.array()
    }
}
