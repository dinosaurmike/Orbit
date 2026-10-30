package com.example.companion2

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

// The soundscapes Orbit makes itself (no internet needed)
val SOUNDSCAPES = listOf("rain", "storm", "ocean", "wind", "fire", "brown noise", "pink noise", "ambient")

// Hand-picked stations for moods (SomaFM: listener-supported, no ads). Anything else is
// looked up in Radio Browser, a free, open directory of tens of thousands of stations.
private val CURATED = mapOf(
    "focus" to listOf("groovesalad" to "Groove Salad", "dronezone" to "Drone Zone", "deepspaceone" to "Deep Space One"),
    "chill" to listOf("groovesalad" to "Groove Salad", "lush" to "Lush", "fluid" to "Fluid"),
    "lounge" to listOf("secretagent" to "Secret Agent", "illstreet" to "Illinois Street Lounge"),
    "jazz" to listOf("sonicuniverse" to "Sonic Universe"),
    "ambient" to listOf("dronezone" to "Drone Zone", "deepspaceone" to "Deep Space One"),
    "80s" to listOf("u80s" to "Underground 80s"),
    "electronic" to listOf("beatblender" to "Beat Blender", "defcon" to "DEF CON Radio"),
    "folk" to listOf("folkfwd" to "Folk Forward"),
    "christmas" to listOf("christmas" to "Christmas Lounge")
)

// Words people use → the radio tag to look for
private val TAGS = mapOf(
    "lofi" to "lofi", "lo-fi" to "lofi", "study" to "lofi", "focus" to "focus", "concentrate" to "focus",
    "work" to "focus", "chill" to "chill", "relax" to "chill", "calm" to "chill", "jazz" to "jazz",
    "lounge" to "lounge", "ambient" to "ambient", "classical" to "classical", "piano" to "piano",
    "80s" to "80s", "eighties" to "80s", "70s" to "70s", "90s" to "90s", "rock" to "rock", "pop" to "pop",
    "country" to "country", "hip hop" to "hip hop", "rap" to "hip hop", "electronic" to "electronic",
    "dance" to "dance", "house" to "house", "techno" to "techno", "folk" to "folk", "blues" to "blues",
    "soul" to "soul", "funk" to "funk", "reggae" to "reggae", "metal" to "metal", "indie" to "indie",
    "news" to "news", "talk" to "talk", "christmas" to "christmas", "latin" to "latin"
)

// A colour for the eyes to take on, per kind of music (no album art on the radio)
private val MOOD_COLORS = mapOf(
    "focus" to 0xFF7FB8FF.toInt(), "chill" to 0xFFB794FF.toInt(), "lofi" to 0xFFB794FF.toInt(),
    "jazz" to 0xFFFFB86B.toInt(), "lounge" to 0xFFFFB86B.toInt(), "ambient" to 0xFF6BE3FF.toInt(),
    "classical" to 0xFFFFE3A3.toInt(), "rain" to 0xFF8FB8FF.toInt(), "storm" to 0xFF9AA6FF.toInt(),
    "ocean" to 0xFF5CD6FF.toInt(), "fire" to 0xFFFF9A5C.toInt(), "wind" to 0xFFBFD4E6.toInt()
)

