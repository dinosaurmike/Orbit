package com.example.companion2

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Where Spotify sends you back to Orbit after signing in (add exactly this in your Spotify app)
const val SPOTIFY_REDIRECT = "orbit://spotify"

// Spotify on your computer (or any Spotify device): Orbit sees what's playing and controls it
// through Spotify's own online service. Needs Spotify Premium (Spotify only allows remote control
// for Premium) and a free Spotify developer key that you make once (see the Music tab).
// Signing in uses the "PKCE" method, so there's no secret to keep: just the key's Client ID.
class SpotifyConnect(private val settings: Settings) {
    private val client = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    val connected get() = settings.spotifyRefresh.isNotBlank()
    @Volatile var status = ""
    @Volatile var playing = false
        private set
    @Volatile var title = ""
        private set
    @Volatile var artist = ""
        private set
    @Volatile var device = ""
        private set
    @Volatile var color: Int? = null
        private set
    @Volatile var onSong: ((String, String) -> Unit)? = null
    @Volatile private var watching = false
    @Volatile private var volumeBeforeDuck = -1
    @Volatile private var ducked = false

    // ---- Signing in ----

    // The web page where you sign in to Spotify and allow Orbit
    fun signInUrl(): String? {
        val id = settings.spotifyClientId.trim()
        if (id.isBlank()) return null
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        val verifier = (1..64).map { chars[random.nextInt(chars.length)] }.joinToString("")
        settings.spotifyVerifier = verifier
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter(
                "scope", "user-read-playback-state user-modify-playback-state user-read-currently-playing"
            )
            .build().toString()
    }

    // Spotify sent you back to Orbit with a sign-in code: swap it for keys (on a background thread)
    fun finishSignIn(code: String) {
        status = "Connecting to Spotify…"
        thread(name = "spotify-sign-in") {
            try {
                val body = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", SPOTIFY_REDIRECT)
                    .add("client_id", settings.spotifyClientId.trim())
                    .add("code_verifier", settings.spotifyVerifier)
                    .build()
                saveTokens(post("https://accounts.spotify.com/api/token", body))
                status = "✓ Connected to Spotify"
                startWatching()
            } catch (e: Exception) {
                Log.w("Orbit", "Spotify sign-in failed", e)
                status = "Couldn't connect: ${e.message}"
            }
        }
    }

    fun signOut() {
        settings.spotifyRefresh = ""
        settings.spotifyAccess = ""
        settings.spotifyExpires = ""
        playing = false
        status = "Not connected"
    }

    private fun saveTokens(answer: JSONObject) {
        settings.spotifyAccess = answer.getString("access_token")
        answer.optString("refresh_token").takeIf { it.isNotBlank() }?.let { settings.spotifyRefresh = it }
        settings.spotifyExpires = (System.currentTimeMillis() + answer.optLong("expires_in", 3600) * 1000 - 60_000).toString()
    }

    @Synchronized
    private fun token(): String {
        if (System.currentTimeMillis() < (settings.spotifyExpires.toLongOrNull() ?: 0L)) return settings.spotifyAccess
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", settings.spotifyRefresh)
            .add("client_id", settings.spotifyClientId.trim())
            .build()
        saveTokens(post("https://accounts.spotify.com/api/token", body))
        return settings.spotifyAccess
    }

