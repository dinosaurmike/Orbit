package com.example.companion2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

// Knowing who's who, entirely on the phone. When it meets someone ("Orby, this is Sarah"),
// it turns a few pictures of their face into a "fingerprint": a list of numbers from Google's
// free MediaPipe image model (about 4 MB, downloaded the first time). Later, when a face
// appears, it compares fingerprints to say who it is. No photos are kept, only the numbers,
// and nothing leaves the phone. It's good at telling a household apart, not a security system.
const val FACE_MODEL_URL =
    "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite"

class People(private val context: Context, private val settings: Settings) {
    @Volatile var status = "Off"
    @Volatile var current: String? = null      // who it thinks is in front of it right now
        private set
    @Volatile var currentAt = 0L
        private set
    @Volatile private var embedder: ImageEmbedder? = null
    @Volatile private var preparing = false
    // Meeting someone: their name, and the fingerprints collected so far
    @Volatile var meeting: String? = null
        private set
    private val samples = mutableListOf<FloatArray>()
    @Volatile var onMet: ((String, Boolean) -> Unit)? = null  // (name, it worked)

    val ready get() = embedder != null

    fun prepare() {
        if (embedder != null || preparing) return
        preparing = true
        thread(name = "people-setup") {
            try {
                status = "Downloading the face model (about 4 MB)…"
                val file = Models.get(File(context.filesDir, "models"), "face_embedder.tflite", FACE_MODEL_URL) {
                    status = "Downloading the face model… $it%"
                }
                val options = ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(Models.map(file)).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setL2Normalize(true)
                    .build()
                embedder = ImageEmbedder.createFromOptions(context, options)
                status = "Ready"
            } catch (e: Exception) {
                Log.e("Orbit", "Face model setup failed", e)
                status = "Couldn't set it up: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                preparing = false
            }
        }
    }

    // Starts meeting someone: the next few looks at the face in front of it are remembered
    fun meet(name: String) {
        samples.clear()
        meeting = name.trim().ifBlank { return }
    }

    // True when it wants a face picture (meeting someone, or not sure who's there)
    fun wantsLook(now: Long) = ready && (meeting != null || (now - currentAt > 15_000 && known().isNotEmpty()))

    // Someone just arrived: forget who was here, and look again straight away
    fun refresh() {
        current = null
        currentAt = 0L
    }

    // Gets an upright camera picture and where the face is in it
    fun look(picture: Bitmap, face: Rect) {
        val e = embedder ?: return
        val vector = try {
            // A bit around the face, so hair and face shape count too
            val grow = (face.width() * 0.25f).toInt()
            val box = Rect(
                (face.left - grow).coerceAtLeast(0), (face.top - grow).coerceAtLeast(0),
                (face.right + grow).coerceAtMost(picture.width), (face.bottom + grow).coerceAtMost(picture.height)
            )
            if (box.width() < 40 || box.height() < 40) return
            val crop = Bitmap.createBitmap(picture, box.left, box.top, box.width(), box.height())
            e.embed(BitmapImageBuilder(crop).build()).embeddingResult().embeddings()[0].floatEmbedding()
        } catch (ex: Exception) {
            Log.w("Orbit", "Face fingerprint failed", ex)
            return
        }
        val name = meeting
        if (name != null) {
            samples.add(vector)
            if (samples.size >= 5) {
                save(name, average(samples))
                meeting = null
                current = name
                currentAt = System.currentTimeMillis()
                onMet?.invoke(name, true)
            }
            return
        }
        // Who's this? The closest fingerprint, if it's close enough and clearly closer than the rest
        val scored = known().map { (person, vec) -> person to similarity(vector, vec) }.sortedByDescending { it.second }
        val best = scored.firstOrNull()
        val runnerUp = scored.getOrNull(1)?.second ?: 0f
        current = if (best != null && best.second > 0.82f && best.second - runnerUp > 0.03f) best.first else null
        currentAt = System.currentTimeMillis()
    }

    fun names(): List<String> = known().map { it.first }

    fun forget(name: String) {
        val list = load()
        val keep = JSONArray()
        for (i in 0 until list.length()) {
            if (list.getJSONObject(i).optString("name") != name) keep.put(list.getJSONObject(i))
        }
        settings.people = keep.toString()
        if (current == name) current = null
    }

    private fun known(): List<Pair<String, FloatArray>> {
        val list = load()
        return (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            val v = o.getJSONArray("vec")
            o.optString("name") to FloatArray(v.length()) { v.getDouble(it).toFloat() }
        }
    }

    private fun load(): JSONArray = try {
        JSONArray(settings.people.ifBlank { "[]" })
    } catch (e: Exception) {
        JSONArray()
    }

    private fun save(name: String, vector: FloatArray) {
        forget(name)  // meeting someone again replaces their old fingerprint
        val list = load()
        val v = JSONArray()
        vector.forEach { v.put(it.toDouble()) }
        list.put(JSONObject().put("name", name).put("vec", v))
        settings.people = list.toString()
    }

    private fun average(list: List<FloatArray>): FloatArray {
        val out = FloatArray(list[0].size)
        list.forEach { v -> for (i in out.indices) out[i] += v[i] / list.size }
        return out
    }

    private fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }
}
