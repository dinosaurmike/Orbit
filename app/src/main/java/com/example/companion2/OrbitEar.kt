package com.example.companion2

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// A small model trained just for Orbit's own name ("Orbit", "Orby", "Or-bee"), built with
// openWakeWord's method. Unlike the speech recognizer, it doesn't wait for a whole word to be
// "transcribed": every 80 ms it scores the last 1.3 s of sound, so it fires the moment the
// name ends. Runs entirely on the phone (three small ONNX models, about 3 MB, in the app).
//
// The steps, every 80 ms:
//   sound -> mel spectrogram (how loud each pitch band is, 8 new frames)
//         -> speech embedding (96 numbers describing the last 0.76 s)
//         -> Orbit model (looks at the last 16 embeddings: one score from 0 to 1)
class OrbitEar(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val melModel = load(context, "wake/melspectrogram.onnx")
    private val embedModel = load(context, "wake/embedding_model.onnx")
    private val wakeModel = load(context, "wake/orbit.onnx")

    private val sound = ShortArray(CHUNK + MEL_CONTEXT)    // this chunk plus a little before it
    private var filled = MEL_CONTEXT                         // where new sound goes in `sound`
    private val mel = ArrayDeque<FloatArray>()               // newest mel frames (32 bands each)
    private val embeddings = ArrayDeque<FloatArray>()        // newest embeddings (96 numbers each)

    // Feed it 16 kHz, 16-bit mono sound (any length). Returns the newest score (0 to 1), or
    // null if there wasn't enough new sound to score yet.
    fun accept(pcm: ByteArray): Float? {
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var score: Float? = null
        while (samples.hasRemaining()) {
            val n = minOf(samples.remaining(), sound.size - filled)
            samples.get(sound, filled, n)
            filled += n
            if (filled == sound.size) {
                score = step() ?: score
                // Keep the end of this chunk as the start of the next (the spectrogram overlaps)
                System.arraycopy(sound, CHUNK, sound, 0, MEL_CONTEXT)
                filled = MEL_CONTEXT
            }
        }
        return score
    }

    // Forget what it's heard (after it wakes, so the same "Orbit" can't wake it twice)
    fun reset() {
        mel.clear()
        embeddings.clear()
        sound.fill(0)
        filled = MEL_CONTEXT
    }

    private fun step(): Float? {
        // 1. Mel spectrogram of the newest 110 ms (the model takes raw 16-bit values as floats)
        val raw = FloatArray(sound.size) { sound[it].toFloat() }
        val frames = run(melModel, raw, longArrayOf(1, raw.size.toLong()))
        for (f in 0 until frames.size / MEL_BANDS) {
            mel.addLast(FloatArray(MEL_BANDS) { frames[f * MEL_BANDS + it] / 10f + 2f })
        }
        while (mel.size > MEL_WINDOW) mel.removeFirst()
        if (mel.size < MEL_WINDOW) return null

        // 2. One speech embedding from the last 76 mel frames
        val window = FloatArray(MEL_WINDOW * MEL_BANDS)
        mel.forEachIndexed { i, frame -> frame.copyInto(window, i * MEL_BANDS) }
        embeddings.addLast(run(embedModel, window, longArrayOf(1, MEL_WINDOW.toLong(), MEL_BANDS.toLong(), 1)))
        while (embeddings.size > EMBEDDINGS) embeddings.removeFirst()
        if (embeddings.size < EMBEDDINGS) return null

        // 3. Is that "Orbit"?
        val features = FloatArray(EMBEDDINGS * EMBEDDING_SIZE)
        embeddings.forEachIndexed { i, e -> e.copyInto(features, i * EMBEDDING_SIZE) }
        return run(wakeModel, features, longArrayOf(1, EMBEDDINGS.toLong(), EMBEDDING_SIZE.toLong()))[0]
    }

    private fun run(session: OrtSession, input: FloatArray, shape: LongArray): FloatArray {
        val name = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(name to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(out.remaining()).also { out.get(it) }
            }
        }
    }

    private fun load(context: Context, asset: String): OrtSession =
        env.createSession(context.assets.open(asset).use { it.readBytes() }, options)

    override fun close() {
        melModel.close()
        embedModel.close()
        wakeModel.close()
        options.close()
    }

    companion object {
        const val CHUNK = 1280          // 80 ms of sound at 16 kHz
        const val MEL_CONTEXT = 480     // 30 ms of the previous chunk, so frames line up
        const val MEL_BANDS = 32
        const val MEL_WINDOW = 76       // mel frames per embedding
        const val EMBEDDINGS = 16       // embeddings the Orbit model looks at (1.28 s)
        const val EMBEDDING_SIZE = 96
        // The names this model knows. Other wake words still use the speech recognizer.
        val NAMES = setOf("orbit", "orby", "orbie", "orbee", "or-bee", "or bee")
    }
}
