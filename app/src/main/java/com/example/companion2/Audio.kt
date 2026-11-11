package com.example.companion2

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

// Listens to the microphone and keeps track of how loud it is.
// Records at 16,000 samples per second, the format Gemini Live expects.
class Microphone {
    @Volatile var level = 0f      // 0 = silent, 1 = loud
    @Volatile var onSound: ((ByteArray, Float) -> Unit)? = null  // each 50 ms of sound + loudness
    @Volatile private var worker: Thread? = null

    @SuppressLint("MissingPermission")  // only started after permission is granted
    fun start() {
        if (worker != null) return
        val newWorker = thread(start = false, name = "microphone") {
            val sampleRate = 16000
            val bufferSize = max(
                AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ),
                sampleRate / 5  // at least 100 ms of sound
            )
            // VOICE_COMMUNICATION turns on the phone's echo cancelling where available
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            val samples = ShortArray(sampleRate / 20)  // 50 ms of sound at a time
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                worker = null  // so a later start() can try again
                return@thread
            }

            // Ask for echo cancelling and noise reduction too, so Orbit's own voice
            // coming out of the speaker isn't mistaken for you
            val echoCanceller = if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            } else null
            val noiseReducer = if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            } else null

            recorder.startRecording()
            while (worker == Thread.currentThread()) {
                val count = recorder.read(samples, 0, samples.size)
                if (count > 0) {
                    var sum = 0.0
                    for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
                    level = (sqrt(sum / count) / 3000.0).toFloat().coerceIn(0f, 1f)

                    // Raw sound as bytes (16-bit, little-endian), the format Gemini expects
                    val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(samples, 0, count)
                    onSound?.invoke(bytes.array(), level)
                }
            }
            recorder.stop()
            echoCanceller?.release()
            noiseReducer?.release()
            recorder.release()
        }
        worker = newWorker
        newWorker.start()
    }

    fun stop() {
        worker = null
        level = 0f
    }
}

// Plays Gemini's voice through the phone speaker.
// Gemini sends raw sound at 24,000 samples per second.
class Speaker(private val audio: AudioManager) {
    private val sampleRate = 24000
    private val audioThread = Executors.newSingleThreadExecutor()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var framesWritten = 0L
    @Volatile private var lastBusy = 0L
    // Goes up every time playback is stopped, so sound queued before that is thrown away
    @Volatile private var generation = 0
    // Loudness of each 50 ms of queued sound: (frame where it ends, loudness 0..1)
    private val levels = java.util.ArrayDeque<Pair<Long, Float>>()

    // Call mode plays like a speakerphone call, so the phone's echo cancelling can remove
    // Orbit's own voice from the microphone. That's what lets you interrupt it.
    @Volatile private var callMode = false

    // Switching into (and out of) call mode can take a noticeable moment on some phones, so it's
    // done on the sound thread: never on the main thread, where it would make the eyes stutter.
    // The same thread plays the voice, so the switch always finishes before any of it plays.
    fun beginConversation(call: Boolean) {
        callMode = call
        if (!call) return
        audioThread.execute {
            try {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                // Use the loudspeaker, not the earpiece you'd hold to your ear
                if (Build.VERSION.SDK_INT >= 31) {
                    audio.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { audio.setCommunicationDevice(it) }
                } else {
                    audio.isSpeakerphoneOn = true
                }
            } catch (e: Exception) {
                Log.w("Orbit", "Couldn't switch to call mode", e)
            }
        }
    }

    fun endConversation() {
        if (!callMode) return
        callMode = false
        audioThread.execute {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    audio.clearCommunicationDevice()
                } else {
                    audio.isSpeakerphoneOn = false
                }
                audio.mode = AudioManager.MODE_NORMAL
            } catch (e: Exception) {
                Log.w("Orbit", "Couldn't leave call mode", e)
            }
        }
    }

    fun play(sound: ByteArray) {
        val myGeneration = generation
        audioThread.execute {
            if (myGeneration != generation) return@execute  // stopped since this arrived
            val t = track ?: newTrack()

            // Note how loud each 50 ms of this sound is, so the eyes can move with the voice
            val samples = ByteBuffer.wrap(sound).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val piece = sampleRate / 20
            var start = 0
            while (start < samples.limit()) {
                val end = minOf(start + piece, samples.limit())
                var sum = 0.0
                for (i in start until end) sum += samples.get(i).toDouble() * samples.get(i)
                val loud = (sqrt(sum / (end - start)) / 4000.0).toFloat().coerceIn(0f, 1f)
                synchronized(levels) { levels.add(framesWritten + end to loud) }
                start = end
            }

            framesWritten += sound.size / 2
            t.write(sound, 0, sound.size)
        }
    }

    private fun newTrack(): AudioTrack {
        val usage =
            if (callMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(sampleRate * 2)  // 1 second of sound
            .build()
        t.play()
        track = t
        framesWritten = 0
        synchronized(levels) { levels.clear() }
        return t
    }

    // How loud the voice is at this moment (0..1)
    fun level(): Float {
        val t = track ?: return 0f
        val head = try {
            t.playbackHeadPosition.toLong()
        } catch (e: IllegalStateException) {
            return 0f
        }
        synchronized(levels) {
            while (levels.isNotEmpty() && levels.first().first < head) levels.removeFirst()
            return levels.peekFirst()?.second ?: 0f
        }
    }

    // True while it's talking, and for a moment after, so it doesn't hear its own voice
    fun busy(): Boolean {
        val t = track ?: return false
        try {
            if (t.playbackHeadPosition < framesWritten) lastBusy = System.currentTimeMillis()
        } catch (e: IllegalStateException) {
            return false  // it was just switched off
        }
        return System.currentTimeMillis() - lastBusy < 400
    }

    // Stops talking right away and throws away anything still queued
    fun stop() {
        generation++
        audioThread.execute {
            val t = track
            track = null
            t?.run { pause(); flush(); release() }
            synchronized(levels) { levels.clear() }
        }
    }
}
