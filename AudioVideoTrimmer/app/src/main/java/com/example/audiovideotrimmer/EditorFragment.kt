package com.example.audiovideotrimmer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.audiovideotrimmer.databinding.FragmentEditorBinding
import com.example.audiovideotrimmer.databinding.ItemMarkBinding
import com.example.audiovideotrimmer.databinding.ItemMergeBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private data class FileInfo(val name: String, val durationMs: Long, val hasVideo: Boolean)

private enum class ExportMode { NORMAL, AUDIO_ONLY, NO_AUDIO }

/** One output file to create. [build] is called right before exporting. */
private class ExportJob(
    val baseName: String,
    val outputIsVideo: Boolean,
    val build: () -> Composition,
    val encoder: (() -> Codec.EncoderFactory)? = null
)

/** Everything that can change the sound or the picture while saving. */
private data class FxOptions(
    val gain: Float = 1f,
    val height: Int = 0,
    val mono: Boolean = false,
    val echoOn: Boolean = false,
    val echoDelayMs: Int = 250,
    val echoLevel: String = "medium",
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val brightness: Int = 0,
    val contrast: Int = 0
)

private data class CompressPlan(
    val audioKbps: Int,
    val mono: Boolean,
    val videoKbps: Int,
    val removeAudio: Boolean,
    val height: Int
)

/**
 * Audio editor / Video editor tab (one screen, two modes) with its own tab bar:
 * Edit · Trim · Split · Merge · Mix · Compress · Tools.
 * Edits are non-destructive (see [EditorProject]); files are written only when saving.
 */
@OptIn(UnstableApi::class)
class EditorFragment : Fragment(), SectionScreen {

    private var _binding: FragmentEditorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: EditorViewModel by viewModels()
    private val project: EditorProject get() = viewModel.project

    private val isVideoMode: Boolean get() = arguments?.getBoolean(ARG_VIDEO) == true
    private val mimeTypes: Array<String>
        get() = if (isVideoMode) arrayOf("video/*") else arrayOf("audio/*")

    private var player: ExoPlayer? = null
    private var switcher: PageSwitcher? = null
    private var isSeeking = false
    private var previewEndMs = -1L
    private var lastShownMs = -1L

    private var busy = false
    private var analysisJob: Job? = null
    private var pendingAutoSilence = false

