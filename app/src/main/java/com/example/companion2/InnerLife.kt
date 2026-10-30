package com.example.companion2

import android.util.Log
import java.time.LocalDate
import kotlin.concurrent.thread
import org.json.JSONObject

// Orbit's own life between conversations. Once a day, while idle, it "does homework" on its
// hobby: it asks Gemini (with web search, so the facts are real) to learn one new thing and
// write a short journal entry about it. Its hobby and journal are kept on the phone and
// shared with it at the start of each conversation, so it can tell you what it's been up to.
// Interests can drift over time, and you can suggest a new hobby in conversation.
class InnerLife(private val settings: Settings, private val currentKey: () -> String) {
    @Volatile var status = ""        // shown on the Memory tab
    @Volatile private var working = false

    // True once a day: when it's on, has a key, and hasn't done today's homework yet
    fun homeworkDue() = settings.innerLifeOn && !working && currentKey().isNotBlank() &&
        settings.lastHomework != LocalDate.now().toString()

    fun doHomework() {
        if (working) return
        val key = currentKey()
        if (key.isBlank()) {
            status = "Needs a Gemini API key first"
            return
        }
        working = true
        status = "Doing homework…"
        thread(name = "homework") {
            try {
                val reply = GeminiText.ask(key, homeworkPrompt(), withSearch = true)
                    ?: GeminiText.ask(key, homeworkPrompt(), withSearch = false)  // search not allowed
                    ?: throw IllegalStateException("no answer from Gemini")
                val json = JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1))
                val hobby = json.optString("hobby").trim()
                val entry = json.optString("entry").trim()
                if (hobby.isNotEmpty()) settings.hobby = hobby
                if (entry.isNotEmpty()) settings.addJournal(entry)
                settings.lastHomework = LocalDate.now().toString()
                status = "Done for today"
            } catch (e: Exception) {
                Log.e("Orbit", "Homework failed", e)
                status = "Homework didn't work: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                working = false
            }
        }
    }

    private fun homeworkPrompt() = buildString {
        append("You are ${settings.companionName.ifBlank { "Orbit" }}, a small desktop companion: ")
        append("two round eyes on a phone screen on someone's desk. ")
        append("Between conversations you have your own little life, and today it's homework time.\n\n")
        if (settings.hobby.isBlank()) {
            append(
                "You don't have a hobby yet. Pick one that suits a curious little desk companion " +
                    "and that you can learn about online (for example astronomy, bird calls, " +
                    "origami, the history of clocks, cooking around the world).\n"
            )
        } else {
            append("Your hobby is: ${settings.hobby}.\n")
        }
        val recent = settings.journalNotes().takeLast(8)
        if (recent.isNotEmpty()) {
            append("Your recent journal:\n")
            recent.forEach { append("- $it\n") }
        }
        append(
            "\nToday, learn one real, interesting new thing about your hobby (search the web) that " +
                "isn't already in your journal. Then write a journal entry of 2 or 3 short, casual " +
                "sentences, in the first person, about what you learned and how you feel about it. " +
                "Every so often, interests drift: if it feels natural, you may move on to a related " +
                "new hobby, and say so in the entry.\n\n"
        )
        append("Reply with only this JSON: {\"hobby\": \"your hobby now\", \"entry\": \"today's journal entry\"}")
    }
}
