package com.example.companion2

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// What the companion is doing right now. The eyes act it out.
enum class Mood {
    IDLE,       // no conversation open
    LISTENING,  // conversation open, waiting for you to talk
    HEARING,    // you're talking
    THINKING,   // you stopped talking, waiting for Gemini's reply
    SPEAKING,   // Gemini is talking
    DOZING,     // nobody around for a while: dim, half-closed, slowly drifting (saves the screen)
    ASLEEP      // sleep or privacy sleep
}

// Holds where the camera last saw a face (shared between camera and face drawing),
// and when someone was last around (seen or touching the screen), for dozing
class FaceTracker {
    @Volatile var x = 0f          // -1 = left, 1 = right
    @Volatile var y = 0f          // -1 = up, 1 = down
    // Where the face really is in the picture (-1..1, same directions), before the eyes'
    // "follow" settings exaggerate it. The neck uses these.
    @Volatile var rawX = 0f
    @Volatile var rawY = 0f
    @Volatile var lastSeen = 0L
    @Volatile var lastAround = System.currentTimeMillis()
    // Where something last moved in the picture (when nobody's face is in view), for a glance
    @Volatile var motionX = 0f
    @Volatile var motionY = 0f
    @Volatile var motionAt = 0L
    @Volatile var smile = 0f      // how much the person is smiling (0..1)
    @Volatile var size = 0f       // the face's width as a share of the picture's (bigger = closer)
    @Volatile var detections = 0  // goes up by one with each new sighting (so the neck uses each once)
    @Volatile var arrivedAt = 0L  // when someone last sat down after being away

    fun moved(x: Float, y: Float) {
        // At most one glance every 4 seconds, so flickering light or camera noise can't make
        // the eyes dart about
        if (System.currentTimeMillis() - motionAt < 4000) return
        motionX = x.coerceIn(-1f, 1f)
        motionY = y.coerceIn(-1f, 1f)
        motionAt = System.currentTimeMillis()
    }

    fun update(newX: Float, newY: Float, newRawX: Float, newRawY: Float) {
        x = newX.coerceIn(-1f, 1f)
        y = newY.coerceIn(-1f, 1f)
        rawX = newRawX.coerceIn(-1f, 1f)
        rawY = newRawY.coerceIn(-1f, 1f)
        lastSeen = System.currentTimeMillis()
        lastAround = lastSeen
        detections++
    }

    // Someone touched the screen
    fun touched() {
        lastAround = System.currentTimeMillis()
    }

    fun seesFace() = System.currentTimeMillis() - lastSeen < 1000
}

