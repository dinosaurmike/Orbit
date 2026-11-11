package com.example.companion2

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNewsTest {
    private fun news(kind: String, message: String = "", source: String = "") =
        AgentEvent(1, kind, message, source, "")

    @After
    fun clear() {
        AgentNews.takeAll()
    }

    @Test
    fun oneThingSaysWhoAndWhat() {
        assertEquals("Codex is done: All 42 tests pass.", AgentNews.spoken(listOf(news("done", "All 42 tests pass.", "Codex")), false))
        // A question keeps its question mark, with no full stop after it
        assertEquals("Cursor needs you: Which database?", AgentNews.spoken(listOf(news("needs_input", "Which database?", "Cursor")), false))
        assertEquals("Codex is done: Shipped!", AgentNews.spoken(listOf(news("done", "Shipped!", "Codex")), false))
        assertEquals("Codex is done: Tests pass.", AgentNews.spoken(listOf(news("done", "Tests pass", "Codex")), false))
        assertEquals("Your agent hit a problem.", AgentNews.spoken(listOf(news("failed")), false))
        assertEquals("While you were away: Antigravity is done.", AgentNews.spoken(listOf(news("done", source = "Antigravity")), true))
    }

    @Test
    fun severalPutWhatNeedsYouFirst() {
        val said = AgentNews.spoken(
            listOf(
                news("done", "Refactor finished", "Codex"),
                news("info", "FYI", "Script"),
                news("needs_input", "Pick a name", "Cursor"),
                news("failed", "Deploy failed", "CI")
            ),
            true
        )
        assertTrue(said, said.startsWith("While you were away: Cursor needs you: Pick a name."))
        assertTrue(said, said.contains("CI hit a problem."))
        assertTrue(said, said.contains("Codex is done."))
        assertTrue(said, said.endsWith("And 1 more."))
        assertFalse(said, said.contains("Refactor finished"))  // details only for the one that needs you
    }

    @Test
    fun needsYouGlowsUntilTold() {
        assertFalse(AgentNews.needsYou())
        AgentNews.add(news("done"))
        assertFalse(AgentNews.needsYou())
        AgentNews.add(news("needs_input", "Which one?"))
        assertTrue(AgentNews.needsYou())
        assertEquals(2, AgentNews.count())
        assertTrue(AgentNews.firstWaitingSince > 0)
        assertEquals(2, AgentNews.takeAll().size)
        assertFalse(AgentNews.needsYou())
        assertEquals(0, AgentNews.count())
        assertEquals(0L, AgentNews.firstWaitingSince)
    }

    @Test
    fun keepsAtMostTwenty() {
        repeat(25) { AgentNews.add(news("info", "n$it")) }
        val kept = AgentNews.takeAll()
        assertEquals(20, kept.size)
        assertEquals("n5", kept.first().message)  // the oldest go first
    }
}
