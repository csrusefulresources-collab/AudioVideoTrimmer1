package com.example.audiovideotrimmer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Working copies of recordings live in the app's private folder; "Save to phone" copies them to Music/Movies. */
object RecordingStore {

    fun dir(context: Context): File =
        File(context.filesDir, "recordings").also { it.mkdirs() }

    fun newFile(context: Context, prefix: String, ext: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir(context), "${prefix}_$stamp.$ext")
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun isVideo(file: File): Boolean = file.extension.lowercase(Locale.US) == "mp4"

    fun mimeFor(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "wav" -> "audio/x-wav"
        "mp4" -> "video/mp4"
        else -> "audio/mp4"
    }

    /** Returns the public folder description, or null on failure. */
    fun saveToPhone(context: Context, file: File): String? =
        MediaSaver.save(context, file, isVideo(file), file.name, mimeFor(file))

    fun sizeText(file: File): String {
        val kb = file.length() / 1024.0
        return if (kb < 1024) String.format(Locale.US, "%.0f KB", kb)
        else String.format(Locale.US, "%.1f MB", kb / 1024.0)
    }
}
