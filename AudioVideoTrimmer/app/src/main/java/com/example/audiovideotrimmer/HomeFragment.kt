package com.example.audiovideotrimmer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.audiovideotrimmer.databinding.FragmentHomeBinding

/** The main page. The four main buttons are at the bottom of the screen. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val box = binding.homeContent
        SettingsUi.heading(ctx, box, "Audio Video Trimmer")
        SettingsUi.paragraph(
            ctx, box,
            "Choose what you want to do with the four buttons at the bottom of the screen. " +
                "When you open one, the bottom of the screen shows the buttons of that part only, " +
                "and the button Back to main menu brings you back here."
        )
        SettingsUi.heading(ctx, box, "Record")
        SettingsUi.paragraph(ctx, box, "Record sound in high quality, record video with the camera, or record the screen. Listen to your recordings, share them, or send them to an editor.")
        SettingsUi.heading(ctx, box, "Audio editor")
        SettingsUi.paragraph(ctx, box, "Edit, trim, split, merge and mix sounds. Add effects such as echo and fade. Compress to make files small. Remove silence.")
        SettingsUi.heading(ctx, box, "Video editor")
        SettingsUi.paragraph(ctx, box, "The same tools for video, plus rotate, mirror, brightness, contrast, saving only the sound, and saving a picture of a frame.")
        SettingsUi.heading(ctx, box, "Settings")
        SettingsUi.paragraph(ctx, box, "Recording, audio, video and general settings, the user guide, contact with the developer, and logs for bug reports.")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
