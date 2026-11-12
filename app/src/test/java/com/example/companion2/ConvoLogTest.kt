package com.example.companion2

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConvoLogTest {
    private val dir = Files.createTempDirectory("convolog").toFile()

    @Before
    fun start() {
        ConvoLog.enabled = true
        ConvoLog.init(dir)
        ConvoLog.clear()
    }

    @After
    fun done() {
        ConvoLog.clear()
        dir.deleteRecursively()
    }

    @Test
    fun piecesOfWordsJoinIntoLines() {
        ConvoLog.begin("You said its name")
        ConvoLog.heard(" What's on")
        ConvoLog.heard(" my desk?")
        ConvoLog.tool("look", """{"where":"down"}""")
        ConvoLog.photo("This is the photo you just took (120 KB)")
        ConvoLog.said(" I can't quite")
        ConvoLog.said(" tell from here.")
        ConvoLog.end("Bye for now")
        val c = ConvoLog.recent().single()
        assertEquals("You said its name", c.reason)
        assertEquals(listOf("heard", "tool", "photo", "said", "end"), c.lines.map { it.kind })
        assertEquals("What's on my desk?", c.lines[0].text)
        assertEquals("I can't quite tell from here.", c.lines[3].text)
        val text = ConvoLog.asText(c)
        assertTrue(text, text.contains("Heard: What's on my desk?"))
        assertTrue(text, text.contains("""Tool: look {"where":"down"}"""))
        assertTrue(text, text.contains("Ended: Bye for now"))
    }

    @Test
    fun keepsTheLastTenAndSurvivesARestart() {
        repeat(13) { i ->
            ConvoLog.begin("Conversation $i", at = 1000L + i)
            ConvoLog.said("Hello $i")
            ConvoLog.end("done")
        }
        ConvoLog.init(dir)  // like the app starting again
        val recent = ConvoLog.recent()
        assertEquals(ConvoLog.KEEP, recent.size)
        assertEquals("Conversation 12", recent.first().reason)  // newest first
        assertEquals("Conversation 3", recent.last().reason)
        assertEquals("Hello 12", recent.first().lines.first().text)
    }

    @Test
    fun offMeansNothingIsKept() {
        ConvoLog.enabled = false
        ConvoLog.begin("You tapped it")
        ConvoLog.heard("hi")
        ConvoLog.end("bye")
        assertTrue(ConvoLog.recent().isEmpty())
        assertTrue(!java.io.File(dir, "conversation_log.json").exists())
    }

    @Test
    fun aDamagedFileStartsAFreshLog() {
        java.io.File(dir, "conversation_log.json").writeText("{not json")
        ConvoLog.init(dir)
        assertTrue(ConvoLog.recent().isEmpty())
    }
}
