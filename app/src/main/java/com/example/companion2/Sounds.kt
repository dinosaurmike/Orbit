package com.example.companion2

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

// Orbit's own little sounds, all made in code when the app starts (no audio files), so they
// share one soft, glassy character that matches the holographic eyes.
//
// Its sound signature: three notes, up a fifth then down a third (G, D, B). Start-up plays all
// three, waking and starting to listen use the first two, ending a conversation steps back
// down, and chimes and timers ring them as bells, so everything sounds like the same Orbit.
private val MOTIF = floatArrayOf(784f, 1174.7f, 987.8f)
enum class Sound {
    WHOOSH,   // start-up: the moon swooshing round its orbit and settling
    CHIRP,    // waking up
    LISTEN,   // a conversation opens
    END,      // a conversation closes
    THINK,    // a faint shimmer while it thinks
    SIGH,     // falling asleep
    YAWN,     // dozing off
    CHIME,    // a reminder is due
    TIMER,    // a timer is done
    STARTLE,  // a sudden loud noise
    BLIP,     // a double-take when you come back
    SNEEZE,   // ah… ah… choo!
    HICCUP,   // hic!
    STRETCH,  // a little squeaky stretch
    TWINKLE,  // a firefly appears
    PURR      // being petted
}

class Sounds(private val settings: Settings) {
    private val rate = 44100
    private val handler = Handler(Looper.getMainLooper())
    private val random = java.util.Random(7)
    private val clips: Map<Sound, ShortArray> = Sound.entries.associateWith { make(it) }
    // After a "shh": only reminders and timers make a sound until then (ms)
    @Volatile var hushedUntil = 0L

    // When it last made a sound (so it doesn't jump at its own chimes)
    @Volatile var lastPlayedAt = 0L
        private set

    // Partials (frequency ratio to strength): glass is pure and bright; bell is a little metallic
    private val glass get() = listOf(1f to 1f, 2f to 0.2f, 3f to 0.06f)
    private val bell get() = listOf(1f to 1f, 2f to 0.35f, 2.76f to 0.25f, 5.4f to 0.08f)

