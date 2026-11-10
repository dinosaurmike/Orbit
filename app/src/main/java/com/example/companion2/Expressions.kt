package com.example.companion2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Expressions and idle moves: all of Orbit's little acts in one place.
//
// Want to add your own? Add an entry to MOVES (how it looks, moment by moment) and, if it
// should happen on its own while Orbit is idle, to IDLE_MOVES. See CONTRIBUTING.md.
// ---------------------------------------------------------------------------------------------

// Everything an expression can change for one moment. It starts from how the current mood looks
// (size, openness, brightness); each move nudges only what it needs.
class Pose(var grow: Float, var squint: Float, var glow: Float) {
    var look: Pair<Float, Float>? = null   // where to look (-1..1 each way), or null = as usual
    var tilt = 0f                          // whole-face tilt, degrees
    var smile = 0f                         // 0..1: the rings open into happy arches
    var wink = 0f                          // 0..1: the right eye closes
    var winkLeft = 0f                      // 0..1: the left eye closes
    var glitch = 0f                        // 0..1: the hologram flickers and jumps (a silly glitch)
    var sideBoost = 0f                     // the left eye bigger than the right
    var shakeX = 0f                        // quick wobble side to side (head shake)
    var shakeY = 0f                        // ...and up and down (nod)
    var spark: Triple<Float, Float, Float>? = null  // a little glowing dot: x, y (-1..1), brightness
    var neckPan = 0f                       // extra neck turn, degrees
    var neckTilt = 0f                      // extra neck tilt, degrees (+ = down)
}

// A move: given how far through it is (0..1), shape the pose
typealias Move = (progress: Float, pose: Pose) -> Unit

private fun move(block: Move): Move = block

private val TAU = 2f * PI.toFloat()
private fun swell(p: Float) = sin(p * PI.toFloat())       // 0 → 1 → 0
private fun wave(p: Float) = sin(p * 2f * TAU)            // two back-and-forths
private fun bump(p: Float, at: Float, width: Float) =      // a short blip centered on `at`
    (1f - abs(p - at) / width).coerceAtLeast(0f)
// Quick in, hold, quick out (0 → 1 … 1 → 0): `rise` and `fall` are the shares of the move
// spent getting there and back. Snappy changes read as expressions; slow ones look like fading.
private fun hold(p: Float, rise: Float = 0.1f, fall: Float = 0.15f): Float {
    val x = minOf(p / rise, (1f - p) / fall, 1f).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)  // smoothed at both ends
}

// How long some moves last, when not the usual EXPRESSION_MS
val MOVE_MS = mapOf("wink" to 650)

