package com.example.audiovideotrimmer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Shared logic of the audio and screen recording services:
 * foreground notification with Pause / Resume / Stop actions, countdown,
 * floating controls, elapsed time, auto-save.
 */
abstract class BaseRecordService : Service() {

    companion object {
        const val ACTION_START = "com.example.audiovideotrimmer.START"
        const val ACTION_PAUSE = "com.example.audiovideotrimmer.PAUSE"
        const val ACTION_RESUME = "com.example.audiovideotrimmer.RESUME"
        const val ACTION_STOP = "com.example.audiovideotrimmer.STOP"
        const val EXTRA_COUNTDOWN = "countdown"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 4201
    }

    protected val handler = Handler(Looper.getMainLooper())
    private var overlay: FloatingControls? = null
    private var segmentStart = 0L
    private var accumulatedMs = 0L
    private var countdownLeft = 0
    private var finishing = false

    protected abstract val kind: RecordKind
    protected abstract val titleText: String
    protected abstract fun foregroundType(): Int
    /** Called right after the service went to the foreground. Return false to abort. */
    protected abstract fun prepare(intent: Intent): Boolean
    protected abstract fun beginCapture(): Boolean
    protected abstract fun pauseCapture()
    protected abstract fun resumeCapture()
    /** Stops capturing and returns the finished file (or null). Must be safe to call at any time. */
    protected abstract fun endCapture(): File?
    protected open fun currentLevel(): Float = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopRecording()
            else -> if (!RecordState.isBusy) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ flow

