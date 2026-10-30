package com.example.companion2

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalTime
import kotlin.concurrent.thread

// Android only lets an app see and control other apps' music if it has "Notification access".
// This (empty) listener is what that permission is granted to; Orbit never reads notifications.
class MusicListener : NotificationListenerService()

// The music apps Orbit knows by name ("" = whichever is around)
val MUSIC_APPS = listOf(
    Triple("", "Any", null as String?),
    Triple("spotify", "Spotify", "com.spotify.music"),
    Triple("youtube", "YouTube Music", "com.google.android.apps.youtube.music"),
    Triple("amazon", "Amazon Music", "com.amazon.mp3"),
    Triple("apple", "Apple Music", "com.apple.android.music"),
    Triple("deezer", "Deezer", "deezer.android.app"),
    Triple("tidal", "Tidal", "com.aspiro.tidal"),
    Triple("pandora", "Pandora", "com.pandora.android"),
    Triple("soundcloud", "SoundCloud", "com.soundcloud.android")
)

// Orbit plays your music: sees what's playing in any music app on the phone (Spotify, YouTube
// Music…), controls it, starts music by search, lowers it while you talk, fades it out on a
// sleep timer, and keeps a small memory of what you play. Spotify on your computer is handled
// by SpotifyConnect and joined in here, so the rest of Orbit doesn't need to care where it plays.
class MusicControl(private val context: Context, private val settings: Settings) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MusicListener::class.java)
    val spotify = SpotifyConnect(settings).also { it.onSong = { t, a -> songStarted(t, a) } }
    // Orbit's own radio and soundscapes (no account, no app)
    val orbit = OrbitAudio(context).also {
        it.onStarted = { name, kind -> songStarted(name, if (kind == "radio") "radio" else "Orbit sounds") }
    }

    // What's playing on the phone right now
    @Volatile var title = ""
        private set
    @Volatile var artist = ""
        private set
    @Volatile var appName = ""
        private set
    @Volatile private var phonePlaying = false
    @Volatile private var phoneColor: Int? = null
    private var controller: MediaController? = null
    // Called when a new song starts: (title, artist)
    @Volatile var onSongChanged: ((String, String) -> Unit)? = null

    // Music is playing somewhere Orbit controls (the phone, or Spotify on the computer)
    val playing get() = phonePlaying || spotify.playing || orbit.playing
    // The album art's main colour, for the eyes (null = none, or too grey to use)
    val color: Int? get() = when {
        phonePlaying -> phoneColor
        spotify.playing -> spotify.color
        orbit.playing -> orbit.color
        else -> phoneColor
    }

    // Orbit's own radio or soundscape is what's on (or paused), not a music app
    private fun ownAudio() = orbit.kind.isNotEmpty() && !phonePlaying && !spotify.playing

    // The music apps on this phone that Orbit knows by name
    fun installedApps(): List<String> = MUSIC_APPS.filter { (_, _, pkg) -> pkg != null && isInstalled(pkg) }.map { it.second }

    fun isInstalled(pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg) != null

    // Linking a music account: open the app (to sign in), or its Play Store page to get it
    fun openOrGet(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Sleep timer: when to stop (0 = off), and the volume to put back afterwards
    @Volatile var stopAt = 0L
        private set
    private var volumeBeforeFade = -1
    // Lowered while you talk to Orbit: the volume to put back (-1 = not lowered)
    private var volumeBeforeDuck = -1
    private var duckedTo = -1
    private var rampTo = -1  // coming back up after a duck: the volume it's heading for

    fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    // Android's page for granting "Notification access"
    fun askAccess() {
        context.startActivity(
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }
    private var watching = false

    // Starts following the music apps (call again after access is granted)
    fun start() {
        spotify.startWatching()  // (Spotify on the computer doesn't need Notification access)
        if (watching || !hasAccess()) return
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
            watching = true
            pick(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            Log.w("Orbit", "No access to music apps yet", e)
        }
    }

    fun stop() {
        orbit.stop()
        if (watching) sessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        watching = false
        controller?.unregisterCallback(callback)
        controller = null
        spotify.stopWatching()
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onPlaybackStateChanged(state: PlaybackState?) = read()
        override fun onSessionDestroyed() {
            controller = null
            phonePlaying = false
        }
    }

    // Follows the music app that's playing (or the most recent one)
    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, handler)
        }
        read()
    }

    private fun read() {
        val c = controller
        phonePlaying = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val meta = c?.metadata ?: return
        val newTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val newArtist = (meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        appName = label(c.packageName)
        if (newTitle.isNotBlank() && (newTitle != title || newArtist != artist)) {
            title = newTitle
            artist = newArtist
            val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            phoneColor = art?.let { mainColor(it) }
            if (phonePlaying) songStarted(newTitle, newArtist)
        }
    }

    // A new song: remember it, and let Orbit know (title fades in, maybe a reaction)
    internal fun songStarted(title: String, artist: String) {
        if (settings.musicMemory) {
            val line = listOf(
                LocalDate.now().toString(), LocalTime.now().hour.toString(),
                if (settings.focusing()) "1" else "0", clean(title), clean(artist)
            ).joinToString("|")
            settings.musicLog = (settings.musicLog.lines().filter { it.isNotBlank() } + line).takeLast(300).joinToString("\n")
        }
        onSongChanged?.invoke(title, artist)
    }

    private fun clean(text: String) = text.replace("|", "/").replace("\n", " ").trim()

    private fun label(pkg: String): String = MUSIC_APPS.firstOrNull { it.third == pkg }?.second ?: try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    // ---- Controls ----

    // Where commands go: Spotify on the computer if that's what's playing (or chosen), else the phone
    private fun onComputer() = spotify.connected &&
        (spotify.playing || (!phonePlaying && settings.musicWhere == "computer"))

    // "pause", "resume", "next", "previous", "louder", "quieter", "like"; returns what happened
    fun control(action: String): String {
        if (ownAudio() && action in listOf("pause", "resume", "next", "previous", "like")) {
            when (action) {
                "pause" -> orbit.pause()
                "resume" -> orbit.resume()
                "next" -> orbit.next()
                "previous" -> orbit.next(back = true)
                "like" -> return "Noted: they like ${orbit.title}"
            }
            return "Done: $action (${orbit.title})"
        }
        if (onComputer()) return spotify.control(action)
        val c = controller
        val t = c?.transportControls
        when (action) {
            "pause" -> if (t != null) t.pause() else key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "resume" -> if (t != null) t.play() else key(KeyEvent.KEYCODE_MEDIA_PLAY)
            "next" -> if (t != null) t.skipToNext() else key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> if (t != null) t.skipToPrevious() else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "louder", "quieter" -> {
                val way = if (action == "louder") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, way, 0) }
            }
            "like" -> return like()
            else -> return "Unknown action"
        }
        return "Done: $action"
    }

    // The same, from a tap or gesture (Spotify answers over the internet, so not on the screen's thread)
    fun controlSoon(action: String) {
        thread(name = "music-control") { control(action) }
    }

    // Pauses whatever's playing, right away (the quick "quiet!")
    fun pauseNow() {
        if (orbit.playing) orbit.pause()
        if (spotify.playing) thread(name = "music-pause") { spotify.control("pause") }
        if (phonePlaying) {
            val c = controller
            if (c != null) c.transportControls.pause() else key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            phonePlaying = false
        }
        cancelSleepTimer()
    }

    private fun key(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // 👍: "like" the song, if the music app offers a like/heart/save button
    private fun like(): String {
        val c = controller ?: return "No music app to like it in"
        val action = c.playbackState?.customActions?.firstOrNull { a ->
            val words = (a.action + " " + a.name).lowercase()
            listOf("like", "heart", "thumb", "favo", "save", "add").any { it in words } && "dislike" !in words
        } ?: return "The music app doesn't let Orbit like songs (but I'll remember you like it)"
        c.transportControls.sendCustomAction(action, null)
        return "Liked it in ${appName.ifBlank { "the music app" }}"
    }

    // "Play some jazz": plays music matching the search. Returns what happened, for Gemini to say.
    fun play(query: String): String {
        makeAudible()
        if (onComputer() || (settings.musicWhere == "computer" && spotify.connected)) return spotify.play(query)
        val wanted = MUSIC_APPS.firstOrNull { it.first == settings.musicApp }?.third
        // An app that's already open can be asked directly (Orbit stays on screen)
        val open = try {
            if (hasAccess()) sessions.getActiveSessions(listener) else emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }.firstOrNull { wanted == null || it.packageName == wanted }
        if (open != null && query.isNotBlank()) {
            open.transportControls.playFromSearch(query, Bundle())
            return "Asked ${label(open.packageName)} to play \"$query\""
        }
        // Otherwise open the music app with the search; it comes to the front for a moment
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Pick one app ourselves (the chosen one, else the first linked app that takes requests),
        // so Android never pops up "open with…"
        val target = wanted ?: MUSIC_APPS.mapNotNull { it.third }.firstOrNull { pkg ->
            isInstalled(pkg) && Intent(intent).setPackage(pkg).resolveActivity(context.packageManager) != null
        }
        if (target != null) intent.setPackage(target)
        if (target == null || intent.resolveActivity(context.packageManager) == null) {
            // No music app: the radio can still play something like it
            return orbit.playRadio(query) + ". (No music app is set up, so it's the radio: it can't pick specific songs.)"
        }
        orbit.stop()
        if (settings.pinned) return "Orbit is locked on screen, so it can't open the music app. Open it once first."
        return try {
            context.startActivity(intent)
            // ...then come back to the eyes (Android allows this when "Display over other apps" is on)
            handler.postDelayed({
                try {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                } catch (_: Exception) {
                }
            }, 4000)
            "Opening the music app to play \"$query\""
        } catch (e: Exception) {
            "Couldn't open the music app: ${e.message}"
        }
    }

    // What's on, in words (for Gemini)
    // Internet radio ("play some jazz") and soundscapes ("play rain"), straight from Orbit
    fun playRadio(what: String): String {
        makeAudible()
        return orbit.playRadio(what)
    }

    fun playSounds(kind: String): String {
        makeAudible()
        return orbit.playSoundscape(kind)
    }

    fun describe(): String = when {
        ownAudio() && orbit.playing -> "Playing ${if (orbit.kind == "radio") "internet radio" else "Orbit's soundscape"}: ${orbit.title}"
        ownAudio() -> "Paused: ${orbit.title}"
        spotify.playing -> "Playing on the computer (Spotify): \"${spotify.title}\" by ${spotify.artist}"
        phonePlaying && title.isNotBlank() -> "Playing on the phone ($appName): \"$title\" by $artist"
        title.isNotBlank() -> "Paused: \"$title\" by $artist ($appName)"
        else -> "Nothing is playing"
    }

    // ---- Quieter while you talk ----

    fun duck() {
        if (!settings.musicDuck) return
        orbit.duck(true)
        if (volumeBeforeDuck >= 0) return
        if (spotify.playing) spotify.duck()
        if (!phonePlaying) return
        // (if it was still coming back up from the last time, remember where it was heading)
        val now = if (rampTo >= 0) rampTo else audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        rampTo = -1
        volumeBeforeDuck = now
        duckedTo = maxOf(1, (now * 0.12f).toInt())  // way down, so you can hear each other
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, duckedTo, 0)
    }

    fun unduck() {
        orbit.duck(false)
        spotify.unduck()
        if (volumeBeforeDuck < 0) return
        // Back up a step at a time over about a second (not a sudden blast), and only if nobody
        // changed the volume meanwhile
        val from = duckedTo
        val to = volumeBeforeDuck
        volumeBeforeDuck = -1
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != from) return
        val steps = (to - from).coerceAtLeast(1)
        rampTo = to
        for (i in 1..steps) {
            handler.postDelayed({
                val expected = from + i - 1
                if (rampTo == to && volumeBeforeDuck < 0 && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == expected) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(to, from + i), 0)
                    if (i == steps) rampTo = -1
                } else if (rampTo == to) {
                    rampTo = -1  // someone changed the volume: leave it alone
                }
            }, i * 1000L / steps)
        }
    }

    // Starting music with the phone's media volume at zero would be silent: bring it up to 40 %
    fun makeAudible() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val usual = if (volumeBeforeDuck >= 0) volumeBeforeDuck else audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (usual > 0) return
        val loud = maxOf(1, (max * 0.4f).toInt())
        if (volumeBeforeDuck >= 0) volumeBeforeDuck = loud else audio.setStreamVolume(AudioManager.STREAM_MUSIC, loud, 0)
    }

    // ---- Sleep timer ----

    fun sleepTimer(minutes: Double): String {
        if (minutes <= 0) {
            cancelSleepTimer()
            return "Sleep timer off"
        }
        stopAt = System.currentTimeMillis() + (minutes * 60_000).toLong()
        return "The music will fade out and stop in ${minutes.toInt()} minutes"
    }

    private fun cancelSleepTimer() {
        orbit.fade(1f)
        if (volumeBeforeFade >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBeforeFade, 0)
        volumeBeforeFade = -1
        stopAt = 0L
    }

    // How bright the eyes should be as the sleep timer runs out: 1, dimming to 0.4 over the
    // last 5 minutes
    fun sleepDim(now: Long): Float {
        if (stopAt == 0L) return 1f
        val left = (stopAt - now).coerceAtLeast(0L)
        return 0.4f + 0.6f * (left / 300_000f).coerceIn(0f, 1f)
    }

    // Called every second: fades the music over the last 30 seconds, then stops it
    fun tick(now: Long) {
        if (stopAt == 0L) return
        val left = stopAt - now
        if (left <= 0) {
            pauseNow()  // also puts the volume back
            return
        }
        if (left < 30_000 && orbit.playing) orbit.fade(left / 30_000f)
        if (left < 30_000 && phonePlaying) {
            if (volumeBeforeFade < 0) volumeBeforeFade = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeBeforeFade * left / 30_000f).toInt(), 0)
        }
    }

    // ---- Memory ----

    // A few lines about what they listen to, for the conversation
    fun memorySummary(): String {
        if (!settings.musicMemory) return ""
        val plays = settings.musicLog.lines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size >= 5) p else null
        }
        if (plays.size < 5) return ""
        fun top(list: List<List<String>>) = list.filter { it[4].isNotBlank() }
            .groupingBy { it[4] }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        val parts = mutableListOf("Music they play most: ${top(plays).joinToString()}.")
        top(plays.filter { it[2] == "1" }).takeIf { it.isNotEmpty() }?.let {
            parts += "While focusing they often play ${it.joinToString()}."
        }
        top(plays.filter { (it[1].toIntOrNull() ?: 12) < 12 }).firstOrNull()?.let { parts += "In the mornings: $it." }
        top(plays.filter { (it[1].toIntOrNull() ?: 0) >= 18 }).firstOrNull()?.let { parts += "In the evenings: $it." }
        return parts.joinToString(" ")
    }

    // What they usually play while focusing, if anything
    fun focusFavourite(): String? {
        val plays = settings.musicLog.lines().map { it.split("|") }.filter { it.size >= 5 && it[2] == "1" && it[4].isNotBlank() }
        return plays.groupingBy { it[4] }.eachCount().maxByOrNull { it.value }?.key
    }

    // Is this one of their most-played artists? (for a happy look now and then)
    fun isFavourite(artist: String): Boolean {
        if (artist.isBlank() || artist == "radio" || artist == "Orbit sounds") return false
        val counts = settings.musicLog.lines().map { it.split("|") }.filter { it.size >= 5 }.groupingBy { it[4] }.eachCount()
        return counts.entries.sortedByDescending { it.value }.take(3).any { it.key == artist && it.value >= 3 }
    }

    fun forget() {
        settings.musicLog = ""
    }
}

// The album art's main colour: the most common strong colour, at full brightness.
// Null if the art is mostly grey (black-and-white covers keep the eyes' own colours).
fun mainColor(art: Bitmap): Int? {
    val small = Bitmap.createScaledBitmap(art, 16, 16, true)
    val bins = FloatArray(12)
    val sums = Array(12) { FloatArray(3) }
    val hsv = FloatArray(3)
    for (y in 0 until 16) for (x in 0 until 16) {
        val p = small.getPixel(x, y)
        Color.colorToHSV(p, hsv)
        val weight = hsv[1] * hsv[2]  // colourful and bright counts most
        if (weight < 0.15f) continue
        val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
        bins[bin] += weight
        sums[bin][0] += Color.red(p) * weight
        sums[bin][1] += Color.green(p) * weight
        sums[bin][2] += Color.blue(p) * weight
    }
    val best = bins.indices.maxByOrNull { bins[it] } ?: return null
    if (bins[best] < 8f) return null
    val w = bins[best]
    Color.colorToHSV(Color.rgb((sums[best][0] / w).toInt(), (sums[best][1] / w).toInt(), (sums[best][2] / w).toInt()), hsv)
    hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
    hsv[2] = 1f
    return Color.HSVToColor(hsv)
}
