package com.example.audiovideotrimmer

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Builds settings rows in code so the Record tab (quick settings) and the Settings tab
 * show exactly the same controls, always in sync with [Prefs].
 *
 * Every row has a clear spoken label: "<title>, <current value>".
 */
object SettingsUi {

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun rowParams(ctx: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(ctx, 6) }

    fun heading(ctx: Context, parent: ViewGroup, text: String) {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextAppearance(R.style.TextAppearance_App_Heading)
        ViewCompat.setAccessibilityHeading(tv, true)
        val lp = rowParams(ctx)
        lp.topMargin = dp(ctx, 16)
        parent.addView(tv, lp)
    }

    fun note(ctx: Context, parent: ViewGroup, text: String) {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        parent.addView(tv, rowParams(ctx))
    }

    fun addSwitch(
        ctx: Context,
        parent: ViewGroup,
        title: String,
        summary: String,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        onChanged: () -> Unit = {}
    ) {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val sw = MaterialSwitch(ctx)
        sw.text = title
        sw.minHeight = dp(ctx, 56)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked ->
            set(checked)
            onChanged()
        }
        box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (summary.isNotEmpty()) {
            val tv = TextView(ctx)
            tv.text = summary
            tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            tv.setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
            box.addView(tv)
        }
        parent.addView(box, rowParams(ctx))
    }

    fun <T> addChoice(
        ctx: Context,
        parent: ViewGroup,
        title: String,
        labels: List<String>,
        values: List<T>,
        get: () -> T,
        set: (T) -> Unit,
        onChanged: () -> Unit = {}
    ) {
        val btn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.minHeight = dp(ctx, 56)
        btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        btn.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        btn.isAllCaps = false

        fun currentLabel(): String {
            val idx = values.indexOf(get())
            return labels.getOrElse(idx) { labels.first() }
        }

        fun refresh() {
            btn.text = "$title: ${currentLabel()}"
            btn.contentDescription = SpeechText.clean("$title, ${currentLabel()}. Double tap to change.")
        }
        refresh()
        btn.setOnClickListener {
            val checked = values.indexOf(get()).coerceAtLeast(0)
            MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                    set(values[which])
                    refresh()
                    onChanged()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        parent.addView(btn, rowParams(ctx))
    }

    fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
        }
    }

    // ------------------------------------------------------------ sections

    fun audioRecording(ctx: Context, p: ViewGroup, advanced: Boolean, changed: () -> Unit) {
        addChoice(ctx, p, "Sample rate",
            listOf("48 kHz (HD, recommended)", "44.1 kHz (CD)"), listOf(48000, 44100),
            { Prefs.audioSampleRate }, { Prefs.audioSampleRate = it }, changed)
        addChoice(ctx, p, "Channels",
            listOf("Stereo (2 channels)", "Mono (1 channel, smaller file)"), listOf(2, 1),
            { Prefs.audioChannels }, { Prefs.audioChannels = it }, changed)
        addSwitch(ctx, p, "Noise reduction",
            "Reduces steady background noise such as fans and traffic.",
            { Prefs.noiseSuppression }, { Prefs.noiseSuppression = it }, changed)
        addSwitch(ctx, p, "Echo cancellation",
            "Removes echo when sound from the speaker is picked up by the microphone.",
            { Prefs.echoCancel }, { Prefs.echoCancel = it }, changed)
        addSwitch(ctx, p, "Automatic gain",
            "Raises quiet voices and lowers loud ones. Turn off for the most natural sound.",
            { Prefs.autoGain }, { Prefs.autoGain = it }, changed)
        addSwitch(ctx, p, "Echo effect (adds an echo to your voice)",
            "Different from echo cancellation. The echo is recorded into the file.",
            { Prefs.echoEffect }, { Prefs.echoEffect = it }, changed)
        addChoice(ctx, p, "Echo strength",
            listOf("Low echo", "Medium echo", "High echo"), listOf("low", "medium", "high"),
            { Prefs.echoLevel }, { Prefs.echoLevel = it }, changed)
        addNumber(ctx, p, "Echo delay", "milliseconds", 20, 2000,
            { Prefs.echoDelayMs }, { Prefs.echoDelayMs = it }, changed)
        if (advanced) {
            addChoice(ctx, p, "Microphone mode",
                listOf("Standard", "Voice (clean, little processing)", "Raw studio (unprocessed, if supported)"),
                listOf("mic", "voice", "raw"),
                { Prefs.audioSource }, { Prefs.audioSource = it }, changed)
        }
    }

    fun videoRecording(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addChoice(ctx, p, "Video quality",
            listOf("4K Ultra HD (if supported)", "Full HD 1080p", "HD 720p", "Standard 480p"),
            listOf("uhd", "fhd", "hd", "sd"),
            { Prefs.videoQuality }, { Prefs.videoQuality = it }, changed)
        addChoice(ctx, p, "Camera",
            listOf("Back camera", "Front camera"), listOf("back", "front"),
            { Prefs.videoCamera }, { Prefs.videoCamera = it }, changed)
        addSwitch(ctx, p, "Record sound with the video",
            "Uses the microphone while recording the video.",
            { Prefs.videoAudio }, { Prefs.videoAudio = it }, changed)
    }

    fun screenRecording(ctx: Context, p: ViewGroup, advanced: Boolean, changed: () -> Unit) {
        addChoice(ctx, p, "Screen resolution",
            listOf("Full screen (native)", "1080p", "720p (smaller file)"),
            listOf("native", "1080", "720"),
            { Prefs.screenScale }, { Prefs.screenScale = it }, changed)
        addChoice(ctx, p, "Frame rate",
            listOf("30 frames per second", "60 frames per second (smoother)"), listOf(30, 60),
            { Prefs.screenFps }, { Prefs.screenFps = it }, changed)
        if (advanced) {
            addChoice(ctx, p, "Video bitrate",
                listOf("4 Mbps (small)", "8 Mbps (balanced)", "12 Mbps (high)", "20 Mbps (very high)"),
                listOf(4, 8, 12, 20),
                { Prefs.screenBitrateMbps }, { Prefs.screenBitrateMbps = it }, changed)
        }
        addSwitch(ctx, p, "Record microphone",
            "Records your voice together with the screen. Sound played by other apps is not recorded.",
            { Prefs.screenMic }, { Prefs.screenMic = it }, changed)
        addSwitch(ctx, p, "Floating control buttons",
            "Shows a small panel above other apps to pause, resume and stop. It also appears in the recording; you can trim it later.",
            { Prefs.floatingControls }, { Prefs.floatingControls = it }, changed)
    }

    fun commonRecording(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addChoice(ctx, p, "Countdown before recording",
            listOf("None", "3 seconds", "5 seconds", "10 seconds"), listOf(0, 3, 5, 10),
            { Prefs.countdownSeconds }, { Prefs.countdownSeconds = it }, changed)
        addSwitch(ctx, p, "Save a copy to the phone automatically",
            "Copies each finished recording to Music or Movies, in the folder AudioVideoTrimmer.",
            { Prefs.autoSaveToPhone }, { Prefs.autoSaveToPhone = it }, changed)
    }

    fun silence(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addChoice(ctx, p, "Silence level",
            listOf("Very quiet only (minus 50 decibels)", "Quiet (minus 40 decibels, recommended)", "Noisy room (minus 30 decibels)"),
            listOf(-50, -40, -30),
            { Prefs.silenceThresholdDb }, { Prefs.silenceThresholdDb = it }, changed)
        addChoice(ctx, p, "Shortest silence to remove",
            listOf("0.3 seconds", "0.5 seconds", "1 second", "2 seconds"),
            listOf(300, 500, 1000, 2000),
            { Prefs.silenceMinMs }, { Prefs.silenceMinMs = it }, changed)
        addChoice(ctx, p, "Pause kept around speech",
            listOf("0.05 seconds", "0.15 seconds", "0.3 seconds"),
            listOf(50, 150, 300),
            { Prefs.silencePadMs }, { Prefs.silencePadMs = it }, changed)
        addSwitch(ctx, p, "Remove silence when I edit an audio recording",
            "When you tap Edit on a new audio recording, silence is removed automatically. You can undo it.",
            { Prefs.autoSilence }, { Prefs.autoSilence = it }, changed)
    }

    fun audioEditing(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addChoice(ctx, p, "Jump size (Back and Forward buttons)",
            listOf("1 second", "5 seconds", "10 seconds", "30 seconds", "60 seconds"),
            listOf(1, 5, 10, 30, 60),
            { Prefs.jumpSeconds }, { Prefs.jumpSeconds = it }, changed)
        heading(ctx, p, "Mix defaults")
        mixDefaults(ctx, p, changed)
        heading(ctx, p, "Compress defaults")
        compressAudio(ctx, p, changed)
        heading(ctx, p, "Silence removal")
        note(ctx, p, "Used by the Remove silence button in the Tools tab of the editors.")
        silence(ctx, p, changed)
    }

    fun videoEditing(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addChoice(ctx, p, "Jump size (Back and Forward buttons)",
            listOf("1 second", "5 seconds", "10 seconds", "30 seconds", "60 seconds"),
            listOf(1, 5, 10, 30, 60),
            { Prefs.jumpSecondsVideo }, { Prefs.jumpSecondsVideo = it }, changed)
        addSwitch(ctx, p, "Show the video picture in the editor",
            "Turn off to leave more room for the controls. Sound and editing are not affected.",
            { Prefs.videoPreview }, { Prefs.videoPreview = it }, changed)
        heading(ctx, p, "Mix (add music) defaults")
        mixDefaults(ctx, p, changed)
        heading(ctx, p, "Compress defaults")
        compressVideo(ctx, p, changed)
    }

    fun mixDefaults(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        val levels = listOf(0, 25, 50, 75, 100, 125, 150)
        val labels = levels.map { if (it == 0) "Muted (0%)" else "$it%" }
        addChoice(ctx, p, "Volume of the main file", labels, levels,
            { Prefs.mixBaseVolume }, { Prefs.mixBaseVolume = it }, changed)
        addChoice(ctx, p, "Volume of the added sound", labels, levels,
            { Prefs.mixOverlayVolume }, { Prefs.mixOverlayVolume = it }, changed)
    }

    fun compressAudio(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addChoice(ctx, p, "Audio quality (bitrate)",
            listOf("16 kbps (tiny, speech only)", "24 kbps", "32 kbps (small)", "48 kbps",
                "64 kbps", "96 kbps (good for voice)", "128 kbps (balanced)", "192 kbps (high)"),
            listOf(16, 24, 32, 48, 64, 96, 128, 192),
            { Prefs.compressAudioKbps }, { Prefs.compressAudioKbps = it }, changed)
        addSwitch(ctx, p, "Mono (one channel, about half the size)", "",
            { Prefs.compressAudioMono }, { Prefs.compressAudioMono = it }, changed)
        addNumber(ctx, p, "Target size (0 = off)", "megabytes", 0, 2000,
            { Prefs.compressTargetMb }, { Prefs.compressTargetMb = it }, changed)
    }

    fun compressVideo(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addChoice(ctx, p, "Picture size",
            listOf("Keep original size", "1080p", "720p", "480p", "360p (small)", "240p (tiny)"),
            listOf(0, 1080, 720, 480, 360, 240),
            { Prefs.compressVideoHeight }, { Prefs.compressVideoHeight = it }, changed)
        addChoice(ctx, p, "Video quality (bitrate)",
            listOf("250 kbps (tiny)", "500 kbps (very small)", "1 Mbps (small)", "2 Mbps (balanced)",
                "4 Mbps (good)", "8 Mbps (high)"),
            listOf(250, 500, 1000, 2000, 4000, 8000),
            { Prefs.compressVideoKbps }, { Prefs.compressVideoKbps = it }, changed)
        addChoice(ctx, p, "Sound",
            listOf("Keep good sound (128 kbps)", "Smaller sound (64 kbps)", "Smallest sound (32 kbps, mono)", "Remove the sound"),
            listOf("keep", "64", "32", "none"),
            { Prefs.compressVideoSound }, { Prefs.compressVideoSound = it }, changed)
        addNumber(ctx, p, "Target size (0 = off)", "megabytes", 0, 4000,
            { Prefs.compressTargetMb }, { Prefs.compressTargetMb = it }, changed)
    }

    /** A number the user types, for example a delay in milliseconds. */
    fun addNumber(
        ctx: Context,
        parent: ViewGroup,
        title: String,
        unit: String,
        min: Int,
        max: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        onChanged: () -> Unit = {}
    ) {
        val btn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.minHeight = dp(ctx, 56)
        btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        btn.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        btn.isAllCaps = false
        fun refresh() {
            btn.text = "$title: ${get()} $unit"
            btn.contentDescription = SpeechText.clean("$title, ${get()} $unit. Double tap to type a new value.")
        }
        refresh()
        btn.setOnClickListener {
            val input = android.widget.EditText(ctx)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            input.setText(get().toString())
            input.setSelectAllOnFocus(true)
            input.hint = unit
            val frame = android.widget.FrameLayout(ctx)
            frame.setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
            frame.addView(input)
            MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setMessage("Type a number from $min to $max $unit.")
                .setView(frame)
                .setPositiveButton("OK") { _, _ ->
                    val v = input.text?.toString()?.trim()?.toIntOrNull()
                    if (v != null) {
                        set(v.coerceIn(min, max))
                        refresh()
                        onChanged()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        parent.addView(btn, rowParams(ctx))
    }

    fun addButton(ctx: Context, parent: ViewGroup, text: String, onClick: () -> Unit): MaterialButton {
        val btn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.text = text
        btn.minHeight = dp(ctx, 56)
        btn.isAllCaps = false
        btn.setOnClickListener { onClick() }
        parent.addView(btn, rowParams(ctx))
        return btn
    }

    fun paragraph(ctx: Context, parent: ViewGroup, text: String): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        parent.addView(tv, rowParams(ctx))
        return tv
    }

    fun accessibility(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addSwitch(ctx, p, "Spoken messages",
            "The app announces what it did, for example \"Cut 12 seconds\".",
            { Prefs.announcements }, { Prefs.announcements = it }, changed)
        addSwitch(ctx, p, "Announce elapsed time every minute while recording",
            "",
            { Prefs.minuteAnnouncements }, { Prefs.minuteAnnouncements = it }, changed)
        addSwitch(ctx, p, "Sound cues",
            "Short beeps when recording starts, pauses, resumes and stops.",
            { Prefs.beeps }, { Prefs.beeps = it }, changed)
        addSwitch(ctx, p, "Vibration cues",
            "A short vibration for the same events.",
            { Prefs.haptics }, { Prefs.haptics = it }, changed)
    }

    fun appearance(ctx: Context, p: ViewGroup, changed: () -> Unit) {
        addChoice(ctx, p, "Theme",
            listOf("Follow the system", "Light", "Dark"), listOf("system", "light", "dark"),
            { Prefs.theme }, { Prefs.theme = it }, changed)
    }
}
