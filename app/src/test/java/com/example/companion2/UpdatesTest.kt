package com.example.companion2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun comparesVersionNumbersNotText() {
        assertTrue(Updates.isNewer("0.1.1", "0.1.0"))
        assertTrue(Updates.isNewer("v0.2.0", "0.1.9"))
        assertTrue(Updates.isNewer("0.1.10", "0.1.9"))  // 10 > 9, though "10" < "9" as text
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        assertFalse(Updates.isNewer("0.1.0", "0.1.0"))
        assertFalse(Updates.isNewer("0.1.0", "0.1.1"))
        assertFalse(Updates.isNewer("1.0.0", "1.0"))
    }

    @Test
    fun oddVersionsNeverCountAsNewer() {
        assertFalse(Updates.isNewer("", "0.1.0"))
        assertFalse(Updates.isNewer("nightly", "0.1.0"))
        assertFalse(Updates.isNewer("0.2.0-beta", "0.1.0"))
        assertNull(Updates.parse("1.2.x"))
        assertEquals(listOf(0, 1, 2), Updates.parse("v0.1.2"))
    }
}
