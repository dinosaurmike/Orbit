package com.example.companion2

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// Is there a newer Orbit? Once a day it asks GitHub for the latest release's version number
// (nothing about you is sent), and Settings → Device shows a card if there's an update.
// People who install Orbit from GitHub would otherwise never hear about new versions.
const val RELEASES_API = "https://api.github.com/repos/dinosaurmike/Orbit/releases/latest"
const val RELEASES_PAGE = "https://github.com/dinosaurmike/Orbit/releases/latest"

class Updates(private val settings: Settings, private val installed: String) {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    // The newer version that's out, or null if this one is the latest (or it hasn't checked yet)
    fun available(): String? = settings.latestVersion.takeIf { isNewer(it, installed) }

    // At most once a day; call from a background thread
    fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (!settings.updateCheck || now - settings.lastUpdateCheck < 24 * 3_600_000L) return
        settings.lastUpdateCheck = now
        try {
            val request = Request.Builder().url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Orbit-app")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val tag = JSONObject(response.body?.string().orEmpty()).optString("tag_name")
                val version = tag.trim().removePrefix("v")
                if (parse(version) != null) settings.latestVersion = version
            }
        } catch (e: Exception) {
            Log.w("Orbit", "Couldn't check for updates", e)  // offline: try again tomorrow
        }
    }

    companion object {
        // "0.1.2" → [0, 1, 2]; null if it isn't a plain version number
        fun parse(version: String): List<Int>? {
            val parts = version.trim().removePrefix("v").split(".")
            if (parts.isEmpty() || parts.size > 4) return null
            return parts.map { it.toIntOrNull() ?: return null }
        }

        fun isNewer(candidate: String, installed: String): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(installed) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
