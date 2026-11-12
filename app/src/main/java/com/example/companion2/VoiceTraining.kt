package com.example.companion2

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

// "Teach it your voice": you say its name 10 times; for each, Orbit notes how sure its fast ear
// was (0 to 1) and keeps the recording on the phone. Then it sets how sure the ear must be to
// answer YOU: lower if your "Orby" scores lower than the voices it was trained on, never lower
// than a safe floor (so other sounds still don't wake it) and never higher than the standard.
const val STANDARD_EAR_THRESHOLD = 0.5f
const val LOWEST_EAR_THRESHOLD = 0.3f
const val TRAINING_TAKES = 10

class VoiceTraining(private val folder: File) {
    @Volatile var ready = false        // the ear has warmed up (about 2 seconds): say it now
    @Volatile var takes = 0
    @Volatile var lastScore = 0f       // how sure the ear was about the last take
    @Volatile var done = false
    @Volatile var result = ""
    @Volatile var threshold = STANDARD_EAR_THRESHOLD
    val scores = mutableListOf<Float>()

    // Working out where each take starts and ends (the wake word thread only)
    private var inTake = false
    private var peak = 0f
    private var quietMs = 0
    private var loudMs = 0
    private var latest = 0f
    private val audio = ByteArrayOutputStream()
    private val before = ArrayDeque<ByteArray>()  // a moment of sound before each take starts

    // Each 50 ms of sound, with the ear's newest score (null when it didn't make a new one).
    // Returns true when the 10th take is in.
    fun feed(sound: ByteArray, score: Float?): Boolean {
        if (done) return true
        if (score != null) {
            latest = score
            ready = true
        }
        if (!ready) return false
        val loud = level(sound) > TALKING_LEVEL
        if (!inTake && loud) {
            inTake = true
            peak = 0f
            quietMs = 0
            loudMs = 0
            audio.reset()
            before.forEach { audio.write(it) }
        }
        if (inTake) {
            audio.write(sound)
            peak = maxOf(peak, latest)
            quietMs = if (loud) 0 else quietMs + 50
            if (loud) loudMs += 50
            // A take ends after 0.6 s of quiet (the ear's score peaks just after the name ends),
            // or after 3 s, whichever comes first
            if (quietMs >= 600 || audio.size() > 16_000 * 2 * 3) {
                inTake = false
                if (loudMs >= 200) addTake(peak, audio.toByteArray())  // a word, not a click
            }
        } else {
            before.addLast(sound)
            if (before.size > 6) before.removeFirst()
        }
        return done
    }

    private fun addTake(score: Float, pcm: ByteArray) {
        scores.add(score)
        takes = scores.size
        lastScore = score
        try {
            folder.mkdirs()
            File(folder, "take_%02d.pcm".format(takes)).writeBytes(pcm)  // 16 kHz, 16-bit mono
        } catch (_: Exception) {
            // The recording is a bonus; the score is what matters
        }
        if (takes >= TRAINING_TAKES) finish()
    }

    private fun finish() {
        threshold = personalThreshold(scores)
        val before = scores.count { it >= STANDARD_EAR_THRESHOLD }
        val after = scores.count { it >= threshold }
        result = when {
            threshold >= STANDARD_EAR_THRESHOLD ->
                "It already knows your voice well: it caught all $after of ${scores.size}. No change needed."
            after > before ->
                "Before: it would have caught $before of ${scores.size}. Now: $after of ${scores.size}."
            else -> "It caught $after of ${scores.size}. Your setting is now as sensitive as is safe."
        } + if (scores.count { it < LOWEST_EAR_THRESHOLD } >= 3) {
            " Some of your takes still scored low: try saying it a little more slowly and clearly, " +
                "or use \"Orbit\", which it recognizes best."
        } else ""
        done = true
    }

    companion object {
        // How loud counts as talking (the same measure as the microphone's level)
        const val TALKING_LEVEL = 0.08f

        fun level(sound: ByteArray): Float {
            var sum = 0.0
            val n = sound.size / 2
            if (n == 0) return 0f
            for (i in 0 until n) {
                val s = ((sound[i * 2 + 1].toInt() shl 8) or (sound[i * 2].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
            }
            return (sqrt(sum / n) / 3000.0).toFloat().coerceIn(0f, 1f)
        }

        // Low enough for 4 in 5 of your takes to clear it with room to spare, but never below the
        // safe floor, and never stricter than standard
        fun personalThreshold(scores: List<Float>): Float {
            if (scores.isEmpty()) return STANDARD_EAR_THRESHOLD
            val sorted = scores.sorted()
            val low = sorted[(sorted.size * 0.2f).toInt().coerceAtMost(sorted.size - 1)]
            return (low * 0.8f).coerceIn(LOWEST_EAR_THRESHOLD, STANDARD_EAR_THRESHOLD)
        }
    }
}
