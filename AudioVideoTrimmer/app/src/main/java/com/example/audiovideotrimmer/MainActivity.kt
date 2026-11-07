package com.example.audiovideotrimmer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.example.audiovideotrimmer.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip

private const val TAG_HOME = "home"
private const val TAG_RECORD = "record"
private const val TAG_AUDIO = "audio"
private const val TAG_VIDEO = "video"
private const val TAG_SETTINGS = "settings"
private val ALL_TAGS = listOf(TAG_HOME, TAG_RECORD, TAG_AUDIO, TAG_VIDEO, TAG_SETTINGS)

/**
 * Two levels:
 *  - Home: the bottom bar has the four main buttons (Record, Audio editor, Video editor, Settings).
 *  - A section (for example Audio editor): the main buttons are hidden and the bottom bar shows
 *    "Back to main menu" and the buttons of that section only (Edit, Trim, Merge …).
 * The content in the middle always belongs to the one chosen section, so swiping never leaves it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String = TAG_HOME
    private val chipIds = ArrayList<Int>()
    private var building = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                setReorderingAllowed(true)
                add(R.id.fragmentContainer, HomeFragment(), TAG_HOME)
                add(R.id.fragmentContainer, RecordFragment(), TAG_RECORD)
                add(R.id.fragmentContainer, EditorFragment.newInstance(false), TAG_AUDIO)
                add(R.id.fragmentContainer, EditorFragment.newInstance(true), TAG_VIDEO)
                add(R.id.fragmentContainer, SettingsFragment(), TAG_SETTINGS)
            }
        } else {
            currentTag = savedInstanceState.getString(STATE_TAG, TAG_HOME)
        }

        binding.btnMainRecord.setOnClickListener { enter(TAG_RECORD) }
        binding.btnMainAudio.setOnClickListener { enter(TAG_AUDIO) }
        binding.btnMainVideo.setOnClickListener { enter(TAG_VIDEO) }
        binding.btnMainSettings.setOnClickListener { enter(TAG_SETTINGS) }
        binding.btnBackToMain.setOnClickListener { goHome() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTag != TAG_HOME) {
                    goHome()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        show(currentTag, announce = false)

        // "Open with → Audio Video Trimmer" from a file manager
        val data = intent?.data
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW && data != null) {
            val video = intent.type?.startsWith("video") == true
            binding.root.post { openInEditor(data, video, false) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAG, currentTag)
    }

    // ------------------------------------------------------------ navigation

    private fun enter(tag: String) = show(tag, announce = true)

    private fun goHome() = show(TAG_HOME, announce = true)

    private fun titleFor(tag: String) = when (tag) {
        TAG_RECORD -> "Record"
        TAG_AUDIO -> "Audio editor"
        TAG_VIDEO -> "Video editor"
        TAG_SETTINGS -> "Settings"
        else -> "Main menu"
    }

    private fun show(tag: String, announce: Boolean) {
        currentTag = tag
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            for (t in ALL_TAGS) {
                val f = supportFragmentManager.findFragmentByTag(t) ?: continue
                if (t == tag) show(f) else hide(f)
            }
        }
        val home = tag == TAG_HOME
        binding.mainBar.visibility = if (home) View.VISIBLE else View.GONE
        binding.sectionBar.visibility = if (home) View.GONE else View.VISIBLE
        if (!home) buildSectionChips(tag)
        if (announce) binding.root.announceForAccessibility(
            if (home) "Main menu" else "${titleFor(tag)}. Back to main menu is at the bottom."
        )
    }

    private fun screenFor(tag: String): SectionScreen? =
        supportFragmentManager.findFragmentByTag(tag) as? SectionScreen

    private fun buildSectionChips(tag: String) {
        val screen = screenFor(tag) ?: return
        building = true
        val group = binding.sectionChips
        group.removeAllViews()
        chipIds.clear()
        screen.sectionTitles.forEach { title ->
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle)
            chip.id = View.generateViewId()
            chip.text = title
            chip.isCheckable = true
            chip.isCheckedIconVisible = true
            chipIds.add(chip.id)
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, ids ->
            if (building) return@setOnCheckedStateChangeListener
            val idx = chipIds.indexOf(ids.firstOrNull())
            val current = screenFor(currentTag)
            if (idx >= 0 && current != null && idx != current.selectedSection) current.selectSection(idx)
        }
        syncSections()
        building = false
    }

    /** Called by a section screen when it changed its own section (for example "Open" from the library). */
    fun syncSections() {
        val screen = screenFor(currentTag) ?: return
        val idx = screen.selectedSection
        if (idx in chipIds.indices) {
            val was = building
            building = true
            binding.sectionChips.check(chipIds[idx])
            building = was
        }
    }

    /** A recording file was deleted: editors that still use it are cleared. */
    fun notifyFileDeleted() {
        for (tag in listOf(TAG_AUDIO, TAG_VIDEO)) {
            (supportFragmentManager.findFragmentByTag(tag) as? EditorFragment)?.checkSources()
        }
    }

    /** Opens a file in the audio or video editor. */
    fun openInEditor(uri: Uri, video: Boolean, removeSilence: Boolean) {
        val tag = if (video) TAG_VIDEO else TAG_AUDIO
        show(tag, announce = true)
        val f: Fragment? = supportFragmentManager.findFragmentByTag(tag)
        (f as? EditorFragment)?.openExternal(uri, removeSilence)
    }

    companion object {
        private const val STATE_TAG = "current_tag"
    }
}
