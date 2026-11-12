package com.example.companion2

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// If Orbit ever stops unexpectedly, what went wrong is kept in a small file on the phone, and
// Settings → Device shows it with a Copy button, so it can be shared without a computer or cables.
// It holds only the app version, the time, and where in Orbit's code it stopped.
object CrashReport {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 6000

    fun text(error: Throwable, version: String, at: Long): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))
        val trace = error.stackTraceToString().replace("\t", "  ")
        return "Orbit $version stopped at $time\n$trace".take(MAX_CHARS)
    }

    fun save(dir: File, error: Throwable, version: String, at: Long = System.currentTimeMillis()) {
        try {
            File(dir, FILE).writeText(text(error, version, at))
        } catch (_: Exception) {
            // Never let the report cause a second crash
        }
    }

    fun read(dir: File): String? = try {
        File(dir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun clear(dir: File) {
        File(dir, FILE).delete()
    }
}
