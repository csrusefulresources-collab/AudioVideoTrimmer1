package com.example.audiovideotrimmer

/** The full user guide shown in Settings → About. */
object GuideText {
    val sections: List<Pair<String, String>> = listOf(
        "Getting started" to
            "The main menu has four buttons at the bottom of the screen: Record, Audio editor, Video editor and Settings. " +
            "When you open one, these four buttons are hidden. The bottom of the screen then shows " +
            "the buttons of that part only, for example Edit, Trim, Split, Merge, Mix, Effects, Compress and Tools in the editors. " +
            "The middle of the screen shows only the content of the chosen button, so you swipe only through that content. " +
            "Use the Back button of your phone to return to the main menu and choose something else.",

        "Record tab – Record" to
            "Choose Audio, Video or Screen. A short sentence under the buttons tells you exactly how the recording will be made. " +
            "Press the big red Start button. If a countdown is set you hear beeps. " +
            "While recording you can Pause, Resume, Stop and save, or press Say elapsed time to hear the time. " +
            "Audio and screen recordings keep going when the screen is off or another app is open. " +
            "Camera video needs the screen on, so the phone stays awake while you record.",

        "Record tab – Library" to
            "Shows your last recording. Play it, open it in the right editor with Edit, save a copy to the phone, share it, or delete it. " +
            "Recent recordings lets you pick an older one. Recordings are also copied automatically to Music or Movies, in the folder AudioVideoTrimmer, unless you turn that off.",

        "Record tab – Settings" to
            "Audio: sample rate, mono or stereo, noise reduction, echo cancellation, automatic gain and microphone mode. " +
            "Video: quality up to 4K, back or front camera, sound on or off. " +
            "Screen: size, frames per second, bitrate, microphone and the floating buttons. " +
            "Noise reduction and echo cancellation come from your phone. If your phone does not offer one, the app tells you.",

        "Screen recording and the floating buttons" to
            "After you tap Start, Android asks permission to record the screen. Allow it. " +
            "A small panel with the time, a Pause or Resume button and a Stop button floats above other apps. " +
            "You can also use the buttons in the notification. For the floating panel, Android needs the permission Display over other apps; the app offers to open that setting. " +
            "The panel is part of the screen picture, so it can appear in the video. You can cut it out in the Video editor. " +
            "Only your microphone is recorded, not the sound that other apps play.",

        "Opening a file" to
            "Every editor page has the Open button at the very top, so you can open a file first thing, whichever tool you use. " +
            "The status message, the position slider and the play controls stay at the bottom, just above the section buttons. " +
            "Everything in the middle scrolls.",

        "Audio and Video editor – Edit tab" to
            "Play the file. Press Set start here and Set end here to select a part. Play selection lets you check it. " +
            "Cut removes the part and keeps it on the clipboard. Copy keeps it without removing. Delete removes it. " +
            "Move to another position and press Paste here to place the clipboard there. Insert file here adds another file at the current position. " +
            "Undo goes back one step, up to fifty steps. Nothing is saved until you press Save edited file.",

        "Trim tab" to
            "Works like the trim tools of a sound editor. Move: Move to beginning, Move to end, Move to start marker, Move to end marker, " +
            "and Nudge back or forward, which moves a tenth of a second. Go to time lets you type a time. " +
            "Markers: Set start marker here and Set end marker here; Start marker earlier or later and End marker earlier or later move a marker by your jump size. " +
            "Listen: Play selection, Hear the start and Hear the end play three seconds at the edges so you can check them. " +
            "Trim: keep only the selection, Delete the selection, Cut everything before here, Cut everything after here, " +
            "or Save selection as a new file. Select everything puts the markers back to the beginning and the end.",

        "Split tab" to
            "Play the file and press Add split mark here at every place where you want a cut. Add marks every… adds marks at equal distances, for example every minute. Previous mark and Next mark move you from mark to mark. " +
            "Split and save creates one file for each part. Marks can be deleted one by one.",

        "Merge tab" to
            "Add two or more files, put them in the order you want with Move up and Move down, and press Merge and save. " +
            "All files become one. In the Video editor all files must be videos.",

        "Mix tab" to
            "Choose a second sound, for example background music. Set the volume of the main file and of the added sound. " +
            "The added sound repeats until the main file ends. Press Mix and save. In the Video editor this adds music to a video.",

        "Effects tab" to
            "Echo: switch it on, choose Low, Medium or High echo, and type the delay in milliseconds. " +
            "Fade in and fade out smooth the start and the end. In the Video editor you can also rotate, mirror or flip the picture and change brightness and contrast. " +
            "Press Save with effects.",

        "Compress tab" to
            "Makes the saved file as small as you need. Tap Smallest, Small or Balanced for a quick choice, or set each option yourself: " +
            "audio quality from 16 to 192 kbps, mono, picture size down to 240p, video quality, and the sound of the video. " +
            "You can also type a target size in megabytes and the app chooses the quality to fit. " +
            "The page shows the estimated size before you save.",

        "Tools tab" to
            "Remove silence finds quiet parts and removes them in one step that you can undo. The level and the shortest silence are in Settings. " +
            "Volume of the saved file makes the result louder or quieter. " +
            "In the Video editor you can also save only the audio, save the video without sound, or save a picture of the current frame.",

        "Saved files" to
            "Edited audio is saved as M4A, edited video as MP4, in Music/AudioVideoTrimmer or Movies/AudioVideoTrimmer. " +
            "Saving a video takes longer than audio because the video is processed again. Keep the app open while saving.",

        "Tips for screen reader users" to
            "Every action is spoken, for example Cut 12 seconds, total length now 3 minutes. " +
            "On the slider, swipe up or down to jump by your jump size. Use Go to time to type an exact time such as 1:30.5. " +
            "Spoken messages, beeps and vibration can be changed in Settings → General.",

        "Problems" to
            "If something does not work, open Settings, Logs, and press Send the log to the developer. " +
            "WhatsApp opens the developer's chat with a short summary typed in. Press send. When you come back, the app offers to attach the complete log file; press Attach the file and then press send again. " +
            "The Logs page shows what the log contains. To write to the developer for any other reason, use Contact the developer on the About page."
    )
}
