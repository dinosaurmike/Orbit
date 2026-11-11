package com.example.companion2

import org.junit.Assert.assertTrue
import org.junit.Test

// Orbit must never make things up. These guard the rules that tell Gemini so, so they can't be
// dropped or watered down by accident.
class TruthfulTest {
    @Test
    fun theHonestyRuleSaysNeverGuessAndHowToSaySo() {
        for (phrase in listOf("Never guess, invent, assume", "I don't know", "I can't quite tell",
            "Can you give me some clues?", "always better than a guess", "matters more than being helpful")) {
            assertTrue("TRUTHFUL lost: $phrase", TRUTHFUL.contains(phrase))
        }
    }

    @Test
    fun everyPhotoComesWithTheRules() {
        for (phrase in listOf("only what you can clearly see", "Never guess or invent", "never fill in",
            "say exactly that", "ask for help")) {
            assertTrue("PHOTO_RULES lost: $phrase", PHOTO_RULES.contains(phrase, ignoreCase = true))
        }
    }
}
