package com.example.audiovideotrimmer

import androidx.lifecycle.ViewModel

/** A file in the Merge list or the Mix overlay. */
data class MergeItem(
    val uriString: String,
    val name: String,
    val durationMs: Long,
    val hasVideo: Boolean
)

/** Keeps the edit session alive across configuration changes (font size, dark mode, language…). */
class EditorViewModel : ViewModel() {
    val project = EditorProject()
    var resumePositionMs: Long = 0L

    val mergeItems = mutableListOf<MergeItem>()
    var mixOverlay: MergeItem? = null
    var saveVolumePercent: Int = 100
    var selectedTab: Int = 0

    // Effects tab (kept while the app is open)
    var fxEchoOn = false
    var fxEchoDelayMs = 250
    var fxEchoLevel = "medium"
    var fxFadeInMs = 0
    var fxFadeOutMs = 0
    var fxRotation = 0          // degrees counter-clockwise: 0, 90, 180, 270
    var fxFlipH = false
    var fxFlipV = false
    var fxBrightness = 0        // percent, -30..30
    var fxContrast = 0          // percent, -30..50
}
