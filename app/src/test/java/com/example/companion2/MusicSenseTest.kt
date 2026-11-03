package com.example.companion2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// MusicSense hears music through the microphone and finds its beat, so the eyes can bob along.
// These tests play it pretend sound (16-bit, 16 kHz, in 50 ms pieces, like the real microphone)
// on a pretend clock, so they run in a moment instead of in real time.
class MusicSenseTest {
    private val rate = 16_000
    private val chunk = rate / 20  // 50 ms

    private class Clock { var now = 1_000_000L }

    // Feeds `seconds` of sound; `sample(i)` gives the i-th sample (i counts from the start)
    private fun play(sense: MusicSense, clock: Clock, seconds: Int, sample: (Int) -> Int) {
        var i = 0
        repeat(seconds * 20) {
            val bytes = ByteArray(chunk * 2)
            for (k in 0 until chunk) {
                val s = sample(i++).coerceIn(-32768, 32767)
                bytes[2 * k] = (s and 0xFF).toByte()
                bytes[2 * k + 1] = (s shr 8).toByte()
            }
            sense.listen(bytes)
            clock.now += 50
        }
    }

    // Music: a steady hum, with a loud drum hit (40 ms) on every beat
    private fun music(bpm: Int): (Int) -> Int {
        val beat = rate * 60 / bpm
        return { i ->
            val hum = (3000 * sin(2 * PI * 220 * i / rate)).toInt()
            val inBeat = i % beat
            val drum = if (inBeat < rate / 25) ((if (i % 2 == 0) 1 else -1) * 15000 * (1 - inBeat / (rate / 25.0))).toInt() else 0
            hum + drum
        }
    }

    private fun bpmFor(bpm: Int): MusicSense {
        val clock = Clock()
        val sense = MusicSense { clock.now }
        play(sense, clock, 12, music(bpm))
        assertTrue("should notice music at $bpm beats a minute", sense.playing)
        return sense
    }

    @Test fun findsTheBeatAt120() = assertEquals(120f, bpmFor(120).bpm, 3f)

    @Test fun findsTheBeatAt90() = assertEquals(90f, bpmFor(90).bpm, 3f)

    @Test fun findsTheBeatAt150() = assertEquals(150f, bpmFor(150).bpm, 4f)

    @Test fun silenceIsNotMusic() {
        val clock = Clock()
        val sense = MusicSense { clock.now }
        play(sense, clock, 12) { 0 }
        assertFalse(sense.playing)
        assertEquals(0f, sense.pulse(clock.now), 0f)
        assertEquals(0f, sense.sway(clock.now), 0f)
    }

    @Test fun burstsAtRandomTimesAreNotMusic() {
        // Like speech or clatter: loud moments with no steady rhythm
        val clock = Clock()
        val sense = MusicSense { clock.now }
        val random = Random(42)
        var nextBurst = 0
        var burstLeft = 0
        play(sense, clock, 12) { i ->
            if (i >= nextBurst) {
                burstLeft = rate / 10 + random.nextInt(rate / 5)
                nextBurst = i + burstLeft + random.nextInt(rate / 2)
            }
            val loud = if (burstLeft-- > 0) 12000 else 2500
            (loud * sin(2 * PI * 180 * i / rate)).toInt() + random.nextInt(-400, 400)
        }
        assertFalse(sense.playing)
    }

    @Test fun waitsAFewSecondsBeforeBobbing() {
        // The history needs 6 seconds of sound, then 3 steady seconds: not after just 5 seconds
        val clock = Clock()
        val sense = MusicSense { clock.now }
        play(sense, clock, 5, music(120))
        assertFalse(sense.playing)
    }

    @Test fun pulseAndSwayStayInRange() {
        val sense = bpmFor(120)
        // The last reading's time is roughly where the pretend clock stopped
        for (ms in 0 until 2000 step 10) {
            val now = 1_000_000L + 12_000L + ms
            val pulse = sense.pulse(now)
            val sway = sense.sway(now)
            assertTrue("pulse $pulse", pulse in 0f..1f)
            assertTrue("sway $sway", sway in -1f..1f)
        }
    }
}
