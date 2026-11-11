package com.example.companion2

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random
import kotlin.concurrent.thread

// The Bluetooth names of the neck's ESP32 (must match neck/orbit_neck.ino)
val NECK_SERVICE: UUID = UUID.fromString("4f726269-742d-4e65-636b-000000000001")
val NECK_COMMAND: UUID = UUID.fromString("4f726269-742d-4e65-636b-000000000002")
val NECK_VERSION: UUID = UUID.fromString("4f726269-742d-4e65-636b-000000000003")
val NECK_UPDATE: UUID = UUID.fromString("4f726269-742d-4e65-636b-000000000004")

// The neck firmware that comes inside the app (app/src/main/assets), for updates over Bluetooth
const val NECK_FIRMWARE_FILE = "orbit_neck.bin"

// Roughly how far the front camera sees, from the middle of the picture to its edge (degrees).
// Used to turn "you're over there in the picture" into "turn this far".
private const val HALF_VIEW_X = 35f
private const val HALF_VIEW_Y = 26f

// The neck: a pan/tilt stand the phone sits on. This works out where the head should point
// (following your face, acting out moods and expressions) and hands it to NeckLink, which
// sends it to the ESP32. With no neck connected it still works out the moves, which the Neck
// tab (and the optional readout on the eyes) show, so it can be tuned before the hardware exists.
//
// The eyes lead and the head follows: small movements are left to the eyes, and the head
// only turns once you're clearly off to one side, like a person would.
class Neck(
    private val settings: Settings,
    private val tracker: FaceTracker,
    private val gemini: GeminiLive,
    val link: NeckLink
) {
    // Which way a voice comes from (a test for now: Settings → Neck)
    val voice = VoiceDirection()
    // Where the head points, in degrees from straight ahead.
    // + turn = the way the eyes move when they look right on screen; + tilt = down.
    @Volatile var pan = 0f
        private set
    @Volatile var tilt = 0f
        private set
    @Volatile var mood = Mood.IDLE     // set by the eyes each moment
    @Volatile var holdStill = false    // Neck tab: point straight ahead while adjusting
    @Volatile var privacy = false      // privacy sleep (turn away) rather than ordinary sleep
    @Volatile var musicBob = 0f        // 0..1 pulse on each beat when music is playing (set by Face)
    @Volatile var musicSway = 0f       // -1..1 over each pair of beats, to sway side to side (set by Face)
    @Volatile var manualAim: Pair<Float, Float>? = null  // Neck tab: aiming at the screen by hand
    // What the head is doing right now, in words (shown in Settings → Device → Status)
    @Volatile var doing = ""
        private set
    fun seesYou() = tracker.seesFace()

    // Where the person is, in words, for Gemini (from face tracking; no photo)
    fun whereAreThey(): String {
        if (tracker.seesFace()) {
            val x = tracker.rawX
            val side = when {
                abs(x) < 0.25f -> "right in front of you"
                x < 0f -> "in view, a little off to one side"
                else -> "in view, a little off to the other side"
            }
            return "Yes: you can see a face right now, $side."
        }
        if (tracker.lastSeen == 0L) return "No: you can't see anyone right now, and haven't since you started."
        val seconds = (System.currentTimeMillis() - tracker.lastSeen) / 1000
        val ago = if (seconds < 90) "$seconds seconds ago" else "${seconds / 60} minutes ago"
        return "No: you can't see anyone right now (you last saw a face $ago)."
    }

    // The head's pose without expressions, eased toward wherever it's aiming
    private var aimPan = 0f
    private var aimTilt = 0f
    private var panSpeed = 0f   // how fast the head is turning (for smooth starts and stops)
    private var tiltSpeed = 0f
    // Following you: where the head has decided to point, and since when you've been off to one side
    private var followPan = 0f
    private var followTilt = 0f
    private var wasFollowing = false
    private var wanderPan = 0f
    private var wanderTilt = 0f
    private var nextWander = 0L
    private var lastSent = ""
    // Lost sight of you: where the head was then, and which side of the picture you were last on
    private var lastFaceX = 0f
    private var lostPan = 0f
    private var lostTilt = 0f
    private var seeing = false
    // The mood a moment ago, and since when it's been this one (for quick moves into sleep)
    private var lastMood = Mood.IDLE
    private var moodSince = 0L
    // "Look to your left": where you asked it to look, held for a while
    @Volatile private var voiceAim: Pair<Float, Float>? = null
    // "Face me" when it can't see you: a search, first the way you were last seen, then across
    @Volatile private var searchUntil = 0L
    private var searchStart = 0L
    private var searchFrom = 0f
    private var searchDir = 1f
    @Volatile private var voiceAimUntil = 0L
    private var lastSentAt = 0L

    // Called about 30 times a second; dt is the seconds since the last call
    fun step(dt: Float) {
        val now = System.currentTimeMillis()
        val range = settings.panRange
        if (mood != lastMood) {
            lastMood = mood
            moodSince = now
        }
        // Falling asleep or dozing off: the head goes down quickly but smoothly (about 2 seconds),
        // then the sleepy slow drift takes over
        val settling = ((now - moodSince - 2500L) / 2000f).coerceIn(0f, 1f)
        if (voiceAim != null && (now > voiceAimUntil || mood == Mood.ASLEEP || mood == Mood.DOZING)) voiceAim = null
        // Just lost sight of you: remember where the head was
        val sees = tracker.seesFace()
        if (seeing && !sees) {
            lostPan = aimPan
            lostTilt = aimTilt
        }
        seeing = sees
        val sinceFace = now - tracker.lastSeen
        // Its own unhurried moves (lost you, scanning, wandering, dozing) are speed- and
        // acceleration-limited, so the head drifts there like a relaxed animal, never snaps
        gentle = false
        // Where to aim, and how quickly to get there (per second)
        val (targetPan, targetTilt, rate) = when {
            holdStill -> { doing = "holding straight ahead"; Triple(0f, 0f, 2f) }
            manualAim != null -> { doing = "aiming by hand"; Triple(manualAim!!.first, manualAim!!.second, 3f) }
            // Looking at the computer screen: turn to where you showed it the screen
            gemini.expression()?.first == "looking_screen" -> {
                doing = "looking at your screen"
                Triple(settings.screenPan, settings.screenTilt, 4f)
            }
            // Looking at the desk for a photo: tilt right down, quickly
            gemini.expression()?.first == "looking_down" -> { doing = "looking down at the desk"; Triple(aimPan, settings.tiltDown, 4f) }
            // Privacy sleep: turned away and looking down, so it's clearly not watching
            mood == Mood.ASLEEP && privacy -> { doing = "privacy: turned away"; gentle = true; Triple(range * 0.6f, settings.tiltDown, 1.6f) }
            // Ordinary sleep: head gently bowed and breathing (a slow nod every 5 seconds, with
            // the eyes), drifting sleepily toward you if you're there, like a dozing pet
            mood == Mood.ASLEEP -> {
                doing = "asleep"
                gentle = true
                val breath = sin((now % 5000L) / 5000f * 2f * PI.toFloat())
                val toward = if (tracker.seesFace()) aimPan + beyond(tracker.rawX, 0.2f) * HALF_VIEW_X * 0.25f else aimPan * 0.7f
                Triple(toward, settings.tiltDown * 0.3f + 1.5f * breath, lerp(1.4f, 0.4f, settling))
            }
            // Dozing: head low, drifting very slowly (like the eyes)
            mood == Mood.DOZING -> {
                doing = "dozing"
                gentle = true
                val t = (now % 10_000_000L) / 1000f
                Triple(sin(t * 0.05f) * range * 0.4f, settings.tiltDown * 0.5f, lerp(1.2f, 0.5f, settling))
            }
            // Asked to look somewhere ("look to your left"): hold there for a while
            voiceAim != null -> { doing = "looking where you asked"; Triple(voiceAim!!.first, voiceAim!!.second, 2.5f) }
            // Looking for you ("face me", but you're out of view): first a bit further the way you
            // were last seen, then a slow sweep across to the other side
            now < searchUntil && !tracker.seesFace() -> {
                doing = "looking for you"
                val t = (now - searchStart) / 1000f
                val first = (searchFrom + searchDir * 25f).coerceIn(-range, range)
                val pan = if (t < 2f) first else first + (-searchDir * range - first) * ((t - 2f) / 7f).coerceAtMost(1f)
                Triple(pan, 0f, 1.6f)
            }
            // It sees you: watch you, like a dog does. See watch().
            tracker.seesFace() -> watch(now, range)
            // Just lost you (you turned away, or moved fast): wait a moment, then look the way you went
            tracker.lastSeen != 0L && sinceFace < 2500 -> {
                doing = "lost you a moment ago: waiting"
                gentle = true
                Triple(lostPan, lostTilt, 1.5f)
            }
            tracker.lastSeen != 0L && sinceFace < 7000 && abs(lastFaceX) > 0.25f -> {
                doing = "looking the way you went"
                gentle = true
                Triple((lostPan + sign(lastFaceX) * 25f).coerceIn(-range, range), lostTilt, 1.4f)
            }
            // Something moved where it can't see a face: turn to look
            now - tracker.motionAt < 2500 && sinceFace > 1500 -> {
                if (tracker.motionAt != motionSeen) {
                    motionSeen = tracker.motionAt
                    val (pastPan, pastTilt) = aimAt(tracker.motionAt - 150)
                    motionPan = (pastPan + tracker.motionX * HALF_VIEW_X * 0.8f).coerceIn(-range, range)
                    motionTilt = (pastTilt + tracker.motionY * HALF_VIEW_Y * 0.6f).coerceIn(-settings.tiltUp, settings.tiltDown)
                }
                doing = "something moved: having a look"
                gentle = true
                Triple(motionPan, motionTilt, 2f)
            }
            // Gone for a while: scan the room now and then (more often in a conversation, since
            // they're talking to it; rarely once they've been gone a long time), and in between
            // do its own thing
            else -> {
                gentle = true
                scanOrWander(now, range, sinceFace)
            }
        }
        wasFollowing = tracker.seesFace() && targetPan == followPan && targetTilt == followTilt
        // Glide there on a spring with no overshoot: eases in, eases out, like a real head turning
        // (rate 3 ≈ settled in 0.8 s)
        val step = dt.coerceAtMost(0.05f)
        val w = 2f * rate
        val maxSpeed = if (gentle) 30f else 220f      // degrees per second
        val maxAccel = (if (gentle) 90f else 1500f) * step
        panSpeed += ((w * w * (targetPan - aimPan) - 2f * w * panSpeed) * step).coerceIn(-maxAccel, maxAccel)
        tiltSpeed += ((w * w * (targetTilt - aimTilt) - 2f * w * tiltSpeed) * step).coerceIn(-maxAccel, maxAccel)
        panSpeed = panSpeed.coerceIn(-maxSpeed, maxSpeed)
        tiltSpeed = tiltSpeed.coerceIn(-maxSpeed, maxSpeed)
        aimPan = (aimPan + panSpeed * step).coerceIn(-range, range)
        aimTilt = (aimTilt + tiltSpeed * step).coerceIn(-settings.tiltUp, settings.tiltDown)

        // Moods and expressions add a gesture on top
        var gesturePan = 0f
        var gestureTilt = 0f
        if (!holdStill && manualAim == null) {
            gestureTilt -= 4f * musicBob  // a little nod to the beat
            gesturePan += 7f * musicSway  // ...and a sway, like dancing
            // Thinking: only the eyes glance up (see Face); the head stays on you
            if (mood == Mood.SPEAKING) gestureTilt -= 3f * gemini.voiceLevel()  // bob with the voice
            // Listening: the head lifts a little, attentive. After its name, this eases in along
            // the same half-second glide as the eyes, so head and eyes move as one.
            if (mood == Mood.LISTENING || mood == Mood.HEARING) gestureTilt -= 2.5f * gemini.listeningGlide(now)
            // Expressions and idle moves move the head too (see Expressions.kt)
            val latest = gemini.expression()
            val shown = when {
                mood == Mood.ASLEEP -> null
                mood == Mood.DOZING && latest?.first != "yawn" -> null
                else -> latest
            }
            if (shown != null) {
                val pose = Pose(1f, 1f, 1f)
                MOVES[shown.first]?.invoke(shown.second, pose)
                gesturePan += pose.neckPan
                gestureTilt += pose.neckTilt
            }
        }
        pan = (aimPan + gesturePan).coerceIn(-range, range)
        tilt = (aimTilt + gestureTilt).coerceIn(-settings.tiltUp, settings.tiltDown)
        // Remember where the head really pointed, gestures included (thinking, talking, nods): the
        // camera rides on the head, so that's what each picture was taken from. Remembering only the
        // base pose made every gesture look like you'd moved, and it kept "re-centring" back and
        // forth during conversations.
        remember(now)

        if (settings.neckOn) send(now)
    }

    // ---- Watching you, like a dog ----
    //
    // Near (your face looks big): it keeps you centred, with small smooth corrections, and only
    // glances away now and then. Far away: it keeps a looser eye on you and does its own thing more.
    // Each new camera sighting is used once. The camera's picture is a moment old, so the
    // correction is worked out from where the head was when the picture was taken (no overshoot).

    private var centering = false
    private var gentle = false
    private var scanFrom = 0f
    private var lastDetection = -1
    private var glanceUntil = 0L
    private var nextGlance = 0L
    private var glancePan = 0f
    private var glanceTilt = 0f
    private var motionSeen = 0L
    private var motionPan = 0f
    private var motionTilt = 0f
    private var scanStart = 0L
    private var nextScan = 0L
    private var scanDir = 1f
    // Where the head has been over the last second (for the camera's delay)
    private val pastTime = LongArray(40)
    private val pastPan = FloatArray(40)
    private val pastTilt = FloatArray(40)
    private var pastIndex = 0

    private fun remember(now: Long) {
        pastIndex = (pastIndex + 1) % pastTime.size
        pastTime[pastIndex] = now
        pastPan[pastIndex] = pan
        pastTilt[pastIndex] = tilt
    }

    // Where the head was pointing at time t (the nearest remembered moment)
    private fun aimAt(t: Long): Pair<Float, Float> {
        var best = pastIndex
        var bestGap = Long.MAX_VALUE
        for (i in pastTime.indices) {
            if (pastTime[i] == 0L) continue
            val gap = abs(pastTime[i] - t)
            if (gap < bestGap) {
                bestGap = gap
                best = i
            }
        }
        return if (pastTime[best] == 0L) aimPan to aimTilt else pastPan[best] to pastTilt[best]
    }

    private fun watch(now: Long, range: Float): Triple<Float, Float, Float> {
        if (!wasFollowing) {  // just found you: start from where the head already is
            followPan = aimPan
            followTilt = aimTilt
            centering = false
            nextGlance = now + Random.nextLong(30_000, 90_000)
        }
        lastFaceX = tracker.rawX
        val near = tracker.size > 0.07f  // within about a metre and a half
        val talking = mood != Mood.IDLE
        // How far off-centre before it re-centres you, and how close to the middle it stops
        val enter = settings.watchZone * (if (near || talking) 1f else 2.5f)
        val exit = enter * 0.4f
        // A fresh sighting, used once (but not while the head is swinging fast: that picture is
        // blurred and a moment out of date, so it would only cause an overshoot)
        if (tracker.detections != lastDetection && abs(panSpeed) < 60f && abs(tiltSpeed) < 60f) {
            lastDetection = tracker.detections
            val offX = abs(tracker.rawX)
            val offY = abs(tracker.rawY)
            if (!centering && (offX > enter || offY > enter * 1.3f)) centering = true
            if (centering && offX < exit && offY < exit * 1.3f) centering = false
            if (centering) {
                val (thenPan, thenTilt) = aimAt(now - 150)
                val goalPan = thenPan + tracker.rawX * HALF_VIEW_X
                val goalTilt = thenTilt + tracker.rawY * HALF_VIEW_Y
                followPan = (followPan + (goalPan - followPan) * 0.8f).coerceIn(-range, range)
                followTilt = (followTilt + (goalTilt - followTilt) * 0.8f).coerceIn(-settings.tiltUp, settings.tiltDown)
            }
        }
        // Now and then it glances away on its own (not mid-conversation): rarely when you're
        // close, more often when you're far off
        if (!talking && now >= nextGlance && glanceUntil < now) {
            glanceUntil = now + Random.nextLong(1800, 3500)
            val side = if (Random.nextBoolean()) 1f else -1f
            glancePan = (aimPan + side * Random.nextDouble(15.0, 30.0).toFloat()).coerceIn(-range, range)
            glanceTilt = (aimTilt + (Random.nextFloat() - 0.6f) * 8f).coerceIn(-settings.tiltUp, settings.tiltDown)
            nextGlance = glanceUntil + if (near) Random.nextLong(45_000, 120_000) else Random.nextLong(12_000, 30_000)
        }
        if (now < glanceUntil && !talking) {
            doing = "glancing about (it'll look back at you)"
            return Triple(glancePan, glanceTilt, 2f)
        }
        val side = (tracker.rawX * 100).toInt()
        doing = (if (near) "watching you" else "keeping an eye on you (you're far off)") +
            " · ${abs(side)} % ${if (side < 0) "left" else "right"} of centre" + if (centering) " · re-centring" else ""
        return Triple(followPan, followTilt, 4.5f * settings.neckFollow)
    }

    private fun scanOrWander(now: Long, range: Float, sinceFace: Long): Triple<Float, Float, Float> {
        val talking = mood != Mood.IDLE
        val longGone = tracker.lastSeen == 0L || sinceFace > 5 * 60_000
        if (nextScan == 0L) nextScan = now + 3000
        if (now >= nextScan && scanStart < nextScan) {
            scanStart = now
            scanFrom = aimPan
            scanDir = if (lastFaceX < 0f) -1f else 1f
            nextScan = now + 11_000 + when {
                talking -> Random.nextLong(5000, 9000)
                longGone -> Random.nextLong(100_000, 140_000)
                else -> Random.nextLong(25_000, 45_000)
            }
        }
        val t = (now - scanStart) / 1000f
        if (scanStart != 0L && t < 11f) {
            // A slow look round: from where it's pointing out to the side you were last seen on
            // (4 s), then across to the other side (7 s), easing in and out of each
            doing = "scanning the room for you"
            val edge = range * 0.9f
            val pan = if (t < 4f) {
                val f = (1f - cos(PI.toFloat() * t / 4f)) / 2f
                scanFrom + (scanDir * edge - scanFrom) * f
            } else {
                val f = (1f - cos(PI.toFloat() * (t - 4f) / 7f)) / 2f
                scanDir * edge * (1f - 2f * f)
            }
            return Triple(pan, -3f, 3f)
        }
        if (talking) {
            doing = "talking, can't see you: holding"
            return Triple(aimPan, aimTilt, 1f)
        }
        if (now >= nextWander) {
            wanderPan = (Random.nextFloat() - 0.5f) * range
            wanderTilt = (Random.nextFloat() - 0.5f) * settings.tiltUp * 0.6f
            nextWander = now + Random.nextLong(6000, 15000)
        }
        doing = "nobody seen: doing its own thing"
        return Triple(wanderPan, wanderTilt, 1.2f)
    }

    // "Look to your left", "look up a bit", "look at me": left and right as the person sees it.
    // Holds there for 30 seconds (or until it dozes), then goes back to watching them.
    fun turn(direction: String, amount: String) {
        if (direction == "me" || direction == "center") {
            voiceAim = null
            if (!tracker.seesFace()) {
                // Can't see them: go and look
                val now = System.currentTimeMillis()
                searchStart = now
                searchUntil = now + 10_000
                searchFrom = aimPan
                searchDir = if (lastFaceX < 0f) -1f else 1f
            }
            return
        }
        val (fromPan, fromTilt) = voiceAim ?: (aimPan to aimTilt)
        val far = amount == "far"
        val side = if (amount == "little") 10f else 25f
        val upDown = if (amount == "little") 6f else 14f
        val range = settings.panRange
        val aim = when (direction) {
            "left" -> (if (far) -range else fromPan - side) to fromTilt
            "right" -> (if (far) range else fromPan + side) to fromTilt
            "up" -> fromPan to (if (far) -settings.tiltUp else fromTilt - upDown)
            "down" -> fromPan to (if (far) settings.tiltDown else fromTilt + upDown)
            else -> return
        }
        voiceAim = aim.first.coerceIn(-range, range) to aim.second.coerceIn(-settings.tiltUp, settings.tiltDown)
        voiceAimUntil = System.currentTimeMillis() + 30_000
    }

    // Converts the pose to servo angles and sends it, when it has changed (or once a second,
    // so the ESP32 knows the phone is still there)
    private fun send(now: Long) {
        val servoPan = settings.panCenter + if (settings.flipPan) -pan else pan
        val servoTilt = settings.tiltCenter + if (settings.flipTilt) -tilt else tilt
        val line = String.format(Locale.US, "M %.1f %.1f\n", servoPan, servoTilt)
        if (line == lastSent && now - lastSentAt < 1000) return
        lastSent = line
        lastSentAt = now
        link.send(line)
    }

    // How far v is past a dead zone around zero (0 inside it)
    private fun beyond(v: Float, dead: Float) = if (abs(v) < dead) 0f else v - sign(v) * dead
}

