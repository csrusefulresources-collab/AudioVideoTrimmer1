package com.example.audiovideotrimmer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.audiovideotrimmer.databinding.FragmentRecordBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Record tab: audio (HD WAV), video (camera) and screen recording,
 * with the settings that matter shown right above the Start button.
 */
class RecordFragment : Fragment(), SectionScreen {

    private enum class Mode { AUDIO, VIDEO, SCREEN }

    private var _b: FragmentRecordBinding? = null
    private val b get() = _b!!

    private var mode = Mode.AUDIO
    private val handler = Handler(Looper.getMainLooper())
    private var switcher: PageSwitcher? = null
    private var selectedTab = 0

    private var lastPhase = Phase.IDLE
    private var lastActive = false
    private var focusRecordAfterChange = false
    private var lastCountdown = -1
    private var lastMinute = 0
    private var lastLevelWarning = 0L
    private var countdownRunnable: Runnable? = null

    // camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraFile: File? = null

    // last recording
    private var player: ExoPlayer? = null
    private var currentFile: File? = null
    private var seekingLast = false
    private val lastTicker = object : Runnable {
        override fun run() {
            updateLastProgress()
            handler.postDelayed(this, 500L)
        }
    }

    private var pendingAfterPermission: (() -> Unit)? = null
    private var requiredPermissions: List<String> = emptyList()
    private var pendingAfterOverlay: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = requiredPermissions.filter { results[it] != true && !hasPermission(it) }
            val next = pendingAfterPermission
            pendingAfterPermission = null
            if (denied.isEmpty()) {
                next?.invoke()
            } else {
                AppLog.w("Permission", "Denied: ${denied.joinToString()}")
                announce("Permission needed: ${denied.joinToString { describePermission(it) }}. Allow it in the phone's app settings.")
            }
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(requireContext(), ScreenRecordService::class.java)
                    .setAction(BaseRecordService.ACTION_START)
                    .putExtra(BaseRecordService.EXTRA_COUNTDOWN, Prefs.countdownSeconds)
                    .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, r.resultCode)
                    .putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
                ContextCompat.startForegroundService(requireContext(), intent)
            } else {
                announce("Screen recording was not allowed.")
            }
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val next = pendingAfterOverlay
            pendingAfterOverlay = null
            next?.invoke()
        }

    // ---- SectionScreen
    override val sectionTitles: List<String> get() = TITLES
    override val selectedSection: Int get() = selectedTab
    override fun selectSection(index: Int) {
        switcher?.select(index)
    }

    override fun focusStart() {
        _b?.tvRecordTitle?.let { v ->
            v.post { ViewCompat.performAccessibilityAction(v, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null) }
        }
    }

    // ================================================================ lifecycle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentRecordBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mode = when (Prefs.recordMode) {
            "video" -> Mode.VIDEO
            "screen" -> Mode.SCREEN
            else -> Mode.AUDIO
        }
        b.modeGroup.check(
            when (mode) {
                Mode.AUDIO -> R.id.btnModeAudio
                Mode.VIDEO -> R.id.btnModeVideo
                Mode.SCREEN -> R.id.btnModeScreen
            }
        )
        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !RecordState.isBusy) {
                setMode(
                    when (id) {
                        R.id.btnModeVideo -> Mode.VIDEO
                        R.id.btnModeScreen -> Mode.SCREEN
                        else -> Mode.AUDIO
                    }
                )
            }
        }

        // One big button that changes its label (Start → Stop and save), so the screen reader
        // focus stays on it and the user can stop the recording right away.
        b.btnRecord.setOnClickListener {
            focusRecordAfterChange = true
            if (RecordState.isBusy) onStopClicked() else onStartClicked()
        }
        b.btnPause.setOnClickListener { onPauseClicked() }
        b.btnSayTime.setOnClickListener {
            val ms = RecordState.state.value.elapsedMs
            announce("Recording time: ${TimeFormat.spoken(ms)}.", force = true)
        }
        b.btnMoreSettings.setOnClickListener { selectSection(2) }
        switcher = PageSwitcher(
            listOf(b.pageRecord, b.pageLibrary, b.pageSettings),
            b.recordScroll,
            selectedTab
        ) {
            selectedTab = it
            (activity as? MainActivity)?.syncSections()
        }

        b.btnPlayLast.setOnClickListener { togglePlayLast() }
        b.btnEdit.setOnClickListener { editLast() }
        b.btnSaveLast.setOnClickListener { saveLast() }
        b.btnShareLast.setOnClickListener { shareLast() }
        b.btnDeleteLast.setOnClickListener { deleteLast() }
        b.btnRecent.setOnClickListener { showRecent() }
        b.seekLast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = player ?: return
                if (!seekingLast && p.duration > 0) p.seekTo(p.duration * progress / 1000)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekingLast = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekingLast = false
                val p = player ?: return
                if (p.duration > 0) p.seekTo(p.duration * (seekBar?.progress ?: 0) / 1000)
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RecordState.state.collect { render(it) } }
                launch { RecordState.events.collect { announce(it, force = true); refreshLast() } }
            }
        }

        rebuildQuickSettings()
        refreshLast()
        render(RecordState.state.value)
    }

    override fun onStart() {
        super.onStart()
        if (!isHidden) startScreen()
    }

    override fun onStop() {
        super.onStop()
        stopScreen()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) stopScreen() else startScreen()
    }

    /** Called when this part is shown: player for the last recording, camera preview. */
    private fun startScreen() {
        if (player == null) createPlayer()
        rebuildQuickSettings()
        refreshLast()
        handler.removeCallbacks(lastTicker)
        handler.post(lastTicker)
        if (mode == Mode.VIDEO) bindCameraIfPossible()
    }

    private fun stopScreen() {
        handler.removeCallbacks(lastTicker)
        player?.release()
        player = null
        _b?.playerViewLast?.player = null
        if (activeRecording == null) unbindCamera()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        activeRecording?.stop()
        activeRecording = null
        unbindCamera()
        _b = null
    }

    // ================================================================== mode

    private fun setMode(newMode: Mode) {
        mode = newMode
        Prefs.recordMode = when (newMode) {
            Mode.AUDIO -> "audio"
            Mode.VIDEO -> "video"
            Mode.SCREEN -> "screen"
        }
        rebuildQuickSettings()
        render(RecordState.state.value)
        val name = when (newMode) {
            Mode.AUDIO -> "Audio recording"
            Mode.VIDEO -> "Video recording with the camera"
            Mode.SCREEN -> "Screen recording"
        }
        announce("$name selected. ${modeHelp()}")
        if (newMode == Mode.VIDEO) bindCameraIfPossible() else unbindCamera()
    }

    private fun modeHelp(): String = when (mode) {
        Mode.AUDIO -> "Records lossless HD sound from the microphone. Keeps recording when the screen is off."
        Mode.VIDEO -> "Records video with the camera in HD or better, with sound."
        Mode.SCREEN -> "Records everything on the screen, with your voice if you like. Use the floating buttons or the notification to pause and stop."
    }

    private fun summary(): String = when (mode) {
        Mode.AUDIO -> {
            val rate = if (Prefs.audioSampleRate == 48000) "48 kHz" else "44.1 kHz"
            val ch = if (Prefs.audioChannels == 2) "stereo" else "mono"
            "HD audio: $rate, 16-bit, $ch, lossless WAV. " +
                "Noise reduction ${onOff(Prefs.noiseSuppression)}. " +
                "Echo cancellation ${onOff(Prefs.echoCancel)}. " +
                "Automatic gain ${onOff(Prefs.autoGain)}. " +
                (if (Prefs.echoEffect) "Echo effect on, ${Prefs.echoDelayMs} milliseconds, ${Prefs.echoLevel}. " else "Echo effect off. ") +
                "Countdown ${countdownText()}."
        }
        Mode.VIDEO -> {
            val q = when (Prefs.videoQuality) {
                "uhd" -> "4K Ultra HD"
                "fhd" -> "Full HD 1080p"
                "hd" -> "HD 720p"
                else -> "Standard 480p"
            }
            "Video: $q, ${if (Prefs.videoCamera == "front") "front" else "back"} camera, " +
                "sound ${onOff(Prefs.videoAudio)}. Countdown ${countdownText()}."
        }
        Mode.SCREEN -> {
            val res = when (Prefs.screenScale) {
                "1080" -> "1080p"
                "720" -> "720p"
                else -> "full screen resolution"
            }
            "Screen: $res, ${Prefs.screenFps} frames per second, ${Prefs.screenBitrateMbps} Mbps, " +
                "microphone ${onOff(Prefs.screenMic)}, floating buttons ${onOff(Prefs.floatingControls)}. " +
                "Countdown ${countdownText()}."
        }
    }

    private fun onOff(v: Boolean) = if (v) "on" else "off"
    private fun countdownText() = if (Prefs.countdownSeconds == 0) "off" else "${Prefs.countdownSeconds} seconds"

    private fun rebuildQuickSettings() {
        val bd = _b ?: return
        val ctx = context ?: return
        val box = bd.recSettingsContainer
        box.removeAllViews()
        val changed = {
            if (mode == Mode.VIDEO) unbindCamera()
            refreshSummary()
        }
        SettingsUi.heading(ctx, box, "Audio recording")
        SettingsUi.audioRecording(ctx, box, true, changed)
        SettingsUi.addSwitch(ctx, box, "Remove silence when I edit an audio recording",
            "Silence is removed automatically when you tap Edit. You can undo it.",
            { Prefs.autoSilence }, { Prefs.autoSilence = it }, changed)
        SettingsUi.heading(ctx, box, "Video recording (camera)")
        SettingsUi.videoRecording(ctx, box, changed)
        SettingsUi.heading(ctx, box, "Screen recording")
        SettingsUi.screenRecording(ctx, box, true, changed)
        SettingsUi.heading(ctx, box, "All recordings")
        SettingsUi.commonRecording(ctx, box, changed)
        SettingsUi.setEnabledDeep(box, !RecordState.isBusy)
        refreshSummary()
    }

    private fun refreshSummary() {
        val bd = _b ?: return
        bd.tvModeHelp.text = modeHelp()
        bd.tvSettingsSummary.text = summary()
        bd.cameraPreview.visibility = if (mode == Mode.VIDEO) View.VISIBLE else View.GONE
        if (!RecordState.isBusy) {
            bd.btnRecord.text = getString(
                when (mode) {
                    Mode.AUDIO -> R.string.rec_start_audio
                    Mode.VIDEO -> R.string.rec_start_video
                    Mode.SCREEN -> R.string.rec_start_screen
                }
            )
        }
        if (mode == Mode.VIDEO && videoCapture == null) bindCameraIfPossible()
    }

    // ================================================================ permissions

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    private fun describePermission(p: String) = when (p) {
        Manifest.permission.RECORD_AUDIO -> "microphone"
        Manifest.permission.CAMERA -> "camera"
        else -> "storage"
    }

    private fun optionalPermissions(): MutableList<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29 && Prefs.autoSaveToPhone) list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return list
    }

    private fun ensurePermissions(required: List<String>, optional: List<String>, then: () -> Unit) {
        val missing = (required + optional).filter { !hasPermission(it) }
        requiredPermissions = required
        if (missing.isEmpty()) {
            then()
            return
        }
        pendingAfterPermission = then
        permissionLauncher.launch(missing.toTypedArray())
    }

    // ================================================================ start / stop

    private fun onStartClicked() {
        if (RecordState.isBusy) return
        when (mode) {
            Mode.AUDIO -> startAudio()
            Mode.VIDEO -> startVideo()
            Mode.SCREEN -> startScreen()
        }
    }

    private fun startAudio() {
        ensurePermissions(listOf(Manifest.permission.RECORD_AUDIO), optionalPermissions()) {
            val intent = Intent(requireContext(), AudioRecordService::class.java)
                .setAction(BaseRecordService.ACTION_START)
                .putExtra(BaseRecordService.EXTRA_COUNTDOWN, Prefs.countdownSeconds)
            ContextCompat.startForegroundService(requireContext(), intent)
        }
    }

    private fun startScreen() {
        val optional = optionalPermissions()
        if (Prefs.screenMic) optional.add(Manifest.permission.RECORD_AUDIO)
        ensurePermissions(emptyList(), optional) {
            ensureOverlay {
                val mpm = requireContext().getSystemService(MediaProjectionManager::class.java)
                captureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }

    private fun ensureOverlay(next: () -> Unit) {
        val ctx = requireContext()
        if (!Prefs.floatingControls || Settings.canDrawOverlays(ctx)) {
            next()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Allow floating buttons?")
            .setMessage(
                "To show Pause and Stop buttons above other apps, this app needs the permission " +
                    "\"Display over other apps\". Without it you can still use the buttons in the notification."
            )
            .setPositiveButton("Open settings") { _, _ ->
                pendingAfterOverlay = next
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            }
            .setNegativeButton("Continue without") { _, _ -> next() }
            .setOnCancelListener { next() }
            .show()
    }

    private fun startVideo() {
        val optional = optionalPermissions()
        if (Prefs.videoAudio) optional.add(Manifest.permission.RECORD_AUDIO)
        ensurePermissions(listOf(Manifest.permission.CAMERA), optional) {
            bindCamera {
                countdownThen(Prefs.countdownSeconds) { startCameraRecording() }
            }
        }
    }

    private fun onPauseClicked() {
        val s = RecordState.state.value
        when (s.kind) {
            RecordKind.VIDEO ->
                if (s.phase == Phase.RECORDING) activeRecording?.pause() else activeRecording?.resume()
            RecordKind.AUDIO -> sendServiceAction(
                AudioRecordService::class.java,
                if (s.phase == Phase.RECORDING) BaseRecordService.ACTION_PAUSE else BaseRecordService.ACTION_RESUME
            )
            RecordKind.SCREEN -> sendServiceAction(
                ScreenRecordService::class.java,
                if (s.phase == Phase.RECORDING) BaseRecordService.ACTION_PAUSE else BaseRecordService.ACTION_RESUME
            )
            null -> {}
        }
    }

    private fun onStopClicked() {
        val s = RecordState.state.value
        when (s.kind) {
            RecordKind.VIDEO -> {
                if (activeRecording != null) {
                    activeRecording?.stop()
                } else {
                    cancelLocalCountdown()
                }
            }
            RecordKind.AUDIO -> sendServiceAction(AudioRecordService::class.java, BaseRecordService.ACTION_STOP)
            RecordKind.SCREEN -> sendServiceAction(ScreenRecordService::class.java, BaseRecordService.ACTION_STOP)
            null -> {}
        }
    }

    private fun sendServiceAction(cls: Class<*>, action: String) {
        requireContext().startService(Intent(requireContext(), cls).setAction(action))
    }

    // ================================================================ camera

    private fun bindCameraIfPossible() {
        if (hasPermission(Manifest.permission.CAMERA)) bindCamera {}
    }

    private fun bindCamera(onReady: () -> Unit) {
        val ctx = context ?: return
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val bd = _b ?: return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(bd.cameraPreview.surfaceProvider)
                val quality = when (Prefs.videoQuality) {
                    "uhd" -> Quality.UHD
                    "hd" -> Quality.HD
                    "sd" -> Quality.SD
                    else -> Quality.FHD
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                val selector = if (Prefs.videoCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, capture)
                videoCapture = capture
                onReady()
            } catch (e: Exception) {
                AppLog.e("Camera", "Camera could not be started", e)
                announce("The camera could not be started.")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun unbindCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        videoCapture = null
    }

    private fun countdownThen(seconds: Int, action: () -> Unit) {
        cancelLocalCountdown()
        if (seconds <= 0) {
            action()
            return
        }
        var left = seconds
        val r = object : Runnable {
            override fun run() {
                if (left <= 0) {
                    countdownRunnable = null
                    action()
                    return
                }
                RecordState.state.value = RecState(RecordKind.VIDEO, Phase.COUNTDOWN, 0L, left, 0f)
                context?.let { Feedback.cue(it) }
                left--
                handler.postDelayed(this, 1000L)
            }
        }
        countdownRunnable = r
        handler.post(r)
    }

    private fun cancelLocalCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        if (RecordState.state.value.kind == RecordKind.VIDEO && RecordState.state.value.phase == Phase.COUNTDOWN) {
            RecordState.state.value = RecState()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCameraRecording() {
        val ctx = context ?: return
        val capture = videoCapture
        if (capture == null) {
            RecordState.state.value = RecState()
            announce("The camera is not ready.")
            return
        }
        val file = RecordingStore.newFile(ctx, "video", "mp4")
        cameraFile = file
        var pending = capture.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
        if (Prefs.videoAudio && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        context?.let { Feedback.cue(it, high = true) }
        activeRecording = pending.start(ContextCompat.getMainExecutor(ctx)) { event -> onVideoEvent(event) }
    }

    private fun onVideoEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start ->
                RecordState.state.value = RecState(RecordKind.VIDEO, Phase.RECORDING, 0L, 0, 0f)
            is VideoRecordEvent.Status -> {
                val s = RecordState.state.value
                if (s.kind == RecordKind.VIDEO) {
                    RecordState.state.value =
                        s.copy(elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000L)
                }
            }
            is VideoRecordEvent.Pause ->
                RecordState.state.value = RecordState.state.value.copy(phase = Phase.PAUSED)
            is VideoRecordEvent.Resume ->
                RecordState.state.value = RecordState.state.value.copy(phase = Phase.RECORDING)
            is VideoRecordEvent.Finalize -> {
                activeRecording = null
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                RecordState.state.value = RecState()
                val file = cameraFile
                cameraFile = null
                val ctx = context?.applicationContext
                if (file != null && file.exists() && file.length() > 1000L && ctx != null) {
                    Feedback.cue(ctx, high = true)
                    RecordState.post("Recording stopped. Saved as ${file.name}.")
                    if (Prefs.autoSaveToPhone) {
                        Thread {
                            val where = try {
                                RecordingStore.saveToPhone(ctx, file)
                            } catch (_: Exception) {
                                null
                            }
                            RecordState.post(
                                if (where != null) "A copy was saved in $where."
                                else "Could not save a copy to the phone storage."
                            )
                        }.start()
                    }
                } else {
                    file?.delete()
                    RecordState.post("The video could not be saved.")
                }
            }
            else -> {}
        }
    }

    // ================================================================ render

    private fun render(s: RecState) {
        val bd = _b ?: return
        val active = s.phase != Phase.IDLE

        if (active && s.kind != null) {
            val m = when (s.kind) {
                RecordKind.AUDIO -> Mode.AUDIO
                RecordKind.VIDEO -> Mode.VIDEO
                RecordKind.SCREEN -> Mode.SCREEN
            }
            if (m != mode) {
                mode = m
                bd.modeGroup.check(
                    when (m) {
                        Mode.AUDIO -> R.id.btnModeAudio
                        Mode.VIDEO -> R.id.btnModeVideo
                        Mode.SCREEN -> R.id.btnModeScreen
                    }
                )
                rebuildQuickSettings()
            }
        }

        bd.tvTimer.text = TimeFormat.clock(s.elapsedMs)
        bd.tvTimer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        bd.tvRecState.text = when (s.phase) {
            Phase.IDLE -> getString(R.string.rec_state_ready)
            Phase.COUNTDOWN -> "Starting in ${s.countdown}"
            Phase.RECORDING -> "Recording"
            Phase.PAUSED -> "Paused"
        }

        // The big button stays in the same place and changes its label and icon.
        bd.btnRecord.text = when (s.phase) {
            Phase.IDLE -> getString(
                when (mode) {
                    Mode.AUDIO -> R.string.rec_start_audio
                    Mode.VIDEO -> R.string.rec_start_video
                    Mode.SCREEN -> R.string.rec_start_screen
                }
            )
            Phase.COUNTDOWN -> "Cancel the countdown"
            else -> getString(R.string.rec_stop)
        }
        bd.btnRecord.icon = AppCompatResources.getDrawable(
            requireContext(), if (active) R.drawable.ic_stop else R.drawable.ic_record
        )
        val running = s.phase == Phase.RECORDING || s.phase == Phase.PAUSED
        bd.activeControls.visibility = if (running) View.VISIBLE else View.GONE
        if (s.phase == Phase.PAUSED) {
            bd.btnPause.setText(R.string.rec_resume)
            bd.btnPause.icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_play)
        } else {
            bd.btnPause.setText(R.string.rec_pause)
            bd.btnPause.icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_pause)
        }

        if (active != lastActive) {
            lastActive = active
            for (i in 0 until bd.modeGroup.childCount) bd.modeGroup.getChildAt(i).isEnabled = !active
            SettingsUi.setEnabledDeep(bd.recSettingsContainer, !active)
        }
        if (focusRecordAfterChange && s.phase != lastPhase) {
            focusRecordAfterChange = false
            bd.btnRecord.post {
                _b?.btnRecord?.let {
                    ViewCompat.performAccessibilityAction(it, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null)
                }
            }
        }

        val showLevel = active && s.kind == RecordKind.AUDIO
        bd.levelGroup.visibility = if (showLevel) View.VISIBLE else View.GONE
        if (showLevel) {
            bd.levelBar.setProgressCompat((s.level * 100).toInt().coerceIn(0, 100), false)
            bd.tvLevel.text = when {
                s.phase == Phase.PAUSED -> "Input level: paused"
                s.level > 0.95f -> "Input level: too loud"
                s.level < 0.03f -> "Input level: very quiet"
                else -> "Input level: good"
            }
            val now = System.currentTimeMillis()
            if (s.phase == Phase.RECORDING && s.level > 0.98f && now - lastLevelWarning > 8000L) {
                lastLevelWarning = now
                announce("Too loud. Move the microphone away or speak more softly.", force = true)
            }
        }

        // announcements on state changes
        if (s.phase == Phase.COUNTDOWN && s.countdown != lastCountdown) {
            lastCountdown = s.countdown
            announce("${s.countdown}", force = true)
        }
        if (s.phase != lastPhase) {
            val previous = lastPhase
            lastPhase = s.phase
            when (s.phase) {
                Phase.RECORDING -> {
                    lastMinute = 0
                    announce(if (previous == Phase.PAUSED) "Recording resumed." else "Recording started.", force = true)
                }
                Phase.PAUSED -> announce("Recording paused at ${TimeFormat.spoken(s.elapsedMs)}.", force = true)
                Phase.IDLE -> {
                    lastCountdown = -1
                    currentFile = null
                    refreshLast()
                    rebuildQuickSettings()
                }
                Phase.COUNTDOWN -> {}
            }
        }
        if (s.phase == Phase.RECORDING && Prefs.minuteAnnouncements) {
            val minutes = (s.elapsedMs / 60_000L).toInt()
            if (minutes > lastMinute) {
                lastMinute = minutes
                announce("$minutes ${if (minutes == 1) "minute" else "minutes"} recorded.", force = true)
            }
        }
    }

    private fun announce(message: String, force: Boolean = false) {
        val bd = _b ?: return
        bd.tvRecStatus.text = message
        if (Prefs.announcements || force) bd.root.announceForAccessibility(SpeechText.clean(message))
    }

    // ================================================================ last recording

    private fun createPlayer() {
        val exo = ExoPlayer.Builder(requireContext()).build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayLastButton()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayLastButton()
            }
        })
        b.playerViewLast.player = exo
        player = exo
        currentFile?.let { loadIntoPlayer(it) }
    }

    private fun loadIntoPlayer(file: File) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.playWhenReady = false
    }

    private fun refreshLast() {
        val ctx = context ?: return
        val bd = _b ?: return
        val files = RecordingStore.list(ctx)
        val keep = currentFile?.takeIf { it.exists() }
        val chosen = keep ?: files.firstOrNull()
        val changed = chosen != currentFile || keep == null
        currentFile = chosen
        val file = chosen
        val has = file != null
        listOf(bd.btnPlayLast, bd.btnEdit, bd.btnSaveLast, bd.btnShareLast, bd.btnDeleteLast).forEach { it.isEnabled = has }
        bd.seekLast.isEnabled = has
        bd.btnRecent.isEnabled = files.isNotEmpty()
        if (file == null) {
            bd.tvLastInfo.text = getString(R.string.last_none)
            bd.playerViewLast.visibility = View.GONE
            return
        }
        bd.playerViewLast.visibility = if (RecordingStore.isVideo(file)) View.VISIBLE else View.GONE
        bd.tvLastInfo.text = "${file.name} — ${RecordingStore.sizeText(file)}"
        if (changed) loadIntoPlayer(file)
        viewLifecycleOwner.lifecycleScope.launch {
            val duration = withContext(Dispatchers.IO) { durationOf(ctx, file) }
            if (currentFile == file && _b != null) {
                val kind = if (RecordingStore.isVideo(file)) "Video" else "Audio"
                val text = "${file.name} — $kind, ${TimeFormat.format(duration)}, ${RecordingStore.sizeText(file)}"
                _b?.tvLastInfo?.text = text
                _b?.tvLastInfo?.contentDescription =
                    "${file.name} — $kind, ${TimeFormat.spoken(duration)}, ${RecordingStore.sizeText(file)}"
            }
        }
    }

    private fun durationOf(ctx: Context, file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, Uri.fromFile(file))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun togglePlayLast() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            p.play()
        }
    }

    private fun updatePlayLastButton() {
        val bd = _b ?: return
        val playing = player?.isPlaying == true
        bd.btnPlayLast.setText(if (playing) R.string.pause else R.string.last_play)
        bd.btnPlayLast.icon = AppCompatResources.getDrawable(
            requireContext(), if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateLastProgress() {
        val bd = _b ?: return
        val p = player ?: return
        val d = p.duration
        val pos = p.currentPosition.coerceAtLeast(0L)
        if (d > 0L) {
            if (!seekingLast) bd.seekLast.progress = (pos * 1000 / d).toInt().coerceIn(0, 1000)
            bd.tvLastTime.text = "${TimeFormat.format(pos)} / ${TimeFormat.format(d)}"
            if (!p.isPlaying) {
                ViewCompat.setStateDescription(
                    bd.seekLast, "${TimeFormat.spoken(pos)} of ${TimeFormat.spoken(d)}"
                )
            }
        }
    }

    private fun editLast() {
        val file = currentFile ?: return
        player?.pause()
        val video = RecordingStore.isVideo(file)
        (activity as? MainActivity)?.openInEditor(
            Uri.fromFile(file), video, removeSilence = Prefs.autoSilence && !video
        )
    }

    private fun saveLast() {
        val file = currentFile ?: return
        if (Build.VERSION.SDK_INT < 29 && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            ensurePermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), emptyList()) { saveLast() }
            return
        }
        val app = requireContext().applicationContext
        announce("Saving a copy…")
        viewLifecycleOwner.lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) { RecordingStore.saveToPhone(app, file) }
            announce(
                if (where != null) "Saved ${file.name} in $where." else "Could not save the file.",
                force = true
            )
        }
    }

    private fun shareLast() {
        val file = currentFile ?: return
        val ctx = requireContext()
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(RecordingStore.mimeFor(file))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share recording"))
        } catch (e: Exception) {
            announce("Could not share this file.")
        }
    }

    private fun deleteLast() {
        val file = currentFile ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete this recording?")
            .setMessage("${file.name} will be deleted from the app. A copy saved on the phone is not deleted.")
            .setPositiveButton("Delete") { _, _ ->
                player?.stop()
                player?.clearMediaItems()
                file.delete()
                currentFile = null
                (activity as? MainActivity)?.notifyFileDeleted()
                refreshLast()
                announce("Recording deleted.", force = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRecent() {
        val ctx = requireContext()
        val files = RecordingStore.list(ctx).take(30)
        if (files.isEmpty()) return
        val labels = files.map { "${it.name} (${RecordingStore.sizeText(it)})" }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.last_recent)
            .setItems(labels) { _, which ->
                currentFile = files[which]
                refreshLast()
                announce("Selected ${files[which].name}.", force = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        val TITLES = listOf("Record", "Library", "Settings")
    }
}
