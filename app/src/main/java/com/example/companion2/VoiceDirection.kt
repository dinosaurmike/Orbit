package com.example.companion2

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Which way a voice comes from, using the phone's microphones at its two ends (with the phone
// on its side, they're left and right, about 15 cm apart). Sound reaches the nearer microphone
// up to half a millisecond sooner; that delay gives the direction. For now this is a test
// (Settings → Device → Neck): it shows whether this phone records real stereo, and which way
// it thinks you are. If it works well, the head can turn toward whoever says "Orby".
@SuppressLint("MissingPermission")  // only started after the microphone permission is granted
class VoiceDirection {
    @Volatile var running = false
        private set
    @Volatile var direction = 0f      // the latest confident estimate: -1 = far left .. 1 = far right
        private set
    @Volatile var directionAt = 0L    // when it last heard a voice clearly enough to tell
        private set
    @Volatile var level = 0f          // how loud it is (0..1)
        private set
    @Volatile var stereo: Boolean? = null  // null = not sure yet, false = both sides identical
        private set
    @Volatile var status = ""
        private set
    @Volatile var flip = false        // swap left and right (which end is which depends on the phone)
    var onStart: (() -> Unit)? = null  // pauses the usual microphone (only one recording at a time)
    var onStop: (() -> Unit)? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        stereo = null
        onStart?.invoke()
        worker = thread(name = "voice-direction") {
            try {
                listen()
            } catch (e: Exception) {
                Log.w("Orbit", "Voice direction failed", e)
                status = "Couldn't record: ${e.message}"
            }
            running = false
            onStop?.invoke()
        }
    }

    fun stop() {
        running = false
    }

    private fun listen() {
        val rate = 48000
        val chunk = 4800  // 100 ms
        // The least-processed recording first (processing can blur the timing between sides)
        val sources = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "unprocessed",
            MediaRecorder.AudioSource.CAMCORDER to "camcorder",
            MediaRecorder.AudioSource.MIC to "mic"
        )
        var recorder: AudioRecord? = null
        var used = ""
        for ((source, name) in sources) {
            val size = max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT), chunk * 8)
            val r = try {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, size)
            } catch (e: Exception) {
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED && r.channelCount == 2) {
                recorder = r
                used = name
                break
            }
            r?.release()
        }
        if (recorder == null) {
            status = "This phone won't record in stereo"
            stereo = false
            return
        }
        status = "Listening (stereo, $used)…"
        val samples = ShortArray(chunk * 2)
        val n = 8192  // FFT size (100 ms padded)
        val left = DoubleArray(n)
        val right = DoubleArray(n)
        val leftIm = DoubleArray(n)
        val rightIm = DoubleArray(n)
        val maxLag = 26  // 15 cm at the speed of sound is about 21 samples at 48 kHz, plus a margin
        var noise = 0.01
        var identical = 0
        var different = 0
        recorder.startRecording()
        try {
            while (running) {
                var got = 0
                while (got < samples.size && running) {
                    val c = recorder.read(samples, got, samples.size - got)
                    if (c <= 0) break
                    got += c
                }
                if (got < samples.size) continue
                // Split into left/right, emphasising the higher sounds (they give sharper timing)
                var energy = 0.0
                var diff = 0.0
                var lastL = 0.0
                var lastR = 0.0
                for (i in 0 until n) {
                    if (i < chunk) {
                        val l = samples[2 * i] / 32768.0
                        val r = samples[2 * i + 1] / 32768.0
                        left[i] = l - 0.95 * lastL
                        right[i] = r - 0.95 * lastR
                        lastL = l
                        lastR = r
                        energy += l * l + r * r
                        diff += (l - r) * (l - r)
                    } else {
                        left[i] = 0.0
                        right[i] = 0.0
                    }
                    leftIm[i] = 0.0
                    rightIm[i] = 0.0
                }
                val rms = sqrt(energy / (2 * chunk))
                level = (rms * 12).toFloat().coerceIn(0f, 1f)
                // Is it real stereo? (some phones copy one microphone to both sides)
                if (rms > 0.005) {
                    if (diff < energy * 0.0005) identical++ else different++
                    if (identical + different >= 10) stereo = different > identical
                }
                // Only work it out for sounds well above the background noise
                if (rms < noise * 3 || rms < 0.004) {
                    noise += (rms - noise) * 0.05
                    continue
                }
                fft(left, leftIm, false)
                fft(right, rightIm, false)
                // Cross-power spectrum, every frequency weighted equally (GCC-PHAT), speech band only
                val low = (300.0 * n / rate).toInt()
                val high = (5000.0 * n / rate).toInt()
                for (k in 0 until n) {
                    val bin = if (k <= n / 2) k else n - k
                    if (bin < low || bin > high) {
                        left[k] = 0.0
                        leftIm[k] = 0.0
                        continue
                    }
                    val re = left[k] * right[k] + leftIm[k] * rightIm[k]
                    val im = leftIm[k] * right[k] - left[k] * rightIm[k]
                    val mag = sqrt(re * re + im * im) + 1e-12
                    left[k] = re / mag
                    leftIm[k] = im / mag
                }
                fft(left, leftIm, true)
                // The delay with the strongest match (positive = the left side heard it later: it came from the right)
                var bestLag = 0
                var best = -1.0
                var sum = 0.0
                for (lag in -maxLag..maxLag) {
                    val v = left[(lag + n) % n]
                    sum += abs(v)
                    if (v > best) {
                        best = v
                        bestLag = lag
                    }
                }
                val mean = sum / (2 * maxLag + 1)
                if (best < mean * 2.5) continue  // no clear winner (echoes, noise)
                // A little finer than one sample: fit a curve through the peak and its neighbours
                val a = left[(bestLag - 1 + n) % n]
                val c = left[(bestLag + 1 + n) % n]
                val shift = if (a - 2 * best + c != 0.0) 0.5 * (a - c) / (a - 2 * best + c) else 0.0
                val lag = bestLag + shift.coerceIn(-0.5, 0.5)
                val raw = (lag / 21.0).toFloat().coerceIn(-1f, 1f)
                val now = if (flip) -raw else raw
                direction = if (System.currentTimeMillis() - directionAt > 1000) now else direction + (now - direction) * 0.5f
                directionAt = System.currentTimeMillis()
            }
        } finally {
            recorder.stop()
            recorder.release()
            status = ""
        }
    }

    // In-place fast Fourier transform (n a power of two); inverse = true for the way back
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var length = 2
        while (length <= n) {
            val angle = 2 * PI / length * (if (inverse) 1 else -1)
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until length / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + length / 2] * curRe - im[i + k + length / 2] * curIm
                    val bIm = re[i + k + length / 2] * curIm + im[i + k + length / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + length / 2] = aRe - bRe
                    im[i + k + length / 2] = aIm - bIm
                    val next = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = next
                }
                i += length
            }
            length = length shl 1
        }
        if (inverse) for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }
}
