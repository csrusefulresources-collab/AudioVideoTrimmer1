# Audio Video Trimmer 4.4

Accessible Android app for **recording** and **editing** audio and video, built for TalkBack users first.
Works on Android 9 (API 28) → Android 16.

## Navigation (two levels, no overlapping buttons)

* **Main menu** (home): the bottom of the screen shows four buttons: **Record · Audio editor · Video editor · Settings**.
* Open one and those four buttons are **hidden**. The bottom now shows only the buttons of that part (use the phone's **Back** button to return to the main menu):

| Part | Section buttons at the bottom (each one is a separate tab) |
|---|---|
| **Record** | Record · Library · Audio settings · Video settings · Screen settings |
| **Audio editor** | Edit · Trim · Split · Merge · Mix · Effects · Compress · Tools |
| **Video editor** | Edit · Trim · Split · Merge · Mix · Effects · Compress · Tools |
| **Settings** | Audio recording · Video recording · Screen recording · Audio editing · Video editing · General · Guide · About · Logs |

* The middle of the screen shows **only the chosen button's content** and scrolls. While you are in Trim you only see and swipe through Trim; other tools are not on the screen.
* The phone's Back button returns to the main menu, then exits from the main menu.
* In the editors the Open button is at the top of every page; play controls, position, status stay just above the section buttons.

## Same signature for every update

All builds (debug and release, GitHub Actions or local) are signed with the fixed key `app/signing/trimmer.p12`, so a new APK installs **over** the old one. Keep that file in the project and never replace it. `versionCode` goes up with every update (the project does this).
The **first** time after switching to this key, the old app (signed with a random debug key) must be uninstalled once. Save recordings first: Library, Save to phone.

## Record

* **Audio** – lossless 16-bit WAV, 44.1 / 48 kHz, mono / stereo, noise reduction, echo cancellation, automatic gain, microphone mode. Works with the screen off.
* **Video (camera)** – CameraX, up to 4K, back / front camera, sound on/off.
* **Screen** – MediaProjection, native / 1080p / 720p, 30 / 60 fps, bitrate, microphone. Pause / resume / stop from the floating panel or the notification buttons.
* Echo effect while recording: on/off, low / medium / high, delay in milliseconds (separate from echo cancellation).
* Countdown, sound + vibration cues, "Say elapsed time", level meter with spoken warnings.
* **Library**: play, Edit, Save to phone, Share, Delete, recent recordings.

## Editors (audio and video)

* **Edit** – selection, cut, copy, delete, paste, insert file, undo, save.
* **Trim** (GoldWave style) – *Move to beginning / end / start marker / end marker*, nudge, go to time; *set start / end marker*, move a marker earlier / later; *hear the start / the end* (3 seconds); *keep only selection*, *delete selection*, *cut everything before / after here*, save the selection as a new file.
* **Split** – previous / next mark; split marks (also every 10 s / 30 s / 1 min / 5 min / 10 min), one file per part.
* **Merge** – join several files in the order you choose.
* **Mix** – mix a second sound (e.g. music) with the main file; volumes for both; the added sound repeats until the end. In the video editor this adds music to a video.
* **Compress** – presets *Smallest / Small / Balanced*; audio 16–192 kbps and mono; video picture size down to 240p, bitrate from 250 kbps, smaller or no sound; optional target size in MB; estimated size shown first.
* **Effects** – echo (on/off, low/medium/high, delay in milliseconds), fade in/out; video: rotate, mirror/flip, brightness, contrast.
* **Tools** – remove silence, volume of the saved file, and (video) save audio only / save without sound / save a picture of the current frame.

## Settings

* **Recording / Audio / Video / General** – every option, described in plain language. Reset to defaults in General.
* **About** – version, developer contact (WhatsApp chat, phone call, copy number), privacy statement, full user guide.
* **Logs** – see below.

## Logs and bug reports (privacy)

* The app has **no internet permission**, no ads, no analytics and no tracking libraries. It cannot send anything by itself.
* A small text log is kept on the phone (`files/logs`, max ≈ 800 KB). It can be switched off (crashes are still noted) and cleared.
* A **bug report** is a text file made only when the user asks for it. It contains: app version, Android version, phone make/model, language, the app's own settings, and the log lines (events like "Recording started", error messages and stack traces). It does **not** contain recordings, file contents, contacts, location, or the user's name. File *names* are not logged.
* **Send log to the developer on WhatsApp** opens WhatsApp with the file attached to the developer's chat. The user must press WhatsApp's send button; Android does not let an app send messages silently. If WhatsApp is missing, the normal share sheet opens.
* The developer's WhatsApp number is set in `Contact.kt` (`INTERNATIONAL`). The app shows only a *Contact the developer* button; no number and no call button.

## Build

GitHub Actions: push everything → **Actions → Build APK → Run workflow** → download the **debug** artifact, extract the ZIP, install `app-debug.apk`.
Local: `./gradlew assembleDebug` (JDK 17 + Android SDK). Unit tests: `./gradlew testDebugUnitTest`.

## Project structure

```
app/src/main/java/com/example/audiovideotrimmer/
├── MainActivity.kt            host + bottom tab bar
├── RecordFragment.kt          Record tab (audio / camera / screen UI)
├── EditorFragment.kt          Audio + Video editor tabs
├── SettingsFragment.kt        Settings tab
├── SettingsUi.kt              settings rows shared by Record quick settings and Settings tab
├── Prefs.kt                   all settings
├── BaseRecordService.kt       notification, countdown, floating controls, timer, auto-save
├── AudioRecordService.kt      audio recording service (microphone)
├── ScreenRecordService.kt     screen recording service (media projection)
├── AudioRecorder.kt           AudioRecord → WAV + noise/echo/gain effects
├── FloatingControls.kt        floating pause / resume / stop panel
├── SilenceDetector.kt         decodes audio and finds silent ranges
├── EditorProject.kt           edit model (segments, selection, clipboard, marks, undo)
├── RecordState.kt, RecordingStore.kt, Feedback.kt, MediaSaver.kt, TimeFormat.kt …
```

## Known limitations / next steps

* Screen recording records the **microphone**, not the sound played by other apps.
* The floating panel is part of the screen recording; trim it in the video editor (or turn it off and use the notification buttons).
* Camera recording needs the screen on (the phone is kept awake); audio and screen recording do not.
* Noise reduction / echo cancellation depend on the device; the app says when one is unavailable. These are recording-time effects; a "clean up existing audio" tool is a good next step.
* Ideas for next updates: volume / fade / speed / normalize, EQ and echo effect, video crop / rotate / speed, merge several files, bookmarks, Urdu interface.
* UI language is English (settings texts are in `SettingsUi.kt`, ready to move into string resources).

## Accessibility design notes (4.4)

* **One topic per tab.** Only the chosen tab's content exists on screen; the others are removed from the layout and from the screen reader's reading order. Settings, the guide, about/contact and logs are separate tabs, and the Record part has its own settings page for audio, video and screen.
* **Real tabs.** The section buttons are announced as "Tab", say whether they are selected ("Selected, tab 3 of 8") and the opened tab's name is announced when its content appears (pane title). The section buttons sit in their own group ("Section buttons"), separate from the content.
* **No digit-heavy labels.** Recordings are named like "Audio recording, 7 Nov 2026 at 11:09 AM" instead of file names with long numbers. Buttons have no numeric prefixes. Time texts are skipped by the screen reader because the slider speaks the position.
* **Keyboard / switch access.** Tab and arrow keys move focus; a thick high-contrast ring marks the focused button; **Ctrl+Tab** and **Ctrl+Shift+Tab** change tab; **Escape** returns to the main menu.
* **Large targets and responsive padding.** Buttons are at least 48 dp high; on tablets (600 dp and wider) the content gets wide side margins so lines stay readable.
* **Honest limit:** Android always lets a screen reader move on to the next element, so the section buttons (bottom) can still be reached by swiping past the end of the content. Content of one tab never appears in another.