@Composable
fun Face(
    tracker: FaceTracker,
    mic: Microphone,
    gemini: GeminiLive,
    settings: Settings,
    neck: Neck,
    sounds: Sounds,
    weather: Weather,
    music: MusicSense,
    player: MusicControl,
    isAsleep: () -> Boolean,
    isPrivate: () -> Boolean,
    isDozing: () -> Boolean,
    isNight: () -> Boolean,
    isWarm: () -> Boolean,
    isOffline: () -> Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipe: (Int) -> Unit  // a stroke across the eyes: -1 = right to left, 1 = left to right
) {
    // Today's occasion (birthday, holiday), shown for a while at start-up and when you arrive
    val startedAt = remember { System.currentTimeMillis() }
    var occasionStrength by remember { mutableFloatStateOf(0f) }
    // A colored border for some states (quiet = red, timer = green…), faded in and out
    var stateColor by remember { mutableStateOf(Color.White) }
    var stateStrength by remember { mutableFloatStateOf(0f) }
    var offline by remember { mutableStateOf(false) }
    // 1 = eyes fully open, 0 = closed (blinking)
    val openness = remember { Animatable(1f) }   // the left eye
    val opennessR = remember { Animatable(1f) }  // the right eye: blinks a hair after (or before)
    // Where the eyes are looking: -1..1 on each axis
    val lookX = remember { Animatable(0f) }
    val lookY = remember { Animatable(0f) }
    // How the mood shapes the eyes, smoothed so changes look natural
    val grow = remember { Animatable(1f) }     // overall size
    val squint = remember { Animatable(1f) }   // eye height: 1 = normal, near 0 = shut
    val glow = remember { Animatable(1f) }     // brightness
    var mood by remember { mutableStateOf(Mood.IDLE) }
    // Small text at the bottom showing what's going on. It fades away after a few seconds
    // so nothing stays still on screen for hours (which could burn into the screen).
    var status by remember { mutableStateOf("") }
    var statusAlpha by remember { mutableFloatStateOf(1f) }
    var statusChanged by remember { mutableLongStateOf(0L) }
    // Captions: what Orbit is saying, shown while it talks and briefly after
    var caption by remember { mutableStateOf("") }
    var captionAlpha by remember { mutableFloatStateOf(0f) }
    // Sleeping: how visible the floating "z z z" are (fades in as it falls asleep)
    var sleepZ by remember { mutableFloatStateOf(0f) }
    // Countdown ring around the right eye: time left on the next timer/reminder (-1 = none)
    var timerLeft by remember { mutableFloatStateOf(-1f) }
    // A little glowing dot some idle moves use (a firefly): x, y (-1..1), brightness
    var spark by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    // Today's weather in the eyes (0 = not showing)
    var weatherStrength by remember { mutableFloatStateOf(0f) }
    // The big weather view (icon in one eye, temperature in the other): 0 = normal, 1 = zoomed in
    var zoom by remember { mutableFloatStateOf(0f) }
    var zoomGoal by remember { mutableFloatStateOf(0f) }
    // Neck angles in the top corner (for testing; switched on from the Neck tab)
    // Seconds since the face appeared; drives the always-moving holographic shimmer
    var time by remember { mutableFloatStateOf(0f) }
    // Where each eye was in recent frames, for the holographic trail
    val trails = remember { mapOf(-1 to ArrayDeque<EyeShape>(), 1 to ArrayDeque<EyeShape>()) }
    // Expressions Gemini asks for, layered on top of the mood
    val headTilt = remember { Animatable(0f) }                 // degrees, curious tilt
    val smile = remember { Animatable(0f) }                    // happy: rings open into arches, 0..1
    var wink by remember { mutableFloatStateOf(0f) }           // right eye closing: 0..1
    var winkLeft by remember { mutableFloatStateOf(0f) }       // left eye closing: 0..1
    var glitch by remember { mutableFloatStateOf(0f) }         // a silly hologram glitch: 0..1
    // Getting sleepy before dozing off (0 = wide awake, 1 = about to doze)
    var drowsy by remember { mutableFloatStateOf(0f) }
    // Where the mood loop wants each of those to be; the frame loop below eases toward them
    var goalGrow by remember { mutableFloatStateOf(1f) }
    var goalSquint by remember { mutableFloatStateOf(1f) }
    var goalGlow by remember { mutableFloatStateOf(1f) }
    var goalSmile by remember { mutableFloatStateOf(0f) }
    // Asleep: the eyes become closed, gently upward-arched slits (0 = open, 1 = shut)
    val shut = remember { Animatable(0f) }
    var goalShut by remember { mutableFloatStateOf(0f) }
    var goalTilt by remember { mutableFloatStateOf(0f) }
    var goalWink by remember { mutableFloatStateOf(0f) }
    var goalWinkLeft by remember { mutableFloatStateOf(0f) }
    // A happy little bounce just as a smile arrives (-1..1, settles to 0)
    var bounce by remember { mutableFloatStateOf(0f) }
    // While it talks: a quick lift on each syllable (1 on the syllable, fading to 0)
    var syllable by remember { mutableFloatStateOf(0f) }
    // Waiting for Gemini (connecting or thinking): the little spinner in the top corner, 0..1
    var thinking by remember { mutableFloatStateOf(0f) }
    // Music playing: the album art's colour in the eyes (strength 0..1, eased)
    var musicColor by remember { mutableStateOf(Color.White) }
    var musicTint by remember { mutableFloatStateOf(0f) }
    var musicTintGoal by remember { mutableFloatStateOf(0f) }
    var thinkingGoal by remember { mutableFloatStateOf(0f) }
    // Playing music: little dancing bars in the top corner (0..1, eased)
    var musicIcon by remember { mutableFloatStateOf(0f) }
    var musicIconGoal by remember { mutableFloatStateOf(0f) }
    var sideBoost by remember { mutableFloatStateOf(0f) }      // left eye bigger than right
    var expressionLook by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var expressionShake by remember { mutableStateOf(0f to 0f) }  // nod / head-shake wobble

    LaunchedEffect(Unit) {
        // Share the intro's clock, so the shimmer carries on smoothly from the intro
        if (animationStart == 0L) animationStart = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - animationStart) / 1_000_000_000f }
    }

    // Blink every 2-6 seconds (not while asleep)
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (drowsy > 0f) Random.nextLong(2500, 4500) else Random.nextLong(2000, 6000))
            if (mood == Mood.ASLEEP || mood == Mood.DOZING || zoomGoal > 0f) continue
            if (drowsy > 0f) {
                // Sleepy: slow, heavy blinks, both eyes together, lingering shut
                launch {
                    opennessR.animateTo(0.04f, tween(420))
                    delay(250L + (drowsy * 400).toLong())
                    opennessR.animateTo(1f, tween(600))
                }
                openness.animateTo(0.04f, tween(420))
                delay(250L + (drowsy * 400).toLong())
                openness.animateTo(1f, tween(600))
                continue
            }
            // Real eyes never blink exactly together: one leads by a few hundredths of a second
            val (lead, follow) = if (Random.nextBoolean()) openness to opennessR else opennessR to openness
            launch {
                delay(Random.nextLong(15, 45))
                follow.animateTo(0f, tween(90))
                follow.animateTo(1f, tween(140))
            }
            lead.animateTo(0f, tween(90))
            lead.animateTo(1f, tween(140))
        }
    }

    // Each moment, work out the mood and ease the eyes toward how that mood looks
    LaunchedEffect(Unit) {
        var lastMood = Mood.IDLE
        var lastThinkSound = 0L
        var lastStartle = 0L
        var quietLevel = 0f   // how loud it usually is, to notice a sudden bang
        var smilingSince = 0L // when you started smiling (a steady smile, not a talking mouth)
        var lastVoiceAt = 0L  // when it last heard you talking
        var waitingSince = 0L // when it started waiting for Gemini
        var yawned = false    // yawned during this wind-down already
        var lastSpokeAt = 0L  // when it last talked (for how long to show the weather)
        while (true) {
            val conversation = gemini.mood()
            mood = when {
                isAsleep() -> Mood.ASLEEP
                conversation == Mood.IDLE && isDozing() -> Mood.DOZING
                else -> conversation
            }
            neck.mood = mood  // the head acts out the same mood
            val clock = System.currentTimeMillis()

            // Little sounds as the mood changes (and a yawn when it starts to doze)
            if (mood != lastMood) {
                val wasTalking = lastMood == Mood.LISTENING || lastMood == Mood.HEARING ||
                    lastMood == Mood.THINKING || lastMood == Mood.SPEAKING
                when {
                    lastMood == Mood.ASLEEP -> sounds.play(Sound.CHIRP)
                    mood == Mood.ASLEEP -> sounds.play(Sound.SIGH)
                    mood == Mood.DOZING -> {}  // it already yawned while getting sleepy
                    lastMood == Mood.DOZING && mood == Mood.IDLE ->
                        if (clock - lastStartle > 2000) sounds.play(Sound.CHIRP, 0.6f)
                    lastMood == Mood.IDLE && mood == Mood.LISTENING ->
                        if (clock - sounds.repliedAt > 2000) sounds.play(Sound.LISTEN)
                    wasTalking && mood == Mood.IDLE -> sounds.play(Sound.END)
                    mood == Mood.THINKING -> if (clock - lastThinkSound > 4000) {
                        lastThinkSound = clock
                        sounds.play(Sound.THINK, 0.5f)
                    }
                }
                lastMood = mood
            }

            // A sudden loud noise (a clap, a door) while it's quiet: it jumps
            if (settings.habits && !gemini.active && (mood == Mood.IDLE || mood == Mood.DOZING)) {
                val level = mic.level
                if (level > 0.8f && quietLevel < 0.12f && clock - lastStartle > 20_000 &&
                    clock - sounds.lastPlayedAt > 1500  // not its own chime
                ) {
                    lastStartle = clock
                    gemini.showExpression("startled", 900)
                    sounds.play(Sound.STARTLE, 0.5f)
                    tracker.touched()  // and it's wide awake now
                }
                quietLevel += (level - quietLevel) * 0.05f
            }

            timerLeft = settings.timeLeftOnSoonest(clock) ?: -1f
            // Waiting on Gemini for more than a moment: show the little spinner (not for a blink)
            // Connecting after a tap or its name isn't "thinking": it's already listening (what you say
            // is kept and sent once connected), so only a slow connection shows the spinner
            val waiting = mood == Mood.THINKING || gemini.connectingFor() > 2500
            if (!waiting) waitingSince = 0L else if (waitingSince == 0L) waitingSince = clock
            thinkingGoal = if (waitingSince != 0L && clock - waitingSince > 300) 1f else 0f
            weatherStrength = weather.strength(clock)
            // The big weather view: on while Orbit talks about it (up to 8 s for it to start
            // talking), off 1.8 s after it stops (or after 45 s whatever happens)
            if (mood == Mood.SPEAKING) lastSpokeAt = clock
            val viewAt = weather.viewAt
            val showWeather = viewAt != 0L && mood != Mood.ASLEEP && clock - viewAt < 45_000 &&
                (if (lastSpokeAt > viewAt) clock - lastSpokeAt < 1800 else clock - viewAt < 8000)
            if (viewAt != 0L && !showWeather) weather.endView()
            zoomGoal = if (showWeather) 1f else 0f
            // Occasions: fade in, hold 15 s, fade out, after start-up or arriving
            val sinceOccasion = (clock - maxOf(startedAt, tracker.arrivedAt)) / 1000f
            occasionStrength = if (settings.occasion() == null || mood == Mood.ASLEEP) 0f
            else minOf(sinceOccasion / 1f, (16f - sinceOccasion) / 1.5f, 1f).coerceIn(0f, 1f)
            offline = isOffline()
            // State colors on the eye border, only where they mean something:
            // red = not listening (privacy mode, or "shh"), violet = focus session,
            // green = a timer running, amber = a reminder or calendar event coming up,
            // blue = a coding agent on your computer is waiting for you
            val soonest = settings.soonestReminder(clock)
            val countdownColor = when {
                soonest == null -> null
                soonest.second == FOCUS_LABEL -> STATE_VIOLET
                soonest.second.contains("timer", ignoreCase = true) -> STATE_GREEN
                else -> STATE_AMBER
            }
            val wanted = when {
                mood == Mood.ASLEEP && isPrivate() -> STATE_RED
                clock < sounds.hushedUntil -> STATE_RED
                AgentNews.needsYou() && mood != Mood.ASLEEP -> STATE_BLUE
                else -> countdownColor
            }
            if (wanted != null) stateColor = wanted
            stateStrength += ((if (wanted != null) 1f else 0f) - stateStrength) * 0.08f
            val newStatus = when (mood) {
                Mood.ASLEEP -> if (isPrivate()) "••  wake   ·   camera and microphone off" else "••  wake"
                Mood.DOZING -> "•  wake"
                Mood.IDLE -> when {
                    isOffline() -> "Offline · no internet"
                    isWarm() -> "too warm · resting"
                    else -> gemini.status
                }
                else -> gemini.status
            }
            val now = System.currentTimeMillis()
            if (newStatus != status) {
                status = newStatus
                statusChanged = now
            }
            // Fully visible for 6 seconds after a change, then fades out over 1 second
            statusAlpha = if (settings.showHints) {
                (1f - (now - statusChanged - 6000) / 1000f).coerceIn(0f, 1f)
            } else 0f
            // Captions stay up while it talks, then fade 3 seconds after the last words
            caption = gemini.caption
            captionAlpha = if (settings.captions && caption.isNotEmpty()) {
                (1f - (now - gemini.captionAt - 3000) / 1000f).coerceIn(0f, 1f)
            } else 0f

            val voice = gemini.voiceLevel()
            var (targetGrow, targetSquint, targetGlow) = when (mood) {
                Mood.IDLE -> Triple(1f, 1f, 1f)
                Mood.LISTENING -> Triple(1.06f, 1f, 1f)                       // attentive
                Mood.HEARING -> Triple(1.06f + 0.12f * mic.level, 1f, 1f)     // swell as you talk
                Mood.THINKING -> Triple(0.95f, 0.75f, 0.9f)                  // narrowed, pondering
                Mood.SPEAKING -> Triple(1f + 0.05f * voice, 1f - 0.12f * voice, 1f)  // syllables add the rest
                Mood.DOZING -> Triple(0.9f, 0.55f, 0.3f)                     // drowsy and dim: closed arcs
                Mood.ASLEEP -> {
                    // Shut and dim, breathing slowly: a little bigger and brighter on each
                    // breath in (one breath every 5 seconds)
                    val breath = sin((now % 5000L) / 5000f * 2f * PI.toFloat())
                    Triple(1f + 0.04f * breath, 0.5f + 0.03f * breath, 0.32f + 0.1f * breath)
                }
            }
            if (mood == Mood.ASLEEP && isPrivate()) targetGlow *= 0.7f  // privacy: a bit darker still
            // Just woken by its name and now connected: ease from the idle look into listening
            // over half a second, rather than switching (see GeminiLive.listeningGlide)
            val listenGlide = gemini.listeningGlide(clock)
            val gliding = listenGlide < 1f && (mood == Mood.LISTENING || mood == Mood.HEARING)
            if (gliding) {
                targetGrow = 1f + (targetGrow - 1f) * listenGlide
                targetSquint = 1f + (targetSquint - 1f) * listenGlide
                targetGlow = 1f + (targetGlow - 1f) * listenGlide
            }
            sleepZ += ((if (mood == Mood.ASLEEP) 1f else 0f) - sleepZ) * 0.04f

            // Expression from Gemini or its own habits (not while asleep; only a yawn while dozing)
            val latest = gemini.expression()
            val shown = when {
                mood == Mood.ASLEEP -> null
                mood == Mood.DOZING && latest?.first != "yawn" -> null
                else -> latest
            }
            // Time of day: a little brighter in the morning, a little droopier late at night
            val hour = java.time.LocalTime.now().hour
            if (mood == Mood.IDLE || mood == Mood.LISTENING) {
                if (hour in 6..9) targetGlow *= 1.05f
                if (hour >= 22 || hour < 5) targetSquint *= 0.9f
            }
            // You're talking nearby (not to it): it perks up and listens in
            val listeningIn = settings.habits && mood == Mood.IDLE && tracker.seesFace() &&
                mic.level > settings.voiceThreshold
            if (listeningIn) targetGrow *= 1.05f
            // Music playing: bob along to the beat (the head too)
            val bobbing = settings.musicOn && settings.habits && mood == Mood.IDLE && shown == null &&
                !settings.focusing()
            val beat = if (bobbing) music.pulse(clock) else 0f
            val fastSong = music.playing && music.bpm > 125f
            val slowSong = bobbing && music.playing && music.bpm < 85f
            targetGrow *= 1f + (if (fastSong) 0.09f else 0.06f) * beat
            if (slowSong) targetSquint *= 0.82f  // a slow song: eyes half-close, content
            neck.musicBob = beat
            neck.musicSway = if (bobbing && settings.musicDance) music.sway(clock) else 0f
            // Music it's playing: the album's colour in the eyes, and dimming as a sleep timer ends
            val album = if (settings.musicColors && player.playing && mood != Mood.ASLEEP) player.color else null
            if (album != null) musicColor = Color(album)
            musicTintGoal = if (album != null) 1f else 0f
            musicIconGoal = if (player.playing && mood != Mood.ASLEEP) 1f else 0f
            targetGlow *= player.sleepDim(clock)
            // Focusing: calm and a little dimmer
            if (settings.focusing() && mood == Mood.IDLE) targetGlow *= 0.85f

            // The expression or idle move shapes the pose (see Expressions.kt)
            val pose = Pose(targetGrow, targetSquint, targetGlow)
            if (shown != null) MOVES[shown.first]?.invoke(shown.second, pose)
            targetGrow = pose.grow
            targetSquint = pose.squint
            targetGlow = pose.glow
            val targetTilt = when {
                shown == null && listeningIn -> 5f
                // Was listening in (head tilted) when it woke: untilt along the same glide
                shown == null && gliding && settings.habits && tracker.seesFace() -> 5f * (1f - listenGlide)
                else -> pose.tilt
            }
            // You're smiling at it: it smiles back. A talking mouth looks like a smile to the
            // face detector, so only a smile held for a second while you're quiet counts.
            if (mic.level > settings.voiceThreshold || mood == Mood.HEARING) lastVoiceAt = clock
            val smilingNow = tracker.seesFace() && tracker.smile > 0.7f && clock - lastVoiceAt > 1500
            if (!smilingNow) smilingSince = 0L else if (smilingSince == 0L) smilingSince = clock
            val smilingBack = settings.smileBack && shown == null && mood != Mood.ASLEEP &&
                mood != Mood.DOZING && smilingSince != 0L && clock - smilingSince > 1000
            val targetSmile = when {
                smilingBack -> maxOf(pose.smile, 0.75f)
                slowSong && shown == null -> 0.3f  // swaying contentedly
                else -> pose.smile
            }
            goalWink = pose.wink
            goalWinkLeft = pose.winkLeft
            glitch = pose.glitch
            sideBoost = pose.sideBoost
            expressionLook = pose.look
            // With a neck, the head does the nodding, so the eyes only join in a little
            val shakeScale = if (neck.link.connected) 0.25f else 1f
            expressionShake = pose.shakeX * shakeScale to pose.shakeY * shakeScale
            spark = pose.spark
            goalTilt = targetTilt
            goalSmile = targetSmile
            // Asleep or dozing: closed, gently arched eyes (not squashed ovals)
            goalShut = if (mood == Mood.ASLEEP || mood == Mood.DOZING) 1f else 0f

            // Night schedule set to dim: keep the eyes low
            // Getting sleepy: in the last 40 seconds before dozing, heavy lids, dimmer, slow
            // blinks (see the blink loop) and a yawn; then the eyes slowly close
            val idleFor = clock - tracker.lastAround
            val dozeAt = (settings.dozeAfter * 60_000).toLong()
            drowsy = if (mood == Mood.IDLE && !gemini.active && !tracker.seesFace()) {
                ((idleFor - (dozeAt - 40_000)) / 40_000f).coerceIn(0f, 1f)
            } else 0f
            if (drowsy > 0f) {
                targetSquint *= 1f - 0.35f * drowsy
                targetGlow *= 1f - 0.3f * drowsy
                if (drowsy > 0.6f && !yawned && settings.habits) {
                    yawned = true
                    sounds.play(Sound.YAWN, 0.7f)
                    gemini.showExpression("yawn", 2400)
                }
            } else if (mood != Mood.DOZING) {
                yawned = false
            }

            if (isNight() && mood != Mood.ASLEEP) targetGlow *= 0.3f
            // Phone too warm: dimmer eyes while it rests (a bright screen adds heat)
            if (isWarm() && mood != Mood.ASLEEP) targetGlow *= 0.5f

            goalGrow = targetGrow
            goalSquint = targetSquint
            goalGlow = targetGlow
            delay(33)
        }
    }

    // Every screen frame (60 or 120 a second), ease the eyes toward where the mood loop wants
    // them. Done per frame rather than in the mood loop so motion is smooth, not steppy.
    // The smile and wink are quick on purpose: a snappy change reads as a new expression,
    // a slow one as the ring draining.
    LaunchedEffect(Unit) {
        var last = 0L
        var smileArriving = false
        var bounceAt = 0L
        var voiceAverage = 0f
        var lastSyllable = 0L
        var zoomProgress = 0f  // linear 0..1; the eyes use a smoothed copy (slow start, slow finish)
        while (true) {
            val now = withFrameNanos { it }
            val ms = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 100f)
            last = now
            grow.snapTo(grow.value + (goalGrow - grow.value) * easing(0.3f, ms))
            squint.snapTo(squint.value + (goalSquint - squint.value) * easing(0.3f, ms))
            glow.snapTo(glow.value + (goalGlow - glow.value) * easing(0.15f, ms))
            headTilt.snapTo(headTilt.value + (goalTilt - headTilt.value) * easing(0.25f, ms))
            smile.snapTo(smile.value + (goalSmile - smile.value) * easing(0.55f, ms))
            // Dozing off: the eyes close slowly (a few seconds); falling asleep on purpose is quicker
            val closeRate = if (mood == Mood.DOZING && goalShut > shut.value) 0.025f else 0.35f
            shut.snapTo(shut.value + (goalShut - shut.value) * easing(closeRate, ms))
            wink += (goalWink - wink) * easing(0.65f, ms)
            winkLeft += (goalWinkLeft - winkLeft) * easing(0.65f, ms)
            // When a smile has nearly arrived: a quick, fading bounce (a happy wiggle)
            if (goalSmile < 0.3f) smileArriving = false
            else if (goalSmile > 0.5f && smile.value < 0.3f) smileArriving = true
            if (smileArriving && smile.value > goalSmile - 0.08f) {
                smileArriving = false
                bounceAt = now
            }
            val t = (now - bounceAt) / 1_000_000_000f
            bounce = if (bounceAt != 0L && t < 0.5f) exp(-t * 7f) * sin(t * 2f * PI.toFloat() * 5.5f) else 0f
            // Talking: each time its voice jumps up (a new syllable), the eyes give a small lift
            val voiceNow = if (mood == Mood.SPEAKING) gemini.voiceLevel() else 0f
            voiceAverage += (voiceNow - voiceAverage) * easing(0.12f, ms)
            if (voiceNow - voiceAverage > 0.1f && now - lastSyllable > 120_000_000L) {
                syllable = 1f
                lastSyllable = now
            }
            syllable *= exp(-ms / 110f)
            thinking += (thinkingGoal - thinking) * easing(0.2f, ms)
            musicTint += (musicTintGoal - musicTint) * easing(0.05f, ms)
            musicIcon += (musicIconGoal - musicIcon) * easing(0.12f, ms)
            zoomProgress = (zoomProgress + (if (zoomGoal > zoomProgress) 1f else -1f) * ms / 900f)
                .coerceIn(minOf(zoomGoal, zoomProgress), maxOf(zoomGoal, zoomProgress))
            zoom = smoothStep(zoomProgress)
        }
    }

    // Where to look: up and away while thinking, down while asleep, at your face if it
    // sees one, straight ahead during a conversation, otherwise glance around
    LaunchedEffect(Unit) {
        var nextGlance = 0L
        var saccade = 0f to 0f  // tiny eye flicks while watching you, like real eyes
        var nextSaccade = 0L
        var lastFrame = 0L
        var speedX = 0f  // how fast the eyes are moving, for the springy follow below
        var speedY = 0f
        var steadyX = 0f  // where you are, with tiny wobbles ignored
        var steadyY = 0f
        var sleepX = 0f  // asleep: where the closed eyes drift, slowly
        var sleepY = 0.3f
        var sleepStep = 0L
        var thinkingSince = 0L  // when it started waiting on Gemini's reply
        while (true) {
            val clock = System.currentTimeMillis()
            // Only a slow reply gets the "pondering" look: Gemini usually answers within a second,
            // and flicking up and straight back on every turn looked like a twitch
            if (mood == Mood.THINKING) { if (thinkingSince == 0L) thinkingSince = clock } else thinkingSince = 0L
            val pondering = thinkingSince != 0L && clock - thinkingSince > PONDER_AFTER_MS
            if (clock >= nextSaccade) {
                saccade = if (settings.habits) {
                    (Random.nextFloat() - 0.5f) * 0.06f to (Random.nextFloat() - 0.5f) * 0.05f
                } else 0f to 0f
                nextSaccade = clock + Random.nextLong(1500, 4500)
            }
            val override = expressionLook
            val target: Pair<Float, Float>? = when {
                override != null -> override
                pondering -> 0.35f to -0.45f  // a soft glance up and to the side
                // Asleep: low, drifting very slowly so the screen doesn't burn in overnight, and
                // turning sleepily toward you if you're there (it checks once a second)
                mood == Mood.ASLEEP -> {
                    val t = (clock % 10_000_000L) / 1000f  // seconds
                    val driftX = sin(t * 0.03f) * 0.5f
                    val driftY = 0.3f + sin(t * 0.021f) * 0.2f
                    val (wantX, wantY) = if (tracker.seesFace() && !isPrivate()) {
                        (tracker.x * 0.45f + driftX * 0.2f) to (0.15f + tracker.y * 0.2f)
                    } else {
                        driftX to driftY
                    }
                    val step = (clock - sleepStep).coerceIn(1L, 100L) / 1000f
                    sleepStep = clock
                    val k = 1f - exp(-step / 3f)  // takes a few seconds, like a sleepy turn
                    sleepX += (wantX - sleepX) * k
                    sleepY += (wantY - sleepY) * k
                    sleepX to sleepY
                }
                // Dozing: drift slowly around so no spot of the screen stays lit for long
                mood == Mood.DOZING -> {
                    val t = (System.currentTimeMillis() % 10_000_000L) / 1000f  // seconds
                    sin(t * 0.05f) * 0.8f to sin(t * 0.037f) * 0.6f
                }
                tracker.seesFace() -> {
                    // Follow you, but let small wobbles in the face detection pass: only move
                    // once you've moved a little (so the eyes rest calmly while you sit still)
                    val dx = tracker.x - steadyX
                    val dy = tracker.y - steadyY
                    if (abs(dx) > 0.04f) steadyX += dx - 0.04f * sign(dx)
                    if (abs(dy) > 0.04f) steadyY += dy - 0.04f * sign(dy)
                    steadyX + saccade.first to steadyY + saccade.second
                }
                mood != Mood.IDLE -> 0f to 0f
                // Something moved: glance at it
                settings.habits && clock - tracker.motionAt < 1500 ->
                    (tracker.motionX * settings.gainX).coerceIn(-1f, 1f) to
                        (tracker.motionY * settings.gainY).coerceIn(-1f, 1f)
                else -> null
            }
            if (target != null) {
                // Follow the target on a spring, every frame: quick to start, a tiny overshoot,
                // then it settles, like real eyes landing on something
                val now = withFrameNanos { it }
                val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastFrame = now
                speedX += (LOOK_SPRING * (target.first - lookX.value) - LOOK_DAMPING * speedX) * dt
                speedY += (LOOK_SPRING * (target.second - lookY.value) - LOOK_DAMPING * speedY) * dt
                lookX.snapTo(lookX.value + speedX * dt)
                lookY.snapTo(lookY.value + speedY * dt)
            } else if (System.currentTimeMillis() >= nextGlance) {
                speedX = 0f
                speedY = 0f
                lastFrame = 0L
                // Nothing going on: an unhurried look somewhere now and then (often just back
                // to the middle), then it rests there a while
                val easy = spring<Float>(dampingRatio = 0.85f, stiffness = 60f)
                val home = Random.nextFloat() < 0.35f
                val toX = if (home) 0f else (Random.nextFloat() - 0.5f) * 1.5f
                val toY = if (home) 0f else (Random.nextFloat() - 0.5f) * 0.4f
                launch { lookY.animateTo(toY, easy) }
                lookX.animateTo(toX, easy)
                nextGlance = System.currentTimeMillis() + Random.nextLong(6000, 14000)
            } else {
                delay(100)
            }
        }
    }

    // Idle moves: every half a minute or so while idle, a little something of its own
    // (a slow blink, a stretch, a sneeze, a firefly…). See IDLE_MOVES in Expressions.kt.
    LaunchedEffect(Unit) {
        var nextIdle = System.currentTimeMillis() + 20_000
        while (true) {
            delay(1000)
            val clock = System.currentTimeMillis()
            if (!settings.habits || mood != Mood.IDLE || gemini.active || gemini.expression() != null ||
                settings.focusing() || music.playing || clock < sounds.hushedUntil
            ) {
                nextIdle = maxOf(nextIdle, clock + 8_000)  // not right after something else
                continue
            }
            if (clock < nextIdle) continue
            val here = tracker.seesFace()
            val cold = weather.kind.isNotEmpty() && weather.celsius < 5f
            val choices = IDLE_MOVES.filter {
                (it.withPerson == null || it.withPerson == here) && (!it.onlyWhenCold || cold)
            }
            val total = choices.sumOf { it.weight }
            if (total > 0) {
                var pick = Random.nextInt(total)
                val move = choices.first { pick -= it.weight; pick < 0 }
                gemini.showExpression(move.name, move.ms)
                for ((at, sound) in move.sounds) {
                    launch {
                        delay((at * move.ms).toLong())
                        sounds.play(sound, 0.45f)
                    }
                }
            }
            // Livelier = more often (the Liveliness slider; 0.6 is the usual pace)
            val pace = (1.9f - 1.5f * settings.liveliness).coerceIn(0.4f, 2f)
            nextIdle = clock + ((if (here) Random.nextLong(25_000, 70_000) else Random.nextLong(15_000, 45_000)) * pace).toLong()
        }
    }

    val textMeasurer = rememberTextMeasurer()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() }
                )
            }
            // A stroke across the eyes (a third of the screen or more): a pet, or a skip with music on
            .pointerInput(Unit) {
                var travel = 0f
                var shift = 0f  // which way overall
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (travel > size.width * 0.3f) onSwipe(if (shift < 0f) -1 else 1)
                        travel = 0f
                        shift = 0f
                    },
                    onDragCancel = {
                        travel = 0f
                        shift = 0f
                    }
                ) { _, amount ->
                    travel += abs(amount)
                    shift += amount
                }
            }
    ) {
        // Drawn as one layer of its own, so the glowing (added) colors only ever mix with the
        // eyes themselves, never with whatever Android has behind the app
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val (shakeX, shakeY) = expressionShake
            // Weather view: the eyes look straight ahead, grow to fill the screen and come together
            // (they may spill past the edges); expressions fade out so the rings stay round
            val look = (lookX.value + shakeX).coerceIn(-1.2f, 1.2f) * (1f - zoom)
            val lookUpDown = (lookY.value + shakeY).coerceIn(-1.2f, 1.2f) * (1f - zoom)
            val zoomScale = 1f + 1.6f * zoom
            val eyeSize = eyeBase() * 0.42f * settings.eyeSize * lerp(grow.value, 1f, zoom) * zoomScale  // ring diameter
            val ringWidth = eyeBase() * 0.42f * 0.15f * settings.ringThickness * (1f + 0.3f * zoom)  // ring thickness
            val turn = abs(look)                       // 0 = straight ahead, 1 = fully to the side
            val gap = eyeSize * (0.7f - 0.25f * turn) * (1f - 0.85f * zoom)  // eyes move closer when turned
            val tilt = 1f - 0.35f * abs(lookUpDown)    // eyes get shorter looking up or down
            // Keep both eyes, including their soft glow, fully on screen. Otherwise the screen
            // edge cuts the glow off in a straight line, which looks like a faint box.
            // (Uses the eyes' real height: they're shorter when looking up or down.)
            val glowReach = when {
                timerLeft >= 0f -> ringWidth * 1.9f   // room for the countdown ring
                settings.holographic -> ringWidth * 1.4f
                else -> ringWidth * 0.6f
            }
            val tallest = eyeSize * tilt * max(squint.value, 1f) * (1f + 0.15f * turn + sideBoost)
            val roomY = (size.height / 2f - tallest / 2f - glowReach).coerceAtLeast(0f)
            val roomX = (size.width / 2f - gap / 2f - eyeSize - glowReach).coerceAtLeast(0f)
            val centerX = size.width / 2f + (look * size.width * 0.22f).coerceIn(-roomX, roomX)
            val centerY = size.height / 2f + (lookUpDown * size.height * 0.32f).coerceIn(-roomY, roomY)
            val smiling = smile.value * (1f - zoom)
            val squinting = lerp(squint.value, 1f, zoom)

            rotate((headTilt.value + 3f * bounce) * (1f - zoom), pivot = Offset(centerX, centerY)) {
                for (side in listOf(-1, 1)) {
                    // Perspective: the far eye shrinks a little,
                    // both eyes get narrower when looking to the side,
                    // and shorter when looking up or down.
                    // Curious makes the left eye bigger; wink closes the right one.
                    val sideScale = 1f - 0.15f * look * side - sideBoost * (1f - zoom) * side
                    val winking = if (side == 1) 1f - 0.95f * wink else 1f - 0.95f * winkLeft
                    // While the smile changes shape, the eye squashes a little, like a quick
                    // blink, so the change reads as a pop into a new shape
                    val pop = 1f - 0.35f * abs(goalSmile - smile.value).coerceAtMost(1f) * (1f - zoom)
                    // Closing: the lids come together into a thin line, and at that flattest
                    // moment the shape swaps to the closed arch (so it never looks like draining)
                    val lids = 1f - 0.85f * sin(PI.toFloat() * shut.value)
                    val arch = ((shut.value - 0.4f) / 0.2f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                    val height = max(
                        eyeSize * sideScale * tilt * squinting * (if (side == -1) openness else opennessR).value * winking * pop * lids *
                            (1f + 0.1f * syllable), 1f
                    )
                    // Happy: the eye squashes into a low, wide smiling arch (^ ^), so it reads as
                    // a new shape rather than a ring emptying (that's the timer's look)
                    // A glitch: the eye jumps about in little steps (like a flickering projector)
                    var jumpX = 0f
                    var jumpY = 0f
                    if (glitch > 0.01f) {
                        val step = kotlin.math.floor(time * 16f)
                        val h = hash(step * 1.37f + side * 7.1f)
                        if (h > 0.4f) jumpX = (h - 0.7f) * eyeSize * 0.6f * glitch
                        if (h > 0.85f) jumpY = (hash(step + 3.3f) - 0.5f) * eyeSize * 0.5f * glitch
                    }
                    val eye = EyeShape(
                        x = centerX + side * (gap / 2f + eyeSize / 2f) + jumpX,
                        // (asleep: only the top of the ring shows, a closed lid arching upward)
                        y = centerY + height * 0.3f * smiling + height * 0.35f * arch - bounce * eyeSize * 0.06f + jumpY,
                        width = eyeSize * sideScale * (1f - 0.3f * turn) * (1f + 0.1f * smiling),
                        height = height * (1f - 0.4f * smiling),
                        sweep = 360f - max(200f * smiling, 220f * arch)
                    )

                    val trail = trails.getValue(side)
                    // (asleep, a thin line, like a closed eyelid; a thick one reads as a squashed oval)
                    drawEye(eye, trail, ringWidth * (1f - 0.6f * shut.value), glow.value * (1f + 0.2f * syllable), time, side, settings)
                    // ...and splits into cyan and magenta copies while it glitches
                    if (glitch > 0.01f && jumpX != 0f) {
                        val split = ringWidth * 1.4f * glitch
                        ring(eye, Color(0xFF00F0FF), ringWidth * 0.8f, 0.7f * glitch, dx = -split)
                        ring(eye, Color(0xFFFF2BD6), ringWidth * 0.8f, 0.7f * glitch, dx = split)
                    }
                    if (musicTint > 0.01f) drawTint(eye, ringWidth, musicColor, musicTint * (1f - zoom) * glow.value.coerceAtMost(1f))
                    if (zoom > 0.01f) {
                        // The weather view: tinted rings, the icon in the left eye, the temperature
                        // in the right one (they appear once the eyes are most of the way in)
                        val brightness = glow.value.coerceIn(0.5f, 1f)
                        weatherTint(weather.viewKind, weather.viewDay)?.let { drawTint(eye, ringWidth, it, 0.6f * zoom * brightness) }
                        val inside = ((zoom - 0.55f) / 0.45f).coerceIn(0f, 1f) * brightness
                        val room = minOf(eye.width, eye.height) / 2f - ringWidth * 1.3f
                        if (side == -1) {
                            drawWeatherIcon(Offset(eye.x, eye.y), room, weather.viewKind, weather.viewDay, time, inside)
                        } else {
                            drawTemperature(textMeasurer, Offset(eye.x, eye.y), room, weather, inside, eyeBase() * 0.42f * settings.eyeSize * 2.6f)
                        }
                    }
                    if (weatherStrength > 0f && zoom < 0.01f) {
                        drawWeather(eye, ringWidth, weather.kind, weather.isDay, weatherStrength * glow.value.coerceAtMost(1f), time, side)
                    }
                    settings.occasion()?.let { kind ->
                        if (occasionStrength > 0f) drawOccasion(eye, ringWidth, kind, occasionStrength * glow.value.coerceAtMost(1f), time, side)
                    }
                    if (side == 1 && timerLeft >= 0f) drawTimerRing(eye, ringWidth, timerLeft, time, glow.value)
                    trail.addLast(eye)
                    while (trail.size > settings.trailLength.toInt()) trail.removeFirst()
                    if (side == 1 && sleepZ > 0.01f) drawSleepZs(eye, eyeSize, ringWidth, time, sleepZ)
                }
            }
            // A firefly (or other little light) from an idle move
            spark?.let { (x, y, brightness) ->
                if (brightness > 0.01f) {
                    val c = Offset(size.width / 2f + x * size.width * 0.4f, size.height / 2f + y * size.height * 0.4f)
                    val r = ringWidth * 1.2f
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFFFFF3A0).copy(alpha = 0.5f * brightness), Color.Transparent), c, r),
                        r, c, blendMode = BlendMode.Plus
                    )
                    drawCircle(Color(0xFFFFFBE0), ringWidth * 0.18f, c, alpha = brightness, blendMode = BlendMode.Plus)
                }
            }
            if (settings.scanLines) {
                drawScanLines(size.height / settings.scanCount, settings.scanThickness)
            }
            // A state that matters (timer, reminder, focus, not listening): the screen's edge glows
            if (stateStrength > 0.01f) {
                drawScreenBorder(stateColor, stateStrength * glow.value.coerceIn(0.5f, 1f), 18.dp.toPx(), 28.dp.toPx())
            }
            // No internet: a small crossed-out Wi-Fi symbol in the top corner
            // (or, waiting for Gemini, a small spinning comet in the same corner)
            val corner = Offset(size.width - 28.dp.toPx(), 28.dp.toPx())
            if (offline) {
                drawOfflineIcon(corner, 14.dp.toPx())
            } else {
                if (thinking > 0.01f) drawThinking(corner, 9.dp.toPx(), time, thinking)
                // Music playing: dancing bars (they step aside for the spinner), on the beat when
                // it can hear one, in the album's colour when there is one
                val bars = musicIcon * (1f - thinking)
                if (bars > 0.01f) {
                    val beat = if (music.playing) music.pulse(System.currentTimeMillis()) else 0f
                    val color = if (musicTint > 0.05f) musicColor else Color(0xFFBFF6FF)
                    drawMusicPlaying(corner, 10.dp.toPx(), time, bars * glow.value.coerceIn(0.5f, 1f), beat, color)
                }
            }
        }

        if (captionAlpha > 0f) {
            Text(
                caption,
                color = Color.White.copy(alpha = 0.85f * captionAlpha),
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 48.dp, end = 48.dp, bottom = 40.dp)
            )
        }

        // The tap hints ("•  talk", "••  sleep", "—  settings") stand big and bold, stacked on the
        // left; any other message stays small at the bottom. Both fade 6 seconds after a change.
        val (hints, message) = splitHints(status)
        if (hints.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                for ((symbol, word) in hints) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            symbol,
                            color = HINT_COLOR.copy(alpha = 0.9f * statusAlpha),
                            fontSize = 34.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(word, color = HINT_COLOR.copy(alpha = 0.9f * statusAlpha), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (message.isNotEmpty()) {
            Text(
                message,
                color = Color.Gray.copy(alpha = statusAlpha),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            )
        }
    }
}

