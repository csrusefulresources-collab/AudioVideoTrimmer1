package com.example.audiovideotrimmer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Finds silent parts of an audio track (or of the audio of a video).
 * Decodes to PCM, measures loudness in 20 ms windows and reports quiet runs.
 */
object SilenceDetector {

    /**
     * @return silent ranges in SOURCE milliseconds (already shrunk by [padMs] on both sides so that
     *         speech is never clipped), or null when the file has no audio track.
     */
    fun detect(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        thresholdDb: Float,
        minSilenceMs: Long,
        padMs: Long,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): List<Pair<Long, Long>>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            extractor.selectTrack(track)
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()
            if (fromMs > 0L) extractor.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            var pcmFloat = false
            var windowFrames = (sampleRate / 50).coerceAtLeast(1)

            val fromUs = fromMs * 1000L
            val toUs = toMs * 1000L
            val spanUs = (toUs - fromUs).coerceAtLeast(1L)
            val threshold = 32768.0 * 10.0.pow(thresholdDb / 20.0)

            val ranges = ArrayList<Pair<Long, Long>>()
            var runStartUs = -1L
            var winCount = 0
            var winSum = 0.0
            var winStartUs = 0L
            var lastPercent = -1

            fun closeRun(startUs: Long, endUs: Long) {
                val s = maxOf(startUs / 1000L, fromMs)
                val e = minOf(endUs / 1000L, toMs)
                if (e - s >= minSilenceMs) {
                    val rs = s + padMs
                    val re = e - padMs
                    if (re - rs >= 50L) ranges.add(rs to re)
                }
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var finishedRange = false

            while (!outputDone && !finishedRange) {
                if (isCancelled()) return emptyList()

                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf = dec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        val t = extractor.sampleTime
                        if (size < 0 || t > toUs) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, size, t, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(info, 10_000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                    pcmFloat = Build.VERSION.SDK_INT >= 24 &&
                        of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    windowFrames = (sampleRate / 50).coerceAtLeast(1)
                } else if (outIndex >= 0) {
                    val out = dec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.order(ByteOrder.LITTLE_ENDIAN)
                        val bytesPerSample = if (pcmFloat) 4 else 2
                        val frames = info.size / (bytesPerSample * channels)
                        for (f in 0 until frames) {
                            var sumSq = 0.0
                            for (c in 0 until channels) {
                                val v = if (pcmFloat) out.getFloat() * 32768.0 else out.getShort().toDouble()
                                sumSq += v * v
                            }
                            val frameUs = info.presentationTimeUs + f * 1_000_000L / sampleRate
                            if (frameUs < fromUs) continue
                            if (frameUs > toUs) {
                                finishedRange = true
                                break
                            }
                            if (winCount == 0) winStartUs = frameUs
                            winSum += sumSq / channels
                            winCount++
                            if (winCount >= windowFrames) {
                                val rms = sqrt(winSum / winCount)
                                if (rms < threshold) {
                                    if (runStartUs < 0L) runStartUs = winStartUs
                                } else if (runStartUs >= 0L) {
                                    closeRun(runStartUs, winStartUs)
                                    runStartUs = -1L
                                }
                                winCount = 0
                                winSum = 0.0
                            }
                        }
                        val percent = (((info.presentationTimeUs - fromUs) * 100L) / spanUs)
                            .toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    dec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (runStartUs >= 0L) closeRun(runStartUs, toUs)
            onProgress(100)
            return ranges
        } catch (e: Exception) {
            AppLog.e("Silence", "Silence detection failed", e)
            return emptyList()
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
