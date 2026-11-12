package com.example.companion2

import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTrainingTest {
    // 50 ms of a 220 Hz tone (loud = "talking") or silence, as 16-bit little-endian PCM
    private fun chunk(loud: Boolean): ByteArray {
        val out = ByteArray(1600)
        for (i in 0 until 800) {
            val s = if (loud) (sin(2 * PI * 220 * i / 16000) * 12000).toInt() else 0
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = (s shr 8).toByte()
        }
        return out
    }

    // Feeds one take: 0.6 s of "speech" with the ear's score rising to `peak`, then 1 s of quiet
    private fun say(t: VoiceTraining, peak: Float): Boolean {
        var finished = false
        for (i in 0 until 12) finished = t.feed(chunk(true), if (i % 2 == 0) peak * i / 11f else null) || finished
        for (i in 0 until 20) finished = t.feed(chunk(false), if (i == 1) peak else if (i % 2 == 0) 0.02f else null) || finished
        return finished
    }

    @Test
    fun findsTenTakesAndLearnsYourVoice() {
        val dir = Files.createTempDirectory("myvoice").toFile()
        val t = VoiceTraining(dir)
        // Before the ear has warmed up (no scores yet), nothing counts
        repeat(10) { t.feed(chunk(true), null) }
        assertEquals(0, t.takes)
        assertFalse(t.ready)
        t.feed(chunk(false), 0f)
        assertTrue(t.ready)
        // A quiet-ish voice: its "Orby" scores 0.35–0.8
        val peaks = listOf(0.8f, 0.35f, 0.6f, 0.45f, 0.7f, 0.4f, 0.55f, 0.65f, 0.38f, 0.75f)
        var done = false
        peaks.forEach { done = say(t, it) }
        assertTrue(done)
        assertEquals(10, t.takes)
        assertEquals(peaks, t.scores)
        // The 20th percentile of 10 takes is the third lowest, 0.40 → 0.40 × 0.8 = 0.32
        assertEquals(0.32f, t.threshold, 0.001f)
        assertTrue(t.result, t.result.contains("Before: it would have caught 6 of 10. Now: 10 of 10."))
        assertEquals(10, dir.list()!!.size)  // the recordings, kept on the phone
        dir.deleteRecursively()
    }

    @Test
    fun theThresholdStaysWithinSafeLimits() {
        assertEquals(STANDARD_EAR_THRESHOLD, VoiceTraining.personalThreshold(List(10) { 0.95f }))
        assertEquals(LOWEST_EAR_THRESHOLD, VoiceTraining.personalThreshold(List(10) { 0.1f }))
        assertEquals(STANDARD_EAR_THRESHOLD, VoiceTraining.personalThreshold(emptyList()))
    }

    @Test
    fun shortClicksArentTakes() {
        val t = VoiceTraining(Files.createTempDirectory("myvoice").toFile())
        t.feed(chunk(false), 0f)
        repeat(5) {
            t.feed(chunk(true), 0.9f)  // 50 ms: a click, not a word
            repeat(15) { t.feed(chunk(false), 0f) }
        }
        assertEquals(0, t.takes)
    }
}
