package com.example.companion2

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

// Notices music playing nearby and finds its beat, so the eyes (and head) can bob along.
// Entirely on the phone, from the loudness of the microphone sound: nothing is recorded or
// sent anywhere. Speech has no steady beat, so it doesn't count as music.
class MusicSense {
    private val rate = 100                        // loudness readings per second (every 10 ms)
    private val history = FloatArray(rate * 6)    // the last 6 seconds of "something just got louder"
    private var filled = 0
    private var write = 0
    private var lastLoudness = 0f
    private var level = 0f                        // how loud it is on average
    private var sinceCheck = 0
    private var confidentSince = 0L

    @Volatile var playing = false                 // music is on
        private set
    @Volatile private var period = 50             // readings between beats (50 = 120 beats a minute)
    @Volatile private var lastBeatAt = 0L         // when the last beat landed (ms)

    // Called with each 50 ms of microphone sound (16-bit, 16 kHz)
    fun listen(sound: ByteArray) {
        val samples = sound.size / 2
        val step = 160  // 10 ms
        var start = 0
        while (start + step <= samples) {
            var sum = 0.0
            for (i in start until start + step) {
                val s = ((sound[2 * i + 1].toInt() shl 8) or (sound[2 * i].toInt() and 0xFF)).toShort().toFloat()
                sum += s * s
            }
            val loudness = ln(1f + (sum / step).toFloat()) // loudness on a log scale, like ears
            val rise = max(0f, loudness - lastLoudness)
            lastLoudness = loudness
            level += (loudness - level) * 0.01f
            history[write] = rise
            write = (write + 1) % history.size
            if (filled < history.size) filled++
            start += step
        }
        sinceCheck += samples / step
        if (sinceCheck >= rate / 2 && filled == history.size) {  // twice a second
            sinceCheck = 0
            findBeat()
        }
    }

    // Finds the steadiest repeat between 60 and 180 beats a minute, and where the beats fall
    private fun findBeat() {
        val n = history.size
        fun at(i: Int) = history[(write + i) % n]  // oldest first
        var mean = 0f
        for (i in 0 until n) mean += at(i)
        mean /= n
        var zero = 0f
        for (i in 0 until n) zero += (at(i) - mean) * (at(i) - mean)
        if (zero <= 0f) return
        var bestLag = 0
        var best = 0f
        for (lag in rate * 60 / 180..rate * 60 / 60) {
            var c = 0f
            for (i in lag until n) c += (at(i) - mean) * (at(i - lag) - mean)
            if (c > best) {
                best = c
                bestLag = lag
            }
        }
        val steadiness = best / zero
        val now = System.currentTimeMillis()
        // Steady and loud enough for a few seconds: music
        val confident = steadiness > 0.3f && level > 11f
        if (!confident) {
            confidentSince = 0
            playing = false
            return
        }
        if (confidentSince == 0L) confidentSince = now
        playing = now - confidentSince > 3000
        period = bestLag
        // Where the beats fall: the offset whose every-beat readings add up highest
        var bestOffset = 0
        var bestSum = -1f
        for (offset in 0 until bestLag) {
            var s = 0f
            var i = n - 1 - offset
            while (i >= 0) {
                s += at(i)
                i -= bestLag
            }
            if (s > bestSum) {
                bestSum = s
                bestOffset = offset
            }
        }
        lastBeatAt = now - bestOffset * (1000L / rate)
    }

    // Beats a minute (when music is playing)
    val bpm get() = 60f * rate / period

    // -1..1 over each pair of beats, for swaying side to side, or 0 with no music
    fun sway(now: Long): Float {
        if (!playing) return 0f
        val beatMs = period * (1000f / rate)
        val t = ((now - lastBeatAt) % (2f * beatMs).toLong()) / beatMs  // 0..2
        return sin(t * PI.toFloat())
    }

    // 0..1: a little pulse on each beat (for the eyes to bob with), or 0 with no music
    fun pulse(now: Long): Float {
        if (!playing) return 0f
        val beatMs = period * (1000f / rate)
        val since = ((now - lastBeatAt) % beatMs.toLong()).toFloat()
        return exp(-since / 90f)
    }
}
