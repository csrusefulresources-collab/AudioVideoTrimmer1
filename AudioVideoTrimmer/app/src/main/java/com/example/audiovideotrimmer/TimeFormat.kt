package com.example.audiovideotrimmer

import java.util.Locale

/** Time helpers: a visual format, a spoken format for screen readers, and a parser. */
object TimeFormat {

    /** 01:30.5  or  1:02:03.4 */
    fun format(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val tenths = ((t / 100) % 10).toInt()
        val totalSec = t / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", m, s, tenths)
        }
    }

    /** 01:30 or 1:02:03 – for the recorder timer. */
    fun clock(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    /** "1 minute 30.5 seconds" – read naturally by TalkBack. */
    fun spoken(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val tenths = ((t / 100) % 10).toInt()
        val totalSec = t / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h ${if (h == 1L) "hour" else "hours"}")
        if (m > 0) parts.add("$m ${if (m == 1L) "minute" else "minutes"}")
        if (s > 0 || tenths > 0 || parts.isEmpty()) {
            if (tenths == 0) {
                parts.add("$s ${if (s == 1L) "second" else "seconds"}")
            } else {
                parts.add("$s.$tenths seconds")
            }
        }
        return parts.joinToString(" ")
    }

    /** Accepts "90", "1:30", "1:30.5", "0:01:30". Returns null when invalid. */
    fun parse(input: String): Long? {
        val text = input.trim().replace(',', '.')
        if (text.isEmpty()) return null
        val parts = text.split(":")
        if (parts.size > 3) return null
        var totalSeconds = 0.0
        for (p in parts) {
            val v = p.trim().toDoubleOrNull() ?: return null
            if (v < 0.0) return null
            totalSeconds = totalSeconds * 60.0 + v
        }
        return Math.round(totalSeconds * 1000.0)
    }
}
