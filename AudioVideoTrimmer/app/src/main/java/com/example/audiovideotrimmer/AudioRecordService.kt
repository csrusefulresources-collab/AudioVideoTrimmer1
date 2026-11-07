package com.example.audiovideotrimmer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.sqrt

/** Records HD audio even when the screen is off or another app is in front. */
class AudioRecordService : BaseRecordService() {

    override val kind = RecordKind.AUDIO
    override val titleText = "Audio recording"

    private var recorder: AudioRecorder? = null
    private var file: File? = null
    @Volatile private var level = 0f

    override fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    override fun prepare(intent: Intent): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun beginCapture(): Boolean {
        val cfg = AudioRecorder.Config(
            sampleRate = Prefs.audioSampleRate,
            channels = Prefs.audioChannels,
            source = Prefs.audioSource,
            noise = Prefs.noiseSuppression,
            echo = Prefs.echoCancel,
            gain = Prefs.autoGain,
            echoOn = Prefs.echoEffect,
            echoDelayMs = Prefs.echoDelayMs,
            echoLevel = Prefs.echoLevel
        )
        val out = RecordingStore.newFile(this, "audio", "wav")
        val rec = AudioRecorder(cfg) { peak -> level = sqrt(peak) }
        if (!rec.start(out)) {
            out.delete()
            AppLog.e("Audio", "The microphone could not be started")
            return false
        }
        recorder = rec
        file = out
        rec.notes.forEach {
            AppLog.w("Audio", it)
            RecordState.post(it)
        }
        AppLog.i("Audio", "Recording started: ${rec.sampleRate} Hz, ${rec.channels} ch")
        return true
    }

    override fun pauseCapture() {
        recorder?.paused = true
    }

    override fun resumeCapture() {
        recorder?.paused = false
    }

    override fun endCapture(): File? {
        val rec = recorder ?: return null
        recorder = null
        val ok = rec.stop()
        val f = file
        file = null
        if (!ok) {
            f?.delete()
            return null
        }
        return f
    }

    override fun currentLevel(): Float = level
}
