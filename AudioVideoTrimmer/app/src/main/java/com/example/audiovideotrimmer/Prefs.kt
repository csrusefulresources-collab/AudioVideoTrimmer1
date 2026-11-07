package com.example.audiovideotrimmer

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. Every screen reads/writes through here. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    fun resetAll() {
        sp.edit().clear().apply()
    }

    private fun i(key: String, def: Int) = sp.getInt(key, def)
    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun s(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, v: Int) { sp.edit().putInt(key, v).apply() }
    private fun put(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply() }
    private fun put(key: String, v: String) { sp.edit().putString(key, v).apply() }

    // ---- audio recording
    var audioSampleRate: Int
        get() = i("audio_sample_rate", 48000)
        set(v) = put("audio_sample_rate", v)
    var audioChannels: Int
        get() = i("audio_channels", 2)
        set(v) = put("audio_channels", v)
    /** "mic" | "voice" | "raw" */
    var audioSource: String
        get() = s("audio_source", "mic")
        set(v) = put("audio_source", v)
    var noiseSuppression: Boolean
        get() = b("noise_suppression", true)
        set(v) = put("noise_suppression", v)
    var echoCancel: Boolean
        get() = b("echo_cancel", true)
        set(v) = put("echo_cancel", v)
    var autoGain: Boolean
        get() = b("auto_gain", false)
        set(v) = put("auto_gain", v)

    // ---- silence removal
    var autoSilence: Boolean
        get() = b("auto_silence", false)
        set(v) = put("auto_silence", v)
    var silenceThresholdDb: Int
        get() = i("silence_threshold_db", -40)
        set(v) = put("silence_threshold_db", v)
    var silenceMinMs: Int
        get() = i("silence_min_ms", 500)
        set(v) = put("silence_min_ms", v)
    var silencePadMs: Int
        get() = i("silence_pad_ms", 150)
        set(v) = put("silence_pad_ms", v)

    // ---- camera video
    /** "uhd" | "fhd" | "hd" | "sd" */
    var videoQuality: String
        get() = s("video_quality", "fhd")
        set(v) = put("video_quality", v)
    /** "back" | "front" */
    var videoCamera: String
        get() = s("video_camera", "back")
        set(v) = put("video_camera", v)
    var videoAudio: Boolean
        get() = b("video_audio", true)
        set(v) = put("video_audio", v)

    // ---- screen recording
    /** "native" | "1080" | "720" */
    var screenScale: String
        get() = s("screen_scale", "native")
        set(v) = put("screen_scale", v)
    var screenFps: Int
        get() = i("screen_fps", 30)
        set(v) = put("screen_fps", v)
    var screenBitrateMbps: Int
        get() = i("screen_bitrate", 8)
        set(v) = put("screen_bitrate", v)
    var screenMic: Boolean
        get() = b("screen_mic", true)
        set(v) = put("screen_mic", v)
    var floatingControls: Boolean
        get() = b("floating_controls", true)
        set(v) = put("floating_controls", v)

    // ---- common recording
    var countdownSeconds: Int
        get() = i("countdown", 3)
        set(v) = put("countdown", v)
    var autoSaveToPhone: Boolean
        get() = b("auto_save", true)
        set(v) = put("auto_save", v)
    var recordMode: String
        get() = s("record_mode", "audio")
        set(v) = put("record_mode", v)

    // ---- editor
    var jumpSeconds: Int
        get() = i("jump_seconds", 5)
        set(v) = put("jump_seconds", v)

    var jumpSecondsVideo: Int
        get() = i("jump_seconds_video", 5)
        set(v) = put("jump_seconds_video", v)
    var videoPreview: Boolean
        get() = b("video_preview", true)
        set(v) = put("video_preview", v)

    // ---- mix / compress defaults
    var mixBaseVolume: Int
        get() = i("mix_base_volume", 100)
        set(v) = put("mix_base_volume", v)
    var mixOverlayVolume: Int
        get() = i("mix_overlay_volume", 50)
        set(v) = put("mix_overlay_volume", v)
    var compressAudioKbps: Int
        get() = i("compress_audio_kbps", 96)
        set(v) = put("compress_audio_kbps", v)
    var compressAudioMono: Boolean
        get() = b("compress_audio_mono", false)
        set(v) = put("compress_audio_mono", v)
    /** 0 = keep original size */
    var compressVideoHeight: Int
        get() = i("compress_video_height", 720)
        set(v) = put("compress_video_height", v)
    var compressVideoKbps: Int
        get() = i("compress_video_kbps", 2000)
        set(v) = put("compress_video_kbps", v)
    /** "keep" | "64" | "32" | "none" */
    var compressVideoSound: String
        get() = s("compress_video_sound", "64")
        set(v) = put("compress_video_sound", v)
    /** 0 = off. Otherwise the compressed file is made to fit this size. */
    var compressTargetMb: Int
        get() = i("compress_target_mb", 0)
        set(v) = put("compress_target_mb", v)

    // ---- echo effect while recording
    var echoEffect: Boolean
        get() = b("echo_effect", false)
        set(v) = put("echo_effect", v)
    var echoDelayMs: Int
        get() = i("echo_delay_ms", 250)
        set(v) = put("echo_delay_ms", v)
    /** "low" | "medium" | "high" */
    var echoLevel: String
        get() = s("echo_level", "medium")
        set(v) = put("echo_level", v)

    // ---- logs
    var logsEnabled: Boolean
        get() = b("logs_enabled", true)
        set(v) = put("logs_enabled", v)

    /** Non-personal settings, written at the top of log reports. */
    fun dump(): String = listOf(
        "audio: ${audioSampleRate}Hz ${audioChannels}ch src=$audioSource ns=$noiseSuppression aec=$echoCancel agc=$autoGain",
        "video: q=$videoQuality cam=$videoCamera audio=$videoAudio",
        "screen: scale=$screenScale fps=$screenFps mbps=$screenBitrateMbps mic=$screenMic floating=$floatingControls",
        "common: countdown=$countdownSeconds autosave=$autoSaveToPhone",
        "silence: ${silenceThresholdDb}dB min=${silenceMinMs}ms pad=${silencePadMs}ms",
        "compress: audio=${compressAudioKbps}k mono=$compressAudioMono video=${compressVideoHeight}p ${compressVideoKbps}kbps sound=$compressVideoSound target=${compressTargetMb}MB",
        "echo effect: $echoEffect ${echoDelayMs}ms $echoLevel"
    ).joinToString("\n")

    // ---- accessibility
    var announcements: Boolean
        get() = b("announcements", true)
        set(v) = put("announcements", v)
    var haptics: Boolean
        get() = b("haptics", true)
        set(v) = put("haptics", v)
    var beeps: Boolean
        get() = b("beeps", true)
        set(v) = put("beeps", v)
    var minuteAnnouncements: Boolean
        get() = b("minute_announcements", true)
        set(v) = put("minute_announcements", v)

    // ---- appearance: "system" | "light" | "dark"
    var theme: String
        get() = s("theme", "system")
        set(v) = put("theme", v)
}