    private var transformer: Transformer? = null
    private var exporting = false
    private var currentTemp: File? = null
    private var currentPart = 0
    private var totalParts = 0
    private var lastProgressBucket = -1
    private val savedNames = mutableListOf<String>()
    private var savedWhere = ""
    private var pendingAfterPermission: (() -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            ui.postDelayed(this, TICK_MS)
        }
    }
    private val progressPoller = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val holder = ProgressHolder()
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onExportProgress(holder.progress)
            }
            ui.postDelayed(this, 500L)
        }
    }

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFilePicked(uri, insert = false)
        }
    private val insertLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFilePicked(uri, insert = true)
        }
    private val mergeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) addMergeFiles(uris)
        }
    private val mixLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) pickMixSound(uri)
        }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAfterPermission
            pendingAfterPermission = null
            if (granted) action?.invoke()
            else announce("Storage permission is needed to save files on this Android version.")
        }

    // ================================================================ lifecycle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvEditorTitle.setText(if (isVideoMode) R.string.title_video_editor else R.string.title_audio_editor)
        binding.btnOpen.text = if (isVideoMode) "Open video" else "Open audio"
        val videoOnly = if (isVideoMode) View.VISIBLE else View.GONE
        binding.btnSaveAudioOnly.visibility = videoOnly
        binding.btnSaveNoSound.visibility = videoOnly
        binding.btnSaveFrame.visibility = videoOnly

        switcher = PageSwitcher(
            listOf(
                binding.pageEdit, binding.pageTrim, binding.pageSplit, binding.pageMerge,
                binding.pageMix, binding.pageEffects, binding.pageCompress, binding.pageTools
            ),
            binding.pagesScroll,
            viewModel.selectedTab,
            TITLES
        ) {
            viewModel.selectedTab = it
            refreshPageContent()
            (activity as? MainActivity)?.syncSections()
        }

        setupListeners()
        cleanOldTempFiles()
        refreshAll()
    }

    override fun onStart() {
        super.onStart()
        if (!isHidden) startPlayback()
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopPlayback()
        } else {
            updateJumpLabels()
            refreshPageContent()
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (player == null) {
            createPlayer()
            rebuildPlaylist(viewModel.resumePositionMs)
            updatePlayPauseButton()
        }
        refreshPageContent()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun stopPlayback() {
        ui.removeCallbacks(ticker)
        if (player != null) {
            viewModel.resumePositionMs = currentTimelineMs()
            player?.release()
            player = null
            _binding?.playerView?.player = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ui.removeCallbacksAndMessages(null)
        analysisJob?.cancel()
        transformer?.cancel()
        transformer = null
        currentTemp?.delete()
        busy = false
        exporting = false
        _binding = null
    }

    private fun cleanOldTempFiles() {
        requireContext().cacheDir.listFiles()?.forEach { if (it.name.startsWith("export_")) it.delete() }
    }

    // ---- SectionScreen: the section buttons are in the bottom section bar of the main screen
    override val sectionTitles: List<String> get() = TITLES
    override val selectedSection: Int get() = viewModel.selectedTab
    override fun selectSection(index: Int) {
        switcher?.select(index)
    }

    override fun focusStart() {
        _binding?.tvEditorTitle?.let { v ->
            v.post { ViewCompat.performAccessibilityAction(v, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null) }
        }
    }

    /** Called when a recording was deleted elsewhere. */
    fun checkSources() {
        if (_binding != null) ensureSourcesExist()
    }

    /** Called by the Record tab ("Edit") and by "Open with". */
    fun openExternal(uri: Uri, removeSilence: Boolean) {
        pendingAutoSilence = removeSilence
        switcher?.select(0)
        onFilePicked(uri, insert = false)
    }

    // ================================================================== player

    private fun createPlayer() {
        val exo = ExoPlayer.Builder(requireContext()).build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        exo.setHandleAudioBecomingNoisy(true)
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPauseButton()
                if (playbackState == Player.STATE_ENDED) {
                    previewEndMs = -1L
                    updateTimeViews(force = true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("Editor", "Playback error", error)
                announce("This file cannot be played.")
            }
        })
        binding.playerView.player = exo
        player = exo
    }

    /** A recording that was deleted must not stay in the editor (it would make the player fail). */
    private fun ensureSourcesExist(): Boolean {
        val missing = project.sources.any { src ->
            src.uriString.startsWith("file:") &&
                Uri.parse(src.uriString).path?.let { !File(it).exists() } == true
        }
        if (!missing) return true
        AppLog.w("Editor", "A source file was deleted; the editor was cleared")
        project.clear()
        viewModel.resumePositionMs = 0L
        player?.clearMediaItems()
        refreshAll()
        announce("The file was deleted, so the editor was cleared. Open a file to continue.")
        return false
    }

    private fun rebuildPlaylist(positionMs: Long) {
        if (!ensureSourcesExist()) return
        val exo = player ?: return
        previewEndMs = -1L
        binding.playerView.visibility =
            if (project.isVideo && Prefs.videoPreview) View.VISIBLE else View.GONE
        val segs = project.segments
        if (segs.isEmpty()) {
            exo.clearMediaItems()
            return
        }
        val items = segs.map { seg ->
            val src = project.source(seg.sourceId)
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(seg.startMs)
            if (seg.endMs < src.durationMs) clip.setEndPositionMs(seg.endMs)
            MediaItem.Builder()
                .setUri(src.uriString)
                .setClippingConfiguration(clip.build())
                .build()
        }
        val (index, offset) = project.locate(positionMs)
        exo.setMediaItems(items, index, offset)
        exo.playWhenReady = false
        exo.prepare()
    }

    private fun currentTimelineMs(): Long {
        val exo = player ?: return viewModel.resumePositionMs
        val segs = project.segments
        if (segs.isEmpty()) return 0L
        val idx = exo.currentMediaItemIndex.coerceIn(0, segs.lastIndex)
        val inSegment = exo.currentPosition.coerceIn(0L, segs[idx].length)
        return project.timelineStartOf(idx) + inSegment
    }

    private fun seekToTimeline(ms: Long) {
        val target = ms.coerceIn(0L, project.totalMs)
        val (i, off) = project.locate(target)
        player?.seekTo(i, off)
        viewModel.resumePositionMs = target
        updateTimeViews(target, force = true)
    }

    private fun onTick() {
        val exo = player ?: return
        if (_binding == null || project.isEmpty) return
        val pos = currentTimelineMs()
        if (previewEndMs >= 0L && exo.isPlaying && pos >= previewEndMs) {
            exo.pause()
            previewEndMs = -1L
            announce("Selection finished.")
        }
        if (!isSeeking) updateTimeViews(pos)
    }

    // ================================================================ listeners

    private fun setupListeners() {
        val b = binding
        b.btnOpen.setOnClickListener { openLauncher.launch(mimeTypes) }
        b.btnInsertFile.setOnClickListener { if (ensureMedia()) insertLauncher.launch(mimeTypes) }

        b.btnPlayPause.setOnClickListener {
            if (!ensureMedia()) return@setOnClickListener
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    previewEndMs = -1L
                    if (currentTimelineMs() >= project.totalMs - 50L) seekToTimeline(0L)
                    it.play()
                }
            }
        }
        b.btnRewind.setOnClickListener { jumpBy(-getSeekSeconds() * 1000L) }
        b.btnForward.setOnClickListener { jumpBy(getSeekSeconds() * 1000L) }
        fineControls(b.btnBackFine, b.btnGoToTime, b.btnForwardFine)
        fineControls(b.btnTrimBackFine, b.btnTrimGoTo, b.btnTrimForwardFine)

        // ---- move (like GoldWave: beginning, end, markers)
        b.btnToBegin.setOnClickListener { moveToBeginning() }
        b.btnToEnd.setOnClickListener { moveToEnd() }
        b.btnTrimToBegin.setOnClickListener { moveToBeginning() }
        b.btnTrimToEnd.setOnClickListener { moveToEnd() }
        b.btnTrimToStartMarker.setOnClickListener { moveToStartMarker() }
        b.btnTrimToEndMarker.setOnClickListener { moveToEndMarker() }
        b.btnStartEarlier.setOnClickListener { adjustStart(-getSeekSeconds() * 1000L) }
        b.btnStartLater.setOnClickListener { adjustStart(getSeekSeconds() * 1000L) }
        b.btnEndEarlier.setOnClickListener { adjustEnd(-getSeekSeconds() * 1000L) }
        b.btnEndLater.setOnClickListener { adjustEnd(getSeekSeconds() * 1000L) }
        b.btnHearStart.setOnClickListener { hearStart() }
        b.btnHearEnd.setOnClickListener { hearEnd() }
        b.btnTrimDelete.setOnClickListener { onDelete() }
        b.btnPrevMark.setOnClickListener { moveToMark(forward = false) }
        b.btnNextMark.setOnClickListener { moveToMark(forward = true) }
        setupSeekBar()

        // ---- selection (Edit + Trim pages share the same selection)
        b.btnSetStart.setOnClickListener { setSelectionStart() }
        b.btnTrimSetStart.setOnClickListener { setSelectionStart() }
        b.btnSetEnd.setOnClickListener { setSelectionEnd() }
        b.btnTrimSetEnd.setOnClickListener { setSelectionEnd() }
        b.btnSelectAll.setOnClickListener { selectEverything() }
        b.btnTrimReset.setOnClickListener { selectEverything() }
        b.btnPlaySelection.setOnClickListener { playSelection() }
        b.btnTrimPlay.setOnClickListener { playSelection() }

        // ---- edit
        b.btnCut.setOnClickListener { onCut() }
        b.btnCopy.setOnClickListener { onCopy() }
        b.btnDelete.setOnClickListener { onDelete() }
        b.btnPaste.setOnClickListener { onPaste() }
        b.btnUndo.setOnClickListener { onUndo() }
        b.btnSave.setOnClickListener { saveEdited(ExportMode.NORMAL) }

        // ---- trim
        b.btnTrimKeep.setOnClickListener { onKeepOnly() }
        b.btnTrimBefore.setOnClickListener { onTrimBefore() }
        b.btnTrimAfter.setOnClickListener { onTrimAfter() }
        b.btnTrimSaveNew.setOnClickListener { saveSelectionAsNew() }

        // ---- split
        b.btnAddMark.setOnClickListener { onAddMark() }
        b.btnMarksEvery.setOnClickListener { showMarksEveryDialog() }
        b.btnClearMarks.setOnClickListener {
            project.clearMarks()
            refreshMarks()
            announce(getString(R.string.msg_marks_cleared))
        }
        b.btnSplit.setOnClickListener { onSplit() }

        // ---- merge / mix / compress / tools
        b.btnMergeAdd.setOnClickListener { if (!busy) mergeLauncher.launch(mimeTypes) }
        b.btnMergeClear.setOnClickListener {
            viewModel.mergeItems.clear()
            refreshMerge()
            announce("The merge list is empty.")
        }
        b.btnMergeSave.setOnClickListener { saveMerged() }
        b.btnMixPick.setOnClickListener { if (!busy) mixLauncher.launch(arrayOf("audio/*")) }
        b.btnMixSave.setOnClickListener { saveMixed() }
        b.btnCompressSave.setOnClickListener { saveCompressed() }
        b.btnPresetSmallest.setOnClickListener { applyCompressPreset("smallest") }
        b.btnPresetSmall.setOnClickListener { applyCompressPreset("small") }
        b.btnPresetBalanced.setOnClickListener { applyCompressPreset("balanced") }
        b.btnEffectsSave.setOnClickListener { saveWithEffects() }
        b.btnSaveFrame.setOnClickListener { saveFrame() }
        b.btnRemoveSilence.setOnClickListener { onRemoveSilence() }
        b.btnSaveAudioOnly.setOnClickListener { saveEdited(ExportMode.AUDIO_ONLY) }
        b.btnSaveNoSound.setOnClickListener { saveEdited(ExportMode.NO_AUDIO) }
        b.btnCancel.setOnClickListener { cancelBusy() }
    }

    private fun fineControls(back: MaterialButton, goTo: MaterialButton, forward: MaterialButton) {
        back.setOnClickListener { jumpBy(-100L) }
        forward.setOnClickListener { jumpBy(100L) }
        goTo.setOnClickListener { showGoToTimeDialog() }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || project.isEmpty) return
                val pos = progress.toLong() * project.totalMs / SEEK_MAX
                if (isSeeking) updateTimeViews(pos, force = true, moveSeekBar = false)
                else seekToTimeline(pos)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                val bd = _binding ?: return
                if (project.isEmpty) return
                seekToTimeline(bd.seekBar.progress.toLong() * project.totalMs / SEEK_MAX)
            }
        })
        ViewCompat.setAccessibilityDelegate(binding.seekBar, object : AccessibilityDelegateCompat() {
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                return when (action) {
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> {
                        jumpBy(getSeekSeconds() * 1000L); true
                    }
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> {
                        jumpBy(-getSeekSeconds() * 1000L); true
                    }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        })
    }

    // ================================================================== actions

    private fun ensureMedia(): Boolean {
        if (busy) return false
        if (project.isEmpty) {
            announce(getString(R.string.msg_no_media))
            return false
        }
        return true
    }

    private fun jumpBy(deltaMs: Long) {
        if (!ensureMedia()) return
        val target = (currentTimelineMs() + deltaMs).coerceIn(0L, project.totalMs)
        seekToTimeline(target)
        announce(TimeFormat.spoken(target))
    }

    private fun setSelectionStart() {
        if (!ensureMedia()) return
        project.setSelectionStart(currentTimelineMs())
        updateSelectionViews()
        announce(
            "Selection start set to ${TimeFormat.spoken(project.selectionStartMs)}. " +
                "Selection end is ${TimeFormat.spoken(project.selectionEndMs)}."
        )
    }

    private fun setSelectionEnd() {
        if (!ensureMedia()) return
        project.setSelectionEnd(currentTimelineMs())
        updateSelectionViews()
        announce(
            "Selection end set to ${TimeFormat.spoken(project.selectionEndMs)}. " +
                "Selection start is ${TimeFormat.spoken(project.selectionStartMs)}."
        )
    }

    private fun selectEverything() {
        if (!ensureMedia()) return
        project.selectAll()
        updateSelectionViews()
        announce(getString(R.string.msg_select_all))
    }

    private fun playSelection() {
        if (!ensureMedia()) return
        seekToTimeline(project.selectionStartMs)
        previewEndMs = project.selectionEndMs
        player?.play()
        announce(
            getString(
                R.string.msg_selection_playing,
                TimeFormat.spoken(project.selectionStartMs),
                TimeFormat.spoken(project.selectionEndMs)
            )
        )
    }

    // ------------------------------------------------------------ move like GoldWave

    private fun moveToBeginning() {
        if (!ensureMedia()) return
        seekToTimeline(0L)
        announce("Moved to the beginning.")
    }

    private fun moveToEnd() {
        if (!ensureMedia()) return
        seekToTimeline(project.totalMs)
        announce("Moved to the end, at ${TimeFormat.spoken(project.totalMs)}.")
    }

    private fun moveToStartMarker() {
        if (!ensureMedia()) return
        seekToTimeline(project.selectionStartMs)
        announce("Moved to the start marker, at ${TimeFormat.spoken(project.selectionStartMs)}.")
    }

    private fun moveToEndMarker() {
        if (!ensureMedia()) return
        seekToTimeline(project.selectionEndMs)
        announce("Moved to the end marker, at ${TimeFormat.spoken(project.selectionEndMs)}.")
    }

    private fun adjustStart(deltaMs: Long) {
        if (!ensureMedia()) return
        project.setSelectionStart(project.selectionStartMs + deltaMs)
        updateSelectionViews()
        announce(
            "Start marker at ${TimeFormat.spoken(project.selectionStartMs)}. " +
                "End marker at ${TimeFormat.spoken(project.selectionEndMs)}."
        )
    }

    private fun adjustEnd(deltaMs: Long) {
        if (!ensureMedia()) return
        project.setSelectionEnd(project.selectionEndMs + deltaMs)
        updateSelectionViews()
        announce(
            "End marker at ${TimeFormat.spoken(project.selectionEndMs)}. " +
                "Start marker at ${TimeFormat.spoken(project.selectionStartMs)}."
        )
    }

    /** Plays the first three seconds of the selection, to check where it starts. */
    private fun hearStart() {
        if (!ensureMedia()) return
        seekToTimeline(project.selectionStartMs)
        previewEndMs = minOf(project.selectionEndMs, project.selectionStartMs + 3000L)
        player?.play()
        announce("Playing the start of the selection.")
    }

    /** Plays the last three seconds of the selection, to check where it ends. */
    private fun hearEnd() {
        if (!ensureMedia()) return
        seekToTimeline(maxOf(project.selectionStartMs, project.selectionEndMs - 3000L))
        previewEndMs = project.selectionEndMs
        player?.play()
        announce("Playing the end of the selection.")
    }

    private fun moveToMark(forward: Boolean) {
        if (!ensureMedia()) return
        val pos = currentTimelineMs()
        val marks = project.marks
        val index = if (forward) marks.indexOfFirst { it > pos + 100L }
        else marks.indexOfLast { it < pos - 100L }
        if (index < 0) {
            announce(if (forward) "There is no later mark." else "There is no earlier mark.")
            return
        }
        seekToTimeline(marks[index])
        announce("Mark ${index + 1} at ${TimeFormat.spoken(marks[index])}.")
    }

    private fun onCut() {
        if (!ensureMedia()) return
        if (project.selectionIsEverything) {
            announce(getString(R.string.msg_cannot_remove_all)); return
        }
        val start = project.selectionStartMs
        val length = project.selectionEndMs - start
        project.cutSelection()
        afterEdit(start, getString(R.string.msg_cut_done_fmt, TimeFormat.spoken(length), TimeFormat.spoken(project.totalMs)))
    }

    private fun onCopy() {
        if (!ensureMedia()) return
        val length = project.copySelection()
        updateClipboardText()
        updateEnabled()
        announce(getString(R.string.msg_copy_done_fmt, TimeFormat.spoken(length)))
    }

    private fun onDelete() {
        if (!ensureMedia()) return
        if (project.selectionIsEverything) {
            announce(getString(R.string.msg_cannot_remove_all)); return
        }
        val start = project.selectionStartMs
        val length = project.selectionEndMs - start
        project.deleteSelection()
        afterEdit(start, getString(R.string.msg_delete_done_fmt, TimeFormat.spoken(length), TimeFormat.spoken(project.totalMs)))
    }

    private fun onPaste() {
        if (!ensureMedia()) return
        val pos = currentTimelineMs()
        val length = project.clipboardMs
        if (!project.pasteAt(pos)) {
            announce(getString(R.string.msg_paste_empty)); return
        }
        afterEdit(
            pos,
            getString(
                R.string.msg_paste_done_fmt,
                TimeFormat.spoken(length), TimeFormat.spoken(pos), TimeFormat.spoken(project.totalMs)
            )
        )
    }

    private fun onKeepOnly() {
        if (!ensureMedia()) return
        if (project.selectionIsEverything) {
            announce("Nothing to trim. Set a start or an end first."); return
        }
        project.keepOnlySelection()
        afterEdit(0L, getString(R.string.msg_keep_done_fmt, TimeFormat.spoken(project.totalMs)))
    }

    private fun onTrimBefore() {
        if (!ensureMedia()) return
        val pos = currentTimelineMs()
        if (!project.trimBefore(pos)) {
            announce("Move to a position inside the file first."); return
        }
        afterEdit(0L, "Removed everything before ${TimeFormat.spoken(pos)}. Total length is now ${TimeFormat.spoken(project.totalMs)}.")
    }

    private fun onTrimAfter() {
        if (!ensureMedia()) return
        val pos = currentTimelineMs()
        if (!project.trimAfter(pos)) {
            announce("Move to a position inside the file first."); return
        }
        afterEdit(project.totalMs, "Removed everything after ${TimeFormat.spoken(pos)}. Total length is now ${TimeFormat.spoken(project.totalMs)}.")
    }

    private fun onUndo() {
        if (!ensureMedia()) return
        val pos = currentTimelineMs()
        if (!project.undo()) {
            announce(getString(R.string.msg_nothing_to_undo)); return
        }
        afterEdit(minOf(pos, project.totalMs), getString(R.string.msg_undo_done_fmt, TimeFormat.spoken(project.totalMs)))
    }

    private fun afterEdit(positionMs: Long, message: String) {
        viewModel.resumePositionMs = positionMs
        rebuildPlaylist(positionMs)
        refreshAll()
        announce(message)
    }

    private fun onAddMark() {
        if (!ensureMedia()) return
        val pos = currentTimelineMs()
        when (project.addMark(pos)) {
            MarkResult.ADDED -> {
                refreshMarks()
                val number = project.marks.indexOf(pos) + 1
                announce(getString(R.string.msg_mark_added_fmt, number, TimeFormat.spoken(pos)))
            }
            MarkResult.DUPLICATE -> announce(getString(R.string.msg_mark_duplicate))
            MarkResult.TOO_CLOSE_TO_EDGE -> announce(getString(R.string.msg_mark_edge))
        }
    }

    private fun showMarksEveryDialog() {
        if (!ensureMedia()) return
        val labels = arrayOf("10 seconds", "30 seconds", "1 minute", "5 minutes", "10 minutes")
        val values = longArrayOf(10_000L, 30_000L, 60_000L, 300_000L, 600_000L)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add a mark every…")
            .setItems(labels) { _, which ->
                val added = project.addMarksEvery(values[which])
                refreshMarks()
                announce(
                    if (added == 0) "No marks were added. The file is shorter than ${labels[which]}."
                    else "Added $added marks, one every ${labels[which]}."
                )
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun onSplit() {
        if (!ensureMedia()) return
        if (project.marks.isEmpty()) {
            announce(getString(R.string.msg_need_marks)); return
        }
        val base = baseName()
        val jobs = project.splitParts().mapIndexed { i, part ->
            timelineJob(part, "${base}_part${i + 1}", ExportMode.NORMAL)
        }
        startJobs(jobs)
    }

    // ============================================================ save variants

    private fun saveEdited(mode: ExportMode) {
        if (!ensureMedia()) return
        val label = when (mode) {
            ExportMode.AUDIO_ONLY -> "${baseName()}_audio"
            ExportMode.NO_AUDIO -> "${baseName()}_nosound"
            ExportMode.NORMAL -> "${baseName()}_edited"
        }
        val gain = viewModel.saveVolumePercent / 100f
        startJobs(listOf(timelineJob(project.segments, label, mode, FxOptions(gain = gain))))
    }

    private fun saveSelectionAsNew() {
        if (!ensureMedia()) return
        val part = project.slice(project.selectionStartMs, project.selectionEndMs)
        if (part.isEmpty()) {
            announce("The selection is empty."); return
        }
        startJobs(listOf(timelineJob(part, "${baseName()}_trim", ExportMode.NORMAL)))
    }

    private fun compressPlan(): CompressPlan {
        val video = project.isVideo
        val seconds = (project.totalMs / 1000.0).coerceAtLeast(1.0)
        var audioKbps = Prefs.compressAudioKbps
        var mono = Prefs.compressAudioMono
        var videoKbps = Prefs.compressVideoKbps
        var removeAudio = false
        if (video) {
            when (Prefs.compressVideoSound) {
                "64" -> { audioKbps = 64; mono = false }
                "32" -> { audioKbps = 32; mono = true }
                "none" -> removeAudio = true
                else -> { audioKbps = 128; mono = false }
            }
        }
        val targetMb = Prefs.compressTargetMb
        if (targetMb > 0) {
            val totalBps = targetMb * 8.0 * 1024.0 * 1024.0 / seconds
            if (video) {
                val audioBps = if (removeAudio) 0.0 else audioKbps * 1000.0
                videoKbps = ((totalBps - audioBps) / 1000.0).toInt().coerceIn(100, 40_000)
            } else {
                audioKbps = (totalBps / 1000.0).toInt().coerceIn(8, 320)
            }
        }
        return CompressPlan(audioKbps, mono, videoKbps, removeAudio, if (video) Prefs.compressVideoHeight else 0)
    }

    private fun applyCompressPreset(kind: String) {
        if (project.isVideo) {
            when (kind) {
                "smallest" -> { Prefs.compressVideoHeight = 240; Prefs.compressVideoKbps = 250; Prefs.compressVideoSound = "32" }
                "small" -> { Prefs.compressVideoHeight = 360; Prefs.compressVideoKbps = 500; Prefs.compressVideoSound = "32" }
                else -> { Prefs.compressVideoHeight = 720; Prefs.compressVideoKbps = 2000; Prefs.compressVideoSound = "64" }
            }
        } else {
            when (kind) {
                "smallest" -> { Prefs.compressAudioKbps = 16; Prefs.compressAudioMono = true }
                "small" -> { Prefs.compressAudioKbps = 32; Prefs.compressAudioMono = true }
                else -> { Prefs.compressAudioKbps = 96; Prefs.compressAudioMono = false }
            }
        }
        Prefs.compressTargetMb = 0
        refreshPageContent()
        announce("Preset chosen: $kind. ${binding.tvCompressInfo.text}")
    }

    private fun saveCompressed() {
        if (!ensureMedia()) return
        val ctx = requireContext().applicationContext
        val video = project.isVideo
        val plan = compressPlan()
        val encoder: () -> Codec.EncoderFactory = {
            val b = DefaultEncoderFactory.Builder(ctx)
            if (video) {
                b.setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(plan.videoKbps * 1000).build()
                )
            }
            b.setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(plan.audioKbps * 1000).build())
            b.build()
        }
        val mode = if (plan.removeAudio) ExportMode.NO_AUDIO else ExportMode.NORMAL
        AppLog.i("Compress", "audio ${plan.audioKbps}k mono=${plan.mono} video ${plan.videoKbps}k ${plan.height}p")
        startJobs(
            listOf(
                timelineJob(
                    project.segments, "${baseName()}_small", mode,
                    FxOptions(height = plan.height, mono = plan.mono), encoder
                )
            )
        )
    }

    private fun saveWithEffects() {
        if (!ensureMedia()) return
        val vm = viewModel
        val fx = FxOptions(
            echoOn = vm.fxEchoOn, echoDelayMs = vm.fxEchoDelayMs, echoLevel = vm.fxEchoLevel,
            fadeInMs = vm.fxFadeInMs, fadeOutMs = vm.fxFadeOutMs,
            rotation = if (project.isVideo) vm.fxRotation else 0,
            flipH = project.isVideo && vm.fxFlipH, flipV = project.isVideo && vm.fxFlipV,
            brightness = if (project.isVideo) vm.fxBrightness else 0,
            contrast = if (project.isVideo) vm.fxContrast else 0
        )
        AppLog.i("Effects", "echo=${fx.echoOn} fade=${fx.fadeInMs}/${fx.fadeOutMs} rot=${fx.rotation}")
        startJobs(listOf(timelineJob(project.segments, "${baseName()}_fx", ExportMode.NORMAL, fx)))
    }

    private fun saveFrame() {
        if (!ensureMedia() || !project.isVideo) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterPermission = { saveFrame() }
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val (index, offset) = project.locate(currentTimelineMs())
        val seg = project.segments[index]
        val src = project.source(seg.sourceId)
        val atMs = seg.startMs + offset
        val app = requireContext().applicationContext
        val name = "${baseName()}_frame_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        announce("Saving a picture…")
        viewLifecycleOwner.lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(app, Uri.parse(src.uriString))
                    val bmp = r.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bmp == null) null else MediaSaver.saveBitmap(app, bmp, name)
                } catch (e: Exception) {
                    AppLog.e("Frame", "Could not read the frame", e)
                    null
                } finally {
                    try {
                        r.release()
                    } catch (_: Exception) {
                    }
                }
            }
            announce(
                if (where != null) "Saved a picture of the current frame in $where."
                else "Could not save the picture."
            )
        }
    }

    private fun saveMerged() {
        if (busy) return
        val items = viewModel.mergeItems.toList()
        if (items.size < 2) {
            announce("Add at least two files to merge."); return
        }
        val video = isVideoMode
        val name = items.first().name.substringBeforeLast('.', items.first().name)
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifEmpty { "media" }.take(30)
        val job = ExportJob("${name}_merged", video, {
            val list = items.map {
                editedItem(it.uriString, it.durationMs, 0L, it.durationMs, removeVideo = !video, removeAudio = false)
            }
            Composition.Builder(listOf(EditedMediaItemSequence(list))).build()
        })
        AppLog.i("Merge", "Merging ${items.size} files")
        startJobs(listOf(job))
    }

    private fun saveMixed() {
        if (!ensureMedia()) return
        val overlay = viewModel.mixOverlay
        if (overlay == null) {
            announce("Choose the sound to add first."); return
        }
        val segs = project.segments
        val total = project.totalMs
        val outVideo = project.isVideo
        val baseGain = Prefs.mixBaseVolume / 100f
        val overlayGain = Prefs.mixOverlayVolume / 100f
        val job = ExportJob("${baseName()}_mix", outVideo, {
            val baseItems = segs.map { seg ->
                val src = project.source(seg.sourceId)
                editedItem(src.uriString, src.durationMs, seg.startMs, seg.endMs, removeVideo = !outVideo, removeAudio = false, fx = FxOptions(gain = baseGain))
            }
            val overlayItems = ArrayList<EditedMediaItem>()
            var covered = 0L
            while (covered < total && overlayItems.size < 300) {
                val len = minOf(overlay.durationMs, total - covered)
                if (len < 100L) break
                overlayItems.add(
                    editedItem(overlay.uriString, overlay.durationMs, 0L, len, removeVideo = true, removeAudio = false, fx = FxOptions(gain = overlayGain))
                )
                covered += len
            }
            Composition.Builder(
                listOf(EditedMediaItemSequence(baseItems), EditedMediaItemSequence(overlayItems))
            ).build()
        })
        AppLog.i("Mix", "Mixing: base ${Prefs.mixBaseVolume}%, added ${Prefs.mixOverlayVolume}%")
        startJobs(listOf(job))
    }

    // ============================================================ merge / mix files

    private fun addMergeFiles(uris: List<Uri>) {
        announce(getString(R.string.msg_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            var added = 0
            var skipped = 0
            for (uri in uris) {
                persist(uri)
                val info = withContext(Dispatchers.IO) { readFileInfo(uri) }
                if (info == null || (isVideoMode && !info.hasVideo)) {
                    skipped++
                    continue
                }
                viewModel.mergeItems.add(MergeItem(uri.toString(), info.name, info.durationMs, info.hasVideo))
                added++
            }
            refreshMerge()
            announce(
                "Added $added ${if (added == 1) "file" else "files"}." +
                    if (skipped > 0) " $skipped could not be used." else ""
            )
        }
    }

    private fun pickMixSound(uri: Uri) {
        persist(uri)
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { readFileInfo(uri) }
            if (info == null) {
                announce(getString(R.string.msg_cannot_read)); return@launch
            }
            viewModel.mixOverlay = MergeItem(uri.toString(), info.name, info.durationMs, info.hasVideo)
            updateMixInfo()
            announce("Sound chosen, ${TimeFormat.spoken(info.durationMs)} long.")
        }
    }

    private fun persist(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            requireContext().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun refreshMerge() {
        val bd = _binding ?: return
        val box = bd.mergeContainer
        box.removeAllViews()
        val items = viewModel.mergeItems
        bd.tvMergeEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEachIndexed { i, item ->
            val row = ItemMergeBinding.inflate(layoutInflater, box, false)
            val number = i + 1
            row.tvMergeName.text = "${item.name} (${TimeFormat.format(item.durationMs)})"
            row.tvMergeName.contentDescription =
                "Number $number: ${item.name}, ${TimeFormat.spoken(item.durationMs)}"
            row.btnMergeUp.contentDescription = "Move number $number up"
            row.btnMergeDown.contentDescription = "Move number $number down"
            row.btnMergeRemove.contentDescription = "Remove number $number"
            row.btnMergeUp.isEnabled = i > 0 && !busy
            row.btnMergeDown.isEnabled = i < items.lastIndex && !busy
            row.btnMergeRemove.isEnabled = !busy
            row.btnMergeUp.setOnClickListener {
                java.util.Collections.swap(items, i, i - 1)
                refreshMerge()
                announce("Moved up. Now number $i.")
            }
            row.btnMergeDown.setOnClickListener {
                java.util.Collections.swap(items, i, i + 1)
                refreshMerge()
                announce("Moved down. Now number ${i + 2}.")
            }
            row.btnMergeRemove.setOnClickListener {
                items.removeAt(i)
                refreshMerge()
                announce("Removed.")
            }
            box.addView(row.root)
        }
        updateEnabled()
    }

    private fun updateMixInfo() {
        val bd = _binding ?: return
        val o = viewModel.mixOverlay
        bd.tvMixInfo.text = if (o == null) "No sound chosen yet."
        else "Added sound: ${o.name} (${TimeFormat.format(o.durationMs)})"
    }

    // ============================================================ per-page content

    private fun refreshPageContent() {
        val bd = _binding ?: return
        val ctx = context ?: return
        val vm = viewModel
        val noop = { updateCompressInfo() }

        bd.mixSettings.removeAllViews()
        SettingsUi.mixDefaults(ctx, bd.mixSettings) {}
        updateMixInfo()

        bd.compressSettings.removeAllViews()
        if (isVideoMode) SettingsUi.compressVideo(ctx, bd.compressSettings, noop)
        else SettingsUi.compressAudio(ctx, bd.compressSettings, noop)
        updateCompressInfo()

        val fxBox = bd.effectsSettings
        fxBox.removeAllViews()
        SettingsUi.heading(ctx, fxBox, "Echo")
        SettingsUi.addSwitch(ctx, fxBox, "Add an echo to the sound", "",
            { vm.fxEchoOn }, { vm.fxEchoOn = it })
        SettingsUi.addChoice(ctx, fxBox, "Echo strength",
            listOf("Low echo", "Medium echo", "High echo"), listOf("low", "medium", "high"),
            { vm.fxEchoLevel }, { vm.fxEchoLevel = it })
        SettingsUi.addNumber(ctx, fxBox, "Echo delay", "milliseconds", 20, 2000,
            { vm.fxEchoDelayMs }, { vm.fxEchoDelayMs = it })
        SettingsUi.heading(ctx, fxBox, "Fade")
        val fadeValues = listOf(0, 1000, 2000, 3000, 5000)
        val fadeLabels = listOf("None", "1 second", "2 seconds", "3 seconds", "5 seconds")
        SettingsUi.addChoice(ctx, fxBox, "Fade in at the start", fadeLabels, fadeValues,
            { vm.fxFadeInMs }, { vm.fxFadeInMs = it })
        SettingsUi.addChoice(ctx, fxBox, "Fade out at the end", fadeLabels, fadeValues,
            { vm.fxFadeOutMs }, { vm.fxFadeOutMs = it })
        if (isVideoMode) {
            SettingsUi.heading(ctx, fxBox, "Picture")
            SettingsUi.addChoice(ctx, fxBox, "Rotate",
                listOf("Do not rotate", "Right, 90 degrees clockwise", "Upside down, 180 degrees", "Left, 90 degrees counter-clockwise"),
                listOf(0, 270, 180, 90),
                { vm.fxRotation }, { vm.fxRotation = it })
            SettingsUi.addSwitch(ctx, fxBox, "Mirror left to right", "",
                { vm.fxFlipH }, { vm.fxFlipH = it })
            SettingsUi.addSwitch(ctx, fxBox, "Flip top to bottom", "",
                { vm.fxFlipV }, { vm.fxFlipV = it })
            SettingsUi.addChoice(ctx, fxBox, "Brightness",
                listOf("Much darker", "Darker", "Normal", "Brighter", "Much brighter"),
                listOf(-30, -15, 0, 15, 30),
                { vm.fxBrightness }, { vm.fxBrightness = it })
            SettingsUi.addChoice(ctx, fxBox, "Contrast",
                listOf("Lower", "Normal", "Higher", "Much higher"),
                listOf(-30, 0, 25, 50),
                { vm.fxContrast }, { vm.fxContrast = it })
        }

        bd.toolsSettings.removeAllViews()
        val levels = listOf(50, 75, 100, 125, 150, 200)
        SettingsUi.addChoice(ctx, bd.toolsSettings, "Volume",
            levels.map { if (it == 100) "Normal (100%)" else "$it%" }, levels,
            { vm.saveVolumePercent }, { vm.saveVolumePercent = it })
        SettingsUi.note(ctx, bd.toolsSettings, "Applies when you press Save edited file in the Edit tab.")

        updateSilenceInfo()
        refreshMerge()
    }

    private fun updateCompressInfo() {
        val bd = _binding ?: return
        if (project.isEmpty) {
            bd.tvCompressInfo.text = "Open a file first."
            bd.tvCompressInfo.contentDescription = null
            return
        }
        val plan = compressPlan()
        val seconds = project.totalMs / 1000.0
        val video = project.isVideo
        val kbps = (if (video) plan.videoKbps else 0) + (if (plan.removeAudio) 0 else plan.audioKbps)
        val bytes = kbps * 1000.0 / 8.0 * seconds
        val mb = bytes / (1024.0 * 1024.0)
        val size = if (mb < 1.0) String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else String.format(Locale.US, "%.1f MB", mb)
        val detail = if (video) {
            "video ${plan.videoKbps} kbps" + if (plan.removeAudio) ", no sound" else ", sound ${plan.audioKbps} kbps"
        } else {
            "${plan.audioKbps} kbps" + if (plan.mono) ", mono" else ""
        }
        val text = "Length ${TimeFormat.format(project.totalMs)}. Estimated size: about $size ($detail)."
        bd.tvCompressInfo.text = text
        bd.tvCompressInfo.contentDescription =
            "Length ${TimeFormat.spoken(project.totalMs)}. Estimated size: about $size ($detail)."
    }

    // ============================================================ silence removal

    private fun onRemoveSilence() {
        if (!ensureMedia()) return
        val threshold = Prefs.silenceThresholdDb.toFloat()
        val minMs = Prefs.silenceMinMs.toLong()
        val padMs = Prefs.silencePadMs.toLong()
        val segs = project.segments
        val sources = project.sources.associateBy { it.id }
        val appContext = requireContext().applicationContext
        val total = segs.sumOf { it.length }.coerceAtLeast(1L)

        player?.pause()
        setBusy(true, "Looking for silence…")
        announce("Looking for silence. Please wait.")

        analysisJob = viewLifecycleOwner.lifecycleScope.launch {
            var sawNoAudio = false
            val ranges = withContext(Dispatchers.Default) {
                val out = ArrayList<Pair<Long, Long>>()
                var offset = 0L
                for (seg in segs) {
                    if (!isActive) break
                    val src = sources.getValue(seg.sourceId)
                    val base = offset
                    val found = SilenceDetector.detect(
                        appContext, Uri.parse(src.uriString),
                        seg.startMs, seg.endMs, threshold, minMs, padMs,
                        onProgress = { p ->
                            val overall = ((base + seg.length * p / 100) * 100 / total).toInt()
                            ui.post { onAnalysisProgress(overall) }
                        },
                        isCancelled = { !isActive }
                    )
                    if (found == null) {
                        sawNoAudio = true
                    } else {
                        for ((s, e) in found) out.add((base + s - seg.startMs) to (base + e - seg.startMs))
                    }
                    offset += seg.length
                }
                out
            }
            analysisJob = null
            endBusy()
            if (ranges.isEmpty()) {
                announce(
                    if (sawNoAudio) "This file has no audio track, so there is no silence to remove."
                    else "No silence found with the current settings. You can change them in Settings."
                )
                return@launch
            }
            val pos = currentTimelineMs()
            val removed = project.removeRanges(ranges)
            if (removed <= 0L) {
                announce("No silence found with the current settings.")
            } else {
                AppLog.i("Silence", "Removed ${removed} ms in ${ranges.size} places")
                afterEdit(
                    minOf(pos, project.totalMs),
                    "Removed ${TimeFormat.spoken(removed)} of silence in ${ranges.size} places. " +
                        "Total length is now ${TimeFormat.spoken(project.totalMs)}. You can undo this."
                )
            }
        }
    }

    private fun onAnalysisProgress(percent: Int) {
        val bd = _binding ?: return
        if (!busy || exporting) return
        bd.progressBar.setProgressCompat(percent.coerceIn(0, 100), true)
        bd.tvProgress.text = "Looking for silence: $percent percent"
    }

    private fun updateSilenceInfo() {
        val bd = _binding ?: return
        bd.tvSilenceInfo.text =
            "Removes parts quieter than minus ${-Prefs.silenceThresholdDb} decibels that last longer than " +
                "${Prefs.silenceMinMs / 1000.0} seconds. Change this in Settings, Audio or Video."
    }

    // ============================================================ go to time

    private fun showGoToTimeDialog() {
        if (!ensureMedia()) return
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.dialog_goto_hint)
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_goto_title)
            .setMessage(R.string.dialog_goto_message)
            .setView(frame)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val ms = TimeFormat.parse(input.text?.toString().orEmpty())
                if (ms == null) {
                    announce(getString(R.string.msg_invalid_time))
                } else {
                    val target = ms.coerceIn(0L, project.totalMs)
                    seekToTimeline(target)
                    announce(TimeFormat.spoken(target))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ============================================================= file loading

    private fun onFilePicked(uri: Uri, insert: Boolean) {
        persist(uri)
        if (!insert && project.isEdited) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_discard_title)
                .setMessage(R.string.dialog_discard_message)
                .setPositiveButton(R.string.dialog_discard_confirm) { _, _ -> loadFile(uri, false) }
                .setNegativeButton(R.string.dialog_keep_editing) { _, _ -> pendingAutoSilence = false }
                .show()
        } else {
            loadFile(uri, insert)
        }
    }

    private fun loadFile(uri: Uri, insert: Boolean) {
        announce(getString(R.string.msg_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { readFileInfo(uri) }
            if (info == null) {
                pendingAutoSilence = false
                AppLog.w("Editor", "A file could not be read")
                announce(getString(R.string.msg_cannot_read))
                return@launch
            }
            if (insert) {
                if (project.isVideo && !info.hasVideo) {
                    announce(getString(R.string.msg_cannot_insert_audio_in_video))
                    return@launch
                }
                val pos = currentTimelineMs()
                val src = project.addSource(uri.toString(), info.name, info.durationMs, info.hasVideo)
                project.insertSourceAt(pos, src)
                afterEdit(
                    pos,
                    getString(R.string.msg_inserted_fmt, info.name, TimeFormat.spoken(pos), TimeFormat.spoken(project.totalMs))
                )
            } else {
                project.newProject(uri.toString(), info.name, info.durationMs, info.hasVideo)
                viewModel.resumePositionMs = 0L
                rebuildPlaylist(0L)
                refreshAll()
                refreshPageContent()
                announce(getString(R.string.msg_loaded_fmt, info.name, TimeFormat.spoken(info.durationMs)))
                if (pendingAutoSilence) {
                    pendingAutoSilence = false
                    onRemoveSilence()
                }
            }
        }
    }

    private fun readFileInfo(uri: Uri): FileInfo? {
        val ctx = context ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (duration <= 0L || (!hasVideo && !hasAudio)) null
            else FileInfo(queryDisplayName(ctx, uri) ?: "media", duration, hasVideo)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        var name: String? = null
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (_: Exception) {
        }
        return name ?: uri.lastPathSegment
    }

    // ================================================================== export

    private fun editedItem(
        uri: String,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        removeVideo: Boolean,
        removeAudio: Boolean,
        fx: FxOptions = FxOptions(),
        isFirst: Boolean = true,
        isLast: Boolean = true
    ): EditedMediaItem {
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            if (startMs > 0L || endMs < durationMs) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
            }
        }.build()
        val builder = EditedMediaItem.Builder(mediaItem)
        if (removeVideo) builder.setRemoveVideo(true)
        if (removeAudio) builder.setRemoveAudio(true)

        val audioFx = ArrayList<AudioProcessor>()
        if (!removeAudio) {
            val fadeIn = if (isFirst) fx.fadeInMs else 0
            val fadeOut = if (isLast) fx.fadeOutMs else 0
            if (fadeIn > 0 || fadeOut > 0) audioFx.add(FadeAudioProcessor(fadeIn, fadeOut, endMs - startMs))
            if (fx.echoOn) audioFx.add(EchoAudioProcessor(fx.echoDelayMs, fx.echoLevel))
            if (fx.gain != 1f) audioFx.add(GainAudioProcessor(fx.gain))
            if (fx.mono) audioFx.add(MonoAudioProcessor())
        }
        val videoFx = ArrayList<Effect>()
        if (!removeVideo) {
            if (fx.rotation != 0 || fx.flipH || fx.flipV) {
                videoFx.add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(fx.rotation.toFloat())
                        .setScale(if (fx.flipH) -1f else 1f, if (fx.flipV) -1f else 1f)
                        .build()
                )
            }
            if (fx.brightness != 0) videoFx.add(Brightness(fx.brightness / 100f))
            if (fx.contrast != 0) videoFx.add(Contrast(fx.contrast / 100f))
            if (fx.height > 0) videoFx.add(Presentation.createForHeight(fx.height))
        }
        if (audioFx.isNotEmpty() || videoFx.isNotEmpty()) builder.setEffects(Effects(audioFx, videoFx))
        return builder.build()
    }

    private fun timelineJob(
        segs: List<Segment>,
        label: String,
        mode: ExportMode,
        fx: FxOptions = FxOptions(),
        encoder: (() -> Codec.EncoderFactory)? = null
    ): ExportJob {
        val outVideo = project.isVideo && mode != ExportMode.AUDIO_ONLY
        return ExportJob(label, outVideo, {
            val items = segs.mapIndexed { i, seg ->
                val src = project.source(seg.sourceId)
                editedItem(
                    src.uriString, src.durationMs, seg.startMs, seg.endMs,
                    removeVideo = !outVideo, removeAudio = mode == ExportMode.NO_AUDIO,
                    fx = fx, isFirst = i == 0, isLast = i == segs.lastIndex
                )
            }
            Composition.Builder(listOf(EditedMediaItemSequence(items))).build()
        }, encoder)
    }

    private fun startJobs(jobs: List<ExportJob>) {
        if (busy || jobs.isEmpty()) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterPermission = { startJobs(jobs) }
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        player?.pause()
        exporting = true
        savedNames.clear()
        savedWhere = ""
        totalParts = jobs.size
        lastProgressBucket = -1
        setBusy(true, getString(R.string.msg_export_started))
        announce(getString(R.string.msg_export_started))
        AppLog.i("Export", "Starting ${jobs.size} job(s)")

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        exportJob(0, jobs, stamp)
    }

    private fun exportJob(index: Int, jobs: List<ExportJob>, stamp: String) {
        if (index >= jobs.size) {
            finishExport()
            return
        }
        val ctx = context ?: return
        currentPart = index
        val job = jobs[index]
        val ext = if (job.outputIsVideo) "mp4" else "m4a"
        val displayName = "${job.baseName}_$stamp.$ext"
        val temp = File(ctx.cacheDir, "export_${System.nanoTime()}.mp4")
        currentTemp = temp

        val composition = try {
            job.build()
        } catch (e: Exception) {
            AppLog.e("Export", "Could not prepare the export", e)
            failExport(getString(R.string.msg_export_failed))
            return
        }
        val tb = Transformer.Builder(ctx).setAudioMimeType(MimeTypes.AUDIO_AAC)
        job.encoder?.let { tb.setEncoderFactory(it()) }
        val t = tb
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    ui.removeCallbacks(progressPoller)
                    transformer = null
                    val appContext = context?.applicationContext ?: return
                    viewLifecycleOwner.lifecycleScope.launch {
                        val where = withContext(Dispatchers.IO) {
                            MediaSaver.save(appContext, temp, job.outputIsVideo, displayName)
                        }
                        temp.delete()
                        currentTemp = null
                        if (where == null) {
                            failExport(getString(R.string.msg_save_failed))
                        } else {
                            savedNames.add(displayName)
                            savedWhere = where
                            AppLog.i("Export", "Saved a file in $where")
                            exportJob(index + 1, jobs, stamp)
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    ui.removeCallbacks(progressPoller)
                    transformer = null
                    temp.delete()
                    currentTemp = null
                    AppLog.e("Export", "Export failed (error code ${exportException.errorCode})", exportException)
                    failExport(getString(R.string.msg_export_failed))
                }
            })
            .build()
        transformer = t
        t.start(composition, temp.absolutePath)
        ui.removeCallbacks(progressPoller)
        ui.post(progressPoller)
    }

    private fun onExportProgress(partPercent: Int) {
        val bd = _binding ?: return
        val overall = ((currentPart * 100 + partPercent) / totalParts.coerceAtLeast(1)).coerceIn(0, 100)
        bd.progressBar.setProgressCompat(overall, true)
        bd.tvProgress.text = getString(R.string.msg_export_progress_fmt, currentPart + 1, totalParts, overall)
        val bucket = overall / 25
        if (bucket != lastProgressBucket) {
            lastProgressBucket = bucket
            if (bucket > 0) announce(bd.tvProgress.text.toString())
        }
    }

    private fun finishExport() {
        endBusy()
        val message = if (savedNames.size == 1) {
            getString(R.string.msg_saved_one_fmt, savedNames[0], savedWhere)
        } else {
            getString(R.string.msg_saved_many_fmt, savedNames.size, savedWhere)
        }
        announce(message)
    }

    private fun failExport(message: String) {
        endBusy()
        announce(message)
    }

    private fun cancelBusy() {
        if (!busy) return
        if (exporting) {
            ui.removeCallbacks(progressPoller)
            transformer?.cancel()
            transformer = null
            currentTemp?.delete()
            currentTemp = null
            endBusy()
            announce(getString(R.string.msg_export_cancelled))
        } else {
            analysisJob?.cancel()
            analysisJob = null
            endBusy()
            announce("Cancelled.")
        }
    }

    private fun setBusy(value: Boolean, text: String) {
        busy = value
        val bd = _binding ?: return
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bd.progressBar.setProgressCompat(0, false)
        bd.tvProgress.text = text
        bd.progressGroup.visibility = View.VISIBLE
        bd.pagesScroll.smoothScrollTo(0, 0)
        updateEnabled()
        refreshMerge()
    }

    private fun endBusy() {
        busy = false
        exporting = false
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding?.progressGroup?.visibility = View.GONE
        updateEnabled()
        refreshMerge()
    }

    private fun baseName(): String {
        val raw = project.sources.firstOrNull()?.name ?: "media"
        val noExt = raw.substringBeforeLast('.', raw)
        val clean = noExt.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_')
        return clean.ifEmpty { "media" }.take(40)
    }

    // ===================================================================== UI

    private fun refreshAll() {
        updateMediaInfo()
        updateTimeViews(force = true)
        updateSelectionViews()
        updateClipboardText()
        updateJumpLabels()
        updateSilenceInfo()
        updateCompressInfo()
        refreshMarks()
        updatePlayPauseButton()
        updateEnabled()
    }

    private fun updateMediaInfo() {
        val bd = _binding ?: return
        val first = project.sources.firstOrNull()
        if (first == null || project.isEmpty) {
            bd.tvMediaInfo.text = getString(
                if (isVideoMode) R.string.media_info_none_video else R.string.media_info_none_audio
            )
            bd.tvMediaInfo.contentDescription = null
            return
        }
        val kind = getString(if (project.isVideo) R.string.kind_video else R.string.kind_audio)
        val extra = if (project.sources.size > 1) getString(R.string.media_info_extra_fmt, project.sources.size - 1) else ""
        bd.tvMediaInfo.text = getString(R.string.media_info_fmt, first.name, kind, TimeFormat.format(project.totalMs)) + extra
        bd.tvMediaInfo.contentDescription =
            getString(R.string.media_info_fmt, first.name, kind, TimeFormat.spoken(project.totalMs)) + extra
    }

    private fun touchExplorationOn(): Boolean {
        val am = context?.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return am?.isTouchExplorationEnabled == true
    }

    private fun updateTimeViews(pos: Long = currentTimelineMs(), force: Boolean = false, moveSeekBar: Boolean = true) {
        val bd = _binding ?: return
        val playing = player?.isPlaying == true
        val minStep = if (playing && touchExplorationOn()) 1000L else 100L
        if (!force && abs(pos - lastShownMs) < minStep) return
        lastShownMs = pos

        val total = project.totalMs
        bd.tvCurrentPosition.text = getString(R.string.position_fmt, TimeFormat.format(pos))
        bd.tvCurrentPosition.contentDescription = getString(R.string.position_fmt, TimeFormat.spoken(pos))
        bd.tvDuration.text = getString(R.string.total_fmt, TimeFormat.format(total))
        bd.tvDuration.contentDescription = getString(R.string.total_fmt, TimeFormat.spoken(total))
        if (moveSeekBar && !isSeeking && total > 0L) {
            bd.seekBar.progress = (pos * SEEK_MAX / total).toInt().coerceIn(0, SEEK_MAX)
        }
        ViewCompat.setStateDescription(bd.seekBar, TimeFormat.spoken(pos) + " / " + TimeFormat.spoken(total))
    }

    private fun updateSelectionViews() {
        val bd = _binding ?: return
        val s = project.selectionStartMs
        val e = project.selectionEndMs
        bd.tvStartTime.text = getString(R.string.start_fmt, TimeFormat.format(s))
        bd.tvStartTime.contentDescription = getString(R.string.start_fmt, TimeFormat.spoken(s))
        bd.tvEndTime.text = getString(R.string.end_fmt, TimeFormat.format(e))
        bd.tvEndTime.contentDescription = getString(R.string.end_fmt, TimeFormat.spoken(e))
        bd.tvSelectionLength.text = getString(R.string.selection_length_fmt, TimeFormat.format(e - s))
        bd.tvSelectionLength.contentDescription = getString(R.string.selection_length_fmt, TimeFormat.spoken(e - s))
        bd.tvTrimInfo.text =
            "Start ${TimeFormat.format(s)}, end ${TimeFormat.format(e)}, length ${TimeFormat.format(e - s)}"
        bd.tvTrimInfo.contentDescription =
            "Start ${TimeFormat.spoken(s)}. End ${TimeFormat.spoken(e)}. Length ${TimeFormat.spoken(e - s)}."
    }

    private fun updateClipboardText() {
        val bd = _binding ?: return
        if (project.clipboard.isEmpty()) {
            bd.tvClipboard.text = getString(R.string.clipboard_empty)
            bd.tvClipboard.contentDescription = null
        } else {
            val ms = project.clipboardMs
            bd.tvClipboard.text = getString(R.string.clipboard_fmt, TimeFormat.format(ms))
            bd.tvClipboard.contentDescription = getString(R.string.clipboard_fmt, TimeFormat.spoken(ms))
        }
    }

    private fun getSeekSeconds(): Int =
        (if (isVideoMode) Prefs.jumpSecondsVideo else Prefs.jumpSeconds).coerceIn(1, 60)

    private fun updateJumpLabels() {
        val bd = _binding ?: return
        val s = getSeekSeconds()
        bd.btnRewind.text = getString(R.string.back_fmt, s)
        bd.btnRewind.contentDescription = getString(R.string.cd_back_fmt, s)
        bd.btnForward.text = getString(R.string.forward_fmt, s)
        bd.btnForward.contentDescription = getString(R.string.cd_forward_fmt, s)
    }

    private fun updatePlayPauseButton() {
        val bd = _binding ?: return
        val playing = player?.isPlaying == true
        bd.btnPlayPause.text = getString(if (playing) R.string.pause else R.string.play)
        bd.btnPlayPause.icon = AppCompatResources.getDrawable(
            requireContext(), if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun refreshMarks() {
        val bd = _binding ?: return
        val container = bd.marksContainer
        container.removeAllViews()
        val marks = project.marks
        bd.tvMarksEmpty.visibility = if (marks.isEmpty()) View.VISIBLE else View.GONE
        marks.forEachIndexed { i, ms ->
            val row = ItemMarkBinding.inflate(layoutInflater, container, false)
            val number = i + 1
            row.tvMark.text = getString(R.string.mark_row_fmt, number, TimeFormat.format(ms))
            row.tvMark.contentDescription = getString(R.string.cd_mark_row_fmt, number, TimeFormat.spoken(ms))
            row.btnGoToMark.contentDescription = getString(R.string.cd_mark_go_fmt, number)
            row.btnGoToMark.setOnClickListener {
                seekToTimeline(ms)
                announce(TimeFormat.spoken(ms))
            }
            row.btnDeleteMark.contentDescription = getString(R.string.cd_mark_delete_fmt, number)
            row.btnDeleteMark.setOnClickListener {
                project.removeMark(i)
                refreshMarks()
                announce(getString(R.string.msg_mark_removed_fmt, number))
            }
            row.btnGoToMark.isEnabled = !busy
            row.btnDeleteMark.isEnabled = !busy
            container.addView(row.root)
        }
        bd.btnSplit.text = getString(R.string.split_save_fmt, marks.size + 1)
        updateEnabled()
    }

    private fun updateEnabled() {
        val bd = _binding ?: return
        val idle = !busy
        val has = idle && !project.isEmpty
        val needsMedia: List<MaterialButton> = listOf(
            bd.btnPlayPause, bd.btnRewind, bd.btnForward,
            bd.btnBackFine, bd.btnForwardFine, bd.btnGoToTime,
            bd.btnTrimBackFine, bd.btnTrimForwardFine, bd.btnTrimGoTo,
            bd.btnSetStart, bd.btnSetEnd, bd.btnSelectAll, bd.btnPlaySelection,
            bd.btnTrimSetStart, bd.btnTrimSetEnd, bd.btnTrimReset, bd.btnTrimPlay,
            bd.btnTrimKeep, bd.btnTrimBefore, bd.btnTrimAfter, bd.btnTrimSaveNew,
            bd.btnCut, bd.btnCopy, bd.btnDelete, bd.btnInsertFile, bd.btnSave,
            bd.btnAddMark, bd.btnMarksEvery,
            bd.btnMixPick, bd.btnCompressSave, bd.btnRemoveSilence,
            bd.btnSaveAudioOnly, bd.btnSaveNoSound, bd.btnSaveFrame, bd.btnEffectsSave,
            bd.btnPresetSmallest, bd.btnPresetSmall, bd.btnPresetBalanced,
            bd.btnToBegin, bd.btnToEnd, bd.btnTrimToBegin, bd.btnTrimToEnd,
            bd.btnTrimToStartMarker, bd.btnTrimToEndMarker,
            bd.btnStartEarlier, bd.btnStartLater, bd.btnEndEarlier, bd.btnEndLater,
            bd.btnHearStart, bd.btnHearEnd, bd.btnTrimDelete, bd.btnPrevMark, bd.btnNextMark
        )
        needsMedia.forEach { it.isEnabled = has }
        bd.btnOpen.isEnabled = idle
        bd.btnPaste.isEnabled = has && project.clipboard.isNotEmpty()
        bd.btnUndo.isEnabled = has && project.canUndo
        bd.btnClearMarks.isEnabled = has && project.marks.isNotEmpty()
        bd.btnSplit.isEnabled = has && project.marks.isNotEmpty()
        bd.btnMixSave.isEnabled = has && viewModel.mixOverlay != null
        bd.btnMergeAdd.isEnabled = idle
        bd.btnMergeClear.isEnabled = idle && viewModel.mergeItems.isNotEmpty()
        bd.btnMergeSave.isEnabled = idle && viewModel.mergeItems.size >= 2
        bd.seekBar.isEnabled = has
    }

    /** Shows the message on screen and speaks it through the screen reader. */
    private fun announce(message: String) {
        val bd = _binding ?: return
        bd.tvStatus.text = message
        if (Prefs.announcements) bd.root.announceForAccessibility(SpeechText.clean(message))
    }

    companion object {
        val TITLES = listOf("Edit", "Trim", "Split", "Merge", "Mix", "Effects", "Compress", "Tools")
        private const val ARG_VIDEO = "video"
        private const val TICK_MS = 100L
        private const val SEEK_MAX = 1000

        fun newInstance(video: Boolean) = EditorFragment().apply {
            arguments = bundleOf(ARG_VIDEO to video)
        }
    }
}
