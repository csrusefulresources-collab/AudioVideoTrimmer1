package com.example.audiovideotrimmer

/**
 * Simple feedback echo for 16-bit little-endian PCM (interleaved):
 *   out[n] = in[n] + level * out[n - delay]
 * Used while recording, so the echo is part of the saved file.
 */
class EchoProcessor(sampleRate: Int, private val channels: Int, delayMs: Int, level: String) {
    private val gain = when (level) {
        "low" -> 0.25f
        "high" -> 0.65f
        else -> 0.45f
    }
    private val size = (sampleRate.toLong() * delayMs.coerceIn(20, 2000) / 1000L).toInt().coerceAtLeast(1) * channels
    private val history = ShortArray(size)
    private var pos = 0

    fun process(data: ByteArray, length: Int) {
        var i = 0
        while (i + 1 < length) {
            val x = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            val y = (x + gain * history[pos]).toInt().coerceIn(-32768, 32767)
            history[pos] = y.toShort()
            pos++
            if (pos == size) pos = 0
            data[i] = (y and 0xFF).toByte()
            data[i + 1] = ((y shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}