// Music Orbit plays by itself, with no account or app: internet radio, and soundscapes it
// makes on the phone. MusicControl decides when to use this.
class OrbitAudio(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    @Volatile var title = ""           // the station or soundscape
        private set
    @Volatile var kind = ""            // "radio", "sounds", or "" when nothing's loaded
        private set
    @Volatile var paused = false
        private set
    @Volatile var color: Int? = null
        private set
    val playing get() = kind.isNotEmpty() && !paused
    // Called when a station or soundscape starts: (its name, "radio" or "sounds")
    @Volatile var onStarted: ((String, String) -> Unit)? = null

    // How loud (0..1), on top of the phone's media volume: lowered while you talk, faded by the sleep timer
    @Volatile private var duckLevel = 1f
    @Volatile private var duckTarget = 1f
    // Radio: did the station start? (null = still trying; false = none of them would play)
    @Volatile private var started: Boolean? = null
    private var failures = 0
    @Volatile private var fadeLevel = 1f
    private val level get() = duckLevel * fadeLevel

    // Radio
    private var player: MediaPlayer? = null
    private var stations: List<Pair<String, String>> = emptyList()  // (name, stream address)
    private var stationIndex = 0
    private var mood = ""

    // Soundscapes
    @Volatile private var generation = 0
    private var soundscape = ""

    // ---- Radio ----

    // "jazz", "something chill", "BBC Radio 4": finds stations and starts the first that works.
    // Waits on the internet, so call from a background thread. Returns what happened, in words.
    fun playRadio(what: String): String {
        val words = what.lowercase()
        mood = TAGS.entries.firstOrNull { words.contains(it.key) }?.value ?: ""
        val found = mutableListOf<Pair<String, String>>()
        CURATED[mood]?.forEach { (id, name) -> found += "SomaFM $name" to "https://ice1.somafm.com/$id-128-mp3" }
        try {
            found += if (mood.isNotEmpty()) search("tag=${Uri.encode(mood)}") else search("name=${Uri.encode(what.trim())}")
            if (found.isEmpty()) found += search("tag=${Uri.encode(words.trim())}")
        } catch (e: Exception) {
            Log.w("Orbit", "Radio search failed", e)
            if (found.isEmpty()) return "Couldn't reach the radio directory (is the internet on?)"
        }
        if (found.isEmpty()) return "Couldn't find a radio station for \"$what\". Try a genre, like jazz or lofi."
        stations = found.distinctBy { it.second }
        stationIndex = 0
        started = null
        failures = 0
        main.post { startStation() }
        color = MOOD_COLORS[mood]
        // Wait until a station is really playing (or they all failed), so Orbit only says it's
        // playing when you can actually hear it
        val giveUpAt = System.currentTimeMillis() + 15_000
        while (started == null && System.currentTimeMillis() < giveUpAt) Thread.sleep(200)
        val kindOfMusic = if (mood.isEmpty()) what else mood
        return when (started) {
            true -> "Playing $title (internet radio, $kindOfMusic)"
            false -> "None of the radio stations would play just now (the internet may be slow). Say so briefly."
            null -> "Starting $title (internet radio, $kindOfMusic); it may take a few seconds to begin"
        }
    }

    // Radio Browser: the most popular working stations that stream securely (https)
    private fun search(query: String): List<Pair<String, String>> {
        val servers = listOf("de1", "at1", "nl1")
        for (server in servers) {
            try {
                val request = Request.Builder()
                    .url(
                        "https://$server.api.radio-browser.info/json/stations/search?$query" +
                            "&order=clickcount&reverse=true&hidebroken=true&limit=40"
                    )
                    .header("User-Agent", "Orbit/1.0 (github.com/dinosaurmike/Orbit)")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val list = JSONArray(response.body?.string().orEmpty())
                    return (0 until list.length()).map { list.getJSONObject(it) }
                        .map { it.optString("name").trim() to it.optString("url_resolved") }
                        .filter { it.second.startsWith("https://") && it.first.isNotBlank() }
                        .take(12)
                }
            } catch (e: Exception) {
                Log.w("Orbit", "Radio server $server didn't answer", e)
            }
        }
        return emptyList()
    }

    // Starts the current station (on the main thread); a station that won't play moves on to the next
    private fun startStation() {
        stopSoundscape()
        releasePlayer()
        val (name, address) = stations.getOrNull(stationIndex) ?: return
        title = name
        kind = "radio"
        paused = false
        askForAudio()
        onStarted?.invoke(name, "radio")
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(address)
            mp.setOnPreparedListener {
                it.setVolume(level, level)
                if (!paused) it.start()
                started = true
                failures = 0
            }
            mp.setOnErrorListener { _, _, _ ->
                stationFailed()
                true
            }
            mp.setOnCompletionListener { nextStation() }  // a stream that ends has dropped
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("Orbit", "Station $name failed", e)
            stationFailed()
        }
    }

    // A station wouldn't play: try the next, unless every one has failed in a row
    private fun stationFailed() {
        failures++
        if (failures >= stations.size) {
            Log.w("Orbit", "No radio station would play")
            started = false
            kind = ""
            title = ""
            main.post { releasePlayer() }
            audio.abandonAudioFocusRequest(focus)
        } else {
            nextStation()
        }
    }

    private fun nextStation() {
        if (stations.isEmpty() || kind != "radio") return
        stationIndex = (stationIndex + 1) % stations.size
        main.post { startStation() }
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.reset()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
    }

    // ---- Soundscapes ----

    fun playSoundscape(name: String): String {
        val wanted = SOUNDSCAPES.firstOrNull { name.lowercase().contains(it.substringBefore(" ")) }
            ?: when {
                "noise" in name.lowercase() || "white" in name.lowercase() -> "pink noise"
                "thunder" in name.lowercase() -> "storm"
                "wave" in name.lowercase() || "sea" in name.lowercase() -> "ocean"
                "fireplace" in name.lowercase() || "camp" in name.lowercase() -> "fire"
                else -> "ambient"
            }
        main.post {
            releasePlayer()
            stopSoundscape()
            soundscape = wanted
            title = wanted.replaceFirstChar { it.uppercase() } + if (wanted.endsWith("noise")) "" else " sounds"
            kind = "sounds"
            paused = false
            color = MOOD_COLORS[wanted.substringBefore(" ")]
            askForAudio()
            onStarted?.invoke(title, "sounds")
            val mine = ++generation
            thread(name = "soundscape") { synthesize(wanted, mine) }
        }
        return "Playing $wanted (made by Orbit, no internet needed)"
    }

    private fun stopSoundscape() {
        generation++
    }

    // Makes the soundscape, a moment at a time, until it's stopped. Everything is generated
    // from noise and simple tones, gently filtered, so it never repeats and needs no files.
    private fun synthesize(name: String, mine: Int) {
        val rate = 32_000
        val block = 1024
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(block * 4 * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w("Orbit", "Couldn't start the soundscape", e)
            return
        }
        track.play()
        val out = ShortArray(block * 2)
        val maker = Soundscape(name, rate)
        var shown = 0f  // fades in over 3 seconds, out over 1
        while (true) {
            val stopping = mine != generation
            val target = if (stopping || paused) 0f else level
            for (i in 0 until block) {
                shown += (target - shown) * (if (target > shown) 1f / (3f * rate) * 4f else 1f / rate * 4f)
                val (l, r) = maker.next()
                out[2 * i] = (tanh(l) * 0.8f * shown * 32767f).toInt().toShort()
                out[2 * i + 1] = (tanh(r) * 0.8f * shown * 32767f).toInt().toShort()
            }
            track.write(out, 0, out.size)
            if (stopping && shown < 0.001f) break
            if (paused && shown < 0.001f) {
                // Paused: hold quietly until resumed or stopped
                while (paused && mine == generation) Thread.sleep(100)
            }
        }
        try {
            track.stop()
        } catch (_: Exception) {
        }
        track.release()
    }

    // ---- Controls ----

    fun pause() {
        if (kind.isEmpty()) return
        paused = true
        main.post { player?.let { if (it.isPlaying) it.pause() } }
    }

    fun resume() {
        if (kind.isEmpty()) return
        paused = false
        askForAudio()
        main.post {
            if (kind == "radio") {
                val p = player
                if (p == null) startStation() else try {
                    p.start()
                } catch (e: Exception) {
                    startStation()
                }
            }
        }
    }

    // Next station (radio) or next soundscape
    fun next(back: Boolean = false) {
        when (kind) {
            "radio" -> {
                if (stations.isEmpty()) return
                stationIndex = (stationIndex + (if (back) stations.size - 1 else 1)) % stations.size
                main.post { startStation() }
            }
            "sounds" -> {
                val i = SOUNDSCAPES.indexOf(soundscape)
                playSoundscape(SOUNDSCAPES[(i + (if (back) SOUNDSCAPES.size - 1 else 1)) % SOUNDSCAPES.size])
            }
        }
    }

    fun stop() {
        kind = ""
        title = ""
        paused = false
        stopSoundscape()
        main.post { releasePlayer() }
        audio.abandonAudioFocusRequest(focus)
    }

    // Quieter while you talk: down quickly, back up gently (about a second)
    fun duck(on: Boolean) {
        val target = if (on) 0.12f else 1f
        if (target == duckTarget) return
        duckTarget = target
        main.removeCallbacks(ramp)
        main.post(ramp)
    }

    private val ramp = object : Runnable {
        override fun run() {
            val step = if (duckTarget < duckLevel) 0.25f else 0.05f
            duckLevel = if (duckTarget < duckLevel) maxOf(duckTarget, duckLevel - step) else minOf(duckTarget, duckLevel + step)
            player?.setVolume(level, level)
            if (duckLevel != duckTarget) main.postDelayed(this, 50)
        }
    }

    // Sleep timer fade (1 = full, 0 = silent)
    fun fade(to: Float) {
        fadeLevel = to.coerceIn(0f, 1f)
        applyLevel()
    }

    private fun applyLevel() {
        main.post { player?.setVolume(level, level) }
    }

    // Asks Android for the speaker, so another music app pauses (and if one starts later, we pause)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) pause()
        }
        .build()

    private fun askForAudio() {
        audio.requestAudioFocus(focus)
    }
}

