package com.example.companion2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Every expression and idle move (Expressions.kt) is a little animation that shapes the eyes
// moment by moment. These tests play each one from start to finish and check it stays sane:
// no missing moves, no broken numbers, nothing out of range that would draw the eyes off
// screen or swing the neck too far.
class ExpressionsTest {
    private val steps = (0..40).map { it / 40f }  // 0, 0.025 … 1

    // In range, allowing for floating-point rounding (sin(π) is 0.00000002, not 0)
    private fun Float.within(lo: Float, hi: Float) = this >= lo - 1e-4f && this <= hi + 1e-4f

    private fun play(name: String, check: (Float, Pose) -> Unit) {
        val move = MOVES[name]
        assertNotNull("no move called \"$name\"", move)
        for (p in steps) {
            val pose = Pose(grow = 1f, squint = 1f, glow = 1f)
            move!!(p, pose)
            check(p, pose)
        }
    }

    @Test fun everyExpressionGeminiCanPickExists() {
        for (name in EXPRESSIONS) assertNotNull("Gemini can pick \"$name\" but there's no move for it", MOVES[name])
    }

    @Test fun everyIdleMoveExists() {
        for (idle in IDLE_MOVES) assertNotNull("idle move \"${idle.name}\" has no animation", MOVES[idle.name])
    }

    @Test fun customDurationsAreForRealMoves() {
        for (name in MOVE_MS.keys) assertNotNull("MOVE_MS lists \"$name\", which isn't a move", MOVES[name])
    }

    @Test fun idleMovesAreSensible() {
        assertEquals("idle move names must be unique", IDLE_MOVES.size, IDLE_MOVES.map { it.name }.toSet().size)
        for (idle in IDLE_MOVES) {
            assertTrue("${idle.name}: weight ${idle.weight}", idle.weight > 0)
            assertTrue("${idle.name}: ${idle.ms} ms", idle.ms in 500..10_000)
            for ((at, _) in idle.sounds) assertTrue("${idle.name}: a sound at $at", at in 0f..1f)
        }
    }

    @Test fun everyMoveStaysInRange() {
        for (name in MOVES.keys) play(name) { p, pose ->
            val where = "$name at ${"%.3f".format(p)}"
            val numbers = listOf(
                pose.grow, pose.squint, pose.glow, pose.tilt, pose.smile, pose.wink, pose.winkLeft,
                pose.glitch, pose.sideBoost, pose.shakeX, pose.shakeY, pose.neckPan, pose.neckTilt
            )
            assertTrue("$where: a number isn't finite $numbers", numbers.all { it.isFinite() })
            assertTrue("$where: smile ${pose.smile}", pose.smile.within(0f, 1f))
            assertTrue("$where: wink ${pose.wink}", pose.wink.within(0f, 1f))
            assertTrue("$where: left wink ${pose.winkLeft}", pose.winkLeft.within(0f, 1f))
            assertTrue("$where: glitch ${pose.glitch}", pose.glitch.within(0f, 1f))
            assertTrue("$where: glow ${pose.glow}", pose.glow.within(0f, 10f))
            assertTrue("$where: size ${pose.grow}", pose.grow > 0f && pose.grow < 3f)
            pose.look?.let { (x, y) ->
                assertTrue("$where: looks to ($x, $y), off the screen", x.within(-1f, 1f) && y.within(-1f, 1f))
            }
            pose.spark?.let { (x, y, b) ->
                assertTrue("$where: spark at ($x, $y)", x in -1.2f..1.2f && y in -1.2f..1.2f)
                assertTrue("$where: spark brightness $b", b.within(0f, 1f))
            }
            // The neck: an expression nudges the head, it shouldn't swing it round
            assertTrue("$where: neck turn ${pose.neckPan}°", pose.neckPan in -40f..40f)
            assertTrue("$where: neck tilt ${pose.neckTilt}°", pose.neckTilt in -30f..30f)
        }
    }
}
