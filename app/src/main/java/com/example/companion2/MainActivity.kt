package com.example.companion2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.system.exitProcess
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val tracker = FaceTracker()
    private val mic = Microphone()
    private lateinit var settings: Settings
    private lateinit var gemini: GeminiLive
    private lateinit var wake: WakeWord
    private lateinit var life: InnerLife
    private lateinit var neck: Neck
    private lateinit var heat: HeatWatch
    private lateinit var sounds: Sounds
    private lateinit var weather: Weather
    private lateinit var desk: DeskLink
    private lateinit var screenFinder: ScreenFinder
    private lateinit var gestures: Gestures
    private lateinit var people: People
    private lateinit var calendar: DayCalendar
    private val music = MusicSense()
    private lateinit var player: MusicControl  // plays and controls your music
    private var lastSongReaction = 0L
    private var maybeCalledAt = 0L  // when it last heard what might be its name (music dips)
    // Covering the camera with a hand (the quick "quiet!"): how bright it usually is, and
    // how many frames in a row it's been dark
    private var usualBrightness = 0f
    private var darkFrames = 0
    private var lastCovered = 0L
    // Whether the phone can reach the internet (for the "offline" sign)
    @Volatile private var online = true
    private val peopleExecutor = Executors.newSingleThreadExecutor()
    private var lastGestureCheck = 0L
    private var lastPeopleCheck = 0L
    private val eventsWarned = mutableSetOf<Long>()  // calendar events it already gave a heads-up for
    // When you sat down (for "time for a stretch"), and when it last suggested one
    private var sittingSince = System.currentTimeMillis()
    private var lastStretchNudge = 0L
    // A tiny picture of the last camera frame, to notice movement
    private var lastGrid: IntArray? = null
    private lateinit var cameraExecutor: ExecutorService
    private var showSettings by mutableStateOf(false)
    private var showWelcome by mutableStateOf(false)  // the first-time setup
    private var cameraProvider: ProcessCameraProvider? = null

    // Privacy sleep: camera and microphone fully off until you double-tap again
    @Volatile private var asleep = false
    // Privacy sleep: camera and microphone fully off (ordinary sleep keeps a gentle eye out,
    // checking for a face once a second on the phone, so it can wake when you come back)
    @Volatile private var privacy = false
    private var sleptAt = 0L
    private var lastFaceAsleep = 0L   // last time it saw a face while asleep
    @Volatile private var lastFaceCheck = 0L
    // Set when Gemini asks to look: the next camera frame becomes a photo and goes here
    @Volatile private var photoWanted: ((ByteArray?) -> Unit)? = null
    @Volatile private var photoSize = 768f  // longest side; bigger for reading a screen

    // Dozing: awake, but nobody has been around for a while and no conversation is open
    private fun dozing() = !asleep && !gemini.active &&
        System.currentTimeMillis() - tracker.lastAround > settings.dozeAfter * 60_000

    // Asks for camera and microphone permission, then starts whatever was allowed
    private val askForPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (asleep) return@registerForActivityResult
            if (results[Manifest.permission.CAMERA] == true) startCamera()
            if (results[Manifest.permission.RECORD_AUDIO] == true) mic.start()
        }

    private fun allowed(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on while the app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Use the whole screen, including beside the camera hole, with no dark strip
        // behind the navigation bar area
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // and no see-through dark band where the status bar would be
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        hideSystemBars()

        settings = Settings(this)
        comeBackAfterCrashes()
        // Test builds may fall back to your own key from local.properties; published builds never do
        gemini = GeminiLive(
            settings,
            if (BuildConfig.DEBUG) BuildConfig.GEMINI_API_KEY else "",
            getSystemService(AudioManager::class.java)
        )
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Watches the phone's temperature (checked below)
        heat = HeatWatch(this)
        // Already know each other from before this update: no first wake-up, count from today
        if (!settings.hatched && (settings.userName.isNotBlank() || settings.memoryNotes().isNotEmpty())) {
            settings.hatched = true
            if (settings.firstMet.isBlank()) settings.firstMet = LocalDate.now().toString()
        }
        // Met before the welcome setup existed (on an earlier day): no need to see it
        if (settings.hatched && settings.firstMet.isNotBlank() && settings.firstMet != LocalDate.now().toString()) {
            settings.setupDone = true
        }

        // Its little sounds (made in code), and the weather for the eyes
        sounds = Sounds(settings)
        weather = Weather(settings)
        // Orbit Desk on your computer, so it can look at your screen when you ask
        desk = DeskLink(settings)
        gemini.desk = desk

        // Its own life: a little homework on its hobby once a day (checked below)
        life = InnerLife(settings) { gemini.key() }

        // The neck (pan/tilt stand over Bluetooth). Its moves are worked out even with no neck
        // connected, so they can be watched on the Neck tab.
        neck = Neck(settings, tracker, gemini, NeckLink(this, settings))
        if (settings.neckOn && neck.link.hasPermission()) neck.link.start()
        screenFinder = ScreenFinder(settings, neck, gemini)

        // Keep track of the internet connection, so it can say plainly when it's offline
        watchInternet()

        // Hand gestures, knowing who's who, and the phone's calendar (all on the phone)
        gestures = Gestures(this, settings)
        if (settings.gesturesOn) gestures.prepare()
        people = People(this, settings)
        if (settings.peopleOn) people.prepare()
        people.onMet = { name, _ ->
            runOnUiThread {
                settings.addMoment("Met $name")
                gemini.showExpression("happy")
                if (!gemini.tell("(You've now learned $name's face, so you'll know them next time. Say so in a few happy words.)")) {
                    gemini.status = "Nice to meet you, $name!"
                }
            }
        }
        calendar = DayCalendar(this, settings)
        // Who's here and what's on today, for the conversation
        gemini.extraContext = { aboutPeopleAndDay() + aboutMusic() }

        // Music: what's playing in your music apps (and Spotify on the computer), and controls
        player = MusicControl(this, settings)
        player.start()
        player.onSongChanged = { title, artist -> runOnUiThread { songChanged(title, artist) } }
        gemini.onMusic = { name, args -> musicTool(name, args) }
        // The voice direction test records in stereo, so the usual microphone pauses meanwhile
        neck.voice.onStart = { mic.stop() }
        neck.voice.onStop = { if (!asleep && allowed(Manifest.permission.RECORD_AUDIO)) mic.start() }
        // Weather in words when asked (and in the eyes while it says it); its town is remembered
        gemini.onWeather = { name, args ->
            if (name == "set_location") {
                weather.setTown(args.optString("town"))
            } else {
                // (the eyes zoom in and show it while Orbit talks: see Face)
                weather.report(args.optString("when", "now"))
            }
        }
        handleLink(intent)
        lifecycleScope.launch {
            var last = System.nanoTime()
            while (true) {
                // Done fine-tuning where the screen is (the conversation ended): back to normal
                if (neck.manualAim != null && !gemini.active && !screenFinder.busy && !showSettings) {
                    neck.manualAim = null
                }
                val now = System.nanoTime()
                neck.step(((now - last) / 1_000_000_000f).coerceAtMost(0.2f))
                last = now
                delay(33)
            }
        }

        // Wake word: saying the phrase starts a conversation, like a tap
        wake = WakeWord(this, settings)
        // The start of its name, maybe: drop the music right away so it can hear the rest
        // (it comes back after 3 seconds if that wasn't its name)
        wake.onMaybe = {
            runOnUiThread {
                if (player.playing && !gemini.active) {
                    maybeCalledAt = System.currentTimeMillis()
                    player.duck()
                }
            }
        }
        wake.onHeard = {
            runOnUiThread {
                // In settings it just shows it heard you (so you can test it there)
                if (!showSettings && !asleep && !gemini.active && gemini.hasKey()) {
                    tracker.touched()
                    sounds.hushedUntil = 0L  // calling it by name ends a "shh"
                    gemini.showExpression("curious")  // a little "yes?" while it connects
                    player.duck()
                    gemini.start(fromWakeWord = true)
                }
            }
        }
        if (settings.wakeWordOn) wake.prepare()

        // "What am I holding?": hand the next camera frame to Gemini as a photo.
        // If no frame comes (the camera is off), give up after 3 seconds.
        gemini.onLook = { sharp, deliver ->
            photoSize = if (sharp) 1600f else 768f
            photoWanted = deliver
            lifecycleScope.launch {
                delay(3000)
                if (photoWanted === deliver) {
                    photoWanted = null
                    deliver(null)
                }
            }
        }

        // Things you can ask for out loud ("goodnight", "that's all", "louder"…)
        gemini.onCommand = { name, args -> runOnUiThread { voiceCommand(name, args) } }

        if (allowed(Manifest.permission.CAMERA)) startCamera()
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filterNot { allowed(it) }
        if (missing.isNotEmpty()) askForPermissions.launch(missing.toTypedArray())

        // Your voice goes to Gemini whenever a conversation is open
        mic.onSound = { sound, level ->
            gemini.sendSound(sound, level)
            // Between conversations, listen (on the phone only) for the wake phrase
            if (settings.wakeWordOn && !gemini.active && !asleep) wake.listen(sound)
            // ...and for music, to bob along to
            if (settings.musicOn && !gemini.active && !asleep) music.listen(sound)
        }

        // Every 30 seconds: has night started or ended, and is today's homework due?
        // (Homework waits until no conversation is open, and until the phone isn't warm.)
        lifecycleScope.launch {
            while (true) {
                checkNight()
                if (!gemini.active && heat.level == 0 && life.homeworkDue()) life.doHomework()
                checkNudges()
                checkCalendar()
                maybeDaydream()
                delay(30_000)
            }
        }

        // Every 10 seconds: is the phone too warm?
        lifecycleScope.launch {
            while (true) {
                heat.check()
                checkHeat()
                delay(10_000)
            }
        }

        // Every second: is a reminder or timer due?
        lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                settings.reminderList().filter { it.first <= now }.forEach { due ->
                    settings.removeReminder(due)
                    remind(due.second)
                }
                // Someone with messages waiting is in front of it: pass them on
                deliverMessages(now)
                // Music: the sleep timer, and quieter only while a conversation is open
                player.tick(now)
                // Dozing for a while with nobody around: it really falls asleep (z's); seeing
                // you or a tap wakes it as usual
                if (settings.sleepAfterDoze > 0f && dozing() &&
                    now - tracker.lastAround > ((settings.dozeAfter + settings.sleepAfterDoze) * 60_000).toLong()
                ) {
                    goToSleep()
                }
                if (gemini.active) player.duck() else if (now - maybeCalledAt > 3000) player.unduck()
                delay(1000)
            }
        }
        // Looking down at the desk only really works with a neck to tilt the camera
        gemini.canLookDown = { settings.neckOn && neck.link.connected }
        // "Can you see me?": answered from face tracking, never guessed
        gemini.presence = { neck.whereAreThey() }
        gemini.seesThem = { tracker.seesFace() }
        // Turning to look at the computer screen needs the neck and a saved screen spot
        gemini.screenProblem = {
            when {
                !settings.neckOn || !neck.link.connected -> "your neck isn't connected, so you can't turn to the screen"
                !settings.screenSpotSet -> "you don't know where their screen is yet; offer to " +
                    "look for it (find_screen)"
                else -> null
            }
        }

        setContent {
            // A short orbit animation plays first, then the eyes appear
            var intro by remember { mutableStateOf(true) }
            if (intro) {
                LaunchedEffect(Unit) { sounds.play(Sound.WHOOSH, 0.5f) }  // subtle
                Intro(settings) {
                    intro = false
                    when {
                        !settings.hatched -> hatch()  // the very first time: it wakes up and meets you
                        !settings.setupDone -> showWelcome = true  // setup was interrupted: carry on
                    }
                }
            } else {
                // The phone's Back gesture also returns from settings to the eyes
                BackHandler(enabled = showSettings) { closeSettings() }
                BackHandler(enabled = showWelcome) { finishWelcome() }

                if (showWelcome) {
                    Welcome(settings, gemini) { finishWelcome() }
                } else if (showSettings) {
                    SettingsScreen(
                        settings, mic, gemini, wake, life, neck, heat, sounds, weather, desk,
                        gestures, people, calendar, player,
                        onHatch = {
                            closeSettings()
                            hatch(replay = true)
                        },
                        onFindScreen = {
                            closeSettings()
                            findScreen()
                        },
                        onTryMove = { move ->
                            closeSettings()
                            lifecycleScope.launch {
                                delay(700)  // let the eyes appear first
                                gemini.showExpression(move.name, move.ms)
                                for ((at, sound) in move.sounds) {
                                    launch {
                                        delay((at * move.ms).toLong())
                                        sounds.play(sound, 0.45f)
                                    }
                                }
                            }
                        },
                        onClose = { closeSettings() }
                    )
                } else {
                    Face(
                        tracker, mic, gemini, settings, neck, sounds, weather,
                        isAsleep = { asleep },
                        isPrivate = { privacy },
                        isDozing = { dozing() },
                        isNight = { settings.isNight() && !settings.nightSleep },
                        music = music,
                        player = player,
                        isWarm = { heat.level >= 1 },
                        isOffline = { !online },
                        onTap = {
                            val wasDozing = dozing()
                            tracker.touched()
                            sounds.hushedUntil = 0L  // a tap ends a "shh"
                            when {
                                wasDozing -> {}  // the first tap just wakes it from dozing
                                asleep -> {}
                                // Music on: a tap stops it at once (the quickest "quiet!")
                                !gemini.active && player.playing -> quietNow("Music paused  ·  tap again to talk")
                                !gemini.hasKey() -> { showSettings = true }  // needs a key first
                                gemini.active -> gemini.stop()
                                !online -> {  // nothing to talk to: say so plainly
                                    gemini.status = "Offline · no internet"
                                    sounds.play(Sound.END, 0.4f)
                                }
                                else -> {
                                    player.duck()
                                    gemini.start()
                                }
                            }
                        },
                        onDoubleTap = {
                            tracker.touched()
                            if (asleep) wakeUp() else goToSleep()
                        },
                        onLongPress = {
                            tracker.touched()
                            gemini.stop()
                            showSettings = true
                        },
                        onSwipe = { direction -> swiped(direction) }
                    )
                }
            }
        }
    }

    private fun voiceCommand(name: String, args: JSONObject) {
        when (name) {
            "go_to_sleep" -> afterSpeaking { if (!asleep) goToSleep() }
            "privacy_mode" -> afterSpeaking { goToSleep(private = true) }
            "find_screen" -> findScreen()
            "start_focus" -> startFocus(args.optDouble("minutes", 25.0))
            "stop_focus" -> stopFocus()
            "meet_person" -> meetPerson(args.optString("name"))
            "aim_screen" -> aimScreen(args)
            "turn_head" -> neck.turn(args.optString("direction"), args.optString("amount", "more"))
            "save_screen_spot" -> {
                neck.manualAim = null  // the spot is saved as it moves; just let go
                gemini.showExpression("happy")
            }
            "end_conversation" -> afterSpeaking { gemini.stop() }
            "change_volume" -> changeVolume(louder = args.optString("direction") != "down")
            "set_greeting" -> settings.greet = args.optBoolean("on", true)
        }
    }

    // Looks around for the computer screen, then (in conversation) asks you to fine-tune it
    private fun findScreen() {
        if (screenFinder.busy) return
        sounds.play(Sound.TWINKLE, 0.4f)
        screenFinder.find { problem ->
            runOnUiThread {
                val message = if (problem == null) SCREEN_FOUND else screenNotFound(problem)
                if (problem == null) gemini.showExpression("happy")
                when {
                    gemini.tell(message) -> {}
                    gemini.hasKey() -> gemini.start(greet = message)
                    else -> gemini.status = problem ?: "Found your screen"
                }
            }
        }
    }

    // "A bit left", "more up"…: nudge the head and keep the new spot
    private fun aimScreen(args: JSONObject) {
        val (pan, tilt) = neck.manualAim ?: (settings.screenPan to settings.screenTilt)
        val step = if (args.optString("amount") == "more") 10f else 4f
        val (dPan, dTilt) = when (args.optString("direction")) {
            "left" -> -step to 0f
            "right" -> step to 0f
            "up" -> 0f to -step * 0.7f
            "down" -> 0f to step * 0.7f
            else -> 0f to 0f
        }
        val aim = (pan + dPan).coerceIn(-settings.panRange, settings.panRange) to
            (tilt + dTilt).coerceIn(-settings.tiltUp, settings.tiltDown)
        neck.manualAim = aim
        settings.screenPan = aim.first
        settings.screenTilt = aim.second
        settings.screenSpotSet = true
    }

    // Messages for whoever it recognizes (face), said straight away
    private var lastDelivery = 0L
    private fun deliverMessages(now: Long) {
        if (!settings.peopleOn || asleep || !gemini.hasKey() || !online || now - lastDelivery < 30_000) return
        val here = people.current ?: return
        if (now - people.currentAt > 20_000 || settings.messagesFor(here).isEmpty()) return
        lastDelivery = now
        val list = settings.takeMessages(here)
        val text = "($here is here, and there are messages for them. Greet them by name and pass these on " +
            "now, naturally (who from, roughly when): " + list.joinToString(" ") { "From ${it.from}: \"${it.text}\"." } + ")"
        if (!gemini.tell(text)) gemini.start(greet = text)
    }

    private fun hourMinute(at: Long): String =
        java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))

    // Lets Orbit finish its goodnight or goodbye before acting (gives up after 8 seconds)
    private fun afterSpeaking(action: () -> Unit) {
        lifecycleScope.launch {
            delay(800)  // its last words may not have started playing yet
            val giveUpAt = System.currentTimeMillis() + 8000
            while (gemini.speaking() && System.currentTimeMillis() < giveUpAt) delay(200)
            action()
        }
    }

    // Two steps louder or quieter, for both call-style and normal playback
    private fun changeVolume(louder: Boolean) {
        val audio = getSystemService(AudioManager::class.java)
        val direction = if (louder) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        for (stream in listOf(AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC)) {
            repeat(2) { audio.adjustStreamVolume(stream, direction, 0) }
        }
    }

    // The welcome setup is done (or skipped): back to the eyes, and say hello if it can talk
    private fun finishWelcome() {
        showWelcome = false
        settings.setupDone = true
        tracker.touched()
        hideSystemBars()
        if (gemini.hasKey()) {
            gemini.start(greet = FIRST_MEETING)
        } else {
            gemini.status = "Add a key any time: press and hold → Conversation"
        }
    }

    // Appliance mode: if Orbit ever crashes, open it again (but not over and over: if it
    // crashes again within a minute, let it stop so the problem can be seen)
    private fun comeBackAfterCrashes() {
        val usual = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e("Orbit", "Crashed", error)
            val crashes = getSharedPreferences("orbit_crashes", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val previous = crashes.getLong("last", 0L)
            crashes.edit().putLong("last", now).commit()
            if (settings.comeBack && now - previous > 60_000) {
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                } catch (_: Exception) {
                }
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } else {
                usual?.uncaughtException(thread, error)
            }
        }
    }

    // Appliance mode: keep Orbit pinned on screen (Android's "pin app"), if switched on.
    // Android shows an "App pinned" message each time an app pins itself, so: only if it isn't
    // pinned already, only once the intro is over and nothing's open on top, and never in test
    // builds run from Android Studio (they relaunch all the time). The switch in settings
    // still pins straight away when you turn it on.
    private var pinLater: kotlinx.coroutines.Job? = null

    private fun pinIfWanted() {
        if (!settings.pinned || BuildConfig.DEBUG) return
        pinLater?.cancel()
        pinLater = lifecycleScope.launch {
            delay(6000)
            val pinning = getSystemService(android.app.ActivityManager::class.java)?.lockTaskModeState
            if (pinning != android.app.ActivityManager.LOCK_TASK_MODE_NONE) return@launch
            if (showSettings || showWelcome) return@launch
            try {
                startLockTask()
            } catch (e: Exception) {
                Log.w("Orbit", "Couldn't pin Orbit", e)
            }
        }
    }

    private fun closeSettings() {
        tracker.touched()
        showSettings = false
        hideSystemBars()  // the keyboard in settings can bring the system bars back
    }

    // Full screen: hide the status bar and navigation bar (swipe from an edge to see them)
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Android sometimes brings the bars back (after a pop-up or the keyboard); hide them again
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Night schedule set to sleep: go to sleep when night starts, wake when it ends
    // (only if it was the schedule that put it to sleep, not a double-tap)
    private var wasNight = false
    private var sleptForNight = false

    private fun checkNight() {
        val night = settings.isNight()
        if (night == wasNight) return
        wasNight = night
        if (night && settings.nightSleep && !asleep) {
            goToSleep()
            sleptForNight = true
        } else if (!night && sleptForNight) {
            sleptForNight = false
            if (asleep) wakeUp()
        }
    }

    // Too hot: sleep until it has cooled down, then wake (only if the heat put it to sleep)
    private var lastHeatLevel = 0
    private var sleptForHeat = false

    private fun checkHeat() {
        val level = heat.level
        if (level == lastHeatLevel) return
        if (level == 2 && !asleep) {
            goToSleep(private = true)  // camera off too, to cool down
            sleptForHeat = true
        } else if (level < 2 && sleptForHeat) {
            sleptForHeat = false
            if (asleep) wakeUp()
        }
        lastHeatLevel = level
    }

    // A reminder or timer is due: wake if asleep, chime, look up, and say what it's for
    private fun remind(what: String) {
        tracker.touched()
        if (asleep) wakeUp()
        val focusDone = what == FOCUS_LABEL
        sounds.play(if (focusDone || what.contains("timer", ignoreCase = true)) Sound.TIMER else Sound.CHIME)
        gemini.showExpression(if (focusDone) "celebrate" else "alert")
        if (focusDone) {
            settings.focusUntil = ""
            settings.addMoment("Finished a focus session")
        }
        val message = if (focusDone) FOCUS_DONE else reminderDue(what)
        when {
            gemini.tell(message) -> {}                      // mid-conversation: it just says it
            gemini.hasKey() -> lifecycleScope.launch {      // after the chime, open a conversation
                delay(1200)
                gemini.start(greet = message)
            }
            else -> gemini.status = "⏰  $what"             // no key: just show it
        }
    }

    // Sleep: eyes shut, microphone off, no conversations. Ordinary sleep keeps checking for a
    // face once a second (on the phone) and wakes when you come back; privacy sleep switches
    // the camera off too and only wakes on a double-tap.
    private fun goToSleep(private: Boolean = false) {
        asleep = true
        privacy = private
        neck.privacy = private
        sleptAt = System.currentTimeMillis()
        lastFaceAsleep = sleptAt  // you're probably right there as it falls asleep
        gemini.stop()
        mic.stop()
        if (private) cameraProvider?.unbindAll()
    }

    private fun wakeUp() {
        asleep = false
        privacy = false
        neck.privacy = false
        if (allowed(Manifest.permission.CAMERA)) startCamera()
        if (allowed(Manifest.permission.RECORD_AUDIO)) mic.start()
    }

    // Listen only while the app is on screen
    override fun onStart() {
        super.onStart()
        if (!asleep && allowed(Manifest.permission.RECORD_AUDIO)) mic.start()
    }

    override fun onResume() {
        super.onResume()
        pinIfWanted()
        player.start()  // in case music access was just allowed
    }

    override fun onStop() {
        super.onStop()
        mic.stop()
        gemini.stop()
    }

    // Turns on the front camera and sends each frame to the face detector
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            if (asleep && privacy) return@addListener
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)  // smiles
                    .build()
            )
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { frame -> findFace(frame, detector) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    // Finds the biggest face in a camera frame and records where it is
    @OptIn(ExperimentalGetImage::class)
    private fun findFace(frame: ImageProxy, detector: FaceDetector) {
        val image = frame.image
        photoWanted?.let { deliver ->
            photoWanted = null
            deliver(if (image != null) takePhoto(frame) else null)
        }
        // Asleep: look for a face once a second. Dozing or warm: twice a second (keeps it cool).
        val now = System.currentTimeMillis()
        val gap = when {
            asleep -> 1000
            dozing() || heat.level >= 1 -> 500
            else -> 0
        }
        if (image == null || now - lastFaceCheck < gap) {
            frame.close()
            return
        }
        lastFaceCheck = now
        val rotation = frame.imageInfo.rotationDegrees
        val sideways = rotation == 90 || rotation == 270
        val width = (if (sideways) frame.height else frame.width).toFloat()
        val height = (if (sideways) frame.width else frame.height).toFloat()

        // Nobody in view: notice anything moving, so the eyes can glance at it
        if (!asleep) checkCovered(frame)
        if (settings.habits && !asleep && !tracker.seesFace() && !dozing()) noticeMovement(frame) else lastGrid = null

        // An upright picture for hand gestures (3 times a second) and recognizing faces (once a
        // second, only while it needs to), made only when one of them wants it
        val gestureDue = settings.gesturesOn && gestures.ready && !asleep && now - lastGestureCheck > 330
        val peopleDue = settings.peopleOn && !asleep && now - lastPeopleCheck > 1000 && people.wantsLook(now)
        val picture = if (gestureDue || peopleDue) uprightPicture(frame) else null
        if (gestureDue && picture != null) {
            lastGestureCheck = now
            gestures.check(picture)?.let { gesture -> runOnUiThread { onGesture(gesture) } }
        }

        detector.process(InputImage.fromMediaImage(image, rotation))
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                // Asleep: wake when you come back (a face after none for 20 seconds), but not
                // while you're still sitting there after putting it to sleep
                if (asleep) {
                    if (face != null && !privacy) {
                        val seen = System.currentTimeMillis()
                        if (seen - lastFaceAsleep > 20_000 && seen - sleptAt > 20_000) wakeUp()
                        lastFaceAsleep = seen
                    }
                    return@addOnSuccessListener
                }
                if (face != null && peopleDue && picture != null) {
                    lastPeopleCheck = System.currentTimeMillis()
                    // The picture was shrunk; shrink the face's box to match
                    val k = picture.width / width
                    val box = face.boundingBox
                    val scaled = android.graphics.Rect(
                        (box.left * k).toInt(), (box.top * k).toInt(), (box.right * k).toInt(), (box.bottom * k).toInt()
                    )
                    peopleExecutor.execute { people.look(picture, scaled) }
                }
                if (face != null) {
                    tracker.smile = face.smilingProbability ?: 0f
                    tracker.size = face.boundingBox.width().toFloat() / width
                    val cx = face.boundingBox.exactCenterX() / width   // 0..1 across the image
                    val cy = face.boundingBox.exactCenterY() / height  // 0..1 down the image
                    val awayFor = System.currentTimeMillis() - tracker.lastAround
                    if (awayFor > 5 * 60_000) sittingSince = System.currentTimeMillis()
                    val flip = if (settings.flipX) -1f else 1f
                    val x = (1f - 2f * cx) * flip
                    val y = 2f * cy - 1f
                    tracker.update(x * settings.gainX, y * settings.gainY, x, y)

                    // You just sat down after being away: look pleased, and say hello
                    val greetMs = settings.greetAfter * 60_000
                    if (awayFor > greetMs && !asleep && !gemini.active) arrived()
                }
            }
            .addOnCompleteListener { frame.close() }
    }

    // You sat down after being away: a double-take, a happy face, maybe the morning weather,
    // and a quick hello
    private fun arrived() {
        tracker.arrivedAt = System.currentTimeMillis()  // occasions show in the eyes again
        if (settings.focusing()) return  // focusing: just let them work
        gemini.showExpression("double_take", 1800)
        lifecycleScope.launch {
            delay(700)
            sounds.play(Sound.BLIP, 0.8f)
        }
        val today = LocalDate.now().toString()
        if (settings.weatherOn && settings.weatherTown.isNotBlank() &&
            settings.lastWeather != today && LocalTime.now().hour in 5..11
        ) {
            settings.lastWeather = today
            lifecycleScope.launch {
                delay(2000)
                weather.show()
            }
        }
        if (settings.greet && gemini.hasKey()) {
            if (settings.peopleOn && people.names().isNotEmpty()) {
                // Take a moment to see who it is, so the hello can use their name
                people.refresh()
                lifecycleScope.launch {
                    delay(1500)
                    if (!gemini.active) gemini.start(greet = greeting())
                }
            } else {
                gemini.start(greet = greeting())
            }
        }
    }

    // Its very first wake-up (or a replay from settings): eyes flutter open, it looks around
    // in wonder, then says hello and asks your name
    private fun hatch(replay: Boolean = false) {
        tracker.touched()
        if (asleep) wakeUp()
        gemini.stop()
        gemini.showExpression("hatch", 4500)
        lifecycleScope.launch {
            delay(1700)
            sounds.play(Sound.CHIRP)  // as the eyes open properly
            delay(3000)
            if (settings.firstMet.isBlank() || !replay && !settings.hatched) {
                settings.firstMet = LocalDate.now().toString()
            }
            settings.hatched = true
            when {
                !settings.setupDone -> showWelcome = true  // the first-time setup, a step at a time
                gemini.hasKey() -> gemini.start(greet = FIRST_MEETING)
                else -> showSettings = true
            }
        }
    }

    // Gentle nudges: a stretch after two hours at the desk, and bed when it's late
    private fun checkNudges() {
        if (!settings.nudges || asleep || gemini.active || !gemini.hasKey() || settings.focusing()) return
        val now = System.currentTimeMillis()
        if (now - tracker.lastSeen > 60_000) return  // only if you're actually here
        if (now - sittingSince > 2 * 3_600_000L && now - lastStretchNudge > 2 * 3_600_000L) {
            lastStretchNudge = now
            gemini.start(greet = STRETCH_NUDGE)
            return
        }
        val time = LocalTime.now()
        // After midnight still counts as the evening before
        val night = (if (time.hour < 4) LocalDate.now().minusDays(1) else LocalDate.now()).toString()
        if ((time.hour >= 23 || time.hour < 4) && settings.lastLateNudge != night) {
            settings.lastLateNudge = night
            gemini.start(greet = LATE_NUDGE)
        }
    }

    // Now and then (about every 15 minutes on average), it drifts off thinking about its hobby
    private fun maybeDaydream() {
        if (!settings.habits || !settings.innerLifeOn || settings.hobby.isBlank()) return
        if (asleep || gemini.active || dozing() || settings.focusing() || music.playing) return
        if (System.currentTimeMillis() < sounds.hushedUntil || Random.nextInt(30) != 0) return
        gemini.showExpression("daydream", 5000)
        sounds.play(Sound.THINK, 0.4f)
        val thought = "…thinking about ${settings.hobby}"
        gemini.status = thought
        lifecycleScope.launch {
            delay(6000)
            if (gemini.status == thought) gemini.status = TAP_HINT
        }
    }

    // Compares a tiny 24 × 18 sample of this camera frame with the last one. If a small part
    // of the picture changed (not the whole thing, which is just the light changing), it
    // records where, the right way up and in the same directions as the eyes use.
    private fun noticeMovement(frame: ImageProxy) {
        val plane = frame.planes[0]
        val buffer = plane.buffer
        val cols = 24
        val rows = 18
        val grid = IntArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = ((c + 0.5f) * frame.width / cols).toInt()
            val y = ((r + 0.5f) * frame.height / rows).toInt()
            var sum = 0
            for (dy in 0..1) for (dx in 0..1) {
                sum += buffer.get((y + dy * 2) * plane.rowStride + (x + dx * 2) * plane.pixelStride).toInt() and 0xFF
            }
            grid[r * cols + c] = sum / 4
        }
        val previous = lastGrid
        lastGrid = grid
        if (previous == null) return
        var count = 0
        var sumX = 0f
        var sumY = 0f
        for (i in grid.indices) {
            if (kotlin.math.abs(grid[i] - previous[i]) > 30) {  // a real change, not camera noise
                count++
                sumX += (i % cols + 0.5f) / cols
                sumY += (i / cols + 0.5f) / rows
            }
        }
        if (count < 6 || count > grid.size * 0.35f) return
        val u = sumX / count
        val v = sumY / count
        // The camera's picture is sideways; turn the spot the right way up
        val (cx, cy) = when (frame.imageInfo.rotationDegrees) {
            90 -> (1f - v) to u
            180 -> (1f - u) to (1f - v)
            270 -> v to (1f - u)
            else -> u to v
        }
        val flip = if (settings.flipX) -1f else 1f
        tracker.moved((1f - 2f * cx) * flip, 2f * cy - 1f)
    }

    // Follows the phone's internet connection (Wi-Fi or mobile data that actually works)
    private fun watchInternet() {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        fun works(caps: android.net.NetworkCapabilities?) =
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        online = works(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                online = works(caps)
            }

            override fun onLost(network: android.net.Network) {
                online = false
            }
        })
    }

    // Background for the conversation: who it knows and who's here, and today's calendar
    private fun aboutPeopleAndDay(): String = buildString {
        // Messages waiting (names only; the messages come with get_messages)
        val waiting = settings.messageList().groupBy { it.to }
        if (waiting.isNotEmpty()) {
            append(
                "Messages are waiting for: " + waiting.entries.joinToString { "${it.key} (${it.value.size})" } +
                    ". If one of them is talking to you (they say who they are, ask for messages, or it's " +
                    "them in front of you), call get_messages with their name and pass them on. "
            )
        }
        // Messages they left that have been delivered: tell them once
        val sender = people.current ?: settings.userName
        if (sender.isNotBlank()) {
            val done = settings.takeReceipts(sender)
            if (done.isNotEmpty()) {
                append(
                    "Early on, tell them briefly that " + done.joinToString("; ") {
                        "${it.to} got their message (\"${it.text}\") at ${hourMinute(it.at)}"
                    } + ". "
                )
            }
        }
        if (isNotEmpty()) append("\n\n")
        if (settings.peopleOn) {
            val names = people.names()
            if (names.isNotEmpty()) {
                append("People you know by face: ${names.joinToString()}. ")
                val here = people.current
                append(if (here != null) "The person in front of you now is $here. " else "")
                append(
                    "When you save a memory about a particular person, start the note with their " +
                        "name, and use what you remember about whoever is in front of you."
                )
            }
        }
        val events = calendar.describeToday()
        if (events.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Their calendar today:")
            events.forEach { append("\n- $it") }
        }
    }

    // Petting: a happy squint and a purr (a sleepy purr if it's asleep, without waking it)
    private fun pet() {
        if (!settings.petting || privacy) return
        if (asleep) {
            sounds.play(Sound.PURR, 0.5f)
            return
        }
        tracker.touched()
        gemini.showExpression("petted", 2600)
        sounds.play(Sound.PURR, 0.8f)
    }

    // A hand gesture it recognized
    private fun onGesture(gesture: String) {
        if (asleep) return
        tracker.touched()
        when (gesture) {
            "Open_Palm" -> if (player.playing && settings.musicGestures) {
                quietNow("Music paused")  // a hand held up while music plays: stop
            } else {  // a wave also ends a "shh"
                sounds.hushedUntil = 0L
                gemini.showExpression("wave_back", 2200)
                sounds.play(Sound.BLIP, 0.5f)
            }
            "Thumb_Up" -> {
                gemini.showExpression("happy")
                if (player.playing && settings.musicGestures) {
                    player.controlSoon("like")
                    gemini.status = "♥  Noted: you like this one"
                }
            }
            "Thumb_Down" -> gemini.showExpression("sad")
            "Victory" -> gemini.showExpression("wink")
            "ILoveYou" -> gemini.showExpression("love", 2600)
            "Pointing_Up" -> {  // "shh": stop talking, and keep quiet (no sounds, no antics) a while
                player.pauseNow()
                gemini.hush()
                sounds.hushedUntil = System.currentTimeMillis() + 10 * 60_000
                gemini.showExpression("shh", 1800)
            }
        }
    }

    // "Help me focus for 25 minutes": a draining ring, calm eyes, and a celebration at the end
    private fun startFocus(minutes: Double) {
        val ms = (minutes.coerceIn(1.0, 240.0) * 60_000).toLong()
        val until = System.currentTimeMillis() + ms
        stopFocus()
        settings.focusUntil = until.toString()
        settings.addReminder(until, FOCUS_LABEL)
        gemini.showExpression("content")
        // Nothing playing? Mention what they usually focus to, so Orbit can offer to put it on
        if (!player.playing && settings.musicMemory) {
            player.focusFavourite()?.let {
                gemini.tell("(They often listen to $it while focusing. Offer, in a few words, to put it on.)")
            }
        }
    }

    private fun stopFocus() {
        settings.focusUntil = ""
        settings.reminderList().filter { it.second == FOCUS_LABEL }.forEach { settings.removeReminder(it) }
    }

    // "This is Sarah": learn the face in front of it
    private fun meetPerson(name: String) {
        if (name.isBlank()) return
        if (!settings.peopleOn) settings.peopleOn = true
        people.prepare()
        people.meet(name)
    }

    // Calendar heads-up: 10 minutes before an event, a draining ring (and a reminder at the start)
    private fun checkCalendar() {
        val now = System.currentTimeMillis()
        for (event in calendar.today()) {
            if (event.allDay || event.id in eventsWarned) continue
            val until = event.start - now
            if (until in 1..10 * 60_000L) {
                eventsWarned.add(event.id)
                settings.addReminder(event.start, "${event.title} (starting now)")
            }
        }
    }

    // A camera frame as an upright picture, shrunk to at most 640 pixels across (quick to check)
    private fun uprightPicture(frame: ImageProxy): Bitmap? = try {
        val picture = frame.toBitmap()
        val turn = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
        val shrink = 640f / max(picture.width, picture.height)
        if (shrink < 1f) turn.postScale(shrink, shrink)
        Bitmap.createBitmap(picture, 0, 0, picture.width, picture.height, turn, true)
    } catch (e: Exception) {
        null
    }

    // The arrival greeting: a quick hello, plus (now and then) a mention of today's homework,
    // or (once a day, only if switched on) a glance at you and a kind word about something
    private fun greeting(): String {
        val today = LocalDate.now().toString()
        var text = GREETING.removeSuffix(")")
        // The first hello of the morning mentions the weather, in a sentence
        if (settings.weatherHello && settings.weatherTown.isNotBlank() && settings.lastWeatherHello != today &&
            LocalTime.now().hour in 5..11
        ) {
            settings.lastWeatherHello = today
            text += " After your hello, check get_weather once and mention today's weather in one short sentence."
        }
        // Who's there (if it knows their face)
        people.current?.let { name -> text += " It's $name." }
        // Their birthday: a happy birthday, once
        if (settings.occasion() == "birthday" && settings.lastBirthdayWish != today) {
            settings.lastBirthdayWish = today
            return "$text$BIRTHDAY)"
        }
        // The first hello of the morning: a quick rundown of the day from the calendar
        val events = calendar.describeToday()
        if (events.isNotEmpty() && settings.lastCalendarMorning != today && LocalTime.now().hour < 12) {
            settings.lastCalendarMorning = today
            return "$text After your hello, in one short sentence, tell them what's on today " +
                "(how many things, and the first one). Today: ${events.joinToString("; ")})"
        }
        // A friendship milestone (a week, a month, 100 days, a year…) gets mentioned once
        val days = settings.daysKnown()
        if (days != null && days in MILESTONE_DAYS && settings.lastMilestone != today) {
            settings.lastMilestone = today
            return "$text${milestone(days)})"
        }
        val newest = settings.journalNotes().lastOrNull()
        if (settings.innerLifeOn && newest != null && newest != settings.lastShared) {
            settings.lastShared = newest
            text += SHARE_HOMEWORK
        } else if (settings.noticeThings && settings.vision && settings.lastNoticed != today) {
            settings.lastNoticed = today
            text += NOTICE
        }
        return "$text)"
    }

    // Turns a camera frame into a small JPEG photo, the right way up
    private fun takePhoto(frame: ImageProxy): ByteArray? = try {
        val picture = frame.toBitmap()
        val turn = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
        val shrink = photoSize / max(picture.width, picture.height)
        if (shrink < 1f) turn.postScale(shrink, shrink)
        val upright = Bitmap.createBitmap(picture, 0, 0, picture.width, picture.height, turn, true)
        ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    } catch (e: Exception) {
        Log.e("Orbit", "Couldn't take a photo", e)
        null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        peopleExecutor.shutdown()
        neck.link.stop()
        player.stop()
    }

    // Spotify sends you back here after signing in (orbit://spotify?code=…)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val link = intent?.data ?: return
        if (link.scheme == "orbit" && link.host == "spotify") {
            val code = link.getQueryParameter("code")
            if (code != null) player.spotify.finishSignIn(code) else player.spotify.status = "Spotify sign-in was cancelled"
            showSettings = true
        }
    }

    // ---- Music ----

    // Stops everything at once: music paused, Orbit stops talking (tap, ✋, 🤫, or covering its eyes)
    private fun quietNow(message: String) {
        player.pauseNow()
        gemini.hush()
        gemini.showExpression("shh", 1500)
        gemini.status = message
    }

    // A swipe across the eyes: skips songs while music plays (right to left = next), otherwise a pet
    private fun swiped(direction: Int) {
        if (player.playing && settings.musicGestures && !asleep) {
            tracker.touched()
            player.controlSoon(if (direction < 0) "next" else "previous")
            gemini.showExpression(if (direction < 0) "look_away" else "curious", 900)
            gemini.status = if (direction < 0) "♪  Next song" else "♪  Previous song"
        } else {
            pet()
        }
    }

    // A new song started
    private fun songChanged(title: String, artist: String) {
        if (settings.musicTitle && !gemini.active) {
            gemini.status = if (artist.isBlank()) "♪  $title" else "♪  $title  ·  $artist"
        }
        if (!settings.musicReact || asleep || artist == "radio" || artist == "Orbit sounds") return
        val now = System.currentTimeMillis()
        when {
            // Mid-conversation: very occasionally, a word about it
            gemini.active && now - lastSongReaction > 30 * 60_000 && Random.nextInt(3) == 0 -> {
                lastSongReaction = now
                gemini.tell("(Now playing: \"$title\" by $artist. If you know it, react in a few words. If not, say nothing.)")
            }
            // Otherwise: a happy look when one of their favourites comes on (no words, no Gemini)
            !gemini.active && now - lastSongReaction > 60 * 60_000 && player.isFavourite(artist) -> {
                lastSongReaction = now
                gemini.showExpression("love", 2600)
            }
        }
    }

    // Gemini asked for music
    private fun musicTool(name: String, args: JSONObject): String {
        val result = when (name) {
            "play_music" -> player.play(args.optString("query"))
            "play_radio" -> player.playRadio(args.optString("what"))
            "play_sounds" -> player.playSounds(args.optString("kind"))
            "music" -> player.control(args.optString("action"))
            "now_playing" -> player.describe()
            "music_sleep_timer" -> player.sleepTimer(args.optDouble("minutes", 0.0))
            else -> "Unknown"
        }
        // Music started: close the conversation once Orbit's had its word, so the music comes
        // back up to full (it's kept low while you talk, and the music itself would keep the
        // conversation open). "Orby" opens a new one any time.
        val starting = name in listOf("play_music", "play_radio", "play_sounds") ||
            (name == "music" && args.optString("action") == "resume")
        val failed = listOf("Couldn't", "None", "Orbit is locked", "Unknown", "No ").any { result.startsWith(it) }
        if (starting && !failed) {
            runOnUiThread { endAfterReply() }
            return "$result. The conversation closes after your reply so they can listen."
        }
        return result
    }

    // Waits for Orbit's short reply to start and finish (or for it to say nothing), then ends the conversation
    private fun endAfterReply() {
        lifecycleScope.launch {
            delay(2500)  // time for the reply to begin
            val giveUpAt = System.currentTimeMillis() + 10_000
            while (gemini.speaking() && System.currentTimeMillis() < giveUpAt) delay(200)
            delay(400)
            if (gemini.active) gemini.stop()
        }
    }

    // What's playing and what they like, for the conversation
    private fun aboutMusic(): String {
        val now = player.describe()
        val memory = player.memorySummary()
        val apps = player.installedApps()
        val connected = when {
            apps.isEmpty() -> "No music app is installed, so for anything use play_radio or play_sounds."
            !player.hasAccess() -> "Their music apps (${apps.joinToString()}) aren't connected yet; play_music " +
                "can still open them. They can connect them in settings (Music)."
            else -> "Music apps connected: ${apps.joinToString()}."
        }
        return "\n\nMusic: $now. $connected You can play and control music with your music tools; when " +
            "they say stop or quiet, pause it at once. When you start or change music, say a couple of " +
            "words at most (\"Here you go\") or nothing; don't wish them well or add their name." + (if (memory.isNotEmpty()) " $memory" else "")
    }

    // A hand over the camera (it suddenly goes dark): the quick "quiet!"
    private fun checkCovered(frame: ImageProxy) {
        val plane = frame.planes[0]
        val buffer = plane.buffer
        var sum = 0
        for (r in 0 until 6) for (c in 0 until 8) {
            val x = ((c + 0.5f) * frame.width / 8).toInt()
            val y = ((r + 0.5f) * frame.height / 6).toInt()
            sum += buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
        }
        val brightness = sum / 48f
        if (brightness > 30f) {
            usualBrightness += (brightness - usualBrightness) * 0.1f
            darkFrames = 0
            return
        }
        // Suddenly very dark in a lit room (not the lights going off slowly at night)
        if (brightness < 12f && usualBrightness > 45f) darkFrames++ else darkFrames = 0
        val now = System.currentTimeMillis()
        if (darkFrames == 2 && now - lastCovered > 3000) {
            lastCovered = now
            runOnUiThread {
                if (player.playing || gemini.active) quietNow(if (player.playing) "Shh… music paused" else "Shh…")
            }
        }
    }
}