// One soundscape's sound, a sample at a time (left, right). Kept simple: noise through gentle
// filters, slow swells, and little random events (drops, crackles, gusts, waves).
private class Soundscape(private val name: String, private val rate: Int) {
    private var seed = 0x2545F491L
    private fun white(): Float {  // fast random -1..1
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
        return ((seed and 0xFFFFFF).toFloat() / 0xFFFFFF) * 2f - 1f
    }

    // Pink-ish noise (softer than white), one per ear so it sounds wide
    private val pinkL = FloatArray(3)
    private val pinkR = FloatArray(3)
    private fun pink(b: FloatArray): Float {
        val w = white()
        b[0] = 0.997f * b[0] + 0.029591f * w
        b[1] = 0.985f * b[1] + 0.032534f * w
        b[2] = 0.950f * b[2] + 0.048056f * w
        return (b[0] + b[1] + b[2] + 0.05f * w) * 3f
    }

    private var brownL = 0f
    private var brownR = 0f
    private fun brown(previous: Float) = ((previous + 0.02f * white()) / 1.02f)

    private var lowL = 0f
    private var lowR = 0f
    private var t = 0L  // samples so far

    // Little events: rain drops, crackles (frequency, phase, loudness, how fast they fade)
    private val drops = Array(24) { FloatArray(5) }  // freq, phase, amp, decay, pan
    private fun addDrop(freqLow: Float, freqHigh: Float, amp: Float, decaySeconds: Float) {
        val d = drops.minByOrNull { it[2] } ?: return
        d[0] = freqLow + (freqHigh - freqLow) * (white() * 0.5f + 0.5f)
        d[1] = 0f
        d[2] = amp
        d[3] = exp(-1f / (decaySeconds * rate))
        d[4] = white() * 0.5f + 0.5f
    }

