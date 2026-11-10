package com.example.audiovideotrimmer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.audiovideotrimmer.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings tab with its own small tab bar:
 * Recording · Audio · Video · General · About · Logs
 */
class SettingsFragment : Fragment(), SectionScreen {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var selected = 0
    private var awaitingFilePrompt = false


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        build()
    }

    // ---- SectionScreen
    override val sectionTitles: List<String> get() = TITLES
    override val selectedSection: Int get() = selected
    override fun focusStart() {
        _binding?.tvSettingsTitle?.let { v ->
            v.post { ViewCompat.performAccessibilityAction(v, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null) }
        }
    }

    override fun selectSection(index: Int) {
        if (index !in TITLES.indices) return
        selected = index
        build()
        _binding?.settingsScroll?.scrollTo(0, 0)
        (activity as? MainActivity)?.syncSections()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) build()
    }

    private fun build() {
        val b = _binding ?: return
        val ctx = requireContext()
        val box = b.settingsContainer
        box.removeAllViews()
        val noop = {}
        when (selected) {
            0 -> {
                SettingsUi.heading(ctx, box, "Audio recording")
                SettingsUi.note(ctx, box, "Records lossless sound: 16-bit, up to 48 kilohertz.")
                SettingsUi.audioRecording(ctx, box, true, noop)
                SettingsUi.commonRecording(ctx, box, noop)
            }
            1 -> {
                SettingsUi.heading(ctx, box, "Video recording with the camera")
                SettingsUi.videoRecording(ctx, box, noop)
                SettingsUi.commonRecording(ctx, box, noop)
            }
            2 -> {
                SettingsUi.heading(ctx, box, "Screen recording")
                SettingsUi.screenRecording(ctx, box, true, noop)
                SettingsUi.commonRecording(ctx, box, noop)
            }
            3 -> {
                SettingsUi.heading(ctx, box, "Audio editor")
                SettingsUi.audioEditing(ctx, box, noop)
            }
            4 -> {
                SettingsUi.heading(ctx, box, "Video editor")
                SettingsUi.videoEditing(ctx, box, noop)
            }
            5 -> buildGeneral(box)
            6 -> buildGuide(box)
            7 -> buildAbout(box)
            8 -> buildLogs(box)
        }
    }

    // ------------------------------------------------------------------ General

    private fun buildGeneral(box: ViewGroup) {
        val ctx = requireContext()
        val noop = {}
        SettingsUi.heading(ctx, box, "Accessibility")
        SettingsUi.accessibility(ctx, box, noop)
        SettingsUi.heading(ctx, box, "Appearance")
        SettingsUi.appearance(ctx, box) { applyTheme() }
        SettingsUi.heading(ctx, box, "Reset")
        SettingsUi.addButton(ctx, box, "Reset all settings to default") {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Reset all settings?")
                .setMessage("Every setting returns to its default value. Your recordings and logs are not deleted.")
                .setPositiveButton("Reset") { _, _ ->
                    Prefs.resetAll()
                    AppLog.i("Settings", "All settings reset")
                    applyTheme()
                    build()
                    binding.root.announceForAccessibility("All settings were reset.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // ------------------------------------------------------------------- About

    /** The whole user guide, one topic after the other, in its own section. */
    private fun buildGuide(box: ViewGroup) {
        val ctx = requireContext()
        SettingsUi.heading(ctx, box, "User guide")
        for ((title, body) in GuideText.sections) {
            SettingsUi.heading(ctx, box, title)
            SettingsUi.paragraph(ctx, box, body)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildAbout(box: ViewGroup) {
        val ctx = requireContext()
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            ""
        }
        SettingsUi.heading(ctx, box, "Audio Video Trimmer")
        SettingsUi.paragraph(ctx, box, "Version $version")
        SettingsUi.paragraph(
            ctx, box,
            "Record audio, video and the screen in high quality, then cut, trim, split, merge, mix, " +
                "compress and clean up your recordings. Made to work well with screen readers."
        )
        SettingsUi.heading(ctx, box, "Contact the developer")
        SettingsUi.paragraph(ctx, box, "Questions, ideas or problems? Write to the developer on WhatsApp.")
        SettingsUi.addButton(ctx, box, "Contact the developer") {
            val ok = Contact.openChat(ctx, "Hello, I am using Audio Video Trimmer (version $version).")
            if (!ok) binding.root.announceForAccessibility("WhatsApp could not be opened.")
        }
        SettingsUi.heading(ctx, box, "Privacy")
        SettingsUi.paragraph(
            ctx, box,
            "This app does not use the internet. It has no ads and no analytics. " +
                "Your recordings stay on your phone until you share them yourself. " +
                "A bug report is created only when you ask for it, and it is sent only when you press send in WhatsApp or another app. " +
                "The Logs section shows exactly what a report contains."
        )
    }

    // -------------------------------------------------------------------- Logs

    private fun buildLogs(box: ViewGroup) {
        val ctx = requireContext()
        SettingsUi.heading(ctx, box, "Logs and bug reports")
        SettingsUi.paragraph(
            ctx, box,
            "The app keeps a short text log of what it does, for example \"Recording started\" or \"Saving failed\", " +
                "and technical error details. A bug report contains: the app version, the Android version, " +
                "the phone make and model, the language, your app settings, and this log. " +
                "It does not contain your recordings, your files, your contacts, your location or your name."
        )
        val info = SettingsUi.paragraph(ctx, box, "")
        fun refreshInfo() {
            info.text = String.format(Locale.US, "Log size: %.1f KB", AppLog.sizeBytes() / 1024.0)
        }
        refreshInfo()

        SettingsUi.addSwitch(ctx, box, "Keep a log of app events",
            "Turn off to stop writing the log. Crashes are always noted.",
            { Prefs.logsEnabled }, { Prefs.logsEnabled = it }) { refreshInfo() }

        SettingsUi.addButton(ctx, box, "Show the recent log") { showLog() }
        SettingsUi.addButton(ctx, box, "Send the log to the developer") { confirmSend() }
        SettingsUi.addButton(ctx, box, "Attach the full log file to the developer chat") { attachFullLog() }
        SettingsUi.addButton(ctx, box, "Share the log file with another app") { shareReport() }
        SettingsUi.addButton(ctx, box, "Clear the log") {
            AppLog.clear()
            refreshInfo()
            binding.root.announceForAccessibility("Log cleared.")
        }
    }

    private fun showLog() {
        val ctx = requireContext()
        val text = AppLog.readAll(8000).ifBlank { "The log is empty." }
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextIsSelectable(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad, pad, pad)
        val sv = ScrollView(ctx)
        sv.addView(tv)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Recent log")
            .setView(sv)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmSend() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Send the log to the developer?")
            .setMessage(
                "Step 1: WhatsApp opens the developer's chat with a short summary already typed. " +
                    "Press WhatsApp's send button. " +
                    "Step 2: when you come back, the app offers to attach the complete log file to the same chat. " +
                    "The log has the app version, Android version, phone model, your app settings and app events. " +
                    "No recordings or personal files are included. Nothing is sent until you press send."
            )
            .setPositiveButton("Continue") { _, _ -> sendSummary() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Step 1: opens the developer's chat with the summary typed in (works even if the number is not a saved contact). */
    private fun sendSummary() {
        val ctx = requireContext()
        val text = AppLog.chatSummary(ctx, 1200)
        awaitingFilePrompt = true
        if (!Contact.openChat(ctx, text)) {
            awaitingFilePrompt = false
            attachFullLog()
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingFilePrompt && !isHidden) {
            awaitingFilePrompt = false
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Attach the full log file?")
                .setMessage("If you sent the summary, you can now attach the complete log file to the same chat.")
                .setPositiveButton("Attach the file") { _, _ -> attachFullLog() }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    /** Step 2: the file. After step 1 the chat exists, so WhatsApp opens it directly. */
    private fun attachFullLog() {
        val app = requireContext().applicationContext
        val activity = requireActivity()
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { AppLog.buildReport(app) }
            val message = when (Contact.sendReport(activity, report)) {
                Contact.SendResult.WHATSAPP_CHAT ->
                    "WhatsApp opened with the log file attached. Press send to deliver it."
                Contact.SendResult.SHARE_SHEET ->
                    "WhatsApp was not found. Choose an app to send the log file."
                Contact.SendResult.FAILED -> "The log file could not be sent."
            }
            _binding?.root?.announceForAccessibility(message)
        }
    }

    private fun shareReport() {
        val app = requireContext().applicationContext
        val activity = requireActivity()
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { AppLog.buildReport(app) }
            try {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", report)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(Intent.createChooser(send, "Share log file"))
            } catch (e: Exception) {
                AppLog.e("Logs", "Could not share the log file", e)
                _binding?.root?.announceForAccessibility("The log file could not be shared.")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        val TITLES = listOf(
            "Audio recording", "Video recording", "Screen recording",
            "Audio editing", "Video editing", "General", "Guide", "About", "Logs"
        )
    }
}
