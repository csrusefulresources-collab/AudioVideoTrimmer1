package com.example.audiovideotrimmer

/**
 * Makes text easy for a text-to-speech voice: abbreviations and symbols that voices read badly
 * ("kbps", "dB", "−", "…", "%") are replaced by words.
 */
object SpeechText {
    private val replacements = listOf(
        "kbps" to "kilobits per second",
        "Mbps" to "megabits per second",
        "kHz" to "kilohertz",
        " dB" to " decibels",
        "−" to "minus ",
        " — " to ", ",
        "—" to ", ",
        "…" to "...",
        "°" to " degrees",
        "%" to " percent",
        "•" to ",",
        "→" to " to ",
        "·" to ","
    )

    fun clean(text: String): String {
        var t = text
        for ((from, to) in replacements) t = t.replace(from, to)
        return t.replace("  ", " ")
    }
}
