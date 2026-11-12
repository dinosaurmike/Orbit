package com.example.companion2

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Orbit's screen sets up its parts one by one when it starts (settings, then Gemini, then the
// neck...). Using a part before it's set up crashes the app on every launch, before anything is
// shown. This reads MainActivity's start-up and checks that nothing is used too early, either
// directly or through a helper it calls straight away.
class StartupOrderTest {
    private val source = File("src/main/java/com/example/companion2/MainActivity.kt").readText()
    private val lines = source.lines()
    private val parts = Regex("""lateinit var (\w+)""").findAll(source).map { it.groupValues[1] }.toList()
    private val start = lines.indexOfFirst { "override fun onCreate(" in it }
    private val end = (start + 1 until lines.size).first { lines[it] == "    }" }
    private val onCreate = lines.subList(start, end)

    private fun setUpAt(part: String) =
        onCreate.indexOfFirst { Regex("""^\s*$part\s*=\s""").containsMatchIn(it) }

    private fun uses(text: String, part: String) = Regex("""\b$part\b""").containsMatchIn(text)

    // A helper's own text: from its declaration to its closing brace (or its one expression)
    private fun helper(name: String): String? {
        val at = source.indexOf("private fun $name(").takeIf { it >= 0 } ?: return null
        val close = source.indexOf("\n    }\n", at).let { if (it < 0) source.length else it }
        val blank = source.indexOf("\n\n", at).let { if (it < 0) source.length else it }
        val firstLine = source.substring(at, source.indexOf('\n', at))
        return source.substring(at, if (firstLine.trimEnd().endsWith("=")) blank else close)
    }

    @Test
    fun foundTheStartUp() {
        assertTrue(start >= 0 && parts.contains("settings") && setUpAt("settings") > 0)
    }

    @Test
    fun nothingIsUsedBeforeItsSetUp() {
        val problems = mutableListOf<String>()
        for (part in parts) {
            val ready = setUpAt(part).takeIf { it >= 0 } ?: continue
            onCreate.take(ready).forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (uses(code, part)) problems += "line ${start + i + 1} uses $part before it's set up"
                // A helper called right here (not inside { ... } that runs later)
                Regex("""^\s*(\w+)\(.*\)\s*$""").find(code)?.groupValues?.get(1)?.let { name ->
                    val body = helper(name) ?: return@let
                    if (uses(body, part) && "::$part.isInitialized" !in body) {
                        problems += "line ${start + i + 1} calls $name(), which uses $part before it's set up"
                    }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