private val HINT_COLOR = Color(0xFFC3CBD9)

// Splits the status line into the tap hints (symbol, word) and whatever else it says
private fun splitHints(status: String): Pair<List<Pair<String, String>>, String> {
    val tidy = { rest: String -> rest.trim().trim('·').trim() }
    return when {
        status.contains(TAP_HINT) ->
            listOf("•" to "talk", "••" to "sleep", "—" to "settings") to tidy(status.replace(TAP_HINT, ""))
        status.startsWith("••  wake") -> listOf("••" to "wake") to tidy(status.removePrefix("••  wake"))
        status.startsWith("•  wake") -> listOf("•" to "wake") to tidy(status.removePrefix("•  wake"))
        else -> emptyList<Pair<String, String>>() to status
    }
}

// The weather view's right eye: the temperature now, big, with the high (red) and low (blue)
// under it (↑18°  ↓9°, bold arrows), glowing like the eyes. For another day or the week (no
// "now"): the day's name on top, then the high and the low, both big. Laid out once at full size
// (`fullSize` = the zoomed eye's diameter) and scaled, so the text isn't laid out again every
// frame while the eyes zoom.
private fun DrawScope.drawTemperature(
    measurer: TextMeasurer, c: Offset, room: Float, weather: Weather, alpha: Float, fullSize: Float
) {
    if (alpha <= 0.01f || room <= 0f) return
    val fullRoom = fullSize / 2f
    val glowColor = weatherTint(weather.viewKind, weather.viewDay) ?: Color(0xFF00F0FF)
    val glow = fullRoom * 0.06f
    // "↑18°" in red or "↓9°" in blue, with a bold arrow
    fun AnnotatedString.Builder.reading(arrow: String, celsius: Double, color: Color) {
        withStyle(SpanStyle(color = color, shadow = Shadow(color, Offset.Zero, glow))) {
            withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append(arrow) }
            append(weather.deg(celsius))
        }
    }
    // Lines from top to bottom: (layout, how much of its height it takes in the stack)
    val lines = mutableListOf<Pair<TextLayoutResult, Float>>()
    val big: TextLayoutResult?
    if (weather.viewNow.isNaN()) {
        big = null
        lines += measurer.measure(
            weather.viewLabel,
            TextStyle(color = Color.White, fontSize = (fullRoom * 0.13f).toSp(), fontWeight = FontWeight.Medium, letterSpacing = (fullRoom * 0.01f).toSp())
        ) to 1f
        for ((arrow, value, color) in listOf(Triple("↑", weather.viewHigh, HIGH_RED), Triple("↓", weather.viewLow, LOW_BLUE))) {
            lines += measurer.measure(
                buildAnnotatedString { reading(arrow, value, color) },
                TextStyle(fontSize = (fullRoom * 0.4f).toSp(), fontWeight = FontWeight.Normal)
            ) to 0.88f
        }
    } else {
        big = measurer.measure(
            weather.deg(weather.viewNow),
            TextStyle(fontSize = (fullRoom * 0.78f).toSp(), fontWeight = FontWeight.Light)
        )
        lines += big to 0.82f  // fonts leave room above and below; pull the lines together
        lines += measurer.measure(
            buildAnnotatedString {
                reading("↑", weather.viewHigh, HIGH_RED)
                append("   ")
                reading("↓", weather.viewLow, LOW_BLUE)
            },
            TextStyle(fontSize = (fullRoom * 0.24f).toSp(), fontWeight = FontWeight.Medium)
        ) to 1f
    }
    val total = lines.sumOf { (layout, share) -> (layout.size.height * share).toDouble() }.toFloat()
    scale(room / fullRoom, pivot = c) {
        translate(c.x, c.y) {
            var y = -total / 2f
            for ((layout, share) in lines) {
                val top = y - layout.size.height * (1f - share) / 2f
                val x = -layout.size.width / 2f
                if (layout === big) {
                    // The big number: cyan and magenta fringes, then the bright core with a soft glow
                    val shift = fullRoom * 0.025f
                    drawText(big, Color(0xFF00F0FF), Offset(x - shift, top - shift * 0.5f), alpha = 0.4f * alpha, blendMode = BlendMode.Plus)
                    drawText(big, Color(0xFFFF2BD6), Offset(x + shift, top + shift * 0.5f), alpha = 0.3f * alpha, blendMode = BlendMode.Plus)
                    drawText(
                        big, Color.White, Offset(x, top), alpha = alpha,
                        shadow = Shadow(glowColor.copy(alpha = 0.8f), Offset.Zero, fullRoom * 0.08f), blendMode = BlendMode.Plus
                    )
                } else {
                    // Everything else in its own colours (high red, low blue, the day's name white)
                    drawText(layout, topLeft = Offset(x, top), alpha = alpha, blendMode = BlendMode.Plus)
                }
                y += layout.size.height * share
            }
        }
    }
}

