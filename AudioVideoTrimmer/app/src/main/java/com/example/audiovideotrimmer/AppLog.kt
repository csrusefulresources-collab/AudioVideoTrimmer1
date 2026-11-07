package com.example.audiovideotrimmer

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small file logger. Everything important (recording, export, errors, crashes) is written to
 * a text file that the user can view, share, or send to the developer.
 * No personal data is logged: no file contents, no names of people.
 */
object AppLog {
    private const val MAX_BYTES = 400_000L
    private const val REPORT_PREFIX = "AudioVideoTrimmer_report_"
    private var dir: File? = null
    private val lock = Any()

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").also { it.mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write("CRASH", "App", "Uncaught exception in thread ${thread.name}", error, force = true)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
        i("App", "App started")
    }

    fun i(tag: String, message: String) = write("INFO", tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write("WARN", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write("ERROR", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?, force: Boolean = false) {
        if (!force && !Prefs.logsEnabled) return
        val d = dir ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append(time).append(' ').append(level).append(' ').append(tag).append(": ").append(message).append('\n')
        if (error != null) sb.append(Log.getStackTraceString(error)).append('\n')
        Log.println(if (level == "INFO") Log.INFO else Log.ERROR, tag, message)
        synchronized(lock) {
            try {
                val f = File(d, "app.log")
                if (f.length() > MAX_BYTES) {
                    val old = File(d, "app.old.log")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(sb.toString())
            } catch (_: Throwable) {
            }
        }
    }

    /** Last [maxChars] characters of the log (older file first). */
    fun readAll(maxChars: Int = 200_000): String {
        val d = dir ?: return ""
        synchronized(lock) {
            val text = listOf(File(d, "app.old.log"), File(d, "app.log"))
                .filter { it.exists() }
                .joinToString("") { it.readText() }
            return if (text.length > maxChars) text.substring(text.length - maxChars) else text
        }
    }

    fun sizeBytes(): Long {
        val d = dir ?: return 0L
        return listOf(File(d, "app.old.log"), File(d, "app.log")).filter { it.exists() }.sumOf { it.length() }
    }

    fun clear() {
        val d = dir ?: return
        synchronized(lock) {
            File(d, "app.log").delete()
            File(d, "app.old.log").delete()
            d.listFiles()?.filter { it.name.startsWith(REPORT_PREFIX) }?.forEach { it.delete() }
        }
        i("App", "Log cleared by user")
    }

    /**
     * A short text for the WhatsApp chat message: device info, the latest errors with their first
     * line of detail, and the latest events. The complete log goes as an attached file.
     */
    @Suppress("DEPRECATION")
    fun chatSummary(context: Context, maxChars: Int = 1200): String {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
        val lines = readAll(100_000).lines()
        val important = ArrayList<String>()
        for ((i, line) in lines.withIndex()) {
            if (line.contains(" ERROR ") || line.contains(" CRASH ") || line.contains(" WARN ")) {
                important.add(line.take(150))
                val next = lines.getOrNull(i + 1)
                if (next != null && next.isNotBlank() && !next.startsWith("20") && !next.startsWith("\t")) {
                    important.add("   " + next.take(150))
                }
            }
        }
        val recent = lines.filter { it.contains(" INFO ") }.takeLast(6).map { it.take(110) }
        val sb = StringBuilder()
        sb.append("Audio Video Trimmer bug report\n")
        sb.append("Version ${info?.versionName}, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}\n")
        if (important.isNotEmpty()) {
            sb.append("\nLatest problems:\n").append(important.takeLast(10).joinToString("\n")).append('\n')
        } else {
            sb.append("\nNo errors in the log.\n")
        }
        if (recent.isNotEmpty()) sb.append("\nLatest events:\n").append(recent.joinToString("\n")).append('\n')
        sb.append("\n(The complete log file follows as an attachment.)")
        val text = sb.toString()
        return if (text.length > maxChars) text.substring(0, maxChars) + "…" else text
    }

    /** Creates the text file that is shared / sent to the developer. */
    fun buildReport(context: Context): File {
        val d = dir ?: File(context.filesDir, "logs").also { it.mkdirs() }
        d.listFiles()?.filter { it.name.startsWith(REPORT_PREFIX) }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(d, "$REPORT_PREFIX$stamp.txt")
        out.writeText(header(context) + "\n\n----- LOG -----\n" + readAll())
        return out
    }

    @Suppress("DEPRECATION")
    private fun header(context: Context): String {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
        return listOf(
            "Audio Video Trimmer – bug report",
            "App version: ${info?.versionName} (${info?.versionCode})",
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Language: ${Locale.getDefault()}",
            "Time: ${Date()}",
            "",
            "Settings:",
            Prefs.dump()
        ).joinToString("\n")
    }
}