    private fun handleStart(intent: Intent) {
        if (RecordState.isBusy) return
        finishing = false
        AppLog.i("Record", "Start requested: $kind")
        createChannel()
        val countdown = intent.getIntExtra(EXTRA_COUNTDOWN, 0).coerceIn(0, 10)
        publish(if (countdown > 0) Phase.COUNTDOWN else Phase.RECORDING, 0L, countdown)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundType())
        } catch (e: Exception) {
            AppLog.e("Record", "Foreground service start failed ($kind)", e)
            fail("Could not start recording: the system did not allow the recording service.")
            return
        }
        if (!prepare(intent)) {
            fail("Could not start recording.")
            return
        }
        if (Prefs.floatingControls && Settings.canDrawOverlays(this)) {
            overlay = FloatingControls(this, { toggle() }, { stopRecording() }).also { it.show() }
        }
        if (countdown > 0) runCountdown(countdown) else startNow()
    }

    private fun runCountdown(seconds: Int) {
        countdownLeft = seconds
        val tick = object : Runnable {
            override fun run() {
                if (RecordState.state.value.phase != Phase.COUNTDOWN) return
                if (countdownLeft <= 0) {
                    startNow()
                    return
                }
                publish(Phase.COUNTDOWN, 0L, countdownLeft)
                Feedback.cue(this@BaseRecordService)
                countdownLeft--
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(tick)
    }

    private fun startNow() {
        Feedback.cue(this, high = true)
        handler.postDelayed({
            if (finishing) return@postDelayed
            if (!beginCapture()) {
                fail("Could not start capturing. Is another app using the microphone?")
                return@postDelayed
            }
            segmentStart = SystemClock.elapsedRealtime()
            accumulatedMs = 0L
            publish(Phase.RECORDING, 0L, 0)
            updateNotification()
            handler.post(ticker)
        }, Feedback.settleDelayMs())
    }

    private val ticker = object : Runnable {
        override fun run() {
            val phase = RecordState.state.value.phase
            if (phase == Phase.RECORDING || phase == Phase.PAUSED) {
                val elapsed = elapsedNow()
                RecordState.state.value = RecordState.state.value.copy(
                    elapsedMs = elapsed,
                    level = if (phase == Phase.RECORDING) currentLevel() else 0f
                )
                overlay?.update(phase, elapsed)
                handler.postDelayed(this, 250L)
            }
        }
    }

    private fun elapsedNow(): Long {
        val running = RecordState.state.value.phase == Phase.RECORDING
        return accumulatedMs + if (running) SystemClock.elapsedRealtime() - segmentStart else 0L
    }

    private fun toggle() {
        if (RecordState.state.value.phase == Phase.PAUSED) resume() else pause()
    }

    private fun pause() {
        if (RecordState.state.value.phase != Phase.RECORDING) return
        pauseCapture()
        accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
        publish(Phase.PAUSED, accumulatedMs, 0)
        overlay?.update(Phase.PAUSED, accumulatedMs)
        Feedback.cue(this)
        updateNotification()
    }

    private fun resume() {
        if (RecordState.state.value.phase != Phase.PAUSED) return
        Feedback.cue(this, high = true)
        handler.postDelayed({
            if (finishing) return@postDelayed
            resumeCapture()
            segmentStart = SystemClock.elapsedRealtime()
            publish(Phase.RECORDING, accumulatedMs, 0)
            overlay?.update(Phase.RECORDING, accumulatedMs)
            updateNotification()
            handler.post(ticker)
        }, Feedback.settleDelayMs())
    }

    protected fun stopRecording() {
        if (finishing) return
        if (!RecordState.isBusy) {
            stopSelf()
            return
        }
        finishing = true
        AppLog.i("Record", "Stop requested: $kind")
        handler.removeCallbacksAndMessages(null)
        val file = try {
            endCapture()
        } catch (e: Exception) {
            AppLog.e("Record", "Stopping the recording failed ($kind)", e)
            null
        }
        overlay?.hide()
        overlay = null
        Feedback.cue(this, high = true)
        RecordState.state.value = RecState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        if (file != null && file.exists() && file.length() > 0L) {
            RecordState.post("Recording stopped. Saved as ${file.name}.")
            if (Prefs.autoSaveToPhone) {
                val app = applicationContext
                Thread {
                    val where = try {
                        RecordingStore.saveToPhone(app, file)
                    } catch (_: Exception) {
                        null
                    }
                    RecordState.post(
                        if (where != null) "A copy was saved in $where." else "Could not save a copy to the phone storage."
                    )
                    handler.post { stopSelf() }
                }.start()
                return
            }
        } else {
            RecordState.post("Recording stopped, but nothing was recorded.")
        }
        stopSelf()
    }

    private fun fail(message: String) {
        AppLog.e("Record", "Recording failed ($kind): $message")
        finishing = true
        handler.removeCallbacksAndMessages(null)
        try {
            endCapture()
        } catch (_: Exception) {
        }
        overlay?.hide()
        overlay = null
        RecordState.state.value = RecState()
        RecordState.post(message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!finishing && RecordState.isBusy) {
            try {
                endCapture()
            } catch (_: Exception) {
            }
            RecordState.state.value = RecState()
        }
        overlay?.hide()
        overlay = null
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------- notification

    private fun publish(phase: Phase, elapsed: Long, countdown: Int) {
        RecordState.state.value = RecState(kind, phase, elapsed, countdown, 0f)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, this::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val phase = RecordState.state.value.phase
        val statusText = when (phase) {
            Phase.COUNTDOWN -> "Starting soon"
            Phase.PAUSED -> "Paused"
            Phase.RECORDING -> "Recording"
            Phase.IDLE -> ""
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(titleText)
            .setContentText(statusText)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(ContextCompat.getColor(this, R.color.primary))
        if (phase == Phase.RECORDING) {
            builder.setUsesChronometer(true)
            builder.setWhen(System.currentTimeMillis() - elapsedNow())
        }
        if (phase == Phase.RECORDING) {
            builder.addAction(R.drawable.ic_pause, "Pause", actionIntent(ACTION_PAUSE, 1))
        } else if (phase == Phase.PAUSED) {
            builder.addAction(R.drawable.ic_play, "Resume", actionIntent(ACTION_RESUME, 2))
        }
        if (phase != Phase.IDLE) {
            builder.addAction(R.drawable.ic_stop, "Stop and save", actionIntent(ACTION_STOP, 3))
        }
        return builder.build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }
}