private val HIGH_RED = Color(0xFFFF5A4E)
private val LOW_BLUE = Color(0xFF4FA3FF)

// Sleeping: little "z"s float up from beside the right eye, growing and fading, one after
// another, drawn in the eyes' glowing style
private fun DrawScope.drawSleepZs(eye: EyeShape, eyeSize: Float, ringWidth: Float, time: Float, strength: Float) {
    val cycle = 4.5f  // seconds for one z to rise and fade
    for (i in 0 until 3) {
        val p = ((time + i * cycle / 3f) % cycle) / cycle  // 0..1 through its rise
        val alpha = sin(p * PI.toFloat()) * 0.55f * strength
        val zSize = ringWidth * (0.9f + 1.6f * p)
        val center = Offset(
            eye.x + eye.width * 0.5f + p * eyeSize * 0.3f + sin(p * 2f * PI.toFloat()) * eyeSize * 0.04f,
            eye.y - eyeSize * 0.15f - p * eyeSize * 0.65f
        )
        val h = zSize / 2f
        val z = Path().apply {
            moveTo(center.x - h, center.y - h)
            lineTo(center.x + h, center.y - h)
            lineTo(center.x - h, center.y + h)
            lineTo(center.x + h, center.y + h)
        }
        val line = ringWidth * 0.22f
        drawPath(  // soft cyan glow
            z, Color(0xFF00F0FF), alpha = alpha * 0.4f,
            style = Stroke(line * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus
        )
        drawPath(
            z, Color.White, alpha = alpha,
            style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus
        )
    }
}

// How long the start-up animation lasts
const val INTRO_SECONDS = 1.4f

// When the shared animation clock started (frame time in nanoseconds; 0 = not yet).
// The intro and the eyes both measure time from here, so nothing jumps between them.
var animationStart = 0L

// Start-up animation: a moon orbits a planet, then the orbit flattens out and the two settle
// side by side as the eyes, exactly where the eyes rest, so the hand-over is seamless.
// The planet becomes the left eye and the moon the right one.
@Composable
fun Intro(settings: Settings, onDone: () -> Unit) {
    var time by remember { mutableFloatStateOf(0f) }
    val trails = remember { mapOf(-1 to ArrayDeque<EyeShape>(), 1 to ArrayDeque<EyeShape>()) }

    LaunchedEffect(Unit) {
        if (animationStart == 0L) animationStart = withFrameNanos { it }
        while (time < INTRO_SECONDS) {
            withFrameNanos { time = (it - animationStart) / 1_000_000_000f }
        }
        onDone()
    }

    Canvas(Modifier.fillMaxSize().background(Color.Black)) {
        val progress = (time / INTRO_SECONDS).coerceIn(0f, 1f)
        val fadeIn = (progress / 0.15f).coerceAtMost(1f)
        // 0 while orbiting, easing up to 1 as the pair settle into the eyes' places
        val settle = smoothStep((progress - 0.55f) / 0.45f)

        // The eyes' resting size and places (the same sums the eyes use)
        val eyeSize = eyeBase() * 0.42f * settings.eyeSize
        val ringWidth = eyeBase() * 0.42f * 0.15f * settings.ringThickness
        val middle = Offset(size.width / 2f, size.height / 2f)
        val restOffset = eyeSize * 0.85f  // half the distance between the eyes' centers

        // Planet glides from the middle to the left eye's place
        val planet = EyeShape(middle.x - restOffset * settle, middle.y, eyeSize, eyeSize)

        // Moon: a little over one trip round a flattened (seen from the side) orbit, ending on
        // the planet's right, already heading toward the right eye's place
        val angle = 2f * PI.toFloat() * 1.25f * (progress - 1f) + 0.3f
        val inFront = sin(angle) > 0f || settle > 0.5f    // lower half of the orbit is nearer us
        val nearness = 0.75f + 0.25f * sin(angle)          // smaller when further away
        val orbitX = planet.x + cos(angle) * eyeSize * 1.05f
        val orbitY = planet.y + sin(angle) * eyeSize * 0.28f
        val moonSize = lerp(eyeSize * 0.3f * nearness, eyeSize, settle)
        val moon = EyeShape(
            x = lerp(orbitX, middle.x + restOffset, settle),
            y = lerp(orbitY, middle.y, settle),
            width = moonSize,
            height = moonSize
        )
        val moonRing = lerp(ringWidth * 0.5f, ringWidth, settle)
        val moonGlow = fadeIn * (if (inFront) 1f else 0.7f)

        rotate(-15f * (1f - settle), pivot = middle) {  // the orbit's tilt straightens out
            if (!inFront) {
                drawEye(moon, trails.getValue(1), moonRing, moonGlow, time, 1, settings)
                // The planet's body hides the moon behind it
                drawOval(
                    Color.Black,
                    topLeft = Offset(planet.x - eyeSize / 2f, planet.y - eyeSize / 2f),
                    size = Size(eyeSize, eyeSize)
                )
            }
            drawEye(planet, trails.getValue(-1), ringWidth, fadeIn, time, -1, settings)
            if (inFront) drawEye(moon, trails.getValue(1), moonRing, moonGlow, time, 1, settings)
        }
        for ((side, eye) in listOf(-1 to planet, 1 to moon)) {
            val trail = trails.getValue(side)
            trail.addLast(eye)
            while (trail.size > settings.trailLength.toInt()) trail.removeFirst()
        }

        if (settings.scanLines) {
            drawScanLines(size.height / settings.scanCount, settings.scanThickness)
        }
    }
}

// Eases 0..1 smoothly (slow start, slow finish); values outside are clamped
private fun smoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerp(from: Float, to: Float, amount: Float) = from + (to - from) * amount

// How far to ease toward a goal in one frame. `rate` is the share per 1/30 s, adjusted for how
// long the frame took, so motion is equally fast at 60 or 120 frames a second
private fun easing(rate: Float, frameMs: Float) = 1f - (1f - rate).pow(frameMs / 33f)

// The springy eye follow: about 2.6 bounces a second, damped so it overshoots only a little (~8 %)
private const val LOOK_SPRING = 191f    // (2π × 2.2)²: a calm, unhurried follow
// How long Gemini has to be "thinking" before the eyes (and head) glance up to ponder
const val PONDER_AFTER_MS = 1200L
private const val LOOK_DAMPING = 23.5f  // 2 × 0.85 × 2π × 2.2: only a whisper of overshoot

// A repeatable "random" number 0..1 from any number (same input, same answer)
private fun hash(x: Float): Float {
    val v = sin(x * 12.9898f) * 43758.547f
    return v - kotlin.math.floor(v)
}