    private fun post(url: String, body: FormBody): JSONObject =
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Spotify said ${response.code}")
            JSONObject(text)
        }

    // One call to Spotify's player service; returns the reply (or null if there's nothing to say)
    private fun api(method: String, path: String, body: JSONObject? = null): JSONObject? {
        val request = Request.Builder()
            .url("https://api.spotify.com/v1$path")
            .header("Authorization", "Bearer ${token()}")
            .method(method, if (method == "GET") null else (body?.toString() ?: "").toRequestBody(json))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.code == 403 -> throw IllegalStateException("Spotify needs Premium for Orbit to control it")
                response.code == 404 -> throw IllegalStateException("Spotify isn't open on any device")
                !response.isSuccessful -> throw IllegalStateException("Spotify said ${response.code}")
            }
            return if (text.isBlank()) null else JSONObject(text)
        }
    }

    // ---- Following what's playing ----

    fun startWatching() {
        if (watching || !connected) return
        watching = true
        thread(name = "spotify-watch") {
            while (watching && connected) {
                try {
                    refresh()
                } catch (e: Exception) {
                    Log.w("Orbit", "Spotify check failed", e)
                }
                Thread.sleep(8000)
            }
            watching = false
        }
    }

    fun stopWatching() {
        watching = false
    }

    private fun refresh() {
        val state = api("GET", "/me/player")
        if (state == null) {
            playing = false
            return
        }
        playing = state.optBoolean("is_playing")
        device = state.optJSONObject("device")?.optString("name").orEmpty()
        val item = state.optJSONObject("item") ?: return
        val newTitle = item.optString("name")
        val artists = item.optJSONArray("artists") ?: JSONArray()
        val newArtist = (0 until artists.length()).joinToString { artists.getJSONObject(it).optString("name") }
        if (newTitle != title || newArtist != artist) {
            title = newTitle
            artist = newArtist
            color = albumColor(item)
            if (playing) onSong?.invoke(newTitle, newArtist)
        }
    }

    // The smallest album picture, boiled down to one colour for the eyes
    private fun albumColor(item: JSONObject): Int? = try {
        val images = item.optJSONObject("album")?.optJSONArray("images") ?: JSONArray()
        val url = (0 until images.length()).map { images.getJSONObject(it) }
            .minByOrNull { it.optInt("width", 9999) }?.optString("url")
        url?.let {
            client.newCall(Request.Builder().url(it).build()).execute().use { response ->
                response.body?.bytes()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }
        }?.let { mainColor(it) }
    } catch (e: Exception) {
        null
    }

    // ---- Controls (call from a background thread; they wait for Spotify) ----

    fun control(action: String): String {
        try {
            when (action) {
                "pause" -> api("PUT", "/me/player/pause")
                "resume" -> api("PUT", "/me/player/play")
                "next" -> api("POST", "/me/player/next")
                "previous" -> api("POST", "/me/player/previous")
                "louder", "quieter" -> {
                    val now = api("GET", "/me/player")?.optJSONObject("device")?.optInt("volume_percent", 50) ?: 50
                    val to = (now + if (action == "louder") 15 else -15).coerceIn(0, 100)
                    api("PUT", "/me/player/volume?volume_percent=$to")
                }
                "like" -> return "Liking songs on the computer isn't supported yet (but I'll remember you like it)"
                else -> return "Unknown action"
            }
            if (action == "pause") playing = false
            return "Done on the computer (Spotify): $action"
        } catch (e: Exception) {
            return e.message ?: "Spotify didn't answer"
        }
    }

    // "Play some jazz": finds a matching playlist (or song) and plays it on the active device,
    // or on a computer if nothing's active
    fun play(query: String): String {
        try {
            val found = api("GET", "/search?q=${Uri.encode(query)}&type=playlist,track&limit=1")
            val playlist = found?.optJSONObject("playlists")?.optJSONArray("items")?.optJSONObject(0)
            val track = found?.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)
            // Words like "some", "music", "for" suggest a mood or genre (a playlist); otherwise a song
            val moody = listOf("some", "music", "for ", "playlist", "mix", "vibes", "chill", "focus")
                .any { it in query.lowercase() }
            val body = when {
                moody && playlist != null -> JSONObject().put("context_uri", playlist.getString("uri"))
                track != null -> JSONObject().put("uris", JSONArray().put(track.getString("uri")))
                playlist != null -> JSONObject().put("context_uri", playlist.getString("uri"))
                else -> return "Couldn't find \"$query\" on Spotify"
            }
            var target = ""
            if (api("GET", "/me/player") == null) {
                // Nothing active: pick a computer (or anything) that has Spotify open
                val devices = api("GET", "/me/player/devices")?.optJSONArray("devices") ?: JSONArray()
                val list = (0 until devices.length()).map { devices.getJSONObject(it) }
                val pick = list.firstOrNull { it.optString("type").equals("Computer", true) } ?: list.firstOrNull()
                    ?: return "Spotify isn't open on your computer. Open it there, then ask again."
                target = "?device_id=" + pick.getString("id")
            }
            api("PUT", "/me/player/play$target", body)
            playing = true
            val name = if (body.has("context_uri")) playlist?.optString("name") else track?.optString("name")
            return "Playing on Spotify: $name"
        } catch (e: Exception) {
            return e.message ?: "Spotify didn't answer"
        }
    }

    // Quieter while you talk to Orbit, and back afterwards
    fun duck() {
        if (!playing || ducked) return
        ducked = true
        thread(name = "spotify-duck") {
            try {
                val now = api("GET", "/me/player")?.optJSONObject("device")?.optInt("volume_percent", -1) ?: -1
                if (now > 0) {
                    volumeBeforeDuck = now
                    api("PUT", "/me/player/volume?volume_percent=${(now * 0.12f).toInt()}")
                }
            } catch (e: Exception) {
                Log.w("Orbit", "Spotify duck failed", e)
            }
        }
    }

    fun unduck() {
        if (!ducked) return
        ducked = false
        val back = volumeBeforeDuck
        volumeBeforeDuck = -1
        if (back < 0) return
        thread(name = "spotify-unduck") {
            try {
                api("PUT", "/me/player/volume?volume_percent=$back")
            } catch (e: Exception) {
                Log.w("Orbit", "Spotify unduck failed", e)
            }
        }
    }
}
