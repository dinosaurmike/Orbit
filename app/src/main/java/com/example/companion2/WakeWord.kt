package com.example.companion2

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

// Vosk's small English speech model (free, offline). Downloaded once, on first use.
const val WAKE_MODEL_NAME = "vosk-model-small-en-us-0.15"
const val WAKE_MODEL_URL = "https://alphacephei.com/vosk/models/$WAKE_MODEL_NAME.zip"

// Some wake words aren't in the speech model's dictionary, or it tends to hear them as two
// words, so they're listened for as the everyday words they sound like
private val ORBY = listOf("or be", "or bee", "orb e", "or b")
private val SOUNDS_LIKE = mapOf(
    "orby" to ORBY, "orbie" to ORBY, "orbee" to ORBY,
    "orbit" to listOf("orbit", "or bit", "orb it")
)

// The wake words from settings ("orbit, orby"), as the speech model should hear them
fun wakePhrases(text: String) = text.split(",")
    .map { it.trim().lowercase() }
    .filter { it.isNotEmpty() }
    .flatMap { SOUNDS_LIKE[it] ?: listOf(it) }
    .distinct()

// True if every wake word is one of Orbit's own names, which its fast ear knows
fun onlyOrbitNames(text: String) = text.split(",")
    .map { it.trim().lowercase() }
    .filter { it.isNotEmpty() }
    .let { words -> words.isNotEmpty() && words.all { it in OrbitEar.NAMES } }

// Listens for the wake words entirely on the phone. No sound leaves the phone for this.
// For Orbit's own names it uses its fast ear (OrbitEar: a small model trained just for "Orbit",
// which fires the moment the name ends). For any other wake word it uses Vosk, a free offline
// speech recognizer listening for the wake words versus "anything else".
class WakeWord(private val context: Context, private val settings: Settings) {
    @Volatile var status = "Off"                 // shown on the settings screen
    @Volatile var onHeard: (() -> Unit)? = null  // called (on a background thread) when heard
    // Called as soon as it hears what might be the start of its name ("or…"), before it's sure:
    // so music can drop straight away while you're still saying it
    @Volatile var onMaybe: (() -> Unit)? = null
    // For checking it works (shown on the settings screen): what it last heard, when sound last
    // reached it, and when it last heard the wake word
    @Volatile var lastHeard = ""
    @Volatile var lastSoundAt = 0L
    @Volatile var lastWakeAt = 0L
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger(0)
    @Volatile private var model: Model? = null
    @Volatile private var ear: OrbitEar? = null
    // Learning your voice (Settings → Orbit → Teach it your voice): its own ear, fresh each time
    @Volatile var training: VoiceTraining? = null
        private set
    @Volatile private var trainingEar: OrbitEar? = null
    val myVoiceFolder get() = File(context.filesDir, "my_voice")
    // Listening with its own fast model right now (not the speech recognizer)
    val fastEarActive get() = ear != null && settings.fastWake && onlyOrbitNames(settings.wakePhrase)
    // Only touched on the worker thread: when the fast ear last fired or last thought "maybe"
    private var earFiredAt = 0L
    private var earMaybeAt = 0L
    private var earStreak = 0  // how many 80 ms steps in a row have sounded sure
    @Volatile private var preparing = false
    // Only touched on the worker thread
    private var recognizer: Recognizer? = null
    private var phrasesInUse = emptyList<String>()

    private val modelDir get() = File(context.filesDir, "vosk")
    private val readyMarker get() = File(modelDir, "ready")

    // Downloads the speech model (first time only) and loads it. Safe to call again.
    fun prepare() {
        if (settings.fastWake && onlyOrbitNames(settings.wakePhrase)) {
            if (ear == null) {
                try {
                    ear = OrbitEar(context)
                } catch (e: Exception) {
                    Log.e("Orbit", "Fast wake word failed to load; using the speech recognizer", e)
                }
            }
            if (ear != null) {
                status = "Ready (fast ear) · say \"${settings.wakePhrase.trim()}\""
                return
            }
        }
        if (model != null) {
            status = "Ready · say \"${settings.wakePhrase.trim()}\""
            return
        }
        if (preparing) return
        preparing = true
        thread(name = "wake-word-setup") {
            try {
                if (!readyMarker.exists()) download()
                status = "Loading the speech model…"
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                model = Model(File(modelDir, WAKE_MODEL_NAME).absolutePath)
                status = "Ready · say \"${settings.wakePhrase.trim()}\""
            } catch (e: Exception) {
                Log.e("Orbit", "Wake word setup failed", e)
                status = "Couldn't set up the wake word: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                preparing = false
            }
        }
    }