val MOVES: Map<String, Move> = mapOf(
    // ----- Expressions Gemini can choose while it talks (listed in EXPRESSIONS) -----
    "happy" to move { p, pose ->
        pose.squint = 0.75f
        pose.smile = 1f
        pose.neckTilt -= 6f * swell(p)          // perks up
    },
    "surprised" to move { p, pose ->
        pose.grow = 1.25f
        pose.squint = 1.1f
        pose.neckTilt -= 8f * swell(p)          // pulls back
    },
    // Wake word: "perk" on the first sound of its name ("Or…"), before it's sure, like a dog's
    // ears; "wake" once it's sure. Both quick to rise, so the eyes answer as you speak.
    "perk" to move { p, pose ->
        val k = hold(p, rise = 0.1f, fall = 0.45f)
        pose.grow *= 1f + 0.08f * k
        pose.glow *= 1f + 0.25f * k
        pose.neckTilt -= 3f * k
    },
    "wake" to move { p, pose ->
        val k = hold(p, rise = 0.06f, fall = 0.35f)
        pose.grow *= 1f + 0.1f * k
        pose.glow *= 1f + 0.2f * k
        pose.tilt = 8f * k
        pose.neckTilt -= 4f * k
    },
    "curious" to move { p, pose ->
        pose.tilt = 12f
        pose.sideBoost = 0.12f
        pose.neckPan += 6f * swell(p)
        pose.neckTilt -= 4f * swell(p)
    },
    "sad" to move { p, pose ->
        pose.squint = 0.6f
        pose.glow *= 0.75f
        pose.look = 0f to 0.6f
        pose.neckTilt += 14f * swell(p)         // droops
    },
    "nod" to move { p, pose ->
        pose.shakeY = 0.45f * wave(p)
        pose.neckTilt += 10f * wave(p)
    },
    "shake_head" to move { p, pose ->
        pose.shakeX = 0.6f * wave(p)
        pose.neckPan += 14f * wave(p)
    },
    "look_away" to move { p, pose ->
        pose.look = 0.9f to -0.2f
        pose.neckPan += 30f * swell(p)
    },
    // A quick, cheeky wink: snaps shut, holds a beat, opens, with a little head tilt
    "wink" to move { p, pose ->
        val w = hold(p, rise = 0.15f, fall = 0.35f)
        pose.wink = w
        pose.tilt = 5f * w
        pose.sideBoost = 0.05f * w   // the open eye opens a touch wider
    },

    // ----- Things it does by itself -----
    // Taking a photo: eyes wide, looking straight ahead
    "looking" to move { _, pose ->
        pose.grow = 1.15f
        pose.glow = 1f
        pose.look = 0f to 0f
    },
    // Taking a photo of the desk: eyes wide, looking down (the neck tilts down separately)
    "looking_down" to move { _, pose ->
        pose.grow = 1.1f
        pose.glow = 1f
        pose.look = 0f to 1f
    },
    // Looking at your computer screen: a glance toward the computer (direction set in settings)
    // Looking around for the computer screen: eyes wide and steady while it takes photos
    "searching" to move { _, pose ->
        pose.grow = 1.08f
        pose.squint = 0.95f
        pose.look = 0f to 0f
    },
    // Looking at the screen with its own camera: the head turns there (Neck); eyes wide, ahead
    "looking_screen" to move { _, pose ->
        pose.grow = 1.1f
        pose.glow = 1f
        pose.look = 0f to 0f
    },
    "looking_screen_left" to move { p, pose -> lookAtScreen(p, pose, -1f) },
    "looking_screen_right" to move { p, pose -> lookAtScreen(p, pose, 1f) },
    // A reminder or timer: the rings pulse
    "alert" to move { p, pose ->
        pose.grow = 1f + 0.14f * abs(sin(p * 3f * PI.toFloat()))
        pose.glow = 1.15f
        pose.neckTilt -= 5f * swell(p)
    },
    // Jumped at a noise
    "startled" to move { p, pose ->
        pose.grow = 1.3f
        pose.squint = 1.15f
        pose.look = 0f to -0.15f
        pose.neckTilt -= 10f * swell(p)
    },
    // A big yawn: eyes squeeze, the head tips back, then they open drowsily
    "yawn" to move { p, pose ->
        pose.squint = if (p < 0.6f) 0.22f else 0.22f + (p - 0.6f) / 0.4f * 0.15f
        pose.grow = 1f + 0.12f * swell(p)
        pose.look = 0f to -0.3f * swell(p)
        pose.neckTilt -= 7f * swell(p)
    },
    // You're back! A glance away… a double-take… then a happy smile
    "double_take" to move { p, pose ->
        when {
            p < 0.25f -> {}
            p < 0.4f -> {
                pose.look = 0.7f to 0.1f
                pose.neckPan += 15f
            }
            p < 0.55f -> {
                pose.grow = 1.3f
                pose.squint = 1.15f
            }
            else -> {
                pose.squint = 0.75f
                pose.smile = 1f
            }
        }
    },
    // Lost in thought about its hobby: gazing up and away
    "daydream" to move { p, pose ->
        pose.squint = 0.85f
        pose.glow *= 0.85f
        pose.look = -0.5f to -0.6f
        pose.neckPan -= 12f * swell(p)
        pose.neckTilt -= 8f * swell(p)
    },
    // Its very first wake-up: eyes flutter open, look around in wonder, then at you
    "hatch" to move { p, pose ->
        pose.glow = (0.3f + p * 1.4f).coerceAtMost(1f)
        pose.squint = when {
            p < 0.25f -> 0.04f
            p < 0.32f -> 0.45f
            p < 0.4f -> 0.08f
            p < 0.6f -> 0.08f + (p - 0.4f) / 0.2f
            else -> 1.05f
        }
        if (p > 0.4f) pose.grow = 1.08f
        when {
            p < 0.6f -> { pose.look = 0f to 0f }
            p < 0.72f -> { pose.look = -0.7f to 0f }
            p < 0.84f -> { pose.look = 0.7f to 0f }
            else -> { pose.tilt = 10f }
        }
    },

    // ----- Reactions to you (petting, hand gestures, focus) -----
    // Stroked across the eyes: they close happily (with a purr)
    "petted" to move { p, pose ->
        pose.squint = 0.35f + 0.2f * (1f - swell(p))
        pose.smile = 0.8f * hold(p)
        pose.tilt = 6f * swell(p)
        pose.neckTilt -= 4f * swell(p)
    },
    // You waved: a happy little wiggle back
    "wave_back" to move { p, pose ->
        pose.smile = 0.7f * hold(p)
        pose.shakeX = 0.25f * sin(p * 3f * TAU) * swell(p)
        pose.neckPan += 8f * sin(p * 3f * TAU) * swell(p)
    },
    // "Shh" (a finger up): eyes narrow, a small quiet nod
    "shh" to move { p, pose ->
        pose.squint = 0.6f
        pose.glow *= 0.8f
        pose.shakeY = 0.12f * sin(p * TAU) * swell(p)
    },
    // A love sign: soft and happy
    "love" to move { p, pose ->
        pose.smile = 0.9f * hold(p)
        pose.grow = 1f + 0.08f * swell(p)
        pose.glow *= 1.1f
    },
    // Finishing a focus session: a proud little bounce
    "celebrate" to move { p, pose ->
        pose.smile = 1f
        pose.grow = 1f + 0.12f * abs(sin(p * 3f * PI.toFloat()))
        pose.neckTilt -= 6f * abs(sin(p * 3f * PI.toFloat()))
    },

    // ----- Idle moves (picked at random while it's idle; see IDLE_MOVES) -----
    // A slow, contented blink: how cats say "I like you"
    // ----- Rare and silly -----
    // The hologram glitches: the eyes flicker, jump and split into colours for a moment
    "glitch" to move { p, pose ->
        pose.glitch = if (p < 0.8f) 1f else ((1f - p) / 0.2f).coerceAtLeast(0f)
        if (p > 0.85f) pose.smile = 0.3f * swell((p - 0.85f) / 0.15f)  // "…that was weird"
    },
    // A blinking game with itself: left, right, left, right, faster and faster, then both
    // shut, and a dizzy little wobble
    "blink_game" to move { p, pose ->
        if (p < 0.7f) {
            val q = p / 0.7f
            val count = 11f * q * q  // speeding up
            val blink = sin(PI.toFloat() * (count - count.toInt()))
            if (count.toInt() % 2 == 0) pose.winkLeft = blink else pose.wink = blink
        } else if (p < 0.82f) {
            pose.wink = 1f
            pose.winkLeft = 1f
        } else {
            val w = (1f - p) / 0.18f
            pose.tilt = 9f * sin(p * 40f) * w
            pose.shakeX = 0.15f * sin(p * 30f) * w
            pose.smile = 0.5f * w
        }
    },
    // Eyes spin round twice, then wobble, dizzy
    "dizzy" to move { p, pose ->
        if (p < 0.55f) {
            val angle = p / 0.55f * 2f * TAU
            pose.look = cos(angle) * 0.6f to sin(angle) * 0.4f
            pose.neckPan += 6f * cos(angle)
        } else {
            val w = (1f - p) / 0.45f
            pose.tilt = 12f * sin(p * 25f) * w
            pose.squint = 0.8f
            pose.look = 0f to 0f
        }
    },
    // Boing: a springy squash and stretch
    "boing" to move { p, pose ->
        val b = sin(p * PI.toFloat() * 6f) * exp(-3f * p)
        pose.grow = 1f + 0.22f * b
        pose.squint = 1f - 0.3f * b
        pose.neckTilt -= 5f * b
    },

    "slow_blink" to move { p, pose ->
        pose.squint *= 1f - 0.9f * swell(p)
        pose.smile = 0.3f * swell(p)
    },
    // Just happy you're here
    "content" to move { p, pose ->
        pose.smile = 0.6f * hold(p)
        pose.squint = 0.85f
        pose.glow *= 1.05f
    },
    // A little puzzled look at you
    "wonder" to move { p, pose ->
        pose.tilt = -10f * swell(p)
        pose.sideBoost = -0.1f * swell(p)
        pose.neckPan -= 5f * swell(p)
    },
    // Thinking something over: eyes down and to the side, narrowed
    "ponder" to move { p, pose ->
        pose.look = -0.4f to 0.5f
        pose.squint = 0.7f
        pose.tilt = -6f * swell(p)
        pose.neckTilt += 5f * swell(p)
    },
    // Ah… ah… CHOO!
    "sneeze" to move { p, pose ->
        when {
            p < 0.45f -> {                           // the build-up
                pose.squint = 0.6f - 0.3f * (p / 0.45f)
                pose.grow = 1f + 0.08f * (p / 0.45f)
                pose.look = 0f to -0.25f * (p / 0.45f)
                pose.neckTilt -= 6f * (p / 0.45f)
            }
            p < 0.58f -> {                           // CHOO
                pose.squint = 0.05f
                pose.grow = 0.9f
                pose.look = 0f to 0.35f
                pose.neckTilt += 12f
            }
            else -> { pose.squint *= 0.6f + 0.4f * ((p - 0.58f) / 0.42f) }  // recovering
        }
    },
    // Hic! Hic! Hic!
    "hiccup" to move { p, pose ->
        val hic = bump(p, 0.1f, 0.06f) + bump(p, 0.45f, 0.06f) + bump(p, 0.8f, 0.06f)
        pose.shakeY = -0.25f * hic
        pose.grow += 0.08f * hic
        pose.neckTilt -= 5f * hic
    },
    // A big stretch: tall, then wide, then settle
    "stretch" to move { p, pose ->
        when {
            p < 0.4f -> {
                pose.squint = 1f + 0.3f * swell(p / 0.4f)
                pose.grow = 1f + 0.05f * swell(p / 0.4f)
                pose.neckTilt -= 10f * swell(p / 0.4f)
            }
            p < 0.8f -> {
                pose.squint = 1f - 0.4f * swell((p - 0.4f) / 0.4f)
                pose.grow = 1f + 0.12f * swell((p - 0.4f) / 0.4f)
            }
            else -> {}  // settling back
        }
    },
    // A firefly drifts past; the eyes (and head) follow it until it's gone
    "firefly" to move { p, pose ->
        val x = sin(p * TAU * 1.3f) * 0.8f
        val y = -0.45f + 0.3f * sin(p * TAU * 2.1f)
        val brightness = minOf(p / 0.1f, (1f - p) / 0.15f, 1f).coerceAtLeast(0f)
        pose.spark = Triple(x, y, brightness)
        pose.look = x * 0.9f to y * 0.9f
        pose.grow = 1.08f
        pose.neckPan += x * 15f
        pose.neckTilt += y * 8f
        if (p > 0.85f) pose.smile = 0.5f * swell((p - 0.85f) / 0.15f)
    },
    // Bored, nobody around: a slow roll of the eyes
    "roll_eyes" to move { p, pose ->
        val angle = p * TAU - PI.toFloat() / 2f
        pose.look = cos(angle) * 0.7f to sin(angle) * 0.5f
        pose.squint = 0.9f
    },
    // Nobody around: small, careful peeking from side to side
    "peek" to move { p, pose ->
        pose.grow = 0.75f
        pose.glow *= 0.9f
        pose.look = sin(p * TAU) * 0.9f to 0.1f
        pose.neckPan += sin(p * TAU) * 20f
    },
    // Brr (only when it knows it's cold out)
    "shiver" to move { p, pose ->
        pose.shakeX = 0.06f * sin(p * 60f) * swell(p)
        pose.squint = 0.8f
    }
)

