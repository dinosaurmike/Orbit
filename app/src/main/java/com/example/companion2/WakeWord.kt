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

// Listens for the wake words ("orbit", "orby") entirely on the phone, using Vosk, a free
// offline speech recognizer. It only listens for the wake words versus "anything else",
// which keeps it light. No sound leaves the phone for this.
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
    @Volatile private var preparing = false
    // Only touched on the worker thread
    private var recognizer: Recognizer? = null
    private var phrasesInUse = emptyList<String>()

    private val modelDir get() = File(context.filesDir, "vosk")
    private val readyMarker get() = File(modelDir, "ready")

    // Downloads the speech model (first time only) and loads it. Safe to call again.
    fun prepare() {
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
        val loaded = model ?: return
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
}

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
