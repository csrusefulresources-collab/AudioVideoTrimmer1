package com.example.audiovideotrimmer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.File

/** Screen recording with MediaProjection + MediaRecorder (H.264 / AAC, MP4). */
class ScreenRecordService : BaseRecordService() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    override val kind = RecordKind.SCREEN
    override val titleText = "Screen recording"

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var file: File? = null
    private var withMic = false
    private var stopped = false

    private fun micAllowed() =
        Prefs.screenMic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun foregroundType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30 && micAllowed()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    override fun prepare(intent: Intent): Boolean {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java) ?: return false
        withMic = micAllowed()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: return false
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The system (or the user via the status bar chip) ended the capture.
                handler.post { if (!stopped) stopRecording() }
            }
        }, handler)
        projection = p
        stopped = false
        return true
    }

    override fun beginCapture(): Boolean {
        val p = projection ?: return false
        val (fullW, fullH, dpi) = screenSize()
        return try {
            start(p, fullW, fullH, dpi, Prefs.screenScale)
        } catch (e: Exception) {
            AppLog.w("Screen", "Recording at the selected size failed, retrying with 720p", e)
            cleanupRecorder()
            // Retry once with a safe size – some devices cannot encode their native resolution.
            try {
                start(p, fullW, fullH, dpi, "720")
            } catch (e2: Exception) {
                AppLog.e("Screen", "Screen recorder could not start", e2)
                cleanupRecorder()
                false
            }
        }
    }

    private fun start(p: MediaProjection, fullW: Int, fullH: Int, dpi: Int, scale: String): Boolean {
        val shortSide = minOf(fullW, fullH).toFloat()
        val target = when (scale) {
            "1080" -> 1080f
            "720" -> 720f
            else -> shortSide
        }
        val factor = if (target < shortSide) target / shortSide else 1f
        val w = ((fullW * factor).toInt() / 2) * 2
        val h = ((fullH * factor).toInt() / 2) * 2

        val out = RecordingStore.newFile(this, "screen", "mp4")
        file = out
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        if (withMic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (withMic) {
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(192_000)
            rec.setAudioSamplingRate(48_000)
            rec.setAudioChannels(2)
        }
        rec.setVideoSize(w, h)
        rec.setVideoFrameRate(Prefs.screenFps)
        rec.setVideoEncodingBitRate(Prefs.screenBitrateMbps * 1_000_000)
        rec.setOutputFile(out.absolutePath)
        rec.prepare()
        recorder = rec

        display = p.createVirtualDisplay(
            "AudioVideoTrimmer",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface, null, handler
        )
        rec.start()
        return true
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dpi)
        }
    }

    override fun pauseCapture() {
        try {
            recorder?.pause()
        } catch (_: Exception) {
        }
    }

    override fun resumeCapture() {
        try {
            recorder?.resume()
        } catch (_: Exception) {
        }
    }

    override fun endCapture(): File? {
        stopped = true
        var ok = false
        try {
            recorder?.stop()
            ok = true
        } catch (_: Exception) {
            // stop() throws when the recording was too short
        }
        cleanupRecorder()
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        val f = file
        file = null
        if (!ok || f == null || !f.exists() || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f
    }

    private fun cleanupRecorder() {
        try {
            display?.release()
        } catch (_: Exception) {
        }
        display = null
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }
}