// Talks to the neck's ESP32 over Bluetooth Low Energy. The first time, it scans for a device
// offering the neck's service and remembers its address; after that it reconnects directly,
// and keeps retrying every few seconds if the neck is off. Commands are short text lines
// ("M 135.0 90.0" = move the servos to these angles), written without waiting for a reply.
@SuppressLint("MissingPermission")  // every call is behind hasPermission()
class NeckLink(private val context: Context, private val settings: Settings) {
    @Volatile var status = "Off"
    @Volatile var connected = false
        private set
    @Volatile private var wanted = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var command: BluetoothGattCharacteristic? = null
    @Volatile private var versionChar: BluetoothGattCharacteristic? = null
    @Volatile private var updateChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    // The neck's firmware version (0 = unknown, 1 = the first one, which can't report it)
    @Volatile var firmware = 0
        private set
    // Sending an update: progress 0..1, and how it's going in words
    @Volatile var updating = false
        private set
    @Volatile var updateProgress = 0f
        private set
    @Volatile var updateStatus = ""
        private set
    // Waiting for one Bluetooth write or read to finish (used while updating)
    @Volatile private var waitDone: java.util.concurrent.CountDownLatch? = null
    @Volatile private var waitResult = 0
    @Volatile private var readValue: ByteArray? = null
    @Volatile private var scanning = false
    // Only one Bluetooth write can be in flight; newer moves replace any that are waiting
    private val lock = Any()
    private var writing = false
    private var writingSince = 0L
    private var pending: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    companion object {
        // What Android asks you to allow before using Bluetooth
        fun permissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)  // older Android needs this to scan
        }
    }

    fun hasPermission() = permissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        wanted = true
        handler.post { connectOrScan() }
    }

    fun stop() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        disconnect()
        status = "Off"
    }

    // Forget the remembered neck and look for one again (e.g. after swapping the ESP32)
    fun forget() {
        settings.neckAddress = ""
        disconnect()
        if (wanted) handler.post { connectOrScan() }
    }

    fun send(line: String) {
        synchronized(lock) { pending = line }
        flush()
    }

    private fun flush() {
        val g = gatt
        val c = command
        if (!connected || updating || g == null || c == null) return
        val line = synchronized(lock) {
            // A write that never finished (rare) shouldn't block everything after it
            if (writing && System.currentTimeMillis() - writingSince < 1000) {
                null
            } else {
                pending?.also {
                    pending = null
                    writing = true
                    writingSince = System.currentTimeMillis()
                }
            }
        } ?: return
        val bytes = line.toByteArray()
        val started = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: SecurityException) {
            false
        }
        if (!started) synchronized(lock) { writing = false }
    }

    private fun connectOrScan() {
        if (!wanted || gatt != null || scanning) return
        if (!hasPermission()) {
            status = "Needs Bluetooth permission (turn the neck off and on to ask again)"
            return
        }
        val a = adapter
        if (a == null) {
            status = "This phone has no Bluetooth"
            return
        }
        if (!a.isEnabled) {
            status = "Bluetooth is off · turn it on in the phone's settings"
            retryLater()
            return
        }
        val saved = settings.neckAddress
        if (saved.isNotBlank()) connect(a.getRemoteDevice(saved)) else scan()
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return retryLater()
        status = "Looking for the neck…"
        scanning = true
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NECK_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback
            )
        } catch (e: SecurityException) {
            scanning = false
            status = "Needs Bluetooth permission"
            return
        }
        handler.postDelayed({
            if (scanning) {
                stopScan()
                status = "Neck not found · is the ESP32 powered? Trying again…"
                retryLater()
            }
        }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w("Orbit", "Couldn't stop the Bluetooth scan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            settings.neckAddress = result.device.address
            handler.post { connect(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status = "Bluetooth scan failed (error $errorCode) · trying again…"
            retryLater()
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!wanted || gatt != null) return
        status = "Connecting to the neck…"
        gatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            status = "Needs Bluetooth permission"
            null
        }
    }

    private fun disconnect() {
        val g = gatt
        gatt = null
        command = null
        versionChar = null
        updateChar = null
        waitDone?.countDown()
        connected = false
        synchronized(lock) { writing = false }
        try {
            g?.disconnect()
            g?.close()
        } catch (e: SecurityException) {
            // permission was taken away; nothing more to do
        }
    }

    private fun retryLater() {
        if (wanted) handler.postDelayed({ connectOrScan() }, 5_000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, result: Int, newState: Int) {
            if (g != gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status = "Connected · checking it's the neck…"
                // Ask for big packets first (quick updates), then look at what it offers
                if (!g.requestMtu(517)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
                status = "Neck disconnected · trying again…"
                retryLater()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, result: Int) {
            if (g != gatt) return
            if (result == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, result: Int) {
            if (g != gatt) return
            val service = g.getService(NECK_SERVICE)
            val c = service?.getCharacteristic(NECK_COMMAND)
            if (c == null) {
                status = "That device isn't an Orbit neck · tap Find the neck again"
                return
            }
            command = c
            versionChar = service.getCharacteristic(NECK_VERSION)
            updateChar = service.getCharacteristic(NECK_UPDATE)
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)  // smoother moves
            // Which firmware is it running? (the first version can't say)
            val v = versionChar
            if (v == null || !g.readCharacteristic(v)) {
                firmware = 1
                ready()
            }
        }

        // Android 13 and newer
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, result: Int) {
            handleRead(c, value, result)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, result: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) handleRead(c, c.value ?: ByteArray(0), result)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, result: Int) {
            if (c.uuid == NECK_UPDATE) {
                waitResult = result
                waitDone?.countDown()
                return
            }
            synchronized(lock) { writing = false }
            flush()
        }
    }

    private fun handleRead(c: BluetoothGattCharacteristic, value: ByteArray, result: Int) {
        when (c.uuid) {
            NECK_VERSION -> {
                firmware = String(value).trim().toIntOrNull() ?: 1
                ready()
            }
            NECK_UPDATE -> {
                readValue = value
                waitResult = result
                waitDone?.countDown()
            }
        }
    }

    // Connected and checked: moves can flow
    private fun ready() {
        connected = true
        status = if (updateAvailable()) "Neck connected · an update is ready (Settings → Neck)" else "Neck connected"
        flush()
    }

    // ---- Updating the neck's firmware over Bluetooth ----

    // The version of the firmware inside the app (read from the tag the firmware carries), or 0
    fun bundledVersion(): Int = bundled?.first ?: 0

    fun updateAvailable() = connected && updateChar != null && bundledVersion() > firmware && !updating

    // What it found when it looked for an update file inside the app (shown in Settings → Neck)
    @Volatile var bundledNote = ""
        private set

    // The newest neck firmware inside the app: any .bin file in app/src/main/assets (whatever it's
    // called), recognised by the version tag the firmware carries
    private val bundled: Pair<Int, ByteArray>? by lazy {
        val files = try {
            context.assets.list("")?.filter { it.endsWith(".bin", ignoreCase = true) }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val found = files.mapNotNull { name -> readFirmware(name)?.let { name to it } }
        val best = found.maxByOrNull { it.second.first }
        bundledNote = when {
            files.isEmpty() -> "No update file inside this app (put the .bin in app/src/main/assets, then run the app again)."
            best == null -> "Found ${files.joinToString()} in the app, but it isn't Orbit neck firmware version 2 or newer."
            else -> "Update file inside the app: ${best.first} (version ${best.second.first})."
        }
        best?.second
    }

    private fun readFirmware(name: String): Pair<Int, ByteArray>? {
        try {
            val bytes = context.assets.open(name).use { it.readBytes() }
            val tag = "ORBIT_NECK_VERSION=".toByteArray()
            var version = 0
            outer@ for (i in 0..bytes.size - tag.size - 1) {
                for (j in tag.indices) if (bytes[i + j] != tag[j]) continue@outer
                var k = i + tag.size
                val digits = StringBuilder()
                while (k < bytes.size && bytes[k] in '0'.code.toByte()..'9'.code.toByte()) digits.append(bytes[k++].toInt().toChar())
                version = digits.toString().toIntOrNull() ?: 0
                break
            }
            return if (version > 0) version to bytes else null
        } catch (e: Exception) {
            return null
        }
    }

    // Sends the built-in firmware to the neck (about a minute), on a background thread. The neck
    // checks it, restarts into it, and reconnects by itself.
    fun update() {
        val (version, bytes) = bundled ?: return
        if (updating || !connected) return
        updating = true
        updateProgress = 0f
        updateStatus = "Starting…"
        status = "Updating the neck…"
        thread(name = "neck-update") {
            val ok = try {
                send(byteArrayOf(1) + littleEndian(bytes.size))
                val piece = (mtu - 4).coerceIn(20, 508)
                var at = 0
                while (at < bytes.size) {
                    val end = minOf(at + piece, bytes.size)
                    send(byteArrayOf(2) + bytes.copyOfRange(at, end))
                    at = end
                    updateProgress = at.toFloat() / bytes.size
                    updateStatus = "Sending… ${(updateProgress * 100).toInt()} %"
                }
                send(byteArrayOf(3))
                updateStatus = "Checking…"
                val answer = readUpdateStatus()
                if (answer.startsWith("done")) true else throw IllegalStateException(answer.ifBlank { "no answer" })
            } catch (e: Exception) {
                Log.w("Orbit", "Neck update failed", e)
                updateStatus = "Update failed: ${e.message}. The neck keeps its old version; try again."
                try {
                    updateChar?.let { gatt?.let { g -> writeNow(g, it, byteArrayOf(4)) } }
                } catch (_: Exception) {
                }
                false
            }
            updating = false
            if (ok) {
                updateStatus = "✓ Updated to version $version. The neck is restarting (its head may jump to the middle)."
                firmware = version
            }
            status = if (ok) "Neck restarting…" else "Neck connected"
        }
    }

    private fun littleEndian(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    // One write to the update characteristic, waiting until the neck has taken it
    private fun send(bytes: ByteArray) {
        val g = gatt ?: throw IllegalStateException("the neck disconnected")
        val c = updateChar ?: throw IllegalStateException("the neck disconnected")
        val latch = java.util.concurrent.CountDownLatch(1)
        waitDone = latch
        var tries = 0
        while (!writeNow(g, c, bytes)) {  // Bluetooth busy for a moment: try again
            if (++tries > 50) throw IllegalStateException("Bluetooth is busy")
            Thread.sleep(20)
        }
        if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("the neck stopped answering")
        if (gatt == null) throw IllegalStateException("the neck disconnected")
        if (waitResult != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("Bluetooth error $waitResult")
    }

    private fun writeNow(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }

    private fun readUpdateStatus(): String {
        val g = gatt ?: return ""
        val c = updateChar ?: return ""
        val latch = java.util.concurrent.CountDownLatch(1)
        waitDone = latch
        readValue = null
        if (!g.readCharacteristic(c)) return ""
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return readValue?.let { String(it).trim() } ?: ""
    }
}

private fun lerp(from: Float, to: Float, amount: Float) = from + (to - from) * amount
