package com.example.companion2

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.io.File
import kotlin.concurrent.thread

// Hand gestures, recognized on the phone with Google's free MediaPipe gesture model
// (about 8 MB, downloaded the first time "Hand gestures" is switched on):
//   open hand (a wave)  → it waves back        thumbs up   → happy
//   thumbs down         → a sad little droop   victory ✌   → a wink
//   "I love you" 🤟     → soft and happy       finger up ☝ → "shh": it goes quiet
const val GESTURE_MODEL_URL =
    "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task"

class Gestures(private val context: Context, private val settings: Settings) {
    @Volatile var status = "Off"
    @Volatile private var recognizer: GestureRecognizer? = null
    @Volatile private var preparing = false
    // A gesture has to show up in two checks in a row (so a passing hand doesn't count)
    private var candidate = ""
    private var lastFired = 0L

    val ready get() = recognizer != null

    // Downloads (first time) and loads the model. Safe to call again.
    fun prepare() {
        if (recognizer != null || preparing) return
        preparing = true
        thread(name = "gestures-setup") {
            try {
                status = "Downloading the gesture model (about 8 MB)…"
                val file = Models.get(File(context.filesDir, "models"), "gesture_recognizer.task", GESTURE_MODEL_URL) {
                    status = "Downloading the gesture model… $it%"
                }
                val options = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(Models.map(file)).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinHandDetectionConfidence(0.5f)
                    .build()
                recognizer = GestureRecognizer.createFromOptions(context, options)
                status = "Ready · try a wave or a thumbs up"
            } catch (e: Exception) {
                Log.e("Orbit", "Gesture setup failed", e)
                status = "Couldn't set up hand gestures: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                preparing = false
            }
        }
    }

    // Looks for a gesture in an upright camera picture. Returns its name once it's been seen
    // twice in a row (and not more than once every 3 seconds), or null.
    fun check(picture: Bitmap): String? {
        val r = recognizer ?: return null
        val seen = try {
            r.recognize(BitmapImageBuilder(picture).build())
                .gestures().firstOrNull()?.firstOrNull()
                ?.takeIf { it.score() > 0.6f }?.categoryName()
        } catch (e: Exception) {
            null
        } ?: "None"
        if (seen == "None" || seen != candidate) {
            candidate = seen
            return null
        }
        val now = System.currentTimeMillis()
        if (now - lastFired < 3000) return null
        lastFired = now
        candidate = ""
        return seen
    }
}
