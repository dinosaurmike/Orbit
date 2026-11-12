package com.example.companion2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// GLaDOS mode: hanging upside down from a shelf or the ceiling
class HangingTest {
    @Test
    fun hangingTurnsBothServosOver() {
        // Standing: a look 10° to one side moves the servo 10° that way
        assertEquals(145f, Neck.servoAngle(135f, 10f, flip = false, hanging = false), 0.001f)
        // Hanging: the same look moves it the other way (from below, its turns are mirrored)
        assertEquals(125f, Neck.servoAngle(135f, 10f, flip = false, hanging = true), 0.001f)
        // A neck already flipped by its own setting stays right when hung up
        assertEquals(125f, Neck.servoAngle(135f, 10f, flip = true, hanging = false), 0.001f)
        assertEquals(145f, Neck.servoAngle(135f, 10f, flip = true, hanging = true), 0.001f)
        // Straight ahead is straight ahead either way
        assertEquals(90f, Neck.servoAngle(90f, 0f, flip = false, hanging = true), 0.001f)
    }

    @Test
    fun onTheDeskTheBackCameraLooksUpForTheScreenFirst() {
        // Face down (positive) points the back camera up, where a screen usually is
        val (rows, heights) = searchTilts(hanging = false, usingBack = true, up = 15f, down = 25f)
        assertTrue(rows.first() > 0f)
        assertEquals(25f, heights.first(), 0.001f)
    }

    @Test
    fun hangingTheCameraInUseLooksDownForTheScreenFirst() {
        // Back camera: face up (negative) points it down
        val (backRows, backHeights) = searchTilts(hanging = true, usingBack = true, up = 15f, down = 25f)
        assertTrue(backRows.first() < 0f)
        assertEquals(-15f, backHeights.first(), 0.001f)
        // Front camera: face down points it down
        val (frontRows, frontHeights) = searchTilts(hanging = true, usingBack = false, up = 15f, down = 25f)
        assertTrue(frontRows.first() > 0f)
        assertEquals(25f, frontHeights.first(), 0.001f)
    }

    @Test
    fun everySearchTiltIsWithinTheNecksLimits() {
        for (hanging in listOf(false, true)) for (back in listOf(false, true)) {
            val (rows, heights) = searchTilts(hanging, back, up = 15f, down = 25f)
            for (t in rows + heights) assertTrue("$t", t >= -15f && t <= 25f)
            assertTrue(0f in rows)  // a level row too, for a screen at eye height
        }
    }
}