    private fun download() {
        status = "Downloading the speech model (about 40 MB)…"
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        val request = Request.Builder().url(WAKE_MODEL_URL).build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("download failed (error ${response.code})")
            val body = response.body ?: throw IOException("download was empty")
            val total = body.contentLength()
            val counted = CountingStream(body.byteStream()) { done ->
                if (total > 0) status = "Downloading the speech model… ${done * 100 / total}%"
            }
            ZipInputStream(counted.buffered()).use { zip -> unzip(zip) }
        }
        readyMarker.writeText("ok")
    }

    private fun unzip(zip: ZipInputStream) {
        val root = modelDir.canonicalPath + File.separator
        var entry = zip.nextEntry
        while (entry != null) {
            val out = File(modelDir, entry.name)
            // Never write outside the model folder, whatever the download contains
            if (!out.canonicalPath.startsWith(root)) throw IOException("unexpected file in download")
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
            entry = zip.nextEntry
        }
    }

    // Called with each 50 ms of microphone sound while no conversation is open
    fun listen(sound: ByteArray) {
        // Learning your voice: the sound goes to the training (and it doesn't wake meanwhile)
        training?.let { t ->
            if (queued.get() > 20) return
            queued.incrementAndGet()
            worker.execute {
                try {
                    val ear = trainingEar ?: return@execute
                    if (t.feed(sound, ear.accept(sound))) finishTraining(t)
                } catch (e: Exception) {
                    Log.e("Orbit", "Voice training failed", e)
                } finally {
                    queued.decrementAndGet()
                }
            }
            return
        }
        val fast = ear?.takeIf { settings.fastWake && onlyOrbitNames(settings.wakePhrase) }
        if (fast != null) {
            hear(fast, sound)
            return
        }
        val loaded = model
        if (loaded == null) {
            // Switched from the fast ear to other wake words: get the speech recognizer ready
            if (!preparing) prepare()
            return
        }
        lastSoundAt = System.currentTimeMillis()
        // If the phone falls behind, skip sound rather than pile it up
        if (queued.get() > 20) return
        queued.incrementAndGet()
        worker.execute {
            try {
                val phrases = wakePhrases(settings.wakePhrase)
                if (phrases.isEmpty()) return@execute
                if (recognizer == null || phrases != phrasesInUse) {
                    recognizer?.close()
                    // Only the wake words, or "[unk]" (anything else)
                    recognizer = Recognizer(loaded, 16000f, JSONArray(phrases + "[unk]").toString())
                    phrasesInUse = phrases
                }
                val r = recognizer ?: return@execute
                val finished = r.acceptWaveForm(sound, sound.size)
                val result = JSONObject(if (finished) r.result else r.partialResult)
                val heard = result.optString("text").ifEmpty { result.optString("partial") }
                if (heard.isNotBlank()) lastHeard = heard
                val firstWords = phrases.map { it.substringBefore(" ") }.toSet()
                if (heard.split(" ").any { it in firstWords }) onMaybe?.invoke()
                if (phrases.any { heard.contains(it) }) {
                    r.reset()
                    lastWakeAt = System.currentTimeMillis()
                    onHeard?.invoke()
                }
            } catch (e: Exception) {
                Log.e("Orbit", "Wake word listening failed", e)
            } finally {
                queued.decrementAndGet()
            }
        }
    }

    // Starts learning your voice: say its name 10 times. Returns the training to watch.
    fun startTraining(): VoiceTraining? {
        val fresh = try {
            OrbitEar(context)
        } catch (e: Exception) {
            Log.e("Orbit", "Couldn't start voice training", e)
            return null
        }
        stopTraining()
        myVoiceFolder.deleteRecursively()  // new takes replace the old ones
        trainingEar = fresh
        return VoiceTraining(myVoiceFolder).also { training = it }
    }

    fun stopTraining() {
        training = null
        val old = trainingEar
        trainingEar = null
        worker.execute { old?.close() }
    }

    // All 10 takes are in: use the new setting, and go back to listening as usual
    private fun finishTraining(t: VoiceTraining) {
        settings.earThreshold = t.threshold
        stopTraining()
        ear?.reset()
    }

    // The fast ear: scores the sound every 80 ms. Answers once, then ignores itself for a moment
    // (so one "Orbit" can't wake it twice).
    private fun hear(fast: OrbitEar, sound: ByteArray) {
        val now0 = System.currentTimeMillis()
        // After a gap (a conversation, sleep), start fresh rather than join old sound to new
        val gap = now0 - lastSoundAt > 500
        lastSoundAt = now0
        if (queued.get() > 20) return
        queued.incrementAndGet()
        worker.execute {
            try {
                if (gap) {
                    fast.reset()
                    earStreak = 0
                }
                val score = fast.accept(sound) ?: return@execute
                val now = System.currentTimeMillis()
                if (now - earFiredAt < 1500) return@execute
                if (score >= EAR_MAYBE) {
                    lastHeard = "sounded %d%% like its name".format((score * 100).toInt())
                    if (now - earMaybeAt > 1000) {
                        earMaybeAt = now
                        onMaybe?.invoke()
                    }
                }
                // Sure twice in a row (160 ms): a single blip from a noise or a TV doesn't count.
                // Costs 80 ms, halves false wakes.
                // How sure it must be: standard, or lower if it learned your voice
                val sure = settings.earThreshold.coerceIn(LOWEST_EAR_THRESHOLD, STANDARD_EAR_THRESHOLD)
                earStreak = if (score >= sure) earStreak + 1 else 0
                if (earStreak >= 2) {
                    earStreak = 0
                    earFiredAt = now
                    fast.reset()
                    lastWakeAt = now
                    onHeard?.invoke()
                }
            } catch (e: Exception) {
                Log.e("Orbit", "Fast wake word listening failed", e)
            } finally {
                queued.decrementAndGet()
            }
        }
    }
}

// How sure the fast ear must be (0 to 1): enough to drop the music, enough to answer
private const val EAR_MAYBE = 0.15f

// Passes data straight through, reporting how many bytes have gone by (for download progress)
private class CountingStream(input: InputStream, private val onProgress: (Long) -> Unit) :
    FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) onProgress(++count)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            count += n
            onProgress(count)
        }
        return n
    }
}
