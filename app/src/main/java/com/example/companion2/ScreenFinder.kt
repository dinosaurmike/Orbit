package com.example.companion2

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// "Find my screen": Orbit looks around for your computer screen by itself, so nobody has to
// lean over the desk aiming it. It turns its head in steps across its range, takes a photo at
// each, and asks Gemini which photo shows the screen. Then it looks closer around that spot
// (finer steps, then up and down) the same way, and turns there. After that, it asks you in
// conversation whether it's right, and you can fine-tune by voice ("a bit left", "perfect").
//
// Needs the neck connected and a Gemini key. Takes about half a minute.
class ScreenFinder(
    private val settings: Settings,
    private val neck: Neck,
    private val gemini: GeminiLive
) {
    @Volatile var busy = false
        private set

    // Searches on a background thread. `done` gets null when the screen was found (Orbit is
    // now pointing at it and the spot is saved), or the reason it couldn't.
    fun find(done: (String?) -> Unit) {
        if (busy) return
        if (!settings.neckOn || !neck.link.connected) return done("my neck isn't connected")
        val key = gemini.key()
        if (key.isBlank()) return done("I need a Gemini key for that")
        busy = true
        thread(name = "find-screen") {
            try {
                val range = settings.panRange
                // 1. A wide look round: 7 photos across the whole range
                val step = 2f * range / 6f
                val wide = (0..6).map { -range + it * step to 0f }
                val rough = pick(wide, key, "Which photo shows a computer screen or monitor? If several " +
                    "do, pick the one where it's biggest and nearest the middle.")
                    ?: return@thread done("I couldn't spot a screen anywhere I can turn to")
                // 2. A closer look around that spot
                val near = listOf(-0.5f, -0.25f, 0f, 0.25f, 0.5f)
                    .map { (rough.first + it * step).coerceIn(-range, range) to 0f }
                val side = pick(near, key, "Which photo shows the computer screen most squarely, " +
                    "closest to the middle of the photo from left to right?") ?: rough
                // 3. Up and down
                val heights = listOf(-settings.tiltUp * 0.7f, -settings.tiltUp * 0.3f, 0f, settings.tiltDown * 0.3f)
                    .map { side.first to it }
                val best = pick(heights, key, "Which photo shows the computer screen most completely, " +
                    "closest to the middle of the photo from top to bottom?") ?: side
                // Point there and remember it
                neck.manualAim = best
                settings.screenPan = best.first
                settings.screenTilt = best.second
                settings.screenSpotSet = true
                done(null)
            } catch (e: Exception) {
                Log.e("Orbit", "Finding the screen failed", e)
                neck.manualAim = null
                done("something went wrong (${e.message ?: e.javaClass.simpleName})")
            } finally {
                busy = false
            }
        }
    }

    // Turns to each pose, takes a photo, and asks Gemini which one answers the question.
    // Returns that pose, or null if none of the photos shows a screen.
    private fun pick(poses: List<Pair<Float, Float>>, key: String, question: String): Pair<Float, Float>? {
        val photos = mutableListOf<ByteArray>()
        val taken = mutableListOf<Pair<Float, Float>>()
        for (pose in poses) {
            neck.manualAim = pose
            gemini.showExpression("searching", 2500)
            gemini.keepAlive()  // don't let an open conversation end from the quiet
            Thread.sleep(1300)  // let the head get there and the camera settle
            snap()?.let {
                photos.add(it)
                taken.add(pose)
            }
        }
        if (photos.isEmpty()) return null
        val answer = GeminiText.askAboutPhotos(
            key,
            "These photos were taken by a small desk robot turning its head to look around a " +
                "room. $question Answer with just the photo number, or 0 if none shows a " +
                "computer screen or monitor.",
            photos
        ) ?: return null
        val number = Regex("\\d+").find(answer)?.value?.toIntOrNull() ?: return null
        return taken.getOrNull(number - 1)
    }

    // One photo from the camera (waits up to 4 seconds)
    private fun snap(): ByteArray? {
        val ask = gemini.onLook ?: return null
        val ready = CountDownLatch(1)
        var photo: ByteArray? = null
        ask(false) {
            photo = it
            ready.countDown()
        }
        ready.await(4, TimeUnit.SECONDS)
        return photo
    }
}