private fun lookAtScreen(p: Float, pose: Pose, side: Float) {
    pose.grow = 1.1f
    pose.glow = 1f
    pose.look = side * 0.95f to 0.15f
    pose.neckPan += side * 35f * swell(p)
}

// Idle moves happen by themselves every half a minute or so while Orbit is idle (with
// "Little habits" on). `withPerson`: true = only when it can see you, false = only when
// nobody's there, null = either. `weight`: how often it's picked compared with the others.
// `sounds`: (when, 0..1 through the move) to (which sound).
class IdleMove(
    val name: String,
    val ms: Int,
    val weight: Int,
    val withPerson: Boolean?,
    val sounds: List<Pair<Float, Sound>> = emptyList(),
    val onlyWhenCold: Boolean = false
)

val IDLE_MOVES = listOf(
    IdleMove("slow_blink", 2000, 4, withPerson = true),
    IdleMove("content", 3000, 3, withPerson = true),
    IdleMove("wonder", 2500, 2, withPerson = true),
    IdleMove("ponder", 3500, 2, withPerson = null),
    IdleMove("sneeze", 1800, 1, withPerson = null, sounds = listOf(0.1f to Sound.SNEEZE)),
    IdleMove(
        "hiccup", 2400, 1, withPerson = null,
        sounds = listOf(0.08f to Sound.HICCUP, 0.43f to Sound.HICCUP, 0.78f to Sound.HICCUP)
    ),
    IdleMove("stretch", 2600, 2, withPerson = null, sounds = listOf(0.15f to Sound.STRETCH)),
    IdleMove("firefly", 6000, 2, withPerson = null),  // silent: the little light is enough
    IdleMove("roll_eyes", 2600, 2, withPerson = false),
    IdleMove("peek", 3000, 3, withPerson = false),
    IdleMove("shiver", 1200, 2, withPerson = null, onlyWhenCold = true),
    // Rare and silly (short, silent)
    IdleMove("glitch", 1300, 1, withPerson = null),
    IdleMove("blink_game", 3000, 1, withPerson = null),
    IdleMove("dizzy", 2600, 1, withPerson = null),
    IdleMove("boing", 1400, 1, withPerson = null)
)
