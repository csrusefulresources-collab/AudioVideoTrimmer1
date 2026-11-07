package com.example.audiovideotrimmer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Beep + vibration cues, switchable in Settings → Accessibility. */
object Feedback {
    private val handler = Handler(Looper.getMainLooper())

    fun cue(context: Context, high: Boolean = false) {
        if (Prefs.beeps) beep(high)
        if (Prefs.haptics) vibrate(context)
    }

    private fun beep(high: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(if (high) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 140)
            handler.postDelayed({ tg.release() }, 500L)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    /** Delay to wait after a cue before capturing starts, so the beep is not recorded. */
    fun settleDelayMs(): Long = if (Prefs.beeps) 350L else 0L
}
