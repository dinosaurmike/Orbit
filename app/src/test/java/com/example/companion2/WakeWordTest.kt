package com.example.companion2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun orbitsOwnNamesUseTheFastEar() {
        assertTrue(onlyOrbitNames("orbit, orby"))
        assertTrue(onlyOrbitNames(" Orbit "))
        assertTrue(onlyOrbitNames("orbit,or-bee,orbie"))
    }

    @Test
    fun otherWakeWordsUseTheSpeechRecognizer() {
        assertFalse(onlyOrbitNames("hey computer"))
        assertFalse(onlyOrbitNames("orbit, buddy"))  // one unknown word: the fast ear can't hear it
        assertFalse(onlyOrbitNames(""))
        assertFalse(onlyOrbitNames(" , "))
    }

    @Test
    fun theSpeechRecognizerListensForSoundAlikes() {
        val phrases = wakePhrases("Orbit, orby")
        assertTrue("orbit" in phrases)
        assertTrue("or bit" in phrases)
        assertTrue("or bee" in phrases)
        assertEquals(phrases.size, phrases.distinct().size)
        assertEquals(listOf("hey computer"), wakePhrases("Hey Computer"))
    }
}