    private fun dropsOut(): Pair<Float, Float> {
        var l = 0f
        var r = 0f
        for (d in drops) {
            if (d[2] < 0.0005f) continue
            d[1] += 2f * PI.toFloat() * d[0] / rate
            val v = sin(d[1]) * d[2]
            d[2] *= d[3]
            l += v * (1f - d[4])
            r += v * d[4]
        }
        return l to r
    }

    // Slow changes (waves, gusts, thunder)
    private var swell = 0f
    private var thunder = 0f
    private var thunderLeft = 0
    private val chords = listOf(
        floatArrayOf(261.6f, 329.6f, 392.0f, 493.9f),  // C major 7
        floatArrayOf(220.0f, 261.6f, 329.6f, 493.9f),  // A minor 9-ish
        floatArrayOf(174.6f, 220.0f, 261.6f, 329.6f),  // F major 7
        floatArrayOf(196.0f, 246.9f, 293.7f, 329.6f)   // G 6
    )
    private val phases = FloatArray(16)

    fun next(): Pair<Float, Float> {
        t++
        val seconds = t.toFloat() / rate
        val chance = white() * 0.5f + 0.5f
        return when (name) {
            "rain", "storm" -> {
                val heavy = if (name == "storm") 1.6f else 1f
                // a soft hiss of rain, plus individual drops landing
                lowL += 0.25f * (pink(pinkL) - lowL)
                lowR += 0.25f * (pink(pinkR) - lowR)
                if (chance < 60f * heavy / rate) addDrop(900f, 3800f, 0.05f + 0.07f * (white() * 0.5f + 0.5f), 0.012f)
                val (dl, dr) = dropsOut()
                var l = lowL * 0.22f * heavy + dl
                var r = lowR * 0.22f * heavy + dr
                if (name == "storm") {  // now and then, distant thunder rolling in and out
                    if (thunderLeft <= 0 && chance < 1f / (35f * rate)) thunderLeft = rate * 6
                    if (thunderLeft > 0) {
                        thunderLeft--
                        val p = 1f - thunderLeft / (rate * 6f)
                        val env = sin(PI.toFloat() * p) * (0.6f + 0.4f * sin(p * 23f))
                        brownL = brown(brownL)
                        thunder += 0.05f * (brownL - thunder)
                        l += thunder * 6f * env
                        r += thunder * 6f * env
                    }
                }
                l to r
            }
            "ocean" -> {
                // waves: a swell of noise every 8-ish seconds, brighter as each one breaks
                val wave = sin(2f * PI.toFloat() * seconds / 8.5f) * 0.5f + 0.5f
                swell += 0.0002f * (wave * wave - swell)
                val bright = 0.02f + 0.18f * swell
                lowL += bright * (pink(pinkL) - lowL)
                lowR += bright * (pink(pinkR) - lowR)
                val loud = 0.08f + 0.3f * swell
                lowL * loud to lowR * loud
            }
            "wind" -> {
                // gusts: slowly wandering loudness and pitch
                swell += 0.00002f * ((sin(seconds * 0.31f) * sin(seconds * 0.17f + 1f)) * 0.5f + 0.5f - swell) * 50f
                val bright = 0.01f + 0.05f * swell
                lowL += bright * (pink(pinkL) - lowL)
                lowR += bright * (pink(pinkR) - lowR)
                val loud = 0.09f + 0.25f * swell
                lowL * loud to lowR * loud
            }
            "fire" -> {
                // a low warm rumble, a soft hiss, and crackles and pops
                brownL = brown(brownL)
                brownR = brown(brownR)
                if (chance < 9f / rate) addDrop(1500f, 5000f, 0.08f + 0.12f * (white() * 0.5f + 0.5f), 0.002f)
                if (chance < 0.6f / rate) addDrop(300f, 700f, 0.25f, 0.006f)  // a pop
                val (dl, dr) = dropsOut()
                val flicker = 0.7f + 0.3f * sin(seconds * 1.3f) * sin(seconds * 0.7f)
                (brownL * 4.5f * flicker + white() * 0.01f + dl) to (brownR * 4.5f * flicker + white() * 0.01f + dr)
            }
            "brown noise" -> {
                brownL = brown(brownL)
                brownR = brown(brownR)
                brownL * 3.5f to brownR * 3.5f
            }
            "pink noise" -> pink(pinkL) * 0.2f to pink(pinkR) * 0.2f
            else -> {  // "ambient": slow, soft chords drifting from one to the next, with a breath of air
                val chordLength = 16f
                val position = seconds / chordLength
                val index = position.toInt()
                val blend = ((position - index) - 0.75f).coerceAtLeast(0f) / 0.25f  // crossfade over the last quarter
                var l = 0f
                var r = 0f
                for (c in 0..1) {
                    val chord = chords[(index + c) % chords.size]
                    val gain = if (c == 0) 1f - blend else blend
                    if (gain <= 0f) continue
                    chord.forEachIndexed { k, f ->
                        val slot = c * 8 + k * 2
                        phases[slot] += 2f * PI.toFloat() * f / rate
                        phases[slot + 1] += 2f * PI.toFloat() * f * 1.003f / rate  // slightly out of tune: shimmer
                        if (phases[slot] > 1000f) phases[slot] -= 2f * PI.toFloat() * 150f
                        if (phases[slot + 1] > 1000f) phases[slot + 1] -= 2f * PI.toFloat() * 150f
                        val breathe = 0.75f + 0.25f * sin(seconds * (0.21f + k * 0.07f))
                        val v = (sin(phases[slot]) + sin(phases[slot + 1])) * 0.09f * gain * breathe
                        l += v * (1f - k * 0.15f)
                        r += v * (0.55f + k * 0.15f)
                    }
                }
                lowL += 0.02f * (pink(pinkL) - lowL)
                (l + lowL * 0.08f) to (r + lowL * 0.08f)
            }
        }
    }
}