    // volume: 0..1 on top of the volume chosen in settings
    fun play(sound: Sound, volume: Float = 1f) {
        if (!settings.soundsOn) return
        if (System.currentTimeMillis() < hushedUntil && sound != Sound.CHIME && sound != Sound.TIMER) return
        val data = clips[sound] ?: return
        lastPlayedAt = System.currentTimeMillis() + data.size * 1000L / rate
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.setVolume((settings.soundVolume * volume).coerceIn(0f, 1f))
            track.play()
            handler.postDelayed({ track.release() }, data.size * 1000L / rate + 300)
        } catch (e: Exception) {
            Log.w("Orbit", "Couldn't play a sound", e)
        }
    }

    private fun make(sound: Sound): ShortArray = when (sound) {
        Sound.WHOOSH -> build(1.6f) {
            // A soft breath of air as the moon swings round: low and gentle, more felt than heard
            noise(it, 0f, 1.5f, amp = 0.22f,
                cutoff = { t -> if (t < 0.45f) 200f + 900f * (t / 0.45f) else 1100f - 750f * ((t - 0.45f) / 0.55f) },
                shape = { t -> sin(PI.toFloat() * t).pow(2f) })
            // ...then the signature, very softly, as the two settle into eyes
            MOTIF.forEachIndexed { i, f ->
                tone(it, 0.85f + i * 0.14f, 0.6f, f, f, 0.03f, tau = 0.35f, partials = bell, attack = 0.02f)
            }
        }
        Sound.CHIRP -> build(0.45f) {  // the first two notes, bright and quick
            tone(it, 0f, 0.14f, MOTIF[0], MOTIF[0] * 1.08f, 0.22f, tau = 0.08f)
            tone(it, 0.1f, 0.25f, MOTIF[1], MOTIF[1] * 1.04f, 0.2f, tau = 0.1f)
        }
        // Listening: a glide up the first two notes; ending: back down the last two
        Sound.LISTEN -> build(0.25f) { tone(it, 0f, 0.2f, MOTIF[0], MOTIF[1], 0.2f, tau = 0.1f) }
        Sound.END -> build(0.3f) { tone(it, 0f, 0.25f, MOTIF[2], MOTIF[0], 0.18f, tau = 0.12f) }
        Sound.THINK -> build(0.9f) {
            listOf(2093f, 2637f, 3136f).forEachIndexed { i, f ->
                tone(it, i * 0.09f, 0.6f, f, f, 0.05f, tau = 0.3f, partials = bell)
            }
        }
        Sound.SIGH -> build(1.5f) {
            noise(it, 0f, 1.4f, amp = 0.2f,
                cutoff = { t -> 1400f - 1100f * t },
                shape = { t -> if (t < 0.25f) t / 0.25f else 1f - (t - 0.25f) / 0.75f })
            tone(it, 0.05f, 1.2f, 520f, 260f, 0.06f, tau = 0.6f)
        }
        Sound.YAWN -> build(1.5f) {
            tone(it, 0f, 0.5f, 300f, 480f, 0.08f, tau = 1f, attack = 0.15f)
            tone(it, 0.45f, 0.95f, 480f, 260f, 0.08f, tau = 0.5f, attack = 0.05f)
            noise(it, 0.1f, 1.2f, amp = 0.08f, cutoff = { t -> 900f - 500f * t },
                shape = { t -> sin(PI.toFloat() * t) })
        }
        Sound.CHIME -> build(2f) {  // a reminder: the signature as soft bells
            MOTIF.forEachIndexed { i, f ->
                tone(it, i * 0.26f, 1.3f, f, f, 0.18f, tau = 0.85f, partials = bell, attack = 0.015f)
            }
        }
        Sound.TIMER -> build(2f) {  // done: the signature, then home to the G above
            (MOTIF.toList() + 1568f).forEachIndexed { i, f ->
                tone(it, i * 0.15f, 1.2f, f, f, 0.16f, tau = 0.75f, partials = bell, attack = 0.015f)
            }
        }
        Sound.STARTLE -> build(0.4f) {
            // a soft "oh!": a quick rising glide, rounded, not a bang
            tone(it, 0f, 0.14f, 520f, 1100f, 0.09f, tau = 0.12f, attack = 0.025f)
            tone(it, 0.12f, 0.2f, 1100f, 880f, 0.06f, tau = 0.12f, attack = 0.02f)
        }
        Sound.BLIP -> build(0.3f) {
            tone(it, 0f, 0.09f, 1200f, 1200f, 0.08f, tau = 0.06f, attack = 0.02f)
            tone(it, 0.1f, 0.1f, 1500f, 1500f, 0.08f, tau = 0.07f, attack = 0.02f)
        }
        Sound.SNEEZE -> build(1.0f) {
            // two rising "ah"s, then a tiny, soft "choo" (a breath, not a bang)
            tone(it, 0f, 0.22f, 420f, 560f, 0.05f, tau = 0.3f, attack = 0.06f)
            tone(it, 0.3f, 0.26f, 470f, 680f, 0.055f, tau = 0.3f, attack = 0.06f)
            noise(it, 0.66f, 0.3f, amp = 0.14f, cutoff = { t -> 1400f - 800f * t },
                shape = { t -> if (t < 0.2f) t / 0.2f else 1f - (t - 0.2f) / 0.8f })
            tone(it, 0.68f, 0.25f, 760f, 380f, 0.05f, tau = 0.12f, attack = 0.03f)
        }
        Sound.HICCUP -> build(0.2f) {
            tone(it, 0f, 0.1f, 640f, 900f, 0.07f, tau = 0.07f, attack = 0.02f)
        }
        Sound.STRETCH -> build(0.8f) {
            tone(it, 0f, 0.35f, 350f, 820f, 0.07f, tau = 1f, attack = 0.08f)
            tone(it, 0.33f, 0.35f, 820f, 560f, 0.07f, tau = 0.3f)
        }
        Sound.PURR -> build(1.8f) {
            // A soft, contented rumble: breathy noise (pitched where a phone speaker can play it)
            // fluttering about 23 times a second, swelling in and out
            noise(it, 0f, 1.7f, amp = 0.2f, cutoff = { 420f },
                shape = { t -> sin(PI.toFloat() * t) * (0.55f + 0.45f * sin(t * 1.7f * 23f * 2f * PI.toFloat())) })
            tone(it, 0f, 1.7f, 210f, 200f, 0.025f, tau = 3f, attack = 0.3f)
        }
        Sound.TWINKLE -> build(0.7f) {
            tone(it, 0f, 0.45f, 2637f, 2637f, 0.05f, tau = 0.25f, partials = bell)
            tone(it, 0.1f, 0.45f, 3520f, 3520f, 0.04f, tau = 0.25f, partials = bell)
        }
    }

    // Makes `seconds` of sound (plus a short tail for the echoes) and finishes it off
    private fun build(seconds: Float, fill: (FloatArray) -> Unit): ShortArray {
        val buffer = FloatArray(((seconds + 0.3f) * rate).toInt())
        fill(buffer)
        // A hint of room: many small, close reflections that blur into a soft tail (a few big,
        // separate echoes sound like a "slap", which is distracting)
        val out = buffer.copyOf()
        val taps = listOf(0.023f to 0.1f, 0.037f to 0.08f, 0.053f to 0.07f, 0.071f to 0.055f,
            0.097f to 0.04f, 0.131f to 0.03f, 0.173f to 0.02f)
        for ((delay, gain) in taps) {
            val d = (delay * rate).toInt()
            for (i in d until out.size) out[i] += buffer[i - d] * gain
        }
        // Take the edge off the top end (warmer, never sharp), then round off rather than clip
        var smooth = 0f
        val a = 0.45f  // about a 4.5 kHz gentle low-pass
        for (i in out.indices) {
            smooth += a * (out[i] - smooth)
            out[i] = smooth
        }
        return ShortArray(out.size) { (tanh(out[it] * 1.2f) * 0.9f * 32767f).toInt().toShort() }
    }

    // A note gliding from one pitch to another, starting at `at` seconds and lasting `length`:
    // quick fade-in, then dying away (tau = seconds to fade to about a third)
    private fun tone(
        buffer: FloatArray, at: Float, length: Float, from: Float, to: Float, amp: Float,
        tau: Float, partials: List<Pair<Float, Float>> = glass, attack: Float = 0.015f
    ) {
        val start = (at * rate).toInt()
        val count = (length * rate).toInt()
        val phases = FloatArray(partials.size)
        val twoPi = 2f * PI.toFloat()
        for (i in 0 until count) {
            val index = start + i
            if (index >= buffer.size) break
            val t = i / rate.toFloat()
            val freq = from * (to / from).pow(t / length)  // even glide in pitch
            val envelope = min(1f, t / attack) * min(1f, (length - t) / 0.01f).coerceAtLeast(0f)
            var v = 0f
            partials.forEachIndexed { k, (ratio, gain) ->
                phases[k] += twoPi * freq * ratio / rate
                if (phases[k] > twoPi) phases[k] -= twoPi
                v += sin(phases[k]) * gain * exp(-t / (tau / sqrt(ratio)))  // high partials fade first
            }
            buffer[index] += v * amp * envelope
        }
    }

    // Breathy noise through a sweeping filter. cutoff and shape take 0..1 through the sound.
    private fun noise(
        buffer: FloatArray, at: Float, length: Float, amp: Float,
        cutoff: (Float) -> Float, shape: (Float) -> Float
    ) {
        val start = (at * rate).toInt()
        val count = (length * rate).toInt()
        var low = 0f
        var band = 0f
        for (i in 0 until count) {
            val index = start + i
            if (index >= buffer.size) break
            val t = i / count.toFloat()
            val f = 2f * sin(PI.toFloat() * cutoff(t).coerceIn(40f, 6000f) / rate)
            val input = random.nextFloat() * 2f - 1f
            low += f * band
            val high = input - low - 0.7f * band
            band += f * high
            // fade in and out over at least 20 ms, so a breath never starts or stops with a click
            val edge = minOf(i, count - i) / (0.02f * rate)
            buffer[index] += band * amp * shape(t).coerceIn(0f, 1f) * edge.coerceAtMost(1f)
        }
    }
}
